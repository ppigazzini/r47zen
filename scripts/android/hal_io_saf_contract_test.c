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
#include <unistd.h>

extern char _ioFileNameOverride[];
extern void set_android_base_path(const char *path);
extern void r47_arm_snap(void);
extern void r47_offer_snap(void);

char *tmpStringLabelOrVariableName;
static char labelStorage[32];

void stringToASCII(const char *in, char *out) { strcpy(out, in); }

static int reports = 0;
// The host side of a core warning: the bridge's real one calls into MainActivity.
static char warned[256] = "";
void r47_show_warning(const char *text) {
  snprintf(warned, sizeof(warned), "%s", text);
}

void r47_report_file_write_failure(void) { reports++; }

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
static void take_snap(const char *content) {
  FILE *capture = fopen(_ioFileNameOverride, "wb");
  if (capture == NULL || fputs(content, capture) < 0 || fclose(capture) != 0) {
    fprintf(stderr, "FATAL: cannot write '%s': %s\n", _ioFileNameOverride,
            strerror(errno));
    exit(2);
  }
  _ioFileNameOverride[0] = '\0';
}

static void check_snap(void) {
  static const char bitmap[] = "BM a screen";
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
  static const size_t sizes[] = {sizeof(bitmap) - 1, 12610, 4096, 8192};
  for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
    static char content[16384];
    static char received[sizeof(content)];
    memset(content, 'x', sizes[i]);
    memcpy(content, bitmap, sizeof(bitmap) - 1);
    content[sizes[i]] = '\0';
    int ends[2];
    if (pipe(ends) != 0) {
      fprintf(stderr, "FATAL: pipe: %s\n", strerror(errno));
      exit(2);
    }
    take_snap(content);
    requestAnswer = ends[1];
    r47_offer_snap();
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

  take_snap(bitmap);
  requestAnswer = -1;
  r47_offer_snap();
  expect_snap_settled("a canceled picker drops the capture", capture, 1, 0);

  take_snap(bitmap);
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
