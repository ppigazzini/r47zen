# mini-gmp Android staging source

This directory is the tracked mini-gmp source staged into
`android/.staged-native/cpp/gmp` for Android builds.

It exists so the Android lane does not depend on an ignored or upstream-owned
`subprojects/gmp-6.2.1/mini-gmp` checkout.

Do not treat this directory as a canonical shared-native source root.

Do not add other shared-core snapshots here.
