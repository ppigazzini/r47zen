#include "jni_bridge.h"
#include "host_services.h"

#include "saveRestoreCalcState.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

extern void refreshFn(uint16_t timerType);
extern void execFnTimeout(uint16_t timerType);
extern void shiftCutoff(uint16_t timerType);
extern void fnTimerDummy1(uint16_t timerType);
extern void execTimerApp(uint16_t timerType);
extern void mainLoop(void);
extern uint32_t nextScreenRefresh;
extern void restoreCalc(void);

// Set once, at the end of r47_init_runtime, and never cleared. The display reads
// published copies and needs no gate, so this only tells Kotlin the core is up.
static _Atomic bool g_r47_runtime_ready = false;
static _Atomic bool g_r47_stop_requested = false;
static _Atomic bool g_r47_stop_without_save = false;

bool r47_runtime_ready(void) {
  return atomic_load_explicit(&g_r47_runtime_ready, memory_order_acquire);
}

void r47_native_preinit_path(const char *path) {
  set_android_base_path(path ? path : "");

  extern void mp_set_memory_functions(
      void *(*alloc_func)(size_t),
      void *(*realloc_func)(void *, size_t, size_t),
      void (*free_func)(void *, size_t));
  extern void *allocGmp(size_t size);
  extern void *reallocGmp(void *ptr, size_t old_size, size_t new_size);
  extern void freeGmp(void *ptr, size_t size);

  mp_set_memory_functions(allocGmp, reallocGmp, freeGmp);
}

// A file from before this model can hold a run saved at a pump, and those flags
// mean nothing once the run is gone: a running state leaves the keys dead, and a
// solver flag turns EXIT into "Solver abort" and switches undo off.
static void clearInterruptedRun(void) {
  programRunStop = PGM_STOPPED;
  hourGlassIconEnabled = false;
  clearSystemFlag(FLAG_SOLVING);
  clearSystemFlag(FLAG_INTING);
}

void r47_init_runtime(void) {
  r47_keys_init();
  setupUI();
  init_lcd_buffers();
  lcd_clear_buf();
  calcModel = USER_R47f_g;
  void doFnReset(uint16_t confirmation, bool_t autoSav);
  doFnReset(CONFIRMED, false);
  r47_restore_guard_begin();
  restoreCalc();
  clearInterruptedRun();
  if (r47_sanitize_graph_bounds()) {
    LOGI("r47_init_runtime: sanitized restored graph bounds");
  }
  fnRefreshState();
  nextScreenRefresh = sys_current_ms();
  nextTimerRefresh = sys_current_ms();
  fnTimerReset();
  fnTimerConfig(TO_FG_LONG, refreshFn, TO_FG_LONG);
  fnTimerConfig(TO_CL_LONG, refreshFn, TO_CL_LONG);
  fnTimerConfig(TO_FG_TIMR, refreshFn, TO_FG_TIMR);
  fnTimerConfig(TO_FN_LONG, refreshFn, TO_FN_LONG);
  fnTimerConfig(TO_FN_EXEC, execFnTimeout, 0);
  fnTimerConfig(TO_3S_CTFF, shiftCutoff, TO_3S_CTFF);
  fnTimerConfig(TO_CL_DROP, fnTimerDummy1, TO_CL_DROP);
  fnTimerConfig(TO_TIMER_APP, execTimerApp, 0);
  fnTimerConfig(TO_ASM_ACTIVE, refreshFn, TO_ASM_ACTIVE);

  r47_arm_snap();
  r47_force_refresh();
  r47_restore_guard_end();
  r47_keypad_publish_at_rest();
  atomic_store_explicit(&g_r47_runtime_ready, true, memory_order_release);
}

// The calculator thread's whole life, as upstream's calcThreadMain: start, the
// main loop, and a save when the calculator switches off. OFF leaves the app in
// the background with its process alive, so the thread goes back into the loop
// and the next launch finds a running calculator.
void r47_calculator_main(void) {
  r47_init_runtime();
  r47_keys_from_host(true);

  if (getSystemFlag(FLAG_AUTXEQ)) {
    clearSystemFlag(FLAG_AUTXEQ);
    if (programRunStop != PGM_RUNNING) {
      screenUpdatingMode = SCRUPD_AUTO;
      runFunction(ITM_RS);
    }
    refreshScreen(191);
  }

  for (;;) {
    mainLoop();
    const bool stopping = atomic_load(&g_r47_stop_requested);
    // OFF minimizes the app by default, so "Auto-Save on Minimize" governs its save too.
    if (stopping || r47_services_autosave_enabled()) {
      r47_save_settled();
    }
    if (stopping) {
      return;
    }
    r47_upcall_quit();
    CLR_ST(STAT_PGM_END | STAT_SUSPENDED | STAT_OFF);
  }
}

// Any thread: end the calculator thread for good. An operation in progress sees
// an EXIT first, and a factory reset asks for no save, since it deletes the file.
void r47_calculator_stop(bool save) {
  atomic_store(&g_r47_stop_without_save, !save);
  atomic_store(&g_r47_stop_requested, true);
  r47_key_release_if_down();
  r47_key_push_quit();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_runCalculatorNative(
    JNIEnv *env, jclass clazz, jstring path_obj) {
  (void)clazz;
  const char *path = (*env)->GetStringUTFChars(env, path_obj, NULL);
  if (!jni_result_ok(env, (const void *)path,
                     "GetStringUTFChars(runCalculatorNative path)")) {
    return;
  }
  r47_native_preinit_path(path);
  (*env)->ReleaseStringUTFChars(env, path_obj, path);
  r47_calculator_main();
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_stopCalculatorNative(
    JNIEnv *env, jclass clazz, jboolean save) {
  (void)env;
  (void)clazz;
  r47_calculator_stop(save == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_isReadyNative(JNIEnv *env,
                                                                jclass clazz) {
  (void)env;
  (void)clazz;
  return r47_runtime_ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_onHostStoppedNative(
    JNIEnv *env, jclass clazz, jboolean stopped) {
  (void)env;
  (void)clazz;
  r47_keys_on_host_stopped(stopped == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_setAutoSaveNative(
    JNIEnv *env, jclass clazz, jboolean enabled) {
  (void)env;
  (void)clazz;
  r47_service_set_autosave(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_CalculatorEngine_saveStateNative(
    JNIEnv *env, jclass clazz, jint timeoutMs) {
  (void)env;
  (void)clazz;
  return r47_service_save_and_wait((int)timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

// saveCalc is written for a calculator that is shutting down: it resets the graph
// variable and dismisses a confirmation. This one keeps running, so both come back.
void r47_save_settled(void) {
  if (atomic_load(&g_r47_stop_without_save)) {
    return; // a factory reset is deleting the file
  }
  const __typeof__(graphVariabl1) savedGraphVariable = graphVariabl1;
  const uint8_t savedCalcMode = calcMode;
  // Upstream's own saves stop a running stopwatch first, which folds its elapsed time
  // into timerValue; timerStartTime is not saved, so a file saved running would restore
  // as running with nothing behind it and the time lost. The copy is saved stopped, and
  // the live stopwatch gets back the fields it had, so the save costs it no time.
  const bool stopwatchRunning = timerStartTime != TIMER_APP_STOPPED;
  const uint32_t startTime = timerStartTime;
  const uint32_t value = timerValue;
  const uint32_t totalTime = timerTotalTime;
  const bool_t firstDigit = rbr1stDigit;
  const bool_t watchIcon = watchIconEnabled;
  if (stopwatchRunning) {
    fnStopTimerApp();
  }
  saveCalc();
  if (stopwatchRunning) {
    timerStartTime = startTime;
    timerValue = value;
    timerTotalTime = totalTime;
    rbr1stDigit = firstDigit;
    watchIconEnabled = watchIcon;
    setSystemFlag(FLAG_RUNTIM);
    fnTimerStart(TO_TIMER_APP, TO_TIMER_APP, TIMER_APP_PERIOD);
  }
  graphVariabl1 = savedGraphVariable;
  if (savedCalcMode == CM_CONFIRMATION) {
    calcMode = savedCalcMode;
    refreshScreen(90);
  }
  r47_services_note_saved();
}

void r47_switch_slot_and_restore(int slot) {
  extern int current_slot_id;
  current_slot_id = slot;

  restoreCalc();
  clearInterruptedRun();
  if (r47_sanitize_graph_bounds()) {
    LOGI("r47_switch_slot_and_restore: sanitized restored graph bounds");
  }
  extern void scanLabelsAndPrograms(void);
  extern void defineCurrentProgramFromGlobalStepNumber(
      int16_t globalStepNumber);
  extern void defineCurrentStep(void);
  extern void defineFirstDisplayedStep(void);
  extern void defineCurrentProgramFromCurrentStep(void);
  extern void updateMatrixHeightCache(void);
  extern uint16_t currentLocalStepNumber;
  extern uint16_t currentProgramNumber;
  extern programList_t *programList;
  scanLabelsAndPrograms();
  if (currentProgramNumber > 0) {
    defineCurrentProgramFromGlobalStepNumber(
        currentLocalStepNumber +
        abs(programList[currentProgramNumber - 1].step) - 1);
  }
  defineCurrentStep();
  defineFirstDisplayedStep();
  defineCurrentProgramFromCurrentStep();
  updateMatrixHeightCache();
  refreshScreen(190);
}

void r47_force_refresh(void) {
  if (!ram) {
    return;
  }

  reDraw = true;
  refreshScreen(190);
  refreshLcd();
  lcd_refresh();
}
