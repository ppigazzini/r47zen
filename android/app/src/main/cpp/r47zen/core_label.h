#ifndef R47_CORE_LABEL_H
#define R47_CORE_LABEL_H

#include <stddef.h>
#include <string.h>

// Copy the core-encoded string src into dst, which holds cap bytes, and
// NUL-terminate it, cutting only between glyphs. In the core's encoding a byte
// with bit 7 set opens a two-byte glyph (charString.c stringNextGlyph), so a
// byte-count cut can keep a lead byte and drop its trail; stringToUtf8 then
// reads the terminator as the trail and exports one stray code point. A lead
// byte with no trail in src is dropped too. run_core_label_copy_contract.sh
// proves it.
static inline void r47_copy_core_label(char *dst, size_t cap, const char *src) {
  size_t used = 0;
  if (cap == 0) {
    return;
  }
  if (src != NULL) {
    while (src[used] != '\0') {
      const size_t glyph = ((unsigned char)src[used] & 0x80u) ? 2u : 1u;
      if (glyph == 2u && src[used + 1] == '\0') {
        break;
      }
      if (used + glyph > cap - 1u) {
        break;
      }
      used += glyph;
    }
    memcpy(dst, src, used);
  }
  dst[used] = '\0';
}

#endif
