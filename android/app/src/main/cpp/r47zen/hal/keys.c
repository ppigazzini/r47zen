// The DMCP key buffer, sleep, delay, and timers on Android, the counterpart of
// upstream's src/c47-gtk/hal/keys.c. The calculator code runs on one thread of
// its own, as it does on the hardware. Every other thread only pushes here, and
// the calculator thread takes each key in the main loop or in a long operation,
// and waits in sys_sleep for a key, a timer, a service request, or the minute.
#include "c47.h"
#include "host_services.h"
#include "jni_bridge.h"

#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <time.h>

#define KEY_BUFFER_SIZE 64
#define HOST_EVENT_SLOTS 100
#define SYS_TIMERS 4

typedef enum {
  hostShiftF,
  hostShiftG,
  hostCancelPress,
  hostQuit
} hostEventKind_t;

static pthread_mutex_t keyMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t keyCond; // signaled on every push and service request
static int keyBuffer[KEY_BUFFER_SIZE];
static int keyCount = 0;
static hostEventKind_t hostEvents[HOST_EVENT_SLOTS];
static int nextHostEvent = 0;
static bool pushedKeyDown = false; // a press was pushed and its release not yet
static bool keysFromHost = false;  // false: a script pushes, then dispatches
static bool quitPending = false;
static bool quitInRing =
    false;                // the main loop's pop, or a redraw's, took the quit
static int insertAt = -1; // while the calculator thread adds keys at the head
static int64_t timerDeadlineUs[SYS_TIMERS]; // monotonic, 0 while not running
static int64_t lastMinute = 0;
static _Atomic bool hostStopped = false;
static _Atomic bool calculatorIdle = false;
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

void r47_keys_init(void) {
  processStartUs = monotonicUs();

  pthread_condattr_t attr;
  pthread_condattr_init(&attr);
  pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
  pthread_cond_init(&keyCond, &attr);
  pthread_condattr_destroy(&attr);
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
  if (insertAt >= 0) {
    memmove(&keyBuffer[insertAt + 1], &keyBuffer[insertAt],
            (size_t)(keyCount - insertAt) * sizeof(int));
    keyBuffer[insertAt++] = key;
  } else {
    keyBuffer[keyCount] = key;
  }
  keyCount++;
  pthread_cond_broadcast(&keyCond);
}

static void pushHostLocked(hostEventKind_t kind) {
  hostEvents[nextHostEvent] = kind;
  pushLocked(KEY_HOST_EVENT_FIRST + nextHostEvent);
  nextHostEvent = (nextHostEvent + 1) % HOST_EVENT_SLOTS;
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

// Any thread. A press is 1 to 43, a release is 0.
void r47_key_push(int code) {
  pthread_mutex_lock(&keyMutex);
  pushLocked(code);
  pushedKeyDown = code > 0;
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. Takes back a press the host will never release: the core then runs
// the release with nothing to do, in order behind the press.
void r47_key_cancel(void) {
  pthread_mutex_lock(&keyMutex);
  pushHostLocked(hostCancelPress);
  pushedKeyDown = false;
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. A press still down when the host goes away is released here, since
// wait_for_key_release(0) holds the calculator thread until a release arrives.
void r47_key_release_if_down(void) {
  pthread_mutex_lock(&keyMutex);
  if (pushedKeyDown) {
    pushHostLocked(hostCancelPress);
    pushedKeyDown = false;
  }
  pthread_mutex_unlock(&keyMutex);
}

// Any thread. Ends the main loop; an operation in progress sees an EXIT first.
void r47_key_push_quit(void) {
  pthread_mutex_lock(&keyMutex);
  quitPending = true;
  pushHostLocked(hostQuit);
  pthread_mutex_unlock(&keyMutex);
}

// A host event popped during a long operation. Nothing here may run core code:
// that is what keeps a computation from being re-entered. A cancel in this window
// has no press left to take back, since the operation owns the keys now.
static void translateHostEvent(int key) {
  if (hostEvents[key - KEY_HOST_EVENT_FIRST] != hostQuit) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  insertAt = 0;
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
  insertAt = 0;
  pushLocked(0);
  insertAt = -1;
  pthread_mutex_unlock(&keyMutex);
}

void keyDispatchHostEvent(int key) {
  switch (hostEvents[key - KEY_HOST_EVENT_FIRST]) {
  case hostShiftF:
    shiftF = true;
    shiftG = false;
    break;
  case hostShiftG:
    shiftF = false;
    shiftG = true;
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

int key_empty(void) {
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
    pushHostLocked(hostQuit);
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
  if (programRunStop == PGM_RUNNING || !emptyKeyBuffer()) {
    return -1;
  }
  const int key = popKey();
  if (keyIsHostEvent(key) &&
      hostEvents[key - KEY_HOST_EVENT_FIRST] == hostQuit) {
    pthread_mutex_lock(&keyMutex);
    quitInRing = true;
    pthread_mutex_unlock(&keyMutex);
  }
  return key;
}

int key_pop(void) {
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
  pthread_mutex_lock(&keyMutex);
  const int64_t deadline = tout > 0 ? monotonicUs() + (int64_t)tout * 1000 : 0;
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
  atomic_store(&hostStopped, stopped);
  pthread_mutex_lock(&keyMutex);
  pthread_cond_broadcast(&keyCond);
  pthread_mutex_unlock(&keyMutex);
}

// Any thread: wake the calculator thread for a queued service request.
void r47_keys_wake(void) {
  pthread_mutex_lock(&keyMutex);
  pthread_cond_broadcast(&keyCond);
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
    r47_services_save_in_pause();
  }
}

void sys_sleep(void) {
  const bool atRest = calculatorAtRest();
  // A request can change what the main loop must do next, such as switch off, so
  // the loop looks again before anything sleeps.
  bool ran = false;
  const bool stopped = atomic_load(&hostStopped);
  if (atRest) {
    ran = r47_services_run();
    ran = r47_services_checkpoint(stopped) || ran;
    r47_keypad_publish_at_rest();
    r47_tones_at_rest();
  } else {
    saveIfInPause();
  }

  pthread_mutex_lock(&keyMutex);
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
    if (until == 0) {
      pthread_cond_wait(&keyCond, &keyMutex);
    } else if (until > now) {
      struct timespec deadline = {(time_t)(until / 1000000),
                                  (long)(until % 1000000) * 1000};
      pthread_cond_timedwait(&keyCond, &keyMutex, &deadline);
    }
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

void sys_timer_start(int timer_ix, uint32_t ms_value) {
  if (timer_ix < 0 || timer_ix >= SYS_TIMERS) {
    return;
  }
  pthread_mutex_lock(&keyMutex);
  timerDeadlineUs[timer_ix] = monotonicUs() + (int64_t)ms_value * 1000;
  pthread_mutex_unlock(&keyMutex);
}

void sys_timer_disable(int timer_ix) {
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
