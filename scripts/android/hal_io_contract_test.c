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

// r47_offer_snap asks for a file; hal_io_saf_contract_test.c holds that to account.
int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
  (void)isSave;
  (void)defaultName;
  (void)fileType;
  return -1;
}

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

// The state file is the one private file that holds the user's work: a save that
// fails or cannot start must leave the file it would have replaced as it was.
static char backup[600];
static char backup_temp[640];

static void write_text(const char *path, const char *text) {
  FILE *file = fopen(path, "w");
  if (file == NULL || fputs(text, file) < 0 || fclose(file) != 0) {
    fprintf(stderr, "FATAL: cannot write %s\n", path);
    exit(2);
  }
}

static int holds(const char *path, const char *text) {
  char seen[64] = "";
  FILE *file = fopen(path, "r");
  if (file == NULL) {
    return 0;
  }
  const size_t got = fread(seen, 1, sizeof(seen) - 1, file);
  fclose(file);
  seen[got] = '\0';
  return strcmp(seen, text) == 0;
}

static int expect(const char *what, int ok) {
  printf("%s: %s\n", ok ? "OK" : "FAIL", what);
  return ok ? 0 : 1;
}

static int save_state(const char *text) {
  reports = 0;
  const int opened = ioFileOpen(ioPathBackup, ioModeWrite);
  if (opened == FILE_OK) {
    ioFileWrite(text, (uint32_t)strlen(text));
    ioFileClose();
  }
  return opened;
}

static int check_state_file_replacement(void) {
  int result = 0;
  write_text(backup, "old");
  result |= expect("a save opens", save_state("new") == FILE_OK);
  result |= expect("a save replaces the state file whole",
                   holds(backup, "new") && access(backup_temp, F_OK) != 0);
  result |= expect_reports("a save the disk takes is not reported", reports, 0);

  write_text(backup, "old");
  if (symlink("/dev/full", backup_temp) != 0) {
    fprintf(stderr, "FATAL: symlink %s: %s\n", backup_temp, strerror(errno));
    exit(2);
  }
  save_state("new");
  result |= expect("a save that fails keeps the old state file",
                   holds(backup, "old"));
  result |= expect_reports("a save that fails is reported once", reports, 1);
  unlink(backup_temp);

  write_text(backup, "old");
  mkdir(backup_temp, 0700);
  result |= expect("a save that cannot start is canceled",
                   save_state("new") == FILE_CANCEL);
  result |= expect("a save that cannot start keeps the old state file",
                   holds(backup, "old"));
  result |= expect_reports("a save that cannot start is reported once", reports, 1);
  rmdir(backup_temp);
  unlink(backup);
  return result;
}

int main(void) {
  snprintf(base, sizeof(base), "/tmp/r47-hal-io-contract-%ld", (long)getpid());
  mkdir(base, 0700);
  set_android_base_path(base);
  snprintf(save_dir, sizeof(save_dir), "%s/%s", base, SAVE_DIR);
  snprintf(autosave, sizeof(autosave), "%s/R47auto_0.sav", save_dir);

  int failures = 0;
  snprintf(backup, sizeof(backup), "%s/backupR47_0.cfg", base);
  snprintf(backup_temp, sizeof(backup_temp), "%s.tmp", backup);
  failures |= check_state_file_replacement();

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
