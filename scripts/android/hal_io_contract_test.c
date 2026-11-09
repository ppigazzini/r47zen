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
#include "r47_format.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern void set_android_base_path(const char *path);

static int reports = 0;

// The core's record of a read's file name, which hal/io.c writes.
char fileNameSelected[stateFileNameVarLength];

// r47_offer_snap asks for a file; hal_io_saf_contract_test.c holds that to account.
int requestAndroidFile(int isSave, const char *defaultName, int fileType) {
  (void)isSave;
  (void)defaultName;
  (void)fileType;
  return -1;
}

// The host side of the report: the bridge's real one calls into MainActivity.
// The host side of a core warning: the bridge's real one calls into MainActivity.
static char warned[256] = "";
void r47_show_warning(const char *text) {
  snprintf(warned, sizeof(warned), "%s", text);
}

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

static void write_text(const char *path, const char *text) {
  FILE *file = fopen(path, "w");
  if (file == NULL || fputs(text, file) < 0 || fclose(file) != 0) {
    fprintf(stderr, "FATAL: cannot write %s: %s\n", path, strerror(errno));
    exit(2);
  }
}

// What a file holds, or "" when it is missing.
static const char *read_text(const char *path) {
  static char text[64];
  text[0] = '\0';
  FILE *file = fopen(path, "r");
  if (file != NULL) {
    const size_t got = fread(text, 1, sizeof(text) - 1, file);
    text[got] = '\0';
    fclose(file);
  }
  return text;
}

static int expect_text(const char *dir, const char *name, const char *expected) {
  char path[PATH_MAX];
  snprintf(path, sizeof(path), "%s/%s", dir, name);
  const char *actual = read_text(path);
  if (strcmp(actual, expected) != 0) {
    fprintf(stderr, "FAIL: %s holds '%s', want '%s'\n", path, actual, expected);
    return 1;
  }
  return 0;
}

static void remove_tree(const char *dir) {
  char command[PATH_MAX + 16];
  snprintf(command, sizeof(command), "rm -rf '%s'", dir);
  if (system(command) != 0) {
    fprintf(stderr, "FATAL: cannot remove %s\n", dir);
    exit(2);
  }
}

// Versions before 0.3.0 named the state file and the saves with a slot number, 0, that
// no screen could change. The base path moves each to upstream's name once, so an
// upgraded install opens its own state, and never over a file of the new name.
static int check_slot_name_migration(void) {
  char dir[256];
  char saves[320];
  char path[PATH_MAX];
  int failures = 0;
  snprintf(dir, sizeof(dir), "/tmp/r47-hal-io-migrate-%ld", (long)getpid());
  snprintf(saves, sizeof(saves), "%s/%s", dir, SAVE_DIR);
  remove_tree(dir);
  mkdir(dir, 0700);
  mkdir(saves, 0700);
  snprintf(path, sizeof(path), "%s/backupR47_0.cfg", dir);
  write_text(path, "old state");
  snprintf(path, sizeof(path), "%s/backupR47_0.cfg.restoring", dir);
  write_text(path, "1");
  snprintf(path, sizeof(path), "%s/backupR47_0.cfg.tmp", dir);
  write_text(path, "half");
  snprintf(path, sizeof(path), "%s/R47_0.sav", saves);
  write_text(path, "old save");
  snprintf(path, sizeof(path), "%s/R47auto_0.sav", saves);
  write_text(path, "old autosave");

  set_android_base_path(dir);
  failures |= expect_text(dir, "backupR47.cfg", "old state");
  failures |= expect_text(dir, "backupR47.cfg.restoring", "1");
  failures |= expect_text(saves, SAVE_FILE, "old save");
  failures |= expect_text(saves, AUTO_SAVE_FILE, "old autosave");
  failures |= expect_text(dir, "backupR47_0.cfg", "");
  failures |= expect_text(dir, "backupR47_0.cfg.tmp", "");
  failures |= expect_text(saves, "R47_0.sav", "");
  failures |= expect_text(saves, "R47auto_0.sav", "");

  // A file of the new name is newer than any old one: it stays, and so does the old.
  snprintf(path, sizeof(path), "%s/backupR47_0.cfg", dir);
  write_text(path, "older state");
  set_android_base_path(dir);
  failures |= expect_text(dir, "backupR47.cfg", "old state");
  failures |= expect_text(dir, "backupR47_0.cfg", "older state");

  if (failures == 0) {
    printf("OK: the slot-numbered state and saves move to upstream's names once\n");
  }
  if (chdir("/") != 0) {
    failures |= 1;
  }
  remove_tree(dir);
  return failures;
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
  // The core leaves the working directory for DATA, so a relative base is held as the
  // absolute path it named: given from /tmp, it must still be the folder in /tmp.
  int failures = check_slot_name_migration();
  if (chdir("/tmp") != 0) {
    fprintf(stderr, "FATAL: cannot enter /tmp\n");
    return 2;
  }
  set_android_base_path(strrchr(base, '/') + 1);
  snprintf(save_dir, sizeof(save_dir), "%s/%s", base, SAVE_DIR);
  snprintf(autosave, sizeof(autosave), "%s/%s", save_dir, AUTO_SAVE_FILE);

  snprintf(backup, sizeof(backup), "%s/backupR47.cfg", base);
  snprintf(backup_temp, sizeof(backup_temp), "%s.tmp", backup);
  failures |= check_state_file_replacement();
  char warning[] = "This is a WP43 program";
  show_warning(warning);
  failures |= expect("a core warning reaches the host whole",
                     strcmp(warned, "This is a WP43 program") == 0);

  // Upstream's prints append to a name relative to the working directory, as GTK's
  // land beside the simulator and the firmware's in DATA; an app's own directory is
  // not writable, so the core must work in the base path's DATA folder.
  char data_dir[PATH_MAX];
  char cwd[PATH_MAX];
  snprintf(data_dir, sizeof(data_dir), "%s/%s", base, DATA_DIR);
  if (getcwd(cwd, sizeof(cwd)) == NULL || strcmp(cwd, data_dir) != 0) {
    fprintf(stderr, "FAIL: the core works in '%s', want '%s'\n",
            getcwd(cwd, sizeof(cwd)) ? cwd : "?", data_dir);
    failures |= 1;
  }

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
  if (strcmp(fileNameSelected, AUTO_SAVE_FILE) != 0) {
    fprintf(stderr, "FAIL: a read of the autosave recorded '%s'\n", fileNameSelected);
    failures |= 1;
  }

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

  // A seek that fails leaves nothing to read: a FIFO cannot seek, and it holds bytes
  // a read at the old place would take. The test keeps the writer end open.
  char fifo[640];
  snprintf(fifo, sizeof(fifo), "%s/seek.fifo", base);
  unlink(fifo);
  const int writer = mkfifo(fifo, 0600) == 0 ? open(fifo, O_RDWR) : -1;
  if (writer < 0 || write(writer, "12345678", 8) != 8) {
    fprintf(stderr, "FATAL: no FIFO to read: %s\n", strerror(errno));
    return 2;
  }
  point_autosave_at(fifo);
  if (ioFileOpen(ioPathAutoSave, ioModeRead) != FILE_OK) {
    fprintf(stderr, "FATAL: ioFileOpen of the FIFO failed\n");
    return 2;
  }
  char after[8];
  ioFileSeek(3);
  const uint32_t read = ioFileRead(after, sizeof(after));
  ioFileClose();
  close(writer);
  unlink(fifo);
  if (read != 0) {
    fprintf(stderr, "FAIL: a read after a failed seek took %u bytes\n", read);
    failures |= 1;
  }

  // r47_format fails a text that does not fit, and leaves nothing of it.
  char small[4] = "abc";
  char fits[8];
  if (r47_format(small, sizeof(small), "%s", "toolong") || small[0] != '\0' ||
      !r47_format(fits, sizeof(fits), "%s", "fits") || strcmp(fits, "fits") != 0) {
    fprintf(stderr, "FAIL: r47_format kept a cut text or refused one that fits\n");
    failures |= 1;
  }

  point_autosave_at(NULL);
  if (chdir("/") != 0) {
    fprintf(stderr, "FAIL: cannot leave %s\n", data_dir);
    failures |= 1;
  }
  rmdir(data_dir);
  rmdir(save_dir);
  rmdir(base);
  return failures;
}
