// Calls from the calculator thread into CalculatorEngine, a process-scoped Kotlin
// object, so nothing here depends on which Activity is alive. The class reference
// and method ids are bound once in JNI_OnLoad, which runs on a thread whose class
// loader sees the app's classes.
#include "jni_bridge.h"

JavaVM *g_jvm = NULL;
static jclass g_engineClass = NULL;
static jmethodID g_requestFileId = NULL;
static jmethodID g_playToneId = NULL;
static jmethodID g_tonesClearId = NULL;
static jmethodID g_fileWriteFailedId = NULL;
static jmethodID g_quitId = NULL;
static jmethodID g_clipboardTextId = NULL;
static jmethodID g_setClipboardId = NULL;
static jmethodID g_pasteResultId = NULL;
static jmethodID g_warningId = NULL;
static jmethodID g_displayTextId = NULL;

static jmethodID bindMethod(JNIEnv *env, jclass clazz, const char *name,
                            const char *signature) {
  jmethodID id = (*env)->GetStaticMethodID(env, clazz, name, signature);
  if (!jni_result_ok(env, (const void *)id, name)) {
    return NULL;
  }
  return id;
}

bool r47_upcalls_bind(JNIEnv *env, jclass engineClass) {
  g_engineClass = (*env)->NewGlobalRef(env, engineClass);
  if (!jni_result_ok(env, g_engineClass, "NewGlobalRef(CalculatorEngine)")) {
    return false;
  }
  g_requestFileId =
      bindMethod(env, engineClass, "requestFile", "(ZLjava/lang/String;I)V");
  g_playToneId = bindMethod(env, engineClass, "playTone", "(II)V");
  g_tonesClearId = bindMethod(env, engineClass, "clearTones", "()V");
  g_fileWriteFailedId =
      bindMethod(env, engineClass, "onFileWriteFailed", "()V");
  g_quitId = bindMethod(env, engineClass, "onCalculatorOff", "()V");
  g_clipboardTextId =
      bindMethod(env, engineClass, "onClipboardText", "(ILjava/lang/String;)V");
  g_setClipboardId =
      bindMethod(env, engineClass, "setClipboardText", "(Ljava/lang/String;)V");
  g_pasteResultId = bindMethod(env, engineClass, "onPasteResult", "(Z)V");
  g_warningId =
      bindMethod(env, engineClass, "onWarning", "(Ljava/lang/String;)V");
  g_displayTextId =
      bindMethod(env, engineClass, "onDisplayText", "(ZLjava/lang/String;)V");
  return g_requestFileId && g_playToneId && g_tonesClearId &&
         g_fileWriteFailedId && g_quitId && g_clipboardTextId &&
         g_setClipboardId && g_pasteResultId && g_warningId && g_displayTextId;
}

bool r47_upcall_request_file(bool isSave, const char *defaultName,
                             int fileType) {
  if (!g_engineClass) {
    return false;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_upcall_request_file")) {
    return false;
  }
  JNIEnv *env = scope.env;
  jstring name = jni_new_string_utf(env, defaultName ? defaultName : "", "",
                                    "r47_upcall_request_file NewStringUTF");
  bool ok = false;
  if (name != NULL) {
    (*env)->CallStaticVoidMethod(env, g_engineClass, g_requestFileId,
                                 (jboolean)isSave, name, (jint)fileType);
    ok = !jni_check_and_clear_exception(env, "r47_upcall_request_file");
    (*env)->DeleteLocalRef(env, name);
  }
  jni_release_env(&scope, "r47_upcall_request_file");
  return ok;
}

void r47_upcall_play_tone(int milliHz, int durationMs) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_upcall_play_tone")) {
    return;
  }
  (*scope.env)
      ->CallStaticVoidMethod(scope.env, g_engineClass, g_playToneId,
                             (jint)milliHz, (jint)durationMs);
  jni_check_and_clear_exception(scope.env, "r47_upcall_play_tone");
  jni_release_env(&scope, "r47_upcall_play_tone");
}

static void callVoid(jmethodID method, const char *context) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, context)) {
    return;
  }
  (*scope.env)->CallStaticVoidMethod(scope.env, g_engineClass, method);
  jni_check_and_clear_exception(scope.env, context);
  jni_release_env(&scope, context);
}

static void callWithText(jmethodID method, const char *text,
                         const char *context) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, context)) {
    return;
  }
  JNIEnv *env = scope.env;
  jstring value = jni_new_string_utf(env, text ? text : "", "", context);
  if (value != NULL) {
    (*env)->CallStaticVoidMethod(env, g_engineClass, method, value);
    jni_check_and_clear_exception(env, context);
    (*env)->DeleteLocalRef(env, value);
  }
  jni_release_env(&scope, context);
}

void r47_upcall_tones_clear(void) {
  callVoid(g_tonesClearId, "r47_upcall_tones_clear");
}

void r47_upcall_file_write_failed(void) {
  callVoid(g_fileWriteFailedId, "r47_upcall_file_write_failed");
}

// Tell the host that hal/io.c lost part of a file write.
void r47_report_file_write_failure(void) { r47_upcall_file_write_failed(); }

void r47_upcall_quit(void) { callVoid(g_quitId, "r47_upcall_quit"); }

void r47_upcall_clipboard_text(int what, const char *text) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_upcall_clipboard_text")) {
    return;
  }
  JNIEnv *env = scope.env;
  jstring value = jni_new_string_utf(env, text ? text : "", "",
                                     "r47_upcall_clipboard_text");
  if (value != NULL) {
    (*env)->CallStaticVoidMethod(env, g_engineClass, g_clipboardTextId,
                                 (jint)what, value);
    jni_check_and_clear_exception(env, "r47_upcall_clipboard_text");
    (*env)->DeleteLocalRef(env, value);
  }
  jni_release_env(&scope, "r47_upcall_clipboard_text");
}

void r47_upcall_set_clipboard(const char *text) {
  callWithText(g_setClipboardId, text, "r47_upcall_set_clipboard");
}

void r47_upcall_warning(const char *text) {
  callWithText(g_warningId, text, "r47_upcall_warning");
}

void r47_upcall_display_text(bool isError, const char *text) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_upcall_display_text")) {
    return;
  }
  JNIEnv *env = scope.env;
  jstring value =
      jni_new_string_utf(env, text ? text : "", "", "r47_upcall_display_text");
  if (value != NULL) {
    (*env)->CallStaticVoidMethod(env, g_engineClass, g_displayTextId,
                                 (jboolean)isError, value);
    jni_check_and_clear_exception(env, "r47_upcall_display_text");
    (*env)->DeleteLocalRef(env, value);
  }
  jni_release_env(&scope, "r47_upcall_display_text");
}

void r47_upcall_paste_result(bool pasted) {
  if (!g_engineClass) {
    return;
  }
  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_upcall_paste_result")) {
    return;
  }
  (*scope.env)
      ->CallStaticVoidMethod(scope.env, g_engineClass, g_pasteResultId,
                             (jboolean)pasted);
  jni_check_and_clear_exception(scope.env, "r47_upcall_paste_result");
  jni_release_env(&scope, "r47_upcall_paste_result");
}
