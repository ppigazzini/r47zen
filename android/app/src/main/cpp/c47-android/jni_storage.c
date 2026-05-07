#include "jni_bridge.h"

#include <string.h>
#include <unistd.h>

static pthread_mutex_t r47_file_request_override_mutex =
    PTHREAD_MUTEX_INITIALIZER;
static int r47_file_request_override_fd = -1;
static int r47_file_request_override_file_type = -1;
static int r47_file_request_override_is_save = -1;

static int takeFileRequestOverride(int isSave, int fileType) {
  pthread_mutex_lock(&r47_file_request_override_mutex);
  int fd = -1;
  if (r47_file_request_override_fd >= 0 &&
      r47_file_request_override_is_save == isSave &&
      r47_file_request_override_file_type == fileType) {
    fd = r47_file_request_override_fd;
    r47_file_request_override_fd = -1;
    r47_file_request_override_file_type = -1;
    r47_file_request_override_is_save = -1;
  }
  pthread_mutex_unlock(&r47_file_request_override_mutex);
  return fd;
}

void r47_set_file_request_override(int fd, int isSave, int fileType) {
  pthread_mutex_lock(&r47_file_request_override_mutex);
  if (r47_file_request_override_fd >= 0) {
    close(r47_file_request_override_fd);
  }
  r47_file_request_override_fd = fd;
  r47_file_request_override_is_save = isSave;
  r47_file_request_override_file_type = fileType;
  pthread_mutex_unlock(&r47_file_request_override_mutex);
}

void r47_clear_file_request_override(void) {
  pthread_mutex_lock(&r47_file_request_override_mutex);
  if (r47_file_request_override_fd >= 0) {
    close(r47_file_request_override_fd);
  }
  r47_file_request_override_fd = -1;
  r47_file_request_override_file_type = -1;
  r47_file_request_override_is_save = -1;
  pthread_mutex_unlock(&r47_file_request_override_mutex);
}

static int restoreScreenMutexAndReturn(int lockCount, int fd) {
  LOGI("requestAndroidFile: Re-acquiring screenMutex (%d times)", lockCount);
  for (int index = 0; index < lockCount; index++) {
    pthread_mutex_lock(&screenMutex);
  }

  return fd;
}

static int failAndroidFileRequest(int lockCount) {
  pthread_mutex_lock(&fileMutex);
  isCoreBlockingForIo = false;
  fileReady = false;
  fileCancelled = true;
  fileDescriptor = -1;
  pthread_mutex_unlock(&fileMutex);

  return restoreScreenMutexAndReturn(lockCount, -1);
}

int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
  LOGI("requestAndroidFile(isSave=%d, defaultName=%s, fileType=%d)", isSave,
       defaultName, fileType);

  int override_fd = takeFileRequestOverride(isSave, fileType);
  if (override_fd >= 0) {
    LOGI("requestAndroidFile: using test override fd=%d", override_fd);
    return override_fd;
  }

  if (!g_requestFileId || !g_mainActivityObj) {
    LOGE("requestAndroidFile: requestFile bridge is not ready");
    return -1;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "requestAndroidFile")) {
    return -1;
  }
  JNIEnv *env = scope.env;

  int lockCount = 0;
  while (pthread_mutex_unlock(&screenMutex) == 0) {
    lockCount++;
  }
  LOGI("requestAndroidFile: Fully released screenMutex (lockCount=%d)",
       lockCount);

  pthread_mutex_lock(&fileMutex);
  isCoreBlockingForIo = true;
  fileReady = false;
  fileCancelled = false;
  fileDescriptor = -1;
  pthread_mutex_unlock(&fileMutex);

  jstring nameObj =
      jni_new_string_utf(env, defaultName ? defaultName : "", "",
                         "requestAndroidFile NewStringUTF");
  if (nameObj == NULL) {
    jni_release_env(&scope, "requestAndroidFile");
    return failAndroidFileRequest(lockCount);
  }

  (*env)->CallVoidMethod(env, g_mainActivityObj, g_requestFileId,
                         (jboolean)isSave, nameObj, (jint)fileType);
  if (nameObj) {
    (*env)->DeleteLocalRef(env, nameObj);
  }

  if (jni_check_and_clear_exception(env,
                                    "requestAndroidFile CallVoidMethod")) {
    jni_release_env(&scope, "requestAndroidFile");
    return failAndroidFileRequest(lockCount);
  }

  jni_release_env(&scope, "requestAndroidFile");

  pthread_mutex_lock(&fileMutex);
  LOGI("requestAndroidFile: Waiting for file result...");
  while (!fileReady && !fileCancelled) {
    pthread_cond_wait(&fileCond, &fileMutex);
  }
  int fd = fileDescriptor;
  isCoreBlockingForIo = false;
  LOGI("requestAndroidFile: Resumed, fd=%d, cancelled=%d", fd, fileCancelled);
  pthread_mutex_unlock(&fileMutex);

  return restoreScreenMutexAndReturn(lockCount, fd);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_onFileSelectedNative(
    JNIEnv *env, jobject thiz, jint fd) {
  (void)env;
  (void)thiz;
  LOGI("onFileSelectedNative(fd=%d)", fd);
  pthread_mutex_lock(&fileMutex);
  fileDescriptor = fd;
  fileReady = true;
  pthread_cond_signal(&fileCond);
  pthread_mutex_unlock(&fileMutex);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_onFileCancelledNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  LOGI("onFileCancelledNative()");
  pthread_mutex_lock(&fileMutex);
  fileCancelled = true;
  pthread_cond_signal(&fileCond);
  pthread_mutex_unlock(&fileMutex);
}