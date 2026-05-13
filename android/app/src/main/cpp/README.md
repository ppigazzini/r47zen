# Android Native Entry Surface

Only two tracked native surfaces are live under this directory:

- `CMakeLists.txt`
- `c47-android/`

Shared native inputs are staged into `android/.staged-native/cpp` during the
Android build.

The former tracked snapshot paths
`android/app/src/main/cpp/{c47,decNumberICU,generated,gmp}` were retired on
purpose. If they appear again, treat that as a staging bug.

Public checkouts keep one explicit staging-only mini-gmp fallback under
`android/compat/mini-gmp-fallback`.
