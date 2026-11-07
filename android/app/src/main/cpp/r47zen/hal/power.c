// The power hooks mainLoop calls. Android has no auto-off and no battery to watch;
// switching the LCD off ends the main loop, as on upstream's simulator, and
// r47_calculator_main then saves and tells the host.
#include "c47.h"

void LCD_power_on(void) {}

void LCD_power_off(int mode) {
  (void)mode;
  backToDMCP = true;
}

void draw_power_off_image(int mode) { (void)mode; }
