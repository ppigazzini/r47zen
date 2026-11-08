#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include "c47.h"
#include "keypad_fixture_bridge.h"
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>

// LOGI, LOGE, and LOGD come from android_mocks.h, which every translation unit
// gets through -include before any header, the app build and each host harness
// alike.

#define CALCULATOR_ENGINE_CLASS "io/github/ppigazzini/r47zen/CalculatorEngine"

extern JavaVM *g_jvm;

typedef struct {
  JNIEnv *env;
  bool attached_here;
} jni_env_scope_t;

static inline bool jni_check_and_clear_exception(JNIEnv *env,
                                                 const char *context) {
  if (!env || !(*env)->ExceptionCheck(env)) {
    return false;
  }

  LOGE("%s: pending Java exception", context);
  (*env)->ExceptionDescribe(env);
  (*env)->ExceptionClear(env);
  return true;
}

static inline bool jni_result_ok(JNIEnv *env, const void *result,
                                 const char *context) {
  if (result == NULL) {
    LOGE("%s returned NULL", context);
  }
  if (jni_check_and_clear_exception(env, context)) {
    return false;
  }
  return result != NULL;
}

static inline bool jni_acquire_env(jni_env_scope_t *scope,
                                   const char *context) {
  if (scope == NULL) {
    LOGE("%s: scope is NULL", context);
    return false;
  }

  scope->env = NULL;
  scope->attached_here = false;
  if (g_jvm == NULL) {
    LOGE("%s: JVM reference is NULL", context);
    return false;
  }

  jint result = (*g_jvm)->GetEnv(g_jvm, (void **)&scope->env, JNI_VERSION_1_6);
  if (result == JNI_OK && scope->env != NULL) {
    return true;
  }

  if (result != JNI_EDETACHED) {
    LOGE("%s: GetEnv failed (%d)", context, result);
    return false;
  }

  // The two jni.h copies disagree on this out parameter: the NDK declares
  // AttachCurrentThread(JavaVM*, JNIEnv**, void*) while the host JDK declares
  // (JavaVM*, void**, void*), and the host workload lane compiles this header
  // against the JDK copy. void * converts to either without a diagnostic; a
  // void ** cast would be right for only one of them, and passing &scope->env
  // bare is right for only the other -- which is why the JDK build warned here
  // on GCC 13 and failed outright on GCC 14. GetEnv needs no such care: both
  // headers declare its out parameter void **.
  jint attach_result =
      (*g_jvm)->AttachCurrentThread(g_jvm, (void *)&scope->env, NULL);
  if (attach_result != JNI_OK || scope->env == NULL) {
    LOGE("%s: AttachCurrentThread failed (%d)", context, attach_result);
    scope->env = NULL;
    return false;
  }

  scope->attached_here = true;
  return true;
}

static inline void jni_release_env(jni_env_scope_t *scope,
                                   const char *context) {
  if (scope == NULL) {
    return;
  }

  if (scope->attached_here && g_jvm != NULL) {
    jint detach_result = (*g_jvm)->DetachCurrentThread(g_jvm);
    if (detach_result != JNI_OK) {
      LOGE("%s: DetachCurrentThread failed (%d)", context, detach_result);
    }
  }

  scope->env = NULL;
  scope->attached_here = false;
}

// A NULL from NewStringUTF comes with an OutOfMemoryError pending, and JNI allows
// almost no call while one is: each result is checked, and its exception cleared,
// before the next call, the fallback's included.
static inline jstring jni_new_string_utf(JNIEnv *env, const char *value,
                                         const char *fallback,
                                         const char *context) {
  jstring result = (*env)->NewStringUTF(env, value ? value : "");
  const bool raised = jni_check_and_clear_exception(env, context);
  if (result != NULL && !raised) {
    return result;
  }

  if (fallback == NULL) {
    return NULL;
  }

  LOGE("%s: falling back to a default Java string", context);
  result = (*env)->NewStringUTF(env, fallback);
  const bool fallbackRaised =
      jni_check_and_clear_exception(env, "jni_new_string_utf fallback");
  if (result != NULL && !fallbackRaised) {
    return result;
  }

  return NULL;
}

extern pthread_mutex_t fileMutex;
extern pthread_cond_t fileCond;
extern int fileDescriptor;
extern bool fileReady;
extern bool fileCanceled;
extern _Atomic bool isCoreBlockingForIo;

extern void set_android_base_path(const char *path);
extern void init_lcd_buffers(void);
extern void setupUI(void);
extern void lcd_clear_buf(void);
extern void lcd_refresh(void);
extern void refreshScreen(uint16_t reason);

gint64 g_get_monotonic_time(void);
gint64 g_get_real_time(void);
uint32_t sys_current_ms(void);
bool r47_graph_touch_no_nan_stress(int iterations);
bool r47_sanitize_graph_bounds(void);
void r47_sync_graph_bounds_reserved_vars(void);
// The graph window bounds (x_min/x_max/y_min/y_max) are real_t *const decNumber
// pointers. These helpers bridge the float-based touch and test math to the
// real API: read a bound into a float, and write a float (including NaN/Inf)
// back into a bound.
float r47_graph_bound_to_float(const real_t *value);
void r47_graph_bound_from_float(float value, real_t *dst);
int requestAndroidFile(int isSave, const char *defaultName, int fileType);
void r47_arm_snap(void);
void r47_offer_snap(void);
void r47_set_file_request_override(int fd, int isSave, int fileType);
void r47_clear_file_request_override(void);
int register_calculator_engine_natives(JNIEnv *env);

// The calculator thread's work behind the service queue.
void r47_apply_graph_gesture(float dxNorm, float dyNorm, float scale);
bool r47_reset_graph(void);

// The calculator thread: a Copy menu text, which the caller frees, or NULL.
char *r47_clipboard_text(int what);
char *r47_capture_clipboard(void (*copy)(void));
// What the display shows, as UTF-8 for a screen reader: the error the screen
// shows, the TAM prompt's entry, the number or alpha text being typed, else X as
// Copy X gives it; cut on a whole character to R47_DISPLAY_TEXT_MAX bytes. The
// calculator thread, at rest. NULL before the core has its memory; the caller frees it.
// *isError says the text is the error the screen shows.
#define R47_DISPLAY_TEXT_MAX 400
char *r47_display_text(bool *isError);
#if defined(R47_TEST_BRIDGE)
// The calculator thread: copy out what an instrumented test watches.
void r47_test_bridge_publish_state(void);
#endif
void r47_restore_guard_begin(void);
void r47_restore_guard_end(void);
void r47_calculator_main(void);
void r47_calculator_stop(bool save);
uint32_t r47_keypad_published_generation(void);
void r47_keypad_mark_input(void);

// Calls into CalculatorEngine, from the calculator thread. Each does nothing when
// no JVM is bound, as in a host harness.
bool r47_upcalls_bind(JNIEnv *env, jclass engineClass);
bool r47_upcall_request_file(bool isSave, const char *defaultName,
                             int fileType);
void r47_upcall_play_tone(int milliHz, int durationMs);
void r47_upcall_tones_clear(void);
void r47_tones_at_rest(void);
void r47_upcall_file_write_failed(void);
void r47_upcall_quit(void);
void r47_upcall_clipboard_text(int what, const char *text);
void r47_upcall_display_text(bool isError, const char *text);
void r47_upcall_set_clipboard(const char *text);
void r47_upcall_paste_result(bool pasted);
void r47_upcall_warning(const char *text);
void r47_report_file_write_failure(void);

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_runCalculatorNative(
    JNIEnv *env, jclass clazz, jstring path_obj);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_stopCalculatorNative(
    JNIEnv *env, jclass clazz, jboolean save);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_setAutoSaveNative(
    JNIEnv *env, jclass clazz, jboolean enabled);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_isReadyNative(JNIEnv *env,
                                                                jclass clazz);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_onHostStoppedNative(
    JNIEnv *env, jclass clazz, jboolean stopped);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_sendKey(JNIEnv *env,
                                                          jclass clazz,
                                                          jint keyCode);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_cancelKey(JNIEnv *env,
                                                            jclass clazz);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_releaseKeyIfDown(
    JNIEnv *env, jclass clazz);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_sendPcKeyNative(
    JNIEnv *env, jclass clazz, jint keyval, jint state, jboolean press);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_graphGestureNative(
    JNIEnv *env, jclass clazz, jfloat dxNorm, jfloat dyNorm, jfloat scale);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_resetGraphNative(
    JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_pasteNumberNative(
    JNIEnv *env, jclass clazz, jstring tokens);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_saveStateNative(
    JNIEnv *env, jclass clazz, jint timeoutMs);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_copyRegistersNative(
    JNIEnv *env, jclass clazz, jint what);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_describeDisplayNative(
    JNIEnv *env, jclass clazz);
JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_getPackedDisplayGeneration(
    JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_getPackedDisplayBuffer(
    JNIEnv *env, jclass clazz, jbyteArray buffer);
JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_getKeypadSnapshotGeneration(
    JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_copyKeypadSnapshotNative(
    JNIEnv *env, jclass clazz, jint mainKeyDynamicMode, jintArray metaBuffer,
    jobjectArray labelsBuffer);
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_onFileSelectedNative(
    JNIEnv *env, jclass clazz, jint fd, jstring name);
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_onFileCanceledNative(
    JNIEnv *env, jclass clazz);

#endif
