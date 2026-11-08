// The two GLib clocks the core reads, which GTK supplies to the simulator; the app
// links no GLib. They sit apart from android_runtime.c, which needs JNI, because
// the arm64 build of upstream's suite links them as well
// (run_upstream_suite_android_config.sh, R47_SUITE_TARGET=app), and it has no JNI.
#include <time.h>

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
