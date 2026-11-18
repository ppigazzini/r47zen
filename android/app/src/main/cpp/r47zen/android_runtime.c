#include "jni_bridge.h"

#include <stdatomic.h>
#include <stdlib.h>

pthread_mutex_t fileMutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t fileCond = PTHREAD_COND_INITIALIZER;
int fileDescriptor = -1;
bool fileReady = false;
bool fileCanceled = false;
// True while the calculator thread is parked in requestAndroidFile, waiting on
// fileCond. It writes this under fileMutex, and a late answer reads it to tell
// whether anything still waits for the descriptor.
_Atomic bool isCoreBlockingForIo = false;

int16_t debugWindow = 0;
int16_t screenStride = 400;
bool_t screenChange = FALSE;
int currentBezel = 0;
calcKeyboard_t calcKeyboard[43];

// The core ends the process on the few errors it does not recover from: memory or the
// free list run out, or a test-program image does not fit. On a phone a plain exit
// leaves no trace, neither a log line nor a crash report, so every exit in this library
// is linked here (-Wl,--wrap=exit in CMakeLists.txt): it logs why and aborts, which
// Android records as a crash.
_Noreturn void __wrap_exit(int status) {
  LOGE("the calculator core ended the process with exit(%d)", status);
  abort();
}
