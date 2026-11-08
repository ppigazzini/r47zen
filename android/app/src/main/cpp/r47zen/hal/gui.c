#include "c47.h"
#include "jni_bridge.h"
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

void calcModeNormalGui(void) {}
void calcModeAimGui(void) {}
void calcModeTamGui(void) {}

// Android specific implementation of setupUI
// This must handle the core allocations that GTK version does in setupUI.
void setupUI(void) {
  LOGI("setupUI: start");
  // screenData is handled in init_lcd_buffers

  // We let doFnReset handle the main RAM and buffer allocations
  // because its logic for splitting errorMessage/aimBuffer is complex.
  LOGI("setupUI: done (buffers will be handled by doFnReset)");
}

// While r47_capture_clipboard_locked runs one of upstream's copies, the text the
// copy hands to gtk_clipboard_set_text (android_mocks.h) goes to the capture.
// GTK's key handler is the only other caller upstream has, and the app does not
// build it, so a copy outside a capture has no clipboard to reach: it is dropped
// and logged.
static char **clipboardCapture = NULL;

void r47_take_clipboard_text(const char *text) {
  if (clipboardCapture == NULL) {
    LOGE("r47_take_clipboard_text: a copy outside the Copy menu was dropped");
    return;
  }
  // A copy first clears the clipboard with an empty text, then sets its own.
  free(*clipboardCapture);
  *clipboardCapture = strdup(text);
}

char *r47_capture_clipboard_locked(void (*copy)(void)) {
  char *text = NULL;
  clipboardCapture = &text;
  copy();
  clipboardCapture = NULL;
  return text;
}
