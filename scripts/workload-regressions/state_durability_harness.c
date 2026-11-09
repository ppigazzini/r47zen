// Host harness for the state file's durability on the app's runtime: the staged core
// and the glue, built by build_state_durability_harness.sh under AddressSanitizer and
// UndefinedBehaviorSanitizer, run r47_init_runtime and the background save exactly as
// the app does. A relaunch is a fresh process that restores from the work directory,
// so each case saves, changes the file, and starts the calculator again to see what
// the restore made of it:
//
// - a state file the core cannot take (cut at any point before or inside the memory
//   image, from an older or a newer version, for another RAM size) is kept aside as
//   <file>.v<version>.<time>, the user is warned once, and the calculator starts
//   afresh; a cut at a line's end restores whole or is kept, never garbage;
// - a file whose restore did not finish twice is set aside, under a name of its own
//   each time, and one unfinished restore is not enough;
// - the state files of released cores restore, and so does the file this core saves
//   from them, and every cut of them is restored or kept;
// - a save leaves a running stopwatch running with its fields, and the saved copy
//   holds its time;
// - a cold start leaves LX to UY as stored and keeps a matrix in X that fills memory;
// - user keys loaded with LOADST survive a save;
// - an exit in the core aborts with a log, so Android records a crash.
//
// Each case runs in its own process and passes only with CASE_PASSED as its status:
// saveCalc ends the process with exit(0) on an open error, which must not pass.

#include "jni_bridge.h"
#include "r47_backup_version.h"

#include <dirent.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CASE_PASSED 42

extern void copyRegisterToClipboardString(calcRegister_t, char *, bool_t);
extern pthread_mutex_t screenMutex;
extern void r47_initialize_native_bridge_state(void);
extern void r47_native_preinit_path(const char *path);
extern void r47_init_runtime(void);
extern void r47_save_background_state_locked(void);

static const char *workDir;
static const char *programDir;
static int failures = 0;
static atomic_int warningCount;

#define CHECK(condition, ...)                                                  \
  do {                                                                         \
    if (!(condition)) {                                                        \
      fprintf(stderr, "FAIL: " __VA_ARGS__);                                   \
      fputc('\n', stderr);                                                     \
      failures++;                                                              \
    }                                                                          \
  } while (0)

// The link wraps the glue's warning up-call, so a case can count what the user is told.
void __wrap_r47_show_warning(const char *text) {
  (void)text;
  atomic_fetch_add(&warningCount, 1);
}

static void startCalculator(void) {
  r47_initialize_native_bridge_state();
  r47_native_preinit_path(workDir);
  r47_init_runtime();
}

static void saveOnce(void) {
  pthread_mutex_lock(&screenMutex);
  r47_save_background_state_locked();
  pthread_mutex_unlock(&screenMutex);
}

typedef struct {
  char x[64];
  char y[64];
  char z[64];
  char t[64];
} state_t;

static void registerText(calcRegister_t r, char *out, size_t size) {
  char *wide = malloc(TMP_STR_LENGTH);
  if (getRegisterDataType(r) == dtReal34Matrix) {
    snprintf(out, size, "matrix");
  } else {
    copyRegisterToClipboardString(r, wide, false);
    snprintf(out, size, "%s", wide);
  }
  free(wide);
}

static state_t readState(void) {
  state_t s;
  memset(&s, 0, sizeof(s));
  pthread_mutex_lock(&screenMutex);
  char *x = r47_capture_clipboard_locked(copyRegisterXToClipboard);
  snprintf(s.x, sizeof(s.x), "%.60s", x != NULL ? x : "(no copy)");
  free(x);
  registerText(REGISTER_Y, s.y, sizeof(s.y));
  registerText(REGISTER_Z, s.z, sizeof(s.z));
  registerText(REGISTER_T, s.t, sizeof(s.t));
  pthread_mutex_unlock(&screenMutex);
  return s;
}

static void setStack(double t, double z, double y, double x) {
  pthread_mutex_lock(&screenMutex);
  convertDoubleToReal34Register(t, REGISTER_T);
  convertDoubleToReal34Register(z, REGISTER_Z);
  convertDoubleToReal34Register(y, REGISTER_Y);
  convertDoubleToReal34Register(x, REGISTER_X);
  pthread_mutex_unlock(&screenMutex);
}

static bool fileExists(const char *path) {
  struct stat st;
  return stat(path, &st) == 0;
}

static void writeBytes(const char *path, const char *bytes, size_t length) {
  FILE *file = fopen(path, "wb");
  CHECK(file != NULL && fwrite(bytes, 1, length, file) == length, "cannot write %s", path);
  if (file) {
    fclose(file);
  }
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

static char *readWhole(const char *path, size_t *length) {
  FILE *file = fopen(path, "rb");
  CHECK(file != NULL, "cannot read %s", path);
  if (file == NULL) {
    return NULL;
  }
  fseek(file, 0, SEEK_END);
  *length = (size_t)ftell(file);
  rewind(file);
  char *bytes = malloc(*length + 1);
  CHECK(fread(bytes, 1, *length, file) == *length, "short read of %s", path);
  bytes[*length] = '\0';
  fclose(file);
  return bytes;
}

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

// Remove the copies a refused restore kept aside, and count them.
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

static void writeRestoreMarker(const char *count) {
  char marker[1100];
  snprintf(marker, sizeof(marker), "%s/backupR47.cfg.restoring", workDir);
  writeBytes(marker, count, strlen(count));
}

// A relaunch is a fresh process image: the core keeps its state in globals.
static void execSelfInChild(const char *mode) {
  fflush(NULL);
  const pid_t child = fork();
  if (child != 0) {
    int status = 0;
    waitpid(child, &status, 0);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == CASE_PASSED, "%s: status %x", mode,
          status);
    return;
  }
  char self[1024];
  const ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
  self[length < 0 ? 0 : length] = '\0';
  execl(self, self, workDir, programDir, mode, (char *)NULL);
  _exit(99);
}

// Cut the file at fixed offsets and at steps through it; a cut at a line's end may
// restore whole, any other must be kept aside.
static void sweepCuts(const char *path, const char *saved, size_t length, long steps) {
  long cuts[64];
  size_t count = 0;
  const long fixed[] = {200, 600, 4096, 20480, 40960, 43400, 45056, 100000};
  for (size_t i = 0; i < sizeof(fixed) / sizeof(fixed[0]); i++) {
    if (fixed[i] < (long)length) {
      cuts[count++] = fixed[i];
    }
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
    CHECK(atLineEnd || kept == 1, "cut %ld: %d files kept", cuts[i], kept);
  }
}

static void statePath(char *path, size_t size) {
  snprintf(path, size, "%s/backupR47.cfg", workDir);
}

// ---- the relaunches -----------------------------------------------------------------

static void relaunchExpectingAFreshStart(void) {
  char path[1024];
  statePath(path, sizeof(path));
  startCalculator();
  const state_t s = readState();
  CHECK(strcmp(s.x, "0.") == 0 && strcmp(s.y, "0.") == 0, "not a fresh start: X %s, Y %s", s.x,
        s.y);
  CHECK(atomic_load(&warningCount) == 1, "%d warnings", atomic_load(&warningCount));
  CHECK(!fileExists(path), "the unusable file is still where the next save writes");
}

static void relaunchExpectingTheSavedStack(void) {
  startCalculator();
  const state_t s = readState();
  CHECK(strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0, "not restored: X %s, Y %s", s.x, s.y);
  CHECK(atomic_load(&warningCount) == 0, "%d warnings", atomic_load(&warningCount));
}

static void relaunchExpectingEither(void) {
  char path[1024];
  statePath(path, sizeof(path));
  startCalculator();
  const state_t s = readState();
  const bool fresh = strcmp(s.x, "0.") == 0 && strcmp(s.y, "0.") == 0 &&
                     atomic_load(&warningCount) == 1 && !fileExists(path);
  const bool restored =
      strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0 && atomic_load(&warningCount) == 0;
  CHECK(fresh || restored, "X %s, Y %s, %d warnings", s.x, s.y, atomic_load(&warningCount));
}

static void relaunchExpectingTheMarkerGone(void) {
  char marker[1100];
  snprintf(marker, sizeof(marker), "%s/backupR47.cfg.restoring", workDir);
  startCalculator();
  CHECK(!fileExists(marker), "the marker outlived the restore and its first refresh");
}

static const double storedBounds[4] = {-7.25, 12.5, -3.75, 9.5};
static const calcRegister_t boundVariables[4] = {RESERVED_VARIABLE_LX, RESERVED_VARIABLE_UX,
                                                 RESERVED_VARIABLE_LY, RESERVED_VARIABLE_UY};

typedef struct {
  double bounds[4];
  int xType;
  int rows;
  int columns;
} boundsAndMatrix_t;

static boundsAndMatrix_t readBoundsAndMatrix(void) {
  boundsAndMatrix_t out;
  memset(&out, 0, sizeof(out));
  pthread_mutex_lock(&screenMutex);
  for (int i = 0; i < 4; i++) {
    real_t value;
    real34ToReal(REGISTER_REAL34_DATA(boundVariables[i]), &value);
    realToDouble(&value, &out.bounds[i]);
  }
  out.xType = (int)getRegisterDataType(REGISTER_X);
  if (out.xType == dtReal34Matrix) {
    out.rows = REGISTER_MATRIX_HEADER(REGISTER_X)->matrixRows;
    out.columns = REGISTER_MATRIX_HEADER(REGISTER_X)->matrixColumns;
  }
  pthread_mutex_unlock(&screenMutex);
  return out;
}

static void relaunchExpectingBoundsAndMatrix(void) {
  startCalculator();
  const boundsAndMatrix_t read = readBoundsAndMatrix();
  for (int i = 0; i < 4; i++) {
    CHECK(read.bounds[i] == storedBounds[i], "bound %d is %g, stored %g", i, read.bounds[i],
          storedBounds[i]);
  }
  const int n = atoi(getenv("R47_HARNESS_MATRIX_SIZE"));
  CHECK(read.xType == dtReal34Matrix && read.rows == n && read.columns == n,
        "X is type %d, %dx%d, not %dx%d", read.xType, read.rows, read.columns, n, n);
}

typedef struct {
  uint32_t value;
  uint32_t startTime;
  bool running;
} stopwatch_t;

static stopwatch_t readStopwatch(void) {
  stopwatch_t out;
  pthread_mutex_lock(&screenMutex);
  out.value = timerValue;
  out.startTime = timerStartTime;
  out.running = timerStartTime != TIMER_APP_STOPPED && getSystemFlag(FLAG_RUNTIM);
  pthread_mutex_unlock(&screenMutex);
  return out;
}

static void relaunchExpectingAStoppedStopwatch(void) {
  startCalculator();
  const stopwatch_t read = readStopwatch();
  CHECK(!read.running, "the restored stopwatch says it runs");
  CHECK(read.value >= 250, "the restored stopwatch lost its time: %u ms", read.value);
}

static void relaunchExpectingTheReleasedState(void) {
  startCalculator();
  const state_t s = readState();
  pthread_mutex_lock(&screenMutex);
  const bool factIt = findNamedLabel("FactIt", ALL_LABELS) != INVALID_VARIABLE;
  pthread_mutex_unlock(&screenMutex);
  CHECK(strcmp(s.x, "4.") == 0 && strcmp(s.y, "3.") == 0 && strcmp(s.z, "2.") == 0 &&
            strcmp(s.t, "1.") == 0,
        "not restored: %s %s %s %s", s.t, s.z, s.y, s.x);
  CHECK(factIt, "the program FactIt is lost");
  CHECK(atomic_load(&warningCount) == 0, "%d warnings", atomic_load(&warningCount));
}

static void relaunchSavingAndStopping(void) {
  startCalculator();
  saveOnce();
}

static void relaunchExpectingTheUserKeys(void) {
  startCalculator();
  char path[1100];
  snprintf(path, sizeof(path), "%s/userKeys.bin", workDir);
  static char expected[sizeof(kbd_usr)];
  FILE *file = fopen(path, "rb");
  CHECK(file != NULL && fread(expected, 1, sizeof(expected), file) == sizeof(expected),
        "no keys to compare");
  if (file) {
    fclose(file);
  }
  CHECK(memcmp(expected, kbd_usr, sizeof(expected)) == 0, "the user keys changed");
}

// ---- the cases ----------------------------------------------------------------------

static void caseUnusableStateFileIsKeptAside(void) {
  startCalculator();
  setStack(0, 0, 3, 4);
  saveOnce();
  char path[1024];
  statePath(path, sizeof(path));
  size_t length = 0;
  char *saved = readWhole(path, &length);
  if (saved == NULL) {
    return;
  }

  writeBytes(path, saved, length);
  execSelfInChild("--relaunch-expect-saved-stack");
  CHECK(takeKeptStateFiles() == 0, "a whole file was set aside");

  sweepCuts(path, saved, length, 40);

  // Line ends before the memory image, every 32nd and the last, and the first inside it.
  const char *ram = strstr(saved, "\nram:hexDump:");
  CHECK(ram != NULL, "no memory image in the saved file");
  if (ram != NULL) {
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
  }

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

static void caseAFileThatCrashedTwoStartsIsSetAside(void) {
  startCalculator();
  setStack(0, 0, 3, 4);
  saveOnce();
  writeRestoreMarker("1\n");
  execSelfInChild("--relaunch-expect-saved-stack");
  execSelfInChild("--relaunch-expect-marker-gone");
  CHECK(countFilesStartingWith("backupR47.cfg.crashed.") == 0,
        "one failed start set the file aside");

  // Each set-aside copy keeps its own name.
  for (int round = 1; round <= 2; round++) {
    saveOnce();
    writeRestoreMarker("2\n");
    execSelfInChild("--relaunch-expect-fresh");
    CHECK(countFilesStartingWith("backupR47.cfg.crashed.") == round,
          "two failed starts kept %d copies, not %d",
          countFilesStartingWith("backupR47.cfg.crashed."), round);
    sleep(1); // a second copy in the same second takes a suffix, not the same name
  }
}

static const char *const releasedStateFiles[] = {"backupR47-0.1.13.cfg.gz",
                                                 "backupR47-0.2.0.cfg.gz"};

// Each released core's state file restores whole at this core, and so does the file
// this core saves from it.
static void caseReleasedStateFilesRestore(void) {
  const char *fixtures = getenv("R47_STATE_FIXTURES");
  CHECK(fixtures != NULL, "R47_STATE_FIXTURES names no directory");
  if (fixtures == NULL) {
    return;
  }
  char path[1100];
  statePath(path, sizeof(path));
  for (size_t i = 0; i < sizeof(releasedStateFiles) / sizeof(releasedStateFiles[0]); i++) {
    char command[3000];
    snprintf(command, sizeof(command), "gzip -dc '%s/%s' > '%s'", fixtures,
             releasedStateFiles[i], path);
    CHECK(system(command) == 0, "cannot unpack %s", releasedStateFiles[i]);
    size_t length = 0;
    char *released = readWhole(path, &length);
    if (released == NULL) {
      continue;
    }
    sweepCuts(path, released, length, 12);
    writeBytes(path, released, length);
    free(released);
    execSelfInChild("--relaunch-expect-released-state");
    execSelfInChild("--relaunch-save-and-stop");
    execSelfInChild("--relaunch-expect-released-state");
  }
}

static void caseASaveKeepsTheStopwatchTime(void) {
  startCalculator();
  pthread_mutex_lock(&screenMutex);
  fnStartStopTimerApp(NOPARAM);
  pthread_mutex_unlock(&screenMutex);
  struct timespec pause = {0, 300 * 1000 * 1000};
  nanosleep(&pause, NULL);
  const stopwatch_t before = readStopwatch();
  saveOnce();
  const stopwatch_t live = readStopwatch();
  CHECK(live.running, "the save stopped the stopwatch");
  CHECK(live.startTime == before.startTime && live.value == before.value,
        "the save moved the stopwatch: start %u to %u, value %u to %u", before.startTime,
        live.startTime, before.value, live.value);
  execSelfInChild("--relaunch-expect-stopped-stopwatch");
}

static void caseAColdStartKeepsTheBoundsAndX(void) {
  startCalculator();
  pthread_mutex_lock(&screenMutex);
  for (int i = 0; i < 4; i++) {
    convertDoubleToReal34Register(storedBounds[i], boundVariables[i]);
  }
  // The largest square matrix memory holds, so a copy of it aside cannot fit.
  for (uint16_t n = 120; n >= 10; n -= 2) {
    lastErrorCode = ERROR_NONE; // a size that did not fit leaves RAM FULL behind
    if (initMatrixRegister(REGISTER_X, n, n, false)) {
      break;
    }
  }
  pthread_mutex_unlock(&screenMutex);
  const boundsAndMatrix_t before = readBoundsAndMatrix();
  CHECK(before.xType == dtReal34Matrix && before.rows > 10, "no large matrix to keep");
  char size[16];
  snprintf(size, sizeof(size), "%d", before.rows);
  setenv("R47_HARNESS_MATRIX_SIZE", size, 1);
  saveOnce();
  execSelfInChild("--relaunch-expect-bounds-and-matrix");
  execSelfInChild("--relaunch-expect-bounds-and-matrix");
}

// Upstream's xfnToR47user.s47 assigns user keys through LOADST. They survive a save
// and a relaunch whole.
static void caseUserKeysSurviveASave(void) {
  startCalculator();
  static char before[sizeof(kbd_usr)];
  static char loaded[sizeof(kbd_usr)];
  memcpy(before, kbd_usr, sizeof(before));
  char from[1100];
  char to[1100];
  snprintf(from, sizeof(from), "%s/../STATE/xfnToR47user.s47", programDir);
  snprintf(to, sizeof(to), "%s/default.dat", workDir);
  copyFile(from, to);
  pthread_mutex_lock(&screenMutex);
  fnLoad(LM_STATE_LOAD);
  memcpy(loaded, kbd_usr, sizeof(loaded));
  pthread_mutex_unlock(&screenMutex);
  CHECK(memcmp(before, loaded, sizeof(before)) != 0, "LOADST changed no user key");
  snprintf(to, sizeof(to), "%s/userKeys.bin", workDir);
  writeBytes(to, loaded, sizeof(loaded));
  saveOnce();
  execSelfInChild("--relaunch-expect-user-keys");
}

static void caseAnExitAborts(void) {
  fflush(NULL);
  const pid_t child = fork();
  if (child == 0) {
    exit(7); // linked to __wrap_exit, as in the app
  }
  int status = 0;
  waitpid(child, &status, 0);
  CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT, "an exit ended with status %x",
        status);
}

typedef struct {
  const char *name;
  void (*run)(void);
} entry_t;

int main(int argc, char **argv) {
  if (argc == 4) {
    workDir = argv[1];
    programDir = argv[2];
    static const entry_t relaunches[] = {
        {"--relaunch-expect-fresh", relaunchExpectingAFreshStart},
        {"--relaunch-expect-saved-stack", relaunchExpectingTheSavedStack},
        {"--relaunch-expect-either", relaunchExpectingEither},
        {"--relaunch-expect-marker-gone", relaunchExpectingTheMarkerGone},
        {"--relaunch-expect-bounds-and-matrix", relaunchExpectingBoundsAndMatrix},
        {"--relaunch-expect-stopped-stopwatch", relaunchExpectingAStoppedStopwatch},
        {"--relaunch-expect-released-state", relaunchExpectingTheReleasedState},
        {"--relaunch-save-and-stop", relaunchSavingAndStopping},
        {"--relaunch-expect-user-keys", relaunchExpectingTheUserKeys},
    };
    for (size_t i = 0; i < sizeof(relaunches) / sizeof(relaunches[0]); i++) {
      if (strcmp(argv[3], relaunches[i].name) == 0) {
        relaunches[i].run();
        fflush(NULL);
        _exit(failures == 0 ? CASE_PASSED : 1);
      }
    }
    fprintf(stderr, "unknown relaunch %s\n", argv[3]);
    _exit(2);
  }
  if (argc != 2) {
    fprintf(stderr, "usage: %s <upstream res/PROGRAMS directory>\n", argv[0]);
    return 2;
  }
  programDir = argv[1];

  static const entry_t cases[] = {
      {"a state file the core cannot take is kept aside", caseUnusableStateFileIsKeptAside},
      {"a file that crashed two starts is set aside", caseAFileThatCrashedTwoStartsIsSetAside},
      {"released state files restore", caseReleasedStateFilesRestore},
      {"a save keeps the stopwatch time", caseASaveKeepsTheStopwatchTime},
      {"a cold start keeps the bounds and X", caseAColdStartKeepsTheBoundsAndX},
      {"user keys survive a save", caseUserKeysSurviveASave},
      {"an exit aborts with a log", caseAnExitAborts},
  };
  int failed = 0;
  for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
    char dir[] = "/tmp/r47-state-durability-XXXXXX";
    CHECK(mkdtemp(dir) != NULL, "no work directory");
    fflush(NULL);
    const pid_t child = fork();
    if (child == 0) {
      workDir = dir;
      cases[i].run();
      fflush(NULL);
      _exit(failures == 0 ? CASE_PASSED : 1);
    }
    int status = 0;
    waitpid(child, &status, 0);
    const bool passed = WIFEXITED(status) && WEXITSTATUS(status) == CASE_PASSED;
    printf("%s: %s\n", passed ? "OK" : "FAIL", cases[i].name);
    failed += !passed;
    char command[128];
    snprintf(command, sizeof(command), "rm -rf '%s'", dir);
    if (system(command) != 0) {
      fprintf(stderr, "cannot remove %s\n", dir);
    }
  }
  printf("%d of %zu cases failed\n", failed, sizeof(cases) / sizeof(cases[0]));
  return failed == 0 ? 0 : 1;
}
