#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef PROGRAMS_DIR
#define PROGRAMS_DIR "PROGRAMS"
#endif

extern pthread_mutex_t screenMutex;
extern uint8_t lastErrorCode;
extern uint8_t programRunStop;
extern uint8_t temporaryInformation;
extern uint16_t currentLocalStepNumber;
extern uint16_t currentProgramNumber;
extern uint16_t numberOfPrograms;

extern void runFunction(int16_t func);
extern void reallyRunFunction(int16_t func, uint16_t param);
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern calcRegister_t findNamedLabel(const char *labelName);
extern void reallocateRegister(calcRegister_t regist, uint32_t dataType,
                               uint16_t dataSizeWithoutDataLenBlocks,
                               uint32_t tag);
extern bool_t lcd_buffer_pixel_on(uint32_t x, uint32_t y);

// FNV-1a hash of the final LCD bitmap, read pixel-by-pixel through the same
// lcd_buffer_pixel_on path the screen dump uses (so it ignores the packed
// buffer's row-header bytes). Plotting fixtures leave a deterministic image
// rather than a scalar in the X register, so this gives them a result oracle the
// X-register check cannot express.
static uint64_t compute_display_hash(void) {
  uint64_t hash = 1469598103934665603ull;  // FNV-1a 64-bit offset basis
  for (uint32_t y = 0; y < SCREEN_HEIGHT; ++y) {
    for (uint32_t x = 0; x < SCREEN_WIDTH; ++x) {
      hash ^= (uint64_t)(lcd_buffer_pixel_on(x, y) ? 1u : 0u);
      hash *= 1099511628211ull;  // FNV-1a 64-bit prime
    }
  }
  return hash;
}

typedef struct {
  int16_t func_id;
  uint16_t param;
  bool uses_param;
  volatile bool done;
} function_worker_t;

typedef struct {
  unsigned int firings;
} timeout_probe_t;

typedef enum {
  STOP_POLICY_NONE = 0,
  STOP_POLICY_DIRECT_AFTER_ACTIVITY,
} stop_policy_t;

typedef enum {
  WORKLOAD_SOURCE_PROGRAM_FILE = 0,
  WORKLOAD_SOURCE_GLOBAL_LABEL,
} workload_source_t;

typedef struct {
  const char *program_name;
  workload_source_t source;
  uint64_t timeout_ms;
  bool resume_pause_with_zero_key;
  bool (*seed_runtime)(void);
  stop_policy_t stop_policy;
  uint64_t stop_after_activity_ms;
  // Numeric oracle (REPORT-24 Milestone 3 / W7, hardened in §37): the expected
  // sequence of non-negative integers the X register must contain after the
  // program runs to completion. The comparison ignores display chrome (the
  // vector arrow, decimal points, commas, and whitespace), so an upstream
  // change to how a result is formatted does not break a correct result -- only
  // a changed result does. NULL stays liveness-only. Only set this for fixtures
  // whose result is independently verifiable and that finish (not interrupted)
  // leaving that integer vector in X.
  const int *expected_x_sequence;
  size_t expected_x_sequence_len;
  // Display-hash oracle for plotting fixtures: the FNV-1a hash of the final LCD
  // bitmap (compute_display_hash). Plotting workloads (BinetV3, GudrmPL) leave a
  // deterministic image, not a scalar in X, so this pins their result where the
  // X-register oracle cannot. 0 stays liveness-only. Only set for fixtures that
  // finish (not interrupted) and whose final image is run-to-run deterministic,
  // verified by repeated runs -- see REPORT-25 Annex A.10.
  uint64_t expected_display_hash;
} program_fixture_scenario_t;

typedef enum {
  WORKLOAD_RESULT_PASS = 0,
  WORKLOAD_RESULT_FAIL,
  WORKLOAD_RESULT_STOP_TIMEOUT,
} workload_result_t;

extern void fnStopProgram(uint16_t unusedButMandatoryParameter);

static void usage(const char *argv0) {
  fprintf(stderr,
          "Usage: %s --program-root <dir> [--program-name <workload>]\n",
          argv0);
}

static uint64_t monotonic_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static uint64_t resolve_program_timeout_ms(uint64_t default_timeout_ms) {
  const char *scale_text = getenv("HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE");
  char *end = NULL;
  unsigned long long scale = 1u;

  if (scale_text == NULL || scale_text[0] == '\0') {
    return default_timeout_ms;
  }

  errno = 0;
  scale = strtoull(scale_text, &end, 10);
  if (errno != 0 || end == scale_text || *end != '\0' || scale == 0u) {
    fprintf(stderr,
            "WARN: Ignoring invalid HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE=%s\n",
            scale_text);
    return default_timeout_ms;
  }

  if (default_timeout_ms > UINT64_MAX / (uint64_t)scale) {
    return UINT64_MAX;
  }

  return default_timeout_ms * (uint64_t)scale;
}

static bool ensure_directory(const char *path) {
  struct stat st;
  if (stat(path, &st) == 0) {
    return S_ISDIR(st.st_mode);
  }
  return mkdir(path, 0777) == 0;
}

static bool create_runtime_directory(char *buffer, size_t buffer_size) {
  const char *tmp_root = getenv("TMPDIR");
  if (tmp_root == NULL || tmp_root[0] == '\0') {
    tmp_root = "/tmp";
  }

  snprintf(buffer, buffer_size, "%s/r47-workload-regressions-XXXXXX", tmp_root);
  return mkdtemp(buffer) != NULL;
}

static bool copy_file(const char *source_path, const char *destination_path) {
  FILE *source = fopen(source_path, "rb");
  FILE *destination = NULL;
  char buffer[8192];

  if (source == NULL) {
    fprintf(stderr, "ERROR: Failed to open %s: %s\n", source_path,
            strerror(errno));
    return false;
  }

  destination = fopen(destination_path, "wb");
  if (destination == NULL) {
    fprintf(stderr, "ERROR: Failed to open %s: %s\n", destination_path,
            strerror(errno));
    fclose(source);
    return false;
  }

  for (;;) {
    size_t bytes_read = fread(buffer, 1, sizeof(buffer), source);
    if (bytes_read > 0 &&
        fwrite(buffer, 1, bytes_read, destination) != bytes_read) {
      fprintf(stderr, "ERROR: Failed to write %s: %s\n", destination_path,
              strerror(errno));
      fclose(source);
      fclose(destination);
      return false;
    }
    if (bytes_read < sizeof(buffer)) {
      if (ferror(source)) {
        fprintf(stderr, "ERROR: Failed to read %s: %s\n", source_path,
                strerror(errno));
        fclose(source);
        fclose(destination);
        return false;
      }
      break;
    }
  }

  fclose(source);
  fclose(destination);
  return true;
}

static bool stage_program_file(const char *runtime_dir, const char *program_root,
                               const char *program_name) {
  char source_path[PATH_MAX];
  char program_dir[PATH_MAX];
  char destination_path[PATH_MAX];

  snprintf(source_path, sizeof(source_path), "%s/%s", program_root,
           program_name);
  snprintf(program_dir, sizeof(program_dir), "%s/%s", runtime_dir,
           PROGRAMS_DIR);
  snprintf(destination_path, sizeof(destination_path), "%s/program.p47",
           program_dir);

  if (!ensure_directory(program_dir)) {
    fprintf(stderr, "ERROR: Failed to prepare runtime program dir %s\n",
            program_dir);
    return false;
  }

  return copy_file(source_path, destination_path);
}

static void *run_function_worker(void *user_data) {
  function_worker_t *worker = (function_worker_t *)user_data;
  pthread_mutex_lock(&screenMutex);
  if (worker->uses_param) {
    reallyRunFunction(worker->func_id, worker->param);
  } else {
    runFunction(worker->func_id);
  }
  pthread_mutex_unlock(&screenMutex);
  worker->done = true;
  return NULL;
}

static bool start_worker(function_worker_t *worker, int16_t func_id,
                         uint16_t param, bool uses_param,
                         pthread_t *thread_id) {
  worker->func_id = func_id;
  worker->param = param;
  worker->uses_param = uses_param;
  worker->done = false;
  if (pthread_create(thread_id, NULL, run_function_worker, worker) != 0) {
    fprintf(stderr, "ERROR: Failed to start worker thread\n");
    return false;
  }
  return true;
}

static bool finish_worker(pthread_t thread_id) {
  return pthread_join(thread_id, NULL) == 0;
}

static bool fail_last_error(const char *scenario_name) {
  if (lastErrorCode == ERROR_NONE) {
    return false;
  }

  fprintf(stderr, "ERROR: %s hit calculator error code %u\n", scenario_name,
          (unsigned int)lastErrorCode);
  return true;
}

static gboolean timeout_probe_callback(gpointer data) {
  timeout_probe_t *probe = (timeout_probe_t *)data;
  probe->firings++;
  return probe->firings < 3u;
}

static bool run_android_wait_shim_probe(void) {
  timeout_probe_t probe = {0};
  guint timer_id = g_timeout_add(5u, timeout_probe_callback, &probe);
  bool saw_pending = false;
  uint64_t deadline = monotonic_ms() + 1000u;

  if (timer_id == 0u) {
    fprintf(stderr, "ERROR: android wait shim failed to register timer\n");
    return false;
  }

  while (probe.firings < 3u && monotonic_ms() < deadline) {
    if (gtk_events_pending()) {
      saw_pending = true;
    }
    g_main_context_iteration(NULL, TRUE);
  }

  if (probe.firings < 3u) {
    fprintf(stderr,
            "ERROR: android wait shim timed out before three timer firings\n");
    return false;
  }
  if (!saw_pending) {
    fprintf(stderr,
            "ERROR: android wait shim never reported pending GTK work\n");
    return false;
  }

  return true;
}

static bool seed_spiralk_runtime_registers(void) {
  reallocateRegister(REGISTER_J, dtReal34, 0, amNone);
  stringToReal34("2", REGISTER_REAL34_DATA(REGISTER_J));
  return true;
}

// NQueens.p47 reads the board size N from X and, with no input, only parks at
// its prompt (the unseeded run yields a default, not a result). Seeding N = 8
// drives a full 8-queens search that runs to completion and leaves the solution
// in X as a string, giving a controlled-input numeric oracle (REPORT-24
// Milestone 3): the expected result below is an independently verified valid
// 8-queens solution (a permutation with no shared row, column, or diagonal).
static bool seed_nqueens_n8_input(void) {
  reallocateRegister(REGISTER_X, dtReal34, 0, amNone);
  stringToReal34("8", REGISTER_REAL34_DATA(REGISTER_X));
  return true;
}

// Extract the sequence of non-negative integers embedded in a value string,
// ignoring every non-digit character (display chrome such as the type tag, the
// vector arrow, decimal points, commas, and whitespace). Returns the count
// written to out, capped at max.
static size_t extract_int_sequence(const char *s, long *out, size_t max) {
  size_t count = 0;
  const char *p = s;

  while (*p != '\0' && count < max) {
    if (*p >= '0' && *p <= '9') {
      long value = 0;
      while (*p >= '0' && *p <= '9') {
        value = value * 10 + (long)(*p - '0');
        p++;
      }
      out[count++] = value;
    } else {
      p++;
    }
  }

  return count;
}

// Render the X register into a stable, type-tagged comparison string so a
// fixture's numeric result can be asserted, not just its liveness. Mirrors the
// upstream testSuite printRegisterToString for the two result types these
// fixtures produce (long integer counts, real34 values); other types fall back
// to their type name so a result-type change is still visible.
static void read_x_register_value_string(char *out, size_t out_len) {
  uint32_t data_type = getRegisterDataType(REGISTER_X);

  if (data_type == dtLongInteger) {
    longInteger_t lgInt;
    char digits[3000];

    convertLongIntegerRegisterToLongInteger(REGISTER_X, lgInt);
    longIntegerToAllocatedString(lgInt, digits, (int32_t)sizeof(digits));
    longIntegerFree(lgInt);
    snprintf(out, out_len, "longint:%s", digits);
  } else if (data_type == dtReal34) {
    char str[1000];

    real34ToString(REGISTER_REAL34_DATA(REGISTER_X), str);
    snprintf(out, out_len, "real34:%s", str);
  } else if (data_type == dtString) {
    char str[2000];

    stringToUtf8(REGISTER_STRING_DATA(REGISTER_X), (uint8_t *)str);
    snprintf(out, out_len, "string:%s", str);
  } else {
    snprintf(out, out_len, "type:%s",
             getRegisterDataTypeName(REGISTER_X, false, false));
  }
}

static workload_result_t run_program_fixture_workload(
  const char *runtime_dir, const char *program_root,
  const program_fixture_scenario_t *scenario) {
  function_worker_t worker;
  pthread_t worker_thread;
  uint64_t activity_started_at = 0;
  bool saw_pause = false;
  bool saw_waiting = false;
  bool saw_view = false;
  bool saw_lcd_refresh = false;
  bool requested_direct_stop = false;
  bool sent_resume_key = false;
  bool uses_param = false;
  calcRegister_t label = INVALID_VARIABLE;
  uint64_t last_direct_stop_request_at = 0;
  uint16_t load_step = 0;
  uint16_t max_step = 0;
  uint16_t run_parameter = NOPARAM;
  uint64_t deadline = 0;
  uint64_t direct_stop_requests = 0;
  uint64_t lcd_refresh_count = 0;
  uint64_t timeout_ms = resolve_program_timeout_ms(scenario->timeout_ms);
  int16_t run_function_id = ITM_RS;

  if (scenario->source == WORKLOAD_SOURCE_PROGRAM_FILE) {
    if (!stage_program_file(runtime_dir, program_root, scenario->program_name)) {
      return WORKLOAD_RESULT_FAIL;
    }
  }

  r47_init_runtime(0);
  if (scenario->seed_runtime != NULL && !scenario->seed_runtime()) {
    fprintf(stderr, "ERROR: %s workload failed to seed runtime state\n",
            scenario->program_name);
    return WORKLOAD_RESULT_FAIL;
  }

  if (scenario->source == WORKLOAD_SOURCE_PROGRAM_FILE) {
    fnLoadProgram(NOPARAM);
    if (fail_last_error(scenario->program_name)) {
      return WORKLOAD_RESULT_FAIL;
    }
    if (numberOfPrograms == 0u) {
      fprintf(stderr, "ERROR: %s workload did not load any programs\n",
              scenario->program_name);
      return WORKLOAD_RESULT_FAIL;
    }
  } else {
    label = findNamedLabel(scenario->program_name);
    if (label == INVALID_VARIABLE) {
      fprintf(stderr, "ERROR: %s workload could not resolve built-in label\n",
              scenario->program_name);
      return WORKLOAD_RESULT_FAIL;
    }
    run_function_id = ITM_XEQ;
    run_parameter = label;
    uses_param = true;
  }

  load_step = currentLocalStepNumber;
  max_step = currentLocalStepNumber;
  r47_reset_host_lcd_refresh_count();
  if (!start_worker(&worker, run_function_id, run_parameter, uses_param,
                    &worker_thread)) {
    return WORKLOAD_RESULT_FAIL;
  }

  deadline = monotonic_ms() + timeout_ms;
  while (!worker.done) {
    uint64_t now = monotonic_ms();

    if (now >= deadline) {
      break;
    }

    if (fail_last_error(scenario->program_name)) {
      return WORKLOAD_RESULT_FAIL;
    }

    if (currentLocalStepNumber > max_step) {
      max_step = currentLocalStepNumber;
    }
    saw_view = saw_view || temporaryInformation == TI_VIEW_REGISTER;
    saw_waiting = saw_waiting || programRunStop == PGM_WAITING;
    lcd_refresh_count = r47_get_host_lcd_refresh_count();
    saw_lcd_refresh = saw_lcd_refresh || lcd_refresh_count > 0u;

    if (programRunStop == PGM_PAUSED) {
      saw_pause = true;
      if (scenario->resume_pause_with_zero_key && !sent_resume_key) {
        r47_send_sim_key("00", false, false);
        usleep(20000);
        r47_send_sim_key("00", false, true);
        sent_resume_key = true;
      }
    }

    if ((max_step > load_step || saw_pause || saw_waiting || saw_view ||
         saw_lcd_refresh) &&
        activity_started_at == 0u) {
      activity_started_at = now;
    }

    if (scenario->stop_policy == STOP_POLICY_DIRECT_AFTER_ACTIVITY &&
        activity_started_at != 0u &&
        now - activity_started_at >= scenario->stop_after_activity_ms &&
        now - last_direct_stop_request_at >= 250u &&
        (programRunStop == PGM_RUNNING || programRunStop == PGM_PAUSED)) {
      fnStopProgram(0);
      requested_direct_stop = true;
      last_direct_stop_request_at = now;
      direct_stop_requests++;
    }

    usleep(5000);
  }

  if (!worker.done) {
    if (scenario->stop_policy == STOP_POLICY_DIRECT_AFTER_ACTIVITY &&
        activity_started_at != 0u) {
      fprintf(stderr,
              "WARN: %s bounded interrupt timed out "
              "(timeoutMs=%llu, directStop=%s, directStopRequests=%llu, "
              "runStop=%u)\n",
              scenario->program_name,
              (unsigned long long)timeout_ms,
              requested_direct_stop ? "yes" : "no",
              (unsigned long long)direct_stop_requests,
              (unsigned int)programRunStop);
      return WORKLOAD_RESULT_STOP_TIMEOUT;
    }
    fprintf(stderr,
            "ERROR: %s workload timed out (timeoutMs=%llu)\n",
            scenario->program_name,
            (unsigned long long)timeout_ms);
    return WORKLOAD_RESULT_FAIL;
  }
  if (!finish_worker(worker_thread)) {
    fprintf(stderr, "ERROR: Failed to join %s worker thread\n",
            scenario->program_name);
    return WORKLOAD_RESULT_FAIL;
  }

  saw_pause = saw_pause || programRunStop == PGM_PAUSED;
  saw_waiting = saw_waiting || programRunStop == PGM_WAITING;
  saw_view = saw_view || temporaryInformation == TI_VIEW_REGISTER;
  lcd_refresh_count = r47_get_host_lcd_refresh_count();
  saw_lcd_refresh = saw_lcd_refresh || lcd_refresh_count > 0u;

  if (max_step <= load_step && !saw_pause && !saw_waiting && !saw_view &&
      !saw_lcd_refresh) {
    fprintf(stderr,
            "ERROR: %s workload never showed run activity after load "
            "(load_step=%u, max_step=%u, tempInfo=%u, runStop=%u, lcdRefreshes=%llu)\n",
            scenario->program_name, (unsigned int)load_step,
            (unsigned int)max_step, (unsigned int)temporaryInformation,
            (unsigned int)programRunStop,
            (unsigned long long)r47_get_host_lcd_refresh_count());
    return WORKLOAD_RESULT_FAIL;
  }
  if (programRunStop == PGM_RUNNING || programRunStop == PGM_PAUSED) {
    fprintf(stderr,
            "ERROR: %s workload finished in an unexpected run state %u\n",
            scenario->program_name,
            (unsigned int)programRunStop);
    return WORKLOAD_RESULT_FAIL;
  }
  // No gmpMemInBytes leak check: this host build links the Android mini-gmp
  // fallback (android/compat/mini-gmp-fallback), whose gmp_free / gmp_xrealloc_
  // limbs pass size 0 to the core's freeGmp / reallocGmp accounting hooks. The
  // core's gmpMemInBytes counter therefore only ever increments and is monotonic
  // under mini-gmp, so a positive delta is an accounting artifact, not a leak
  // (the memory is freed correctly). Real allocation leaks are caught by the
  // ASan/UBSan sanitized workload lane, not by this counter. See
  // __DEV/issues/ISSUE-3-BINET.md Annex B.

  char x_value[3200];
  read_x_register_value_string(x_value, sizeof(x_value));
  fprintf(stderr, "INFO: %s X register = %s\n", scenario->program_name, x_value);
  if (scenario->expected_x_sequence != NULL) {
    long actual_sequence[64];
    size_t actual_len = extract_int_sequence(
        x_value, actual_sequence,
        sizeof(actual_sequence) / sizeof(actual_sequence[0]));

    bool sequence_matches = actual_len == scenario->expected_x_sequence_len;
    for (size_t i = 0; sequence_matches && i < actual_len; ++i) {
      if (actual_sequence[i] != (long)scenario->expected_x_sequence[i]) {
        sequence_matches = false;
      }
    }

    if (!sequence_matches) {
      fprintf(stderr,
              "ERROR: %s workload produced the wrong result: X=%s (parsed %zu "
              "integers; the verified solution has %zu)\n",
              scenario->program_name, x_value, actual_len,
              scenario->expected_x_sequence_len);
      return WORKLOAD_RESULT_FAIL;
    }
  }

  const uint64_t display_hash = compute_display_hash();
  fprintf(stderr, "INFO: %s display hash = 0x%016llx\n",
          scenario->program_name, (unsigned long long)display_hash);
  if (scenario->expected_display_hash != 0u &&
      display_hash != scenario->expected_display_hash) {
    fprintf(stderr,
            "ERROR: %s produced the wrong final display: hash=0x%016llx "
            "(expected 0x%016llx)\n",
            scenario->program_name, (unsigned long long)display_hash,
            (unsigned long long)scenario->expected_display_hash);
    return WORKLOAD_RESULT_FAIL;
  }

  fprintf(stderr,
          "PASS: %s workload loaded and ran (load_step=%u, max_step=%u, pause=%s, waiting=%s, view=%s, lcdRefreshes=%llu, directStop=%s, directStopRequests=%llu, x=%s)\n",
          scenario->program_name, (unsigned int)load_step,
          (unsigned int)max_step, saw_pause ? "yes" : "no",
          saw_waiting ? "yes" : "no", saw_view ? "yes" : "no",
          (unsigned long long)r47_get_host_lcd_refresh_count(),
          requested_direct_stop ? "yes" : "no",
          (unsigned long long)direct_stop_requests, x_value);
  return WORKLOAD_RESULT_PASS;
}

static const program_fixture_scenario_t kProgramFixtureScenarios[] = {
    {.program_name = "BinetV3.p47",
  .source = WORKLOAD_SOURCE_PROGRAM_FILE,
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u,
     // Verified run-to-run deterministic over repeated host runs (Annex A.10);
     // BinetV3 parks at its plot prompt leaving a stable final image. Re-pinned
     // for the upstream plot-rendering change: the numeric oracles (NQueens,
     // SPIRALk, MANSLV2) and GudrmPL were unchanged at that re-pin, so only this
     // plot image moved then; the value tracks the current upstream HEAD
     // (upstream reverted the 485b6709 render, so this hash returns to its
     // pre-485b6709 value).
     .expected_display_hash = 0x1ddff07951d1afb6ull},
    {.program_name = "GudrmPL.p47",
  .source = WORKLOAD_SOURCE_PROGRAM_FILE,
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u,
     // Verified run-to-run deterministic over repeated host runs (Annex A.10);
     // GudrmPL runs the Gudermannian plot to natural completion. Re-pinned for
     // the upstream real_t graph-bounds refactor (the window bounds x_min/x_max/
     // y_min/y_max retyped from float to real_t *const), which shifted this plot
     // image. The new hash reproduces identically on the dev host and the CI
     // runner, and the other fixtures (BinetV3, NQueens, SPIRALk, MANSLV2) are
     // unaffected, so only this plot image moved.
     .expected_display_hash = 0x25b097461cc5a4feull},
    {.program_name = "MANSLV2.p47",
  .source = WORKLOAD_SOURCE_PROGRAM_FILE,
     .timeout_ms = 15000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     // Liveness-only, by design: STOP_POLICY_DIRECT_AFTER_ACTIVITY interrupts the
     // run after sustained activity, so there is no completed state to assert --
     // neither an X-register sequence nor a final-image hash. This fixture proves
     // the bounded-interrupt path stays responsive (it stops on request without
     // hanging), not that it computes a particular result. NQueens and SPIRALk
     // both carry numeric value oracles (SPIRALk's final X is reproducible across
     // machines even though its plot image is not, see below); MANSLV2 is the
     // remaining known result-coverage gap because its direct-stop interrupt
     // leaves no completed state to assert.
     .stop_policy = STOP_POLICY_DIRECT_AFTER_ACTIVITY,
     .stop_after_activity_ms = 3000u},
    {.program_name = "NQueens.p47",
  .source = WORKLOAD_SOURCE_PROGRAM_FILE,
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = true,
     .seed_runtime = seed_nqueens_n8_input,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u,
     // Independently verified valid 8-queens solution (see seed_nqueens_n8_input).
     // Asserted as an integer sequence so an upstream change to the vector's
     // display format (e.g. the whitespace tightening in 5b925867) does not
     // break a still-correct result.
     .expected_x_sequence = (const int[]){8, 4, 1, 3, 6, 2, 7, 5},
     .expected_x_sequence_len = 8},
    {.program_name = "SPIRALk.p47",
  .source = WORKLOAD_SOURCE_PROGRAM_FILE,
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = true,
     .seed_runtime = seed_spiralk_runtime_registers,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u,
     // Runs to completion and leaves a deterministic long-integer result in X.
     // The final X (150) is reproducible across environments -- verified equal on
     // the dev host and on the CI runner (twice) -- so it now carries a value
     // oracle, upgrading SPIRALk from liveness-only. Its final plot IMAGE stays
     // un-oracled because it is NOT reproducible across machines: the number of
     // points plotted before it finishes depends on the pause/resume interleaving
     // (a pinned hash 0x8cfc1f2910613f3c locally failed CI with
     // 0xeae799e2c2ad6d93), so expected_display_hash stays 0 while the X result
     // gates the computed value. extract_int_sequence reads 150 from the
     // "longint:150" register string.
     .expected_x_sequence = (const int[]){150},
     .expected_x_sequence_len = 1,
     .expected_display_hash = 0u},
};

static const program_fixture_scenario_t *find_program_fixture_scenario(
    const char *program_name) {
  for (size_t index = 0;
       index < sizeof(kProgramFixtureScenarios) /
                   sizeof(kProgramFixtureScenarios[0]);
       ++index) {
    if (strcmp(kProgramFixtureScenarios[index].program_name, program_name) ==
        0) {
      return &kProgramFixtureScenarios[index];
    }
  }

  return NULL;
}

int main(int argc, char **argv) {
  const char *program_root = NULL;
  const char *program_name = NULL;
  char runtime_dir[PATH_MAX];

  for (int index = 1; index < argc; index++) {
    if (strcmp(argv[index], "--program-root") == 0 && index + 1 < argc) {
      program_root = argv[++index];
      continue;
    }
    if (strcmp(argv[index], "--program-name") == 0 && index + 1 < argc) {
      program_name = argv[++index];
      continue;
    }

    usage(argv[0]);
    return 1;
  }

  if (program_root == NULL) {
    usage(argv[0]);
    return 1;
  }
  if (!create_runtime_directory(runtime_dir, sizeof(runtime_dir))) {
    fprintf(stderr, "ERROR: Failed to create runtime directory\n");
    return 1;
  }

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

  if (!run_android_wait_shim_probe()) {
    return 1;
  }
  fprintf(stderr, "PASS: Android PC_BUILD wait/progress shim probe\n");

  if (program_name != NULL) {
    workload_result_t result;
    const program_fixture_scenario_t *scenario =
        find_program_fixture_scenario(program_name);

    if (scenario == NULL) {
      fprintf(stderr, "ERROR: Unknown host workload %s\n", program_name);
      return 1;
    }

    result = run_program_fixture_workload(runtime_dir, program_root, scenario);
    if (result == WORKLOAD_RESULT_STOP_TIMEOUT) {
      return 3;
    }
    return result == WORKLOAD_RESULT_PASS ? 0 : 1;
  }

  for (size_t index = 0;
       index < sizeof(kProgramFixtureScenarios) / sizeof(kProgramFixtureScenarios[0]);
       ++index) {
    workload_result_t result = run_program_fixture_workload(
        runtime_dir, program_root, &kProgramFixtureScenarios[index]);
    if (result == WORKLOAD_RESULT_STOP_TIMEOUT) {
      return 3;
    }
    if (result != WORKLOAD_RESULT_PASS) {
      return 1;
    }
  }

  return 0;
}
