// Host regression guard for the SAF branch of hal/io.c: each file path asks the
// host for the name and folder it should, a save through a pipe arrives whole with
// nothing reported, and a load refuses a descriptor that cannot seek. READP reads a
// file twice, and over a pipe it would load a program of zeros as a success.
//
// Built without HOST_TOOL_BUILD, so ioFileOpen takes the branch the app takes;
// requestAndroidFile stands in for the picker. Built and run on the Linux host by
// run_hal_io_contract.sh; no emulator or device is required.

#include "c47.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

char *tmpStringLabelOrVariableName;
static char labelStorage[32];

void stringToASCII(const char *in, char *out) { strcpy(out, in); }

static int reports = 0;
void r47_report_file_write_failure(void) { reports++; }

static int requestIsSave;
static char requestName[256];
static int requestCategory;
static int requestAnswer = -1;

int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
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

int main(void) {
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

  if (pipe(ends) != 0) {
    fprintf(stderr, "FATAL: pipe: %s\n", strerror(errno));
    return 2;
  }
  requestAnswer = ends[0];
  if (ioFileOpen(ioPathLoadProgram, ioModeRead) != FILE_ERROR) {
    fprintf(stderr, "FAIL: a load from a pipe was not refused\n");
    failures++;
  } else {
    printf("OK: a load from a pipe is refused\n");
  }
  close(ends[1]);

  return failures == 0 ? 0 : 1;
}
