#include "c47.h"
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>

#define LCD_ROW_SIZE_BYTES 52u

extern uint8_t *lcd_buffer;

// Upstream PC_BUILD helpers compiled into Android still expect a valid
// framebuffer symbol for screenshot and menu-export code paths, so keep this
// compatibility surface updated even though JNI no longer exports it.
uint32_t *screenData = NULL;

// Android consumes a packed LCD snapshot. Keep it separate from the live
// lcd_buffer so native refresh paths can still clear row dirty flags.
uint8_t *packedDisplayBuffer = NULL;
pthread_mutex_t packedDisplayMutex = PTHREAD_MUTEX_INITIALIZER;

bool lcdBufferDirty = false;

static uint64_t hostLcdRefreshCount = 0;

static void writeCompatScreenRow(const uint8_t *line_buf) {
  if (!screenData || !line_buf) {
    return;
  }

  const uint8_t row_id = line_buf[1];
  if (row_id >= SCREEN_HEIGHT) {
    return;
  }

  uint32_t *line_start = screenData + (SCREEN_HEIGHT - row_id) * screenStride - 1;
  for (int byte_index = 0; byte_index < 50; byte_index++) {
    const uint8_t packed = line_buf[byte_index + 2];
    for (int bit = 0; bit < 8; bit++) {
      *(line_start - byte_index * 8 - bit) =
          ((packed >> bit) & 1u) ? ON_PIXEL : OFF_PIXEL;
    }
  }
}

uint64_t r47_get_host_lcd_refresh_count(void) {
  return hostLcdRefreshCount;
}

void r47_reset_host_lcd_refresh_count(void) {
  hostLcdRefreshCount = 0;
}

static void markAllRowsDirty(void) {
  if (!lcd_buffer) {
    return;
  }

  for (uint8_t row = 0; row < SCREEN_HEIGHT; row++) {
    lcd_buffer[row * LCD_ROW_SIZE_BYTES] = 1u;
  }
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_setLcdColors(JNIEnv *env,
                                               jobject thiz,
                                               jint text,
                                               jint bg) {
  (void)env;
  (void)thiz;
  (void)text;
  (void)bg;
  markAllRowsDirty();
  lcd_refresh();
}

void init_lcd_buffers() {
  if (!screenData) {
    screenData = (uint32_t *)malloc(SCREEN_WIDTH * SCREEN_HEIGHT * sizeof(uint32_t));
  }

  if (!packedDisplayBuffer) {
    packedDisplayBuffer = (uint8_t *)malloc(SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES);
    memset(packedDisplayBuffer, 0, SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES);
    for (int row = 0; row < SCREEN_HEIGHT; row++) {
      packedDisplayBuffer[row * LCD_ROW_SIZE_BYTES] = 1u;
      packedDisplayBuffer[row * LCD_ROW_SIZE_BYTES + 1] =
          SCREEN_HEIGHT - row - 1;
    }
  }

  if (!lcd_buffer) {
    lcd_buffer = (uint8_t *)malloc(SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES);
    memset(lcd_buffer, 255, SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES);
    for (int row = 0; row < SCREEN_HEIGHT; row++) {
      lcd_buffer[row * LCD_ROW_SIZE_BYTES] = 1u;
      lcd_buffer[row * LCD_ROW_SIZE_BYTES + 1] = SCREEN_HEIGHT - row - 1;
    }
  }

  if (screenData) {
    for (int pixel = 0; pixel < SCREEN_WIDTH * SCREEN_HEIGHT; pixel++) {
      screenData[pixel] = OFF_PIXEL;
    }
  }

  lcdBufferDirty = true;
}

uint8_t *lcd_line_addr(int row) {
  if (!lcd_buffer) return NULL;
  lcd_buffer[LCD_ROW_SIZE_BYTES * row] = 1u;
  return lcd_buffer + row * LCD_ROW_SIZE_BYTES + 2;
}

void LCD_write_line(uint8_t *line_buf) {
  if (!line_buf || !packedDisplayBuffer) {
    return;
  }

  const uint8_t row_id = line_buf[1];
  if (row_id >= SCREEN_HEIGHT) {
    return;
  }

  writeCompatScreenRow(line_buf);

  const size_t buffer_row = (size_t)(SCREEN_HEIGHT - row_id - 1u);
  pthread_mutex_lock(&packedDisplayMutex);
  uint8_t *snapshot_line = packedDisplayBuffer + buffer_row * LCD_ROW_SIZE_BYTES;
  memcpy(snapshot_line, line_buf, LCD_ROW_SIZE_BYTES);
  snapshot_line[0] = 1u;
  line_buf[0] = 0u;
  lcdBufferDirty = true;
  pthread_mutex_unlock(&packedDisplayMutex);
}

void lcd_clear_buf() {
  if (!lcd_buffer) return;
  for (uint8_t row = 0; row < SCREEN_HEIGHT; row++) {
    uint8_t *line_buf = lcd_buffer + LCD_ROW_SIZE_BYTES * row;
    for (uint8_t column = 2; column < LCD_ROW_SIZE_BYTES; column++) {
      line_buf[column] = 255u;
    }
    line_buf[1] = SCREEN_HEIGHT - row - 1;
    line_buf[0] = 1u;
    LCD_write_line(line_buf);
  }
}

void lcd_refresh() {
  if (!lcd_buffer) return;
  hostLcdRefreshCount++;
  for (uint8_t row = 0; row < SCREEN_HEIGHT; row++) {
    if (lcd_buffer[LCD_ROW_SIZE_BYTES * row]) {
      LCD_write_line(&lcd_buffer[LCD_ROW_SIZE_BYTES * row]);
    }
  }
}

void lcd_refresh_lines(uint8_t ln, uint8_t cnt) {
  if (!lcd_buffer) return;
  for (uint8_t row = ln; row < ln + cnt && row < SCREEN_HEIGHT; row++) {
    LCD_write_line(&lcd_buffer[LCD_ROW_SIZE_BYTES * row]);
  }
}

void bitblt24(uint32_t x, uint32_t dx, uint32_t y, uint32_t val, int blt_op, int fill) {
  if (!lcd_buffer) return;
  if (dx < 1 || dx > 24) return;
  if (x >= SCREEN_WIDTH || x + dx > SCREEN_WIDTH) return;
  x = SCREEN_WIDTH - dx - x;
  const uint32_t byte_i = x >> 3;
  const uint32_t bit_off = x & 7u;
  const uint32_t lowmask = (1u << dx) - 1u;
  const uint32_t bytes_needed = (bit_off + dx + 7) / 8;
  uint32_t srcbits;
  if (fill == BLT_SET && blt_op != BLT_XOR) {
    srcbits = (blt_op == BLT_ANDN) ? lowmask << bit_off : 0u;
  } else {
    srcbits = (val & lowmask) << bit_off;
  }
  uint8_t srcbytes[4] = {
      (uint8_t)(srcbits),
      (uint8_t)(srcbits >> 8),
      (uint8_t)(srcbits >> 16),
      (uint8_t)(srcbits >> 24),
  };
  uint8_t *j = &lcd_buffer[y * LCD_ROW_SIZE_BYTES + byte_i + 2];
  switch (blt_op) {
    case BLT_OR:
      for (uint32_t i = 0; i < bytes_needed; i++) j[i] |= srcbytes[i];
      break;
    case BLT_XOR:
      for (uint32_t i = 0; i < bytes_needed; i++) j[i] ^= srcbytes[i];
      break;
    case BLT_ANDN:
      for (uint32_t i = 0; i < bytes_needed; i++) j[i] &= ~srcbytes[i];
      break;
    default:
      return;
  }
  lcd_buffer[y * LCD_ROW_SIZE_BYTES] = 1u;
}

void lcd_fill_rect(uint32_t x, uint32_t y, uint32_t dx, uint32_t dy, int val) {
  uint32_t line, col, cols, endX = x + dx, endY = y + dy;
  if (endX > SCREEN_WIDTH || endY > SCREEN_HEIGHT) return;
  int blt_op = val ? BLT_OR : BLT_ANDN;
  for (col = x; col < endX; col += 24) {
    cols = (24 < endX - col) ? 24 : (endX - col);
    for (line = y; line < endY; line++) {
      bitblt24(col, cols, line, 0xFFFFFF, blt_op, BLT_NONE);
    }
  }
}

void _lcdRefresh(void) { lcd_refresh(); }
void _lcdBandRefresh(uint32_t y, uint32_t dy) { (void)y; (void)dy; lcd_refresh(); }
void _lcdSBRefresh(void) { lcd_refresh(); }
void refresh_gui(void) {}
