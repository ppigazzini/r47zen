# R47 Zen
[![Android CI](https://github.com/ppigazzini/r47zen/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ppigazzini/r47zen/actions/workflows/android-ci.yml)

R47 Zen packages the upstream C47 calculator core as an Android app for the
R47 calculator variant. It is independently maintained and is not an official
release of the C47 project.

This repository builds the APK, stages the shared native calculator core for
Android, and owns the Android-specific Kotlin, JNI, packaging, and CI code that
run the calculator on Android devices. The calculator core itself comes from
the upstream C47 project, `https://gitlab.com/rpncalculators/c43.git`; the
GitLab path keeps the older `c43` name.

## Get the app

Download the APK from the
[latest release](https://github.com/ppigazzini/r47zen/releases/latest) and
install it; [Verify a downloaded APK](#verify-a-downloaded-apk) says how to
check it was signed here. The `-dev` pre-releases on the same page are
snapshot builds of `main`: they install as a separate app, beside the release.

## Support

- A bug or a question about the Android app:
  [open an issue](https://github.com/ppigazzini/r47zen/issues).
- A calculator behaviour that the desktop C47 simulator shows too is the
  upstream core's; report it to the upstream project.
- A security problem: follow `.github/SECURITY.md`, never a public issue.
- Privacy: the
  [privacy policy](https://ppigazzini.github.io/r47zen/privacy-policy.html)
  says what the app stores on the device; it collects nothing and has no
  Internet permission. The app shows the same page in Settings.

## Licence

GNU General Public License, version 3; `COPYING` holds the text. The app's
licence screens show the notices of the third-party code it is built with:
decNumber, mini-gmp, xlsxio, and the AndroidX libraries.

## Repo Layout

- `android/`: Android app, Gradle project, packaging, and Android-owned native
  glue
- `android/app/src/main/cpp/r47zen/`: Android bridge, JNI, HAL, and other
  Android-local native code
- `scripts/`: repo-owned Android build, staging, and upstream sync automation
- `android/docs/dev/`: maintainer docs for source ownership, build flow, JNI,
  rendering, CI, and verification

## Quick Start

Prerequisites: a JDK, the Android SDK and NDK, CMake, Meson/Ninja, and `uv`
for the Python contract lane. Rather than list exact versions here (they live in
`android/r47-defaults.properties`), probe your machine and let the build report
what is missing:

```sh
./scripts/android/build_android.sh --doctor
```

Hydrate the upstream core inputs:

```sh
./scripts/upstream-sync/upstream.sh sync --auto --write-lock
```

Build the debug APK:

```sh
./scripts/android/build_android.sh
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
The root `Makefile` and `BUILD.md` are upstream-owned and describe the upstream
simulator build, not the Android lane; see `android/docs/dev/` for this repo's
build. Full local pipeline: `android/docs/dev/01-build-and-source-layout.md`.

Signed prerelease and production release publication run from the CI and
release workflows. Nightly `-dev` snapshot pre-releases are pruned on a
schedule (short TTL, newest few retained) while signed releases are kept. See
`android/docs/dev/07-ci-and-release-workflow.md` for the lane split,
signing-key policy, retention, and published artifacts.

## Verify a downloaded APK

Every production release APK and AAB is signed by one certificate. Its
SHA-256, as `apksigner verify --print-certs` prints it:

    fa001b0b6a847335806c5ba5094a07c38b1962b0b983e1b07407bbe5628aacc6

The release workflow refuses to publish a build that carries any other signer,
and each release's notes repeat the value. The `-dev` pre-releases install as
a separate app, `io.github.ppigazzini.r47zen.dev`, signed by a prerelease key.

Android refuses to update an install of a production release older than
0.1.10-signed.2026100904: those carry a date-shaped version code (2026092802
to 2026100903), higher than any later release's, and all but
0.1.10-signed.2026100903 an earlier certificate, SHA-256
7514186154f3789499eddd8e2b656b986dc801462fc5571293202ec5f0e92d71. Uninstall it,
which deletes the app's data, then install the current release.

## Documentation

- [`AGENTS.md`](AGENTS.md): the one-page short form for contributors and
  coding agents: the commands, the traps, and the rules.
- [`android/docs/dev/README.md`](android/docs/dev/README.md): the maintainer
  index, with the pages in reading order, by task, and by symptom.
