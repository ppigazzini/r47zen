#include "jni_bridge.h"

#include <math.h>
#include <stdatomic.h>
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
static const float r47_graph_pan_input_limit = 1.0f;
static const float r47_graph_scale_factor_min = 0.4f;
static const float r47_graph_scale_factor_max = 2.5f;
static const float r47_graph_bounds_limit = 1.0e38f;
static const float r47_graph_default_min = -10.0f;
static const float r47_graph_default_max = 10.0f;

extern void fnEqSolvGraph(uint16_t func);
extern void graph_reset(void);
extern int8_t PLOT_ZMY;

// Upstream promoted the graph window bounds from float globals to real_t *const
// decNumber pointers, so the const pointers cannot be reassigned and their
// targets are not plain floats. Keep the touch gesture math in float for
// deterministic sensitivity tuning by reading each bound into a float here and
// writing computed results back through the real API. fnRealToDouble already
// maps special reals to NaN/+-Inf doubles, so isfinite() checks stay valid.
float r47_graph_bound_to_float(const real_t *value) {
  double d;
  realToDouble(value, &d);
  return (float)d;
}

void r47_graph_bound_from_float(float value, real_t *dst) {
  char buff[64];
  if (isnan(value)) {
    stringToReal("NaN", dst, &ctxtReal39);
  } else if (isinf(value)) {
    stringToReal(value < 0.0f ? "-Inf" : "Inf", dst, &ctxtReal39);
  } else {
    snprintf(buff, sizeof(buff), "%.17g", (double)value);
    stringToReal(buff, dst, &ctxtReal39);
  }
}

static bool r47_graph_touch_supported_locked(void) {
  if (!ram ||
      atomic_load_explicit(&isCoreBlockingForIo, memory_order_relaxed) ||
      calcMode != CM_GRAPH) {
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

// Double-tap graph reset for function (EQN) plots only. It is a gesture
// shortcut for the PLTRST menu key on the same surface as pinch/pan
// (r47_graph_touch_supported_locked): graph_reset() restores auto Y-scaling and
// clears the zoom override and plot decorations - the LX/UX X domain is
// preserved, matching PLTRST - then the standard Draw-LU solve re-renders.
// Statistical and program-drawn (.p47) plots are deliberately excluded: their
// stat redraw does not transport to the Android display from this JNI context
// so reset there stays on the softkey.
static bool r47_reset_graph_locked(void) {
  if (!r47_graph_touch_supported_locked()) {
    return false;
  }

  graph_reset();
  fnEqSolvGraph(EQ_PLOT_LU);
  refreshLcd(NULL);
  lcd_refresh();
  return true;
}

static void r47_sync_graph_bounds_reserved_vars_locked(void) {
  double bound;
  copySourceRegisterToDestRegister(REGISTER_X, TEMP_REGISTER_1);

  realToDouble(x_min, &bound);
  convertDoubleToReal34Register(bound, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_LX);

  realToDouble(x_max, &bound);
  convertDoubleToReal34Register(bound, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_UX);

  realToDouble(y_min, &bound);
  convertDoubleToReal34Register(bound, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_LY);

  realToDouble(y_max, &bound);
  convertDoubleToReal34Register(bound, REGISTER_X);
  copySourceRegisterToDestRegister(REGISTER_X, RESERVED_VARIABLE_UY);

  copySourceRegisterToDestRegister(TEMP_REGISTER_1, REGISTER_X);
}

static void r47_reset_graph_bounds_defaults_locked(void) {
  r47_graph_bound_from_float(r47_graph_default_min, x_min);
  r47_graph_bound_from_float(r47_graph_default_max, x_max);
  r47_graph_bound_from_float(r47_graph_default_min, y_min);
  r47_graph_bound_from_float(r47_graph_default_max, y_max);
}

bool r47_sanitize_graph_bounds_locked(void) {
  bool changed = false;
  float lx = r47_graph_bound_to_float(x_min);
  float ux = r47_graph_bound_to_float(x_max);
  float ly = r47_graph_bound_to_float(y_min);
  float uy = r47_graph_bound_to_float(y_max);

  if (!isfinite(lx) || !isfinite(ux) || !isfinite(ly) || !isfinite(uy) ||
      fabsf(ux - lx) < 1.0e-6f || fabsf(uy - ly) < 1.0e-6f ||
      lx <= -r47_graph_bounds_limit || lx >= r47_graph_bounds_limit ||
      ux <= -r47_graph_bounds_limit || ux >= r47_graph_bounds_limit ||
      ly <= -r47_graph_bounds_limit || ly >= r47_graph_bounds_limit ||
      uy <= -r47_graph_bounds_limit || uy >= r47_graph_bounds_limit) {
    r47_reset_graph_bounds_defaults_locked();
    changed = true;
  } else {
    if (lx > ux) {
      const float swapped = lx;
      lx = ux;
      ux = swapped;
      changed = true;
    }
    if (ly > uy) {
      const float swapped = ly;
      ly = uy;
      uy = swapped;
      changed = true;
    }
    if (changed) {
      r47_graph_bound_from_float(lx, x_min);
      r47_graph_bound_from_float(ux, x_max);
      r47_graph_bound_from_float(ly, y_min);
      r47_graph_bound_from_float(uy, y_max);
    }
  }

  r47_sync_graph_bounds_reserved_vars_locked();
  return changed;
}

// Return true if a finite, in-range window [nextXMin..nextYMax] should be
// committed (none NaN/inf, none beyond +/- r47_graph_bounds_limit).
static bool r47_graph_bounds_in_range(float nextXMin, float nextXMax,
                                      float nextYMin, float nextYMax) {
  return isfinite(nextXMin) && isfinite(nextXMax) && isfinite(nextYMin) &&
         isfinite(nextYMax) && nextXMin > -r47_graph_bounds_limit &&
         nextXMin < r47_graph_bounds_limit &&
         nextXMax > -r47_graph_bounds_limit &&
         nextXMax < r47_graph_bounds_limit &&
         nextYMin > -r47_graph_bounds_limit &&
         nextYMin < r47_graph_bounds_limit &&
         nextYMax > -r47_graph_bounds_limit &&
         nextYMax < r47_graph_bounds_limit;
}

// Apply a pan to the graph window in place. Pure bounds math: no gate (the
// caller gates) and no redraw (the caller redraws once). Returns true if the
// window changed.
static bool r47_pan_update_bounds_locked(float dxNorm, float dyNorm) {
  if (!isfinite(dxNorm) || !isfinite(dyNorm) ||
      fabsf(dxNorm) > r47_graph_pan_input_limit ||
      fabsf(dyNorm) > r47_graph_pan_input_limit) {
    return false;
  }

  const float curXMin = r47_graph_bound_to_float(x_min);
  const float curXMax = r47_graph_bound_to_float(x_max);
  const float curYMin = r47_graph_bound_to_float(y_min);
  const float curYMax = r47_graph_bound_to_float(y_max);

  const float spanX = curXMax - curXMin;
  const float spanY = curYMax - curYMin;
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

  const float nextXMin = curXMin + shiftX;
  const float nextXMax = curXMax + shiftX;
  const float nextYMin = curYMin + shiftY;
  const float nextYMax = curYMax + shiftY;
  if (!r47_graph_bounds_in_range(nextXMin, nextXMax, nextYMin, nextYMax)) {
    return false;
  }

  r47_graph_bound_from_float(nextXMin, x_min);
  r47_graph_bound_from_float(nextXMax, x_max);
  r47_graph_bound_from_float(nextYMin, y_min);
  r47_graph_bound_from_float(nextYMax, y_max);
  return true;
}

// Apply a center-preserving pinch zoom to the graph window in place. Pure
// bounds math: no gate and no redraw. Returns true if the window changed.
static bool r47_zoom_update_bounds_locked(float scaleFactor) {
  if (!isfinite(scaleFactor) || scaleFactor <= 0.0f ||
      scaleFactor < r47_graph_scale_factor_min ||
      scaleFactor > r47_graph_scale_factor_max) {
    return false;
  }

  const float adjustedScale = 1.0f + (scaleFactor - 1.0f) * r47_graph_zoom_gain;
  if (!isfinite(adjustedScale) ||
      fabsf(adjustedScale - 1.0f) < r47_graph_scale_epsilon ||
      adjustedScale <= 0.0f) {
    return false;
  }

  const float curXMin = r47_graph_bound_to_float(x_min);
  const float curXMax = r47_graph_bound_to_float(x_max);
  const float curYMin = r47_graph_bound_to_float(y_min);
  const float curYMax = r47_graph_bound_to_float(y_max);

  const float spanX = curXMax - curXMin;
  const float spanY = curYMax - curYMin;
  if (!isfinite(spanX) || !isfinite(spanY) || fabsf(spanX) < 1.0e-6f ||
      fabsf(spanY) < 1.0e-6f) {
    return false;
  }

  const float centerX = 0.5f * (curXMin + curXMax);
  const float centerY = 0.5f * (curYMin + curYMax);
  const float newSpanX = spanX / adjustedScale;
  const float newSpanY = spanY / adjustedScale;
  if (!isfinite(centerX) || !isfinite(centerY) || !isfinite(newSpanX) ||
      !isfinite(newSpanY) || newSpanX < 1.0e-6f || newSpanY < 1.0e-6f) {
    return false;
  }

  const float nextXMin = centerX - 0.5f * newSpanX;
  const float nextXMax = centerX + 0.5f * newSpanX;
  const float nextYMin = centerY - 0.5f * newSpanY;
  const float nextYMax = centerY + 0.5f * newSpanY;
  if (!r47_graph_bounds_in_range(nextXMin, nextXMax, nextYMin, nextYMax)) {
    return false;
  }

  r47_graph_bound_from_float(nextXMin, x_min);
  r47_graph_bound_from_float(nextXMax, x_max);
  r47_graph_bound_from_float(nextYMin, y_min);
  r47_graph_bound_from_float(nextYMax, y_max);
  return true;
}

static bool r47_apply_graph_pan_locked(float dxNorm, float dyNorm) {
  if (!r47_graph_touch_supported_locked() ||
      !r47_pan_update_bounds_locked(dxNorm, dyNorm)) {
    return false;
  }
  r47_sync_graph_bounds_reserved_vars_locked();
  r47_draw_graph_from_lu_locked();
  return true;
}

static bool r47_apply_graph_pinch_zoom_locked(float scaleFactor) {
  if (!r47_graph_touch_supported_locked() ||
      !r47_zoom_update_bounds_locked(scaleFactor)) {
    return false;
  }
  r47_sync_graph_bounds_reserved_vars_locked();
  r47_draw_graph_from_lu_locked();
  return true;
}

// Combined pan + zoom in one gate, one bounds commit, and one re-solve. The
// gesture flush coalesces a drag and a pinch into a single batch, so applying
// them together halves the heavy fnEqSolvGraph re-solve during fast combined
// play. Pan is applied first, then a center-preserving zoom around the panned
// center, matching the prior sequential apply minus the discarded intermediate
// draw.
static bool r47_apply_graph_pan_zoom_locked(float dxNorm, float dyNorm,
                                            float scaleFactor) {
  if (!r47_graph_touch_supported_locked()) {
    return false;
  }

  bool changed = false;
  if (r47_pan_update_bounds_locked(dxNorm, dyNorm)) {
    changed = true;
  }
  if (r47_zoom_update_bounds_locked(scaleFactor)) {
    changed = true;
  }
  if (!changed) {
    return false;
  }

  r47_sync_graph_bounds_reserved_vars_locked();
  r47_draw_graph_from_lu_locked();
  return true;
}

bool r47_graph_touch_no_nan_stress_locked(int iterations) {
  if (!ram || iterations <= 0) {
    return false;
  }

  const float savedXMin = r47_graph_bound_to_float(x_min);
  const float savedXMax = r47_graph_bound_to_float(x_max);
  const float savedYMin = r47_graph_bound_to_float(y_min);
  const float savedYMax = r47_graph_bound_to_float(y_max);
  const int8_t savedCalcMode = calcMode;
  const int16_t savedMenu = currentMenu();

  if (!isfinite(savedXMin) || !isfinite(savedXMax) || !isfinite(savedYMin) ||
      !isfinite(savedYMax) || fabsf(savedXMax - savedXMin) < 1.0e-6f ||
      fabsf(savedYMax - savedYMin) < 1.0e-6f) {
    r47_reset_graph_bounds_defaults_locked();
  }

  extern void showSoftmenu(int16_t id);
  calcMode = CM_GRAPH;
  showSoftmenu(-MNU_PLOT_FUNC);

  bool ok = true;
  for (int i = 0; i < iterations; i++) {
    const float extremePan = (i & 1) == 0 ? 1.0e20f : -1.0e20f;
    const float extremeScale = (i & 1) == 0 ? 1.0e20f : 1.0e-20f;
    const float beforeXMin = r47_graph_bound_to_float(x_min);
    const float beforeXMax = r47_graph_bound_to_float(x_max);
    const float beforeYMin = r47_graph_bound_to_float(y_min);
    const float beforeYMax = r47_graph_bound_to_float(y_max);

    bool panApplied = r47_apply_graph_pan_locked(extremePan, -extremePan);
    bool pinchApplied = r47_apply_graph_pinch_zoom_locked(extremeScale);

    const float afterXMin = r47_graph_bound_to_float(x_min);
    const float afterXMax = r47_graph_bound_to_float(x_max);
    const float afterYMin = r47_graph_bound_to_float(y_min);
    const float afterYMax = r47_graph_bound_to_float(y_max);

    if (panApplied || pinchApplied || afterXMin != beforeXMin ||
        afterXMax != beforeXMax || afterYMin != beforeYMin ||
        afterYMax != beforeYMax) {
      ok = false;
      break;
    }

    if (!isfinite(afterXMin) || !isfinite(afterXMax) || !isfinite(afterYMin) ||
        !isfinite(afterYMax) || afterXMin <= -r47_graph_bounds_limit ||
        afterXMin >= r47_graph_bounds_limit ||
        afterXMax <= -r47_graph_bounds_limit ||
        afterXMax >= r47_graph_bounds_limit ||
        afterYMin <= -r47_graph_bounds_limit ||
        afterYMin >= r47_graph_bounds_limit ||
        afterYMax <= -r47_graph_bounds_limit ||
        afterYMax >= r47_graph_bounds_limit) {
      ok = false;
      break;
    }
  }

  r47_graph_bound_from_float(savedXMin, x_min);
  r47_graph_bound_from_float(savedXMax, x_max);
  r47_graph_bound_from_float(savedYMin, y_min);
  r47_graph_bound_from_float(savedYMax, y_max);
  calcMode = savedCalcMode;
  if (savedMenu < 0) {
    showSoftmenu(savedMenu);
  }

  return ok;
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
  if (!ram ||
      atomic_load_explicit(&isCoreBlockingForIo, memory_order_relaxed) ||
      keyId == NULL) {
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

// Resolve a shell menu code from jni_bridge.h to its upstream softmenu id, or 0.
static int r47_shell_menu_item(int shellMenu) {
  switch (shellMenu) {
  case R47_SHELL_MENU_HOME:
    return -MNU_HOME;
  case R47_SHELL_MENU_MY_MENU:
    return -MNU_MyMenu;
  default:
    return 0;
  }
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_applyGraphPanNative(
    JNIEnv *env, jobject thiz, jfloat dxNorm, jfloat dyNorm) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_apply_graph_pan_locked((float)dxNorm, (float)dyNorm);
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_applyGraphPinchZoomNative(
    JNIEnv *env, jobject thiz, jfloat scaleFactor) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_apply_graph_pinch_zoom_locked((float)scaleFactor);
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_applyGraphPanZoomNative(
    JNIEnv *env, jobject thiz, jfloat dxNorm, jfloat dyNorm,
    jfloat scaleFactor) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_apply_graph_pan_zoom_locked((float)dxNorm, (float)dyNorm,
                                                 (float)scaleFactor);
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_resetGraphNative(JNIEnv *env,
                                                               jobject thiz) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&screenMutex);
  bool applied = r47_reset_graph_locked();
  pthread_mutex_unlock(&screenMutex);
  return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_sendSimMenuNative(
    JNIEnv *env, jobject thiz, jint shellMenu) {
  (void)env;
  (void)thiz;
  int item = r47_shell_menu_item((int)shellMenu);
  if (item == 0) {
    LOGE("sendSimMenuNative: unknown shell menu code %d", (int)shellMenu);
    return;
  }
  r47_send_sim_menu(item);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease) {
  (void)thiz;
  if (!ram) {
    return;
  }

  const char *nativeKeyId = (*env)->GetStringUTFChars(env, keyId, 0);
  if (jni_check_and_clear_exception(env,
                                    "sendSimKeyNative GetStringUTFChars") ||
      !nativeKeyId) {
    // Release before bailing: GetStringUTFChars can return a valid copy even
    // when an exception is pending, and an early return must not leak it.
    if (nativeKeyId) {
      (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
    }
    return;
  }

  r47_send_sim_key(nativeKeyId, isFn == JNI_TRUE, isRelease == JNI_TRUE);
  (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
}

JNIEXPORT void JNICALL Java_io_github_ppigazzini_r47zen_MainActivity_sendKey(
    JNIEnv *env, jobject thiz, jint keyCode) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return;
  }

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

// Only the genuinely-busy run states justify the out-of-band direct stop: a
// program that is RUNNING (executing) or PAUSED (inside a timed PSE loop) cannot
// drain the queued sendKey in time, so the keypad's stop must be delivered out
// of band. PGM_WAITING and PGM_RESUMING are interactive states where the core is
// parked waiting for the *next* keystroke (a graphing program holding its plot,
// a program between PSE/VIEW steps, an open f/g/I/O menu). In those states the
// queued sendKey IS processed, and R/S must resume / EXIT must navigate the
// menus -- short-circuiting them here swallows the keystroke and strands the
// user. Keep aligned with
// src/c47/programming/input.c, which only treats R/S(36)/EXIT(33) as a stop
// request while *prevStop == PGM_RUNNING.
bool r47_direct_stop_allowed(uint16_t runState) {
  return runState == PGM_RUNNING || runState == PGM_PAUSED;
}

_Static_assert(ITM_9 == ITM_0 + 9, "the paste tokens assume contiguous digits");

// Resolve one PasteNumber.kt token to its upstream item, or 0. Tokens cross JNI
// instead of item numbers, which items.h renumbers between upstream revisions.
static int16_t r47_paste_token_item(char token) {
  if (token >= '0' && token <= '9') {
    return (int16_t)(ITM_0 + (token - '0'));
  }
  switch (token) {
  case '.':
    return ITM_PERIOD;
  case 'e':
    return ITM_EXPONENT;
  case '~':
    return ITM_CHS;
  case 'j':
    return ITM_op_j;
  default:
    return 0;
  }
}

// Report whether the core reads digits as a number now. Alpha entry, a parameter
// prompt, a catalog, or a browser would read the same items as something else, and
// the digits of a matrix cell the user is typing would merge with the clip.
static bool r47_ready_for_number_locked(void) {
  if (tam.mode != 0 || catalog != 0 ||
      r47_direct_stop_allowed(programRunStop)) {
    return false;
  }
  return calcMode == CM_NORMAL || calcMode == CM_NIM ||
         (calcMode == CM_MIM && aimBuffer[0] == '\0') ||
         (calcMode == CM_PEM && !getSystemFlag(FLAG_ALPHA));
}

// Enter one item the way a key press does once determineItem has resolved it.
static void r47_enter_paste_item_locked(int16_t item) {
  processKeyAction(item);
  if (!keyActionProcessed) {
    runFunction(item);
  }
}

// Report whether the core kept an entered token. Number entry drops what it will
// not take with no error: a digit past the display width or the RNG exponent limit,
// and the radix mark in integer input mode. CHS rewrites a sign in place, so it
// keeps the length unless the wider sign pushed the last digit past the width.
static bool r47_paste_token_kept_locked(char token, size_t lengthBefore) {
  size_t length = strlen(aimBuffer);
  return token == '~' ? length == lengthBefore : length > lengthBefore;
}

// Report whether a number entry is still open. In program entry and the matrix
// editor the buffer tells: a backspace on an empty one deletes the program step
// above the cursor or zeroes the cell.
static bool r47_paste_entry_open_locked(void) {
  return calcMode == CM_NIM ||
         ((calcMode == CM_PEM || calcMode == CM_MIM) && aimBuffer[0] != '\0');
}

// Back out an open entry, one character per backspace as the key does, which also
// undoes the stack lift the entry made.
static void r47_back_out_paste_locked(void) {
  for (size_t left = strlen(aimBuffer) + 1;
       left > 0 && r47_paste_entry_open_locked(); left--) {
    r47_enter_paste_item_locked(ITM_BACKSPACE);
  }
}

// Enter one number as one operation under the lock, or refuse it whole. The items
// go in below determineItem, so USER assignments, a pending shift, and the
// multi-press timers cannot turn a digit into a function, and no key press from
// the user can land inside the number. A token the core drops backs the entry out,
// because what it kept would be a different number.
bool r47_paste_number(const char *tokens) {
  if (!ram ||
      atomic_load_explicit(&isCoreBlockingForIo, memory_order_relaxed) ||
      tokens == NULL || tokens[0] == '\0') {
    return false;
  }
  for (const char *token = tokens; *token != '\0'; token++) {
    if (r47_paste_token_item(*token) == 0) {
      LOGE("r47_paste_number: refused token '%c'", *token);
      return false;
    }
  }

  pthread_mutex_lock(&screenMutex);
  bool pasted = r47_ready_for_number_locked();
  if (pasted && calcMode == CM_NIM) {
    // Appending would merge the clip into the digits the user is typing.
    closeNim();
    pasted = calcMode == CM_NORMAL;
  }
  if (pasted && calcMode == CM_PEM && aimBuffer[0] != '\0') {
    // Close it as the next key would. Left open at the top of a program, its step
    // stays under the cursor, and the clip would land before it.
    pemCloseNumberInput();
  }
  if (pasted) {
    aimBuffer[0] = '\0';
    for (const char *token = tokens; pasted && *token != '\0'; token++) {
      size_t lengthBefore = strlen(aimBuffer);
      r47_enter_paste_item_locked(r47_paste_token_item(*token));
      pasted = r47_paste_token_kept_locked(*token, lengthBefore);
    }
    if (pasted) {
      resetShiftState();
    } else {
      r47_back_out_paste_locked();
    }
    refreshScreen(1);
  }
  pthread_mutex_unlock(&screenMutex);
  return pasted;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_pasteNumberNative(
    JNIEnv *env, jobject thiz, jstring tokens) {
  (void)thiz;
  const char *nativeTokens = (*env)->GetStringUTFChars(env, tokens, 0);
  if (jni_check_and_clear_exception(env,
                                    "pasteNumberNative GetStringUTFChars") ||
      !nativeTokens) {
    if (nativeTokens) {
      (*env)->ReleaseStringUTFChars(env, tokens, nativeTokens);
    }
    return JNI_FALSE;
  }

  bool pasted = r47_paste_number(nativeTokens);
  (*env)->ReleaseStringUTFChars(env, tokens, nativeTokens);
  return pasted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_requestStopProgramNative(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  if (!r47_runtime_ready()) {
    return JNI_FALSE;
  }

  if (!r47_direct_stop_allowed(programRunStop)) {
    return JNI_FALSE;
  }

  fnStopProgram(0);
  r47_request_stop_refresh();
  return JNI_TRUE;
}
