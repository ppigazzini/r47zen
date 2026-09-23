// Host regression guard for the hal/lcd.c contracts: the keypad-snapshot
// generation bump, the bitblt24 blit semantics the upstream core relies on, and
// the lcd_buffer polarity those semantics are stated in.
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
#include <stdbool.h>
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

// --- bitblt24 blit semantics and lcd_buffer polarity ------------------------
//
// hal/lcd.c implements the upstream bitblt24 contract of src/c47/hal/lcd.h, on
// the lcd_buffer polarity of the upstream simulator HALs (c47-gtk/hal/lcd.c and
// testSuite/hal/lcd.c). Upstream 1560394c moved both onto the DMCP convention:
// a 1 bit is a white pixel and a 0 bit a dark one. BLT_OR draws val's 1 bits
// dark, BLT_ANDN draws them white, and the `fill` argument decides what happens
// to val's 0 bits:
//
//   BLT_NONE - only the pixels where val has a 1 are written.
//   BLT_SET  - the pixels where val has a 0 are written as well: the dx columns
//              are written white before BLT_OR and dark before BLT_ANDN.
//
// Neither half is visible to a build, since every signature is unchanged.
// Upstream 492298ff produced the first caller that depends on BLT_SET,
// softmenus.c drawKeyFrame, and the older Android reading replaced srcbits
// outright, which drew no softkey frame at all. 1560394c produced the first
// core code that writes lcd_buffer bytes itself rather than through bitblt24:
// the function-name box in screen.c clears a bit for a dark pixel
// (lineSetBlackPixel) and sets one for white (lineSetWhiteRange). On the older
// polarity that box drew inverted, and nothing drawn through bitblt24 alone
// could show it, because swapping the two ops and the reader cancels out.
//
// Asserted by dark-pixel population over the whole row, so the checks stay
// valid independently of the Android column mirroring inside bitblt24.

// Row 0's pixel bytes: LCD_ROW_SIZE_BYTES (52) in
// android/app/src/main/cpp/r47zen/hal/lcd.h is two header bytes plus these 50,
// and SCREEN_WIDTH comes from the upstream src/c47/defines.h. Kept local so this
// test links against hal/lcd.c alone, like the externs above.
#define ROW_DATA_OFFSET 2u
#define ROW_DATA_BYTES 50u
#define SCREEN_WIDTH_PX 400
#define SCREEN_HEIGHT_PX 240

// Upstream src/c47/hal/lcd.h: blt_op_t, blt_fill_t, and the two lcd_fill_rect
// values, whose names say the opposite of what they fill.
#define BLT_OR 0
#define BLT_ANDN 1
#define BLT_XOR 2
#define BLT_NONE 0
#define BLT_SET 1
#define LCD_SET_VALUE 0
#define LCD_EMPTY_VALUE 255

extern void bitblt24(uint32_t x, uint32_t dx, uint32_t y, uint32_t val,
                     int blt_op, int fill);
extern void lcd_fill_rect(uint32_t x, uint32_t y, uint32_t dx, uint32_t dy,
                          int val);
extern void lcd_clear_buf(void);
extern bool lcd_buffer_pixel_on(uint32_t x, uint32_t y);
extern uint8_t *packedDisplayBuffer;

// Row 0 only; the dirty flag and row-index header bytes are left untouched.
static void fill_row(uint8_t value) {
  memset(lcd_buffer + ROW_DATA_OFFSET, value, ROW_DATA_BYTES);
}

static int count_zero_bits(const uint8_t *bytes) {
  int bits = 0;
  for (unsigned int i = 0; i < ROW_DATA_BYTES; i++) {
    for (uint8_t byte = (uint8_t)~bytes[i]; byte; byte >>= 1) {
      bits += byte & 1u;
    }
  }
  return bits;
}

static int expect_dark_pixels(const char *what, int expected) {
  const int actual = count_zero_bits(lcd_buffer + ROW_DATA_OFFSET);
  if (actual != expected) {
    fprintf(stderr, "FAIL: %s: expected %d dark pixel(s) in the row, got %d\n",
            what, expected, actual);
    return 1;
  }
  printf("OK: %s (%d dark pixel(s))\n", what, actual);
  return 0;
}

static int check_bitblt24_fill_semantics(void) {
  const uint32_t dx = 24u;
  int failures = 0;

  // BLT_OR + BLT_SET writes the dx columns white first, so val == 0 blanks
  // them. The pre-492298ff reading left the row untouched here.
  fill_row(0x00u);
  bitblt24(0u, dx, 0u, 0u, BLT_OR, BLT_SET);
  failures |= expect_dark_pixels("BLT_OR with BLT_SET whitens the dx columns",
                                 SCREEN_WIDTH_PX - (int)dx);

  // BLT_ANDN + BLT_SET writes the dx columns dark first, so val == 0 leaves
  // them dark. The pre-492298ff reading left the row untouched here too.
  fill_row(0xFFu);
  bitblt24(0u, dx, 0u, 0u, BLT_ANDN, BLT_SET);
  failures |= expect_dark_pixels("BLT_ANDN with BLT_SET darkens the dx columns",
                                 (int)dx);

  // BLT_SET must not widen a partial value: only val's 1 bits are drawn dark
  // over the whitened columns, so an alternating pattern darkens exactly half.
  fill_row(0x00u);
  bitblt24(0u, dx, 0u, 0xAAAAAAu, BLT_OR, BLT_SET);
  failures |= expect_dark_pixels("BLT_OR with BLT_SET writes val's 0 bits white",
                                 SCREEN_WIDTH_PX - (int)dx / 2);

  // BLT_NONE is the untouched half of the contract: only val's 1 bits are
  // written, the 0 bits leave the destination alone.
  fill_row(0xFFu);
  bitblt24(0u, dx, 0u, 0xFFFFFFu, BLT_OR, BLT_NONE);
  failures |= expect_dark_pixels("BLT_OR with BLT_NONE darkens only val's 1 bits",
                                 (int)dx);

  // "Value of fill doesn't apply for BLT_XOR" -- both fills must agree.
  fill_row(0xFFu);
  bitblt24(0u, dx, 0u, 0xFFFFFFu, BLT_XOR, BLT_SET);
  failures |= expect_dark_pixels("BLT_XOR ignores BLT_SET", (int)dx);

  // lcd_fill_rect maps LCD_SET_VALUE to BLT_ANDN and so fills white, and
  // LCD_EMPTY_VALUE to BLT_OR and so fills dark. Every upstream eraser passes
  // LCD_SET_VALUE, so reading the names literally blacks out the screen.
  fill_row(0x00u);
  lcd_fill_rect(0u, 0u, SCREEN_WIDTH_PX, 1u, LCD_SET_VALUE);
  failures |= expect_dark_pixels("lcd_fill_rect with LCD_SET_VALUE fills white",
                                 0);
  lcd_fill_rect(0u, 0u, SCREEN_WIDTH_PX, 1u, LCD_EMPTY_VALUE);
  failures |= expect_dark_pixels("lcd_fill_rect with LCD_EMPTY_VALUE fills dark",
                                 SCREEN_WIDTH_PX);

  return failures;
}

static int check_polarity(void) {
  int failures = 0;

  // A cleared screen is white: lcd_clear_buf writes 0xFF, and no pixel of it
  // may read as on through the accessor the screen and menu dumps use.
  lcd_clear_buf();
  int on = 0;
  for (uint32_t y = 0; y < SCREEN_HEIGHT_PX; y++) {
    for (uint32_t x = 0; x < SCREEN_WIDTH_PX; x++) {
      on += lcd_buffer_pixel_on(x, y) ? 1 : 0;
    }
  }
  if (on != 0) {
    fprintf(stderr,
            "FAIL: lcd_clear_buf left %d pixel(s) reading as on; a 1 bit must "
            "be white\n",
            on);
    failures |= 1;
  } else {
    printf("OK: lcd_clear_buf leaves every pixel off\n");
  }

  // A byte written directly, the way screen.c lineSetBlackPixel does, reads as
  // on where its bit is 0. Byte 2 holds the rightmost eight columns (the row is
  // mirrored), so clearing its low bit darkens x = SCREEN_WIDTH - 1.
  lcd_buffer[ROW_DATA_OFFSET] &= (uint8_t)~1u;
  if (!lcd_buffer_pixel_on(SCREEN_WIDTH_PX - 1u, 0u) ||
      lcd_buffer_pixel_on(SCREEN_WIDTH_PX - 2u, 0u)) {
    fprintf(stderr,
            "FAIL: a cleared lcd_buffer bit did not read as the one dark "
            "pixel at x = %d\n",
            SCREEN_WIDTH_PX - 1);
    failures |= 1;
  } else {
    printf("OK: a cleared lcd_buffer bit reads as a dark pixel\n");
  }

  // The packed snapshot handed to Kotlin keeps a 1 bit as a dark pixel, which
  // is what ReplicaOverlay.decodePackedRow paints, so LCD_write_line inverts.
  // lcd_buffer row 0 carries row id SCREEN_HEIGHT - 1 and lands in snapshot
  // row 0.
  bitblt24(0u, 24u, 0u, 0xFFFFFFu, BLT_OR, BLT_NONE);
  lcd_buffer[0] = 1u;
  LCD_write_line(lcd_buffer);
  const int dark = count_zero_bits(lcd_buffer + ROW_DATA_OFFSET);
  const int packed_set =
      ROW_DATA_BYTES * 8 - count_zero_bits(packedDisplayBuffer + ROW_DATA_OFFSET);
  if (packed_set != dark) {
    fprintf(stderr,
            "FAIL: the packed snapshot row has %d set bit(s) for %d dark "
            "pixel(s); LCD_write_line must invert\n",
            packed_set, dark);
    failures |= 1;
  } else {
    printf("OK: LCD_write_line packs %d dark pixel(s) as set bits\n", dark);
  }

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

  return check_bitblt24_fill_semantics() | check_polarity();
}
