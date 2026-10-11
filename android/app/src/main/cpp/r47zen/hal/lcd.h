#ifndef R47ZEN_HAL_LCD_H
#define R47ZEN_HAL_LCD_H

#include <stdbool.h>
#include <stdint.h>

// Stride, in bytes, of one packed LCD row shared by the HAL packing code and
// the JNI display bridge. Each row is two header bytes (dirty flag + row index)
// followed by the packed pixel bytes for one 400 px scanline, so a mismatch
// between the packer (hal/lcd.c) and the reader (jni_display.c) silently
// corrupts the snapshot copied to Kotlin. Keep it the single source of truth so
// the two sides cannot drift; the Kotlin mirror lives in
// R47LcdContract.PACKED_ROW_SIZE_BYTES on the other side of the JNI boundary.
#define LCD_ROW_SIZE_BYTES 52u

// Copy the whole packed snapshot, SCREEN_HEIGHT rows of LCD_ROW_SIZE_BYTES, into
// dst and clear every row's change flag, so the next copy carries only rows
// LCD_write_line changed since. Every row's pixels are current whether or not
// its flag is set, so a consumer that has never decoded a frame decodes all of
// them. Takes packedDisplayMutex with a trylock and returns false, copying
// nothing, when the core holds it or the snapshot does not exist yet. A caller
// on another thread than the core must first see r47_runtime_ready(), whose
// acquire load publishes the snapshot pointer.
bool lcd_try_take_packed_snapshot(uint8_t *dst);

#endif // R47ZEN_HAL_LCD_H
