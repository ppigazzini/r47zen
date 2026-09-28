#ifndef R47ZEN_HAL_LCD_H
#define R47ZEN_HAL_LCD_H

// Stride, in bytes, of one packed LCD row shared by the HAL packing code and
// the JNI display bridge. Each row is two header bytes (dirty flag + row index)
// followed by the packed pixel bytes for one 400 px scanline, so a mismatch
// between the packer (hal/lcd.c) and the reader (jni_display.c) silently
// corrupts the snapshot copied to Kotlin. Keep it the single source of truth so
// the two sides cannot drift; the Kotlin mirror lives in
// R47LcdContract.PACKED_ROW_SIZE_BYTES on the other side of the JNI boundary.
#define LCD_ROW_SIZE_BYTES 52u

// Set every row's dirty byte in lcd_buffer, so the next lcd_refresh copies the
// whole screen into the packed snapshot. The caller holds screenMutex: the core
// writes the same bytes while it draws.
void lcd_mark_all_rows_dirty(void);

#endif // R47ZEN_HAL_LCD_H
