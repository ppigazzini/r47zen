#include "jni_bridge.h"

#include <math.h>
#include <stdio.h>

extern void btnFnPressed(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnFnReleased(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnPressed(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnReleased(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void fnStopProgram(uint16_t unusedButMandatoryParameter);

static char currentPressedKeyStr[4] = {0};
static int currentPressedKeyCode = 0;

// Keep sensitivity calibration in one layer (native) for deterministic tuning.
static const float r47_graph_pan_gain = 1.00f;
static const float r47_graph_zoom_gain = 1.00f;
static const float r47_graph_scale_epsilon = 0.0001f;

extern void fnEqSolvGraph(uint16_t func);
extern int8_t PLOT_ZMY;

static bool r47_graph_touch_supported_locked(void) {
  if (!ram || isCoreBlockingForIo || calcMode != CM_GRAPH) {
    return false;
  }

  int16_t menuId = currentMenu();
  return menuId == -MNU_PLOT_FUNC || menuId == -MNU_GRAPHS;
}

static void r47_draw_graph_from_lu_locked(void) {
  // Upstream graph rendering only honors LY/UY when ZOOM is in override mode.
  // Keep touch-driven Y bounds on that path before Draw-LU solve.
  PLOT_ZMY = zoomOverride;
  fnEqSolvGraph(EQ_PLOT_LU);

  // Draw-LU already marks redraw state and refresh reasons. Force only LCD
  // transport here to avoid competing refresh reason paths.
  refreshLcd(NULL);
  lcd_refresh();
}

static void r47_sync_graph_bounds_reserved_vars_locked(void) {
  copySourceRegisterToDestRegister(REGISTER_X, TEMP_REGISTER_1);

  convertDoubleToReal34Register((double)x_min, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_LX);

  convertDoubleToReal34Register((double)x_max, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_UX);

  convertDoubleToReal34Register((double)y_min, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_LY);

  convertDoubleToReal34Register((double)y_max, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_UY);

  copySourceRegisterToDestRegister(TEMP_REGISTER_1, REGISTER_X);
}

static bool r47_apply_graph_pan_locked(float dxNorm, float dyNorm) {
  if (!r47_graph_touch_supported_locked()) {
    return false;
  }
  if (!isfinite(dxNorm) || !isfinite(dyNorm)) {
    return false;
  }

  const float spanX = x_max - x_min;
  const float spanY = y_max - y_min;
  if (!isfinite(spanX) || !isfinite(spanY) || fabsf(spanX) < 1.0e-6f ||
      fabsf(spanY) < 1.0e-6f) {
    return false;
  }

  const float shiftX = -dxNorm * spanX * r47_graph_pan_gain;
  const float shiftY = dyNorm * spanY * r47_graph_pan_gain;
  if (!isfinite(shiftX) || !isfinite(shiftY) ||
      (fabsf(shiftX) < 1.0e-7f && fabsf(shiftY) < 1.0e-7f)) {
    return false;
  }

  x_min += shiftX;
  x_max += shiftX;
  y_min += shiftY;
  y_max += shiftY;
  if (!isfinite(x_min) || !isfinite(x_max) || !isfinite(y_min) ||
      !isfinite(y_max)) {
    return false;
  }

  r47_sync_graph_bounds_reserved_vars_locked();
  r47_draw_graph_from_lu_locked();
  return true;
}

static bool r47_apply_graph_pinch_zoom_locked(float scaleFactor) {
  if (!r47_graph_touch_supported_locked()) {
    return false;
  }
  if (!isfinite(scaleFactor) || scaleFactor <= 0.0f) {
    return false;
  }

  const float adjustedScale =
      1.0f + (scaleFactor - 1.0f) * r47_graph_zoom_gain;
  if (!isfinite(adjustedScale) ||
      fabsf(adjustedScale - 1.0f) < r47_graph_scale_epsilon ||
      adjustedScale <= 0.0f) {
    return false;
  }

  const float spanX = x_max - x_min;
  const float spanY = y_max - y_min;
  if (!isfinite(spanX) || !isfinite(spanY) || fabsf(spanX) < 1.0e-6f ||
      fabsf(spanY) < 1.0e-6f) {
    return false;
  }

  const float centerX = 0.5f * (x_min + x_max);
  const float centerY = 0.5f * (y_min + y_max);
  const float newSpanX = spanX / adjustedScale;
  const float newSpanY = spanY / adjustedScale;
  if (!isfinite(centerX) || !isfinite(centerY) || !isfinite(newSpanX) ||
      !isfinite(newSpanY) || newSpanX < 1.0e-6f || newSpanY < 1.0e-6f) {
    return false;
  }

  x_min = centerX - 0.5f * newSpanX;
  x_max = centerX + 0.5f * newSpanX;
  y_min = centerY - 0.5f * newSpanY;
  y_max = centerY + 0.5f * newSpanY;
  if (!isfinite(x_min) || !isfinite(x_max) || !isfinite(y_min) ||
      !isfinite(y_max)) {
    return false;
  }

  r47_sync_graph_bounds_reserved_vars_locked();
  r47_draw_graph_from_lu_locked();
  return true;
}

void r47_send_sim_function(int funcId) {
  pthread_mutex_lock(&screenMutex);
  extern void runFunction(int16_t id);
  runFunction((int16_t)funcId);
  pthread_mutex_unlock(&screenMutex);
}

void r47_send_sim_menu(int menuId) {
  pthread_mutex_lock(&screenMutex);
  extern void showSoftmenu(int16_t id);
  showSoftmenu((int16_t)menuId);
  refreshScreen(1);
  pthread_mutex_unlock(&screenMutex);
}

void r47_send_sim_key(const char *keyId, bool isFn, bool isRelease) {
  if (!ram || isCoreBlockingForIo || keyId == NULL) {
    return;
  }

  // This string bridge stays as a cold simulation/test surface. Live keypad
  // input uses the fixed-width sendKey() press/release path below.
  pthread_mutex_lock(&screenMutex);
  if (isFn) {
    if (isRelease) {
      extern void btnFnClickedR(void *w, void *data);
      btnFnClickedR(NULL, (void *)keyId);
    } else {
      extern void btnFnClickedP(void *w, void *data);
      btnFnClickedP(NULL, (void *)keyId);
    }
  } else {
    if (isRelease) {
      extern void btnClickedR(void *w, void *data);
      btnClickedR(NULL, (void *)keyId);
    } else {
      extern void btnClickedP(void *w, void *data);
      btnClickedP(NULL, (void *)keyId);
    }
  }
  pthread_mutex_unlock(&screenMutex);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimFuncNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;
  r47_send_sim_function((int)funcId);
}

JNIEXPORT jboolean JNICALL
Java_com_example_r47_MainActivity_applyGraphPanNative(JNIEnv *env, jobject thiz,
                                                      jfloat dxNorm,
                                                      jfloat dyNorm) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_apply_graph_pan_locked((float)dxNorm, (float)dyNorm);
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_r47_MainActivity_applyGraphPinchZoomNative(JNIEnv *env,
                                                            jobject thiz,
                                                            jfloat scaleFactor) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_apply_graph_pinch_zoom_locked((float)scaleFactor);
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimMenuNative(
    JNIEnv *env, jobject thiz, jint menuId) {
  (void)env;
  (void)thiz;
  r47_send_sim_menu((int)menuId);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease) {
  (void)thiz;
  if (!ram || isCoreBlockingForIo) {
    return;
  }

  const char *nativeKeyId = (*env)->GetStringUTFChars(env, keyId, 0);
  if (!nativeKeyId ||
      jni_check_and_clear_exception(env,
                                    "sendSimKeyNative GetStringUTFChars")) {
    return;
  }

  r47_send_sim_key(nativeKeyId, isFn == JNI_TRUE, isRelease == JNI_TRUE);
  (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
}

JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_sendKey(
    JNIEnv *env, jobject thiz, jint keyCode) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return;
  }

  onUIActivity();

  if (keyCode > 0) {
    LOGD("sendKey: DOWN %d", keyCode);

    currentPressedKeyCode = keyCode;
    pthread_mutex_lock(&screenMutex);
    if (keyCode >= 38 && keyCode <= 43) {
      snprintf(currentPressedKeyStr, sizeof(currentPressedKeyStr), "%c",
               keyCode - 38 + '1');
      btnFnPressed(NULL, &pressEvent, currentPressedKeyStr);
    } else if (keyCode >= 1 && keyCode <= 37) {
      snprintf(currentPressedKeyStr, sizeof(currentPressedKeyStr), "%02u",
               keyCode - 1);
      btnPressed(NULL, &pressEvent, currentPressedKeyStr);
    }
    pthread_mutex_unlock(&screenMutex);
    return;
  }

  LOGD("sendKey: UP (last=%d)", currentPressedKeyCode);

  pthread_mutex_lock(&screenMutex);
  if (currentPressedKeyCode >= 38 && currentPressedKeyCode <= 43) {
    btnFnReleased(NULL, &releaseEvent, currentPressedKeyStr);
  } else if (currentPressedKeyCode >= 1 && currentPressedKeyCode <= 37) {
    btnReleased(NULL, &releaseEvent, currentPressedKeyStr);
  }
  pthread_mutex_unlock(&screenMutex);
  currentPressedKeyCode = 0;
  currentPressedKeyStr[0] = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_r47_MainActivity_requestStopProgramNative(JNIEnv *env,
                                                           jobject thiz) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return JNI_FALSE;
  }

  onUIActivity();
  if (programRunStop != PGM_RUNNING && programRunStop != PGM_PAUSED) {
    return JNI_FALSE;
  }

  fnStopProgram(0);
  r47_request_stop_refresh();
  return JNI_TRUE;
}
