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
check it was signed here, and [Update with Obtainium](#update-with-obtainium)
how to keep it current. The `-dev` pre-releases on the same page are snapshot
builds of `main`: they install as a separate app, beside the release.

## Support

- A bug or a question about the Android app:
  [open an issue](https://github.com/ppigazzini/r47zen/issues).
- A calculator behavior that the desktop C47 simulator shows too is the
  upstream core's; report it to the upstream project.
- A security problem: follow `.github/SECURITY.md`, never a public issue.
- Privacy: the
  [privacy policy](https://ppigazzini.github.io/r47zen/privacy-policy.html)
  says what the app stores on the device; it collects nothing and has no
  Internet permission. The app shows the same page in Settings.

## License

GNU General Public License, version 3; `COPYING` holds the text. The app's
license screens show the notices of the third-party code it is built with:
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

Hydrate the upstream core inputs at upstream HEAD; run it again to move to a
newer HEAD:

```sh
./scripts/upstream-sync/upstream.sh sync --latest --write-lock
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

The APK and the AAB of every production release on GitHub are signed by one
certificate, the release certificate. Its SHA-256, as
`apksigner verify --print-certs` prints it:

    fa001b0b6a847335806c5ba5094a07c38b1962b0b983e1b07407bbe5628aacc6

The release workflow refuses to publish a build that carries any other signer,
and each release's notes repeat the value.

The `-dev` pre-releases install as a separate app,
`io.github.ppigazzini.r47zen.dev`, signed by the prerelease certificate. Its
SHA-256:

    3e205018f6b622fc382be665efec9c06c231d8f8aec2027e4014800573c43ab1

The CI workflow refuses to publish a pre-release that carries any other
signer, and each pre-release's notes repeat the value.

Google Play signs the APKs it delivers with its own app-signing key, so an
install from Play does not carry this certificate. Android updates an app only
with the signer it was installed with: a Play install and a GitHub install
cannot replace each other, and moving from one to the other means
uninstalling, which deletes the app's data.

## Update with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs the APK from this
repository's releases and keeps it current. The release and the `-dev`
pre-releases are two apps, so each takes its own entry: add
`https://github.com/ppigazzini/r47zen` once for each app you want, and set
these fields on the same screen.

For the release, `io.github.ppigazzini.r47zen`:

- "Filter links by regex":

  ```text
  ^r47zen_[0-9]+\.[0-9]+\.[0-9]+_[0-9a-f]{8}_[0-9a-f]{8}\.apk$
  ```

- "Trim version string with RegEx":

  ```text
  [0-9]+\.[0-9]+\.[0-9]+
  ```

For the `-dev` pre-releases, the snapshot builds of `main`,
`io.github.ppigazzini.r47zen.dev`:

- "Include prereleases": on.
- "Filter links by regex":

  ```text
  ^r47zen_[0-9]+\.[0-9]+\.[0-9]+-[0-9]+\.[0-9]+_[0-9a-f]{8}_[0-9a-f]{8}\.apk$
  ```

- "Use release title as version string": on; a `-dev` tag carries no version.
- "Trim version string with RegEx":

  ```text
  [0-9]+\.[0-9]+\.[0-9]+-[0-9]+\.[0-9]+
  ```

The link filter picks the app: a release APK is named
`r47zen_<version>_<upstream8>_<android8>.apk`, a `-dev` APK carries
`<version>-<run>.<attempt>` in place of `<version>`, and Obtainium skips a
release with no matching APK. The version regex cuts the release tag, or the
`-dev` title, down to the version name the installed app reports. One entry
cannot follow both apps: Obtainium refuses a download whose package differs
from the entry's.

## Documentation

- [Owner's manual](https://ppigazzini.github.io/r47zen/manual/C47_R47_calc_Crop.pdf):
  the cropped PDF of the upstream manual, which Settings also opens.
- [`AGENTS.md`](AGENTS.md): the one-page short form for contributors and
  coding agents: the commands, the traps, and the rules.
- [`android/docs/dev/README.md`](android/docs/dev/README.md): the maintainer
  index, with the pages in reading order, by task, and by symptom.
