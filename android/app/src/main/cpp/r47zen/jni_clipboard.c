// The clipboard export JNI surface: the X register, the stack registers, and
// all registers as the text upstream's desktop clipboard actions produce.
#include "jni_bridge.h"

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardXRegisterNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  if (!ram) {
    return jni_new_string_utf(
        env, "", NULL, "getClipboardXRegisterNative default NewStringUTF");
  }

  pthread_mutex_lock(&screenMutex);
  extern char *getClipboardXRegisterString(void);
  char *registerText = getClipboardXRegisterString();
  jstring result =
      jni_new_string_utf(env, registerText ? registerText : "", "",
                         "getClipboardXRegisterNative NewStringUTF");
  pthread_mutex_unlock(&screenMutex);
  return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardStackRegistersNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  if (!ram) {
    return jni_new_string_utf(
        env, "", NULL, "getClipboardStackRegistersNative default NewStringUTF");
  }

  pthread_mutex_lock(&screenMutex);
  extern char *getClipboardStackRegistersString(void);
  char *registerText = getClipboardStackRegistersString();
  jstring result =
      jni_new_string_utf(env, registerText ? registerText : "", "",
                         "getClipboardStackRegistersNative NewStringUTF");
  pthread_mutex_unlock(&screenMutex);
  return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardAllRegistersNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  if (!ram) {
    return jni_new_string_utf(
        env, "", NULL, "getClipboardAllRegistersNative default NewStringUTF");
  }

  pthread_mutex_lock(&screenMutex);
  extern char *getClipboardAllRegistersString(void);
  char *registerText = getClipboardAllRegistersString();
  jstring result =
      jni_new_string_utf(env, registerText ? registerText : "", "",
                         "getClipboardAllRegistersNative NewStringUTF");
  pthread_mutex_unlock(&screenMutex);
  return result;
}
