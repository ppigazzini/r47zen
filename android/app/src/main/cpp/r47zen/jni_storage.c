#include "jni_bridge.h"

#include <stdatomic.h>
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

// The name of the document the picker gave, which hal/io.c records for a read as
// GTK records a file's name. The answer writes it under fileMutex, and only while
// the core thread waits in requestAndroidFile, so that thread reads it unlocked
// once it resumes.
static char pickedFileName[256];

const char *r47_picked_file_name(void) { return pickedFileName; }

static int restoreScreenMutexAndReturn(int lockCount, int fd) {
  LOGI("requestAndroidFile: Re-acquiring screenMutex (%d times)", lockCount);
  for (int index = 0; index < lockCount; index++) {
    pthread_mutex_lock(&screenMutex);
  }

  return fd;
}

static int failAndroidFileRequest(int lockCount) {
  pthread_mutex_lock(&fileMutex);
  atomic_store_explicit(&isCoreBlockingForIo, false, memory_order_relaxed);
  fileReady = false;
  fileCanceled = true;
  fileDescriptor = -1;
  pthread_mutex_unlock(&fileMutex);

  return restoreScreenMutexAndReturn(lockCount, -1);
}

int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
  LOGI("requestAndroidFile(isSave=%d, fileType=%d)", isSave, fileType);

  pickedFileName[0] = '\0';
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
  atomic_store_explicit(&isCoreBlockingForIo, true, memory_order_relaxed);
  fileReady = false;
  fileCanceled = false;
  fileDescriptor = -1;
  pthread_mutex_unlock(&fileMutex);

  jstring nameObj = jni_new_string_utf(env, defaultName ? defaultName : "", "",
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

  if (jni_check_and_clear_exception(env, "requestAndroidFile CallVoidMethod")) {
    jni_release_env(&scope, "requestAndroidFile");
    return failAndroidFileRequest(lockCount);
  }

  jni_release_env(&scope, "requestAndroidFile");

  pthread_mutex_lock(&fileMutex);
  LOGI("requestAndroidFile: Waiting for file result...");
  while (!fileReady && !fileCanceled) {
    pthread_cond_wait(&fileCond, &fileMutex);
  }
  int fd = fileDescriptor;
  atomic_store_explicit(&isCoreBlockingForIo, false, memory_order_relaxed);
  LOGI("requestAndroidFile: Resumed, fd=%d, canceled=%d", fd, fileCanceled);
  pthread_mutex_unlock(&fileMutex);

  return restoreScreenMutexAndReturn(lockCount, fd);
}

// hal/io.c records at most a name's last 19 bytes, so a longer name keeps its end.
static void keepNameEnd(const char *name) {
  const size_t length = strlen(name);
  const size_t kept =
      length < sizeof(pickedFileName) ? length : sizeof(pickedFileName) - 1;
  memcpy(pickedFileName, name + length - kept, kept);
  pickedFileName[kept] = '\0';
}

// Report whether a core thread took the descriptor. A process killed behind the
// picker comes back with the answer and no request, and the next request would drop
// a descriptor left here without closing it. A save's worker reads until that
// descriptor closes, so it would wait for the life of the process.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_onFileSelectedNative(
    JNIEnv *env, jobject thiz, jint fd, jstring name) {
  (void)thiz;
  LOGI("onFileSelectedNative(fd=%d)", fd);
  const char *chars =
      name != NULL ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
  pthread_mutex_lock(&fileMutex);
  bool waiting =
      atomic_load_explicit(&isCoreBlockingForIo, memory_order_relaxed);
  if (waiting) {
    fileDescriptor = fd;
    keepNameEnd(chars != NULL ? chars : "");
    fileReady = true;
    pthread_cond_signal(&fileCond);
  }
  pthread_mutex_unlock(&fileMutex);
  if (chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, name, chars);
  }
  return waiting ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_onFileCanceledNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  LOGI("onFileCanceledNative()");
  pthread_mutex_lock(&fileMutex);
  fileCanceled = true;
  pthread_cond_signal(&fileCond);
  pthread_mutex_unlock(&fileMutex);
}
