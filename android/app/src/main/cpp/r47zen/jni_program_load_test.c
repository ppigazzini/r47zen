// The instrumented tests' bridge, which only an instrumentation build compiles. It
// holds the app's one threading rule: everything that touches core state runs on
// the calculator thread at rest, through r47_run_at_rest.
#include "jni_bridge.h"
#include "host_services.h"
#include "hal/lcd.h"

#include <math.h>
#include <stdatomic.h>
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

static _Atomic bool r47_program_load_busy = false;

extern uint8_t *packedDisplayBuffer;
extern pthread_mutex_t packedDisplayMutex;
extern void doFnReset(uint16_t confirmation, bool_t autoSav);

static bool r47_seed_spiralk_input(void) {
  reallocateRegister(REGISTER_J, dtReal34, 0, amNone);
  stringToReal34("2", REGISTER_REAL34_DATA(REGISTER_J));
  return true;
}

static uint64_t r47_capture_display_hash(void) {
  if (!packedDisplayBuffer) {
    return 0;
  }

  uint64_t hash = 1469598103934665603ULL;
  pthread_mutex_lock(&packedDisplayMutex);
  for (size_t row = 0; row < SCREEN_HEIGHT; row++) {
    const uint8_t *snapshot_line =
        packedDisplayBuffer + row * LCD_ROW_SIZE_BYTES;
    for (size_t byte_index = 2; byte_index < LCD_ROW_SIZE_BYTES; byte_index++) {
      hash ^= snapshot_line[byte_index];
      hash *= 1099511628211ULL;
    }
  }
  pthread_mutex_unlock(&packedDisplayMutex);
  return hash;
}

static void
r47_fill_program_load_state_values(jint values[R47_PROGRAM_LOAD_STATE_LENGTH]) {
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
      jni_check_and_clear_exception(env, "ProgramLoadTestBridge.NewIntArray")) {
    return NULL;
  }

  (*env)->SetIntArrayRegion(env, result, 0, R47_PROGRAM_LOAD_STATE_LENGTH,
                            values);
  if (jni_check_and_clear_exception(
          env, "ProgramLoadTestBridge.SetIntArrayRegion")) {
    return NULL;
  }

  return result;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_isRuntimeReadyNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return r47_runtime_ready() ? JNI_TRUE : JNI_FALSE;
}

typedef struct {
  char decimal[64];
  bool longInteger;
  bool seeded;
} r47_x_seed_t;

static void seedXAtRest(void *arg) {
  r47_x_seed_t *seed = arg;
  if (seed->longInteger) {
    longInteger_t value;
    longIntegerInit(value);
    seed->seeded = stringToLongInteger(seed->decimal, 10, value) == 0;
    if (seed->seeded) {
      convertLongIntegerToLongIntegerRegister(value, REGISTER_X);
    }
    longIntegerFree(value);
  } else {
    reallocateRegister(REGISTER_X, dtReal34, 0, amNone);
    stringToReal34(seed->decimal, REGISTER_REAL34_DATA(REGISTER_X));
    seed->seeded = !real34IsNaN(REGISTER_REAL34_DATA(REGISTER_X));
  }
  if (seed->seeded) {
    fnRefreshState();
    r47_force_refresh();
  }
}

// Put a decimal number in X, as a long integer or a real, on the calculator thread.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_seedXNative(
    JNIEnv *env, jobject thiz, jstring decimal, jboolean longInteger) {
  (void)thiz;
  r47_x_seed_t seed = {
      .decimal = "", .longInteger = longInteger == JNI_TRUE, .seeded = false};
  const char *chars =
      decimal != NULL ? (*env)->GetStringUTFChars(env, decimal, NULL) : NULL;
  if (chars == NULL) {
    return JNI_FALSE;
  }
  const bool fits = r47_format(seed.decimal, sizeof(seed.decimal), "%s", chars);
  (*env)->ReleaseStringUTFChars(env, decimal, chars);
  if (!fits) {
    return JNI_FALSE;
  }
  r47_run_at_rest(seedXAtRest, &seed);
  return seed.seeded ? JNI_TRUE : JNI_FALSE;
}

// X as the Copy menu copies it, whole.
static void readXClipboardAtRest(void *arg) {
  *(char **)arg = r47_clipboard_text(R47_COPY_X);
}

// X as the clipboard copies it: a matrix as "RxC" and each element, row by row.
JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_getXRegisterClipboardTextNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  char *text = NULL;
  r47_run_at_rest(readXClipboardAtRest, &text);
  jstring result = jni_new_string_utf(
      env, text, "", "ProgramLoadTestBridge.getXRegisterClipboardText");
  free(text);
  return result;
}

static void seedSpiralkAtRest(void *arg) {
  bool *seeded = arg;
  *seeded = r47_seed_spiralk_input();
  if (*seeded) {
    fnRefreshState();
    r47_force_refresh();
  }
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_seedSpiralkInputNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  bool seeded = false;
  r47_run_at_rest(seedSpiralkAtRest, &seeded);
  return seeded ? JNI_TRUE : JNI_FALSE;
}

static void forceRefreshAtRest(void *arg) {
  (void)arg;
  r47_force_refresh();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_forceRefreshNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  r47_run_at_rest(forceRefreshAtRest, NULL);
}

static void saveAtRest(void *arg) {
  (void)arg;
  r47_save_settled();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_saveBackgroundStateForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  r47_run_at_rest(saveAtRest, NULL);
}

JNIEXPORT jlong JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_captureDisplayHashNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return (jlong)r47_capture_display_hash();
}

// Writes a deterministic, non-trivial pattern into the hashed bytes of the
// packed display framebuffer so a lifecycle event's effect on the framebuffer
// can be checked without running a graphing program.
static void r47_fill_test_display_pattern(void) {
  pthread_mutex_lock(&packedDisplayMutex);
  for (size_t row = 0; row < SCREEN_HEIGHT; row++) {
    uint8_t *snapshot_line = packedDisplayBuffer + row * LCD_ROW_SIZE_BYTES;
    for (size_t byte_index = 2; byte_index < LCD_ROW_SIZE_BYTES; byte_index++) {
      snapshot_line[byte_index] =
          (uint8_t)((row * 31u + byte_index * 7u) & 0xFFu);
    }
  }
  pthread_mutex_unlock(&packedDisplayMutex);
}

static void saveKeepsDisplayAtRest(void *arg) {
  bool *kept = arg;
  r47_fill_test_display_pattern();
  const uint64_t before_save = r47_capture_display_hash();
  r47_save_settled();
  *kept = before_save == r47_capture_display_hash() && before_save != 0u;
}

// Deterministic proof that a background save does not corrupt the visible
// framebuffer. saveCalc serializes calculator state and never touches
// packedDisplayBuffer, so the contract holds for ANY framebuffer. All of it runs on
// the calculator thread, so no redraw can interleave.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_backgroundSaveKeepsInjectedDisplayBufferForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!packedDisplayBuffer) {
    return JNI_FALSE;
  }
  bool kept = false;
  r47_run_at_rest(saveKeepsDisplayAtRest, &kept);
  return kept ? JNI_TRUE : JNI_FALSE;
}

static void injectDisplayAtRest(void *arg) {
  uint64_t *hash = arg;
  r47_fill_test_display_pattern();
  *hash = r47_capture_display_hash();
}

// Injects the deterministic framebuffer pattern and returns its hash. The pause,
// resume, and recreation transitions are display-passive: the calculator and
// packedDisplayBuffer outlive each Activity and are not re-rendered, so the test
// injects this pattern, drives the lifecycle event, and re-hashes.
JNIEXPORT jlong JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_injectDeterministicDisplayBufferForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!packedDisplayBuffer) {
    return 0;
  }
  uint64_t hash = 0;
  r47_run_at_rest(injectDisplayAtRest, &hash);
  return (jlong)hash;
}

static void setRedrawAtRest(void *arg) { reDraw = *(bool *)arg; }

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_setRedrawFlagForTestNative(
    JNIEnv *env, jobject thiz, jboolean enabled) {
  (void)env;
  (void)thiz;
  bool value = enabled == JNI_TRUE;
  r47_run_at_rest(setRedrawAtRest, &value);
}

static void readRedrawAtRest(void *arg) { *(bool *)arg = reDraw; }

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_isRedrawFlagSetForTestNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  bool value = false;
  r47_run_at_rest(readRedrawAtRest, &value);
  return value ? JNI_TRUE : JNI_FALSE;
}

typedef struct {
  int iterations;
  bool ok;
} graphStress_t;

static void graphStressAtRest(void *arg) {
  graphStress_t *stress = arg;
  stress->ok = r47_graph_touch_no_nan_stress(stress->iterations);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_runExtremeGraphTouchStressNative(
    JNIEnv *env, jobject thiz, jint iterations) {
  (void)env;
  (void)thiz;
  graphStress_t stress = {(int)iterations <= 0 ? 1 : (int)iterations, false};
  r47_run_at_rest(graphStressAtRest, &stress);
  return stress.ok ? JNI_TRUE : JNI_FALSE;
}

static void sanitizeBoundsAtRest(void *arg) {
  bool *ok = arg;
  const float savedXMin = r47_graph_bound_to_float(x_min);
  const float savedXMax = r47_graph_bound_to_float(x_max);
  const float savedYMin = r47_graph_bound_to_float(y_min);
  const float savedYMax = r47_graph_bound_to_float(y_max);

  r47_graph_bound_from_float(NAN, x_min);
  r47_graph_bound_from_float(INFINITY, x_max);
  r47_graph_bound_from_float(4.0f, y_min);
  r47_graph_bound_from_float(4.0f, y_max);

  const bool changed = r47_sanitize_graph_bounds();
  const float sanXMin = r47_graph_bound_to_float(x_min);
  const float sanXMax = r47_graph_bound_to_float(x_max);
  const float sanYMin = r47_graph_bound_to_float(y_min);
  const float sanYMax = r47_graph_bound_to_float(y_max);
  *ok = changed && isfinite(sanXMin) && isfinite(sanXMax) &&
        isfinite(sanYMin) && isfinite(sanYMax) && sanXMin < sanXMax &&
        sanYMin < sanYMax;

  r47_graph_bound_from_float(savedXMin, x_min);
  r47_graph_bound_from_float(savedXMax, x_max);
  r47_graph_bound_from_float(savedYMin, y_min);
  r47_graph_bound_from_float(savedYMax, y_max);
  r47_sync_graph_bounds_reserved_vars();
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_restoreSanitizesInvalidGraphBoundsNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  bool ok = false;
  r47_run_at_rest(sanitizeBoundsAtRest, &ok);
  return ok ? JNI_TRUE : JNI_FALSE;
}

static void resetAtRest(void *arg) {
  (void)arg;
  doFnReset(CONFIRMED, false);
  fnRefreshState();
  r47_force_refresh();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_resetRuntimeNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  r47_run_at_rest(resetAtRest, NULL);
}

static void runFunctionAtRest(void *arg) {
  runFunction((int16_t)(intptr_t)arg);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_sendSimFunctionNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)funcId);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease) {
  (void)thiz;
  (void)isFn;
  const char *nativeKeyId = (*env)->GetStringUTFChars(env, keyId, 0);
  if (!nativeKeyId ||
      jni_check_and_clear_exception(
          env, "ProgramLoadTestBridge.sendSimKey GetStringUTFChars")) {
    if (nativeKeyId) {
      (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
    }
    return;
  }

  // "00" to "36" is a key and "1" to "6" a softkey.
  const int code = nativeKeyId[1] == 0
                       ? nativeKeyId[0] - '1' + 38
                       : (nativeKeyId[0] - '0') * 10 + nativeKeyId[1] - '0' + 1;
  r47_key_push(isRelease == JNI_TRUE ? 0 : code);
  (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
}

static void resetRefreshCountAtRest(void *arg) {
  (void)arg;
  r47_reset_host_lcd_refresh_count();
}

static void beginFunctionAtRest(void *arg) {
  runFunction((int16_t)(intptr_t)arg);
  atomic_store(&r47_program_load_busy, false);
}

// Starts a function on the calculator thread and returns at once. The test polls
// isSimFunctionRunning and presses keys meanwhile, which the function's polls see.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_beginSimFunctionNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;
  if (atomic_exchange(&r47_program_load_busy, true)) {
    return JNI_FALSE;
  }
  r47_run_at_rest(resetRefreshCountAtRest, NULL);
  r47_run_at_rest_async(beginFunctionAtRest, (void *)(intptr_t)funcId);
  return JNI_TRUE;
}

static void markRestReached(void *arg) {
  (void)arg;
  atomic_store(&r47_program_load_busy, false);
}

// Presses one key through the same path as the keypad, and counts as running until
// the calculator is back at rest.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_beginMainActivityKeySequenceNative(
    JNIEnv *env, jobject thiz, jint keyCode) {
  (void)env;
  (void)thiz;
  if (atomic_exchange(&r47_program_load_busy, true)) {
    return JNI_FALSE;
  }
  r47_run_at_rest(resetRefreshCountAtRest, NULL);
  r47_key_push((int)keyCode);
  r47_key_push(0);
  r47_run_at_rest_async(markRestReached, NULL);
  return JNI_TRUE;
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
  return atomic_load(&r47_program_load_busy) ? JNI_TRUE : JNI_FALSE;
}

static void readStateAtRest(void *arg) {
  r47_fill_program_load_state_values((jint *)arg);
}

JNIEXPORT jintArray JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_snapshotStateNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  jint values[R47_PROGRAM_LOAD_STATE_LENGTH];
  memset(values, 0, sizeof(values));
  r47_run_at_rest(readStateAtRest, values);
  return r47_new_program_load_state_array(env, values);
}

// The busy test's read: during a computation there is no state to read.
JNIEXPORT jintArray JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_snapshotStateIfAvailableNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;

  if (atomic_load(&r47_program_load_busy)) {
    return NULL;
  }
  jint values[R47_PROGRAM_LOAD_STATE_LENGTH];
  memset(values, 0, sizeof(values));
  r47_run_at_rest(readStateAtRest, values);
  return r47_new_program_load_state_array(env, values);
}

static void readXTypeAtRest(void *arg) {
  *(jint *)arg = (jint)getRegisterDataType(REGISTER_X);
}

JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_getXRegisterTypeNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  jint result = 0;
  r47_run_at_rest(readXTypeAtRest, &result);
  return result;
}
