// Host regression guard for the clipboard register-dump buffer overflow.
// getClipboardAllRegistersString / getClipboardStackRegistersString /
// getClipboardXRegisterString in android_helpers.c build a register dump into a
// fixed ANDROID_CLIPSTR (30000) byte buffer, which the getters must not overrun.
// Concatenating a span of registers holding large long integers with unbounded
// sprintf/strcat/stringToUtf8 would exceed that cap.
//
// This harness fills ten numbered registers, X, and Y with a huge long integer
// (2^60000, 18062 decimal digits) so the all-registers and stack dumps run far
// past the 30000-byte cap, then calls each clipboard getter. Built under
// AddressSanitizer: any overrun aborts with a stack trace. The dump is bounded
// (truncated at the cap) and the run stays clean.
//
// First, before any register is large, it dumps the statistical sums and holds
// each SR row to the name upstream gives that sum (check_statistical_sum_rows).

#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

// The clipboard getters in android_helpers.c, which no header declares.
extern char *getClipboardAllRegistersString(void);
extern char *getClipboardStackRegistersString(void);
extern char *getClipboardXRegisterString(void);
extern char *getXRegisterString(void);

// The initializer of upstream's StatSumNames, local to
// copyAllRegistersToClipboard in screen.c, which
// build_clipboard_overflow_harness.sh extracts from the core this harness is
// built from. Sizing the array from it fails the build when upstream's own
// table leaves a sum unnamed.
static const char *const upstreamStatSumNames[] = {
#include "upstream_stat_sum_names.inc"
};
_Static_assert(sizeof(upstreamStatSumNames) / sizeof(upstreamStatSumNames[0]) ==
                   NUMBER_OF_STATISTICAL_SUMS,
               "upstream's StatSumNames must name every statistical sum");

// Dump all registers after one Sigma+ of x = 2 and y = 3, and fail every SR
// row that does not carry upstream's name for its sum. android_helpers.c keeps
// its own copy of the names, so this is what holds the copy to upstream. Two
// values tie the names to the sums they label: SR14, x^2 ln(y), is 4 ln 3, and
// SR23, x^4, is 16. Return the number of failures.
static int check_statistical_sum_rows(void) {
  convertDoubleToReal34Register(3.0, REGISTER_Y);
  convertDoubleToReal34Register(2.0, REGISTER_X);
  fnSigmaAddRem(SIGMA_PLUS);
  if (statisticalSumsPointer == NULL) {
    fprintf(stderr, "FAIL: Sigma+ allocated no statistical sums to dump\n");
    return 1;
  }

  const char *all = getClipboardAllRegistersString();
  int failures = 0;
  for (int sum = 0; sum < NUMBER_OF_STATISTICAL_SUMS; sum++) {
    char nameUtf8[160];
    char row[200];
    stringToUtf8(upstreamStatSumNames[sum], (uint8_t *)nameUtf8);
    snprintf(row, sizeof(row), "\nSR%02d = %s = ", sum, nameUtf8);

    const char *at = strstr(all, row);
    if (at == NULL) {
      fprintf(stderr, "FAIL: the dump has no row \"SR%02d = %s\"\n", sum,
              nameUtf8);
      failures++;
      continue;
    }
    const char *value = at + strlen(row);
    if (sum == SUM_X2lnY && strncmp(value, "4.394449154672438765", 20) != 0) {
      fprintf(stderr, "FAIL: SR%02d is not 4 ln 3: %.40s\n", sum, value);
      failures++;
    }
    if (sum == SUM_X4 && strtod(value, NULL) != 16.0) {
      fprintf(stderr, "FAIL: SR%02d is not 16: %.40s\n", sum, value);
      failures++;
    }
  }
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
    used += (size_t)snprintf(out + used, size - used, "[%s]", tmpString);
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

int main(void) {
  const char *runtime_dir = "/tmp/r47-clipboard-overflow-harness";
  mkdir(runtime_dir, 0700);

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

  int failures = check_statistical_sum_rows();
  failures += check_paste_number();

  // Ten numbered registers of 2^60000 make the all-registers dump about 180 KB,
  // six times the ANDROID_CLIPSTR cap, and X and Y of it run the stack dump past
  // the cap as well. Keep the set inside the register pool, and fail when a
  // register does not take the value: the core then leaves the register as it
  // was, a real after the reset, and the dumps would stop short of the cap.
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

  char *all = getClipboardAllRegistersString();
  fprintf(stderr, "all-registers dump length = %zu\n", strlen(all));

  char *stack = getClipboardStackRegistersString();
  fprintf(stderr, "stack dump length = %zu\n", strlen(stack));

  char *x = getClipboardXRegisterString();
  fprintf(stderr, "x dump length = %zu\n", strlen(x));

  // ascii_clean glyph-expansion overflow guard. getXRegisterString strncpy's a
  // dtString register's text into a 1024-byte coreBuf and runs ascii_clean on
  // it. ascii_clean expands STD_op_i (0xA1 0x48) to three output bytes, so a loop
  // guard that checked only the cursor at the START of each iteration would let an
  // op_i processed at cursor 1022 write one byte past the 1024-byte tmp[]
  // scratch (plus the terminator two past). Build a register whose text is 722
  // single-byte chars followed by op_i glyphs: the glyph output cursor lands on
  // 1022 exactly at the 101st glyph, tripping the overrun under AddressSanitizer
  // unless the guard truncates cleanly at the cap.
  static char strdata[1200];
  size_t p = 0;
  for (; p < 722; p++) {
    strdata[p] = 'A';
  }
  while (p < 1022) {
    strdata[p++] = (char)0xA1;
    strdata[p++] = 0x48;
  }
  strdata[p] = '\0';
  if (!reallocateRegister(REGISTER_X, dtString, TO_BLOCKS(p + 1), amNone)) {
    fprintf(stderr, "FAIL: the pool cannot hold the %zu-byte string in X\n",
            p + 1);
    return 1;
  }
  memcpy(REGISTER_STRING_DATA(REGISTER_X), strdata, p + 1);
  char *xreg = getXRegisterString();
  fprintf(stderr, "getXRegisterString length = %zu\n", strlen(xreg));

  if (failures != 0) {
    fprintf(stderr, "FAIL: %d check(s) failed\n", failures);
    return 1;
  }
  fprintf(stderr, "DONE: clipboard register dumps produced no memory fault\n");
  return 0;
}
