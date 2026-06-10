// Force-included first by build_graph_crash_harness.sh.
//
// On this toolchain (gcc 13 + glibc), the GCC <stddef.h> _STDDEF_H guard ends
// up set without size_t being typedef'd by the time mini-gmp's gmp.h is reached
// (gmp.h's own `#include <stddef.h>` then no-ops), leaving size_t undefined.
// Define size_t up front using GCC's __SIZE_TYPE__ builtin under the same
// _SIZE_T guard GCC's stddef.h uses, so GCC's later typedef is skipped and no
// redefinition occurs.
#ifndef R47_HARNESS_PRELUDE_H
#define R47_HARNESS_PRELUDE_H

#ifndef _SIZE_T
#define _SIZE_T
typedef __SIZE_TYPE__ size_t;
#endif

#ifndef _PTRDIFF_T
#define _PTRDIFF_T
typedef __PTRDIFF_TYPE__ ptrdiff_t;
#endif

#ifndef _WCHAR_T
#define _WCHAR_T
typedef __WCHAR_TYPE__ wchar_t;
#endif

#ifndef NULL
#define NULL ((void *)0)
#endif

#endif
