// Host regression guard for the Copy menu's text, which upstream's builders in
// screen.c make, each sized from the registers it holds; jni_clipboard.c takes
// it through r47_capture_clipboard_locked. Built under AddressSanitizer, so an
// overrun aborts with a stack trace.
//
// It holds the copies whole: the statistical sums of an all-registers copy,
// matrices to 60x60 with every value and whole UTF-8, and registers of 2^60000
// (18062 digits) whose copies no fixed cap may cut. It holds the capture to the
// one copy it runs. It also holds paste, SNAP, the file answer, and a canceled
// key to the live core (see each check).

#include "jni_bridge.h"
#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// The SNAP slot of hal/io.c.
extern char _ioFileNameOverride[];
extern const char *r47_picked_file_name(void);

// The Copy menu's copy, as jni_clipboard.c takes it, which the caller frees.
static char *copy_text(void (*copy)(void)) {
  pthread_mutex_lock(&screenMutex);
  char *text = r47_capture_clipboard_locked(copy);
  pthread_mutex_unlock(&screenMutex);
  return text;
}

// Dump all registers after one Sigma+ of x = 2 and y = 3, as the Copy menu copies
// them, and hold the statistical sums: every SR row is there, and two values tie
// the rows to the sums they label: SR14, x^2 ln(y), is 4 ln 3, and SR23, x^4, is
// 16. Return the number of failures.
static int check_statistical_sum_rows(void) {
  convertDoubleToReal34Register(3.0, REGISTER_Y);
  convertDoubleToReal34Register(2.0, REGISTER_X);
  fnSigmaAddRem(SIGMA_PLUS);
  if (statisticalSumsPointer == NULL) {
    fprintf(stderr, "FAIL: Sigma+ allocated no statistical sums to dump\n");
    return 1;
  }

  char *all = copy_text(copyAllRegistersToClipboard);
  if (all == NULL) {
    fprintf(stderr, "FAIL: the all-registers copy allocated nothing\n");
    return 1;
  }
  int failures = 0;
  for (int sum = 0; sum < NUMBER_OF_STATISTICAL_SUMS; sum++) {
    char row[16];
    snprintf(row, sizeof(row), "\nSR%02d = ", sum);
    const char *at = strstr(all, row);
    const char *value = at != NULL ? strstr(at + strlen(row), " = ") : NULL;
    const char *lineEnd = at != NULL ? strchr(at + 1, '\n') : NULL;
    if (value == NULL || (lineEnd != NULL && value > lineEnd)) {
      fprintf(stderr, "FAIL: the dump has no row SR%02d\n", sum);
      failures++;
      continue;
    }
    value += 3;
    if (sum == SUM_X2lnY && strncmp(value, "4.394449154672438765", 20) != 0) {
      fprintf(stderr, "FAIL: SR%02d is not 4 ln 3: %.40s\n", sum, value);
      failures++;
    }
    if (sum == SUM_X4 && strtod(value, NULL) != 16.0) {
      fprintf(stderr, "FAIL: SR%02d is not 16: %.40s\n", sum, value);
      failures++;
    }
  }
  free(all);
  fprintf(stderr, "statistical sum rows checked = %d, failures = %d\n",
          NUMBER_OF_STATISTICAL_SUMS, failures);
  return failures;
}

// The imaginary unit U+2148, as the clipboard text writes it in UTF-8.
#define PASTE_I "\xE2\x85\x88"

// Reset the stack to known values with no entry open and no prompt pending.
static void reset_paste_state(void) {
  if (calcMode == CM_NIM) {
    closeNim();
  }
  calcMode = CM_NORMAL;
  tam.mode = 0;
  lastErrorCode = 0;
  temporaryInformation = TI_NO_INFO;
  clearSystemFlag(FLAG_USER);
  convertDoubleToReal34Register(9.0, REGISTER_Z);
  convertDoubleToReal34Register(8.0, REGISTER_Y);
  convertDoubleToReal34Register(7.0, REGISTER_X);
  setSystemFlag(FLAG_ASLIFT);
}

static const char *paste_register_text(calcRegister_t regist) {
  static char text[4096];
  if (calcMode == CM_NIM) {
    closeNim();
  }
  copyRegisterToClipboardString(regist, text, false);
  return text;
}

static int expect_register(const char *label, calcRegister_t regist,
                           const char *expected) {
  const char *actual = paste_register_text(regist);
  if (strcmp(actual, expected) == 0) {
    return 0;
  }
  fprintf(stderr, "FAIL: %s: register %d is '%s', want '%s'\n", label, regist,
          actual, expected);
  return 1;
}

// A refused paste must leave the stack as reset_paste_state wrote it, with no
// entry open: a half-typed number or a lifted stack is the wrong number by another
// route.
static int expect_paste_backed_out(const char *label) {
  int failures = 0;
  if (calcMode != CM_NORMAL) {
    fprintf(stderr, "FAIL: %s left the core in mode %d\n", label, calcMode);
    failures++;
  }
  failures += expect_register(label, REGISTER_X, "7.");
  failures += expect_register(label, REGISTER_Y, "8.");
  failures += expect_register(label, REGISTER_Z, "9.");
  return failures;
}

// Write the current program's steps as "[step][step]...".
static void program_listing(char *out, size_t size) {
  size_t used = 0;
  out[0] = '\0';
  for (uint8_t *step = beginOfCurrentProgram;
       step != NULL && step < endOfCurrentProgram && used < size &&
       !(step[0] == 255 && step[1] == 255);
       step = findNextStep(step)) {
    decodeOneStep(step);
    // snprintf returns the length it wanted, so a cut write would carry used past
    // the buffer: a failed or cut step ends the listing instead.
    int written = snprintf(out + used, size - used, "[%s]", tmpString);
    if (written < 0 || (size_t)written >= size - used) {
      break;
    }
    used += (size_t)written;
  }
}

// The matrix editor reads digits as the number for the cursor's cell, so a paste
// enters there as typed digits do, and a typed number still open is not closed for it.
static int check_paste_in_matrix_editor(void) {
  int failures = 0;
  reset_paste_state();
  convertDoubleToReal34Register(2.0, REGISTER_Y);
  convertDoubleToReal34Register(2.0, REGISTER_X);
  runFunction(ITM_M_NEW);
  runFunction(ITM_M_EDI);
  if (calcMode != CM_MIM) {
    fprintf(stderr, "FAIL: the matrix editor did not open, mode %d\n", calcMode);
    return failures + 1;
  }
  if (!r47_paste_number("1.5~") || strcmp(aimBuffer, "-1.5") != 0) {
    fprintf(stderr, "FAIL: a paste in the matrix editor left '%s'\n", aimBuffer);
    failures++;
  }
  if (r47_paste_number("6") || strcmp(aimBuffer, "-1.5") != 0) {
    fprintf(stderr, "FAIL: a paste over an open cell left '%s'\n", aimBuffer);
    failures++;
  }
  aimBuffer[0] = '\0';
  if (r47_paste_number("1e99999") || aimBuffer[0] != '\0') {
    fprintf(stderr, "FAIL: a dropped token in the matrix editor left '%s'\n", aimBuffer);
    failures++;
  }
  aimBuffer[0] = '\0';
  mimFinalize();
  calcMode = CM_NORMAL;
  reset_paste_state();
  return failures;
}

// Hold r47_paste_number to the core at this commit. PasteNumberTest.kt holds
// PasteNumber.kt to the same token strings, so the two together cover clip text
// to stack value. Return the number of failures.
static int check_paste_number(void) {
  static const struct {
    const char *tokens;
    const char *x;
  } numbers[] = {
      {"12", "12"},
      {"1.5", "1.5"},
      {"0.5", "0.5"},
      {"1.5~", "-1.5"},
      {"2~", "-2"},
      {"1.5e3", "1.5E+3"},
      {"1.5e3~", "0.0015"},
      {"1.5~e3~", "-0.0015"},
      {"1~e100~", "-1E-100"},
      {"1.234567890123456789012345678901234",
       "1.234567890123456789012345678901234"},
      {"3j4", "3. + " PASTE_I "4."},
      {"3j4~", "3. - " PASTE_I "4."},
      {"3~j4~", "-3. - " PASTE_I "4."},
      {"1.5e3j2e1~", "1.5E+3 + " PASTE_I "0.2"},
      {"0j4", "0. + " PASTE_I "4."},
      {"0j4~", "0. - " PASTE_I "4."},
      {"7.", "7."},
      {"12345~", "-12345"},
      {"1e100", "1E+100"},
      {"3.j4.", "3. + " PASTE_I "4."},
      {"3.j4.~", "3. - " PASTE_I "4."},
      {"3.~j4.~", "-3. - " PASTE_I "4."},
      {"1.5e3j0.2", "1.5E+3 + " PASTE_I "0.2"},
      {"0", "0"},
      {"0.50", "0.5"},
      {"1.2345", "1.2345"},
      {"1e0", "1."},
      {"1e5", "1E+5"},
      {"7", "7"},
      {"1.5e3j2~e1~", "1.5E+3 - " PASTE_I "0.2"},
  };
  int failures = 0;
  for (size_t i = 0; i < sizeof(numbers) / sizeof(numbers[0]); i++) {
    reset_paste_state();
    if (!r47_paste_number(numbers[i].tokens)) {
      fprintf(stderr, "FAIL: paste '%s' was refused\n", numbers[i].tokens);
      failures++;
      continue;
    }
    failures += expect_register(numbers[i].tokens, REGISTER_X, numbers[i].x);
    failures += expect_register(numbers[i].tokens, REGISTER_Y, "7.");
  }

  // USER assignments and a pending shift must not turn a digit into a function.
  reset_paste_state();
  setSystemFlag(FLAG_USER);
  calcKey_t savedKey = kbd_usr[28];
  kbd_usr[28].primary = ITM_sin;
  shiftF = true;
  if (!r47_paste_number("1")) {
    fprintf(stderr, "FAIL: paste under USER and f-shift was refused\n");
    failures++;
  }
  if (shiftF) {
    fprintf(stderr, "FAIL: a paste left f-shift pending for the next key\n");
    failures++;
    shiftF = false;
  }
  kbd_usr[28] = savedKey;
  failures += expect_register("USER and f-shift", REGISTER_X, "1");

  // An open entry closes first, so the clip never merges into typed digits.
  reset_paste_state();
  r47_send_sim_key("29", false, false);
  r47_send_sim_key("29", false, true);
  if (!r47_paste_number("3")) {
    fprintf(stderr, "FAIL: paste over an open entry was refused\n");
    failures++;
  }
  failures += expect_register("open entry", REGISTER_X, "3");
  failures += expect_register("open entry", REGISTER_Y, "2");

  // The modes that read digits as something else refuse the whole clip.
  const uint8_t modeSaved = calcMode;
  reset_paste_state();
  calcMode = CM_AIM;
  if (r47_paste_number("1")) {
    fprintf(stderr, "FAIL: paste in alpha entry was accepted\n");
    failures++;
  }
  calcMode = modeSaved;
  reset_paste_state();
  tam.mode = TM_VALUE;
  if (r47_paste_number("1")) {
    fprintf(stderr, "FAIL: paste under a parameter prompt was accepted\n");
    failures++;
  }
  reset_paste_state();
  catalog = CATALOG_FCNS;
  if (r47_paste_number("1")) {
    fprintf(stderr, "FAIL: paste in a catalog was accepted\n");
    failures++;
  }
  catalog = CATALOG_NONE;
  const uint8_t runStopSaved = programRunStop;
  programRunStop = PGM_RUNNING;
  if (r47_paste_number("1")) {
    fprintf(stderr, "FAIL: paste during a running program was accepted\n");
    failures++;
  }
  programRunStop = runStopSaved;
  reset_paste_state();
  failures += expect_register("refused paste leaves X", REGISTER_X, "7.");

  // A token outside the alphabet refuses the clip before anything runs.
  if (r47_paste_number("12x")) {
    fprintf(stderr, "FAIL: a bad token was accepted\n");
    failures++;
  }
  failures += expect_register("bad token leaves X", REGISTER_X, "7.");

  // Number entry drops what it will not take with no error, so each of these must
  // refuse the clip and back the entry out, leaving the stack as it was.
  static const struct {
    const char *label;
    const char *tokens;
  } dropped[] = {
      // Past the display width: the negative exponents here take one digit too many.
      {"full-precision negative complex",
       "1.234567890123456789012345678901234~e6144~"
       "j1.234567890123456789012345678901234~e6144~"},
      {"exponent past the default RNG", "1e99999"},
      {"imaginary exponent past the default RNG", "1j1e99999"},
  };
  for (size_t i = 0; i < sizeof(dropped) / sizeof(dropped[0]); i++) {
    reset_paste_state();
    if (r47_paste_number(dropped[i].tokens)) {
      fprintf(stderr, "FAIL: %s was accepted\n", dropped[i].label);
      failures++;
    }
    failures += expect_paste_backed_out(dropped[i].label);
  }

  char longDigits[257];
  memset(longDigits, '7', sizeof(longDigits) - 1);
  longDigits[sizeof(longDigits) - 1] = '\0';
  reset_paste_state();
  if (r47_paste_number(longDigits)) {
    fprintf(stderr, "FAIL: digits past the display width were accepted\n");
    failures++;
  }
  failures += expect_paste_backed_out("digits past the display width");

  const uint8_t savedInputDefault = Input_Default;
  Input_Default = ID_LI;
  reset_paste_state();
  if (r47_paste_number("1.5")) {
    fprintf(stderr, "FAIL: a radix mark in integer input mode was accepted\n");
    failures++;
  }
  failures += expect_paste_backed_out("radix mark in integer input mode");
  Input_Default = savedInputDefault;

  const int16_t savedLimit = exponentLimit;
  fnRange(99);
  reset_paste_state();
  if (r47_paste_number("1e100")) {
    fprintf(stderr, "FAIL: an exponent past RNG was accepted\n");
    failures++;
  }
  failures += expect_paste_backed_out("exponent past RNG");
  if (!r47_paste_number("1e99")) {
    fprintf(stderr, "FAIL: an exponent at RNG was refused\n");
    failures++;
  }
  failures += expect_register("exponent at RNG", REGISTER_X, "1E+99");
  fnRange(savedLimit);

  // A paste leaves its number open, as typed digits do, so the next paste must close
  // it. At the top of a program an open number would stay under the cursor, and the
  // next paste would land before it.
  reset_paste_state();
  fnPem(NOPARAM);
  if (!r47_paste_number("8") || !r47_paste_number("9")) {
    fprintf(stderr, "FAIL: two pastes at the top of a program were refused\n");
    failures++;
  }
  pemCloseNumberInput();
  aimBuffer[0] = '\0';
  char topListing[256];
  program_listing(topListing, sizeof(topListing));
  if (strncmp(topListing, "[8][9]", 6) != 0) {
    fprintf(stderr, "FAIL: pastes at the top of a program left %s, want [8][9]\n", topListing);
    failures++;
  }

  // In a program, a number is one step, and a refused one leaves none behind.
  const uint16_t stepsBefore = getNumberOfSteps();
  if (!r47_paste_number("1.5~")) {
    fprintf(stderr, "FAIL: a paste in program entry was refused\n");
    failures++;
  }
  pemCloseNumberInput();
  aimBuffer[0] = '\0';
  decodeOneStep(findPreviousStep(currentStep));
  if (strcmp(tmpString, "-1.5") != 0 || getNumberOfSteps() != stepsBefore + 1) {
    fprintf(stderr, "FAIL: the program holds '%s' at %u steps, want '-1.5' at %u\n",
            tmpString, getNumberOfSteps(), stepsBefore + 1u);
    failures++;
  }
  if (r47_paste_number("1e99999")) {
    fprintf(stderr, "FAIL: a program step past the limit was accepted\n");
    failures++;
  }
  if (aimBuffer[0] != '\0' || getNumberOfSteps() != stepsBefore + 1) {
    fprintf(stderr, "FAIL: a refused paste changed the program\n");
    failures++;
  }

  // A later paste closes it the same way in the middle of a program.
  if (!r47_paste_number("1.5") || !r47_paste_number("2")) {
    fprintf(stderr, "FAIL: two pastes in a row in program entry were refused\n");
    failures++;
  }
  pemCloseNumberInput();
  aimBuffer[0] = '\0';
  char listing[256];
  program_listing(listing, sizeof(listing));
  if (strstr(listing, "[-1.5][1.5][2]") == NULL) {
    fprintf(stderr, "FAIL: pastes mid-program left %s, want [-1.5][1.5][2]\n", listing);
    failures++;
  }
  reset_paste_state();

  failures += check_paste_in_matrix_editor();
  return failures;
}

// A picker can answer after the process died behind it, with no request waiting. A
// descriptor left in native would never close, and a save's worker reads until it does.
static int check_file_answer_with_no_request(void) {
  int failures = 0;
  if (Java_io_github_ppigazzini_r47zen_MainActivity_onFileSelectedNative(NULL, NULL, 77, NULL)) {
    fprintf(stderr, "FAIL: native took a file answer with no request waiting\n");
    failures++;
  }
  if (fileReady || fileDescriptor == 77) {
    fprintf(stderr, "FAIL: native kept a file answer with no request waiting\n");
    failures++;
  }
  atomic_store(&isCoreBlockingForIo, true);
  if (!Java_io_github_ppigazzini_r47zen_MainActivity_onFileSelectedNative(NULL, NULL, 77, NULL) ||
      !fileReady || fileDescriptor != 77) {
    fprintf(stderr, "FAIL: native dropped a file answer a request was waiting for\n");
    failures++;
  }
  atomic_store(&isCoreBlockingForIo, false);
  fileReady = false;
  fileDescriptor = -1;
  return failures;
}

static int stringsReleased;

static const char *fake_get_string_chars(JNIEnv *env, jstring string, jboolean *isCopy) {
  (void)env;
  if (isCopy != NULL) {
    *isCopy = JNI_FALSE;
  }
  return (const char *)string;
}

static void fake_release_string_chars(JNIEnv *env, jstring string, const char *chars) {
  (void)env;
  (void)string;
  (void)chars;
  stringsReleased++;
}

// The answer's name is what hal/io.c records for STATE#. It reaches native whole, a
// name longer than native keeps holds its end, the part GTK records, and no name
// leaves none.
static int check_file_answer_name(void) {
  int failures = 0;
  struct JNINativeInterface_ table = {
      .GetStringUTFChars = fake_get_string_chars,
      .ReleaseStringUTFChars = fake_release_string_chars,
  };
  JNIEnv env = &table;
  char longName[400];
  memset(longName, 'a', sizeof(longName) - 1);
  memcpy(longName + sizeof(longName) - 13, "-the-end.s47", 13);
  const size_t kept = 255;
  const struct {
    const char *name;
    const char *want;
  } cases[] = {
      {"mystate.s47", "mystate.s47"},
      {longName, longName + sizeof(longName) - 1 - kept},
      {NULL, ""},
  };
  stringsReleased = 0;
  for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
    atomic_store(&isCoreBlockingForIo, true);
    if (!Java_io_github_ppigazzini_r47zen_MainActivity_onFileSelectedNative(
            &env, NULL, 77, (jstring)(uintptr_t)cases[i].name) ||
        strcmp(r47_picked_file_name(), cases[i].want) != 0) {
      const char *got = r47_picked_file_name();
      fprintf(stderr, "FAIL: answer %zu left %zu bytes ending '%s', want %zu ending '%s'\n",
              i, strlen(got), got + (strlen(got) > 16 ? strlen(got) - 16 : 0),
              strlen(cases[i].want),
              cases[i].want + (strlen(cases[i].want) > 16 ? strlen(cases[i].want) - 16 : 0));
      failures++;
    }
    atomic_store(&isCoreBlockingForIo, false);
    fileReady = false;
    fileDescriptor = -1;
  }
  if (stringsReleased != 2) {
    fprintf(stderr, "FAIL: native released %d of the 2 names it read\n", stringsReleased);
    failures++;
  }
  return failures;
}

// A fake JNI environment that keeps two JNI rules: no JNI call while
// an exception is pending, but the few that handle it, and none between a critical
// get and its release.
static bool fakeExceptionPending;
static int fakeCriticalDepth;
static int fakeCallsWithExceptionPending;
static int fakeCallsInCritical;
static int fakeNewStringCalls;
static uint8_t fakeCriticalBuffer[1 << 16];

static jboolean fake_exception_check(JNIEnv *env) {
  (void)env;
  fakeCallsInCritical += fakeCriticalDepth > 0;
  return fakeExceptionPending ? JNI_TRUE : JNI_FALSE;
}

static void fake_exception_describe(JNIEnv *env) {
  (void)env;
  fakeCallsInCritical += fakeCriticalDepth > 0;
}

static void fake_exception_clear(JNIEnv *env) {
  (void)env;
  fakeCallsInCritical += fakeCriticalDepth > 0;
  fakeExceptionPending = false;
}

// The first call runs out of memory, as NewStringUTF does: NULL, and an
// OutOfMemoryError pending.
static jstring fake_new_string_utf(JNIEnv *env, const char *text) {
  (void)env;
  fakeCallsWithExceptionPending += fakeExceptionPending;
  if (++fakeNewStringCalls == 1) {
    fakeExceptionPending = true;
    return NULL;
  }
  return (jstring)(uintptr_t)text;
}

static jsize fake_array_length(JNIEnv *env, jarray array) {
  (void)env;
  (void)array;
  return (jsize)sizeof(fakeCriticalBuffer);
}

static void *fake_get_critical(JNIEnv *env, jarray array, jboolean *isCopy) {
  (void)env;
  (void)array;
  (void)isCopy;
  fakeCriticalDepth++;
  return fakeCriticalBuffer;
}

static void fake_release_critical(JNIEnv *env, jarray array, void *carray, jint mode) {
  (void)env;
  (void)array;
  (void)carray;
  (void)mode;
  fakeCriticalDepth--;
}

// A string that runs out of memory falls back to the default only after its
// OutOfMemoryError is cleared: JNI allows almost no call while one is pending.
static int check_string_fallback_after_out_of_memory(void) {
  int failures = 0;
  struct JNINativeInterface_ table = {
      .NewStringUTF = fake_new_string_utf,
      .ExceptionCheck = fake_exception_check,
      .ExceptionDescribe = fake_exception_describe,
      .ExceptionClear = fake_exception_clear,
  };
  JNIEnv env = &table;
  fakeExceptionPending = false;
  fakeCallsWithExceptionPending = 0;
  fakeNewStringCalls = 0;
  const jstring result = jni_new_string_utf(&env, "value", "fallback", "harness");
  if (fakeCallsWithExceptionPending != 0) {
    fprintf(stderr, "FAIL: NewStringUTF ran %d time(s) with an OutOfMemoryError pending\n",
            fakeCallsWithExceptionPending);
    failures++;
  }
  if (result == NULL || strcmp((const char *)(uintptr_t)result, "fallback") != 0 ||
      fakeExceptionPending) {
    fprintf(stderr, "FAIL: an out-of-memory string did not end in the fallback, cleared\n");
    failures++;
  }
  return failures;
}

// The display copy makes no JNI call between the critical get and its release.
static int check_display_copy_makes_no_call_in_critical(void) {
  struct JNINativeInterface_ table = {
      .GetArrayLength = fake_array_length,
      .GetPrimitiveArrayCritical = fake_get_critical,
      .ReleasePrimitiveArrayCritical = fake_release_critical,
      .ExceptionCheck = fake_exception_check,
      .ExceptionDescribe = fake_exception_describe,
      .ExceptionClear = fake_exception_clear,
  };
  JNIEnv env = &table;
  fakeExceptionPending = false;
  fakeCriticalDepth = 0;
  fakeCallsInCritical = 0;
  Java_io_github_ppigazzini_r47zen_MainActivity_getPackedDisplayBuffer(
      &env, NULL, (jbyteArray)(uintptr_t)1);
  if (fakeCallsInCritical != 0 || fakeCriticalDepth != 0) {
    fprintf(stderr, "FAIL: the display copy made %d JNI call(s) in its critical region\n",
            fakeCallsInCritical);
    return 1;
  }
  return 0;
}

// Upstream names a print file <date>.<kind>.TSV.
static bool is_print_file(const char *name) {
  const size_t length = strlen(name);
  return length > 4 && strcmp(name + length - 4, ".TSV") == 0;
}

static void remove_print_files(const char *dir) {
  DIR *folder = opendir(dir);
  if (folder == NULL) {
    return;
  }
  char path[PATH_MAX];
  for (struct dirent *entry = readdir(folder); entry != NULL; entry = readdir(folder)) {
    if (is_print_file(entry->d_name) &&
        snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name) < (int)sizeof(path)) {
      unlink(path);
    }
  }
  closedir(folder);
}

// The bytes of the one print file in dir, or -1 with none or more than one.
static long print_file_bytes(const char *dir) {
  DIR *folder = opendir(dir);
  if (folder == NULL) {
    return -1;
  }
  long bytes = -1;
  int files = 0;
  char path[PATH_MAX];
  struct stat info;
  for (struct dirent *entry = readdir(folder); entry != NULL; entry = readdir(folder)) {
    if (is_print_file(entry->d_name) &&
        snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name) < (int)sizeof(path) &&
        stat(path, &info) == 0) {
      files++;
      bytes = (long)info.st_size;
    }
  }
  closedir(folder);
  return files == 1 ? bytes : -1;
}

// hal/io.c offers a SNAP on two upstream behaviors, so hold them to the live core:
// fnSNAP writes a BMP of the whole screen to the name it was given, and clears the
// name. With no picker in this harness, the offer then drops the capture and arms.
static int check_snap(const char *runtime_dir) {
  int failures = 0;
  char capture[PATH_MAX];
  snprintf(capture, sizeof(capture), "%s/snap.bmp", runtime_dir);
  if (strcmp(_ioFileNameOverride, capture) != 0) {
    fprintf(stderr, "FAIL: init armed '%s', want '%s'\n", _ioFileNameOverride, capture);
    failures++;
  }

  // fnSNAP ends with a register print, which appends to a name relative to the working
  // directory: it must land in the base path's DATA folder, which the shell mirrors.
  char data_dir[PATH_MAX];
  snprintf(data_dir, sizeof(data_dir), "%s/DATA", runtime_dir);
  remove_print_files(data_dir);
  fnSNAP(NOPARAM);
  const long printed = print_file_bytes(data_dir);
  if (printed <= 0) {
    fprintf(stderr, "FAIL: SNAP's register print left %ld bytes in %s\n", printed, data_dir);
    failures++;
  }
  remove_print_files(data_dir);

  long wantSize = 0x82L + (long)(SCREEN_WIDTH / 8 + 2) * SCREEN_HEIGHT;
  char magic[3] = "";
  struct stat info;
  FILE *bmp = fopen(capture, "rb");
  size_t read = bmp ? fread(magic, 1, 2, bmp) : 0;
  if (bmp) {
    fclose(bmp);
  }
  if (_ioFileNameOverride[0] != '\0' || read != 2 || strcmp(magic, "BM") != 0 ||
      stat(capture, &info) != 0 || info.st_size != wantSize) {
    fprintf(stderr, "FAIL: SNAP left name '%s' and a file of %ld bytes, magic '%s'; want %ld\n",
            _ioFileNameOverride, stat(capture, &info) == 0 ? (long)info.st_size : -1L,
            magic, wantSize);
    failures++;
  }

  // tick runs the offer, so this fails too if tick stops calling it.
  Java_io_github_ppigazzini_r47zen_MainActivity_tick(NULL, NULL);
  if (stat(capture, &info) == 0 || strcmp(_ioFileNameOverride, capture) != 0) {
    fprintf(stderr, "FAIL: the tick left the capture or name '%s'\n", _ioFileNameOverride);
    failures++;
  }
  return failures;
}

// In AIM with NUM on, a key's face is what it types, as GTK draws it through
// numlockReplacements: the key that shows N has 7 on its g face, and shows 7 under
// NUM. Return the number of failures.
static char aimFaces[2][R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
                    [R47_KEYPAD_LABEL_CAPACITY];

static const char *aim_face(int numLock, int key, int slot) {
  return aimFaces[numLock][key * R47_KEYPAD_LABELS_PER_KEY + slot];
}

static int check_aim_faces_follow_num_lock(void) {
  pthread_mutex_lock(&screenMutex);
  runFunction(ITM_AIM);
  for (int numLock = 0; numLock < 2; numLock++) {
    if (numLock) {
      setSystemFlag(FLAG_NUMLOCK);
    } else {
      clearSystemFlag(FLAG_NUMLOCK);
    }
    r47_get_keypad_labels(aimFaces[numLock], true);
  }
  clearSystemFlag(FLAG_NUMLOCK);
  runFunction(ITM_EXIT1);
  const int mode = calcMode;
  pthread_mutex_unlock(&screenMutex);

  int key = 0;
  while (key < R47_KEYPAD_KEY_COUNT &&
         strcmp(aim_face(0, key, R47_KEYPAD_LABEL_PRIMARY), "N") != 0) {
    key++;
  }
  if (key == R47_KEYPAD_KEY_COUNT) {
    fprintf(stderr, "FAIL: no AIM key shows N\n");
    return 1;
  }
  int failures = 0;
  if (strcmp(aim_face(0, key, R47_KEYPAD_LABEL_G), "7") != 0) {
    fprintf(stderr, "FAIL: the N key's g face is %s, not 7\n",
            aim_face(0, key, R47_KEYPAD_LABEL_G));
    failures++;
  }
  if (strcmp(aim_face(1, key, R47_KEYPAD_LABEL_PRIMARY), "7") != 0) {
    fprintf(stderr, "FAIL: the N key under NUM shows %s, not 7\n",
            aim_face(1, key, R47_KEYPAD_LABEL_PRIMARY));
    failures++;
  }
  if (mode != CM_NORMAL) {
    fprintf(stderr, "FAIL: EXIT left AIM in mode %d\n", mode);
    failures++;
  }
  return failures;
}

// The app keeps no beep volume, so the VOL softkeys show no value, as GTK's
// getBeepVolume answers NOVAL. Return the number of failures.
extern int16_t fnItemShowValue(int16_t item);

static int check_volume_softkeys_show_no_value(void) {
  pthread_mutex_lock(&screenMutex);
  const int16_t up = fnItemShowValue(ITM_VOLPLUS);
  const int16_t down = fnItemShowValue(ITM_VOLMINUS);
  pthread_mutex_unlock(&screenMutex);
  if (up != NOVAL || down != NOVAL) {
    fprintf(stderr, "FAIL: VOL+ shows %d and VOL- %d, not NOVAL\n", up, down);
    return 1;
  }
  return 0;
}

// What a screen reader hears of the display: the error it shows, the number being
// typed, else X as Copy X gives it, cut on a whole character. Return the number of
// failures.
static int check_display_text(void) {
  int failures = 0;
  bool isError = true;
  pthread_mutex_lock(&screenMutex);
  convertDoubleToReal34Register(15.0, REGISTER_X);
  char *x = r47_display_text_locked(&isError);
  if (x == NULL || strcmp(x, "15.") != 0 || isError) {
    fprintf(stderr, "FAIL: the display of X 15 read '%s', error %d\n", x ? x : "(null)",
            isError);
    failures++;
  }
  free(x);

  const uint8_t savedMode = calcMode;
  calcMode = CM_NIM;
  strcpy(aimBuffer, "12");
  char *typed = r47_display_text_locked(&isError);
  if (typed == NULL || strcmp(typed, "12") != 0 || isError) {
    fprintf(stderr, "FAIL: the number being typed read '%s'\n", typed ? typed : "(null)");
    failures++;
  }
  free(typed);
  aimBuffer[0] = 0;
  calcMode = savedMode;

  lastErrorCode = ERROR_ARG_EXCEEDS_FUNCTION_DOMAIN;
  char *error = r47_display_text_locked(&isError);
  if (error == NULL || strcmp(error, "An argument exceeds the function domain") != 0 ||
      !isError) {
    fprintf(stderr, "FAIL: the error shown read '%s', error %d\n", error ? error : "(null)",
            isError);
    failures++;
  }
  free(error);
  lastErrorCode = 0;

  // A text past the cap is cut at a character's first byte.
  static char longText[R47_DISPLAY_TEXT_MAX + 8];
  memset(longText, 'a', R47_DISPLAY_TEXT_MAX - 1);
  longText[R47_DISPLAY_TEXT_MAX - 1] = 0;
  calcMode = CM_AIM;
  strcpy(aimBuffer, longText);
  strcat(aimBuffer, STD_SIGMA STD_SIGMA);
  char *cut = r47_display_text_locked(&isError);
  if (cut == NULL || strlen(cut) != R47_DISPLAY_TEXT_MAX - 1) {
    fprintf(stderr, "FAIL: a long text was cut to %zu bytes, want %d\n",
            cut ? strlen(cut) : 0, R47_DISPLAY_TEXT_MAX - 1);
    failures++;
  }
  free(cut);
  aimBuffer[0] = 0;
  calcMode = savedMode;
  pthread_mutex_unlock(&screenMutex);
  return failures;
}

// sendKey's codes for the keys check_canceled_key presses.
enum {
  SEND_KEY_FIRST = 1,
  SEND_KEY_DOWN = 28,
  SEND_KEY_PLUS = 37,
  SEND_KEY_SOFTKEY_1 = 38,
};

extern void runFunction(int16_t func);
extern void showSoftmenu(int16_t id);
extern void goToGlobalStep(int32_t step);

static void send_key(int code) {
  Java_io_github_ppigazzini_r47zen_MainActivity_sendKey(NULL, NULL, code);
}

// Start an ASSIGN of + and press the key, or softkey, it waits for.
static void press_into_assign(int code) {
  reset_paste_state();
  if (code == SEND_KEY_SOFTKEY_1) {
    showSoftmenu(-MNU_MyMenu);
  }
  runFunction(ITM_ASSIGN);
  itemToBeAssigned = ITM_ADD;
  send_key(code);
}

// Leave ASSIGN as the cases found the core, with no softmenu but HOME.
static void leave_assign(void) {
  itemToBeAssigned = 0;
  reset_paste_state();
  showSoftmenu(-MNU_HOME);
}

// A touch the platform canceled reaches sendKey as R47_KEY_CANCEL, which lets the
// key up without running what its release would: + adds nothing, a single step
// runs no step, and an ASSIGN, to a key or a softkey, assigns nothing. A whole
// press after the cancel still runs. The single step runs the program
// check_paste_number left, whose first step is the number 8. Return the number of
// failures.
static int check_canceled_key(void) {
  int failures = 0;

  reset_paste_state();
  send_key(SEND_KEY_PLUS);
  send_key(R47_KEY_CANCEL);
  failures += expect_register("canceled +", REGISTER_X, "7.");
  send_key(SEND_KEY_PLUS);
  send_key(0);
  failures += expect_register("+ after a cancel", REGISTER_X, "15.");

  reset_paste_state();
  goToGlobalStep(1);
  send_key(SEND_KEY_DOWN);
  if (programRunStop != PGM_SINGLE_STEP) {
    fprintf(stderr, "FAIL: the down key started no single step: runStop %d\n",
            programRunStop);
    failures++;
  }
  send_key(R47_KEY_CANCEL);
  if (programRunStop == PGM_SINGLE_STEP) {
    fprintf(stderr, "FAIL: a canceled single step left runStop %d\n", programRunStop);
    failures++;
  }
  failures += expect_register("canceled single step", REGISTER_X, "7.");
  programRunStop = PGM_STOPPED;

  const calcKey_t keyBefore = kbd_usr[0];
  press_into_assign(SEND_KEY_FIRST);
  send_key(R47_KEY_CANCEL);
  if (kbd_usr[0].primary != keyBefore.primary || calcMode != CM_ASSIGN) {
    fprintf(stderr, "FAIL: a canceled key took the ASSIGN: %d to %d, mode %d\n",
            keyBefore.primary, kbd_usr[0].primary, calcMode);
    failures++;
  }
  kbd_usr[0] = keyBefore;
  leave_assign();

  const int16_t softkeyBefore = userMenuItems[0].item;
  press_into_assign(SEND_KEY_SOFTKEY_1);
  send_key(R47_KEY_CANCEL);
  if (userMenuItems[0].item != softkeyBefore || calcMode != CM_ASSIGN) {
    fprintf(stderr, "FAIL: a canceled softkey took the ASSIGN: %d to %d, mode %d\n",
            softkeyBefore, userMenuItems[0].item, calcMode);
    failures++;
  }
  userMenuItems[0].item = softkeyBefore;
  leave_assign();
  return failures;
}

// Run action with stderr sent to a file, write what it caught back to stderr, and
// say whether that holds needle. A fault inside action aborts with its report in
// the file, so the line before it names the step.
static bool stderr_holds(const char *step, void (*action)(void), const char *needle) {
  char path[] = "/tmp/r47-clipboard-stderr-XXXXXX";
  const int fd = mkstemp(path);
  if (fd < 0) {
    return false;
  }
  unlink(path);
  fprintf(stderr, "running, its stderr caught: %s\n", step);
  fflush(stderr);
  const int saved = dup(STDERR_FILENO);
  if (saved < 0 || dup2(fd, STDERR_FILENO) < 0) {
    close(fd);
    return false;
  }
  action();
  fflush(stderr);
  dup2(saved, STDERR_FILENO);
  close(saved);
  char seen[512] = {0};
  const ssize_t got = pread(fd, seen, sizeof(seen) - 1, 0);
  close(fd);
  fputs(seen, stderr);
  return got > 0 && strstr(seen, needle) != NULL;
}

// The Copy menu's copy goes to its capture, and the capture ends with it: a copy
// after it, which only GTK's key handler makes upstream, is dropped and logged,
// not written through a capture that has gone.
static int check_capture_ends_with_the_copy(void) {
  int failures = 0;
  convertDoubleToReal34Register(7.0, REGISTER_X);
  char *captured = copy_text(copyRegisterXToClipboard);
  if (captured == NULL || strcmp(captured, "7.") != 0) {
    fprintf(stderr, "FAIL: the Copy menu's copy of 7 gave '%s'\n",
            captured != NULL ? captured : "(null)");
    failures++;
  }
  free(captured);
  if (!stderr_holds("a copy after the capture", copyRegisterXToClipboard,
                    "a copy outside the Copy menu was dropped")) {
    fprintf(stderr, "FAIL: a copy after the capture was not dropped and logged\n");
    failures++;
  }
  return failures;
}

// Whether text is whole UTF-8: no sequence cut short, none overlong, no surrogate,
// none past the BMP, which is what NewStringUTF takes.
static bool whole_utf8(const char *text) {
  const unsigned char *p = (const unsigned char *)text;
  while (*p) {
    int extra = *p < 0x80 ? 0 : (*p & 0xE0) == 0xC0 ? 1 : (*p & 0xF0) == 0xE0 ? 2 : -1;
    if (extra < 0 || (extra == 1 && *p < 0xC2) || (*p == 0xE0 && p[1] < 0xA0) ||
        (*p == 0xED && p[1] > 0x9F)) {
      return false;
    }
    for (int i = 1; i <= extra; i++) {
      if ((p[i] & 0xC0) != 0x80) {
        return false;
      }
    }
    p += extra + 1;
  }
  return true;
}

// The values a matrix copy holds: upstream writes one a line after its "RxC" head.
static size_t copied_values(const char *text) {
  size_t lines = 0;
  for (const char *p = text; *p; p++) {
    lines += *p == '\n';
  }
  return lines;
}

// Fill X with an n by n matrix of one long value, real or complex.
static void fill_matrix_x(uint16_t n, bool complex) {
  real34_t value;
  stringToReal34("-1.234567890123456789012345678901234E-6000", &value);
  if (complex) {
    complex34Matrix_t m;
    if (!complexMatrixInit(&m, n, n)) {
      fprintf(stderr, "FATAL: no room for a %ux%u complex matrix\n", n, n);
      exit(2);
    }
    for (uint32_t i = 0; i < (uint32_t)n * n; i++) {
      real34Copy(&value, (real34_t *)&m.matrixElements[i]);
      real34Copy(&value, ((real34_t *)&m.matrixElements[i]) + 1);
    }
    convertComplex34MatrixToComplex34MatrixRegister(&m, REGISTER_X);
    complexMatrixFree(&m);
    return;
  }
  real34Matrix_t m;
  if (!realMatrixInit(&m, n, n)) {
    fprintf(stderr, "FATAL: no room for a %ux%u matrix\n", n, n);
    exit(2);
  }
  for (uint32_t i = 0; i < (uint32_t)n * n; i++) {
    real34Copy(&value, &m.matrixElements[i]);
  }
  convertReal34MatrixToReal34MatrixRegister(&m, REGISTER_X);
  realMatrixFree(&m);
}

// A copy of a large matrix gives every value, whole UTF-8, as upstream's builder
// sizes it from the register: a 30x30 and a 60x60 real, and a 20x20 and a 40x40
// complex.
static int check_matrix_copies_are_whole(void) {
  int failures = 0;
  const struct {
    uint16_t n;
    bool complex;
  } cases[] = {{30, false}, {60, false}, {20, true}, {40, true}};
  for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
    fill_matrix_x(cases[i].n, cases[i].complex);
    char *text = copy_text(copyRegisterXToClipboard);
    const size_t want = (size_t)cases[i].n * cases[i].n;
    const size_t got = text ? copied_values(text) : 0;
    if (got != want || !whole_utf8(text ? text : "")) {
      fprintf(stderr, "FAIL: a %ux%u %s matrix copied %zu of %zu values%s\n", cases[i].n,
              cases[i].n, cases[i].complex ? "complex" : "real", got, want,
              text && whole_utf8(text) ? "" : ", not whole UTF-8");
      failures++;
    }
    free(text);
  }
  return failures;
}

int main(void) {
  // A fresh directory each run: init restores the state file a run saves, and
  // the sums check counts one Sigma+ on a fresh start.
  char runtime_dir[] = "/tmp/r47-clipboard-overflow-harness-XXXXXX";
  if (mkdtemp(runtime_dir) == NULL) {
    fprintf(stderr, "FATAL: no runtime directory\n");
    return 2;
  }

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime();

  int failures = check_statistical_sum_rows();
  failures += check_paste_number();
  failures += check_snap(runtime_dir);
  failures += check_file_answer_with_no_request();
  failures += check_file_answer_name();
  failures += check_string_fallback_after_out_of_memory();
  failures += check_display_copy_makes_no_call_in_critical();
  failures += check_matrix_copies_are_whole();
  failures += check_capture_ends_with_the_copy();
  failures += check_aim_faces_follow_num_lock();
  failures += check_volume_softkeys_show_no_value();
  failures += check_display_text();
  failures += check_canceled_key();

  // Ten numbered registers of 2^60000 make the all-registers copy about 220 KB,
  // and X and Y of it make the stack copy twice the digits. Keep the set inside
  // the register pool, and fail when a register does not take the value: the core
  // then leaves the register as it was, a real after the reset, and the copies
  // would be short for that reason alone.
  const calcRegister_t filled[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, REGISTER_X,
                                   REGISTER_Y};
  longInteger_t big;
  longIntegerInit(big);
  longInteger2Pow(60000, big);
  for (size_t i = 0; i < sizeof(filled) / sizeof(filled[0]); i++) {
    convertLongIntegerToLongIntegerRegister(big, filled[i]);
    if (getRegisterDataType(filled[i]) != dtLongInteger) {
      fprintf(stderr, "FAIL: register %d did not take 2^60000; the pool is full\n",
              filled[i]);
      longIntegerFree(big);
      return 1;
    }
  }
  longIntegerFree(big);

  // 2^60000 has 18062 digits, so a whole copy of X holds them all and the
  // all-registers copy holds twelve of them, which a cut at any fixed cap loses.
  const size_t digits = 18062;
  const struct {
    void (*copy)(void);
    size_t atLeast;
    const char *name;
  } copies[] = {{copyRegisterXToClipboard, digits, "X"},
                {copyStackRegistersToClipboard, 2 * digits, "stack"},
                {copyAllRegistersToClipboard, 12 * digits, "all-registers"}};
  for (size_t i = 0; i < sizeof(copies) / sizeof(copies[0]); i++) {
    char *text = copy_text(copies[i].copy);
    const size_t length = text != NULL ? strlen(text) : 0;
    fprintf(stderr, "%s copy length = %zu\n", copies[i].name, length);
    if (length < copies[i].atLeast || !whole_utf8(text != NULL ? text : "")) {
      fprintf(stderr, "FAIL: the %s copy is cut: %zu bytes, want %zu or more\n",
              copies[i].name, length, copies[i].atLeast);
      failures++;
    }
    free(text);
  }

  if (failures != 0) {
    fprintf(stderr, "FAIL: %d check(s) failed\n", failures);
    return 1;
  }
  fprintf(stderr, "DONE: the copies are whole and produced no memory fault\n");
  return 0;
}
