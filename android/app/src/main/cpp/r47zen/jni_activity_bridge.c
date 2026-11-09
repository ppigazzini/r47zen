#include "jni_bridge.h"

JavaVM *g_jvm = NULL;
jobject g_mainActivityObj = NULL;
jmethodID g_requestFileId = NULL;
jmethodID g_playToneId = NULL;
jmethodID g_processCoreTasksId = NULL;
jmethodID g_fileWriteFailedId = NULL;
jmethodID g_warningId = NULL;
jmethodID g_displayTextId = NULL;

void processCoreTasksNative(void) {
  if (!g_mainActivityObj || !g_jvm || !g_processCoreTasksId) {
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "processCoreTasksNative")) {
    return;
  }

  (*scope.env)
      ->CallVoidMethod(scope.env, g_mainActivityObj, g_processCoreTasksId);
  jni_check_and_clear_exception(scope.env,
                                "processCoreTasksNative CallVoidMethod");
  jni_release_env(&scope, "processCoreTasksNative");
}

// Tell the host that hal/io.c lost part of a file write. Core thread only, like
// every reader of the activity globals.
void r47_report_file_write_failure(void) {
  if (!g_mainActivityObj || !g_jvm || !g_fileWriteFailedId) {
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_report_file_write_failure")) {
    return;
  }

  (*scope.env)
      ->CallVoidMethod(scope.env, g_mainActivityObj, g_fileWriteFailedId);
  jni_check_and_clear_exception(scope.env,
                                "r47_report_file_write_failure CallVoidMethod");
  jni_release_env(&scope, "r47_report_file_write_failure");
}

// Show the user a warning the core raised through show_warning: a program or a state
// file it could not take whole. Core thread only, like every reader of the activity
// globals. With no activity the text goes to the log.
void r47_show_warning(const char *text) {
  if (!g_mainActivityObj || !g_jvm || !g_warningId) {
    LOGE("core warning with no activity: %s", text != NULL ? text : "");
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_show_warning")) {
    return;
  }

  jstring message = jni_new_string_utf(scope.env, text != NULL ? text : "", "",
                                       "r47_show_warning");
  if (message != NULL) {
    (*scope.env)
        ->CallVoidMethod(scope.env, g_mainActivityObj, g_warningId, message);
    jni_check_and_clear_exception(scope.env, "r47_show_warning CallVoidMethod");
    (*scope.env)->DeleteLocalRef(scope.env, message);
  }
  jni_release_env(&scope, "r47_show_warning");
}

// The core thread: what the display shows, for a screen reader, after
// describeDisplayNative. isError says the text is the error the screen shows.
void r47_show_display_text(bool isError, const char *text) {
  if (!g_mainActivityObj || !g_jvm || !g_displayTextId) {
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "r47_show_display_text")) {
    return;
  }

  jstring message = jni_new_string_utf(scope.env, text != NULL ? text : "", "",
                                       "r47_show_display_text");
  if (message != NULL) {
    (*scope.env)
        ->CallVoidMethod(scope.env, g_mainActivityObj, g_displayTextId,
                         isError ? JNI_TRUE : JNI_FALSE, message);
    jni_check_and_clear_exception(scope.env,
                                  "r47_show_display_text CallVoidMethod");
    (*scope.env)->DeleteLocalRef(scope.env, message);
  }
  jni_release_env(&scope, "r47_show_display_text");
}
