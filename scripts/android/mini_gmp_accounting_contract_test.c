// Host contract test for the size-prefixed allocation in
// android/compat/mini-gmp-fallback/mini-gmp.c, the GMP the Android build
// ships.
//
// The core counts GMP memory through its mp_set_memory_functions hooks
// (allocGmp, reallocGmp, freeGmp in upstream memory.c): an alloc adds the size
// it is handed, a realloc adds new minus old, a free subtracts its size, and on
// a PC_BUILD the core reports an error after any operation that leaves the
// count non-zero. Upstream mini-gmp hands the free and realloc hooks size 0,
// so the count only grows. This test installs hooks that count the same way and
// also remember the size of every block they handed out, runs operations that
// allocate inside mini-gmp, and requires that every free and realloc names the
// size its block holds and that the count returns to zero.
//
// Run via scripts/android/run_mini_gmp_accounting_contract.sh (no SDK, no
// staged native tree required).

#include "gmp.h"

#include <stdio.h>
#include <stdlib.h>

#define MAX_LIVE_BLOCKS 256

struct block {
  void *base;
  size_t size;
};

static struct block live[MAX_LIVE_BLOCKS];
static size_t live_count = 0;
// What upstream's gmpMemInBytes would hold.
static long long counted_bytes = 0;
static unsigned long frees = 0;
static unsigned long reallocs = 0;
static int failures = 0;

static void fail(const char *what, size_t got, size_t want) {
  fprintf(stderr, "FAIL: %s: got %zu, want %zu\n", what, got, want);
  failures++;
}

static size_t find_live(void *base) {
  for (size_t i = 0; i < live_count; i++) {
    if (live[i].base == base) {
      return i;
    }
  }
  fprintf(stderr, "FAIL: a hook was handed %p, which no alloc returned\n",
          base);
  exit(EXIT_FAILURE);
}

static void *counting_alloc(size_t size) {
  if (live_count == MAX_LIVE_BLOCKS) {
    fprintf(stderr, "FAIL: more than %d live GMP blocks\n", MAX_LIVE_BLOCKS);
    exit(EXIT_FAILURE);
  }
  void *base = malloc(size);
  if (base == NULL) {
    exit(EXIT_FAILURE);
  }
  live[live_count++] = (struct block){base, size};
  counted_bytes += (long long)size;
  return base;
}

static void *counting_realloc(void *base, size_t old_size, size_t new_size) {
  size_t i = find_live(base);
  reallocs++;
  if (old_size != live[i].size) {
    fail("realloc old size", old_size, live[i].size);
  }
  void *moved = realloc(base, new_size);
  if (moved == NULL) {
    exit(EXIT_FAILURE);
  }
  counted_bytes += (long long)new_size - (long long)old_size;
  live[i] = (struct block){moved, new_size};
  return moved;
}

static void counting_free(void *base, size_t size) {
  size_t i = find_live(base);
  frees++;
  if (size != live[i].size) {
    fail("free size", size, live[i].size);
  }
  counted_bytes -= (long long)size;
  live[i] = live[--live_count];
  free(base);
}

static void expect_count_zero(const char *after) {
  if (counted_bytes != 0 || live_count != 0) {
    fprintf(stderr,
            "FAIL: after %s the count holds %lld bytes in %zu live block(s)\n",
            after, counted_bytes, live_count);
    failures++;
  }
}

int main(void) {
  mp_set_memory_functions(counting_alloc, counting_realloc, counting_free);

  // An integer grown in place (a realloc), scratch buffers freed inside
  // mini-gmp, and a string parse that allocates and frees its digit buffer.
  mpz_t a, b, c;
  mpz_init(a);
  mpz_init(b);
  mpz_init(c);
  mpz_set_ui(a, 1);
  for (int i = 0; i < 8; i++) {
    mpz_mul_2exp(a, a, 256);
  }
  mpz_fac_ui(a, 300);
  mpz_set_str(b, "123456789012345678901234567890123456789", 10);
  mpz_mul(c, a, b);
  mpz_pow_ui(c, c, 3);
  mpz_tdiv_q(a, c, b);
  mpz_sqrt(b, a);
  mpz_powm(c, a, b, c);
  char digits[4096];
  mpz_get_str(digits, 10, b);
  if (mpz_sizeinbase(a, 7) == 0) {
    fail("mpz_sizeinbase", 0, 1);
  }
  mpz_clear(a);
  mpz_clear(b);
  mpz_clear(c);
  expect_count_zero("clearing every integer");

  // A parse that fails frees its digit buffer on the error path.
  mpz_init(a);
  if (mpz_set_str(a, "12x34", 10) != -1) {
    fail("mpz_set_str of a bad digit", 0, 1);
  }
  mpz_clear(a);
  expect_count_zero("a failed parse");

  if (frees == 0 || reallocs == 0) {
    fprintf(stderr,
            "FAIL: the workload made %lu free(s) and %lu realloc(s); it must "
            "make both or it proves nothing\n",
            frees, reallocs);
    failures++;
  }

  if (failures != 0) {
    fprintf(stderr, "FAIL: %d mini-gmp accounting check(s) failed\n", failures);
    return EXIT_FAILURE;
  }

  printf("OK: mini-gmp hands every free and realloc the size of its block "
         "(%lu frees, %lu reallocs), and the count returns to zero.\n",
         frees, reallocs);
  return EXIT_SUCCESS;
}
