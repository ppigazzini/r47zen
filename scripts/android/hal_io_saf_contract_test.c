// Host regression guard for the SAF branch of hal/io.c: each file path asks the
// host for the name and folder it should, and a save through a pipe arrives whole
// with nothing reported.
//
// It also holds the SNAP offer: a finished capture goes to the picker once, and
// whatever the answer the private file is gone and the next SNAP has a name. A SNAP
// here is what clipboard_overflow_harness.c measures fnSNAP doing on the real core.
//
// Built without HOST_TOOL_BUILD, so ioFileOpen takes the branch the app takes;
// requestAndroidFile stands in for the picker. Built and run on the Linux host by
// run_hal_io_contract.sh; no emulator or device is required.

#include "c47.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern char _ioFileNameOverride[];
extern void set_android_base_path(const char *path);
extern void r47_arm_snap(void);
extern void r47_offer_snap(void);

char *tmpStringLabelOrVariableName;
static char labelStorage[32];

void stringToASCII(const char *in, char *out) { strcpy(out, in); }

static int reports = 0;
// show_warning in hal/io.c reports through it, and so does a capture cut short; the
// bridge's real one calls into MainActivity.
static char warned[256] = "";
static int warnings = 0;
void r47_show_warning(const char *text) {
  snprintf(warned, sizeof(warned), "%s", text);
  warnings++;
}

void r47_report_file_write_failure(void) { reports++; }

// The core's record of a read's file name, which hal/io.c writes, and the name the
// picker answered with, which jni_storage.c gives it.
char fileNameSelected[stateFileNameVarLength];
static char pickedName[256];
const char *r47_picked_file_name(void) { return pickedName; }

static int requestIsSave;
static char requestName[256];
static int requestCategory;
static int requestAnswer = -1;
static int requests = 0;

int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
  requests++;
  requestIsSave = isSave;
  snprintf(requestName, sizeof(requestName), "%s", defaultName);
  requestCategory = fileType;
  return requestAnswer;
}

static int failures = 0;

static void expect_request(ioFilePath_t path, ioFileMode_t mode,
                           const char *label, const char *name, int category) {
  snprintf(labelStorage, sizeof(labelStorage), "%s", label);
  tmpStringLabelOrVariableName = labelStorage;
  requestAnswer = -1;
  requestName[0] = '\0';
  requestCategory = -1;
  int result = ioFileOpen(path, mode);
  int wantSave = mode == ioModeWrite;
  if (result != FILE_CANCEL || requestIsSave != wantSave ||
      strcmp(requestName, name) != 0 || requestCategory != category) {
    fprintf(stderr,
            "FAIL: path %d: result %d, save %d, '%s' in folder %d; want a "
            "canceled request, save %d, '%s' in folder %d\n",
            path, result, requestIsSave, requestName, requestCategory, wantSave,
            name, category);
    failures++;
  } else {
    printf("OK: path %d asks for '%s' in folder %d\n", path, name, category);
  }
}

// A state file picked for a read leaves its name, the last 19 bytes of it, in
// fileNameSelected, as GTK's ioFileOpen records it; a save leaves none.
static void expect_name_recorded(ioFilePath_t path, ioFileMode_t mode,
                                 const char *picked, const char *recorded) {
  snprintf(pickedName, sizeof(pickedName), "%s", picked);
  snprintf(fileNameSelected, sizeof(fileNameSelected), "%s", "stale");
  requestAnswer = open("/dev/null", mode == ioModeRead ? O_RDONLY : O_WRONLY);
  const int result = ioFileOpen(path, mode);
  ioFileClose();
  requestAnswer = -1;
  if (result != FILE_OK || strcmp(fileNameSelected, recorded) != 0) {
    fprintf(stderr, "FAIL: path %d of '%s' recorded '%s', result %d; want '%s'\n",
            path, picked, fileNameSelected, result, recorded);
    failures++;
  } else {
    printf("OK: path %d of '%s' records '%s'\n", path, picked, recorded);
  }
}

// WRXPall's bulk export writes one file per program; on Android each would open the
// picker, two per program, and a cancel would not stop the loop. The HAL refuses it, as
// the hardware refuses the item, so the loop asks for nothing.
// Fail unless path is refused at once, with a work directory that takes files:
// no picker request and no file written in it, where a path the SAF branch does
// not take would go on to write the directory's default.dat.
static void expect_refused_without_a_picker(ioFilePath_t path) {
  char base[] = "/tmp/hal-io-refused-XXXXXX";
  if (mkdtemp(base) == NULL) {
    fprintf(stderr, "FATAL: mkdtemp: %s\n", strerror(errno));
    exit(2);
  }
  set_android_base_path(base);
  char written[sizeof(base) + 32];
  snprintf(written, sizeof(written), "%s/default.dat", base);

  const int requestsBefore = requests;
  const int result = ioFileOpen(path, ioModeWrite);
  ioFileClose();
  struct stat info;
  const bool wroteAFile = stat(written, &info) == 0;
  if (result != FILE_ERROR || requests != requestsBefore || wroteAFile) {
    fprintf(stderr, "FAIL: path %d: result %d after %d picker request(s)%s; want "
                    "FILE_ERROR, none, and no file\n",
            path, result, requests - requestsBefore,
            wroteAFile ? ", and wrote default.dat" : "");
    failures++;
  } else {
    printf("OK: path %d is refused without a picker or a file\n", path);
  }
  unlink(written);
  rmdir(base);
}

// Fail unless the capture is gone, the next SNAP has its name, and the offer asked
// and reported as many times as it should have.
static void expect_snap_settled(const char *what, const char *capture,
                                int wantRequests, int wantReports) {
  if (access(capture, F_OK) == 0 || strcmp(_ioFileNameOverride, capture) != 0 ||
      requests != wantRequests || reports != wantReports) {
    fprintf(stderr,
            "FAIL: %s: file %s, name '%s', %d request(s), %d report(s); want %d "
            "and %d\n",
            what, access(capture, F_OK) == 0 ? "kept" : "gone",
            _ioFileNameOverride, requests, reports, wantRequests, wantReports);
    failures++;
  } else {
    printf("OK: %s\n", what);
  }
  requests = 0;
  reports = 0;
}

// What fnSNAP leaves behind: the capture at the name it was given, and no name.
static void take_snap_bytes(const char *content, size_t length) {
  FILE *capture = fopen(_ioFileNameOverride, "wb");
  if (capture == NULL || fwrite(content, 1, length, capture) != length ||
      fclose(capture) != 0) {
    fprintf(stderr, "FATAL: cannot write '%s': %s\n", _ioFileNameOverride,
            strerror(errno));
    exit(2);
  }
  _ioFileNameOverride[0] = '\0';
}

static void take_snap(const char *content) { take_snap_bytes(content, strlen(content)); }

// A capture of length bytes whose header, as fnScreenDump writes it, says it holds
// declared bytes.
static void write_bitmap(char *content, size_t length, uint32_t declared) {
  memset(content, 'x', length);
  content[0] = 'B';
  content[1] = 'M';
  for (int i = 0; i < 4; i++) {
    content[2 + i] = (char)(declared >> (8 * i));
  }
}

static void check_snap(void) {
  static char bitmap[11];
  write_bitmap(bitmap, sizeof(bitmap), sizeof(bitmap));
  char base[] = "/tmp/hal-io-snap-XXXXXX";
  if (mkdtemp(base) == NULL) {
    fprintf(stderr, "FATAL: mkdtemp: %s\n", strerror(errno));
    exit(2);
  }
  set_android_base_path(base);
  char capture[sizeof(base) + 16];
  snprintf(capture, sizeof(capture), "%s/snap.bmp", base);
  requests = 0;
  reports = 0;

  r47_offer_snap();
  if (requests != 0 || _ioFileNameOverride[0] != '\0') {
    fprintf(stderr, "FAIL: an offer ran before anything was armed\n");
    failures++;
  }

  snprintf(_ioFileNameOverride, sizeof(capture), "%s", capture);
  take_snap("left by a process that died");
  r47_arm_snap();
  expect_snap_settled("arming drops a capture a dead process left", capture, 0, 0);

  r47_offer_snap();
  expect_snap_settled("an armed name asks for nothing", capture, 0, 0);

  // The name going empty is the signal, not the file being there.
  FILE *present = fopen(capture, "wb");
  if (present == NULL || fclose(present) != 0) {
    fprintf(stderr, "FATAL: cannot write '%s': %s\n", capture, strerror(errno));
    exit(2);
  }
  r47_offer_snap();
  if (requests != 0 || access(capture, F_OK) != 0) {
    fprintf(stderr, "FAIL: an armed name offered the file beside it\n");
    failures++;
  }
  unlink(capture);

  // A short capture, the size SNAP writes, and exact multiples of the copy's chunk.
  static const size_t sizes[] = {sizeof(bitmap), 12610, 4096, 8192};
  for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
    static char content[16384];
    static char received[sizeof(content)];
    write_bitmap(content, sizes[i], (uint32_t)sizes[i]);
    int ends[2];
    if (pipe(ends) != 0) {
      fprintf(stderr, "FATAL: pipe: %s\n", strerror(errno));
      exit(2);
    }
    take_snap_bytes(content, sizes[i]);
    requestAnswer = ends[1];
    const int requestsBefore = requests;
    r47_offer_snap();
    if (requests == requestsBefore) {
      close(ends[1]); // nothing took the picker's end, so nothing will close it
    }
    ssize_t count = read(ends[0], received, sizeof(received) - 1);
    char extra;
    ssize_t atEnd = read(ends[0], &extra, 1);
    close(ends[0]);
    size_t nameLength = strlen(requestName);
    if (count != (ssize_t)sizes[i] || memcmp(received, content, sizes[i]) != 0 ||
        atEnd != 0 || requestIsSave != 1 || requestCategory != 3 ||
        nameLength != strlen("20261106-14000000.bmp") ||
        strcmp(requestName + nameLength - 6, "00.bmp") != 0) {
      fprintf(stderr,
              "FAIL: a capture of %zu bytes sent %zd, end %zd, as save %d of "
              "'%s' in folder %d\n",
              sizes[i], count, atEnd, requestIsSave, requestName,
              requestCategory);
      failures++;
    }
    expect_snap_settled("a capture reaches the picker's file as a BMP in SCREENS",
                        capture, 1, 0);
  }

  // A capture the disk cut short: its header names more bytes than the file holds.
  static char cut[4096];
  write_bitmap(cut, sizeof(cut), 12610);
  take_snap_bytes(cut, sizeof(cut));
  requestAnswer = -1;
  warnings = 0;
  r47_offer_snap();
  expect_snap_settled("a capture cut short asks for nothing", capture, 0, 0);
  if (warnings != 1) {
    fprintf(stderr, "FAIL: a capture cut short gave %d warnings, not 1\n", warnings);
    failures++;
  }

  take_snap_bytes(bitmap, sizeof(bitmap));
  requestAnswer = -1;
  r47_offer_snap();
  expect_snap_settled("a canceled picker drops the capture", capture, 1, 0);

  take_snap_bytes(bitmap, sizeof(bitmap));
  requestAnswer = open("/dev/full", O_WRONLY);
  r47_offer_snap();
  expect_snap_settled("a capture the file refuses is reported", capture, 1, 1);

  _ioFileNameOverride[0] = '\0';
  requestAnswer = -1;
  r47_offer_snap();
  expect_snap_settled("a capture the core could not write offers nothing", capture,
                      0, 0);

  static const char *const folders[] = {STATE_DIR, PROGRAMS_DIR, SAVE_DIR};
  for (size_t i = 0; i < sizeof(folders) / sizeof(folders[0]); i++) {
    char folder[sizeof(base) + 32];
    snprintf(folder, sizeof(folder), "%s/%s", base, folders[i]);
    rmdir(folder);
  }
  rmdir(base);
}

int main(void) {
  check_snap();

  expect_request(ioPathRegExport, ioModeWrite, "", "data.d47", 4);
  expect_request(ioPathRegImport, ioModeRead, "", "data.d47", 4);
  expect_request(ioPathSaveStateFile, ioModeWrite, "", "state.s47", 0);
  expect_request(ioPathLoadStateFile, ioModeRead, "", "state.s47", 0);
  expect_request(ioPathManualSave, ioModeWrite, "", "R47.sav", 2);
  expect_request(ioPathSaveProgram, ioModeWrite, "PRIME", "PRIME.p47", 1);
  expect_request(ioPathSaveProgram, ioModeWrite, "", "program.p47", 1);
  expect_request(ioPathLoadProgram, ioModeRead, "", "program.p47", 1);
  expect_request(ioPathExportRTFProgram, ioModeWrite, "PRIME", "PRIME.rtf", 1);
  expect_name_recorded(ioPathLoadStateFile, ioModeRead, "mystate.s47", "mystate.s47");
  expect_name_recorded(ioPathLoadStateFile, ioModeRead,
                       "a-very-long-state-file-name.s47", "state-file-name.s47");
  expect_name_recorded(ioPathSaveStateFile, ioModeWrite, "mystate.s47", "");
  expect_refused_without_a_picker(ioPathSaveAllPrograms);
  expect_refused_without_a_picker(ioPathExportRTFAllPrograms);

  int ends[2];
  if (pipe(ends) != 0) {
    fprintf(stderr, "FATAL: pipe: %s\n", strerror(errno));
    return 2;
  }

  // A save writes the pipe DocumentTransfer drains. fsync on it answers EINVAL,
  // which loses nothing, so a whole save reports nothing.
  static const char payload[] = "DATA_FILE_REVISION\n0\n";
  requestAnswer = ends[1];
  reports = 0;
  if (ioFileOpen(ioPathRegExport, ioModeWrite) != FILE_OK) {
    fprintf(stderr, "FAIL: a save to a pipe was refused\n");
    failures++;
  } else {
    ioFileWrite(payload, sizeof(payload) - 1);
    ioFileClose();
    char received[sizeof(payload)] = {0};
    ssize_t count = read(ends[0], received, sizeof(received));
    char extra;
    ssize_t atEnd = read(ends[0], &extra, 1);
    if (count != (ssize_t)sizeof(payload) - 1 ||
        strcmp(received, payload) != 0 || atEnd != 0 || reports != 0) {
      fprintf(stderr,
              "FAIL: the pipe got %zd bytes, end %zd, with %d report(s)\n",
              count, atEnd, reports);
      failures++;
    } else {
      printf("OK: a save through a pipe arrives whole and reports nothing\n");
    }
  }
  close(ends[0]);

  return failures == 0 ? 0 : 1;
}
