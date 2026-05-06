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
extern uint16_t currentLocalStepNumber;
extern uint16_t currentProgramNumber;
extern uint16_t numberOfPrograms;

extern void runFunction(int16_t func);
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern void reallocateRegister(calcRegister_t regist, uint32_t dataType,
                               uint16_t dataSizeWithoutDataLenBlocks,
                               uint32_t tag);
extern void setSystemFlag(unsigned int sf);
extern void clearSystemFlag(unsigned int sf);
extern char *getXRegisterString(void);
extern uint32_t getRegisterDataType(calcRegister_t regist);

typedef struct {
  int16_t func_id;
  volatile bool done;
} function_worker_t;

typedef struct {
  unsigned int firings;
} timeout_probe_t;

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
  runFunction(worker->func_id);
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

static bool run_spiralk_workload(const char *runtime_dir,
                                 const char *program_root) {
  function_worker_t worker;
  pthread_t worker_thread;
  bool saw_progress = false;
  bool saw_pause = false;
  bool sent_resume_key = false;
  uint16_t max_step = 0;
  uint64_t deadline = 0;

  if (!stage_program_file(runtime_dir, program_root, "SPIRALk.p47")) {
    return false;
  }

  r47_init_runtime(0);
  if (!seed_spiralk_runtime_registers()) {
    return false;
  }
  fnLoadProgram(NOPARAM);
  if (fail_last_error("SPIRALk load")) {
    return false;
  }
  if (numberOfPrograms == 0u) {
    fprintf(stderr, "ERROR: SPIRALk workload did not load any programs\n");
    return false;
  }

  max_step = currentLocalStepNumber;
  if (!start_worker(&worker, ITM_RS, &worker_thread)) {
    return false;
  }

  deadline = monotonic_ms() + 15000u;
  while (!worker.done && monotonic_ms() < deadline) {
    if (fail_last_error("SPIRALk")) {
      return false;
    }

    if (currentLocalStepNumber > max_step) {
      max_step = currentLocalStepNumber;
    }
    if (max_step >= 10u) {
      saw_progress = true;
    }

    if (programRunStop == PGM_PAUSED) {
      saw_pause = true;
      if (!sent_resume_key) {
        r47_send_sim_key("00", false, false);
        usleep(20000);
        r47_send_sim_key("00", false, true);
        sent_resume_key = true;
      }
    }

    usleep(5000);
  }

  if (!worker.done) {
    fprintf(stderr, "ERROR: SPIRALk workload timed out\n");
    return false;
  }
  if (!finish_worker(worker_thread)) {
    fprintf(stderr, "ERROR: Failed to join SPIRALk worker thread\n");
    return false;
  }
  if (!saw_progress) {
    fprintf(stderr,
            "ERROR: SPIRALk workload never advanced beyond the initial steps\n");
    return false;
  }
  if (!saw_pause) {
    fprintf(stderr,
            "ERROR: SPIRALk workload never reached the final PAUSE 99 wait state\n");
    return false;
  }
  if (programRunStop == PGM_RUNNING || programRunStop == PGM_PAUSED) {
    fprintf(stderr,
            "ERROR: SPIRALk workload finished in an unexpected run state %u\n",
            (unsigned int)programRunStop);
    return false;
  }

  fprintf(stderr, "PASS: SPIRALk workload progressed to step %u and exited PAUSE 99\n",
          (unsigned int)max_step);
  return true;
}

static bool seed_large_factor_input(void) {
  static const char *const kLargeFactorInput =
      "5424563354566542698521412502251020304050";
  longInteger_t value;

  longIntegerInit(value);
  if (stringToLongInteger(kLargeFactorInput, 10, value) != 0) {
    fprintf(stderr, "ERROR: Failed to parse large FACTORS workload input\n");
    longIntegerFree(value);
    return false;
  }

  convertLongIntegerToLongIntegerRegister(value, REGISTER_X);
  longIntegerFree(value);
  return true;
}

static bool run_large_factors_workload(void) {
  function_worker_t worker;
  pthread_t worker_thread;
  uint64_t deadline;
  bool saw_refresh_progress = false;

  r47_init_runtime(0);
  setSystemFlag(FLAG_MONIT);
  if (!seed_large_factor_input()) {
    return false;
  }
  r47_reset_host_lcd_refresh_count();

  if (!start_worker(&worker, ITM_FACTORS, &worker_thread)) {
    return false;
  }

  deadline = monotonic_ms() + 20000u;
  while (!worker.done && monotonic_ms() < deadline) {
    if (fail_last_error("FACTORS")) {
      return false;
    }
    if (r47_get_host_lcd_refresh_count() > 0u) {
      saw_refresh_progress = true;
    }
    usleep(5000);
  }

  if (!worker.done) {
    fprintf(stderr, "ERROR: FACTORS workload timed out\n");
    return false;
  }
  if (!finish_worker(worker_thread)) {
    fprintf(stderr, "ERROR: Failed to join FACTORS worker thread\n");
    return false;
  }
  if (!saw_refresh_progress) {
    fprintf(stderr,
            "ERROR: FACTORS workload completed without any monitored refresh transitions\n");
    return false;
  }
  if (getRegisterDataType(REGISTER_X) != dtReal34Matrix) {
    fprintf(stderr,
            "ERROR: FACTORS workload left X in unexpected register type %u\n",
            (unsigned int)getRegisterDataType(REGISTER_X));
    return false;
  }

  fprintf(stderr, "PASS: FACTORS workload refreshed %llu times and produced %s\n",
          (unsigned long long)r47_get_host_lcd_refresh_count(),
          getXRegisterString());
  clearSystemFlag(FLAG_MONIT);
  return true;
}

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

  if (!run_spiralk_workload(runtime_dir, program_root)) {
    return 1;
  }
  if (!run_large_factors_workload()) {
    return 1;
  }

  return 0;
}