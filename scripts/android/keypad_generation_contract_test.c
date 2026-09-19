// Host regression guard for the hal/lcd.c contracts: the keypad-snapshot
// generation bump, and the bitblt24 blit semantics the upstream core relies on.
//
// The Android display loop re-reads the keypad/softkey snapshot only when
// keypadSnapshotGeneration changes. Dynamic softmenus -- notably the EQN editor
// (MNU_EQN) -- rebuild their softkey labels on the screen-refresh path
// (refreshScreen -> LCD_write_line) with no key event at the instant the labels
// change. So LCD_write_line MUST bump keypadSnapshotGeneration; decoupling that
// bump leaves the EQN softkeys stale (wrong buttons) until the display loop's
// 500 ms fallback. This test fails if that refresh coupling is removed.
//
// Built and run on the Linux host by run_keypad_generation_contract.sh; no
// emulator or device is required.

#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// hal/lcd.c defines this as a C11 relaxed atomic (the lock-free display-change
// signal); match the type so the bare reads below are valid atomic loads.
extern _Atomic uint32_t keypadSnapshotGeneration;
extern uint32_t *screenData;
extern void init_lcd_buffers(void);
extern void LCD_write_line(uint8_t *line_buf);

// Normally provided by android_runtime.c; defined here so the test can link
// against hal/lcd.c alone without pulling in the whole core.
int16_t screenStride = 400;
uint8_t *lcd_buffer = NULL;

// --- bitblt24 blit semantics -------------------------------------------------
//
// hal/lcd.c implements the upstream bitblt24 contract documented in
// src/c47/hal/lcd.h. The `fill` argument is the half that compiles either way
// and therefore cannot be caught by a build:
//
//   BLT_NONE - only the pixels where val has a 1 are written.
//   BLT_SET  - the pixels where val has a 0 are written as well: the dx columns
//              are cleared before BLT_OR and set before BLT_ANDN.
//
// Upstream 492298ff both restated that contract and produced the first core
// caller that depends on it: softmenus.c drawKeyFrame, the dotted softkey frame,
// was rewritten from greyRect plus lcd_fill_rect onto one BLT_OR plus BLT_SET
// blit per 24 columns. Nothing passed BLT_SET before that. The older Android
// reading replaced srcbits outright, which turned that call into `j |= 0` -- a
// silent no-op that drew no frame at all. These checks fail if it comes back.
//
// Asserted by set-bit population over the whole row, so they stay valid
// independently of the Android column mirroring inside bitblt24.

// Row 0's pixel bytes: LCD_ROW_SIZE_BYTES (52) in
// android/app/src/main/cpp/r47zen/hal/lcd.h is two header bytes plus these 50,
// and SCREEN_WIDTH comes from the upstream src/c47/defines.h. Kept local so this
// test links against hal/lcd.c alone, like the externs above.
#define ROW_DATA_OFFSET 2u
#define ROW_DATA_BYTES 50u
#define SCREEN_WIDTH_PX 400

// Upstream src/c47/hal/lcd.h: blt_op_t and blt_fill_t.
#define BLT_OR 0
#define BLT_ANDN 1
#define BLT_XOR 2
#define BLT_NONE 0
#define BLT_SET 1

extern void bitblt24(uint32_t x, uint32_t dx, uint32_t y, uint32_t val,
                     int blt_op, int fill);

// Row 0 only; the dirty flag and row-index header bytes are left untouched.
static void fill_row(uint8_t value) {
  memset(lcd_buffer + ROW_DATA_OFFSET, value, ROW_DATA_BYTES);
}

static int row_set_bits(void) {
  int bits = 0;
  for (unsigned int i = 0; i < ROW_DATA_BYTES; i++) {
    uint8_t byte = lcd_buffer[ROW_DATA_OFFSET + i];
    while (byte) {
      bits += byte & 1u;
      byte >>= 1;
    }
  }
  return bits;
}

static int expect_set_bits(const char *what, int expected) {
  const int actual = row_set_bits();
  if (actual != expected) {
    fprintf(stderr, "FAIL: %s: expected %d set pixel(s) in the row, got %d\n",
            what, expected, actual);
    return 1;
  }
  printf("OK: %s (%d set pixel(s))\n", what, actual);
  return 0;
}

static int check_bitblt24_fill_semantics(void) {
  const uint32_t dx = 24u;
  int failures = 0;

  // BLT_OR + BLT_SET clears the dx columns first, so val == 0 blanks them.
  // The pre-492298ff reading left the row untouched here.
  fill_row(0xFFu);
  bitblt24(0u, dx, 0u, 0u, BLT_OR, BLT_SET);
  failures |= expect_set_bits("BLT_OR with BLT_SET clears the dx columns",
                              SCREEN_WIDTH_PX - (int)dx);

  // BLT_ANDN + BLT_SET sets the dx columns first, so val == 0 leaves them set.
  // The pre-492298ff reading left the row untouched here too.
  fill_row(0x00u);
  bitblt24(0u, dx, 0u, 0u, BLT_ANDN, BLT_SET);
  failures |= expect_set_bits("BLT_ANDN with BLT_SET sets the dx columns",
                              (int)dx);

  // BLT_SET must not widen a partial value: only val's 1 bits survive the
  // clear, so an alternating pattern keeps exactly half the columns.
  fill_row(0xFFu);
  bitblt24(0u, dx, 0u, 0xAAAAAAu, BLT_OR, BLT_SET);
  failures |= expect_set_bits("BLT_OR with BLT_SET writes val's 0 bits as 0",
                              SCREEN_WIDTH_PX - (int)dx / 2);

  // BLT_NONE is the untouched half of the contract: only val's 1 bits are
  // written, the 0 bits leave the destination alone.
  fill_row(0x00u);
  bitblt24(0u, dx, 0u, 0xFFFFFFu, BLT_OR, BLT_NONE);
  failures |= expect_set_bits("BLT_OR with BLT_NONE writes only val's 1 bits",
                              (int)dx);

  // "Value of fill doesn't apply for BLT_XOR" -- both fills must agree.
  fill_row(0x00u);
  bitblt24(0u, dx, 0u, 0xFFFFFFu, BLT_XOR, BLT_SET);
  failures |= expect_set_bits("BLT_XOR ignores BLT_SET", (int)dx);

  return failures;
}

int main(void) {
  init_lcd_buffers();
  if (lcd_buffer == NULL) {
    fprintf(stderr, "FAIL: init_lcd_buffers did not allocate lcd_buffer\n");
    return 1;
  }

  // screenData is a NULL compatibility symbol referenced only by the compiled,
  // never-invoked PC_BUILD GTK screenshot helpers. It must not be allocated:
  // nothing on Android reads it, so a 384 KB framebuffer here would be dead
  // resident memory.
  if (screenData != NULL) {
    fprintf(stderr,
            "FAIL: init_lcd_buffers allocated screenData (%p); the unused "
            "compatibility framebuffer must stay NULL.\n",
            (void *)screenData);
    return 1;
  }
  printf("OK: screenData left unallocated (no dead framebuffer)\n");

  const uint32_t before = keypadSnapshotGeneration;

  // Refresh one row, exactly as the upstream refresh path does for a changed
  // line. This must invalidate the keypad snapshot for the consumer.
  lcd_buffer[0] = 1u;  // mark row 0 dirty
  LCD_write_line(lcd_buffer);

  if (keypadSnapshotGeneration == before) {
    fprintf(stderr,
            "FAIL: LCD_write_line did not bump keypadSnapshotGeneration "
            "(stayed %u). EQN and other dynamic softkeys would go stale.\n",
            (unsigned int)before);
    return 1;
  }

  printf("OK: LCD_write_line bumped keypadSnapshotGeneration %u -> %u\n",
         (unsigned int)before, (unsigned int)keypadSnapshotGeneration);

  return check_bitblt24_fill_semantics();
}
