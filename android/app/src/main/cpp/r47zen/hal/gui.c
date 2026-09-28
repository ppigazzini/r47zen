#include "c47.h"
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

void calcModeNormalGui (void) {}
void calcModeAimGui    (void) {}
void calcModeTamGui    (void) {}

// Android specific implementation of setupUI
// This must handle the core allocations that GTK version does in setupUI.
void setupUI(void) {
    LOGI("setupUI: start");
    // screenData is handled in init_lcd_buffers

    // We let doFnReset handle the main RAM and buffer allocations
    // because its logic for splitting errorMessage/aimBuffer is complex.
    LOGI("setupUI: done (buffers will be handled by doFnReset)");
}
