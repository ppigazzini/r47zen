// The GTK and GLib event-loop calls upstream's PC_BUILD core makes (calcMode.c,
// screen.c, print.c, input.c, addons.c), for the arm64 build of upstream's suite
// (run_upstream_suite_android_config.sh, R47_SUITE_TARGET=app). The app defines
// them on top of JNI, in android_runtime.c and hal/power.c, so the suite cannot
// link those. The suite runs headless, with no events, no timer source, and no
// window to quit, so each answers as an empty loop does. Built with
// android_mocks.h forced in, as the glue is.

gboolean gtk_events_pending(void) { return FALSE; }

gboolean gtk_main_iteration(void) { return FALSE; }

gboolean g_main_context_iteration(GMainContext *context, gboolean may_block) {
  (void)context;
  (void)may_block;
  return FALSE;
}

// 0 is no source: nothing fires, and removing it finds nothing.
guint g_timeout_add(guint interval, GSourceFunc function, gpointer data) {
  (void)interval;
  (void)function;
  (void)data;
  return 0;
}

gboolean g_source_remove(guint tag) {
  (void)tag;
  return FALSE;
}

void triggerQuit(void) {}
