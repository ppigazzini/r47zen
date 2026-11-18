// libFuzzer entry for the files a user hands the calculator: a program (READP), the
// state file the app restores at every start, a state file (LOADST), and a data file.
// A calculator writes each of them, so an input is never a file's bytes: those reach
// values no writer produces, and a fault on such a value is no defect a user can meet.
// An input is what a user does. Its first byte picks the file the calculator starts
// from, none or one a calculator wrote, which the reader opens; each byte after it
// presses and releases a key. The calculator then writes the target's file with its own
// writer, is reset, and reads the file back. A restore is followed, as at the app's
// start, by the graph-bound repair, the state refresh, and the first redraw. The core and
// the glue run on this one thread, as on the calculator thread; libFuzzer counts a core
// exit as a finding.
//
// FUZZ_TARGET picks the reader: program, backup, state, or data. FUZZ_START_DIR holds
// the files an input may start from, FUZZ_TMP the folders each process keeps its files
// in. build_fuzz_core.sh builds it and runs it.
#include "jni_bridge.h"
#include "host_services.h"
#include "keypad_fixture_bridge.h"

#include <dirent.h>
#include <ftw.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

extern void doFnReset(uint16_t confirmation, bool_t autoSav);
extern void restoreCalc(void);
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern void fnLoad(uint16_t loadMode);
extern void fnLoadRegisters(uint16_t unusedButMandatoryParameter);

typedef enum { targetProgram, targetBackup, targetState, targetData } target_t;

static target_t target;
static char inputPath[1024];
static char dir[512];

// A byte presses one of the 37 keys, "00" to "36", or one of the six softkeys, "1" to
// "6", as r47_send_sim_key names them.
#define KEY_COUNT 43
// EXIT, and the code r47_send_sim_key gives it: a running program and a computation stop
// on that code, R47 or C47 layout alike.
#define EXIT_KEY "32"
#define EXIT_CODE 33

// The first byte of an input picks one of these, or none, so a byte bounds their number.
#define MAX_START_FILES 255

typedef struct {
  uint8_t *bytes;
  size_t size;
} startFile_t;

static startFile_t startFiles[MAX_START_FILES];
static int startFileCount;

// A key that starts a program or a computation returns when it ends, and a user ends one
// that runs on with EXIT. So does pressExitWhenBusy, EXIT_AFTER_US into a press and each
// EXIT_AFTER_US after, through the queue the app's presses take. One that takes no EXIT
// runs into the -timeout build_fuzz_core.sh sets, which libFuzzer reports as a finding.
#define EXIT_AFTER_US 500000

static pthread_mutex_t pressMutex = PTHREAD_MUTEX_INITIALIZER;
static int64_t pressExitAtUs; // 0 while no key is being handled

static int64_t monotonicUs(void) {
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (int64_t)now.tv_sec * 1000000 + now.tv_nsec / 1000;
}

static void setPressExitAt(int64_t atUs) {
  pthread_mutex_lock(&pressMutex);
  pressExitAtUs = atUs;
  pthread_mutex_unlock(&pressMutex);
}

static void *pressExitWhenBusy(void *unused) {
  (void)unused;
  for (;;) {
    usleep(50000);
    pthread_mutex_lock(&pressMutex);
    if (pressExitAtUs != 0 && monotonicUs() >= pressExitAtUs) {
      r47_key_push(EXIT_CODE);
      r47_key_push(0);
      pressExitAtUs = monotonicUs() + EXIT_AFTER_US;
    }
    pthread_mutex_unlock(&pressMutex);
  }
  return NULL;
}

static void pressKey(const char *id) {
  setPressExitAt(monotonicUs() + EXIT_AFTER_US);
  r47_send_sim_key(id, false, false);
  r47_send_sim_key(id, false, true);
  setPressExitAt(0);
  // An EXIT pressed once the work it was for had ended is a key the input did not press,
  // and it goes, so an input replays the same keys.
  while (!key_empty()) {
    key_pop();
  }
  clearKeyBuffer();
}

static void pressByte(uint8_t byte) {
  const unsigned key = byte % KEY_COUNT;
  char id[3] = {0};
  if (key < 37) {
    id[0] = (char)('0' + key / 10);
    id[1] = (char)('0' + key % 10);
  } else {
    id[0] = (char)('1' + key - 37);
  }
  pressKey(id);
}

// Every file an earlier input left goes, a file a key saved among them, so each input
// starts from the same folder and replays alike. hal/io.c also sets a state file it
// refuses aside under a name of its own, which would fill the disk over a run.
static int removeFile(const char *path, const struct stat *info, int type,
                      struct FTW *walk) {
  (void)info;
  (void)walk;
  if (type == FTW_F || type == FTW_SL) {
    unlink(path);
  }
  return 0;
}

static bool writeInput(const uint8_t *bytes, size_t size) {
  FILE *file = fopen(inputPath, "wb");
  if (file == NULL) {
    return false;
  }
  const bool written = fwrite(bytes, 1, size, file) == size;
  return fclose(file) == 0 && written;
}

// Open the target's file as a user does: READP, the app's start, LOADST, or the data
// import, each on a calculator at rest.
static void readFile(void) {
  lastErrorCode = ERROR_NONE;
  switch (target) {
  case targetProgram:
    dynamicMenuItem = -1; // as a key would, so the load activates the program
    fnLoadProgram(NOPARAM);
    break;
  case targetBackup:
    restoreCalc();
    r47_sanitize_graph_bounds();
    fnRefreshState();
    r47_force_refresh();
    break;
  case targetState:
    fnLoad(LM_STATE_LOAD);
    break;
  case targetData:
    fnLoadRegisters(NOPARAM);
    break;
  }
}

static bool atRest(void) {
  return calcMode == CM_NORMAL && !tam.mode && programRunStop != PGM_RUNNING;
}

// Write the target's file with the calculator's own writer. The app saves its state file
// when it stops, which can fall between any two keys, so that save runs where the keys
// left the calculator. SAVEST, WRITEP, and EXPnrg are menu items, which a key reaches
// only at rest: EXIT, up to three times, as a user leaves an entry, an editor, or a
// browser, and no file when that is not enough. Each runs with a parameter its key
// gives: WRITEP with no label writes the current program, and EXPnrg's largest count
// writes every register up to RW.
static bool writeFile(void) {
  unlink(inputPath);
  if (target == targetBackup) {
    saveCalc();
  } else {
    for (int i = 0; i < 3 && !atRest(); i++) {
      pressKey(EXIT_KEY);
    }
    if (!atRest()) {
      return false;
    }
    switch (target) {
    case targetProgram:
      reallyRunFunction(ITM_WRITEP, 0);
      break;
    case targetState:
      reallyRunFunction(ITM_SAVEST, SM_STATE_SAVE);
      break;
    case targetData:
      reallyRunFunction(ITM_EXPNREG, LAST_SPARE_REGISTER + 1);
      break;
    case targetBackup:
      break;
    }
  }
  struct stat info;
  return stat(inputPath, &info) == 0;
}

static int regularFile(const struct dirent *entry) {
  return entry->d_type == DT_REG;
}

// The files in FUZZ_START_DIR, in name order, so a first byte picks the same file in
// every run.
static void readStartFiles(void) {
  const char *startDir = getenv("FUZZ_START_DIR");
  if (startDir == NULL) {
    return;
  }
  struct dirent **names;
  const int count = scandir(startDir, &names, regularFile, alphasort);
  if (count < 0) {
    perror("FUZZ_START_DIR");
    exit(2);
  }
  if (count > MAX_START_FILES) {
    fprintf(stderr, "FUZZ_START_DIR holds %d files; a first byte picks one of %d\n",
            count, MAX_START_FILES);
    exit(2);
  }
  for (int i = 0; i < count; i++) {
    char path[1300];
    snprintf(path, sizeof(path), "%s/%s", startDir, names[i]->d_name);
    FILE *file = fopen(path, "rb");
    if (file == NULL || fseek(file, 0, SEEK_END) != 0) {
      perror(path);
      exit(2);
    }
    const long size = ftell(file);
    rewind(file);
    uint8_t *bytes = malloc(size > 0 ? (size_t)size : 1);
    if (size < 0 || bytes == NULL || fread(bytes, 1, (size_t)size, file) != (size_t)size) {
      perror(path);
      exit(2);
    }
    fclose(file);
    startFiles[startFileCount++] = (startFile_t){bytes, (size_t)size};
    free(names[i]);
  }
  free(names);
}

int LLVMFuzzerInitialize(int *argc, char ***argv) {
  (void)argc;
  (void)argv;
  const char *name = getenv("FUZZ_TARGET");
  if (name == NULL || strcmp(name, "program") == 0) {
    target = targetProgram;
  } else if (strcmp(name, "backup") == 0) {
    target = targetBackup;
  } else if (strcmp(name, "state") == 0) {
    target = targetState;
  } else if (strcmp(name, "data") == 0) {
    target = targetData;
  } else {
    fprintf(stderr, "FUZZ_TARGET must be program, backup, state, or data\n");
    exit(2);
  }
  readStartFiles();

  const char *tmp = getenv("FUZZ_TMP");
  snprintf(dir, sizeof(dir), "%s/fuzz-%d", tmp != NULL ? tmp : "/tmp", (int)getpid());
  mkdir(dir, 0700);
  r47_native_preinit_path(dir);
  r47_init_runtime();

  switch (target) {
  case targetProgram:
    snprintf(inputPath, sizeof(inputPath), "%s/PROGRAMS/program.p47", dir);
    break;
  case targetBackup:
    snprintf(inputPath, sizeof(inputPath), "%s/backupR47.cfg", dir);
    break;
  case targetState:
  case targetData:
    snprintf(inputPath, sizeof(inputPath), "%s/default.dat", dir);
    break;
  }

  pthread_t thread;
  if (pthread_create(&thread, NULL, pressExitWhenBusy, NULL) != 0) {
    perror("pthread_create");
    exit(2);
  }
  pthread_detach(thread);
  return 0;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
  if (size == 0) {
    return 0;
  }
  nftw(dir, removeFile, 16, FTW_PHYS);
  doFnReset(CONFIRMED, false);
  const int start = data[0] % (startFileCount + 1);
  if (start > 0 && writeInput(startFiles[start - 1].bytes, startFiles[start - 1].size)) {
    readFile();
  }
  for (size_t i = 1; i < size; i++) {
    pressByte(data[i]);
  }
  if (writeFile()) {
    doFnReset(CONFIRMED, false);
    readFile();
  }
  lastErrorCode = ERROR_NONE;
  programRunStop = PGM_STOPPED;
  return 0;
}
