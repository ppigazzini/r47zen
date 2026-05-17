#include "jni_bridge.h"

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
    values[R47_PROGRAM_LOAD_LAST_ERROR_INDEX] = (jint)lastErrorCode;
    values[R47_PROGRAM_LOAD_TEMP_INFO_INDEX] = (jint)temporaryInformation;
    values[R47_PROGRAM_LOAD_RUN_STOP_INDEX] = (jint)programRunStop;
    values[R47_PROGRAM_LOAD_PROGRAM_COUNT_INDEX] = (jint)numberOfPrograms;
    values[R47_PROGRAM_LOAD_LOCAL_STEP_INDEX] = (jint)currentLocalStepNumber;
    values[R47_PROGRAM_LOAD_CURRENT_PROGRAM_INDEX] = (jint)currentProgramNumber;
    values[R47_PROGRAM_LOAD_LCD_REFRESH_INDEX] =
        (jint)r47_get_host_lcd_refresh_count();
    pthread_mutex_unlock(&screenMutex);
  }

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
