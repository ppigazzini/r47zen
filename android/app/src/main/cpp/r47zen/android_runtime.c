#include "jni_bridge.h"
#include "r47_time.h"

#include <stdatomic.h>
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

// Lock-free cross-thread refresh signal. A JNI/keypad-thread writer requests a
// stop-refresh with no lock held (r47_request_stop_refresh) while the core thread
// reads-and-clears it under screenMutex (r47_apply_pending_stop_refresh_locked).
// This is the same shape as the lock-free display signals in hal/lcd.c: plain
// volatile leaves the concurrent access a data race under the C memory model, so
// it is a C11 relaxed atomic at identical codegen. A stale sample at most delays
// or repeats one stop-refresh and self-corrects.
static _Atomic bool g_r47_stop_refresh_pending = false;

typedef struct {
  guint id;
  guint interval_ms;
  uint32_t next_fire_ms;
  GSourceFunc callback;
  gpointer data;
  bool active;
} android_mock_timeout_t;

static android_mock_timeout_t g_android_mock_timeout = {0};
static guint g_android_mock_next_timeout_id = 1;
static uint32_t g_android_mock_next_gtk_iteration_ms = 0;

static gboolean androidMockTimeoutReady(void) {
  return g_android_mock_timeout.active &&
         g_android_mock_timeout.callback != NULL &&
         r47_ms_deadline_reached(sys_current_ms(),
                                 g_android_mock_timeout.next_fire_ms);
}

static gboolean pumpAndroidMockTimeout(void) {
  if (!g_android_mock_timeout.active || g_android_mock_timeout.callback == NULL) {
    return FALSE;
  }

  uint32_t now = sys_current_ms();
  if (!r47_ms_deadline_reached(now, g_android_mock_timeout.next_fire_ms)) {
    return FALSE;
  }

  gboolean keep_running =
      g_android_mock_timeout.callback(g_android_mock_timeout.data);
  if (!keep_running || !g_android_mock_timeout.active) {
    g_android_mock_timeout.active = false;
    g_android_mock_timeout.id = 0;
    g_android_mock_timeout.callback = NULL;
    return TRUE;
  }

  uint32_t next_fire =
      g_android_mock_timeout.next_fire_ms + g_android_mock_timeout.interval_ms;
  if (r47_ms_deadline_reached(now, next_fire)) {
    next_fire = now + g_android_mock_timeout.interval_ms;
  }
  g_android_mock_timeout.next_fire_ms = next_fire;
  return TRUE;
}

static void driveAndroidMockEventLoop(gboolean may_block) {
  if (pumpAndroidMockTimeout()) {
    return;
  }

  uint32_t wait_ms = may_block ? 1u : 0u;
  if (may_block && g_android_mock_timeout.active) {
    uint32_t until_fire = r47_ms_until_deadline(
        sys_current_ms(), g_android_mock_timeout.next_fire_ms);
    if (until_fire > 0u) {
      wait_ms = until_fire;
    }
    if (wait_ms > 16u) {
      wait_ms = 16u;
    }
  }

  yieldToAndroidWithMs((int)wait_ms);
  pumpAndroidMockTimeout();
}

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

void r47_request_stop_refresh(void) {
  atomic_store_explicit(&g_r47_stop_refresh_pending, true, memory_order_relaxed);
}

bool r47_apply_pending_stop_refresh_locked(void) {
  if (!ram) {
    return false;
  }
  if (!atomic_exchange_explicit(&g_r47_stop_refresh_pending, false,
                                memory_order_relaxed)) {
    return false;
  }

  screenUpdatingMode = SCRUPD_AUTO;
  reDraw = true;
  refreshScreen(190);
  refreshLcd(NULL);
  lcd_refresh();
  nextScreenRefresh = sys_current_ms() + 100;
  return true;
}

void yieldToAndroidWithMs(int ms) {
  if (!ram) {
    return;
  }

  uint32_t now = sys_current_ms();
  if (r47_ms_deadline_reached(now, nextTimerRefresh)) {
    // Long-running native work can yield in 1 ms bursts, so advance timers
    // here instead of relying only on the separate core-thread tick loop.
    refreshTimer(NULL);
    nextTimerRefresh = now + 5;
  }

  if (!r47_apply_pending_stop_refresh_locked()) {
    refreshLcd(NULL);
    lcd_refresh();
  }

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

  r47_apply_pending_stop_refresh_locked();
}

void yieldToAndroid(void) { yieldToAndroidWithMs(1); }

gboolean gtk_events_pending(void) {
  return androidMockTimeoutReady() ||
         r47_ms_deadline_reached(sys_current_ms(),
                                 g_android_mock_next_gtk_iteration_ms);
}

gboolean gtk_main_iteration(void) {
  g_android_mock_next_gtk_iteration_ms = sys_current_ms() + 5u;
  driveAndroidMockEventLoop(FALSE);
  return TRUE;
}

guint g_timeout_add(guint interval, GSourceFunc function, gpointer data) {
  if (function == NULL) {
    return 0;
  }

  guint normalized_interval = interval == 0 ? 1u : interval;
  guint id = g_android_mock_next_timeout_id++;
  if (g_android_mock_next_timeout_id == 0) {
    g_android_mock_next_timeout_id = 1;
  }

  g_android_mock_timeout.id = id;
  g_android_mock_timeout.interval_ms = normalized_interval;
  g_android_mock_timeout.next_fire_ms = sys_current_ms() + normalized_interval;
  g_android_mock_timeout.callback = function;
  g_android_mock_timeout.data = data;
  g_android_mock_timeout.active = true;
  return id;
}

guint gdk_threads_add_timeout(guint interval, GSourceFunc function,
                              gpointer data) {
  return g_timeout_add(interval, function, data);
}

gboolean g_source_remove(guint tag) {
  if (!g_android_mock_timeout.active || g_android_mock_timeout.id != tag) {
    return FALSE;
  }

  g_android_mock_timeout.active = false;
  g_android_mock_timeout.id = 0;
  g_android_mock_timeout.callback = NULL;
  return TRUE;
}

gboolean g_main_context_iteration(GMainContext *context, gboolean may_block) {
  (void)context;
  driveAndroidMockEventLoop(may_block);
  return TRUE;
}
