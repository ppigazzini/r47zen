#include "jni_bridge.h"

JavaVM *g_jvm = NULL;
jobject g_mainActivityObj = NULL;
jmethodID g_requestFileId = NULL;
jmethodID g_playToneId = NULL;
jmethodID g_stopToneId = NULL;
jmethodID g_processCoreTasksId = NULL;

void processCoreTasksNative(void) {
  if (!g_mainActivityObj || !g_jvm || !g_processCoreTasksId) {
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "processCoreTasksNative")) {
    return;
  }

  (*scope.env)->CallVoidMethod(scope.env, g_mainActivityObj,
                               g_processCoreTasksId);
  jni_check_and_clear_exception(scope.env,
                                "processCoreTasksNative CallVoidMethod");
  jni_release_env(&scope, "processCoreTasksNative");
}