#include "host_services.h"
#include "jni_bridge.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define SERVICE_QUEUE_SIZE 32
#define R47_SAVE_IDLE_GRACE_MS 50

#if defined(HOST_TOOL_BUILD) || defined(R47_TEST_BRIDGE)
typedef struct {
  void (*fn)(void *);
  void *arg;
  bool wait;
  bool done;
} hostScript_t;
static pthread_mutex_t scriptMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t scriptCond = PTHREAD_COND_INITIALIZER;
#endif // HOST_TOOL_BUILD || R47_TEST_BRIDGE

typedef enum {
  servicePaste,
  serviceGraphReset,
  serviceCopy,
  serviceDescribeDisplay,
  serviceKeypadMode,
  serviceHostScript
} serviceKind_t;

typedef struct {
  serviceKind_t kind;
  int value;
  char *text;
} service_t;

static pthread_mutex_t serviceMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t saveCond;
static pthread_once_t saveCondOnce = PTHREAD_ONCE_INIT;
static service_t queue[SERVICE_QUEUE_SIZE];
static int queueHead = 0;
static int queueCount = 0;
static bool gesturePending = false;
static float gestureDx = 0.0f;
static float gestureDy = 0.0f;
static float gestureScale = 1.0f;
static unsigned long savesRequested = 0;
static unsigned long savesDone = 0;
static unsigned long savedUpTo =
    0; // the last ticket a save the disk took covered
static bool saveInProgress = false;

// An idle checkpoint bounds what a kill can cost, since onStop's save may not run
// before the process dies: the state is saved once the keys have been quiet this long,
// at most this often, and only when a key arrived since the last save.
#define R47_CHECKPOINT_INTERVAL_MS 30000u
#define R47_CHECKPOINT_QUIET_MS 2000u
static uint32_t checkpointIntervalMs = R47_CHECKPOINT_INTERVAL_MS;
static uint32_t checkpointQuietMs = R47_CHECKPOINT_QUIET_MS;
static bool inputSinceSave = false;
static uint32_t lastSaveMs = 0;
// A save at a poll or in a pause holds the state mid-run, so the state the run ends in
// is saved at the next rest, host stopped or not. The calculator thread only.
static bool restSaveOwed = false;
// True while r47_services_run looks for keys at rest: that look polls, and a poll
// takes a stopped host's pending save, which the run's own last step takes at rest.
// The calculator thread only.
static bool keysCheckedAtRest = false;
static uint32_t lastInputMs = 0;
static _Atomic bool autosaveEnabled = true;

static void initSaveCond(void) {
  pthread_condattr_t attr;
  pthread_condattr_init(&attr);
  pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
  pthread_cond_init(&saveCond, &attr);
  pthread_condattr_destroy(&attr);
}

static bool enqueue(serviceKind_t kind, int value, char *text) {
  pthread_mutex_lock(&serviceMutex);
  const bool room = queueCount < SERVICE_QUEUE_SIZE;
  if (room) {
    service_t *slot = &queue[(queueHead + queueCount) % SERVICE_QUEUE_SIZE];
    slot->kind = kind;
    slot->value = value;
    slot->text = text;
    queueCount++;
  }
  pthread_mutex_unlock(&serviceMutex);
  if (room) {
    r47_keys_wake();
  } else {
    LOGE("service queue full: refused kind %d", (int)kind);
  }
  return room;
}

bool r47_service_paste(const char *tokens) {
  char *copy = strdup(tokens);
  if (copy == NULL) {
    return false;
  }
  if (!enqueue(servicePaste, 0, copy)) {
    free(copy);
    return false;
  }
  return true;
}

bool r47_service_graph_reset(void) {
  return enqueue(serviceGraphReset, 0, NULL);
}

bool r47_service_copy(int what) { return enqueue(serviceCopy, what, NULL); }

bool r47_service_describe_display(void) {
  return enqueue(serviceDescribeDisplay, 0, NULL);
}

bool r47_service_keypad_mode(int mode) {
  return enqueue(serviceKeypadMode, mode, NULL);
}

void r47_service_graph_gesture(float dxNorm, float dyNorm, float scale) {
  pthread_mutex_lock(&serviceMutex);
  gesturePending = true;
  gestureDx += dxNorm;
  gestureDy += dyNorm;
  gestureScale *= scale;
  pthread_mutex_unlock(&serviceMutex);
  r47_keys_wake();
}

bool r47_service_save_and_wait(int timeoutMs) {
  pthread_once(&saveCondOnce, initSaveCond);

  struct timespec until;
  clock_gettime(CLOCK_MONOTONIC, &until);
  until.tv_sec += timeoutMs / 1000;
  until.tv_nsec += (long)(timeoutMs % 1000) * 1000000;
  if (until.tv_nsec >= 1000000000) {
    until.tv_sec++;
    until.tv_nsec -= 1000000000;
  }

  pthread_mutex_lock(&serviceMutex);
  const unsigned long ticket = ++savesRequested;
  pthread_mutex_unlock(&serviceMutex);
  r47_keys_wake();

  // A calculator that is busy, or parked at a picker, cannot answer, so waiting out the
  // fence would only stall the caller. A key still finishing settles within the grace,
  // and a calculator already saving is at rest and answers next, however slow the disk.
  const struct timespec graceStep = {0, 1000000};
  int graceMs = R47_SAVE_IDLE_GRACE_MS;
  while (!r47_keys_calculator_idle() && graceMs > 0) {
    pthread_mutex_lock(&serviceMutex);
    const bool finished = savesDone >= ticket;
    const bool saved = savedUpTo >= ticket;
    const bool saving = saveInProgress;
    pthread_mutex_unlock(&serviceMutex);
    if (finished) {
      return saved;
    }
    if (saving) {
      break;
    }
    nanosleep(&graceStep, NULL);
    graceMs--;
  }
  if (graceMs == 0) {
    return false;
  }

  pthread_mutex_lock(&serviceMutex);
  while (savesDone < ticket) {
    if (pthread_cond_timedwait(&saveCond, &serviceMutex, &until) != 0) {
      break;
    }
  }
  const bool saved = savedUpTo >= ticket;
  pthread_mutex_unlock(&serviceMutex);
  return saved;
}

void r47_service_set_autosave(bool enabled) {
  atomic_store(&autosaveEnabled, enabled);
}

bool r47_services_autosave_enabled(void) {
  return atomic_load(&autosaveEnabled);
}

static bool saveMarked(void) {
  pthread_mutex_lock(&serviceMutex);
  saveInProgress = true;
  pthread_mutex_unlock(&serviceMutex);
  const bool saved = r47_save_settled();
  pthread_mutex_lock(&serviceMutex);
  saveInProgress = false;
  pthread_mutex_unlock(&serviceMutex);
  return saved;
}

// A save the disk refused leaves the work unsaved, and the checkpoint tries again a
// whole interval later, so a full disk is not written to on every rest.
void r47_services_note_saved(bool saved) {
  inputSinceSave = !saved;
  lastSaveMs = sys_current_ms();
}

void r47_services_note_input(void) {
  inputSinceSave = true;
  lastInputMs = sys_current_ms();
}

int r47_services_checkpoint_due_ms(bool hostStopped) {
  if (restSaveOwed && atomic_load(&autosaveEnabled)) {
    return 0;
  }
  if (!inputSinceSave || hostStopped || !atomic_load(&autosaveEnabled)) {
    return -1;
  }
  const uint32_t now = sys_current_ms();
  const uint32_t sinceSave = now - lastSaveMs;
  const uint32_t sinceInput = now - lastInputMs;
  const uint32_t untilInterval =
      sinceSave >= checkpointIntervalMs ? 0u : checkpointIntervalMs - sinceSave;
  const uint32_t untilQuiet =
      sinceInput >= checkpointQuietMs ? 0u : checkpointQuietMs - sinceInput;
  return (int)(untilInterval > untilQuiet ? untilInterval : untilQuiet);
}

bool r47_services_checkpoint(bool hostStopped) {
  R47_CALCULATOR_THREAD_ONLY();
  if (r47_services_checkpoint_due_ms(hostStopped) != 0) {
    return false;
  }
  // A save that cannot run now must not leave the checkpoint due, or every sleep
  // would return at once.
  restSaveOwed = false;
  saveMarked();
  return true;
}

// Mark every save asked for so far as done, after one ran, and as saved if the state
// file took it. A later save the disk takes saves what an earlier refused one asked.
static void completeSaves(unsigned long upTo, bool saved) {
  pthread_once(&saveCondOnce, initSaveCond);
  pthread_mutex_lock(&serviceMutex);
  if (upTo > savesDone) {
    savesDone = upTo;
  }
  if (saved && upTo > savedUpTo) {
    savedUpTo = upTo;
  }
  pthread_cond_broadcast(&saveCond);
  pthread_mutex_unlock(&serviceMutex);
}

void r47_services_save_pending(void) {
  R47_CALCULATOR_THREAD_ONLY();
  if (keysCheckedAtRest) {
    return;
  }
  pthread_mutex_lock(&serviceMutex);
  const unsigned long ticket = savesRequested;
  const bool pending = savesDone < ticket && !saveInProgress;
  pthread_mutex_unlock(&serviceMutex);
  if (pending) {
    completeSaves(ticket, saveMarked());
    restSaveOwed = true;
  }
}

void r47_save_before_picker(void) {
  R47_CALCULATOR_THREAD_ONLY();
  if (atomic_load(&autosaveEnabled)) {
    r47_save_settled();
  }
}

bool r47_services_save_waiting(void) {
  pthread_mutex_lock(&serviceMutex);
  const bool waiting = savesDone < savesRequested;
  pthread_mutex_unlock(&serviceMutex);
  return waiting;
}

bool r47_services_pending(void) {
  pthread_mutex_lock(&serviceMutex);
  const bool pending =
      queueCount > 0 || gesturePending || savesDone < savesRequested;
  pthread_mutex_unlock(&serviceMutex);
  return pending;
}

#if defined(HOST_TOOL_BUILD) || defined(R47_TEST_BRIDGE)
static void runHostScript(hostScript_t *script) {
  script->fn(script->arg);
#if defined(R47_TEST_BRIDGE)
  r47_test_bridge_publish_state(); // before the test that waits on it reads it
#endif                             // R47_TEST_BRIDGE
  pthread_mutex_lock(&scriptMutex);
  const bool wait = script->wait;
  script->done = true;
  pthread_cond_broadcast(&scriptCond);
  pthread_mutex_unlock(&scriptMutex);
  if (!wait) {
    free(script);
  }
}

static void queueHostScript(void (*fn)(void *), void *arg, hostScript_t *script,
                            bool wait) {
  script->fn = fn;
  script->arg = arg;
  script->wait = wait;
  script->done = false;
  while (!enqueue(serviceHostScript, 0, (char *)script)) {
    struct timespec ms = {0, 1000000};
    nanosleep(&ms, NULL);
  }
}

void r47_run_at_rest(void (*fn)(void *), void *arg) {
  hostScript_t script;
  queueHostScript(fn, arg, &script, true);
  pthread_mutex_lock(&scriptMutex);
  while (!script.done) {
    pthread_cond_wait(&scriptCond, &scriptMutex);
  }
  pthread_mutex_unlock(&scriptMutex);
}

void r47_run_at_rest_async(void (*fn)(void *), void *arg) {
  hostScript_t *script = malloc(sizeof(hostScript_t));
  queueHostScript(fn, arg, script, false);
}

void r47_services_set_checkpoint_timing(uint32_t intervalMs, uint32_t quietMs) {
  checkpointIntervalMs = intervalMs;
  checkpointQuietMs = quietMs;
}
#endif // HOST_TOOL_BUILD || R47_TEST_BRIDGE

static void runService(const service_t *service) {
  R47_CALCULATOR_THREAD_ONLY();
  switch (service->kind) {
  case servicePaste:
    r47_upcall_paste_result(r47_paste_number(service->text));
    break;
  case serviceGraphReset:
    r47_reset_graph();
    break;
  case serviceCopy: {
    // An empty text tells the host the copy could not be built.
    char *text = r47_clipboard_text(service->value);
    r47_upcall_clipboard_text(service->value, text != NULL ? text : "");
    free(text);
    break;
  }
  case serviceDescribeDisplay: {
    bool isError = false;
    char *text = r47_display_text(&isError);
    r47_upcall_display_text(isError, text != NULL ? text : "");
    free(text);
    break;
  }
  case serviceKeypadMode:
    r47_keypad_set_mode(service->value);
    break;
  case serviceHostScript:
#if defined(HOST_TOOL_BUILD) || defined(R47_TEST_BRIDGE)
    runHostScript((hostScript_t *)service->text);
#endif // HOST_TOOL_BUILD || R47_TEST_BRIDGE
    break;
  }
}

static bool keysPendingAtRest(void) {
  keysCheckedAtRest = true;
  const bool pending = r47_keys_pending();
  keysCheckedAtRest = false;
  return pending;
}

bool r47_services_run(void) {
  R47_CALCULATOR_THREAD_ONLY();
  bool ran = false;

  for (;;) {
    pthread_mutex_lock(&serviceMutex);
    if (queueCount == 0) {
      pthread_mutex_unlock(&serviceMutex);
      break;
    }
    pthread_mutex_unlock(&serviceMutex);
    if (keysPendingAtRest()) {
      return ran;
    }
    pthread_mutex_lock(&serviceMutex);
    const service_t service = queue[queueHead];
    queueHead = (queueHead + 1) % SERVICE_QUEUE_SIZE;
    queueCount--;
    pthread_mutex_unlock(&serviceMutex);

    runService(&service);
    if (service.kind != serviceHostScript) {
      free(service.text);
    }
    ran = true;
  }

  if (keysPendingAtRest()) {
    return ran;
  }

  pthread_mutex_lock(&serviceMutex);
  const bool gesture = gesturePending;
  const float dx = gestureDx;
  const float dy = gestureDy;
  const float scale = gestureScale;
  gesturePending = false;
  gestureDx = 0.0f;
  gestureDy = 0.0f;
  gestureScale = 1.0f;
  const unsigned long saveTicket = savesRequested;
  const bool save = savesDone < saveTicket;
  pthread_mutex_unlock(&serviceMutex);

  if (gesture) {
    r47_apply_graph_gesture(dx, dy, scale);
    ran = true;
  }

  // Last, so that it holds what the requests above changed. A save at rest pays a
  // save a run owed.
  if (save) {
    restSaveOwed = false;
    completeSaves(saveTicket, saveMarked());
    ran = true;
  }

  r47_offer_snap();

  if (ran) {
    refreshLcd();
    lcd_refresh();
    r47_keypad_mark_dirty();
  }
  return ran;
}
