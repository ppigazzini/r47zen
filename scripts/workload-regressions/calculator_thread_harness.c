// The calculator thread on the real main loop, as the app runs it. Each case runs in
// its own process on a thread started like CalculatorEngine's: this thread only
// pushes keys and service requests, and everything it reads is read on the calculator
// thread at rest, so the harness holds the app's one threading rule too.
//
// Usage: calculator-thread-harness <work dir> <program dir> [case]
#include "jni_bridge.h"
#include "host_services.h"
#include "keypad_fixture_bridge.h"
#include "r47_backup_version.h"

#include <dirent.h>
#include <errno.h>
#include <execinfo.h> // the glue's stand-in, first on the include path
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define KEY_F 11
#define KEY_EIGHT 20
#define KEY_DOWN 28
#define KEY_ONE 29
#define KEY_EXIT 33
#define KEY_ZERO 34
#define KEY_RS 36
#define KEY_MINUS 32
#define KEY_PLUS 37
#define KEY_SOFTKEY_1 38

// A case passes only by reaching finishCase. Upstream's saveCalc calls exit(0) when it
// cannot open its file, so status 0 would count a calculator that died as a pass.
#define CASE_PASSED 77

// A timer upstream's main loop does not use.
#define SPARE_TIMER 3

// Takes seconds, and polls for keys the whole time.
#define SLOW_SEMIPRIME "756702320882110506052343"

extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern bool_t anyKeyWaiting(void);
extern uint64_t r47_get_host_lcd_refresh_count(void);

static const char *workDir;
static const char *programDir;
static int failures = 0;

static atomic_int toneCount = 0;
static atomic_int quitCount = 0;
static atomic_int pasteResult = -1;
static atomic_int warningCount = 0;
static atomic_int tonesClearCount = 0;
static char warningText[512];
static atomic_int fsyncCount = 0;
static atomic_int fsyncDelayMs = 0;
static atomic_bool diskFull = false;
static atomic_int timedWaits = 0;

// The up-calls, which go to Kotlin in the app.
void __wrap_r47_upcall_play_tone(int milliHz, int durationMs) {
  (void)milliHz;
  (void)durationMs;
  atomic_fetch_add(&toneCount, 1);
}
void __wrap_r47_upcall_quit(void) { atomic_fetch_add(&quitCount, 1); }
void __wrap_r47_upcall_paste_result(bool pasted) {
  atomic_store(&pasteResult, pasted ? 1 : 0);
}
void __wrap_r47_upcall_warning(const char *text) {
  snprintf(warningText, sizeof(warningText), "%s", text);
  atomic_fetch_add(&warningCount, 1);
}
void __wrap_r47_upcall_tones_clear(void) { atomic_fetch_add(&tonesClearCount, 1); }

// A disk the case controls: slow to sync, or short of room.
extern int __real_fsync(int fd);
int __wrap_fsync(int fd) {
  atomic_fetch_add(&fsyncCount, 1);
  const int delayMs = atomic_load(&fsyncDelayMs);
  if (delayMs > 0) {
    const struct timespec delay = {delayMs / 1000, (long)(delayMs % 1000) * 1000000};
    nanosleep(&delay, NULL);
  }
  return __real_fsync(fd);
}
extern int __real_pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex,
                                         const struct timespec *until);
int __wrap_pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex,
                                  const struct timespec *until) {
  atomic_fetch_add(&timedWaits, 1);
  return __real_pthread_cond_timedwait(cond, mutex, until);
}
// Set by a case: the next sleep at rest starts the host from inside its own sleep, after it
// read whether the host is stopped and before it locks to wait, as onStart can.
static atomic_bool startHostInsideSleep = false;
extern void __real_r47_tones_at_rest(void);
void __wrap_r47_tones_at_rest(void) {
  __real_r47_tones_at_rest();
  if (atomic_exchange(&startHostInsideSleep, false)) {
    r47_keys_on_host_stopped(false);
  }
}
static atomic_int displayTextCount = 0;
void __wrap_r47_upcall_display_text(bool isError, const char *text) {
  (void)isError;
  (void)text;
  atomic_fetch_add(&displayTextCount, 1);
}
// Upstream's refreshLcd, which the main loop calls once a SCREEN_REFRESH_PERIOD and
// which counts a shown function name down by a whole period on each call.
static atomic_int refreshLcdCount = 0;
extern void __real_refreshLcd(void);
void __wrap_refreshLcd(void) {
  atomic_fetch_add(&refreshLcdCount, 1);
  __real_refreshLcd();
}
// Bytes the disk still takes, or -1 for no limit. A write that does not fit takes none.
static atomic_long diskRoom = -1;
extern size_t __real_fwrite(const void *data, size_t size, size_t count, FILE *file);
size_t __wrap_fwrite(const void *data, size_t size, size_t count, FILE *file) {
  if (atomic_load(&diskFull)) {
    return 0;
  }
  const long room = atomic_load(&diskRoom);
  if (room >= 0) {
    if ((long)(size * count) > room) {
      atomic_store(&diskRoom, 0);
      return 0;
    }
    atomic_store(&diskRoom, room - (long)(size * count));
  }
  return __real_fwrite(data, size, count, file);
}

#define CHECK(condition, ...)                                                  \
  do {                                                                         \
    if (!(condition)) {                                                        \
      dprintf(2, "  FAIL %s:%d: ", __func__, __LINE__);                        \
      dprintf(2, __VA_ARGS__);                                                 \
      dprintf(2, "\n");                                                        \
      failures++;                                                              \
    }                                                                          \
  } while (0)

static int64_t nowMs(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void sleepMs(int ms) {
  struct timespec ts = {ms / 1000, (long)(ms % 1000) * 1000000};
  nanosleep(&ts, NULL);
}

static void *calculatorThread(void *arg) {
  (void)arg;
  r47_calculator_main();
  return NULL;
}

static pthread_t startCalculator(void) {
  r47_native_preinit_path(workDir);
  pthread_t thread;
  pthread_attr_t attr;
  pthread_attr_init(&attr);
  pthread_attr_setstacksize(&attr, 8u * 1024u * 1024u);
  pthread_create(&thread, &attr, calculatorThread, NULL);
  pthread_attr_destroy(&attr);
  while (!r47_runtime_ready()) {
    sleepMs(1);
  }
  return thread;
}

static void press(int code) { r47_key_push(code); }
static void release(void) { r47_key_push(0); }
static void tap(int code) {
  press(code);
  release();
}

typedef struct {
  double t, z, y, x;
} stack_t_;

static void setStackAtRest(void *arg) {
  const stack_t_ *s = arg;
  convertDoubleToReal34Register(s->t, REGISTER_T);
  convertDoubleToReal34Register(s->z, REGISTER_Z);
  convertDoubleToReal34Register(s->y, REGISTER_Y);
  convertDoubleToReal34Register(s->x, REGISTER_X);
}

static void setStack(double t, double z, double y, double x) {
  stack_t_ s = {t, z, y, x};
  r47_run_at_rest(setStackAtRest, &s);
}

typedef struct {
  char x[64];
  char y[64];
  char z[64];
  char t[64];
  int xType;
  int calcMode;
  int runStop;
  int step;
  int lastError;
  bool solving;
  char aim[64];
  int menu;
  bool shiftF;
  bool shiftG;
} state_t;

static void registerText(calcRegister_t r, char *out, size_t size) {
  extern void copyRegisterToClipboardString(calcRegister_t, char *, bool_t);
  char *wide = malloc(TMP_STR_LENGTH);
  if (getRegisterDataType(r) == dtReal34Matrix) {
    snprintf(out, size, "matrix");
  } else {
    copyRegisterToClipboardString(r, wide, false);
    snprintf(out, size, "%s", wide);
  }
  free(wide);
}

static void readStateAtRest(void *arg) {
  state_t *s = arg;
  char *x = r47_clipboard_text(R47_COPY_X);
  snprintf(s->x, sizeof(s->x), "%.60s", x != NULL ? x : "");
  free(x);
  registerText(REGISTER_Y, s->y, sizeof(s->y));
  registerText(REGISTER_Z, s->z, sizeof(s->z));
  registerText(REGISTER_T, s->t, sizeof(s->t));
  s->xType = (int)getRegisterDataType(REGISTER_X);
  s->calcMode = calcMode;
  s->runStop = programRunStop;
  s->step = currentLocalStepNumber;
  s->lastError = lastErrorCode;
  s->solving = getSystemFlag(FLAG_SOLVING);
  snprintf(s->aim, sizeof(s->aim), "%.60s", aimBuffer);
  s->menu = currentMenu();
  s->shiftF = shiftF;
  s->shiftG = shiftG;
}

// Blocks until the calculator is at rest again, so it is also the wait for "done".
static state_t readState(void) {
  state_t s;
  memset(&s, 0, sizeof(s));
  r47_run_at_rest(readStateAtRest, &s);
  return s;
}

static void showNumberTheoryAtRest(void *arg) {
  (void)arg;
  showSoftmenu(-MNU_NUMTHEORY);
  calcMode = CM_NORMAL;
}

static void setSlowFactorInputAtRest(void *arg) {
  (void)arg;
  longInteger_t value;
  longIntegerInit(value);
  stringToLongInteger(SLOW_SEMIPRIME, 10, value);
  convertLongIntegerToLongIntegerRegister(value, REGISTER_X);
  longIntegerFree(value);
}

static atomic_bool probeRan;

static void markProbeRan(void *arg) {
  (void)arg;
  atomic_store(&probeRan, true);
}

// A service runs only at rest, so one that has not run after this long proves the
// calculator is inside the computation, not still waiting to start it.
static void waitUntilBusy(void) {
  sleepMs(400);
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  sleepMs(200);
  CHECK(!atomic_load(&probeRan), "the calculator was not busy");
}

// FACTORS through its softkey, the way a user starts it.
static void startSlowFactors(void) {
  setStack(4, 3, 2, 0);
  r47_run_at_rest(setSlowFactorInputAtRest, NULL);
  r47_run_at_rest(showNumberTheoryAtRest, NULL);
  tap(KEY_SOFTKEY_1);
  waitUntilBusy();
}

// A stopped FACTORS leaves its input in X; one that ran to the end leaves the factors
// there, so a stop that did nothing cannot pass as one that stopped it.
static void checkFactorsStopped(const state_t *s, const char *stop) {
  CHECK(strstr(s->x, SLOW_SEMIPRIME) != NULL, "%s did not stop FACTORS: X=%s", stop, s->x);
}

static void copyFile(const char *from, const char *to) {
  FILE *in = fopen(from, "rb");
  FILE *out = fopen(to, "wb");
  CHECK(in != NULL && out != NULL, "cannot copy %s", from);
  if (in && out) {
    char chunk[4096];
    size_t count;
    while ((count = fread(chunk, 1, sizeof(chunk), in)) > 0) {
      fwrite(chunk, 1, count, out);
    }
  }
  if (in) {
    fclose(in);
  }
  if (out) {
    fclose(out);
  }
}

// A save has only the fence's grace to find the calculator idle, and a thread still
// finishing the last request can take longer than that under the sanitizers.
static bool saveWhenIdle(void) {
  readState();
  for (int i = 0; i < 1000 && !r47_keys_calculator_idle(); i++) {
    sleepMs(5);
  }
  return r47_service_save_and_wait(5000);
}

static void saveOnceIdle(void) { CHECK(saveWhenIdle(), "no save"); }

static void loadProgramAtRest(void *arg) {
  (void)arg;
  fnLoadProgram(0);
}

static void loadProgram(const char *name) {
  char from[1024];
  char dir[1024];
  char to[2048];
  snprintf(from, sizeof(from), "%s/%s", programDir, name);
  snprintf(dir, sizeof(dir), "%s/PROGRAMS", workDir);
  mkdir(dir, 0700);
  snprintf(to, sizeof(to), "%s/program.p47", dir);
  copyFile(from, to);
  r47_run_at_rest(loadProgramAtRest, NULL);
}

static void runFunctionAtRest(void *arg) { runFunction((int16_t)(intptr_t)arg); }

// LBL 01 <body> GTO 01, entered in program mode with the keys, then back to run mode.
// The body's argument is 8 after the key tensKey, or 8 alone when tensKey is 0.
static void enterLoopWithArgument(int16_t bodyItem, int tensKey) {
  tap(KEY_F);
  tap(KEY_RS);
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_LBL);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  if (bodyItem != 0) {
    r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)bodyItem);
    if (tensKey != 0) {
      tap(tensKey);
    }
    tap(KEY_EIGHT);
  }
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_GTO);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  tap(KEY_EXIT);
  tap(KEY_EXIT);
  readState();
}

// TONE takes one digit, and a step with no item takes none.
static void enterLoopProgram(int16_t bodyItem) { enterLoopWithArgument(bodyItem, 0); }

// ---- cases ----

static void caseKeyGoesThroughTheBuffer(void) {
  startCalculator();
  setStack(0, 0, 3, 4);
  tap(KEY_PLUS);
  state_t s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "3 4 + gave X=%s", s.x);
}

static void caseKeysKeepTheirOrder(void) {
  startCalculator();
  setStack(0, 0, 0, 0);
  // With no gap: 1 0 1 typed faster than the core can take them.
  tap(KEY_ONE);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  state_t s = readState();
  CHECK(s.calcMode == CM_NIM && strcmp(s.aim, "+101") == 0, "typed aim=%s", s.aim);
}

typedef struct {
  const char *number;
  char factors[128];
} factorCase_t;

static void factorAtRest(void *arg) {
  factorCase_t *c = arg;
  longInteger_t value;
  longIntegerInit(value);
  stringToLongInteger(c->number, 10, value);
  convertLongIntegerToLongIntegerRegister(value, REGISTER_X);
  longIntegerFree(value);
  runFunction(ITM_FACTORS);
  char *x = r47_clipboard_text(R47_COPY_X);
  snprintf(c->factors, sizeof(c->factors), "%.120s", x != NULL ? x : "");
  free(x);
}

// mini-gmp's fits test once read 64 bits here, which factored the first number wrong and
// never returned on the second, with no error. Upstream's vectors in this range check only
// the error code.
static void caseFactorsAreRight(void) {
  startCalculator();
  factorCase_t square = {"4294971521", ""};
  r47_run_at_rest(factorAtRest, &square);
  CHECK(strstr(square.factors, "51577") && strstr(square.factors, "83273"),
        "4294971521 gave %s", square.factors);
  factorCase_t large = {"41622656721349609", ""};
  r47_run_at_rest(factorAtRest, &large);
  CHECK(strstr(large.factors, "180763589") && strstr(large.factors, "230260181"),
        "41622656721349609 gave %s", large.factors);
}

// A service pushed after a key must see that key's result, whatever the main loop was
// doing when both arrived. On one core the main loop is reliably between its look at the
// empty buffer and its sleep when they do: without the check this fails at the first round.
static void caseServiceRunsBehindEarlierKeys(void) {
  cpu_set_t oneCore;
  CPU_ZERO(&oneCore);
  CPU_SET(sched_getcpu(), &oneCore);
  CHECK(sched_setaffinity(0, sizeof(oneCore), &oneCore) == 0, "cannot pin to one core");
  startCalculator();
  for (int round = 0; round < 300; round++) {
    setStack(0, 0, 3, 4);
    tap(KEY_PLUS);
    state_t s = readState();
    CHECK(strcmp(s.x, "7.") == 0, "round %d: the read overtook the key: X=%s", round, s.x);
    if (failures > 0) {
      return;
    }
  }
}

static void caseDigitDuringAComputationDoesNothing(void) {
  startCalculator();
  startSlowFactors();
  tap(KEY_ZERO);
  state_t s = readState();
  CHECK(s.calcMode == CM_NORMAL, "calcMode=%d aim=%s", s.calcMode, s.aim);
  CHECK(s.lastError == 0, "lastError=%d", s.lastError);
  CHECK(strncmp(s.x, "2x", 2) == 0 || strstr(s.x, "[2x") != NULL, "X=%s", s.x);
  CHECK(strcmp(s.y, "2.") == 0, "Y=%s", s.y);
  CHECK(strcmp(s.z, "3.") == 0, "Z=%s", s.z);
  CHECK(strcmp(s.t, "4.") == 0, "T=%s", s.t);
}

static void caseExitStopsAComputation(void) {
  startCalculator();
  startSlowFactors();
  tap(KEY_EXIT);
  const int64_t start = nowMs();
  state_t s = readState();
  CHECK(nowMs() - start < 2000, "EXIT took %d ms", (int)(nowMs() - start));
  CHECK(s.calcMode == CM_NORMAL, "calcMode=%d", s.calcMode);
  checkFactorsStopped(&s, "EXIT");
  setStack(0, 0, 3, 4);
  tap(KEY_PLUS);
  s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "the keypad is dead after EXIT: X=%s", s.x);
}

static void caseRunStopStopsAComputationAndRunsNothing(void) {
  startCalculator();
  loadProgram("FactIt.p47");
  const int stepBefore = readState().step;
  startSlowFactors();
  press(KEY_RS);
  sleepMs(50);
  release();
  state_t s = readState();
  CHECK(s.step == stepBefore, "R/S ran a program: step %d to %d", stepBefore, s.step);
  CHECK(s.runStop != PGM_RUNNING, "a program is running");
  checkFactorsStopped(&s, "R/S");
}

static void assertLoopStopsOn(int key) {
  startCalculator();
  enterLoopProgram(0);
  tap(KEY_RS);
  waitUntilBusy();
  tap(key);
  const int64_t start = nowMs();
  state_t s = readState();
  CHECK(nowMs() - start < 2000, "stop took %d ms", (int)(nowMs() - start));
  CHECK(s.runStop != PGM_RUNNING && s.runStop != PGM_PAUSED, "runStop=%d", s.runStop);
}

static void caseRunStopStopsALoopThatNeverYields(void) { assertLoopStopsOn(KEY_RS); }
static void caseExitStopsALoopThatNeverYields(void) { assertLoopStopsOn(KEY_EXIT); }

static void caseToneTakesItsTime(void) {
  startCalculator();
  enterLoopProgram(ITM_TONE);
  tap(KEY_RS);
  sleepMs(1000);
  const int count = atomic_load(&toneCount);
  CHECK(count >= 2 && count <= 6, "%d tones in one second", count);
  tap(KEY_RS);
  readState();
  const int afterStop = atomic_load(&toneCount);
  sleepMs(600);
  CHECK(atomic_load(&toneCount) == afterStop, "tones kept coming after the stop");
}

// Upstream's suite calls functions and never presses a key, so btnPressed and
// btnReleased, TAM, AIM, and the shifts have no oracle there. Each sequence runs from a
// reset calculator through the key buffer, and what it expects is written from the R47
// key table (assign.c, kbd_std_R47f_g: key code n is its n-th row, and off DMCP
// convertKeyCode passes codes through) and what the calculator does, not read from a
// run: typed digits with no point make a long integer, which the clipboard copies with
// no point; the clipboard copies a real reduced, 2000 as 2E+3; a reset leaves an
// 8-level stack of real zeros.
enum {
  K_SQUARE = 1, K_ROOT = 2, K_LOG = 5, K_STO = 7, K_RCL = 8, K_RDOWN = 9, K_G = 12,
  K_ENTER = 13, K_SWAP = 14, K_CHS = 15, K_E = 16, K_BACKSPACE = 17, K_XEQ = 18,
  K_SEVEN = 19, K_UP = 23, K_FOUR = 24, K_FIVE = 25, K_TWO = 30, K_THREE = 31, K_DOT = 35
};

// A menu of -1 is not checked; MENU_AT_RESET is the one a reset leaves.
#define MENU_AT_RESET 1

typedef struct {
  const char *name;
  int keys[16]; // DMCP key codes, ended by 0
  int calcMode;
  const char *x;   // X as the clipboard copies it, or NULL
  const char *y;   // Y, or NULL
  const char *aim; // aimBuffer, or NULL
  int menu;
} keyReplay_t;

static const keyReplay_t keyReplays[] = {
    {"digits, ENTER, and +", {KEY_ONE, K_TWO, K_ENTER, K_THREE, KEY_PLUS}, CM_NORMAL, "15", NULL, NULL, -1},
    {"a decimal point", {KEY_ONE, K_TWO, K_DOT, K_FIVE, K_ENTER}, CM_NORMAL, "12.5", NULL, NULL, -1},
    {"CHS during entry", {K_FIVE, K_CHS}, CM_NIM, NULL, NULL, "-5", -1},
    {"an exponent during entry", {K_TWO, K_E, K_THREE, K_ENTER}, CM_NORMAL, "2E+3", NULL, NULL, -1},
    {"backspace during entry", {KEY_ONE, K_TWO, K_THREE, K_BACKSPACE}, CM_NIM, NULL, NULL, "+12", -1},
    {"f LOG is 10 to the x", {K_TWO, KEY_F, K_LOG}, CM_NORMAL, "100", NULL, NULL, -1},
    {"x exchange y", {KEY_ONE, K_ENTER, K_TWO, K_SWAP}, CM_NORMAL, "1", "2", NULL, -1},
    {"STO and RCL take a register", {K_SEVEN, K_STO, KEY_ZERO, K_FIVE, K_THREE, K_RCL, KEY_ZERO, K_FIVE, KEY_PLUS},
     CM_NORMAL, "10", NULL, NULL, -1},
    // TAM leaves calcMode alone. With STO's first register digit typed, EXIT must
    // cancel it for the 5 to start a number; without STO, 0 would join the 7.
    {"EXIT cancels STO", {K_SEVEN, K_STO, KEY_ZERO, KEY_EXIT, K_FIVE}, CM_NIM, NULL, "7", "+5", -1},
    // R-up brings level 8's zero down and lifts the 4; plain R-down would leave Y 0.
    {"g R-down is R-up", {K_FOUR, K_G, K_RDOWN}, CM_NORMAL, "0.", "4", NULL, -1},
    {"f XEQ enters AIM and types letters", {KEY_F, K_XEQ, K_SQUARE, K_ROOT}, CM_AIM, NULL, NULL, "AB", -1},
    {"ENTER leaves AIM with the string in X", {KEY_F, K_XEQ, K_SQUARE, K_ROOT, K_ENTER}, CM_NORMAL, "AB", NULL,
     NULL, -1},
    {"NUM lock in AIM types digits", {KEY_F, K_XEQ, KEY_F, K_UP, K_SEVEN}, CM_AIM, NULL, NULL, "7", -1},
    {"f + opens the catalog", {KEY_F, KEY_PLUS}, CM_NORMAL, NULL, NULL, NULL, -MNU_CATALOG},
    // Holds only with the row before it, which shows f + opened the catalog.
    {"EXIT closes the catalog", {KEY_F, KEY_PLUS, KEY_EXIT}, CM_NORMAL, NULL, NULL, NULL, MENU_AT_RESET},
    {"f R/S enters program entry", {KEY_F, KEY_RS}, CM_PEM, NULL, NULL, NULL, -1},
    // Holds only with the row before it, which shows f R/S entered program entry.
    {"EXIT leaves program entry", {KEY_F, KEY_RS, KEY_EXIT}, CM_NORMAL, NULL, NULL, NULL, -1},
};

static void resetAtRest(void *arg) {
  (void)arg;
  fnReset(CONFIRMED);
}

// The PC keyboard (pc_keyboard.c), as upstream's GTK simulator gives it. Kotlin sends
// X11 keysyms with the modifiers before each event, as X11 reports them.
#define PC_SHIFT_L 0xffe1u
#define PC_SHIFT_MASK 0x1u
#define PC_DEAD_ACUTE 0xfe51u
#define PC_UNICODE(cp) (0x01000000u | (cp))
#define PC_F1 0xffbeu
#define PC_ESCAPE 0xff1bu
#define PC_CONTROL_L 0xffe3u
#define PC_CONTROL_MASK 0x4u

static void pcTap(uint32_t keyval) {
  r47_key_push_pc(keyval, 0, true);
  r47_key_push_pc(keyval, 0, false);
}

static void pcTapShifted(uint32_t keyval) {
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_push_pc(keyval, PC_SHIFT_MASK, true);
  r47_key_push_pc(keyval, PC_SHIFT_MASK, false);
  r47_key_push_pc(PC_SHIFT_L, PC_SHIFT_MASK, false);
}

static void enterAimAtRest(void *arg) {
  (void)arg;
  runFunction(ITM_AIM);
}

static void aimTextAtRest(void *arg) { snprintf(arg, 64, "%.60s", aimBuffer); }

static void aimAppendAtRest(void *arg) { processAimInput((int16_t)(intptr_t)arg); }

// Keys and PC keys share one buffer, so a PC key keeps its place between two touch
// keys: 1, a shifted PC E (the constant e, by item), then - is 1 - e, all pushed with
// no wait. Either key overtaking the PC key gives another number.
static void casePcKeyRunsInOrderWithTheKeys(void) {
  startCalculator();
  tap(KEY_ONE);
  pcTapShifted('E');
  tap(KEY_MINUS);
  state_t s = readState();
  CHECK(strncmp(s.x, "-1.71828182845904523536", 23) == 0, "1, E, - gave X=%s, not 1 - e", s.x);
}

// GTK's period is the R47's decimal point key; its x squared row is the Raspberry
// Pi build's only: 1 . 5 is 1.5.
static void casePcPeriodIsTheDecimalPoint(void) {
  startCalculator();
  pcTap('1');
  pcTap('.');
  pcTap('5');
  pcTap(0xff0du); // Return, ENTER
  state_t s = readState();
  CHECK(strcmp(s.x, "1.5") == 0, "1 . 5 ENTER gave X=%s", s.x);
}

// A lone Shift is f and a lone Ctrl is g, on their release, as GTK's are.
static void casePcLoneShiftAndCtrlAreTheShifts(void) {
  startCalculator();
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_push_pc(PC_SHIFT_L, PC_SHIFT_MASK, false);
  state_t s = readState();
  CHECK(s.shiftF && !s.shiftG, "a lone Shift gave f=%d g=%d", s.shiftF, s.shiftG);
  r47_key_push_pc(PC_CONTROL_L, 0, true);
  r47_key_push_pc(PC_CONTROL_L, PC_CONTROL_MASK, false);
  s = readState();
  CHECK(!s.shiftF && s.shiftG, "a lone Ctrl gave f=%d g=%d", s.shiftF, s.shiftG);
}

static void clearShiftsAtRest(void *arg) {
  (void)arg;
  shiftF = false;
  shiftG = false;
}

// Android cancels a key's up when focus leaves the window with the key down. A
// canceled Shift or Ctrl was not let go on purpose: it shifts nothing, the next
// Shift is f again, and the Shift leaves no case behind: the f letter still shifts,
// and q in AIM still types Q.
static void casePcCanceledShiftOrCtrlShiftsNothing(void) {
  startCalculator();
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_cancel_pc(PC_SHIFT_L);
  r47_key_push_pc(PC_CONTROL_L, 0, true);
  r47_key_cancel_pc(PC_CONTROL_L);
  state_t s = readState();
  CHECK(!s.shiftF && !s.shiftG, "a canceled Shift and Ctrl gave f=%d g=%d", s.shiftF,
        s.shiftG);
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_push_pc(PC_SHIFT_L, PC_SHIFT_MASK, false);
  s = readState();
  CHECK(s.shiftF && !s.shiftG, "a Shift after a canceled one gave f=%d g=%d", s.shiftF,
        s.shiftG);
  r47_run_at_rest(clearShiftsAtRest, NULL);
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_cancel_pc(PC_SHIFT_L);
  pcTap('f');
  s = readState();
  CHECK(s.shiftF && !s.shiftG, "f after a canceled Shift gave f=%d g=%d", s.shiftF,
        s.shiftG);
  r47_run_at_rest(clearShiftsAtRest, NULL);
  r47_run_at_rest(enterAimAtRest, NULL);
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_cancel_pc(PC_SHIFT_L);
  pcTap('q');
  s = readState();
  CHECK(s.calcMode == CM_AIM, "calcMode=%d", s.calcMode);
  CHECK(strcmp(s.aim, "Q") == 0, "q after a canceled Shift typed aim=%s", s.aim);
}

static void setHexBaseAtRest(void *arg) {
  (void)arg;
  lastIntegerBase = 16;
}

// In integer entry with a base, a-f are digits, not their shortcuts: a is not SIGMA+.
static void casePcLetterInHexEntryIsADigit(void) {
  startCalculator();
  r47_run_at_rest(setHexBaseAtRest, NULL);
  pcTap('1');
  pcTap('a');
  state_t s = readState();
  CHECK(s.calcMode == CM_NIM, "a closed number entry: calcMode=%d", s.calcMode);
  CHECK(strchr(s.aim, 'A') != NULL, "a gave no hex digit: aim=%s", s.aim);
}

static void userModeWithExpOnThePiKeyAtRest(void *arg) {
  (void)arg;
  setSystemFlag(FLAG_USER);
  kbd_usr[8].fShifted = ITM_EXP; // the default layout's f 8 is pi
}

// A shortcut to an item runs the item by name, as GTK's -01 rows do, so a user key
// that moved the item does not change what the letter gives: p is pi.
static void casePcLetterRunsItsItemInUserMode(void) {
  startCalculator();
  r47_run_at_rest(userModeWithExpOnThePiKeyAtRest, NULL);
  setStack(0, 0, 0, 1);
  pcTap('p');
  state_t s = readState();
  CHECK(strncmp(s.x, "3.14159265358979323846", 22) == 0, "p in USER mode gave X=%s, not pi",
        s.x);
}

// A held shift stops a shortcut that runs an item, as GTK's shortCutCommand does: g
// then t does nothing.
static void casePcShiftThenLetterDoesNothing(void) {
  startCalculator();
  setStack(0, 0, 0, 1);
  pcTap('g');
  pcTap('t');
  state_t s = readState();
  CHECK(strcmp(s.x, "1.") == 0, "g then t changed X to %s", s.x);
  CHECK(s.shiftG && !s.shiftF, "g then t left f=%d g=%d", s.shiftF, s.shiftG);
}

// The layout choice, as the calculator's own menu makes it. Two upstream behaviors
// are put away, so each f press is a first press: the version screen the choice shows,
// under which the release of a shift key returns before it ends the key's long press,
// and the window in which a third shift press opens HOME and clears the shift.
static void setModelAtRest(void *arg) {
  fnKeysManagement((uint16_t)(intptr_t)arg);
  temporaryInformation = TI_NO_INFO;
  fnTimerStop(TO_3S_CTFF);
  shiftF = false;
  shiftG = false;
}

// The f letter finds the layout's own shift key, as GTK's does.
static void casePcFLetterShiftsOnEveryLayout(void) {
  startCalculator();
  const int models[] = {USER_R47f_g, USER_R47bk_fg, USER_R47fg_bk, USER_R47fg_g};
  for (size_t i = 0; i < sizeof(models) / sizeof(models[0]); i++) {
    r47_run_at_rest(setModelAtRest, (void *)(intptr_t)models[i]);
    pcTap('f');
    state_t s = readState();
    CHECK(s.shiftF && !s.shiftG, "model %d: f gave f=%d g=%d", models[i], s.shiftF, s.shiftG);
  }
}

// Letters in AIM type what they say, in the case the calculator shows: q w e r t y
// at AC_UPPER is QWERTY, and a shifted Q flips to q.
static void casePcLettersInAimTypeWhatTheySay(void) {
  startCalculator();
  r47_run_at_rest(enterAimAtRest, NULL);
  const uint32_t word[] = {'q', 'w', 'e', 'r', 't', 'y'};
  for (size_t i = 0; i < sizeof(word) / sizeof(word[0]); i++) {
    pcTap(word[i]);
  }
  pcTapShifted('Q');
  state_t s = readState();
  CHECK(s.calcMode == CM_AIM, "calcMode=%d", s.calcMode);
  CHECK(strcmp(s.aim, "QWERTYq") == 0, "typed QWERTYq, aim=%s", s.aim);
}

static void readRegisterAAtRest(void *arg) { registerText(REGISTER_A, arg, 64); }

// STO, then a letter, stores to that register, as GTK's STO and RCL chain does.
static void casePcLetterAfterStoNamesTheRegister(void) {
  startCalculator();
  setStack(0, 0, 0, 5);
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_STO);
  pcTap('a');
  char registerA[64] = "";
  readState();
  r47_run_at_rest(readRegisterAAtRest, registerA);
  CHECK(strcmp(registerA, "5.") == 0, "STO a left A=%s", registerA);
}

// A character beyond Latin-1 comes as its Unicode keysym and types the item GTK's map
// gives its legacy keysym; a dead key composes with the next letter.
static void casePcCharactersBeyondAsciiType(void) {
  startCalculator();
  r47_run_at_rest(enterAimAtRest, NULL);
  r47_run_at_rest(aimAppendAtRest, (void *)(intptr_t)ITM_a_OGONEK);
  r47_run_at_rest(aimAppendAtRest, (void *)(intptr_t)ITM_E_ACUTE);
  char expected[64] = "";
  r47_run_at_rest(aimTextAtRest, expected);
  r47_run_at_rest(resetAtRest, NULL);
  r47_run_at_rest(enterAimAtRest, NULL);
  pcTap(PC_UNICODE(0x0105));
  pcTap(PC_DEAD_ACUTE);
  pcTap('e');
  char typed[64] = "";
  readState();
  r47_run_at_rest(aimTextAtRest, typed);
  CHECK(expected[0] != 0 && strcmp(typed, expected) == 0, "typed %s, expected %s", typed,
        expected);
}

static void readVolumeSoftkeyValueAtRest(void *arg) {
  *(int16_t *)arg = fnItemShowValue(ITM_VOLPLUS);
}

// The app keeps no beep volume, so the VOL softkeys show no value, as GTK's
// getBeepVolume answers NOVAL.
static void caseVolumeSoftkeysShowNoValue(void) {
  startCalculator();
  int16_t value = 0;
  r47_run_at_rest(readVolumeSoftkeyValueAtRest, &value);
  CHECK(value == NOVAL, "VOL+ shows %d, not NOVAL", value);
}

typedef struct {
  bool numLock;
  char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY][R47_KEYPAD_LABEL_CAPACITY];
} aimFaces_t;

static void readAimFacesAtRest(void *arg) {
  aimFaces_t *faces = arg;
  runFunction(ITM_AIM);
  if (faces->numLock) {
    setSystemFlag(FLAG_NUMLOCK);
  } else {
    clearSystemFlag(FLAG_NUMLOCK);
  }
  r47_get_keypad_labels(faces->labels, true);
}

static const char *aimFace(const aimFaces_t *faces, int key, int slot) {
  return faces->labels[key * R47_KEYPAD_LABELS_PER_KEY + slot];
}

// In AIM with NUM on, a key's face is what it types, as GTK draws it through
// numlockReplacements: the N key shows its g face, 7, under NUM.
static void caseAimFacesFollowNumLock(void) {
  startCalculator();
  static aimFaces_t letters = {.numLock = false};
  static aimFaces_t digits = {.numLock = true};
  r47_run_at_rest(readAimFacesAtRest, &letters);
  r47_run_at_rest(readAimFacesAtRest, &digits);
  int key = 0;
  while (key < R47_KEYPAD_KEY_COUNT && strcmp(aimFace(&letters, key, R47_KEYPAD_LABEL_PRIMARY), "N") != 0) {
    key++;
  }
  CHECK(key < R47_KEYPAD_KEY_COUNT, "no AIM key shows N");
  if (key == R47_KEYPAD_KEY_COUNT) {
    return;
  }
  CHECK(strcmp(aimFace(&letters, key, R47_KEYPAD_LABEL_G), "7") == 0, "the N key's g face is %s",
        aimFace(&letters, key, R47_KEYPAD_LABEL_G));
  CHECK(strcmp(aimFace(&digits, key, R47_KEYPAD_LABEL_PRIMARY), "7") == 0,
        "the N key under NUM shows %s, not 7", aimFace(&digits, key, R47_KEYPAD_LABEL_PRIMARY));
}

// During a computation a PC key only pushes keys at the head of the buffer: s gives
// the key sine sits on, which the computation's poll drops, and Escape gives EXIT,
// which stops it.
static void casePcKeyDuringAComputationOnlyPushesKeys(void) {
  startCalculator();
  startSlowFactors();
  pcTap('s');
  pcTap(PC_ESCAPE);
  const int64_t start = nowMs();
  state_t s = readState();
  CHECK(nowMs() - start < 2000, "Escape took %d ms", (int)(nowMs() - start));
  CHECK(s.calcMode == CM_NORMAL && s.lastError == 0, "calcMode=%d lastError=%d", s.calcMode,
        s.lastError);
  checkFactorsStopped(&s, "Escape");
}

static atomic_bool releaseWaitEnded;

static void waitForReleaseAtRest(void *arg) {
  (void)arg;
  wait_for_key_release(0);
  atomic_store(&releaseWaitEnded, true);
}

// A softkey a PC F1 pressed during a computation, released once the computation is
// over, leaves no key down: the next wait for a release does not wait.
static void casePcSoftkeyReleasedAtRestLeavesNoKeyDown(void) {
  startCalculator();
  startSlowFactors();
  r47_key_push_pc(PC_F1, 0, true);
  readState();
  r47_key_push_pc(PC_F1, 0, false);
  readState();
  atomic_store(&releaseWaitEnded, false);
  r47_run_at_rest_async(waitForReleaseAtRest, NULL);
  sleepMs(500);
  CHECK(atomic_load(&releaseWaitEnded), "the calculator waits for a release no key will send");
}

static void caseKeySequencesGiveWhatTheKeysSay(void) {
  startCalculator();
  for (size_t i = 0; i < sizeof(keyReplays) / sizeof(keyReplays[0]); i++) {
    const keyReplay_t *replay = &keyReplays[i];
    r47_run_at_rest(resetAtRest, NULL);
    const int menuAtReset = readState().menu;
    for (const int *key = replay->keys; *key != 0; key++) {
      tap(*key);
    }
    state_t s = readState();
    const int menu = replay->menu == MENU_AT_RESET ? menuAtReset : replay->menu;
    CHECK(s.calcMode == replay->calcMode, "%s: calcMode %d, expected %d", replay->name, s.calcMode,
          replay->calcMode);
    CHECK(replay->x == NULL || strcmp(s.x, replay->x) == 0, "%s: X=%s, expected %s", replay->name,
          s.x, replay->x);
    CHECK(replay->y == NULL || strcmp(s.y, replay->y) == 0, "%s: Y=%s, expected %s", replay->name,
          s.y, replay->y);
    CHECK(replay->aim == NULL || strcmp(s.aim, replay->aim) == 0, "%s: aim=%s, expected %s",
          replay->name, s.aim, replay->aim);
    CHECK(replay->menu == -1 || s.menu == menu, "%s: menu %d, expected %d", replay->name, s.menu,
          menu);
  }
}

static void caseCancelTakesBackAKey(void) {
  startCalculator();
  setStack(0, 0, 3, 4);
  press(KEY_PLUS);
  r47_key_cancel();
  state_t s = readState();
  CHECK(strcmp(s.x, "4.") == 0, "canceled + gave X=%s", s.x);
  tap(KEY_PLUS);
  s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "+ after the cancel gave X=%s", s.x);
}

static void caseCancelOfASingleStepLeavesNoRunState(void) {
  startCalculator();
  loadProgram("FactIt.p47");
  setStack(0, 0, 3, 4);
  press(KEY_DOWN);
  r47_key_cancel();
  state_t s = readState();
  CHECK(s.runStop != PGM_SINGLE_STEP, "runStop=%d", s.runStop);
}

static void assigningAtRest(void *arg) {
  (void)arg;
  runFunction(ITM_ASSIGN);
  itemToBeAssigned = ITM_ADD;
}

static void readAssignedAtRest(void *arg) { *(int *)arg = kbd_usr[0].primary; }

static void caseCancelDoesNotAssign(void) {
  startCalculator();
  int before = 0;
  r47_run_at_rest(readAssignedAtRest, &before);
  r47_run_at_rest(assigningAtRest, NULL);
  press(1);
  r47_key_cancel();
  state_t s = readState();
  int after = 0;
  r47_run_at_rest(readAssignedAtRest, &after);
  CHECK(after == before, "the canceled key was assigned: %d to %d", before, after);
  CHECK(s.calcMode == CM_ASSIGN, "calcMode=%d", s.calcMode);
}

static void switchOffAtRest(void *arg) {
  (void)arg;
  fnOff(NOPARAM);
}

static time_t backupTime(void) {
  char path[1024];
  struct stat info;
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  return stat(path, &info) == 0 ? info.st_mtime : 0;
}

static void caseOffSavesAndKeepsTheCalculatorAlive(void) {
  startCalculator();
  CHECK(backupTime() == 0, "a backup exists before OFF");
  setStack(0, 0, 3, 4);
  r47_run_at_rest(switchOffAtRest, NULL);
  for (int i = 0; i < 500 && atomic_load(&quitCount) == 0; i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&quitCount) == 1, "quit up-calls=%d", atomic_load(&quitCount));
  CHECK(backupTime() != 0, "OFF wrote no backup");
  tap(KEY_PLUS);
  state_t s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "the calculator is dead after OFF: X=%s", s.x);
}

static void caseSaveAtRestFinishesInsideTheFence(void) {
  startCalculator();
  CHECK(r47_service_save_and_wait(5000), "no save at rest");
  CHECK(backupTime() != 0, "no backup file");
}

static void caseSaveWaitsForACalculatorThatIsBusy(void) {
  startCalculator();
  startSlowFactors();
  CHECK(!r47_service_save_and_wait(100), "a save ran inside a computation");
  CHECK(backupTime() == 0, "a backup appeared inside a computation");
  tap(KEY_EXIT);
  readState();
  for (int i = 0; i < 500 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the pending save never ran");
}

// A stopped host can be frozen or killed before the calculator rests again, so the save
// it asked for runs at the next poll of a computation instead of waiting for the
// computation to end.
static void caseStoppedHostSavesInsideAComputation(void) {
  startCalculator();
  startSlowFactors();
  r47_service_save_and_wait(0);
  r47_keys_on_host_stopped(true);
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "a stopped host's save waited for the computation");
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  sleepMs(200);
  CHECK(!atomic_load(&probeRan), "the computation ended first, so nothing was proven");
  r47_keys_on_host_stopped(false);
  tap(KEY_EXIT);
}

static bool restWithin(int ms);
static void startRunningAndProveIt(void);

// The same for a running program, which polls between its steps.
static void caseStoppedHostSavesInsideARunningProgram(void) {
  startCalculator();
  enterLoopProgram(0);
  startRunningAndProveIt();
  r47_service_save_and_wait(0);
  r47_keys_on_host_stopped(true);
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "a stopped host's save waited for the program");
  CHECK(!atomic_load(&probeRan), "the program stopped first, so nothing was proven");
  r47_keys_on_host_stopped(false);
  tap(KEY_RS);
  CHECK(restWithin(2000), "the program did not stop");
}

// The HAL holds one open file, and a save opens its own, so a poll while the core has a
// file open must not save: the save waits until the file is closed.
static void pollWithAFileOpen(void *arg) {
  (void)arg;
  CHECK(ioFileOpen(ioPathAutoSave, ioModeWrite) == FILE_OK, "no file to hold open");
  const int64_t start = nowMs();
  while (nowMs() - start < 500) {
    key_empty();
    sleepMs(5);
  }
  ioFileClose();
}

static void caseStoppedHostSaveWaitsForAnOpenFile(void) {
  startCalculator();
  r47_run_at_rest_async(pollWithAFileOpen, NULL);
  sleepMs(100);
  r47_service_save_and_wait(0);
  r47_keys_on_host_stopped(true);
  sleepMs(200);
  CHECK(backupTime() == 0, "a save ran while the core had a file open");
  for (int i = 0; i < 300 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the save never ran once the file was closed");
}

static void casePasteSurvivesAComputation(void) {
  startCalculator();
  startSlowFactors();
  CHECK(r47_service_paste("29"), "the paste was refused");
  tap(KEY_EXIT);
  state_t s = readState();
  CHECK(atomic_load(&pasteResult) == 1, "paste result %d", atomic_load(&pasteResult));
  CHECK(s.calcMode == CM_NIM && strstr(s.aim, "29") != NULL, "aim=%s", s.aim);
}

static void saveRunningStateAtRest(void *arg) {
  (void)arg;
  programRunStop = PGM_RUNNING;
  setSystemFlag(FLAG_SOLVING);
  saveCalc();
  programRunStop = PGM_STOPPED;
  clearSystemFlag(FLAG_SOLVING);
}

static void caseFenceDoesNotWaitForABusyCalculator(void) {
  startCalculator();
  startSlowFactors();
  const int64_t start = nowMs();
  CHECK(!r47_service_save_and_wait(5000), "a save ran inside a computation");
  CHECK(nowMs() - start < 100, "the fence waited %d ms", (int)(nowMs() - start));
}

static void caseIdleCheckpointKeepsTypedWork(void) {
  startCalculator();
  r47_services_set_checkpoint_timing(300, 100);
  sleepMs(600);
  CHECK(backupTime() == 0, "a checkpoint ran with no key since the start");
  tap(KEY_ONE);
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "no checkpoint after typing");
  state_t s = readState();
  CHECK(s.calcMode == CM_NIM, "the checkpoint closed number entry: calcMode=%d", s.calcMode);
}

static void caseNoCheckpointWithAutosaveOff(void) {
  startCalculator();
  r47_service_set_autosave(false);
  r47_services_set_checkpoint_timing(100, 50);
  tap(KEY_ONE);
  readState();
  sleepMs(500);
  CHECK(backupTime() == 0, "a checkpoint ran with autosave off");
}

static void pauseForeverAtRest(void *arg) {
  (void)arg;
  programRunStop = PGM_RUNNING;
  fnPause(99);
}

static void caseSaveRunsInsideAPause(void) {
  startCalculator();
  r47_run_at_rest_async(pauseForeverAtRest, NULL);
  sleepMs(300);
  r47_service_save_and_wait(100);
  for (int i = 0; i < 300 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the pending save did not run in the pause");
  tap(KEY_EXIT);
  readState();
}

static long backupSize(void) {
  char path[1024];
  struct stat info;
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  return stat(path, &info) == 0 ? (long)info.st_size : -1;
}

static void caseKillDuringSavesNeverCutsTheFile(void) {
  startCalculator();
  saveOnceIdle();
  const long whole = backupSize();
  CHECK(whole > 100000, "the first save is %ld bytes", whole);
  fflush(NULL);
  for (int round = 0; round < 12; round++) {
    const pid_t child = fork();
    if (child == 0) {
      // Only this thread survives the fork, so a fresh image runs the saves.
      char self[1024];
      const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
      self[length] = 0;
      execl(self, self, workDir, programDir, "--save-forever", (char *)NULL);
      _exit(99);
    }
    sleepMs(150 + round * 37);
    kill(child, SIGKILL);
    int status = 0;
    waitpid(child, &status, 0);
    const long size = backupSize();
    CHECK(size > whole - 2000 && size < whole + 2000,
          "a kill left %ld bytes of about %ld", size, whole);
  }
}

static void saveForever(void) {
  startCalculator();
  for (;;) {
    r47_service_save_and_wait(5000);
  }
}

static void caseFailedSaveKeepsTheFileAndTheProcess(void) {
  startCalculator();
  saveOnceIdle();
  const long whole = backupSize();
  chmod(workDir, 0500);
  r47_service_save_and_wait(5000);
  chmod(workDir, 0700);
  CHECK(backupSize() == whole, "a failed save changed the file to %ld bytes", backupSize());
  setStack(0, 0, 3, 4);
  tap(KEY_PLUS);
  state_t s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "the calculator died with the save: X=%s", s.x);
}

static void readGraphVariableAtRest(void *arg) { *(int *)arg = (int)graphVariabl1; }
static void setGraphVariableAtRest(void *arg) { graphVariabl1 = *(int *)arg; }

static void caseSaveKeepsTheRunningCalculatorsState(void) {
  startCalculator();
  int variable = FIRST_RESERVED_VARIABLE;
  r47_run_at_rest(setGraphVariableAtRest, &variable);
  saveOnceIdle();
  int after = 0;
  r47_run_at_rest(readGraphVariableAtRest, &after);
  CHECK(after == variable, "the save changed the graph variable to %d", after);
}

static void caseStopKeySurvivesTheBufferWipe(void) {
  r47_keys_init();
  r47_key_push(KEY_ONE);
  r47_key_push(0);
  r47_key_push(KEY_EXIT);
  r47_key_push(0);
  key_pop_all();
  CHECK(key_pop() == KEY_EXIT, "the wipe took the stop key");
  CHECK(key_pop() == 0, "the wipe took its release");
  CHECK(key_empty(), "the wipe kept another key");
}

static void caseFileWithARunStateRestoresToRest(void) {
  startCalculator();
  r47_run_at_rest(saveRunningStateAtRest, NULL);
  fflush(NULL);
  const pid_t relaunch = fork();
  if (relaunch != 0) {
    int status = 0;
    waitpid(relaunch, &status, 0);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == CASE_PASSED, "relaunch status %x", status);
    return;
  }
  // Only this thread survives the fork, so the core's statics are those of a
  // process that restarts below. A second start in the same process is not what
  // this tests: exec a fresh image instead.
  char self[1024];
  const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
  self[length] = 0;
  execl(self, self, workDir, programDir, "--relaunch-after-run-state", (char *)NULL);
  _exit(99);
}

static void relaunchAfterRunState(void) {
  startCalculator();
  state_t s = readState();
  CHECK(s.runStop == PGM_STOPPED, "restored runStop=%d", s.runStop);
  CHECK(!s.solving, "restored FLAG_SOLVING");
  tap(KEY_ZERO + 0);
  s = readState();
  CHECK(s.calcMode == CM_NIM, "a digit did not enter: calcMode=%d", s.calcMode);
  tap(KEY_EXIT);
  s = readState();
  CHECK(s.lastError == 0, "EXIT raised error %d", s.lastError);
}

static void caseKeypadPublishesOnlyWhatChanged(void) {
  startCalculator();
  readState();
  sleepMs(1700); // past the activity window
  const uint32_t idle = r47_keypad_published_generation();
  sleepMs(500);
  CHECK(r47_keypad_published_generation() == idle, "published while idle");
  tap(KEY_F);
  readState();
  sleepMs(50);
  CHECK(r47_keypad_published_generation() != idle, "the f key published nothing");
}

static void caseStoppedHostParksTheThread(void) {
  startCalculator();
  sleepMs(300);
  r47_keys_on_host_stopped(true);
  sleepMs(200);
  const uint64_t parked = r47_get_host_lcd_refresh_count();
  sleepMs(500);
  CHECK(r47_get_host_lcd_refresh_count() == parked, "refreshed while parked");
  r47_keys_on_host_stopped(false);
  setStack(0, 0, 3, 4);
  tap(KEY_PLUS);
  state_t s = readState();
  CHECK(strcmp(s.x, "7.") == 0, "a parked thread did not wake: X=%s", s.x);
}

static void caseStopEndsTheThreadDuringAComputation(void) {
  const pthread_t thread = startCalculator();
  startSlowFactors();
  r47_calculator_stop(false);
  const int64_t start = nowMs();
  pthread_join(thread, NULL);
  CHECK(nowMs() - start < 3000, "stop took %d ms", (int)(nowMs() - start));
  CHECK(backupTime() == 0, "a stop with no save wrote the file");
}

// LBL 'XREC' XEQ 'XREC' RTN END, as program file bytes: a real program that recurses
// until RAM FULL, and whose RTN then frees a block the core has lost track of.
static const char xrecProgram[] =
    "PROGRAM_FILE_FORMAT\n0\nC47_program_file_version\n1\nPROGRAM\n17\n"
    "1\n253\n4\n88\n82\n69\n67\n3\n253\n4\n88\n82\n69\n67\n4\n133\n178\n";

static void writeTextFile(const char *path, const char *text) {
  FILE *file = fopen(path, "wb");
  CHECK(file != NULL, "cannot write %s", path);
  if (file != NULL) {
    fputs(text, file);
    fclose(file);
  }
}

static void loadProgramText(const char *text) {
  char dir[1024];
  char to[2048];
  snprintf(dir, sizeof(dir), "%s/PROGRAMS", workDir);
  mkdir(dir, 0700);
  snprintf(to, sizeof(to), "%s/program.p47", dir);
  writeTextFile(to, text);
  r47_run_at_rest(loadProgramAtRest, NULL);
}

static void runXrecAtRest(void *arg) {
  (void)arg;
  extern void reallyRunFunction(int16_t func, uint16_t param);
  const calcRegister_t label = findNamedLabel("XREC", ALL_LABELS);
  CHECK(label != INVALID_VARIABLE, "the program did not load");
  dynamicMenuItem = -1;
  lastErrorCode = ERROR_NONE;
  reallyRunFunction(ITM_XEQ, label);
  CHECK(lastErrorCode == ERROR_RAM_FULL, "the recursion ended with error %d", lastErrorCode);
}

static void returnAtRest(void *arg) {
  (void)arg;
  lastErrorCode = ERROR_NONE;
  runFunction(ITM_RTN);
}

// RTN after RAM FULL makes upstream's allocator report a bad free, through the
// execinfo stand-in: the report has to print and the calculator has to go on.
static void caseBadFreeReportDoesNotCrash(void) {
  startCalculator();
  loadProgramText(xrecProgram);
  r47_run_at_rest(runXrecAtRest, NULL);
  r47_run_at_rest(returnAtRest, NULL);
  CHECK(readState().lastError == ERROR_NONE, "the calculator did not go on");
}

static void execSelfInChild(const char *mode) {
  fflush(NULL);
  const pid_t child = fork();
  if (child != 0) {
    int status = 0;
    waitpid(child, &status, 0);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == CASE_PASSED, "%s: status %x", mode, status);
    return;
  }
  // See caseFileWithARunStateRestoresToRest: a relaunch needs a fresh image.
  char self[1024];
  const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
  self[length] = 0;
  execl(self, self, workDir, programDir, mode, (char *)NULL);
  _exit(99);
}

static int countFilesStartingWith(const char *prefix) {
  int count = 0;
  DIR *dir = opendir(workDir);
  if (dir == NULL) {
    return 0;
  }
  struct dirent *entry;
  while ((entry = readdir(dir)) != NULL) {
    count += strncmp(entry->d_name, prefix, strlen(prefix)) == 0;
  }
  closedir(dir);
  return count;
}

// How many state files were set aside, and remove them, so each relaunch starts clean.
static int takeKeptStateFiles(void) {
  int kept = 0;
  DIR *dir = opendir(workDir);
  if (dir == NULL) {
    return 0;
  }
  struct dirent *entry;
  while ((entry = readdir(dir)) != NULL) {
    if (strncmp(entry->d_name, "backupR47.cfg.v", 15) == 0) {
      char path[1400];
      snprintf(path, sizeof(path), "%s/%s", workDir, entry->d_name);
      unlink(path);
      kept++;
    }
  }
  closedir(dir);
  return kept;
}

static bool fileExists(const char *path) {
  struct stat st;
  return stat(path, &st) == 0;
}

// What a relaunch over a state file the core cannot take must show: a calculator that
// starts afresh, one warning, and the file moved aside, never restored as garbage.
static void relaunchExpectingAFreshStart(void) {
  char path[1024];
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  startCalculator();
  const state_t s = readState();
  CHECK(strcmp(s.x, "0.") == 0 && strcmp(s.y, "0.") == 0, "not a fresh start: X %s, Y %s", s.x,
        s.y);
  CHECK(atomic_load(&warningCount) == 1, "%d warnings", atomic_load(&warningCount));
  CHECK(!fileExists(path), "the unusable file is still where the next save writes");
}

// What a relaunch over a whole state file must show: the stack it saved, and no warning.
static void relaunchExpectingTheSavedStack(void) {
  startCalculator();
  const state_t s = readState();
  CHECK(strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0, "not restored: X %s, Y %s", s.x, s.y);
  CHECK(atomic_load(&warningCount) == 0, "%d warnings", atomic_load(&warningCount));
}

// A relaunch over a file cut at a line's end: a cut after the memory image restores, any
// other is set aside. Garbage, a crash, or a file left in place fails either way.
static void relaunchExpectingEither(void) {
  char path[1024];
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  startCalculator();
  const state_t s = readState();
  const bool fresh = strcmp(s.x, "0.") == 0 && strcmp(s.y, "0.") == 0 &&
                     atomic_load(&warningCount) == 1 && !fileExists(path);
  const bool restored =
      strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0 && atomic_load(&warningCount) == 0;
  CHECK(fresh || restored, "X %s, Y %s, %d warnings", s.x, s.y, atomic_load(&warningCount));
}

static void writeBytes(const char *path, const char *bytes, size_t length) {
  FILE *file = fopen(path, "wb");
  CHECK(file != NULL && fwrite(bytes, 1, length, file) == length, "cannot write %s", path);
  if (file) {
    fclose(file);
  }
}

// Write the saved file with its line "<name>:uint32:<old>" holding another number.
static void writeWithField(const char *path, const char *saved, size_t length, const char *name,
                           long value) {
  char key[64];
  snprintf(key, sizeof(key), "%s:uint32:", name);
  const char *at = strstr(saved, key);
  CHECK(at != NULL, "no %s line", name);
  if (at == NULL) {
    return;
  }
  const char *end = strchr(at, '\n');
  FILE *file = fopen(path, "wb");
  fwrite(saved, 1, (size_t)(at - saved), file);
  fprintf(file, "%s%ld", key, value);
  fwrite(end, 1, length - (size_t)(end - saved), file);
  fclose(file);
}

// Cuts across a state file, and the cuts that crashed 0.2.0's loads, each inside a line
// unless it lands on a line's end.
static void sweepCuts(const char *path, const char *saved, size_t length, long steps) {
  long cuts[64];
  size_t count = 0;
  const long fixed[] = {200, 600, 4096, 20480, 40960, 43400, 45056, 100000};
  for (size_t i = 0; i < sizeof(fixed) / sizeof(fixed[0]); i++) {
    cuts[count++] = fixed[i];
  }
  for (long step = 1; step < steps; step++) {
    cuts[count++] = (long)length * step / steps;
  }
  cuts[count++] = (long)length - 1;
  for (size_t i = 0; i < count; i++) {
    const bool atLineEnd = saved[cuts[i] - 1] == '\n';
    writeBytes(path, saved, (size_t)cuts[i]);
    execSelfInChild(atLineEnd ? "--relaunch-expect-either" : "--relaunch-expect-fresh");
    const int kept = takeKeptStateFiles();
    CHECK(kept == (fileExists(path) ? 0 : 1), "cut %ld: %d files kept", cuts[i], kept);
  }
}

// A state file the core cannot take, from a cut anywhere in it, an old or a newer core,
// or another memory size, is kept aside and the calculator starts afresh.
static void caseUnusableStateFileIsKeptAside(void) {
  startCalculator();
  loadProgram("FactIt.p47");
  setStack(0, 0, 3, 4);
  saveOnceIdle();
  char path[1024];
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  FILE *file = fopen(path, "rb");
  CHECK(file != NULL, "no state file");
  if (file == NULL) {
    return;
  }
  fseek(file, 0, SEEK_END);
  const size_t length = (size_t)ftell(file);
  fseek(file, 0, SEEK_SET);
  char *saved = malloc(length + 1);
  CHECK(fread(saved, 1, length, file) == length, "short read");
  saved[length] = '\0';
  fclose(file);

  writeBytes(path, saved, length);
  execSelfInChild("--relaunch-expect-saved-stack");
  CHECK(takeKeptStateFiles() == 0, "a whole file was set aside");

  sweepCuts(path, saved, length, 40);

  // Line ends before the memory image, every 32nd and the last, and the first inside it.
  const char *ram = strstr(saved, "\nram:hexDump:");
  int line = 0;
  for (const char *at = strchr(saved, '\n'); at != NULL && at <= ram;
       at = strchr(at + 1, '\n')) {
    if (line++ % 32 != 0 && at != ram) {
      continue;
    }
    writeBytes(path, saved, (size_t)(at - saved) + 1);
    execSelfInChild("--relaunch-expect-fresh");
    CHECK(takeKeptStateFiles() == 1, "cut at a line end before the memory image was not kept");
  }
  const char *firstDump = strchr(ram + 1, '\n');
  writeBytes(path, saved, (size_t)(strchr(firstDump + 1, '\n') - saved) + 1);
  execSelfInChild("--relaunch-expect-fresh");
  CHECK(takeKeptStateFiles() == 1, "a cut between two lines of the memory image was not kept");

  const long versions[] = {R47_BACKUP_VERSION_FLOOR - 1, R47_BACKUP_VERSION + 1};
  for (size_t i = 0; i < 2; i++) {
    writeWithField(path, saved, length, "backupVersion", versions[i]);
    execSelfInChild("--relaunch-expect-fresh");
    char prefix[64];
    snprintf(prefix, sizeof(prefix), "backupR47.cfg.v%ld.", versions[i]);
    CHECK(countFilesStartingWith(prefix) == 1, "version %ld was not kept as %s<time>",
          versions[i], prefix);
    takeKeptStateFiles();
  }
  writeWithField(path, saved, length, "ramSizeInBlocks", RAM_SIZE_IN_BLOCKS - 1);
  execSelfInChild("--relaunch-expect-fresh");
  CHECK(takeKeptStateFiles() == 1, "a file for another memory size was not kept");
  free(saved);
}

// LBL 01 <item> <argument> GTO 01, then one press of the stop key, 450 to 805 ms after
// R/S: the loop ends, stays ended, and takes no second press. A KEY? 08 loop takes
// the press wherever it lands. A PSE loop takes it while a pause polls the keys and
// at upstream's runProgram poll once the pause returns; the next PSE's fnPause then
// pops one key it never reads, so a press between that poll and that pop can be lost,
// on every build. PSE 18 holds each trial's press inside the first pause.
static void assertYieldingLoopStopsOn(int16_t item, int tensKey, int stopKey) {
  startCalculator();
  enterLoopWithArgument(item, tensKey);
  for (int trial = 0; trial < 6; trial++) {
    tap(KEY_RS);
    sleepMs(300 + trial * 71);
    atomic_store(&probeRan, false);
    r47_run_at_rest_async(markProbeRan, NULL);
    sleepMs(150);
    CHECK(!atomic_load(&probeRan), "trial %d: the program was not running", trial);
    press(stopKey);
    sleepMs(50);
    release();
    for (int i = 0; i < 200 && !atomic_load(&probeRan); i++) {
      sleepMs(10);
    }
    CHECK(atomic_load(&probeRan), "trial %d: one press did not stop the program", trial);
    if (!atomic_load(&probeRan)) {
      return;
    }
    sleepMs(50);
  }
}

static void casePauseLoopStopsOnRunStop(void) { assertYieldingLoopStopsOn(ITM_PAUSE, KEY_ONE, KEY_RS); }
static void casePauseLoopStopsOnExit(void) { assertYieldingLoopStopsOn(ITM_PAUSE, KEY_ONE, KEY_EXIT); }
static void caseKeyTestLoopStopsOnRunStop(void) { assertYieldingLoopStopsOn(ITM_KEYQ, KEY_ZERO, KEY_RS); }
static void caseKeyTestLoopStopsOnExit(void) { assertYieldingLoopStopsOn(ITM_KEYQ, KEY_ZERO, KEY_EXIT); }

static void setAutoExecuteAtRest(void *arg) {
  (void)arg;
  setSystemFlag(FLAG_AUTXEQ);
}

static void writeRestoreMarker(const char *count);

// The start before this one began a restore and did not finish it, so a second such
// start would set the file aside. A program AUTXEQ runs never reaches rest, so the
// restore's marker must be gone before it starts.
static void caseAutoExecuteRunsOnRelaunch(void) {
  startCalculator();
  enterLoopProgram(0);
  r47_run_at_rest(setAutoExecuteAtRest, NULL);
  saveOnceIdle();
  writeRestoreMarker("1\n");
  execSelfInChild("--relaunch-with-autxeq");
}

static void relaunchWithAutoExecute(void) {
  char marker[1100];
  snprintf(marker, sizeof(marker), "%s/backupR47.cfg.restoring", workDir);
  startCalculator();
  sleepMs(400);
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  sleepMs(400);
  CHECK(!atomic_load(&probeRan), "the saved program did not run on relaunch");
  CHECK(!fileExists(marker), "the program AUTXEQ runs held the restore marker");
  tap(KEY_EXIT);
  for (int i = 0; i < 300 && !atomic_load(&probeRan); i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&probeRan), "EXIT did not stop the program");
}

static void caseWrongProgramFileGivesTheWholeWarning(void) {
  startCalculator();
  loadProgramText("NOT_A_PROGRAM\nfoo\nbar\n");
  readState();
  CHECK(atomic_load(&warningCount) == 1, "%d warnings", atomic_load(&warningCount));
  CHECK(strstr(warningText, "not be loaded") != NULL, "warning was [%s]", warningText);
}

static void caseQueuedTonesEndWithTheRun(void) {
  startCalculator();
  enterLoopProgram(ITM_TONE);
  CHECK(atomic_load(&tonesClearCount) == 0, "cleared with nothing played");
  tap(KEY_RS);
  sleepMs(600);
  CHECK(atomic_load(&toneCount) > 0, "the loop played nothing");
  tap(KEY_RS);
  readState();
  sleepMs(100);
  const int cleared = atomic_load(&tonesClearCount);
  CHECK(cleared == 1, "cleared %d times after the stop", cleared);
  sleepMs(300);
  CHECK(atomic_load(&tonesClearCount) == cleared, "cleared again at rest");
}

// ---- what a redraw must not take from a running program ----

// A refresh moves keys to the ring buffer, which a running program's poll does not read.
// So each of these is the program that redraws: LBL 01 VIEW 01 <body> GTO 01. The
// body's argument is 8 after the key tensKey, or 8 alone when tensKey is 0.
static void enterViewLoop(int16_t bodyItem, int tensKey) {
  tap(KEY_F);
  tap(KEY_RS);
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_LBL);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_VIEW);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  if (bodyItem != 0) {
    r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)bodyItem);
    if (tensKey != 0) {
      tap(tensKey);
    }
    tap(KEY_EIGHT);
  }
  r47_run_at_rest(runFunctionAtRest, (void *)(intptr_t)ITM_GTO);
  tap(KEY_ZERO);
  tap(KEY_ONE);
  tap(KEY_EXIT);
  tap(KEY_EXIT);
  readState();
}

static bool restWithin(int ms) {
  for (int i = 0; i < ms / 10 && !atomic_load(&probeRan); i++) {
    sleepMs(10);
  }
  return atomic_load(&probeRan);
}

static void startRunningAndProveIt(void) {
  tap(KEY_RS);
  sleepMs(500);
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  sleepMs(200);
  CHECK(!atomic_load(&probeRan), "the program was not running");
}

static void assertViewLoopStopsOn(int stopKey) {
  startCalculator();
  enterViewLoop(0, 0);
  for (int trial = 0; trial < 4; trial++) {
    startRunningAndProveIt();
    press(stopKey);
    sleepMs(60);
    release();
    CHECK(restWithin(2000), "trial %d: one press did not stop the program", trial);
    if (!atomic_load(&probeRan)) {
      return;
    }
    // It stayed stopped: nothing left in the ring buffer starts it again.
    sleepMs(300);
    atomic_store(&probeRan, false);
    r47_run_at_rest_async(markProbeRan, NULL);
    CHECK(restWithin(300), "trial %d: the program started again", trial);
  }
}

static void caseViewLoopStopsOnRunStop(void) { assertViewLoopStopsOn(KEY_RS); }
static void caseViewLoopStopsOnExit(void) { assertViewLoopStopsOn(KEY_EXIT); }

// A pause polls only the FIFO too, and redraws. LBL 01 VIEW 01 PSE 18 GTO 01: the
// press, about 700 ms after R/S, lands inside the pause, since one between two pauses
// can be lost (assertYieldingLoopStopsOn).
static void caseViewLoopWithAPauseStopsOnOnePress(void) {
  startCalculator();
  enterViewLoop(ITM_PAUSE, KEY_ONE);
  for (int trial = 0; trial < 6; trial++) {
    startRunningAndProveIt();
    press(KEY_RS);
    sleepMs(60);
    release();
    CHECK(restWithin(2000), "trial %d: one press did not stop the program", trial);
    if (!atomic_load(&probeRan)) {
      return;
    }
    sleepMs(200);
  }
}

// Two taps of R/S with no gap: however many keys the first wake finds, the next press
// stops the program and nothing queued starts it again.
static void caseDoubleTapLeavesTheProgramStoppable(void) {
  startCalculator();
  enterViewLoop(0, 0);
  for (int trial = 0; trial < 20; trial++) {
    tap(KEY_RS);
    tap(KEY_RS);
    sleepMs(300);
    atomic_store(&probeRan, false);
    r47_run_at_rest_async(markProbeRan, NULL);
    if (!restWithin(300)) {
      tap(KEY_RS);
      CHECK(restWithin(2000), "trial %d: the program did not stop", trial);
      if (!atomic_load(&probeRan)) {
        return;
      }
    }
    sleepMs(300);
    atomic_store(&probeRan, false);
    r47_run_at_rest_async(markProbeRan, NULL);
    CHECK(restWithin(300), "trial %d: the program started again", trial);
    if (!atomic_load(&probeRan)) {
      return;
    }
  }
}

static void caseKeyTestSeesAKeyBehindAView(void) {
  startCalculator();
  enterViewLoop(ITM_KEYQ, KEY_ZERO);
  startRunningAndProveIt();
  press(KEY_ONE);
  sleepMs(60);
  release();
  CHECK(restWithin(2000), "KEY? never saw the key");
}

static void caseStopEndsTheThreadInAViewLoop(void) {
  const pthread_t thread = startCalculator();
  enterViewLoop(0, 0);
  startRunningAndProveIt();
  const int64_t start = nowMs();
  r47_calculator_stop(false);
  pthread_join(thread, NULL);
  CHECK(nowMs() - start < 3000, "stop took %d ms", (int)(nowMs() - start));
}

static void caseSaveWaitsOutAViewLoopAtItsNextRest(void) {
  startCalculator();
  enterViewLoop(0, 0);
  startRunningAndProveIt();
  CHECK(!r47_service_save_and_wait(100), "a save ran inside a running program");
  CHECK(backupTime() == 0, "a file appeared inside a running program");
  tap(KEY_RS);
  CHECK(restWithin(2000), "the program did not stop");
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the pending save never ran");
}

// A redraw in a computation can carry a quit to the ring, whose wipe loses it, so the
// operation's next poll has to find the quit anyway, once.
static void caseQuitSurvivesARedrawAndTheWipe(void) {
  r47_keys_init();
  r47_key_push_quit();
  keyBuffer_pop();
  clearKeyBuffer();
  CHECK(anyKeyWaiting(), "the poll cannot see the quit");
  CHECK(key_pop() == KEY_EXIT, "the operation was not told to stop");
  CHECK(key_pop() == 0, "no release behind the stop");
  CHECK(!anyKeyWaiting(), "the poll would never finish");
}

// ---- held keys ----

static void caseHeldKeyHoldsTheProgramUntilItsRelease(void) {
  startCalculator();
  enterLoopProgram(0);
  startRunningAndProveIt();
  press(KEY_RS);
  CHECK(!restWithin(500), "a program stopped on a press still down");
  release();
  CHECK(restWithin(2000), "the release did not end the hold");
}

static void caseCancelEndsAHold(void) {
  startCalculator();
  enterLoopProgram(0);
  startRunningAndProveIt();
  press(KEY_RS);
  sleepMs(300);
  r47_key_cancel();
  CHECK(restWithin(2000), "a canceled press still holds the thread");
}

static void caseStoppingHostEndsAHold(void) {
  startCalculator();
  enterLoopProgram(0);
  startRunningAndProveIt();
  press(KEY_RS);
  sleepMs(300);
  r47_key_release_if_down();
  CHECK(restWithin(2000), "a press left down holds the thread");
}

// ---- parking and pauses ----

static void armSpareTimerAtRest(void *arg) {
  (void)arg;
  sys_timer_start(SPARE_TIMER, 5000);
}

// With a timer running the thread waits with a deadline, and a parked one waits without.
static void caseParkingStopsARunningTimer(void) {
  startCalculator();
  r47_run_at_rest(armSpareTimerAtRest, NULL);
  sleepMs(100);
  CHECK(atomic_load(&timedWaits) > 0, "no wait had a deadline, so this proves nothing");
  atomic_store(&timedWaits, 0);
  r47_keys_on_host_stopped(true);
  sleepMs(500);
  CHECK(atomic_load(&timedWaits) == 0, "a parked thread waited with a deadline");
  r47_keys_on_host_stopped(false);
  sleepMs(100);
  CHECK(atomic_load(&timedWaits) > 0, "the timer did not come back");
}

static void timedPauseAtRest(void *arg) {
  (void)arg;
  programRunStop = PGM_RUNNING;
  fnPause(98); // in tens of milliseconds, so this outlasts the wait below
  programRunStop = PGM_STOPPED;
}

static void caseSaveRunsInsideATimedPause(void) {
  startCalculator();
  r47_run_at_rest_async(timedPauseAtRest, NULL);
  sleepMs(300);
  r47_service_save_and_wait(100);
  // Well inside the pause, which would run the save at its end anyway.
  for (int i = 0; i < 50 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the timed pause did not take the save");
  readState();
}

static void solverPauseAtRest(void *arg) {
  (void)arg;
  setSystemFlag(FLAG_SOLVING);
  programRunStop = PGM_RUNNING;
  fnPause(99);
  programRunStop = PGM_STOPPED;
  clearSystemFlag(FLAG_SOLVING);
}

// A pause inside a solver holds a half-done computation, so no save belongs there, and
// a caller at the fence must not wait for one.
static void caseNoSaveInAPauseInsideASolver(void) {
  startCalculator();
  r47_run_at_rest_async(solverPauseAtRest, NULL);
  sleepMs(300);
  const int64_t start = nowMs();
  CHECK(!r47_service_save_and_wait(5000), "a save ran inside a solver's pause");
  CHECK(nowMs() - start < 1000, "the fence waited %d ms", (int)(nowMs() - start));
  CHECK(backupTime() == 0, "a file appeared inside a solver's pause");
  tap(KEY_EXIT);
  readState();
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "the save did not run at rest");
}

// ---- saves ----

static void readModeAtRest(void *arg) {
  int *values = arg;
  values[0] = calcMode;
  values[1] = temporaryInformation;
}

static void confirmedAtRest(uint16_t unused) { (void)unused; }

static void askForConfirmationAtRest(void *arg) {
  (void)arg;
  extern void setConfirmationMode(void (*func)(uint16_t));
  setConfirmationMode(confirmedAtRest);
  refreshScreen(1);
}

static void caseSaveKeepsAQuestionOnScreen(void) {
  startCalculator();
  r47_run_at_rest(askForConfirmationAtRest, NULL);
  saveOnceIdle();
  int values[2] = {0, 0};
  r47_run_at_rest(readModeAtRest, values);
  CHECK(values[0] == CM_CONFIRMATION, "the save closed the question: calcMode=%d", values[0]);
  CHECK(values[1] == TI_ARE_YOU_SURE, "temporaryInformation=%d", values[1]);
}

static void caseShortWriteKeepsTheFile(void) {
  startCalculator();
  saveOnceIdle();
  const long whole = backupSize();
  CHECK(whole > 0, "no first save");
  setStack(0, 0, 3, 4);
  atomic_store(&diskFull, true);
  CHECK(!saveWhenIdle(), "a save the disk refused answered saved");
  atomic_store(&diskFull, false);
  CHECK(backupSize() == whole, "a failed write changed the file: %ld to %ld", whole, backupSize());
  char temp[1024];
  snprintf(temp, sizeof(temp), "%s/backupR47.cfg.tmp", workDir);
  CHECK(access(temp, F_OK) != 0, "a failed write left its temporary file");
  tap(KEY_PLUS);
  CHECK(strcmp(readState().x, "7.") == 0, "the calculator did not go on");
}

// Each save that lands renames a new file over the old one, so the inode tells them apart.
static ino_t backupInode(void) {
  char path[1024];
  struct stat info;
  snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
  return stat(path, &info) == 0 ? info.st_ino : 0;
}

// A save the disk refused is not taken as done: the next checkpoint writes the state
// again once the disk has room, with no key pressed in between.
static void caseCheckpointRetriesAFailedSave(void) {
  startCalculator();
  r47_services_set_checkpoint_timing(60000, 60000);
  saveOnceIdle();
  const ino_t first = backupInode();
  CHECK(first != 0, "no first save");
  tap(KEY_ONE);
  atomic_store(&diskFull, true);
  CHECK(!saveWhenIdle(), "a save the disk refused answered saved");
  atomic_store(&diskFull, false);
  CHECK(backupInode() == first, "a refused save replaced the file");
  r47_services_set_checkpoint_timing(300, 100);
  r47_keys_wake();
  for (int i = 0; i < 300 && backupInode() == first; i++) {
    sleepMs(10);
  }
  CHECK(backupInode() != first, "no checkpoint wrote the state the refused save lost");
}

static void nothingAtRest(void *arg) { (void)arg; }

// A service at rest leaves refreshLcd to the main loop's period: one call more would
// take a period off a held key's function name, so a burst of services, as a screen
// reader's descriptions come, makes no more calls than the periods it lasts.
static void caseServicesAddNoLcdRefresh(void) {
  startCalculator();
  readState();
  const int64_t start = nowMs();
  const int before = atomic_load(&refreshLcdCount);
  for (int i = 0; i < 50; i++) {
    r47_run_at_rest(nothingAtRest, NULL);
  }
  const int calls = atomic_load(&refreshLcdCount) - before;
  const int periods = (int)((nowMs() - start) / SCREEN_REFRESH_PERIOD) + 2;
  CHECK(calls <= periods, "50 services made %d refreshLcd calls in %d periods", calls,
        periods);
}

// The clock counts from the calculator's start, not the phone's: upstream compares its
// deadlines with a plain <=, which then holds for 49.7 days of a process.
static void caseTheClockStartsWithTheCalculator(void) {
  const int64_t before = nowMs();
  startCalculator();
  const uint32_t clock = sys_current_ms();
  const uint32_t elapsed = (uint32_t)(nowMs() - before);
  CHECK(clock <= elapsed + 10u, "the clock read %u ms, %u ms after the start", clock, elapsed);
}

static void setClockAtRest(void *arg) { r47_keys_set_clock_ms((uint32_t)(uintptr_t)arg); }

// The glue's own intervals take unsigned differences, so they hold across the clock's
// wrap: a key just before it, and the checkpoint that waits for the quiet after it,
// just past it, still runs.
static void caseACheckpointRunsAcrossTheClocksWrap(void) {
  startCalculator();
  r47_services_set_checkpoint_timing(60000, 60000);
  saveOnceIdle(); // nothing left to save, so only the key below makes a checkpoint due
  const ino_t saved = backupInode();
  r47_run_at_rest(setClockAtRest, (void *)(uintptr_t)(UINT32_MAX - 50u));
  r47_services_set_checkpoint_timing(300, 100);
  tap(KEY_ONE);
  for (int i = 0; i < 200 && backupInode() == saved; i++) {
    sleepMs(10);
  }
  CHECK(backupInode() != saved, "no checkpoint across the clock's wrap");
}

static void caseSaveReachesTheDisk(void) {
  startCalculator();
  readState();
  atomic_store(&fsyncCount, 0);
  saveOnceIdle();
  CHECK(atomic_load(&fsyncCount) >= 2, "%d syncs: the file and its directory each need one",
        atomic_load(&fsyncCount));
}

// The fence is for a thread that cannot answer. One that is saving already can: this
// asks during a save that takes far longer than the grace.
static void caseSlowSaveStillAnswersInsideTheFence(void) {
  startCalculator();
  saveOnceIdle();
  atomic_store(&fsyncDelayMs, 200);
  r47_service_save_and_wait(1);
  sleepMs(20);
  CHECK(!r47_keys_calculator_idle(), "the first save was not running, so this proves nothing");
  CHECK(r47_service_save_and_wait(3000), "a save was refused while the calculator was saving");
  atomic_store(&fsyncDelayMs, 0);
}

static void caseCheckpointWaitsForAQuietMoment(void) {
  startCalculator();
  r47_services_set_checkpoint_timing(100, 400);
  for (int i = 0; i < 12; i++) {
    tap(KEY_ONE);
    sleepMs(100);
  }
  CHECK(backupTime() == 0, "a checkpoint ran during typing");
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "no checkpoint after the typing stopped");
}

static void caseOffLeavesTheFileWithAutosaveOff(void) {
  startCalculator();
  r47_service_set_autosave(false);
  setStack(0, 0, 3, 4);
  r47_run_at_rest(switchOffAtRest, NULL);
  for (int i = 0; i < 500 && atomic_load(&quitCount) == 0; i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&quitCount) == 1, "quit up-calls=%d", atomic_load(&quitCount));
  CHECK(backupTime() == 0, "OFF wrote the file with autosave off");
}

// ---- the rest of what the review found ----

static void assigningSoftkeyAtRest(void *arg) {
  (void)arg;
  showSoftmenu(-MNU_MyMenu);
  runFunction(ITM_ASSIGN);
  itemToBeAssigned = ITM_ADD;
}

static void readMenuAssignmentAtRest(void *arg) { *(int *)arg = userMenuItems[0].item; }

static void caseCancelOfASoftkeyDoesNotAssign(void) {
  startCalculator();
  int before = 0;
  r47_run_at_rest(assigningSoftkeyAtRest, NULL);
  r47_run_at_rest(readMenuAssignmentAtRest, &before);
  press(KEY_SOFTKEY_1);
  sleepMs(30);
  r47_key_cancel();
  state_t s = readState();
  int after = 0;
  r47_run_at_rest(readMenuAssignmentAtRest, &after);
  CHECK(after == before, "the canceled softkey was assigned: %d to %d", before, after);
  CHECK(s.calcMode == CM_ASSIGN, "calcMode=%d", s.calcMode);
}

// A screen reader asks for a description after each settled frame, and a long program
// keeps redrawing: the requests are one pending description, as gestures are one
// gesture, so they leave the queue to what the user asks for, and the description
// comes once, at rest.
static void caseDescriptionsLeaveTheQueueToACopy(void) {
  startCalculator();
  startSlowFactors();
  atomic_store(&displayTextCount, 0);
  for (int i = 0; i < 40; i++) {
    r47_service_describe_display();
  }
  CHECK(r47_service_copy(R47_COPY_X), "40 description requests left no room for a copy");
  tap(KEY_EXIT);
  readState();
  for (int i = 0; i < 200 && atomic_load(&displayTextCount) == 0; i++) {
    sleepMs(5);
  }
  sleepMs(50);
  CHECK(atomic_load(&displayTextCount) == 1, "%d descriptions for 40 requests",
        atomic_load(&displayTextCount));
}

static void caseRefusedKeypadModeIsAskedForAgain(void) {
  startCalculator();
  startSlowFactors();
  while (r47_service_copy(R47_COPY_X)) {
  }
  static int32_t fill[R47_KEYPAD_META_LENGTH];
  static char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY][R47_KEYPAD_LABEL_CAPACITY];
  CHECK(!r47_try_copy_app_keypad_snapshot(fill, labels, 1), "the mode was already published");
  tap(KEY_EXIT);
  readState();
  bool copied = false;
  for (int i = 0; i < 100 && !copied; i++) {
    copied = r47_try_copy_app_keypad_snapshot(fill, labels, 1);
    sleepMs(10);
  }
  CHECK(copied, "a mode the full queue refused was never asked for again");
}

typedef struct {
  const char *name;
  void (*run)(void);
} testCase_t;

// Each entry that checks its thread, so a path off the calculator thread aborts at its
// first call; build_calculator_thread_harness.sh fails when one is missing here. The
// static runService is reached through r47_services_run.
extern const char *r47_picked_file_name(void);
static void offCheckpoint(void) { r47_services_checkpoint(false); }
static void offSavePending(void) { r47_services_save_pending(); }
static void offSaveBeforePicker(void) { r47_save_before_picker(); }
static void offServicesRun(void) { r47_services_run(); }
static void offDisplayText(void) {
  bool isError = false;
  free(r47_display_text(&isError));
}
static void offPickedFileName(void) { (void)r47_picked_file_name(); }
static void offRequestAndroidFile(void) { (void)requestAndroidFile(0, "x.p47", 1); }
static void offPcKeyHandle(void) { r47_pc_key_handle('a', 0, true, false); }
static void offPcKeyCancel(void) { r47_pc_key_cancel(PC_SHIFT_L, false); }
static void offKeypadPublishAtRest(void) { r47_keypad_publish_at_rest(); }
static void offSaveSettled(void) { (void)r47_save_settled(); }
static void offForceRefresh(void) { r47_force_refresh(); }
static void offKeyPushKey(void) { r47_key_push_key("01", true, 1); }
static void offKeyPushClick(void) { r47_key_push_click("01", 1); }
static void offKeyDispatchHostEvent(void) { keyDispatchHostEvent(0); }
static void offKeyEmpty(void) { (void)key_empty(); }
static void offKeyTail(void) { (void)key_tail(); }
static void offKeyPopForMainLoop(void) { (void)keyPopForMainLoop(); }
static void offKeyPop(void) { (void)key_pop(); }
static void offKeyPopAll(void) { key_pop_all(); }
static void offWaitForKeyRelease(void) { wait_for_key_release(0); }
static void offSysSleep(void) { sys_sleep(); }
static void offSysDelay(void) { sys_delay(1); }
static void offSysTimerStart(void) { sys_timer_start(0, 10); }
static void offSysTimerDisable(void) { sys_timer_disable(0); }
static void offCaptureClipboard(void) { free(r47_capture_clipboard(NULL)); }

// The rule the check holds: a core path on any other thread aborts at its first call.
// This thread is not the calculator's, so none of these may return.
static void caseACoreCallOffItsThreadAborts(void) {
  startCalculator();
  static const struct {
    const char *name;
    void (*call)(void);
  } entries[] = {
      {"r47_services_checkpoint", offCheckpoint},
      {"r47_services_save_pending", offSavePending},
      {"r47_save_before_picker", offSaveBeforePicker},
      {"r47_services_run", offServicesRun},
      {"r47_display_text", offDisplayText},
      {"r47_picked_file_name", offPickedFileName},
      {"requestAndroidFile", offRequestAndroidFile},
      {"r47_pc_key_handle", offPcKeyHandle},
      {"r47_pc_key_cancel", offPcKeyCancel},
      {"r47_keypad_publish_at_rest", offKeypadPublishAtRest},
      {"r47_save_settled", offSaveSettled},
      {"r47_force_refresh", offForceRefresh},
      {"r47_key_push_key", offKeyPushKey},
      {"r47_key_push_click", offKeyPushClick},
      {"keyDispatchHostEvent", offKeyDispatchHostEvent},
      {"key_empty", offKeyEmpty},
      {"key_tail", offKeyTail},
      {"keyPopForMainLoop", offKeyPopForMainLoop},
      {"key_pop", offKeyPop},
      {"key_pop_all", offKeyPopAll},
      {"wait_for_key_release", offWaitForKeyRelease},
      {"sys_sleep", offSysSleep},
      {"sys_delay", offSysDelay},
      {"sys_timer_start", offSysTimerStart},
      {"sys_timer_disable", offSysTimerDisable},
      {"r47_capture_clipboard", offCaptureClipboard},
  };
  for (size_t i = 0; i < sizeof(entries) / sizeof(entries[0]); i++) {
    fflush(NULL);
    const pid_t child = fork();
    if (child == 0) {
      alarm(5); // with no check to abort it, a wait would last for good
      entries[i].call();
      _exit(CASE_PASSED);
    }
    int status = 0;
    waitpid(child, &status, 0);
    CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT,
          "%s returned off the calculator thread (status %x)", entries[i].name, status);
  }
}

// The app links every exit to a logged abort, and so does this harness, so a core that
// ends the process leaves a crash report, not a silent stop.
static void caseAnExitAborts(void) {
  fflush(NULL);
  const pid_t child = fork();
  if (child == 0) {
    exit(7);
  }
  int status = 0;
  waitpid(child, &status, 0);
  CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT, "an exit ended with status %x",
        status);
}

static const double storedBounds[4] = {-7.25, 12.5, -3.75, 9.5};
static const calcRegister_t boundVariables[4] = {RESERVED_VARIABLE_LX, RESERVED_VARIABLE_UX,
                                                 RESERVED_VARIABLE_LY, RESERVED_VARIABLE_UY};

static void storeBoundsAndAMatrixAtRest(void *arg) {
  (void)arg;
  for (int i = 0; i < 4; i++) {
    convertDoubleToReal34Register(storedBounds[i], boundVariables[i]);
  }
  // The largest square matrix memory holds, so a copy of it aside cannot fit.
  for (uint16_t n = 120; n >= 10; n -= 2) {
    lastErrorCode = ERROR_NONE; // a size that did not fit leaves RAM FULL behind
    if (initMatrixRegister(REGISTER_X, n, n, false)) {
      return;
    }
  }
}

typedef struct {
  double bounds[4];
  int xType;
  int rows;
  int columns;
} boundsAndMatrix_t;

static void readBoundsAndMatrixAtRest(void *arg) {
  boundsAndMatrix_t *out = arg;
  for (int i = 0; i < 4; i++) {
    real_t value;
    real34ToReal(REGISTER_REAL34_DATA(boundVariables[i]), &value);
    realToDouble(&value, &out->bounds[i]);
  }
  out->xType = (int)getRegisterDataType(REGISTER_X);
  if (out->xType == dtReal34Matrix) {
    out->rows = REGISTER_MATRIX_HEADER(REGISTER_X)->matrixRows;
    out->columns = REGISTER_MATRIX_HEADER(REGISTER_X)->matrixColumns;
  }
}

static void relaunchExpectingBoundsAndMatrix(void) {
  startCalculator();
  boundsAndMatrix_t read = {0};
  r47_run_at_rest(readBoundsAndMatrixAtRest, &read);
  for (int i = 0; i < 4; i++) {
    CHECK(read.bounds[i] == storedBounds[i], "bound %d is %g, stored %g", i, read.bounds[i],
          storedBounds[i]);
  }
  const int n = atoi(getenv("R47_HARNESS_MATRIX_SIZE"));
  CHECK(read.xType == dtReal34Matrix && read.rows == n && read.columns == n,
        "X is type %d, %dx%d, not %dx%d", read.xType, read.rows, read.columns, n, n);
}

// A cold start copied the plot window over LX, UX, LY, and UY, through X and a scratch
// register. What the user stored moved on every launch, and a matrix in X too large to
// copy aside was lost.
static void caseAColdStartKeepsTheBoundsAndX(void) {
  startCalculator();
  r47_run_at_rest(storeBoundsAndAMatrixAtRest, NULL);
  boundsAndMatrix_t before = {0};
  r47_run_at_rest(readBoundsAndMatrixAtRest, &before);
  CHECK(before.xType == dtReal34Matrix && before.rows > 10, "no large matrix to keep");
  char size[16];
  snprintf(size, sizeof(size), "%d", before.rows);
  setenv("R47_HARNESS_MATRIX_SIZE", size, 1);
  saveOnceIdle();
  execSelfInChild("--relaunch-expect-bounds-and-matrix");
  execSelfInChild("--relaunch-expect-bounds-and-matrix");
}

static void writeRestoreMarker(const char *count) {
  char marker[1100];
  snprintf(marker, sizeof(marker), "%s/backupR47.cfg.restoring", workDir);
  writeBytes(marker, count, strlen(count));
}

static void relaunchExpectingTheMarkerGone(void) {
  char marker[1100];
  snprintf(marker, sizeof(marker), "%s/backupR47.cfg.restoring", workDir);
  startCalculator();
  CHECK(!fileExists(marker), "the marker outlived the restore and its first refresh");
  readState();
}

// A state file that crashed two starts in a row is set aside on the third, which starts
// afresh; one failed start, which closing the app can cause, restores as usual.
static void caseAFileThatCrashedTwoStartsIsSetAside(void) {
  startCalculator();
  setStack(0, 0, 3, 4);
  saveOnceIdle();
  writeRestoreMarker("1\n");
  execSelfInChild("--relaunch-expect-saved-stack");
  execSelfInChild("--relaunch-expect-marker-gone");
  CHECK(countFilesStartingWith("backupR47.cfg.crashed.") == 0,
        "one failed start set the file aside");

  // Each set-aside copy keeps its own name.
  for (int round = 1; round <= 2; round++) {
    saveOnceIdle();
    writeRestoreMarker("2\n");
    execSelfInChild("--relaunch-expect-fresh");
    CHECK(countFilesStartingWith("backupR47.cfg.crashed.") == round,
          "two failed starts kept %d copies, not %d",
          countFilesStartingWith("backupR47.cfg.crashed."), round);
  }
}

static void deleteBackupAtRest(void *arg) {
  (void)arg;
  fnDeleteBackup(CONFIRMED);
}

// DELBkup removes the SAVE and automatic save, as the hardware does. Upstream
// names a path relative to a working directory, so on Android it removed nothing.
static void caseDeleteBackupRemovesBothSaves(void) {
  startCalculator();
  char dir[1100];
  char manual[1200];
  char automatic[1200];
  snprintf(dir, sizeof(dir), "%s/SAVFILES", workDir);
  snprintf(manual, sizeof(manual), "%s/R47.sav", dir);
  snprintf(automatic, sizeof(automatic), "%s/R47auto.sav", dir);
  mkdir(dir, 0700);
  writeBytes(manual, "x", 1);
  writeBytes(automatic, "x", 1);
  r47_run_at_rest(deleteBackupAtRest, NULL);
  const state_t s = readState();
  CHECK(!fileExists(manual) && !fileExists(automatic), "DELBkup left a save behind");
  CHECK(s.lastError == ERROR_NONE, "DELBkup raised error %d", s.lastError);
}

static void dumpScreenAtRest(void *arg) {
  (void)arg;
  fnScreenDump(0);
}

// A SNAP whose capture the disk cut short is not offered, and says so: its header
// names a size the file does not have. A whole one reaches the picker's file.
static void caseACutScreenCaptureIsNotOffered(void) {
  startCalculator();
  char picked[1100];
  snprintf(picked, sizeof(picked), "%s/picked.bmp", workDir);
  // The picker's answer is ready before any capture, so an offer never waits for one.
  r47_set_file_request_override(open(picked, O_WRONLY | O_CREAT | O_TRUNC, 0600), 1, 3);

  atomic_store(&diskRoom, 200); // the header and a few rows, then a full disk
  r47_run_at_rest(dumpScreenAtRest, NULL);
  atomic_store(&diskRoom, -1);
  for (int i = 0; i < 300 && atomic_load(&warningCount) == 0; i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&warningCount) == 1, "%d warnings for a cut capture",
        atomic_load(&warningCount));
  struct stat st;
  CHECK(stat(picked, &st) == 0 && st.st_size == 0, "a cut capture reached the picker");

  r47_run_at_rest(dumpScreenAtRest, NULL);
  for (int i = 0; i < 300 && (stat(picked, &st) != 0 || st.st_size <= 54); i++) {
    sleepMs(10);
  }
  CHECK(stat(picked, &st) == 0 && st.st_size > 54, "a whole capture did not reach the picker");
  CHECK(atomic_load(&warningCount) == 1, "a whole capture warned");
  r47_clear_file_request_override();
}

static void startStopwatchAtRest(void *arg) {
  (void)arg;
  fnStartStopTimerApp(NOPARAM);
}

typedef struct {
  uint32_t value;
  uint32_t startTime;
  bool running;
} stopwatch_t;

static void readStopwatchAtRest(void *arg) {
  stopwatch_t *out = arg;
  out->value = timerValue;
  out->startTime = timerStartTime;
  out->running = timerStartTime != TIMER_APP_STOPPED && getSystemFlag(FLAG_RUNTIM);
}

static void relaunchExpectingAStoppedStopwatch(void) {
  startCalculator();
  stopwatch_t read = {0};
  r47_run_at_rest(readStopwatchAtRest, &read);
  CHECK(!read.running, "the restored stopwatch says it runs");
  CHECK(read.value >= 250, "the restored stopwatch lost its time: %u ms", read.value);
}

// A save takes a running stopwatch's time, as upstream's saves do, and leaves it
// running: a file saved running restored as running with nothing behind it, time lost.
static void caseASaveKeepsTheStopwatchTime(void) {
  startCalculator();
  r47_run_at_rest(startStopwatchAtRest, NULL);
  sleepMs(300);
  stopwatch_t before = {0};
  r47_run_at_rest(readStopwatchAtRest, &before);
  atomic_store(&fsyncDelayMs, 200); // a slow disk must not cost the stopwatch its time
  saveOnceIdle();
  atomic_store(&fsyncDelayMs, 0);
  stopwatch_t live = {0};
  r47_run_at_rest(readStopwatchAtRest, &live);
  CHECK(live.running, "the save stopped the stopwatch");
  CHECK(live.startTime == before.startTime && live.value == before.value,
        "the save moved the stopwatch: start %u to %u, value %u to %u", before.startTime,
        live.startTime, before.value, live.value);
  execSelfInChild("--relaunch-expect-stopped-stopwatch");
}

// The state files phones carry, saved by the cores of past releases with
// make_state_fixture.sh: FactIt.p47 loaded, and 1, 2, 3, 4 on the stack.
static const char *const releasedStateFiles[] = {"backupR47-0.1.13.cfg.gz",
                                                 "backupR47-0.2.0.cfg.gz"};

static void findFactItAtRest(void *arg) {
  *(bool *)arg = findNamedLabel("FactIt", ALL_LABELS) != INVALID_VARIABLE;
}

static void relaunchExpectingTheReleasedState(void) {
  startCalculator();
  const state_t s = readState();
  bool factIt = false;
  r47_run_at_rest(findFactItAtRest, &factIt);
  CHECK(strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0 && strcmp(s.z, "2.") == 0 &&
            strcmp(s.t, "1.") == 0,
        "not restored: %s %s %s %s", s.t, s.z, s.y, s.x);
  CHECK(factIt, "the program FactIt is lost");
  CHECK(atomic_load(&warningCount) == 0, "%d warnings", atomic_load(&warningCount));
}

// gzip takes both paths as they are: no shell reads them, so a quote or a space in
// one is part of the name.
static bool unpackTo(const char *from, const char *to) {
  fflush(NULL);
  const pid_t child = fork();
  if (child == 0) {
    const int out = open(to, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0 || dup2(out, STDOUT_FILENO) < 0) {
      _exit(126);
    }
    execlp("gzip", "gzip", "-dc", "--", from, (char *)NULL);
    _exit(127);
  }
  int status = 0;
  return child > 0 && waitpid(child, &status, 0) == child && WIFEXITED(status) &&
         WEXITSTATUS(status) == 0;
}

// Each released core's state file restores whole at this core, and so does the file
// this core saves from it.
static void caseReleasedStateFilesRestore(void) {
  const char *fixtures = getenv("R47_STATE_FIXTURES");
  CHECK(fixtures != NULL, "R47_STATE_FIXTURES names no directory");
  if (fixtures == NULL) {
    return;
  }
  for (size_t i = 0; i < sizeof(releasedStateFiles) / sizeof(releasedStateFiles[0]); i++) {
    char fixture[1100];
    snprintf(fixture, sizeof(fixture), "%s/%s", fixtures, releasedStateFiles[i]);
    char path[1100];
    snprintf(path, sizeof(path), "%s/backupR47.cfg", workDir);
    CHECK(unpackTo(fixture, path), "cannot unpack %s", releasedStateFiles[i]);
    FILE *file = fopen(path, "rb");
    CHECK(file != NULL, "no unpacked %s", releasedStateFiles[i]);
    if (file == NULL) {
      continue;
    }
    fseek(file, 0, SEEK_END);
    const size_t length = (size_t)ftell(file);
    rewind(file);
    char *released = malloc(length);
    CHECK(fread(released, 1, length, file) == length, "short read");
    fclose(file);
    sweepCuts(path, released, length, 12);
    writeBytes(path, released, length);
    free(released);
    execSelfInChild("--relaunch-expect-released-state");
    execSelfInChild("--relaunch-save-and-stop");
    execSelfInChild("--relaunch-expect-released-state");
  }
}

static void relaunchSavingAndStopping(void) {
  startCalculator();
  saveOnceIdle();
}

static void readUserKeysAtRest(void *arg) { memcpy(arg, kbd_usr, sizeof(kbd_usr)); }

static void loadStateFileAtRest(void *arg) {
  (void)arg;
  fnLoad(LM_STATE_LOAD);
}

static void relaunchExpectingTheUserKeys(void) {
  startCalculator();
  char path[1100];
  snprintf(path, sizeof(path), "%s/userKeys.bin", workDir);
  static char expected[sizeof(kbd_usr)];
  static char restored[sizeof(kbd_usr)];
  FILE *file = fopen(path, "rb");
  CHECK(file != NULL && fread(expected, 1, sizeof(expected), file) == sizeof(expected),
        "no keys to compare");
  if (file) {
    fclose(file);
  }
  r47_run_at_rest(readUserKeysAtRest, restored);
  CHECK(memcmp(expected, restored, sizeof(expected)) == 0, "the user keys changed");
}

// Upstream's xfnToR47user.s47 assigns user keys through LOADST. They survive a save
// and a relaunch whole.
static void caseUserKeysSurviveASave(void) {
  startCalculator();
  static char before[sizeof(kbd_usr)];
  static char loaded[sizeof(kbd_usr)];
  r47_run_at_rest(readUserKeysAtRest, before);
  char from[1100];
  char to[1100];
  snprintf(from, sizeof(from), "%s/../STATE/xfnToR47user.s47", programDir);
  snprintf(to, sizeof(to), "%s/default.dat", workDir);
  copyFile(from, to);
  r47_run_at_rest(loadStateFileAtRest, NULL);
  r47_run_at_rest(readUserKeysAtRest, loaded);
  CHECK(memcmp(before, loaded, sizeof(before)) != 0, "LOADST changed no user key");
  snprintf(to, sizeof(to), "%s/userKeys.bin", workDir);
  writeBytes(to, loaded, sizeof(loaded));
  saveOnceIdle();
  execSelfInChild("--relaunch-expect-user-keys");
}

static bool mpzIs(mpz_srcptr value, const char *expected) {
  mpz_t want;
  mpz_init_set_str(want, expected, 10);
  const bool same = mpz_cmp(value, want) == 0;
  mpz_clear(want);
  return same;
}

// The GMP names gmp_names.h maps onto mini-gmp, held to what GMP gives on boundary
// values, computed apart in Python. A wrong mpz_fits_uint_p once gave a wrong FACTORS
// answer, and backtrace_symbols in execinfo.h was once a crash: a build cannot see a
// stand-in that differs from the header upstream was written for.
static void caseTheStandInsAgreeWithGmp(void) {
  static const struct {
    const char *value;
    int fits;
  } uintCases[] = {{"0", 1}, {"4294967295", 1}, {"4294967296", 0}, {"-1", 0},
                   {"18446744073709551616", 0}};
  for (size_t i = 0; i < sizeof(uintCases) / sizeof(uintCases[0]); i++) {
    mpz_t v;
    mpz_init_set_str(v, uintCases[i].value, 10);
    CHECK(mpz_fits_uint_p(v) == uintCases[i].fits, "mpz_fits_uint_p(%s)", uintCases[i].value);
    mpz_clear(v);
  }

  static const struct {
    const char *from;
    const char *next;
  } primeCases[] = {{"0", "2"},
                    {"1", "2"},
                    {"2", "3"},
                    {"4294967291", "4294967311"},
                    {"18446744073709551557", "18446744073709551629"},
                    {"1000000000000000000000000000000", "1000000000000000000000000000057"}};
  for (size_t i = 0; i < sizeof(primeCases) / sizeof(primeCases[0]); i++) {
    mpz_t from, next;
    mpz_init_set_str(from, primeCases[i].from, 10);
    mpz_init(next);
    mpz_nextprime(next, from);
    CHECK(mpzIs(next, primeCases[i].next), "mpz_nextprime(%s)", primeCases[i].from);
    mpz_clear(from);
    mpz_clear(next);
  }

  static const struct {
    unsigned long n;
    const char *fib;
  } fibCases[] = {{0, "0"},
                  {1, "1"},
                  {2, "1"},
                  {93, "12200160415121876738"},
                  {94, "19740274219868223167"},
                  {300, "222232244629420445529739893461909967206666939096499764990979600"}};
  for (size_t i = 0; i < sizeof(fibCases) / sizeof(fibCases[0]); i++) {
    mpz_t fib;
    mpz_init(fib);
    mpz_fib_ui(fib, fibCases[i].n);
    CHECK(mpzIs(fib, fibCases[i].fib), "mpz_fib_ui(%lu)", fibCases[i].n);
    mpz_clear(fib);
  }

  mpz_t half;
  mpz_init_set_si(half, -5);
  mpz_div_2exp(half, half, 1); // GMP's obsolete name rounds toward minus infinity
  CHECK(mpz_get_si(half) == -3, "mpz_div_2exp(-5, 1) is %ld", mpz_get_si(half));
  mpz_clear(half);

  // upstream's freeList.c prints strs[1] and frees the array, whatever the frame count
  void *frames[4];
  char **names = backtrace_symbols(frames, backtrace(frames, 4));
  CHECK(names != NULL, "backtrace_symbols gave no array");
  if (names != NULL) {
    // The read is the check: AddressSanitizer stops on an array of fewer than two.
    const char *volatile second = names[1];
    (void)second;
    free(names);
  }
}

typedef struct {
  char x[64];
  bool xError;
  char typed[64];
  bool typedError;
  char error[96];
  bool errorError;
  size_t cutLength;
} displayTexts_t;

static void keepDisplayText(char *out, size_t size, bool *outError, bool isError, char *text) {
  snprintf(out, size, "%s", text != NULL ? text : "(null)");
  *outError = isError;
  free(text);
}

static void readDisplayTextsAtRest(void *arg) {
  displayTexts_t *d = arg;
  bool isError = true;
  convertDoubleToReal34Register(15.0, REGISTER_X);
  char *text = r47_display_text(&isError);
  keepDisplayText(d->x, sizeof(d->x), &d->xError, isError, text);

  const uint8_t savedMode = calcMode;
  calcMode = CM_NIM;
  strcpy(aimBuffer, "12");
  isError = true;
  text = r47_display_text(&isError);
  keepDisplayText(d->typed, sizeof(d->typed), &d->typedError, isError, text);
  aimBuffer[0] = 0;
  calcMode = savedMode;

  lastErrorCode = ERROR_ARG_EXCEEDS_FUNCTION_DOMAIN;
  isError = false;
  text = r47_display_text(&isError);
  keepDisplayText(d->error, sizeof(d->error), &d->errorError, isError, text);
  lastErrorCode = 0;

  static char longText[R47_DISPLAY_TEXT_MAX + 8];
  memset(longText, 'a', R47_DISPLAY_TEXT_MAX - 1);
  longText[R47_DISPLAY_TEXT_MAX - 1] = 0;
  calcMode = CM_AIM;
  strcpy(aimBuffer, longText);
  strcat(aimBuffer, STD_SIGMA STD_SIGMA);
  text = r47_display_text(&isError);
  d->cutLength = text != NULL ? strlen(text) : 0;
  free(text);
  aimBuffer[0] = 0;
  calcMode = savedMode;
}

// What a screen reader hears of the display: X as Copy X gives it, the number being
// typed, or the error shown, marked as one; a text past the cap is cut at a
// character's first byte.
static void caseDisplayTextIsWhatTheScreenShows(void) {
  startCalculator();
  displayTexts_t d = {0};
  r47_run_at_rest(readDisplayTextsAtRest, &d);
  CHECK(strcmp(d.x, "15.") == 0 && !d.xError, "X 15 read '%s', error %d", d.x, d.xError);
  CHECK(strcmp(d.typed, "12") == 0 && !d.typedError, "the number typed read '%s', error %d",
        d.typed, d.typedError);
  CHECK(strcmp(d.error, "An argument exceeds the function domain") == 0 && d.errorError,
        "the error shown read '%s', error %d", d.error, d.errorError);
  CHECK(d.cutLength == R47_DISPLAY_TEXT_MAX - 1, "a long text was cut to %zu bytes, want %d",
        d.cutLength, R47_DISPLAY_TEXT_MAX - 1);
}

// A PC softkey pressed during a computation and let go while it still runs: the
// computation's next poll waits for that release, which comes as a host event the
// wait itself holds back, so only the release's arrival can end the wait. Escape
// then stops the computation.
static void casePcSoftkeyReleasedDuringAComputationEndsItsWait(void) {
  startCalculator();
  startSlowFactors();
  r47_key_push_pc(PC_F1, 0, true);
  sleepMs(300);
  r47_key_push_pc(PC_F1, 0, false);
  pcTap(PC_ESCAPE);
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  for (int i = 0; i < 300 && !atomic_load(&probeRan); i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&probeRan), "the calculator still waits for a PC softkey's release");
}

// The wait for that release is the softkey's own: another PC key let go meanwhile, a
// Shift, does not end it, and Escape, queued behind it, stops the computation only
// once F1 comes up.
static void casePcSoftkeyWaitHoldsThroughAnotherKeysRelease(void) {
  startCalculator();
  startSlowFactors();
  r47_key_push_pc(PC_F1, 0, true);
  sleepMs(300);
  r47_key_push_pc(PC_SHIFT_L, 0, true);
  r47_key_push_pc(PC_SHIFT_L, PC_SHIFT_MASK, false);
  pcTap(PC_ESCAPE);
  atomic_store(&probeRan, false);
  r47_run_at_rest_async(markProbeRan, NULL);
  sleepMs(500);
  CHECK(!atomic_load(&probeRan), "another PC key's release ended the softkey's wait");
  r47_key_push_pc(PC_F1, 0, false);
  for (int i = 0; i < 300 && !atomic_load(&probeRan); i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&probeRan), "the softkey's own release did not end its wait");
}

// A save at a poll holds the state mid-run. With the host still stopped, the state the
// run ends in is saved too, once the calculator is at rest, or a kill restores the
// state from before the end.
static void caseStoppedHostSavesTheRunsEndAtRest(void) {
  startCalculator();
  startSlowFactors();
  r47_service_save_and_wait(0);
  r47_keys_on_host_stopped(true);
  for (int i = 0; i < 200 && backupTime() == 0; i++) {
    sleepMs(10);
  }
  CHECK(backupTime() != 0, "a stopped host's save waited for the computation");
  // The file is in place before the poll save syncs its directory: wait for that sync.
  int afterPollSave = atomic_load(&fsyncCount);
  for (int i = 0; i < 50; i++) {
    sleepMs(20);
    const int now = atomic_load(&fsyncCount);
    if (now == afterPollSave) {
      break;
    }
    afterPollSave = now;
  }
  tap(KEY_EXIT);
  for (int i = 0; i < 300 && atomic_load(&fsyncCount) == afterPollSave; i++) {
    sleepMs(10);
  }
  CHECK(atomic_load(&fsyncCount) > afterPollSave,
        "the run's end was not saved while the host stayed stopped");
  r47_keys_on_host_stopped(false);
}

// The host can start after a sleep read that it was stopped and before it locked to wait:
// that sleep must still wait for its timers, or the clock and the key timers stall until
// the next key.
static void caseHostStartInsideASleepKeepsTheTimers(void) {
  startCalculator();
  r47_run_at_rest(armSpareTimerAtRest, NULL);
  r47_keys_on_host_stopped(true);
  sleepMs(200);
  atomic_store(&timedWaits, 0);
  atomic_store(&startHostInsideSleep, true);
  r47_keys_wake();
  sleepMs(300);
  CHECK(!atomic_load(&startHostInsideSleep), "no sleep at rest ran, so this proves nothing");
  CHECK(atomic_load(&timedWaits) > 0, "the host started inside a sleep, which then waited with no deadline");
}

static const testCase_t cases[] = {
    {"a core call off its thread aborts", caseACoreCallOffItsThreadAborts},
    {"the stand-ins agree with GMP", caseTheStandInsAgreeWithGmp},
    {"an exit aborts with a log", caseAnExitAborts},
    {"a cold start keeps the bounds and X", caseAColdStartKeepsTheBoundsAndX},
    {"a file that crashed two starts is set aside", caseAFileThatCrashedTwoStartsIsSetAside},
    {"DELBkup removes both saves", caseDeleteBackupRemovesBothSaves},
    {"a cut screen capture is not offered", caseACutScreenCaptureIsNotOffered},
    {"a save keeps the stopwatch time", caseASaveKeepsTheStopwatchTime},
    {"released state files restore", caseReleasedStateFilesRestore},
    {"user keys survive a save", caseUserKeysSurviveASave},
    {"key goes through the buffer", caseKeyGoesThroughTheBuffer},
    {"keys keep their order", caseKeysKeepTheirOrder},
    {"key sequences give what the keys say", caseKeySequencesGiveWhatTheKeysSay},
    {"a PC key runs in order with the keys", casePcKeyRunsInOrderWithTheKeys},
    {"a PC period is the decimal point", casePcPeriodIsTheDecimalPoint},
    {"a lone PC Shift is f and a lone Ctrl is g", casePcLoneShiftAndCtrlAreTheShifts},
    {"a canceled PC Shift or Ctrl shifts nothing", casePcCanceledShiftOrCtrlShiftsNothing},
    {"a PC letter in hex entry is a digit", casePcLetterInHexEntryIsADigit},
    {"a PC letter runs its item in USER mode", casePcLetterRunsItsItemInUserMode},
    {"a PC shift then a letter does nothing", casePcShiftThenLetterDoesNothing},
    {"the PC f letter shifts on every layout", casePcFLetterShiftsOnEveryLayout},
    {"PC letters in AIM type what they say", casePcLettersInAimTypeWhatTheySay},
    {"a PC letter after STO names the register", casePcLetterAfterStoNamesTheRegister},
    {"PC characters beyond ASCII type", casePcCharactersBeyondAsciiType},
    {"a PC key during a computation only pushes keys", casePcKeyDuringAComputationOnlyPushesKeys},
    {"a PC softkey released at rest leaves no key down",
     casePcSoftkeyReleasedAtRestLeavesNoKeyDown},
    {"a PC softkey released during a computation ends its wait",
     casePcSoftkeyReleasedDuringAComputationEndsItsWait},
    {"a PC softkey's wait holds through another key's release",
     casePcSoftkeyWaitHoldsThroughAnotherKeysRelease},
    {"AIM faces follow NUM", caseAimFacesFollowNumLock},
    {"the VOL softkeys show no value", caseVolumeSoftkeysShowNoValue},
    {"the display text is what the screen shows", caseDisplayTextIsWhatTheScreenShows},
    {"a service runs behind earlier keys", caseServiceRunsBehindEarlierKeys},
    {"FACTORS gives the right factors", caseFactorsAreRight},
    {"digit during a computation does nothing",
     caseDigitDuringAComputationDoesNothing},
    {"EXIT stops a computation", caseExitStopsAComputation},
    {"R/S stops a computation and runs nothing",
     caseRunStopStopsAComputationAndRunsNothing},
    {"R/S stops a loop that never yields", caseRunStopStopsALoopThatNeverYields},
    {"EXIT stops a loop that never yields", caseExitStopsALoopThatNeverYields},
    {"a tone takes its time", caseToneTakesItsTime},
    {"cancel takes back a key", caseCancelTakesBackAKey},
    {"cancel of a single step leaves no run state",
     caseCancelOfASingleStepLeavesNoRunState},
    {"cancel does not assign", caseCancelDoesNotAssign},
    {"OFF saves and keeps the calculator alive",
     caseOffSavesAndKeepsTheCalculatorAlive},
    {"a save at rest finishes inside the fence", caseSaveAtRestFinishesInsideTheFence},
    {"a save waits for a busy calculator", caseSaveWaitsForACalculatorThatIsBusy},
    {"a stopped host saves inside a computation",
     caseStoppedHostSavesInsideAComputation},
    {"a stopped host saves the run's end at rest", caseStoppedHostSavesTheRunsEndAtRest},
    {"a stopped host saves inside a running program",
     caseStoppedHostSavesInsideARunningProgram},
    {"a stopped host's save waits for an open file",
     caseStoppedHostSaveWaitsForAnOpenFile},
    {"a paste survives a computation", casePasteSurvivesAComputation},
    {"the fence does not wait for a busy calculator",
     caseFenceDoesNotWaitForABusyCalculator},
    {"an idle checkpoint keeps typed work", caseIdleCheckpointKeepsTypedWork},
    {"the checkpoint retries a save the disk refused", caseCheckpointRetriesAFailedSave},
    {"the clock starts with the calculator", caseTheClockStartsWithTheCalculator},
    {"a checkpoint runs across the clock's wrap", caseACheckpointRunsAcrossTheClocksWrap},
    {"services add no LCD refresh", caseServicesAddNoLcdRefresh},
    {"descriptions leave the queue to a copy", caseDescriptionsLeaveTheQueueToACopy},
    {"no checkpoint with autosave off", caseNoCheckpointWithAutosaveOff},
    {"a save runs inside a pause", caseSaveRunsInsideAPause},
    {"a kill during saves never cuts the file", caseKillDuringSavesNeverCutsTheFile},
    {"a failed save keeps the file and the process",
     caseFailedSaveKeepsTheFileAndTheProcess},
    {"a save keeps the running calculator's state",
     caseSaveKeepsTheRunningCalculatorsState},
    {"a stop key survives the buffer wipe", caseStopKeySurvivesTheBufferWipe},
    {"a file with a run state restores to rest", caseFileWithARunStateRestoresToRest},
    {"the keypad publishes only what changed", caseKeypadPublishesOnlyWhatChanged},
    {"a stopped host parks the thread", caseStoppedHostParksTheThread},
    {"stop ends the thread during a computation",
     caseStopEndsTheThreadDuringAComputation},
    {"a bad free report does not crash", caseBadFreeReportDoesNotCrash},
    {"a state file the core cannot take is kept aside", caseUnusableStateFileIsKeptAside},
    {"R/S stops a PSE loop at one press in a pause", casePauseLoopStopsOnRunStop},
    {"EXIT stops a PSE loop at one press in a pause", casePauseLoopStopsOnExit},
    {"R/S stops a KEY? loop at one press", caseKeyTestLoopStopsOnRunStop},
    {"EXIT stops a KEY? loop at one press", caseKeyTestLoopStopsOnExit},
    {"AUTXEQ runs the program on relaunch", caseAutoExecuteRunsOnRelaunch},
    {"a wrong program file gives the whole warning",
     caseWrongProgramFileGivesTheWholeWarning},
    {"queued tones end with the run", caseQueuedTonesEndWithTheRun},
    {"R/S stops a VIEW loop at one press", caseViewLoopStopsOnRunStop},
    {"EXIT stops a VIEW loop at one press", caseViewLoopStopsOnExit},
    {"one press in a pause stops a VIEW loop with a PSE",
     caseViewLoopWithAPauseStopsOnOnePress},
    {"a double tap leaves the program stoppable", caseDoubleTapLeavesTheProgramStoppable},
    {"KEY? sees a key behind a VIEW", caseKeyTestSeesAKeyBehindAView},
    {"stop ends the thread in a VIEW loop", caseStopEndsTheThreadInAViewLoop},
    {"a save waits out a VIEW loop", caseSaveWaitsOutAViewLoopAtItsNextRest},
    {"a quit survives a redraw and the wipe", caseQuitSurvivesARedrawAndTheWipe},
    {"a held key holds the program until its release",
     caseHeldKeyHoldsTheProgramUntilItsRelease},
    {"a cancel ends a hold", caseCancelEndsAHold},
    {"a stopping host ends a hold", caseStoppingHostEndsAHold},
    {"parking stops a running timer", caseParkingStopsARunningTimer},
    {"a host start inside a sleep keeps the timers", caseHostStartInsideASleepKeepsTheTimers},
    {"a save runs inside a timed pause", caseSaveRunsInsideATimedPause},
    {"no save in a pause inside a solver", caseNoSaveInAPauseInsideASolver},
    {"a save keeps a question on screen", caseSaveKeepsAQuestionOnScreen},
    {"a short write keeps the file", caseShortWriteKeepsTheFile},
    {"a save reaches the disk", caseSaveReachesTheDisk},
    {"a slow save still answers inside the fence",
     caseSlowSaveStillAnswersInsideTheFence},
    {"a checkpoint waits for a quiet moment", caseCheckpointWaitsForAQuietMoment},
    {"OFF leaves the file with autosave off", caseOffLeavesTheFileWithAutosaveOff},
    {"a cancel of a softkey does not assign", caseCancelOfASoftkeyDoesNotAssign},
    {"a refused keypad mode is asked for again", caseRefusedKeypadModeIsAskedForAgain},
};

// A coverage build has gcov's counters; _exit would skip the handler that writes them.
extern void __gcov_dump(void) __attribute__((weak));

static int finishCase(void) {
  fflush(NULL);
  if (__gcov_dump != NULL) {
    __gcov_dump();
  }
  _exit(failures == 0 ? CASE_PASSED : 1);
}

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <work dir> <program dir> [case]\n", argv[0]);
    return 2;
  }
  programDir = argv[2];
  const char *only = argc > 3 ? argv[3] : NULL;

  if (only != NULL && strcmp(only, "--save-forever") == 0) {
    workDir = argv[1];
    if (freopen("/dev/null", "w", stdout) == NULL) {
      return 2;
    }
    alarm(120);
    saveForever();
  }

  if (only != NULL && strcmp(only, "--relaunch-after-run-state") == 0) {
    workDir = argv[1];
    if (freopen("/dev/null", "w", stdout) == NULL) {
      return 2;
    }
    alarm(120);
    relaunchAfterRunState();
    finishCase();
  }

  const struct {
    const char *mode;
    void (*run)(void);
  } relaunches[] = {
      {"--relaunch-expect-fresh", relaunchExpectingAFreshStart},
      {"--relaunch-expect-saved-stack", relaunchExpectingTheSavedStack},
      {"--relaunch-expect-either", relaunchExpectingEither},
      {"--relaunch-expect-bounds-and-matrix", relaunchExpectingBoundsAndMatrix},
      {"--relaunch-expect-marker-gone", relaunchExpectingTheMarkerGone},
      {"--relaunch-expect-stopped-stopwatch", relaunchExpectingAStoppedStopwatch},
      {"--relaunch-expect-released-state", relaunchExpectingTheReleasedState},
      {"--relaunch-save-and-stop", relaunchSavingAndStopping},
      {"--relaunch-expect-user-keys", relaunchExpectingTheUserKeys},
  };
  for (size_t i = 0; only != NULL && i < sizeof(relaunches) / sizeof(relaunches[0]); i++) {
    if (strcmp(only, relaunches[i].mode) == 0) {
      workDir = argv[1];
      if (freopen("/dev/null", "w", stdout) == NULL) {
        return 2;
      }
      alarm(120);
      relaunches[i].run();
      finishCase();
    }
  }

  if (only != NULL && strcmp(only, "--relaunch-with-autxeq") == 0) {
    workDir = argv[1];
    if (freopen("/dev/null", "w", stdout) == NULL) {
      return 2;
    }
    alarm(120);
    relaunchWithAutoExecute();
    finishCase();
  }

  int failed = 0;
  const int count = (int)(sizeof(cases) / sizeof(cases[0]));
  for (int i = 0; i < count; i++) {
    if (only != NULL && strstr(cases[i].name, only) == NULL) {
      continue;
    }
    char dir[1024];
    snprintf(dir, sizeof(dir), "%s/case%02d", argv[1], i);
    mkdir(argv[1], 0700);
    mkdir(dir, 0700);
    workDir = dir;

    fflush(NULL);
    const pid_t child = fork();
    if (child == 0) {
      if (freopen("/dev/null", "w", stdout) == NULL) {
        _exit(2);
      }
      alarm(120);
      cases[i].run();
      finishCase();
    }
    int status = 0;
    waitpid(child, &status, 0);
    const bool passed = WIFEXITED(status) && WEXITSTATUS(status) == CASE_PASSED;
    dprintf(1, "%s %s", passed ? "ok  " : "FAIL", cases[i].name);
    if (WIFSIGNALED(status)) {
      dprintf(1, " (signal %d)", WTERMSIG(status));
    }
    dprintf(1, "\n");
    failed += passed ? 0 : 1;
  }
  dprintf(1, "%d of %d failed\n", failed, count);
  return failed == 0 ? 0 : 1;
}
