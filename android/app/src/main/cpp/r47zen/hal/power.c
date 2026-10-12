// The power and exit hooks the core calls on DMCP hardware. Android has no
// auto-off or RTC to drive; powering the LCD off or leaving the program
// closes the activity through MainActivity.quitApp.
#include "jni_bridge.h"

void dmcpResetAutoOff() {}
void rtc_wakeup_delay() {}
void LCD_power_on() {}

void triggerQuit() {
  LOGI("triggerQuit called");
  if (!g_mainActivityObj || !g_jvm) {
    LOGE("triggerQuit: MainActivity or JVM reference is NULL");
    return;
  }

  jni_env_scope_t scope;
  if (!jni_acquire_env(&scope, "triggerQuit")) {
    return;
  }
  JNIEnv *env = scope.env;

  jclass clazz = (*env)->GetObjectClass(env, g_mainActivityObj);
  if (!jni_result_ok(env, clazz, "GetObjectClass(triggerQuit)")) {
    jni_release_env(&scope, "triggerQuit");
    return;
  }

  jmethodID methodId = (*env)->GetMethodID(env, clazz, "quitApp", "()V");
  if (jni_result_ok(env, (const void *)methodId,
                    "GetMethodID(triggerQuit quitApp)")) {
    LOGI("triggerQuit: Calling Java quitApp()");
    (*env)->CallVoidMethod(env, g_mainActivityObj, methodId);
    jni_check_and_clear_exception(env, "CallVoidMethod(triggerQuit quitApp)");
  } else {
    LOGE("triggerQuit: Could not find quitApp method ID");
  }
  (*env)->DeleteLocalRef(env, clazz);
  jni_release_env(&scope, "triggerQuit");
}

void LCD_power_off(int mode) {
  (void)mode;
  LOGI("LCD_power_off triggered");
  triggerQuit();
}

void draw_power_off_image(int mode) { (void)mode; }

void pgm_exit(void) {
  LOGI("pgm_exit triggered");
  triggerQuit();
}
