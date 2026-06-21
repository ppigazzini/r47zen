// Host regression guard for the clipboard register-dump buffer overflow.
// getClipboardAllRegistersString / getClipboardStackRegistersString /
// getClipboardXRegisterString in android_helpers.c build a register dump into a
// fixed ANDROID_CLIPSTR (30000) byte buffer. The pre-fix code concatenated every
// register with unbounded sprintf/strcat/stringToUtf8, so filling a span of
// registers with large long integers overran the buffer.
//
// This harness stuffs the global numbered registers with a huge long integer
// (2^60000, ~18000 decimal digits each) so the concatenated dump is megabytes,
// far past the 30000-byte cap, then calls each clipboard getter. Built under
// AddressSanitizer: any overrun aborts with a stack trace. After the fix the
// dump is bounded (truncated at the cap) and the run is clean.

#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

extern void r47_initialize_native_bridge_state(void);
extern void r47_native_preinit_path(const char *path);
extern void r47_init_runtime(int slotId);

extern char *getClipboardAllRegistersString(void);
extern char *getClipboardStackRegistersString(void);
extern char *getClipboardXRegisterString(void);
extern void convertLongIntegerToLongIntegerRegister(const longInteger_t lgInt,
                                                    calcRegister_t regist);

int main(void) {
  const char *runtime_dir = "/tmp/r47-clipboard-overflow-harness";
  mkdir(runtime_dir, 0700);

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

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

  fprintf(stderr, "DONE: clipboard register dumps produced no memory fault\n");
  return 0;
}
