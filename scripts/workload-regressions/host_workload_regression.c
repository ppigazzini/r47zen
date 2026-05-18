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
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern void reallocateRegister(calcRegister_t regist, uint32_t dataType,
                               uint16_t dataSizeWithoutDataLenBlocks,
                               uint32_t tag);

typedef struct {
  int16_t func_id;
  volatile bool done;
} function_worker_t;

typedef struct {
  unsigned int firings;
} timeout_probe_t;

typedef enum {
  STOP_POLICY_NONE = 0,
  STOP_POLICY_DIRECT_AFTER_ACTIVITY,
} stop_policy_t;

typedef struct {
  const char *program_name;
  uint64_t timeout_ms;
  bool resume_pause_with_zero_key;
  bool (*seed_runtime)(void);
  stop_policy_t stop_policy;
  uint64_t stop_after_activity_ms;
} program_fixture_scenario_t;

extern void fnStopProgram(uint16_t unusedButMandatoryParameter);

static void usage(const char *argv0) {
  fprintf(stderr, "Usage: %s --program-root <dir>\n", argv0);
}

static uint64_t monotonic_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
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
  runFunction(worker->func_id);
  pthread_mutex_unlock(&screenMutex);
  worker->done = true;
  return NULL;
}

static bool start_worker(function_worker_t *worker, int16_t func_id,
                         pthread_t *thread_id) {
  worker->func_id = func_id;
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

static bool run_program_fixture_workload(const char *runtime_dir,
                                         const char *program_root,
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
  uint64_t last_direct_stop_request_at = 0;
  uint16_t load_step = 0;
  uint16_t max_step = 0;
  uint64_t deadline = 0;
  uint64_t direct_stop_requests = 0;
  uint64_t lcd_refresh_count = 0;

  if (!stage_program_file(runtime_dir, program_root, scenario->program_name)) {
    return false;
  }

  r47_init_runtime(0);
  if (scenario->seed_runtime != NULL && !scenario->seed_runtime()) {
    fprintf(stderr, "ERROR: %s workload failed to seed runtime state\n",
            scenario->program_name);
    return false;
  }
  fnLoadProgram(NOPARAM);
  if (fail_last_error(scenario->program_name)) {
    return false;
  }
  if (numberOfPrograms == 0u) {
    fprintf(stderr, "ERROR: %s workload did not load any programs\n",
            scenario->program_name);
    return false;
  }

  load_step = currentLocalStepNumber;
  max_step = currentLocalStepNumber;
  r47_reset_host_lcd_refresh_count();
  if (!start_worker(&worker, ITM_RS, &worker_thread)) {
    return false;
  }

  deadline = monotonic_ms() + scenario->timeout_ms;
  while (!worker.done) {
    uint64_t now = monotonic_ms();

    if (now >= deadline) {
      break;
    }

    if (fail_last_error(scenario->program_name)) {
      return false;
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
    fprintf(stderr, "ERROR: %s workload timed out\n", scenario->program_name);
    return false;
  }
  if (!finish_worker(worker_thread)) {
    fprintf(stderr, "ERROR: Failed to join %s worker thread\n",
            scenario->program_name);
    return false;
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
    return false;
  }
  if (scenario->stop_policy == STOP_POLICY_DIRECT_AFTER_ACTIVITY &&
      !requested_direct_stop) {
    fprintf(stderr,
            "ERROR: %s workload finished before the maintained direct-stop probe ran\n",
            scenario->program_name);
    return false;
  }
  if (programRunStop == PGM_RUNNING || programRunStop == PGM_PAUSED) {
    fprintf(stderr,
            "ERROR: %s workload finished in an unexpected run state %u\n",
            scenario->program_name,
            (unsigned int)programRunStop);
    return false;
  }

  fprintf(stderr,
          "PASS: %s workload loaded and ran (load_step=%u, max_step=%u, pause=%s, waiting=%s, view=%s, lcdRefreshes=%llu, directStop=%s, directStopRequests=%llu)\n",
          scenario->program_name, (unsigned int)load_step,
          (unsigned int)max_step, saw_pause ? "yes" : "no",
          saw_waiting ? "yes" : "no", saw_view ? "yes" : "no",
          (unsigned long long)r47_get_host_lcd_refresh_count(),
          requested_direct_stop ? "yes" : "no",
          (unsigned long long)direct_stop_requests);
  return true;
}

static const program_fixture_scenario_t kProgramFixtureScenarios[] = {
    {.program_name = "BinetV3.p47",
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u},
    {.program_name = "GudrmPL.p47",
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u},
    {.program_name = "MANSLV2.p47",
     .timeout_ms = 15000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_DIRECT_AFTER_ACTIVITY,
     .stop_after_activity_ms = 3000u},
    {.program_name = "NQueens.p47",
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = false,
     .seed_runtime = NULL,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u},
    {.program_name = "SPIRALk.p47",
     .timeout_ms = 20000u,
     .resume_pause_with_zero_key = true,
     .seed_runtime = seed_spiralk_runtime_registers,
     .stop_policy = STOP_POLICY_NONE,
     .stop_after_activity_ms = 0u},
};

int main(int argc, char **argv) {
  const char *program_root = NULL;
  char runtime_dir[PATH_MAX];

  for (int index = 1; index < argc; index++) {
    if (strcmp(argv[index], "--program-root") == 0 && index + 1 < argc) {
      program_root = argv[++index];
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

  for (size_t index = 0;
       index < sizeof(kProgramFixtureScenarios) / sizeof(kProgramFixtureScenarios[0]);
       ++index) {
    if (!run_program_fixture_workload(runtime_dir, program_root,
                                      &kProgramFixtureScenarios[index])) {
      return 1;
    }
  }

  return 0;
}
