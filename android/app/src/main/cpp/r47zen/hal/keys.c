// The DMCP key buffer, sleep, delay, and timers on Android, the counterpart of
// upstream's src/c47-gtk/hal/keys.c. The calculator code runs on one thread of
// its own, as it does on the hardware. Every other thread only pushes here, and
// the calculator thread takes each key in the main loop or in a long operation,
// and waits in sys_sleep for a key, a timer, a service request, a change of host
// visibility, the minute, or a due checkpoint.
#include "c47.h"
#include "host_services.h"
#include "jni_bridge.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define KEY_BUFFER_SIZE 64
#define HOST_EVENT_SLOTS 100
#define SYS_TIMERS 4

typedef enum {
  hostPcKeyPress,
  hostPcKeyRelease,
  hostPcKeyCancel,
  hostShiftF,
  hostShiftG,
  hostCancelPress,
  hostQuit
} hostEventKind_t;

typedef struct {
  hostEventKind_t kind;
  uint32_t keyval; // a PC key's keysym
  uint32_t state;  // and its modifiers
} hostEvent_t;

static pthread_mutex_t keyMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t keyCond; // signaled on every push and service request
// Other threads broadcast keyCond from the first onStart or key, which can come
// before the calculator thread runs r47_keys_init, so every use goes through
// pthread_once: POSIX leaves an init that races a use undefined.
static pthread_once_t keyCondOnce = PTHREAD_ONCE_INIT;
static int keyBuffer[KEY_BUFFER_SIZE];
static int keyCount = 0;
static hostEvent_t hostEvents[HOST_EVENT_SLOTS];
static int nextHostEvent = 0;
static bool pushedKeyDown = false; // a press was pushed and its release not yet
static bool pcPressPushed = false; // that press came from a PC key while busy
static uint32_t pcPressKeysym = 0; // and that key's keysym
// The calculator thread only: the keysym of the PC event pc_keyboard.c is handling.
static uint32_t handledPcKeysym = 0;
#if defined(HOST_TOOL_BUILD)
static void callPollHook(void);
#endif                            // HOST_TOOL_BUILD
static bool keysFromHost = false; // false: a script pushes, then dispatches
static bool quitPending = false;
static bool quitInRing =
    false;                // the main loop's pop, or a redraw's, took the quit
static int insertAt = -1; // while the calculator thread adds keys at the head
static pthread_t insertThread;
static int64_t timerDeadlineUs[SYS_TIMERS]; // monotonic, 0 while not running
static int64_t lastMinute = 0;
static bool hostStopped =
    false; // under keyMutex: a sleep decides its wait from it
static _Atomic bool calculatorIdle = false;
// When the calculator thread last answered the host, by a poll, a sleep, or the end of
// a wait for it, and whether it waits for the host now: a sleep, a key's release.
static _Atomic int64_t lastAnswerUs = 0;
static _Atomic bool waitingForHost = false;
static void answered(void);
static int64_t processStartUs = 0;
uint32_t calc_state = 0;

static int64_t monotonicUs(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static int64_t realUs(void) {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

#if defined(R47_THREAD_CHECK)
static pthread_t calculatorThread;
static _Atomic bool calculatorThreadKnown = false;

void r47_assert_calculator_thread(const char *caller) {
  if (atomic_load_explicit(&calculatorThreadKnown, memory_order_acquire) &&
      !pthread_equal(pthread_self(), calculatorThread)) {
    LOGE("%s ran off the calculator thread", caller);
    abort();
  }
}
#endif // R47_THREAD_CHECK

#if defined(HOST_TOOL_BUILD)
void r47_keys_claim_calculator_thread(void) {
#if defined(R47_THREAD_CHECK)
  calculatorThread = pthread_self();
  atomic_store_explicit(&calculatorThreadKnown, true, memory_order_release);
#endif // R47_THREAD_CHECK
}
#endif // HOST_TOOL_BUILD

static void initKeyCond(void) {
  pthread_condattr_t attr;
  pthread_condattr_init(&attr);
  pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
  pthread_cond_init(&keyCond, &attr);
  pthread_condattr_destroy(&attr);
}

// Any thread, under keyMutex. The calculator thread's waits come after its
// r47_keys_init, so only a broadcast can be the first use.
static void broadcastKeysLocked(void) {
  pthread_once(&keyCondOnce, initKeyCond);
  pthread_cond_broadcast(&keyCond);
}

// The calculator thread, before it runs anything else.
void r47_keys_init(void) {
#if defined(R47_THREAD_CHECK)
  calculatorThread = pthread_self();
  atomic_store_explicit(&calculatorThreadKnown, true, memory_order_release);
#endif // R47_THREAD_CHECK
  processStartUs = monotonicUs();
  answered();

  pthread_once(&keyCondOnce, initKeyCond);
}

void r47_keys_from_host(bool fromHost) {
  pthread_mutex_lock(&keyMutex);
  keysFromHost = fromHost;
  pthread_mutex_unlock(&keyMutex);
}

static void pushLocked(int key) {
  if (keyCount ==
      KEY_BUFFER_SIZE) { // a full buffer drops the key, as DMCP does
    return;
  }
  if (insertAt >= 0 && pthread_equal(pthread_self(), insertThread)) {
    memmove(&keyBuffer[insertAt + 1], &keyBuffer[insertAt],
            (size_t)(keyCount - insertAt) * sizeof(int));
    keyBuffer[insertAt++] = key;
  } else {
    keyBuffer[keyCount] = key;
  }
  keyCount++;
  broadcastKeysLocked();
}

// A full buffer drops the event before it takes a slot, so a slot the buffer still
// holds is never written over.
static void pushHostLocked(hostEventKind_t kind, uint32_t keyval,
                           uint32_t state) {
  if (keyCount == KEY_BUFFER_SIZE) {
    return;
  }
  hostEvents[nextHostEvent] = (hostEvent_t){kind, keyval, state};
  pushLocked(KEY_HOST_EVENT_FIRST + nextHostEvent);
  nextHostEvent = (nextHostEvent + 1) % HOST_EVENT_SLOTS;
}

// While the calculator thread takes a host event during a long operation, the keys
// it gives go at the head of the buffer, ahead of what other threads push.
static void beginInsertAtHeadLocked(void) {
  insertAt = 0;
  insertThread = pthread_self();
}

static int popLocked(void) {
  if (keyCount == 0) {
    return -1;
  }
  const int key = keyBuffer[0];
  memmove(&keyBuffer[0], &keyBuffer[1], (size_t)(keyCount - 1) * sizeof(int));
  keyCount--;
  return key;
}

bool_t keyIsHostEvent(int key) {
  return key >= KEY_HOST_EVENT_FIRST &&
         key < KEY_HOST_EVENT_FIRST + HOST_EVENT_SLOTS;
}

static hostEvent_t hostEventOf(int key) {
  pthread_mutex_lock(&keyMutex);
  const hostEvent_t event = hostEvents[key - KEY_HOST_EVENT_FIRST];
  pthread_mutex_unlock(&keyMutex);
  return event;
}

// Any thread. A press is 1 to 43, a release is 0.
void r47_key_push(int code) {
  pthread_mutex_lock(&keyMutex);
  pushLocked(code);
  pushedKeyDown = code > 0;
  pcPressPushed = false;
  pthread_mutex_unlock(&keyMutex);
}

static void pushPcLocked(hostEventKind_t kind, uint32_t keyval,
                         uint32_t state) {
  pushHostLocked(kind, keyval, state);
  // A press a busy calculator took from a PC key is waited for in
  // wait_for_key_release, which this release's host event, queued behind the wait,
  // cannot end, so its arrival does: that key's own, not another one let go meanwhile.
  // Upstream's keyPushPcEvent leaves the flag set.
  if (kind != hostPcKeyPress && pcPressPushed && keyval == pcPressKeysym) {
    pushedKeyDown = false;
    pcPressPushed = false;
  }
}

// Any thread. A PC key's press or release, as upstream's keyPushPcEvent sends it:
// one host event, which pc_keyboard.c handles when the calculator thread pops it.
void r47_key_push_pc(uint32_t keyval, uint32_t state, bool press) {
  pthread_mutex_lock(&keyMutex);
  pushPcLocked(press ? hostPcKeyPress : hostPcKeyRelease, keyval, state);
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. A PC key's up the host canceled: a release that toggles no shift.
void r47_key_cancel_pc(uint32_t keyval) {
  pthread_mutex_lock(&keyMutex);
  pushPcLocked(hostPcKeyCancel, keyval, 0);
  pthread_mutex_unlock(&keyMutex);
}

// "00" to "36" is a key and "1" to "6" a softkey, the strings btnPressed and
// btnFnPressed take.
static int keyCodeOf(const char *data) {
  return data[1] == 0 ? data[0] - '1' + 38
                      : (data[0] - '0') * 10 + data[1] - '0' + 1;
}

// The calculator thread, for pc_keyboard.c: upstream's keyPushKey. Button 2 or 3 is
// a click with the f or g shift, which goes ahead of the press as a host event.
void r47_key_push_key(const char *data, bool press, int button) {
  R47_CALCULATOR_THREAD_ONLY();
  pthread_mutex_lock(&keyMutex);
  if (press && button == 2) {
    pushHostLocked(hostShiftF, 0, 0);
  } else if (press && button == 3) {
    pushHostLocked(hostShiftG, 0, 0);
  }
  pushLocked(press ? keyCodeOf(data) : 0);
  pushedKeyDown = press;
  pcPressPushed = press;
  pcPressKeysym = press ? handledPcKeysym : 0;
  pthread_mutex_unlock(&keyMutex);
}

void r47_key_push_click(const char *data, int button) {
  R47_CALCULATOR_THREAD_ONLY();
  r47_key_push_key(data, true, button);
  r47_key_push_key(data, false, button);
}

// Any thread. Takes back a press the host will never release: the core then runs
// the release with nothing to do, in order behind the press.
void r47_key_cancel(void) {
  pthread_mutex_lock(&keyMutex);
  pushHostLocked(hostCancelPress, 0, 0);
  pushedKeyDown = false;
  pcPressPushed = false;
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. A press still down when the host goes away is released here, since
// wait_for_key_release(0) holds the calculator thread until a release arrives.
void r47_key_release_if_down(void) {
  pthread_mutex_lock(&keyMutex);
  if (pushedKeyDown) {
    pushHostLocked(hostCancelPress, 0, 0);
    pushedKeyDown = false;
    pcPressPushed = false;
  }
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. Ends the main loop; an operation in progress sees an EXIT first.
void r47_key_push_quit(void) {
  pthread_mutex_lock(&keyMutex);
  quitPending = true;
  pushHostLocked(hostQuit, 0, 0);
  pthread_mutex_unlock(&keyMutex);
}

static bool isPcEvent(hostEvent_t event) {
  return event.kind == hostPcKeyPress || event.kind == hostPcKeyRelease ||
         event.kind == hostPcKeyCancel;
}

static void handlePcEvent(hostEvent_t event, bool busy) {
  handledPcKeysym = event.keyval;
  if (event.kind == hostPcKeyCancel) {
    r47_pc_key_cancel(event.keyval, busy);
  } else {
    r47_pc_key_handle(event.keyval, event.state, event.kind == hostPcKeyPress,
                      busy);
  }
}

// A host event popped during a long operation. Nothing here may run a calculator
// function, which is what keeps a computation from being re-entered, but upstream's
// one exception: a PC Ctrl command, a copy or a capture, in a program's pause. A
// cancel in this window has no press left to take back, since the operation owns
// the keys now, and a shift is dropped, as upstream drops it. A PC key in text entry
// waits whole in the ring buffer for the main loop; any other gives only the keys
// pc_keyboard.c pushes, at the head of the buffer, as upstream's translateHostEvent
// does.
static void translateHostEvent(int key) {
  const hostEvent_t event = hostEventOf(key);
  if (isPcEvent(event)) {
    if (r47_pc_key_in_text_entry()) {
      inKeyBuffer((uint8_t)key);
      return;
    }
    pthread_mutex_lock(&keyMutex);
    beginInsertAtHeadLocked();
    pthread_mutex_unlock(&keyMutex);
    handlePcEvent(event, true);
    pthread_mutex_lock(&keyMutex);
    insertAt = -1;
    pthread_mutex_unlock(&keyMutex);
    return;
  }
  if (event.kind != hostQuit) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  beginInsertAtHeadLocked();
  pushLocked(33);
  pushLocked(0);
  insertAt = -1;
  pthread_mutex_unlock(&keyMutex);
}

// The calculator thread, from the main loop, behind the press it cancels. A
// function name on show is what the press has left to do, so clearing it is the
// abort upstream's release path already knows. A single step and an ASSIGN act
// on the release without looking at it, so those two are stopped here.
static void cancelPressedKey(void) {
  if (programRunStop == PGM_SINGLE_STEP) {
    programRunStop = PGM_STOPPED;
  }
  // A softkey's press writes "ASSIGN " to tamBuffer, so its test is the key still down.
  const bool assigning = calcMode == CM_ASSIGN && itemToBeAssigned != 0 &&
                         (tamBuffer[0] == 0 || FN_key_pressed != 0);
  showFunctionNameItem = ITM_NOP;
  FN_timed_out_to_NOP_or_Executed = true;
  if (assigning) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  beginInsertAtHeadLocked();
  pushLocked(0);
  insertAt = -1;
  pthread_mutex_unlock(&keyMutex);
}

void keyDispatchHostEvent(int key) {
  R47_CALCULATOR_THREAD_ONLY();
  const hostEvent_t event = hostEventOf(key);
  switch (event.kind) {
  case hostShiftF:
    shiftF = true;
    shiftG = false;
    break;
  case hostShiftG:
    shiftF = false;
    shiftG = true;
    break;
  case hostPcKeyPress:
    handlePcEvent(event, false);
    break;
  case hostPcKeyRelease:
  case hostPcKeyCancel:
    handlePcEvent(event, false);
    // A PC key's press pushed while busy is over once its release comes at rest,
    // where the release runs and pushes nothing, so no wait for a release may hold.
    pthread_mutex_lock(&keyMutex);
    if (pcPressPushed && event.keyval == pcPressKeysym) {
      pushedKeyDown = false;
      pcPressPushed = false;
    }
    pthread_mutex_unlock(&keyMutex);
    break;
  case hostCancelPress:
    cancelPressedKey();
    break;
  case hostQuit:
    pthread_mutex_lock(&keyMutex);
    quitPending = false;
    quitInRing = false;
    pthread_mutex_unlock(&keyMutex);
    fnOff(NOPARAM);
    break;
  }
}

static void saveIfHostStopped(void);

int key_empty(void) {
  R47_CALCULATOR_THREAD_ONLY();
#if defined(R47_TEST_BRIDGE)
  r47_test_bridge_publish_state(); // every poll of a running program asks this
#endif                             // R47_TEST_BRIDGE
#if defined(HOST_TOOL_BUILD)
  callPollHook();
#endif // HOST_TOOL_BUILD
  answered();
  saveIfHostStopped();
  pthread_mutex_lock(&keyMutex);
  // The ring's wipe can lose a quit a redraw moved there, so an operation's poll looks
  // for it here, and key_pop brings it back.
  const bool empty = keyCount == 0 && !quitInRing;
  pthread_mutex_unlock(&keyMutex);
  return empty;
}

int key_push(int k1) {
  r47_key_push(k1);
  return 1;
}

int key_tail(void) {
  R47_CALCULATOR_THREAD_ONLY();
  pthread_mutex_lock(&keyMutex);
  const int key = keyCount == 0 ? -1 : keyBuffer[0];
  pthread_mutex_unlock(&keyMutex);
  return key;
}

// A quit an operation took as an EXIT comes back here once it pops keys again, so
// the ring buffer's refusal of a full buffer or its wipe cannot lose it.
static int popKey(void) {
  pthread_mutex_lock(&keyMutex);
  if (keyCount == 0 && quitPending) {
    pushHostLocked(hostQuit, 0, 0);
    quitInRing = false;
  }
  const int key = popLocked();
  pthread_mutex_unlock(&keyMutex);
  if (key >= 0) {
    r47_keypad_mark_input();
    r47_services_note_input();
  }
  return key;
}

// What the main loop and every redraw move to the ring buffer. A running program polls
// only this buffer, so a stop key or a KEY? answer in the ring would never be seen, and
// the ring's R/S would start the program again after the next stop. So a redraw inside a
// program moves nothing, and the ring holds one key at a time: the second of two quick
// taps stays here, where a program the first one starts can see it.
int keyPopForMainLoop(void) {
  R47_CALCULATOR_THREAD_ONLY();
  if (programRunStop == PGM_RUNNING || !emptyKeyBuffer()) {
    return -1;
  }
  const int key = popKey();
  if (keyIsHostEvent(key) && hostEventOf(key).kind == hostQuit) {
    pthread_mutex_lock(&keyMutex);
    quitInRing = true;
    pthread_mutex_unlock(&keyMutex);
  }
  return key;
}

int key_pop(void) {
  R47_CALCULATOR_THREAD_ONLY();
  for (;;) {
    const int key = popKey();
    if (!keyIsHostEvent(key)) {
      return key;
    }
    translateHostEvent(key);
  }
}

// Upstream calls this after a poll found no stop key, so an EXIT or R/S pushed between
// the poll and this call would be lost: those two survive, with a release behind them.
void key_pop_all(void) {
  R47_CALCULATOR_THREAD_ONLY();
  pthread_mutex_lock(&keyMutex);
  int kept = 0;
  for (int i = 0; i < keyCount; i++) {
    const int key = keyBuffer[i];
    const bool afterStop =
        key == 0 && kept > 0 &&
        (keyBuffer[kept - 1] == 33 || keyBuffer[kept - 1] == 36);
    if (key == 33 || key == 36 || afterStop) {
      keyBuffer[kept++] = key;
    }
  }
  keyCount = kept;
  pthread_mutex_unlock(&keyMutex);
}

bool r47_keys_calculator_idle(void) { return atomic_load(&calculatorIdle); }

static void answered(void) { atomic_store(&lastAnswerUs, monotonicUs()); }

// A program's SOLVE or INT runs its function as a nested engine, which upstream never
// lets poll, so no key stops it: this is how long the calculator has run with no poll.
// A wait for the host, or for a picker, is no silence.
int64_t r47_keys_unanswered_ms(void) {
  const int64_t last = atomic_load(&lastAnswerUs);
  if (last == 0 || atomic_load(&waitingForHost) ||
      atomic_load_explicit(&isCoreBlockingForIo, memory_order_relaxed)) {
    return 0;
  }
  return (monotonicUs() - last) / 1000;
}

// The calculator thread: keys are waiting, in the buffer or the ring.
bool r47_keys_pending(void) { return !key_empty() || !emptyKeyBuffer(); }

#if defined(HOST_TOOL_BUILD)
// A harness presses a key and runs on its own thread what the main loop would run.
void keyDispatchNow(void) {
  uint8_t key;

  for (;;) {
    keyBuffer_pop();
    if (outKeyBuffer(&key) != BUFFER_SUCCESS) {
      return;
    }
    processKeyCode(key);
  }
}
#endif // HOST_TOOL_BUILD

void wait_for_key_release(int tout) {
  R47_CALCULATOR_THREAD_ONLY();
  pthread_mutex_lock(&keyMutex);
  const int64_t deadline = tout > 0 ? monotonicUs() + (int64_t)tout * 1000 : 0;
  atomic_store(&waitingForHost, true);
  while (keysFromHost && pushedKeyDown) {
    if (deadline == 0) {
      pthread_cond_wait(&keyCond, &keyMutex);
    } else {
      struct timespec until = {(time_t)(deadline / 1000000),
                               (long)(deadline % 1000000) * 1000};
      if (pthread_cond_timedwait(&keyCond, &keyMutex, &until) != 0) {
        break;
      }
    }
  }
  atomic_store(&waitingForHost, false);
  answered();
  pthread_mutex_unlock(&keyMutex);
}

// Only the idle main loop and a program's pause sleep on this build, and the pause
// sets PGM_PAUSED first. A keyboard computation never sleeps. So a sleep with no
// program running or paused is the main loop at rest.
// run_upstream_hal_contract.sh fails when upstream adds another caller.
static bool calculatorAtRest(void) {
  return programRunStop != PGM_RUNNING && programRunStop != PGM_PAUSED;
}

void r47_keys_on_host_stopped(bool stopped) {
  pthread_mutex_lock(&keyMutex);
  hostStopped = stopped;
  broadcastKeysLocked();
  pthread_mutex_unlock(&keyMutex);
}

// Any thread: wake the calculator thread for a queued service request.
void r47_keys_wake(void) {
  pthread_mutex_lock(&keyMutex);
  broadcastKeysLocked();
  pthread_mutex_unlock(&keyMutex);
}

// A save the host asked for can run in a program's pause, which is a step boundary.
// Inside a solver, an integral, or a plot the same pause is the middle of a computation.
static bool pauseCanSave(void) {
  return programRunStop == PGM_PAUSED && engineNestingDepth == 0 &&
         !getSystemFlag(FLAG_SOLVING) && !getSystemFlag(FLAG_INTING);
}

static void saveIfInPause(void) {
  if (pauseCanSave()) {
    r47_services_save_pending();
  }
}

// A stopped host can be frozen or killed before the calculator rests again, so a save it
// asked for runs at the next poll of a computation or a running program; with the host
// in front it waits for rest or a pause that can save. The image then holds the run,
// which a restore clears, and the blocks the computation held, which stay allocated
// until RESET. Not while the core has a file open, since the HAL holds one and a save
// opens its own, and not before the start's restore is done.
static bool hostIsStopped(void) {
  pthread_mutex_lock(&keyMutex);
  const bool stopped = hostStopped;
  pthread_mutex_unlock(&keyMutex);
  return stopped;
}

static void saveIfHostStopped(void) {
  if (hostIsStopped() && r47_runtime_ready() && !r47_io_file_open()) {
    r47_services_save_pending();
  }
}

void sys_sleep(void) {
  R47_CALCULATOR_THREAD_ONLY();
#if defined(R47_TEST_BRIDGE)
  r47_test_bridge_publish_state();
#endif // R47_TEST_BRIDGE
#if defined(HOST_TOOL_BUILD)
  callPollHook();
#endif // HOST_TOOL_BUILD
  answered();
  const bool atRest = calculatorAtRest();
  // A request can change what the main loop must do next, such as switch off, so
  // the loop looks again before anything sleeps.
  bool ran = false;
  const bool stoppedBefore = hostIsStopped();
  if (atRest) {
    ran = r47_services_run();
    ran = r47_services_checkpoint(stoppedBefore) || ran;
    r47_keypad_publish_at_rest();
    r47_tones_at_rest();
  } else {
    saveIfInPause();
  }

  pthread_mutex_lock(&keyMutex);
  // Read again under the lock the wait takes: a host started since the read above has
  // broadcast already, and a wait chosen from the stale value would miss its timers.
  const bool stopped = hostStopped;
  if (keyCount == 0 && !quitPending && !ran &&
      !(atRest && r47_services_pending()) &&
      !(pauseCanSave() && r47_services_save_waiting())) {
    const int64_t now = monotonicUs();
    int64_t until = 0;
    // A stopped host needs no timer and no clock: the deadlines count against the
    // clock, so the first wake fires whatever came due meanwhile.
    if (!(atRest && stopped)) {
      for (int i = 0; i < SYS_TIMERS; i++) {
        if (timerDeadlineUs[i] != 0 &&
            (until == 0 || timerDeadlineUs[i] < until)) {
          until = timerDeadlineUs[i];
        }
      }
      if (ST(STAT_CLK_WKUP_ENABLE)) {
        const int64_t real = realUs();
        const int64_t toNextMinute = (real / 60000000 + 1) * 60000000 - real;
        if (until == 0 || now + toNextMinute < until) {
          until = now + toNextMinute;
        }
      }
    }
    if (atRest) {
      const int checkpointMs = r47_services_checkpoint_due_ms(stopped);
      if (checkpointMs >= 0 &&
          (until == 0 || now + (int64_t)checkpointMs * 1000 < until)) {
        until = now + (int64_t)checkpointMs * 1000;
      }
    }
    atomic_store(&calculatorIdle, atRest);
    atomic_store(&waitingForHost, true);
    if (until == 0) {
      pthread_cond_wait(&keyCond, &keyMutex);
    } else if (until > now) {
      struct timespec deadline = {(time_t)(until / 1000000),
                                  (long)(until % 1000000) * 1000};
      pthread_cond_timedwait(&keyCond, &keyMutex, &deadline);
    }
    atomic_store(&waitingForHost, false);
    answered();
    atomic_store(&calculatorIdle, false);
  }

  const int64_t woken = monotonicUs();
  for (int i = 0; i < SYS_TIMERS; i++) {
    if (timerDeadlineUs[i] != 0 && timerDeadlineUs[i] <= woken) {
      timerDeadlineUs[i] = 0;
    }
  }
  pthread_mutex_unlock(&keyMutex);

  if (ST(STAT_CLK_WKUP_ENABLE)) {
    const int64_t minute = realUs() / 60000000;
    if (minute != lastMinute) {
      if (lastMinute != 0) {
        SET_ST(STAT_CLK_WKUP_FLAG);
      }
      lastMinute = minute;
    }
  }
}

void sys_delay(uint32_t ms_delay) {
  R47_CALCULATOR_THREAD_ONLY();
  saveIfInPause();
  struct timespec ts = {(time_t)(ms_delay / 1000),
                        (long)(ms_delay % 1000) * 1000000};
  nanosleep(&ts, NULL);
}

// Counted from this process's start, not from boot. The main loop compares its
// deadlines with a plain <=, as the hardware can, whose clock restarts with the
// calculator. A phone stays up for months, so a boot-based count would wrap into
// a stalled display 49.7 days into an uptime, and this one needs that long a process.
uint32_t sys_current_ms(void) {
  return (uint32_t)((monotonicUs() - processStartUs) / 1000);
}

#if defined(HOST_TOOL_BUILD)
void r47_keys_set_clock_ms(uint32_t nowMs) {
  processStartUs = monotonicUs() - (int64_t)nowMs * 1000;
}

static void (*_Atomic pollHook)(void) = NULL;

void r47_keys_set_poll_hook(void (*hook)(void)) {
  atomic_store(&pollHook, hook);
}

static void callPollHook(void) {
  void (*const hook)(void) = atomic_load(&pollHook);
  if (hook != NULL) {
    hook();
  }
}
#endif // HOST_TOOL_BUILD

void sys_timer_start(int timer_ix, uint32_t ms_value) {
  R47_CALCULATOR_THREAD_ONLY();
  if (timer_ix < 0 || timer_ix >= SYS_TIMERS) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  timerDeadlineUs[timer_ix] = monotonicUs() + (int64_t)ms_value * 1000;
  pthread_mutex_unlock(&keyMutex);
}

void sys_timer_disable(int timer_ix) {
  R47_CALCULATOR_THREAD_ONLY();
  if (timer_ix < 0 || timer_ix >= SYS_TIMERS) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  timerDeadlineUs[timer_ix] = 0;
  pthread_mutex_unlock(&keyMutex);
}

void reset_auto_off(void) {}

int get_vbat(void) { return 3100; }

void rtc_wakeup_delay(void) {}
