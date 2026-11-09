// The Copy menu's text: the X register, the stack registers, and all registers,
// as upstream's own clipboard copies in screen.c build it, each sized from the
// registers it holds. A null string tells the shell the copy could not be built.
// The display's text for a screen reader is built the same way and answers through
// onDisplayText.
#include "jni_bridge.h"

#include <stdlib.h>
#include <string.h>

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

// The display for a screen reader. The LCD is a bitmap, so the text comes from the
// state the screen draws from. While an error shows, upstream keeps its code in
// lastErrorCode and clears it on the next key; a TAM prompt's entry is in tamBuffer;
// a number or alpha text being typed is in aimBuffer, while X still holds the value
// before it. Otherwise X, as Copy X gives it: the register's value, which the screen
// may round.
static char *coreTextToUtf8(const char *coreText) {
  // One core glyph takes at most two bytes, and four in UTF-8.
  char *utf8 = malloc(strlen(coreText) * 2U + 1U);
  if (utf8 != NULL) {
    stringToUtf8(coreText, (uint8_t *)utf8);
  }
  return utf8;
}

static void cutToWholeCharacter(char *text, size_t max) {
  if (strlen(text) <= max) {
    return;
  }
  // Back off any UTF-8 continuation bytes, so the cut keeps whole characters.
  while (max > 0 && ((unsigned char)text[max] & 0xC0U) == 0x80U) {
    max--;
  }
  text[max] = '\0';
}

char *r47_display_text_locked(bool *isError) {
  *isError = false;
  if (!ram) {
    return NULL;
  }
  char *text;
  if (lastErrorCode != 0) {
    *isError = true;
    text = coreTextToUtf8(errorMessageOf(lastErrorCode));
  } else if (tam.mode) {
    text = coreTextToUtf8(tamBuffer);
  } else if (calcMode == CM_NIM || calcMode == CM_AIM) {
    text = coreTextToUtf8(aimBuffer);
  } else {
    text = r47_capture_clipboard_locked(copyRegisterXToClipboard);
  }
  if (text != NULL) {
    cutToWholeCharacter(text, R47_DISPLAY_TEXT_MAX);
  }
  return text;
}

// A core task: reads the display under screenMutex, then answers through
// onDisplayText with the lock released.
JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_describeDisplayNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return;
  }
  bool isError = false;
  pthread_mutex_lock(&screenMutex);
  char *text = r47_display_text_locked(&isError);
  pthread_mutex_unlock(&screenMutex);
  r47_show_display_text(isError, text != NULL ? text : "");
  free(text);
}
