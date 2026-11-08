// The Copy menu's text: the X register, the stack registers, and all registers,
// as upstream's own clipboard copies in screen.c build it, each sized from the
// registers it holds. A null string tells the shell the copy could not be built.
#include "jni_bridge.h"

#include <stdlib.h>

static jstring copyText(JNIEnv *env, void (*copy)(void), const char *context) {
  if (!ram) {
    return jni_new_string_utf(env, "", NULL, context);
  }

  pthread_mutex_lock(&screenMutex);
  char *text = r47_capture_clipboard_locked(copy);
  pthread_mutex_unlock(&screenMutex);
  if (text == NULL) {
    return NULL;
  }
  jstring result = jni_new_string_utf(env, text, NULL, context);
  free(text);
  return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardXRegisterNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  return copyText(env, copyRegisterXToClipboard,
                  "getClipboardXRegisterNative NewStringUTF");
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardStackRegistersNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  return copyText(env, copyStackRegistersToClipboard,
                  "getClipboardStackRegistersNative NewStringUTF");
}

JNIEXPORT jstring JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getClipboardAllRegistersNative(
    JNIEnv *env, jobject thiz) {
  (void)thiz;
  return copyText(env, copyAllRegistersToClipboard,
                  "getClipboardAllRegistersNative NewStringUTF");
}
