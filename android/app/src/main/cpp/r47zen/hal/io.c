#include "c47.h"
#include "r47_format.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <time.h>
#include <android/log.h>

#include "r47_backup_version.h"

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
// Whether a seek on openedFile failed; the file then reads as ended.
static bool openedFileSeekFailed = false;
// The state file a save is replacing, and the copy beside it the save writes.
static char replaceTarget[PATH_MAX] = "";
static char replaceTemp[PATH_MAX + 8] = "";
// The folder WorkDirectory.kt starts the save picker in, for a screen capture.
#define FILE_CATEGORY_SCREENS 3
extern int requestAndroidFile(int isSave, const char *defaultName,
                              int fileType);
extern void r47_report_file_write_failure(void);
extern void r47_show_warning(const char *text);

static bool setAsideUnusableStateFile(const char *path);
static int has_android_base_path(void);

// Where a SAVE and an automatic save of this slot live. Every path here is the base
// path, which set_android_base_path holds to 511 bytes, and a short name, in a buffer
// of 1024 bytes or more, so none is cut; a cut would still fail the operation.
static bool savePath(char *out, size_t size, bool automatic) {
#if (CALCMODEL == USER_R47)
  return r47_format(out, size, "%s/%s/R47%s_%d.sav", android_base_path,
                    SAVE_DIR, automatic ? "auto" : "", current_slot_id);
#else
  return r47_format(out, size, "%s/%s/C47%s_%d.sav", android_base_path,
                    SAVE_DIR, automatic ? "auto" : "", current_slot_id);
#endif
}

// DELBkup removes SAVE_DIR "/" SAVE_FILE, a path relative to a working directory an app
// does not have, so on Android it removed nothing and said nothing. Every remove in this
// library is linked here (-Wl,--wrap=remove in CMakeLists.txt): that one removes the
// slot's SAVE and automatic save, both of which the hardware's DELBkup deletes. Any other
// path is removed as remove() would, so a build that links the glue without the wrap
// needs nothing from it.
int __wrap_remove(const char *path) {
  if (strcmp(path, SAVE_DIR "/" SAVE_FILE) != 0 || !has_android_base_path()) {
    if (unlink(path) == 0) {
      return 0;
    }
    return errno == EISDIR || errno == EPERM ? rmdir(path) : -1;
  }
  int result = 0;
  for (int automatic = 0; automatic <= 1; automatic++) {
    char save[1024];
    if (!savePath(save, sizeof(save), automatic)) {
      result = -1;
    } else if (unlink(save) != 0 && errno != ENOENT) {
      result = -1;
    }
  }
  return result;
}

static void ensure_android_subdir(const char *subdir) {
  char buf[1024];

  if (!android_base_path_ready) {
    return;
  }

  if (r47_format(buf, sizeof(buf), "%s/%s", android_base_path, subdir)) {
    mkdir(buf, 0777);
  }
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
  openedFileSeekFailed = false;
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

    // The name the picker offers; a program's label too long for it gives the
    // generic name.
    bool named = false;
    if (path == ioPathSaveStateFile || path == ioPathLoadStateFile) {
      named = r47_format(defaultName, sizeof(defaultName), "%s", "state.s47");
    } else if (path == ioPathManualSave) {
#if (CALCMODEL == USER_R47)
      named = r47_format(defaultName, sizeof(defaultName), "%s", "R47.sav");
#else
      named = r47_format(defaultName, sizeof(defaultName), "%s", "C47.sav");
#endif
    } else if (path == ioPathRegExport || path == ioPathRegImport) {
      named = r47_format(defaultName, sizeof(defaultName), "%s", "data.d47");
    } else if (tmpStringLabelOrVariableName &&
               tmpStringLabelOrVariableName[0] != 0) {
      char asciiName[256];
      stringToASCII(tmpStringLabelOrVariableName, asciiName);
      named =
          r47_format(defaultName, sizeof(defaultName), "%s%s", asciiName, ext);
    }
    if (!named &&
        !r47_format(defaultName, sizeof(defaultName), "program%s", ext)) {
      return FILE_ERROR;
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

  bool named = false;
  switch (path) {
  case ioPathManualSave:
    named = savePath(fullpath, sizeof(fullpath), false);
    break;
  case ioPathAutoSave:
    named = savePath(fullpath, sizeof(fullpath), true);
    break;
  case ioPathBackup:
#if (CALCMODEL == USER_R47)
    named = r47_format(fullpath, sizeof(fullpath), "%s/backupR47_%d.cfg",
                       android_base_path, current_slot_id);
#else
    named = r47_format(fullpath, sizeof(fullpath), "%s/backup_%d.cfg",
                       android_base_path, current_slot_id);
#endif
    break;
  case ioPathPgmFile:
  case ioPathSaveProgram:
  case ioPathLoadProgram:
    named = r47_format(fullpath, sizeof(fullpath), "%s/%s/program.p47",
                       android_base_path, PROGRAMS_DIR);
    break;
  default:
    named = r47_format(fullpath, sizeof(fullpath), "%s/default.dat",
                       android_base_path);
    break;
  }
  if (!named) {
    return FILE_ERROR;
  }

  if (mode != ioModeRead) {
    char dir[1024];
    if ((path == ioPathManualSave || path == ioPathAutoSave) &&
        r47_format(dir, sizeof(dir), "%s/%s", android_base_path, SAVE_DIR)) {
      mkdir(dir, 0777);
    } else if ((path == ioPathPgmFile || path == ioPathSaveProgram ||
                path == ioPathLoadProgram) &&
               r47_format(dir, sizeof(dir), "%s/%s", android_base_path,
                          PROGRAMS_DIR)) {
      mkdir(dir, 0777);
    }
  }

  // The state file is the only copy of the user's work, and a save can be killed at
  // any point, so it is written beside the file and renamed over it. A save that
  // cannot start is canceled, because saveCalc ends the process on an error, and the
  // file it would have replaced stays good.
  if (path == ioPathBackup && mode == ioModeWrite) {
    if (!r47_format(replaceTarget, sizeof(replaceTarget), "%s", fullpath) ||
        !r47_format(replaceTemp, sizeof(replaceTemp), "%s.tmp", fullpath)) {
      replaceTarget[0] = '\0';
      r47_report_file_write_failure();
      return FILE_CANCEL;
    }
    openedFile = fopen(replaceTemp, modeStr);
    openedFileWritable = openedFile != NULL;
    openedFileWriteFailed = false;
    openedFileSeekFailed = false;
    if (!openedFile) {
      LOGE("ioFileOpen: cannot write %s: %s", replaceTemp, strerror(errno));
      replaceTarget[0] = '\0';
      r47_report_file_write_failure();
      return FILE_CANCEL;
    }
    return FILE_OK;
  }

  if (path == ioPathBackup && mode == ioModeRead &&
      setAsideUnusableStateFile(fullpath)) {
    return FILE_CANCEL;
  }

  openedFile = fopen(fullpath, modeStr);
  openedFileWritable = openedFile != NULL && mode != ioModeRead;
  openedFileWriteFailed = false;
  openedFileSeekFailed = false;
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
  if (openedFile && !openedFileSeekFailed)
    return fread(buffer, 1, size, openedFile);
  return 0;
}

// A seek that fails would leave the next read at the wrong place, so the file reads as
// ended from there, and a save through it counts as failed.
void ioFileSeek(uint32_t position) {
  if (openedFile && fseek(openedFile, position, SEEK_SET) != 0) {
    LOGE("ioFileSeek: cannot reach %u: %s", position, strerror(errno));
    openedFileSeekFailed = true;
    openedFileWriteFailed = openedFileWritable;
  }
}

// Why the core cannot take the state file at path, or NULL when it can, or when the file
// cannot be read, which upstream's restore reports itself. The file is upstream's
// "name:type:value" lines, each hexDump followed by its bytes at 32 to a line, so a file
// cut short is one whose last dump runs out or whose last line has no end. That holds
// for every file a past core wrote, so nothing needs a trailer, and a field this check
// does not know passes.
static const char *stateFileProblem(const char *path, long *version) {
  FILE *file = fopen(path, "rb");
  if (file == NULL) {
    return NULL;
  }
  long ramBlocks = -1;
  bool sawRam = false;
  bool lineEnded = true;
  unsigned long dumpBytesLeft = 0;
  char *line = NULL;
  size_t capacity = 0;
  ssize_t length;
  const char *problem = NULL;
  *version = -1;
  while (problem == NULL && (length = getline(&line, &capacity, file)) > 0) {
    lineEnded = line[length - 1] == '\n';
    while (length > 0 &&
           (line[length - 1] == '\n' || line[length - 1] == '\r')) {
      line[--length] = '\0';
    }
    if (dumpBytesLeft > 0) {
      const unsigned long bytes = dumpBytesLeft < 32 ? dumpBytesLeft : 32;
      if ((unsigned long)length < 7 + 3 * bytes - 1) {
        problem = "it is cut short";
      }
      dumpBytesLeft -= bytes;
      continue;
    }
    char *type = strchr(line, ':');
    char *value = type == NULL ? NULL : strchr(type + 1, ':');
    if (value == NULL) {
      continue; // upstream's restore skips a line it cannot read
    }
    *type++ = '\0';
    *value++ = '\0';
    if (strcmp(type, "hexDump") == 0) {
      dumpBytesLeft = strtoul(value, NULL, 10);
      sawRam = sawRam || strcmp(line, "ram") == 0;
    } else if (strcmp(line, "backupVersion") == 0) {
      *version = strtol(value, NULL, 10);
    } else if (strcmp(line, "ramSizeInBlocks") == 0) {
      ramBlocks = strtol(value, NULL, 10);
    }
  }
  const bool readFailed = ferror(file) != 0;
  free(line);
  if (fclose(file) != 0) {
    LOGE("stateFileProblem: cannot close %s: %s", path, strerror(errno));
  }
  if (problem != NULL || readFailed) {
    return problem;
  }
  if (dumpBytesLeft > 0 || !lineEnded || !sawRam) {
    return "it is cut short";
  }
  if (*version < R47_BACKUP_VERSION_FLOOR) {
    return "it is from a version too old to read";
  }
  if (*version > R47_BACKUP_VERSION) {
    return "it is from a newer version";
  }
  if (ramBlocks != RAM_SIZE_IN_BLOCKS) {
    return "its memory size does not match";
  }
  return NULL;
}

// Move path aside as <path>.<tag>.<time>, never over a copy kept before it.
static bool keepAside(const char *path, const char *tag, char *kept,
                      size_t size) {
  char stamp[32];
  struct tm when;
  const time_t now = time(NULL);
  if (strftime(stamp, sizeof(stamp), "%Y%m%d-%H%M%S",
               localtime_r(&now, &when)) == 0) {
    stamp[0] = '0'; // no time to name it by: the attempts still keep it apart
    stamp[1] = '\0';
  }
  for (int attempt = 0; attempt < 100; attempt++) {
    const bool named =
        attempt == 0
            ? r47_format(kept, size, "%s.%s.%s", path, tag, stamp)
            : r47_format(kept, size, "%s.%s.%s-%d", path, tag, stamp, attempt);
    if (!named) {
      return false;
    }
    if (link(path, kept) == 0) {
      unlink(path);
      return true;
    }
    if (errno != EEXIST) {
      return false;
    }
  }
  return false;
}

// A state file the core cannot take would be restored as garbage, or refused and then
// overwritten by the next save. Move it aside under a name the next save does not touch
// and say so; the restore then finds no file and starts from a reset.
static bool setAsideUnusableStateFile(const char *path) {
  long version;
  const char *problem = stateFileProblem(path, &version);
  if (problem == NULL) {
    return false;
  }
  // A long holds at most 20 digits, so the tag fits; an empty one still names the copy.
  char tag[24];
  r47_format(tag, sizeof(tag), "v%ld", version < 0 ? 0 : version);
  char kept[PATH_MAX + 64] = "";
  char warning[160];
  if (keepAside(path, tag, kept, sizeof(kept))) {
    r47_format(warning, sizeof(warning),
               "The saved state was not restored: %s. A copy is kept, and the "
               "calculator starts afresh.",
               problem);
  } else {
    LOGE("ioFileOpen: cannot keep %s: %s", path, strerror(errno));
    r47_format(warning, sizeof(warning),
               "The saved state was not restored: %s.", problem);
  }
  LOGE("%s (%s)", warning, kept);
  r47_show_warning(warning);
  return true;
}

// A state file can crash its own restore, through a value the structure check cannot
// judge, and then every launch crashes the same way, since nothing replaces the file
// before the crash. A marker beside it counts the starts that began a restore and did
// not finish it and the first refresh of what it restored. A start that finds two such
// starts sets the file aside and begins afresh. One is not enough: a start can also die
// because the user closed the app, and that file is good. The marker is gone before an
// AUTXEQ program or anything else the user started runs.
#define R47_FAILED_RESTORES_BEFORE_SET_ASIDE 2
static char restoreMarker[PATH_MAX];

void r47_restore_guard_begin(void) {
  if (!has_android_base_path()) {
    return;
  }
  char state[PATH_MAX];
#if (CALCMODEL == USER_R47)
  const bool named = r47_format(state, sizeof(state), "%s/backupR47_%d.cfg",
                                android_base_path, current_slot_id);
#else
  const bool named = r47_format(state, sizeof(state), "%s/backup_%d.cfg",
                                android_base_path, current_slot_id);
#endif
  if (!named || !r47_format(restoreMarker, sizeof(restoreMarker),
                            "%s.restoring", state)) {
    return; // no guard: the restore runs as it would without one
  }

  int failed = 0;
  FILE *marker = fopen(restoreMarker, "r");
  if (marker != NULL) {
    if (fscanf(marker, "%d", &failed) != 1) {
      failed = 0;
    }
    if (fclose(marker) != 0) {
      LOGE("restore guard: cannot close %s: %s", restoreMarker,
           strerror(errno));
    }
  }
  if (failed >= R47_FAILED_RESTORES_BEFORE_SET_ASIDE) {
    char kept[PATH_MAX + 64];
    if (keepAside(state, "crashed", kept, sizeof(kept))) {
      LOGE("restore failed %d times: kept %s", failed, kept);
      r47_show_warning("The saved state did not restore, twice. A copy is "
                       "kept, and the calculator starts afresh.");
    }
    failed = 0;
  }
  // A marker not written leaves this start uncounted, so a state file that crashes
  // its restore is set aside one start later; it is logged, not fatal.
  marker = fopen(restoreMarker, "w");
  if (marker != NULL) {
    const bool written = fprintf(marker, "%d\n", failed + 1) > 0;
    if (fclose(marker) != 0 || !written) {
      LOGE("restore guard: cannot write %s", restoreMarker);
    }
  }
}

// The calculator thread, once the restored state has been refreshed on screen.
void r47_restore_guard_end(void) {
  if (restoreMarker[0] != '\0') {
    unlink(restoreMarker);
    restoreMarker[0] = '\0';
  }
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
    char *slash = r47_format(dir, sizeof(dir), "%s", replaceTarget)
                      ? strrchr(dir, '/')
                      : NULL;
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
  openedFileSeekFailed = false;
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
  if (!r47_format(snapPath, sizeof(snapPath), "%s/snap.bmp",
                  android_base_path)) {
    return; // no SNAP armed: the capture goes where upstream sends it
  }
  unlink(snapPath);
  r47_format(_ioFileNameOverride, PATH_MAX, "%s", snapPath);
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
  // fnScreenDump writes the file's size into its header before the pixels, so a
  // capture a full disk cut short says how long it should have been, and is not offered.
  unsigned char header[6] = {0};
  long size = -1;
  struct stat st;
  if (capture && fread(header, 1, sizeof(header), capture) == sizeof(header) &&
      header[0] == 'B' && header[1] == 'M' &&
      fstat(fileno(capture), &st) == 0) {
    size = (long)st.st_size;
  }
  const uint32_t declared = (uint32_t)header[2] | (uint32_t)header[3] << 8 |
                            (uint32_t)header[4] << 16 |
                            (uint32_t)header[5] << 24;
  if (capture && (size < 0 || (uint32_t)size != declared)) {
    LOGE("r47_offer_snap: capture holds %ld bytes of %u", size, declared);
    r47_show_warning("The screen capture was not written in full, so it is not "
                     "offered.");
  } else if (capture && fseek(capture, 0, SEEK_SET) == 0) {
    char name[32];
    struct tm when;
    time_t now = time(NULL);
    if (strftime(name, sizeof(name), "%Y%m%d-%H%M%S00.bmp",
                 localtime_r(&now, &when)) == 0) {
      r47_format(name, sizeof(name), "%s", "snap.bmp"); // no time to name it by
    }
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
  }
  if (capture) {
    if (fclose(capture) != 0) {
      LOGE("r47_offer_snap: cannot close the capture: %s", strerror(errno));
    }
    unlink(snapPath);
  }
  r47_format(_ioFileNameOverride, PATH_MAX, "%s", snapPath);
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
// A core warning, such as a program or a state file it could not take whole, goes to
// the user in a dialog.
void show_warning(char *string) { r47_show_warning(string); }
void fnDiskInfo(uint16_t unused) {}
