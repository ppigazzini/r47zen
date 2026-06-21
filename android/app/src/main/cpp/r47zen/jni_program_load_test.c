#include "jni_bridge.h"

#include <math.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum {
  R47_PROGRAM_LOAD_STATE_LENGTH = 7,
  R47_PROGRAM_LOAD_LAST_ERROR_INDEX = 0,
  R47_PROGRAM_LOAD_TEMP_INFO_INDEX = 1,
  R47_PROGRAM_LOAD_RUN_STOP_INDEX = 2,
  R47_PROGRAM_LOAD_PROGRAM_COUNT_INDEX = 3,
  R47_PROGRAM_LOAD_LOCAL_STEP_INDEX = 4,
  R47_PROGRAM_LOAD_CURRENT_PROGRAM_INDEX = 5,
  R47_PROGRAM_LOAD_LCD_REFRESH_INDEX = 6,
};

static pthread_mutex_t r47_program_load_worker_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool_t r47_program_load_worker_running = false;

typedef struct {
  int key_code;
} r47_program_load_key_sequence_t;

static const char *const kR47LargeFactorsInput =
  "5424563354566542698521412502251020304050";

extern char *getXRegisterString(void);

static void *r47_program_load_worker_main(void *arg) {
  int func_id = (int)(intptr_t)arg;

  pthread_mutex_lock(&screenMutex);
  extern void runFunction(int16_t id);
  runFunction((int16_t)func_id);
  pthread_mutex_unlock(&screenMutex);

  pthread_mutex_lock(&r47_program_load_worker_mutex);
  r47_program_load_worker_running = false;
  pthread_mutex_unlock(&r47_program_load_worker_mutex);

  return NULL;
}

static void *r47_program_load_key_worker_main(void *arg) {
  r47_program_load_key_sequence_t *request =
      (r47_program_load_key_sequence_t *)arg;

  Java_com_example_r47_MainActivity_sendKey(NULL, NULL, request->key_code);
  Java_com_example_r47_MainActivity_sendKey(NULL, NULL, 0);
  free(request);

  pthread_mutex_lock(&r47_program_load_worker_mutex);
  r47_program_load_worker_running = false;
  pthread_mutex_unlock(&r47_program_load_worker_mutex);

  return NULL;
}

static bool r47_seed_large_factors_input(void) {
  longInteger_t value;

  longIntegerInit(value);
  if (stringToLongInteger(kR47LargeFactorsInput, 10, value) != 0) {
    longIntegerFree(value);
    return false;
  }

  convertLongIntegerToLongIntegerRegister(value, REGISTER_X);
  longIntegerFree(value);
  return true;
}

static bool r47_seed_spiralk_input(void) {
  reallocateRegister(REGISTER_J, dtReal34, 0, amNone);
  stringToReal34("2", REGISTER_REAL34_DATA(REGISTER_J));
  return true;
}

static uint64_t r47_capture_display_hash_locked(void) {
  extern uint8_t *packedDisplayBuffer;
  extern pthread_mutex_t packedDisplayMutex;

  if (!packedDisplayBuffer) {
    return 0;
  }

  uint64_t hash = 1469598103934665603ULL;
  pthread_mutex_lock(&packedDisplayMutex);
  for (size_t row = 0; row < SCREEN_HEIGHT; row++) {
    const uint8_t *snapshot_line = packedDisplayBuffer + row * 52u;
    for (size_t byte_index = 2; byte_index < 52u; byte_index++) {
      hash ^= snapshot_line[byte_index];
      hash *= 1099511628211ULL;
    }
  }
  pthread_mutex_unlock(&packedDisplayMutex);
  return hash;
}

static void r47_fill_program_load_state_values(
    jint values[R47_PROGRAM_LOAD_STATE_LENGTH]) {
  values[R47_PROGRAM_LOAD_LAST_ERROR_INDEX] = (jint)lastErrorCode;
  values[R47_PROGRAM_LOAD_TEMP_INFO_INDEX] = (jint)temporaryInformation;
  values[R47_PROGRAM_LOAD_RUN_STOP_INDEX] = (jint)programRunStop;
  values[R47_PROGRAM_LOAD_PROGRAM_COUNT_INDEX] = (jint)numberOfPrograms;
  values[R47_PROGRAM_LOAD_LOCAL_STEP_INDEX] = (jint)currentLocalStepNumber;
  values[R47_PROGRAM_LOAD_CURRENT_PROGRAM_INDEX] = (jint)currentProgramNumber;
  values[R47_PROGRAM_LOAD_LCD_REFRESH_INDEX] =
      (jint)r47_get_host_lcd_refresh_count();
}

static jintArray r47_new_program_load_state_array(
    JNIEnv *env, const jint values[R47_PROGRAM_LOAD_STATE_LENGTH]) {
  jintArray result = (*env)->NewIntArray(env, R47_PROGRAM_LOAD_STATE_LENGTH);
  if (result == NULL ||
      jni_check_and_clear_exception(env,
                                    "ProgramLoadTestBridge.NewIntArray")) {
    return NULL;
  }

  (*env)->SetIntArrayRegion(env, result, 0, R47_PROGRAM_LOAD_STATE_LENGTH,
                            values);
  if (jni_check_and_clear_exception(env,
                                    "ProgramLoadTestBridge.SetIntArrayRegion")) {
    return NULL;
  }

  return result;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_isRuntimeReadyNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return ram != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_seedLargeFactorsInputNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  bool seeded = r47_seed_large_factors_input();
  if (seeded) {
    fnRefreshState();
    r47_force_refresh();
  }
  pthread_mutex_unlock(&screenMutex);

  return seeded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_seedSpiralkInputNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  bool seeded = r47_seed_spiralk_input();
  if (seeded) {
    fnRefreshState();
    r47_force_refresh();
  }
  pthread_mutex_unlock(&screenMutex);

  return seeded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_forceRefreshNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  r47_force_refresh();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_saveBackgroundStateForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return;
  }

  extern void r47_save_background_state_locked(void);

  pthread_mutex_lock(&screenMutex);
  r47_save_background_state_locked();
  pthread_mutex_unlock(&screenMutex);
}

JNIEXPORT jlong JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_captureDisplayHashNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return 0;
  }

  pthread_mutex_lock(&screenMutex);
  uint64_t hash = r47_capture_display_hash_locked();
  pthread_mutex_unlock(&screenMutex);
  return (jlong)hash;
}

// Writes a deterministic, non-trivial pattern into the hashed bytes of the
// packed display framebuffer so a lifecycle event's effect on the framebuffer
// can be checked without running a graphing program (REPORT-24 Milestone 4b).
// The caller must already hold screenMutex.
static void r47_fill_test_display_pattern_locked(void) {
  extern uint8_t *packedDisplayBuffer;
  extern pthread_mutex_t packedDisplayMutex;

  pthread_mutex_lock(&packedDisplayMutex);
  for (size_t row = 0; row < SCREEN_HEIGHT; row++) {
    uint8_t *snapshot_line = packedDisplayBuffer + row * 52u;
    for (size_t byte_index = 2; byte_index < 52u; byte_index++) {
      snapshot_line[byte_index] =
          (uint8_t)((row * 31u + byte_index * 7u) & 0xFFu);
    }
  }
  pthread_mutex_unlock(&packedDisplayMutex);
}

// Deterministic proof that a background save does not corrupt the visible
// framebuffer (REPORT-24 Milestone 4b Slice B). r47_save_background_state_locked
// only calls saveCalc(), which serializes calculator state and never touches
// packedDisplayBuffer, so the contract holds for ANY framebuffer -- there is no
// need to run a graphing program emergently to produce one. A deterministic
// non-trivial pattern is injected, the framebuffer is hashed, the background save
// runs, and it is re-hashed, all while screenMutex is held so no async redraw can
// interleave. Returns true when the save preserved the (non-trivial) framebuffer.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_backgroundSaveKeepsInjectedDisplayBufferForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  extern uint8_t *packedDisplayBuffer;
  extern void r47_save_background_state_locked(void);

  if (!packedDisplayBuffer) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  r47_fill_test_display_pattern_locked();
  uint64_t before_save = r47_capture_display_hash_locked();
  r47_save_background_state_locked();
  uint64_t after_save = r47_capture_display_hash_locked();
  pthread_mutex_unlock(&screenMutex);

  return (before_save == after_save && before_save != 0u) ? JNI_TRUE : JNI_FALSE;
}

// Injects the deterministic non-trivial framebuffer pattern and returns its hash,
// atomically under screenMutex (REPORT-24 Milestone 4b Slice C). The pause/resume
// and recreation lifecycle transitions are display-passive -- the native runtime
// and packedDisplayBuffer persist across them and are not re-rendered (the core
// thread and native-init flags are process-shared, so a recreated Activity
// re-attaches without re-init) -- so the test injects this pattern, drives the
// Android lifecycle event, and re-hashes via captureDisplayHash, expecting the
// returned hash to be preserved. Returns 0 if the framebuffer is unavailable.
JNIEXPORT jlong JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_injectDeterministicDisplayBufferForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return 0;
  }

  extern uint8_t *packedDisplayBuffer;

  if (!packedDisplayBuffer) {
    return 0;
  }

  pthread_mutex_lock(&screenMutex);
  r47_fill_test_display_pattern_locked();
  uint64_t hash = r47_capture_display_hash_locked();
  pthread_mutex_unlock(&screenMutex);

  return (jlong)hash;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_setRedrawFlagForTestNative(
    JNIEnv *env, jobject thiz, jboolean enabled) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return;
  }

  pthread_mutex_lock(&screenMutex);
  reDraw = enabled == JNI_TRUE;
  pthread_mutex_unlock(&screenMutex);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_isRedrawFlagSetForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  jboolean enabled = reDraw ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock(&screenMutex);
  return enabled;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_runExtremeGraphTouchStressNative(
    JNIEnv *env, jobject thiz, jint iterations) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  int loops = (int)iterations;
  if (loops <= 0) {
    loops = 1;
  }

  pthread_mutex_lock(&screenMutex);
  bool ok = r47_graph_touch_no_nan_stress_locked(loops);
  pthread_mutex_unlock(&screenMutex);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_restoreSanitizesInvalidGraphBoundsNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);

  const float savedXMin = x_min;
  const float savedXMax = x_max;
  const float savedYMin = y_min;
  const float savedYMax = y_max;

  x_min = NAN;
  x_max = INFINITY;
  y_min = 4.0f;
  y_max = 4.0f;

  bool changed = r47_sanitize_graph_bounds_locked();
  bool ok = changed && isfinite(x_min) && isfinite(x_max) && isfinite(y_min) &&
            isfinite(y_max) && x_min < x_max && y_min < y_max;

  x_min = savedXMin;
  x_max = savedXMax;
  y_min = savedYMin;
  y_max = savedYMax;
  r47_sanitize_graph_bounds_locked();

  pthread_mutex_unlock(&screenMutex);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_resetRuntimeNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return;
  }

  extern void doFnReset(uint16_t confirmation, bool_t autoSav);

  pthread_mutex_lock(&screenMutex);
  doFnReset(CONFIRMED, false);
  fnRefreshState();
  r47_force_refresh();
  pthread_mutex_unlock(&screenMutex);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_sendSimFunctionNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;
  r47_send_sim_function((int)funcId);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease) {
  (void)thiz;
  if (!ram || isCoreBlockingForIo) {
    return;
  }

  const char *nativeKeyId = (*env)->GetStringUTFChars(env, keyId, 0);
  if (!nativeKeyId ||
      jni_check_and_clear_exception(
          env, "ProgramLoadTestBridge.sendSimKey GetStringUTFChars")) {
    // Release before bailing: GetStringUTFChars can return a valid copy even
    // when an exception is pending, and an early return must not leak it
    // (mirrors the production sendSimKeyNative path in jni_input.c).
    if (nativeKeyId) {
      (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
    }
    return;
  }

  r47_send_sim_key(nativeKeyId, isFn == JNI_TRUE, isRelease == JNI_TRUE);
  (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_beginSimFunctionNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  r47_reset_host_lcd_refresh_count();
  pthread_mutex_unlock(&screenMutex);

  pthread_mutex_lock(&r47_program_load_worker_mutex);
  if (r47_program_load_worker_running) {
    pthread_mutex_unlock(&r47_program_load_worker_mutex);
    return JNI_FALSE;
  }
  r47_program_load_worker_running = true;
  pthread_mutex_unlock(&r47_program_load_worker_mutex);

  pthread_t worker_thread;
  if (pthread_create(&worker_thread, NULL, r47_program_load_worker_main,
                     (void *)(intptr_t)funcId) != 0) {
    pthread_mutex_lock(&r47_program_load_worker_mutex);
    r47_program_load_worker_running = false;
    pthread_mutex_unlock(&r47_program_load_worker_mutex);
    return JNI_FALSE;
  }

  pthread_detach(worker_thread);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_beginMainActivityKeySequenceNative(
    JNIEnv *env, jobject thiz, jint keyCode) {
  (void)env;
  (void)thiz;

  if (!ram) {
    return JNI_FALSE;
  }

  pthread_mutex_lock(&screenMutex);
  r47_reset_host_lcd_refresh_count();
  pthread_mutex_unlock(&screenMutex);

  pthread_mutex_lock(&r47_program_load_worker_mutex);
  if (r47_program_load_worker_running) {
    pthread_mutex_unlock(&r47_program_load_worker_mutex);
    return JNI_FALSE;
  }
  r47_program_load_worker_running = true;
  pthread_mutex_unlock(&r47_program_load_worker_mutex);

  r47_program_load_key_sequence_t *request =
      (r47_program_load_key_sequence_t *)malloc(sizeof(*request));
  if (request == NULL) {
    pthread_mutex_lock(&r47_program_load_worker_mutex);
    r47_program_load_worker_running = false;
    pthread_mutex_unlock(&r47_program_load_worker_mutex);
    return JNI_FALSE;
  }
  request->key_code = (int)keyCode;

  pthread_t worker_thread;
  if (pthread_create(&worker_thread, NULL, r47_program_load_key_worker_main,
                     request) != 0) {
    free(request);
    pthread_mutex_lock(&r47_program_load_worker_mutex);
    r47_program_load_worker_running = false;
    pthread_mutex_unlock(&r47_program_load_worker_mutex);
    return JNI_FALSE;
  }

  pthread_detach(worker_thread);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_requestStopProgramNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  return Java_com_example_r47_MainActivity_requestStopProgramNative(env, NULL);
}

// Pure, side-effect-free probe of the out-of-band direct-stop run-state gate, so
// the run-state contract can be asserted deterministically without driving a
// real program into a specific (timing-dependent) run state. Mirrors the gate
// consulted by MainActivity.dispatchLiveKey before it swallows R/S/EXIT.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_directStopAllowedForRunStateNative(
    JNIEnv *env, jobject thiz, jint runState) {
  (void)env;
  (void)thiz;
  return r47_direct_stop_allowed((uint16_t)runState) ? JNI_TRUE : JNI_FALSE;
}

// Inject a run state directly so the REAL requestStopProgramNative gate can be
// asserted end to end across every run state deterministically, instead of
// waiting for a graphing program to emergently reach a busy state (the flaky
// 90 s SPIRALk wait this replaces). Safe because fnStopProgram() only sets
// programRunStop = PGM_WAITING and clears a screen flag; it dereferences no
// program state. Tests must resetRuntime() afterwards to restore isolation.
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_setProgramRunStopForTestNative(
    JNIEnv *env, jobject thiz, jint runState) {
  (void)env;
  (void)thiz;
  programRunStop = (uint8_t)runState;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_setNextLoadProgramFdNative(
    JNIEnv *env, jobject thiz, jint fd) {
  (void)env;
  (void)thiz;
  r47_set_file_request_override((int)fd, 0, 1);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_clearLoadProgramFdOverrideNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  r47_clear_file_request_override();
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_isSimFunctionRunningNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  pthread_mutex_lock(&r47_program_load_worker_mutex);
  jboolean is_running = r47_program_load_worker_running ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock(&r47_program_load_worker_mutex);

  return is_running;
}

JNIEXPORT jintArray JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_snapshotStateNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  jint values[R47_PROGRAM_LOAD_STATE_LENGTH];
  memset(values, 0, sizeof(values));

  if (ram) {
    pthread_mutex_lock(&screenMutex);
    r47_fill_program_load_state_values(values);
    pthread_mutex_unlock(&screenMutex);
  }

  return r47_new_program_load_state_array(env, values);
}

JNIEXPORT jintArray JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_snapshotStateIfAvailableNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  jint values[R47_PROGRAM_LOAD_STATE_LENGTH];
  memset(values, 0, sizeof(values));

  if (ram) {
    if (pthread_mutex_trylock(&screenMutex) != 0) {
      return NULL;
    }
    r47_fill_program_load_state_values(values);
    pthread_mutex_unlock(&screenMutex);
  }

  return r47_new_program_load_state_array(env, values);
}

JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_getXRegisterTypeNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;

  jint result = 0;
  if (ram) {
    pthread_mutex_lock(&screenMutex);
    result = (jint)getRegisterDataType(REGISTER_X);
    pthread_mutex_unlock(&screenMutex);
  }

  return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_getXRegisterStringNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  char register_text[2048];
  register_text[0] = '\0';

  if (ram) {
    pthread_mutex_lock(&screenMutex);
    const char *value = getXRegisterString();
    if (value != NULL) {
      snprintf(register_text, sizeof(register_text), "%s", value);
    }
    pthread_mutex_unlock(&screenMutex);
  }

  return jni_new_string_utf(env, register_text, "",
                            "ProgramLoadTestBridge.getXRegisterString");
}
