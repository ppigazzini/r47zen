// Host regression guard for the clipboard register-dump buffer overflow.
// getClipboardAllRegistersString / getClipboardStackRegistersString /
// getClipboardXRegisterString in android_helpers.c build a register dump into a
// fixed ANDROID_CLIPSTR (30000) byte buffer, which the getters must not overrun.
// Concatenating a span of registers holding large long integers with unbounded
// sprintf/strcat/stringToUtf8 would exceed that cap.
//
// This harness stuffs the global numbered registers with a huge long integer
// (2^60000, ~18000 decimal digits each) so the concatenated dump is megabytes,
// far past the 30000-byte cap, then calls each clipboard getter. Built under
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

int main(void) {
  const char *runtime_dir = "/tmp/r47-clipboard-overflow-harness";
  mkdir(runtime_dir, 0700);

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

  int failures = check_statistical_sum_rows();

  // 2^60000 is about 18000 decimal digits; storing it in 100 global registers
  // makes the all-registers dump ~1.8 MB, ~60x the ANDROID_CLIPSTR cap.
  longInteger_t big;
  longIntegerInit(big);
  longInteger2Pow(60000, big);
  for (calcRegister_t r = 0; r < 100; r++) {
    convertLongIntegerToLongIntegerRegister(big, r);
  }
  // Also overfill the X stack register so the stack and X getters are exercised.
  convertLongIntegerToLongIntegerRegister(big, REGISTER_X);
  convertLongIntegerToLongIntegerRegister(big, REGISTER_Y);
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
  reallocateRegister(REGISTER_X, dtString, TO_BLOCKS(p + 1), amNone);
  memcpy(REGISTER_STRING_DATA(REGISTER_X), strdata, p + 1);
  char *xreg = getXRegisterString();
  fprintf(stderr, "getXRegisterString length = %zu\n", strlen(xreg));

  if (failures != 0) {
    fprintf(stderr, "FAIL: %d statistical sum row(s) differ from upstream\n",
            failures);
    return 1;
  }
  fprintf(stderr, "DONE: clipboard register dumps produced no memory fault\n");
  return 0;
}
