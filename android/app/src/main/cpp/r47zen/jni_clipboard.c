// The Copy menu's text: the X register, the stack registers, and all registers, as
// upstream's own clipboard copies build it in screen.c, each sized from the
// registers it holds. It runs on the calculator thread at rest and answers through
// onClipboardText. The display's text for a screen reader is built the same way and
// answers through onDisplayText.
#include "host_services.h"
#include "jni_bridge.h"

#include <stdlib.h>
#include <string.h>

char *r47_clipboard_text(int what) {
  if (!ram) {
    return NULL;
  }
  switch (what) {
  case R47_COPY_X:
    return r47_capture_clipboard(copyRegisterXToClipboard);
  case R47_COPY_STACK:
    return r47_capture_clipboard(copyStackRegistersToClipboard);
  case R47_COPY_ALL:
    return r47_capture_clipboard(copyAllRegistersToClipboard);
  default:
    LOGE("r47_clipboard_text: unknown copy kind %d", what);
    return NULL;
  }
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_copyRegistersNative(
    JNIEnv *env, jclass clazz, jint what) {
  (void)env;
  (void)clazz;
  return r47_service_copy((int)what) ? JNI_TRUE : JNI_FALSE;
}

// The display for a screen reader. The LCD is a bitmap, so the text comes
// from the state the screen draws from. While an error shows, upstream keeps its
// code in lastErrorCode and clears it on the next key; a TAM prompt's entry is in
// tamBuffer; a number or alpha text being typed is in aimBuffer, while X still holds
// the value before it. Otherwise X, as Copy X gives it: the register's value, which
// the screen may round.
static char *coreTextToUtf8(const char *coreText) {
  // One core glyph takes at most two bytes and four in UTF-8.
  char *utf8 = malloc(strlen(coreText) * 2U + 1U);
  if (utf8 != NULL) {
    stringToUtf8(coreText, (uint8_t *)utf8);
  }
  return utf8;
}

static void cutToWholeCharacter(char *text, size_t max) {
  size_t length = strlen(text);
  if (length <= max) {
    return;
  }
  // Back off any UTF-8 continuation bytes, so the cut keeps whole characters.
  while (max > 0 && ((unsigned char)text[max] & 0xC0U) == 0x80U) {
    max--;
  }
  text[max] = '\0';
}

char *r47_display_text(bool *isError) {
  R47_CALCULATOR_THREAD_ONLY();
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
    text = r47_capture_clipboard(copyRegisterXToClipboard);
  }
  if (text != NULL) {
    cutToWholeCharacter(text, R47_DISPLAY_TEXT_MAX);
  }
  return text;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_describeDisplayNative(
    JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return r47_service_describe_display() ? JNI_TRUE : JNI_FALSE;
}
