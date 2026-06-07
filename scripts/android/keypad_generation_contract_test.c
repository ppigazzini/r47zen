// Host regression guard for the keypad-snapshot generation contract.
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
// emulator or device is required. See REPORT-25 Annex A.2.

#include <stdint.h>
#include <stdio.h>

extern volatile uint32_t keypadSnapshotGeneration;
extern void init_lcd_buffers(void);
extern void LCD_write_line(uint8_t *line_buf);

// Normally provided by android_runtime.c; defined here so the test can link
// against hal/lcd.c alone without pulling in the whole core.
int16_t screenStride = 400;
uint8_t *lcd_buffer = NULL;

int main(void) {
  init_lcd_buffers();
  if (lcd_buffer == NULL) {
    fprintf(stderr, "FAIL: init_lcd_buffers did not allocate lcd_buffer\n");
    return 1;
  }

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
  return 0;
}
