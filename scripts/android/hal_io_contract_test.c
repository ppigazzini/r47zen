// Host regression guard for the write-failure contract of hal/io.c: a write the
// file system did not take must be reported to the host exactly once per file,
// at ioFileClose, and a write it did take must not be.
//
// Upstream's HAL signatures return nothing, and upstream reports only a failed
// open, so without this the Android HAL let a full disk or a revoked document
// grant truncate a save or an autosave in silence. /dev/full stands in for the
// full disk: a write to it fails with ENOSPC. /dev/null stands in for a SAF
// pipe: it takes every write but answers fsync with EINVAL, which loses nothing.
//
// Built with HOST_TOOL_BUILD, which compiles out the SAF branch of ioFileOpen,
// so every case goes through the base-path branch: the autosave file is made a
// symbolic link to the device under test. Built and run on the Linux host by
// run_hal_io_contract.sh; no emulator or device is required.

#include "c47.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern void set_android_base_path(const char *path);

static int reports = 0;

// The host side of the report: the bridge's real one calls into MainActivity.
void r47_report_file_write_failure(void) {
  reports++;
}

static char base[256];
static char save_dir[512];
static char autosave[600];

static void point_autosave_at(const char *target) {
  unlink(autosave);
  if (target != NULL && symlink(target, autosave) != 0) {
    fprintf(stderr, "FATAL: symlink %s -> %s: %s\n", autosave, target,
            strerror(errno));
    exit(2);
  }
}

static int write_autosave(size_t bytes) {
  static char payload[1 << 17];
  memset(payload, 'x', sizeof(payload));
  reports = 0;
  if (ioFileOpen(ioPathAutoSave, ioModeWrite) != FILE_OK) {
    fprintf(stderr, "FATAL: ioFileOpen(ioPathAutoSave) failed\n");
    exit(2);
  }
  size_t left = bytes;
  while (left > 0) {
    const size_t chunk = left < sizeof(payload) ? left : sizeof(payload);
    ioFileWrite(payload, (uint32_t)chunk);
    left -= chunk;
  }
  ioFileClose();
  return reports;
}

static int expect_reports(const char *what, int actual, int expected) {
  if (actual != expected) {
    fprintf(stderr, "FAIL: %s: expected %d report(s), got %d\n", what,
            expected, actual);
    return 1;
  }
  printf("OK: %s (%d report(s))\n", what, actual);
  return 0;
}

int main(void) {
  snprintf(base, sizeof(base), "/tmp/r47-hal-io-contract-%ld", (long)getpid());
  mkdir(base, 0700);
  set_android_base_path(base);
  snprintf(save_dir, sizeof(save_dir), "%s/%s", base, SAVE_DIR);
  snprintf(autosave, sizeof(autosave), "%s/R47auto_0.sav", save_dir);

  int failures = 0;

  point_autosave_at(NULL);
  failures |= expect_reports("a write the disk takes is not reported",
                             write_autosave(200000), 0);
  struct stat written;
  if (stat(autosave, &written) != 0 || written.st_size != 200000) {
    fprintf(stderr, "FAIL: the regular autosave holds %lld bytes, not 200000\n",
            (long long)written.st_size);
    failures |= 1;
  }

  reports = 0;
  if (ioFileOpen(ioPathAutoSave, ioModeRead) != FILE_OK) {
    fprintf(stderr, "FATAL: ioFileOpen(ioPathAutoSave, read) failed\n");
    return 2;
  }
  ioFileClose();
  failures |= expect_reports("closing a file opened for reading is not reported",
                             reports, 0);

  point_autosave_at("/dev/full");
  failures |= expect_reports(
      "a short write caught at the close flush is reported once",
      write_autosave(16), 1);
  failures |= expect_reports(
      "short writes caught during the writes are reported once, not per write",
      write_autosave(3u << 17), 1);

  point_autosave_at("/dev/null");
  failures |= expect_reports(
      "an fsync that answers EINVAL loses nothing and is not reported",
      write_autosave(4096), 0);

  point_autosave_at(NULL);
  rmdir(save_dir);
  rmdir(base);
  return failures;
}
