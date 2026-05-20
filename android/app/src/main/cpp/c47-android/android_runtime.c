#include "jni_bridge.h"

#include <unistd.h>

pthread_mutex_t fileMutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t fileCond = PTHREAD_COND_INITIALIZER;
pthread_mutex_t screenMutex;
int fileDescriptor = -1;
bool fileReady = false;
bool fileCancelled = false;
bool isCoreBlockingForIo = false;

int16_t debugWindow = 0;
int16_t screenStride = 400;
bool_t screenChange = FALSE;
int currentBezel = 0;
calcKeyboard_t calcKeyboard[43];
gboolean ui_is_active = FALSE;

uint32_t nextTimerRefresh = 0;
uint32_t nextScreenRefresh = 0;

GdkEvent pressEvent;
GdkEvent releaseEvent;

void onUIActivity(void) { ui_is_active = TRUE; }

gint64 g_get_monotonic_time(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (gint64)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000LL;
}

gint64 g_get_real_time(void) {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return (gint64)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000LL;
}

uint32_t sys_current_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

void yieldToAndroidWithMs(int ms) {
  if (!ram) {
    return;
  }

  refreshLcd(NULL);
  lcd_refresh();

  int lockCount = 0;
  while (pthread_mutex_unlock(&screenMutex) == 0) {
    lockCount++;
  }

  processCoreTasksNative();
  if (ms > 0) {
    usleep(ms * 1000);
  } else {
    usleep(1000);
  }

  while (lockCount > 0) {
    pthread_mutex_lock(&screenMutex);
    lockCount--;
  }
}

void yieldToAndroid(void) { yieldToAndroidWithMs(1); }