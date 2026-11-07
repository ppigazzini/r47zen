// libFuzzer entry for the files a user hands the calculator: a program (READP), the
// state file the app restores at every start, a state file (LOADST), and a data file.
// Each input is written where the host build of hal/io.c looks for that file, and the
// core reads it through the same calls the app makes; a restore is followed, as at the
// app's start, by the graph-bound repair, the state refresh, and the first redraw. The
// core and the glue run on this one thread, as on the calculator thread, and the state
// file goes through the check hal/io.c makes before a restore. libFuzzer counts a core
// exit as a finding.
//
// FUZZ_TARGET picks the reader: program, backup, state, or data. FUZZ_TMP is where
// each process keeps its files. build_fuzz_core.sh builds it and runs a corpus.
#include "jni_bridge.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern void doFnReset(uint16_t confirmation, bool_t autoSav);
extern void restoreCalc(void);
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);
extern void fnLoad(uint16_t loadMode);
extern void fnLoadRegisters(uint16_t unusedButMandatoryParameter);
extern void fnSave(uint16_t saveMode);

typedef enum { targetProgram, targetBackup, targetState, targetData } target_t;

static target_t target;
static char inputPath[1024];
static char dir[512];

// hal/io.c moves a state file it refuses aside under a name of its own, which would fill
// the disk over a run; each input gets a clean folder.
static void dropKeptStateFiles(void) {
  DIR *folder = opendir(dir);
  if (folder == NULL) {
    return;
  }
  struct dirent *entry;
  while ((entry = readdir(folder)) != NULL) {
    if (strncmp(entry->d_name, "backupR47.cfg.", 14) == 0) {
      char path[1100];
      snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name);
      unlink(path);
    }
  }
  closedir(folder);
}

// A state file and a program for the seeds: FactIt.p47 loaded if FUZZ_SEED_PROGRAM names
// it, the stack and ten registers filled, so a SAVEST holds programs and registers.
static void fillState(void) {
  const char *program = getenv("FUZZ_SEED_PROGRAM");
  if (program != NULL) {
    char copy[1100];
    snprintf(copy, sizeof(copy), "cp '%s' '%s/PROGRAMS/program.p47'", program, dir);
    if (system(copy) == 0) {
      dynamicMenuItem = -1;
      fnLoadProgram(NOPARAM);
    }
  }
  for (calcRegister_t r = 0; r < 10; r++) {
    convertDoubleToReal34Register(r * 1.25 - 3, r);
  }
  convertDoubleToReal34Register(4, REGISTER_X);
  convertDoubleToReal34Register(3, REGISTER_Y);
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
  // FUZZ_SEED_STATE=<file> saves a SAVEST state file there, a seed for state.
  const char *stateSeed = getenv("FUZZ_SEED_STATE");
  if (stateSeed != NULL) {
    fillState();
    fnSave(SM_STATE_SAVE);
    char saved[1100];
    snprintf(saved, sizeof(saved), "%s/default.dat", dir);
    if (rename(saved, stateSeed) != 0) {
      perror("FUZZ_SEED_STATE");
      exit(2);
    }
    exit(0);
  }
  // FUZZ_SEED_BACKUP=<file> saves this core's state file there, a seed for backup.
  const char *seed = getenv("FUZZ_SEED_BACKUP");
  if (seed != NULL) {
    fillState();
    saveCalc();
    char saved[1100];
    snprintf(saved, sizeof(saved), "%s/backupR47.cfg", dir);
    if (rename(saved, seed) != 0) {
      perror("FUZZ_SEED_BACKUP");
      exit(2);
    }
    exit(0);
  }
  return 0;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
  FILE *file = fopen(inputPath, "wb");
  if (file == NULL) {
    return 0;
  }
  fwrite(data, 1, size, file);
  fclose(file);

  doFnReset(CONFIRMED, false);
  dynamicMenuItem = -1; // as a key would, so a program load activates the program
  lastErrorCode = ERROR_NONE;
  switch (target) {
  case targetProgram:
    fnLoadProgram(NOPARAM);
    break;
  case targetBackup:
    restoreCalc();
    r47_sanitize_graph_bounds();
    fnRefreshState();
    r47_force_refresh();
    dropKeptStateFiles();
    break;
  case targetState:
    fnLoad(LM_STATE_LOAD);
    break;
  case targetData:
    fnLoadRegisters(NOPARAM);
    break;
  }
  lastErrorCode = ERROR_NONE;
  programRunStop = PGM_STOPPED;
  return 0;
}
