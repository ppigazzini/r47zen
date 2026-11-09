#include "c47.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <time.h>
#include <android/log.h>

// Android HAL definition of the upstream PC_BUILD filename-override slot.
// fnScreenDump (gated by PC_BUILD) writes its BMP to this name and clears it; left
// empty, it would write a timestamped name relative to the working directory, which
// an Android app cannot count on writing. r47_arm_snap and r47_offer_snap keep a
// private path here, so a SNAP lands in a file the save picker can then pass on.
// Use PATH_MAX from <limits.h> to match the upstream C47_PATH_MAX mapping on
// POSIX hosts (C47_PATH_MAX resolves to PATH_MAX when PATH_MAX is defined).
#include <limits.h>
char _ioFileNameOverride[PATH_MAX] = {0};
static char snapPath[PATH_MAX] = "";

int create_dir(char *dir) {
  int ret;

#if defined(WIN32)
  ret = mkdir(dir);
#else
  ret = mkdir(dir, 0775);
#endif

  if (ret != 0 && errno != EEXIST) {
    return -1;
  }

  return 0;
}

static char android_base_path[512] = "";
static int android_base_path_ready = 0;
int current_slot_id = 0; // Linked via JNI

static FILE *openedFile = NULL;
// Whether openedFile was opened for writing, and whether a write to it has
// come up short. Upstream's HAL signatures return nothing, and upstream reports
// only a failed open, so ioFileClose is where a lost write is noticed and
// reported, once per file, through r47_report_file_write_failure.
static bool openedFileWritable = false;
static bool openedFileWriteFailed = false;
// The state file a save is replacing, and the copy beside it the save writes.
static char replaceTarget[PATH_MAX] = "";
static char replaceTemp[PATH_MAX + 8] = "";
// The folder WorkDirectory.kt starts the save picker in, for a screen capture.
#define FILE_CATEGORY_SCREENS 3
extern int requestAndroidFile(int isSave, const char *defaultName,
                              int fileType);
extern void r47_report_file_write_failure(void);

static void ensure_android_subdir(const char *subdir) {
  char buf[1024];

  if (!android_base_path_ready) {
    return;
  }

  snprintf(buf, sizeof(buf), "%s/%s", android_base_path, subdir);
  mkdir(buf, 0777);
}

static int has_android_base_path(void) {
  if (android_base_path_ready) {
    return 1;
  }

  LOGI("Android base path is not initialized.");
  return 0;
}

void set_android_base_path(const char *path) {
  int written;

  if (path == NULL || path[0] == '\0') {
    android_base_path[0] = '\0';
    android_base_path_ready = 0;
    return;
  }

  written = snprintf(android_base_path, sizeof(android_base_path), "%s", path);
  if (written < 0 || written >= (int)sizeof(android_base_path)) {
    android_base_path[0] = '\0';
    android_base_path_ready = 0;
    LOGI("Android base path is too long.");
    return;
  }

  android_base_path_ready = 1;
  ensure_android_subdir(STATE_DIR);
  ensure_android_subdir(PROGRAMS_DIR);
  ensure_android_subdir(SAVE_DIR);
}

// Make a descriptor the picker returned the open file.
static bool openPickedFile(int fd, int isSave) {
  openedFile = fdopen(fd, isSave ? "wb" : "rb");
  if (!openedFile) {
    close(fd);
    return false;
  }
  openedFileWritable = isSave;
  openedFileWriteFailed = false;
  return true;
}

int ioFileOpen(ioFilePath_t path, ioFileMode_t mode) {
  if (openedFile) {
    ioFileClose();
  }

#if !defined(HOST_TOOL_BUILD)
  // Intercept State, Program, Manual Save, and register data file operations for
  // Android SAF
  if (path == ioPathSaveStateFile || path == ioPathLoadStateFile ||
      path == ioPathSaveProgram || path == ioPathLoadProgram ||
      path == ioPathExportRTFProgram || path == ioPathSaveAllPrograms ||
      path == ioPathExportRTFAllPrograms || path == ioPathManualSave ||
      path == ioPathPgmFile || path == ioPathRegExport ||
      path == ioPathRegImport) {

    int isSave = (mode == ioModeWrite);
    char defaultName[256];
    const char *ext = ".s47";

    if (path == ioPathSaveProgram || path == ioPathLoadProgram ||
        path == ioPathSaveAllPrograms || path == ioPathPgmFile) {
      ext = ".p47";
    } else if (path == ioPathExportRTFProgram ||
               path == ioPathExportRTFAllPrograms) {
      ext = ".rtf";
    } else if (path == ioPathManualSave) {
      ext = ".sav";
    }

    extern char *tmpStringLabelOrVariableName;
    extern void stringToASCII(const char *in, char *out);

    if (path == ioPathSaveStateFile || path == ioPathLoadStateFile) {
      snprintf(defaultName, sizeof(defaultName), "%s", "state.s47");
    } else if (path == ioPathManualSave) {
#if (CALCMODEL == USER_R47)
      snprintf(defaultName, sizeof(defaultName), "%s", "R47.sav");
#else
      snprintf(defaultName, sizeof(defaultName), "%s", "C47.sav");
#endif
    } else if (path == ioPathRegExport || path == ioPathRegImport) {
      snprintf(defaultName, sizeof(defaultName), "%s", "data.d47");
    } else {
      // For programs, try to use the current label name
      if (tmpStringLabelOrVariableName &&
          tmpStringLabelOrVariableName[0] != 0) {
        char asciiName[256];
        stringToASCII(tmpStringLabelOrVariableName, asciiName);
        snprintf(defaultName, sizeof(defaultName), "%s%s", asciiName, ext);
      } else {
        snprintf(defaultName, sizeof(defaultName), "program%s", ext);
      }
    }

    LOGI("ioFileOpen SAF: path=%d, isSave=%d, defaultName=%s", path, isSave,
         defaultName);

    int category = 1; // Default to PROGRAMS
    if (path == ioPathSaveStateFile || path == ioPathLoadStateFile) {
      category = 0; // STATE
    } else if (path == ioPathManualSave) {
      category = 2; // SAVFILES
    } else if (path == ioPathRegExport || path == ioPathRegImport) {
      category = 4; // DATA
    }

    int fd = requestAndroidFile(isSave, defaultName, category);
    if (fd < 0)
      return FILE_CANCEL;

    if (!openPickedFile(fd, isSave)) {
      return FILE_ERROR;
    }
    return FILE_OK;
  }
#endif

  if (!has_android_base_path()) {
    return FILE_ERROR;
  }

  char fullpath[1024];
  const char *modeStr =
      (mode == ioModeRead) ? "rb" : ((mode == ioModeWrite) ? "wb" : "r+b");

  switch (path) {
  case ioPathManualSave:
#if (CALCMODEL == USER_R47)
    snprintf(fullpath, 1024, "%s/%s/R47_%d.sav", android_base_path, SAVE_DIR,
             current_slot_id);
#else
    snprintf(fullpath, 1024, "%s/%s/C47_%d.sav", android_base_path, SAVE_DIR,
             current_slot_id);
#endif
    break;
  case ioPathAutoSave:
#if (CALCMODEL == USER_R47)
    snprintf(fullpath, 1024, "%s/%s/R47auto_%d.sav", android_base_path,
             SAVE_DIR, current_slot_id);
#else
    snprintf(fullpath, 1024, "%s/%s/C47auto_%d.sav", android_base_path,
             SAVE_DIR, current_slot_id);
#endif
    break;
  case ioPathBackup:
#if (CALCMODEL == USER_R47)
    snprintf(fullpath, 1024, "%s/backupR47_%d.cfg", android_base_path,
             current_slot_id);
#else
    snprintf(fullpath, 1024, "%s/backup_%d.cfg", android_base_path,
             current_slot_id);
#endif
    break;
  case ioPathPgmFile:
  case ioPathSaveProgram:
  case ioPathLoadProgram:
    snprintf(fullpath, 1024, "%s/%s/program.p47", android_base_path,
             PROGRAMS_DIR);
    break;
  default:
    snprintf(fullpath, 1024, "%s/default.dat", android_base_path);
    break;
  }

  if (mode != ioModeRead) {
    char dir[1024];
    if (path == ioPathManualSave || path == ioPathAutoSave) {
      snprintf(dir, 1024, "%s/%s", android_base_path, SAVE_DIR);
      mkdir(dir, 0777);
    } else if (path == ioPathPgmFile || path == ioPathSaveProgram ||
               path == ioPathLoadProgram) {
      snprintf(dir, 1024, "%s/%s", android_base_path, PROGRAMS_DIR);
      mkdir(dir, 0777);
    }
  }

  // The state file is the only copy of the user's work, and a save can be killed at
  // any point, so it is written beside the file and renamed over it. A save that
  // cannot start is canceled, because saveCalc ends the process on an error, and the
  // file it would have replaced stays good.
  if (path == ioPathBackup && mode == ioModeWrite) {
    snprintf(replaceTarget, sizeof(replaceTarget), "%s", fullpath);
    snprintf(replaceTemp, sizeof(replaceTemp), "%s.tmp", fullpath);
    openedFile = fopen(replaceTemp, modeStr);
    openedFileWritable = openedFile != NULL;
    openedFileWriteFailed = false;
    if (!openedFile) {
      LOGE("ioFileOpen: cannot write %s: %s", replaceTemp, strerror(errno));
      replaceTarget[0] = '\0';
      r47_report_file_write_failure();
      return FILE_CANCEL;
    }
    return FILE_OK;
  }

  openedFile = fopen(fullpath, modeStr);
  openedFileWritable = openedFile != NULL && mode != ioModeRead;
  openedFileWriteFailed = false;
  if (!openedFile) {
    return FILE_ERROR;
  }
  return FILE_OK;
}

void ioFileWrite(const void *buffer, uint32_t size) {
  if (!openedFile) {
    return;
  }
  if (fwrite(buffer, 1, size, openedFile) != size && !openedFileWriteFailed) {
    LOGE("ioFileWrite: short write: %s", strerror(errno));
    openedFileWriteFailed = true;
  }
}

uint32_t ioFileRead(void *buffer, uint32_t size) {
  if (openedFile)
    return fread(buffer, 1, size, openedFile);
  return 0;
}

void ioFileSeek(uint32_t position) {
  if (openedFile)
    fseek(openedFile, position, SEEK_SET);
}

// Replace the state file with the whole new copy, or leave it as it was.
static bool finishReplacingFile(bool failed) {
  if (replaceTarget[0] == '\0') {
    return failed;
  }
  if (failed) {
    unlink(replaceTemp);
  } else if (rename(replaceTemp, replaceTarget) != 0) {
    LOGE("ioFileClose: cannot replace %s: %s", replaceTarget, strerror(errno));
    unlink(replaceTemp);
    failed = true;
  } else {
    // The rename is durable only once its directory is.
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s", replaceTarget);
    char *slash = strrchr(dir, '/');
    if (slash != NULL) {
      *slash = '\0';
      const int dirFd = open(dir, O_RDONLY | O_DIRECTORY);
      if (dirFd >= 0) {
        fsync(dirFd);
        close(dirFd);
      }
    }
  }
  replaceTarget[0] = '\0';
  return failed;
}

void ioFileClose(void) {
  if (!openedFile) {
    return;
  }
  bool failed = openedFileWriteFailed;
  if (openedFileWritable) {
    if (fflush(openedFile) != 0) {
      LOGE("ioFileClose: flush failed: %s", strerror(errno));
      failed = true;
    }
    // fsync makes a private file durable. A SAF save writes a pipe, which cannot be
    // synced and answers EINVAL, as a character device does: DocumentTransfer syncs
    // the document itself.
    if (fsync(fileno(openedFile)) != 0 && errno != EINVAL) {
      LOGE("ioFileClose: fsync failed: %s", strerror(errno));
      failed = true;
    }
  }
  if (fclose(openedFile) != 0 && openedFileWritable) {
    LOGE("ioFileClose: close failed: %s", strerror(errno));
    failed = true;
  }
  openedFile = NULL;
  openedFileWritable = false;
  openedFileWriteFailed = false;
  failed = finishReplacingFile(failed);
  if (failed) {
    r47_report_file_write_failure();
  }
}

// Name the private file the next SNAP writes, and drop a capture left by a process
// that died before offering it.
void r47_arm_snap(void) {
  if (!has_android_base_path()) {
    return;
  }
  snprintf(snapPath, sizeof(snapPath), "%s/snap.bmp", android_base_path);
  unlink(snapPath);
  snprintf(_ioFileNameOverride, PATH_MAX, "%s", snapPath);
}

// Offer a finished capture through the save picker, then arm the next. fnScreenDump
// clearing the name is the signal. The tick calls this on the core thread with
// screenMutex held, and an armed name returns on the first test. A running program
// reaches no tick, so of the SNAPs it takes before it stops only the first is kept.
void r47_offer_snap(void) {
  if (_ioFileNameOverride[0] != '\0' || snapPath[0] == '\0' || openedFile) {
    return;
  }
  FILE *capture = fopen(snapPath, "rb");
  if (capture) {
    char name[32];
    struct tm when;
    time_t now = time(NULL);
    strftime(name, sizeof(name), "%Y%m%d-%H%M%S00.bmp",
             localtime_r(&now, &when));
    int fd = requestAndroidFile(1, name, FILE_CATEGORY_SCREENS);
    if (fd >= 0 && openPickedFile(fd, 1)) {
      // A short read means the end of the file or an error, so it is the last.
      char chunk[4096];
      size_t count;
      do {
        count = fread(chunk, 1, sizeof(chunk), capture);
        ioFileWrite(chunk, (uint32_t)count);
      } while (count == sizeof(chunk));
      openedFileWriteFailed = openedFileWriteFailed || ferror(capture);
      ioFileClose();
    }
    fclose(capture);
    unlink(snapPath);
  }
  snprintf(_ioFileNameOverride, PATH_MAX, "%s", snapPath);
}

int ioEof(void) { return openedFile ? feof(openedFile) : 1; }

int save_statefile(const char *fpath, const char *fname, void *data) {
  return MRET_SAVESTATE;
}
int load_statefile(const char *fpath, const char *fname, void *data) {
  return MRET_LOADSTATE;
}
int save_programfile(const char *fpath, const char *fname, void *data) {
  return MRET_SAVESTATE;
}
int load_programfile(const char *fpath, const char *fname, void *data) {
  return MRET_LOADSTATE;
}
void show_warning(char *string) {}
void fnDiskInfo(uint16_t unused) {}
