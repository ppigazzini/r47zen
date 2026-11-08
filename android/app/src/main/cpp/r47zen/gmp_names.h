// GMP names the staged core uses, mapped onto mini-gmp's, which lacks them. This is
// their one definition: android_mocks.h includes it, so the app and every host harness
// get it, and run_upstream_suite_android_config.sh force-includes it into upstream's
// suite, whose host build links GTK and so cannot take android_mocks.h's stand-ins.
// The calculator thread harness holds each name to GMP on boundary values.
#ifndef R47_GMP_NAMES_H
#define R47_GMP_NAMES_H

#include <stdio.h> // before gmp.h, which declares its FILE functions only after it

#include "gmp.h"

#define mpz_div_2exp mpz_fdiv_q_2exp
static inline int mpz_fits_uint_p(mpz_srcptr value) {
  return mpz_fits_ulong_p(value) && mpz_get_ui(value) <= 0xFFFFFFFFUL;
}

static inline void mpz_nextprime(mpz_ptr r, mpz_srcptr n) {
  mpz_add_ui(r, n, 1);
  while (mpz_probab_prime_p(r, 10) == 0)
    mpz_add_ui(r, r, 1);
}
static inline void mpz_fib_ui(mpz_ptr r, unsigned long n) {
  if (n == 0) {
    mpz_set_ui(r, 0);
    return;
  }
  if (n == 1) {
    mpz_set_ui(r, 1);
    return;
  }
  mpz_t a, b;
  mpz_init_set_ui(a, 0);
  mpz_init_set_ui(b, 1);
  for (unsigned long i = 2; i <= n; i++) {
    mpz_add(a, a, b);
    mpz_swap(a, b);
  }
  mpz_set(r, b);
  mpz_clear(a);
  mpz_clear(b);
}

#endif // R47_GMP_NAMES_H
