#include "c47.h"
#include "host_services.h"
#include "jni_bridge.h"

#include <string.h>

void calcModeNormalGui(void) {}
void calcModeAimGui(void) {}
void calcModeTamGui(void) {}

// doFnReset does the core allocations, because its split of errorMessage and
// aimBuffer is its own, and init_lcd_buffers owns the screen.
void setupUI(void) {}

// While r47_capture_clipboard runs one of upstream's copies, the text it builds goes
// to the capture instead of the clipboard, so the Copy menu can label it.
static char **clipboardCapture = NULL;

void guiSetClipboardText(const char *text) {
  if (clipboardCapture != NULL) {
    *clipboardCapture = strdup(text);
    return;
  }
  r47_upcall_set_clipboard(text);
}

// The calculator thread. Runs one of upstream's clipboard copies and returns its
// text, which the caller frees, or NULL when the copy could not allocate.
char *r47_capture_clipboard(void (*copy)(void)) {
  char *text = NULL;
  clipboardCapture = &text;
  copy();
  clipboardCapture = NULL;
  return text;
}
