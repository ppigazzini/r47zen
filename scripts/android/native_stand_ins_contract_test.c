// Host contract for the glue's stand-ins for system headers the NDK lacks at the
// app's minSdk or that upstream takes from GMP: android_mocks.h maps GMP names onto
// mini-gmp, and execinfo.h shadows the NDK's. Each is held to what GMP and glibc
// give on boundary values, computed apart in Python: a stand-in that differs from
// the header upstream was written for gives wrong answers or crashes, and no build
// can see it. Built by run_native_stand_ins_contract.sh with android_mocks.h forced
// into the unit, as the app build forces it.
#include <execinfo.h> // the glue's stand-in, first on the include path
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

static int failures = 0;

#define CHECK(condition, ...)                                                  \
  do {                                                                         \
    if (!(condition)) {                                                        \
      fprintf(stderr, "FAIL: " __VA_ARGS__);                                   \
      fputc('\n', stderr);                                                     \
      failures++;                                                              \
    }                                                                          \
  } while (0)

static bool mpzIs(mpz_srcptr value, const char *expected) {
  mpz_t want;
  mpz_init_set_str(want, expected, 10);
  const bool same = mpz_cmp(value, want) == 0;
  mpz_clear(want);
  return same;
}

int main(void) {
  static const struct {
    const char *value;
    int fits;
  } uintCases[] = {{"0", 1},
                   {"4294967295", 1},
                   {"4294967296", 0},
                   {"4294971521", 0},
                   {"-1", 0},
                   {"18446744073709551616", 0}};
  for (size_t i = 0; i < sizeof(uintCases) / sizeof(uintCases[0]); i++) {
    mpz_t v;
    mpz_init_set_str(v, uintCases[i].value, 10);
    CHECK(mpz_fits_uint_p(v) == uintCases[i].fits, "mpz_fits_uint_p(%s)",
          uintCases[i].value);
    mpz_clear(v);
  }

  static const struct {
    const char *from;
    const char *next;
  } primeCases[] = {{"0", "2"},
                    {"1", "2"},
                    {"2", "3"},
                    {"4294967291", "4294967311"},
                    {"18446744073709551557", "18446744073709551629"},
                    {"1000000000000000000000000000000",
                     "1000000000000000000000000000057"}};
  for (size_t i = 0; i < sizeof(primeCases) / sizeof(primeCases[0]); i++) {
    mpz_t from, next;
    mpz_init_set_str(from, primeCases[i].from, 10);
    mpz_init(next);
    mpz_nextprime(next, from);
    CHECK(mpzIs(next, primeCases[i].next), "mpz_nextprime(%s)",
          primeCases[i].from);
    mpz_clear(from);
    mpz_clear(next);
  }

  static const struct {
    unsigned long n;
    const char *fib;
  } fibCases[] = {
      {0, "0"},
      {1, "1"},
      {2, "1"},
      {93, "12200160415121876738"},
      {94, "19740274219868223167"},
      {300, "222232244629420445529739893461909967206666939096499764990979600"}};
  for (size_t i = 0; i < sizeof(fibCases) / sizeof(fibCases[0]); i++) {
    mpz_t fib;
    mpz_init(fib);
    mpz_fib_ui(fib, fibCases[i].n);
    CHECK(mpzIs(fib, fibCases[i].fib), "mpz_fib_ui(%lu)", fibCases[i].n);
    mpz_clear(fib);
  }

  mpz_t half;
  mpz_init_set_si(half, -5);
  mpz_div_2exp(half, half, 1); // GMP's obsolete name rounds toward minus infinity
  CHECK(mpz_get_si(half) == -3, "mpz_div_2exp(-5, 1) is %ld", mpz_get_si(half));
  mpz_clear(half);

  // upstream's freeList.c prints strs[1] and frees the array, whatever the frame
  // count
  void *frames[4];
  char **names = backtrace_symbols(frames, backtrace(frames, 4));
  CHECK(names != NULL, "backtrace_symbols gave no array");
  if (names != NULL) {
    // The read is the check: AddressSanitizer stops on an array of fewer than two.
    const char *volatile second = names[1];
    (void)second;
    free(names);
  }

  if (failures != 0) {
    return 1;
  }
  printf("stand-ins: every case agrees with GMP and glibc\n");
  return 0;
}
