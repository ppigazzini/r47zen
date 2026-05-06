# Native Core And JNI

## Native library shape

The Android module builds one shared library: `c47-android`. `MainActivity`
loads it from a static initializer via `System.loadLibrary("c47-android")`.

CMake builds the library from:

- build-only staged core sources under `android/.staged-native/cpp/c47`
- build-only staged decNumber sources under
  `android/.staged-native/cpp/decNumberICU`
- build-only staged generated sources under
  `android/.staged-native/cpp/generated`
- Android-specific bridge and HAL files under
  `android/app/src/main/cpp/c47-android`
- build-only staged mini-gmp sources under `android/.staged-native/cpp/gmp`
- the tracked Android mini-gmp staging source under
  `android/compat/mini-gmp-fallback`

Tracked Android stub headers under `c47-android/stubs` and the forced include
of `android_mocks.h` let the Android build satisfy upstream GTK, GDK, and Cairo
includes without rewriting staged source files during the Gradle build.

The Android native module currently compiles the staged upstream core in
`PC_BUILD` mode. That means the Android-owned bridge layer must provide the
GLib and GTK compatibility behavior used by upstream pause, wait, and progress
paths instead of treating those entry points as no-op stubs.

`android/app/src/main/cpp/CMakeLists.txt` passes and consumes
`R47_STAGED_CPP_DIR` so the live Android native build reads shared-native inputs
from the build-only staging root rather than any retired app-module snapshots.

The former tracked directories
`android/app/src/main/cpp/{c47,decNumberICU,generated,gmp}` are retired
snapshot paths and must stay absent during normal builds.

The Android bridge code is intentionally split by responsibility:

- `jni_lifecycle.c` for init, tick, refresh, and slot-state lifecycle work
- `jni_input.c` for key and menu dispatch
- `jni_display.c` for LCD pixels, keypad snapshots, and X-register queries
- `jni_storage.c` for SAF-backed blocking file handoff
- `native-lib.c` for shared JNI bootstrap, registration, and bridge globals

## JNI contract

JNI registration is explicit. `JNI_OnLoad()` initializes the JVM handle, the
recursive `screenMutex`, and calls `register_main_activity_natives(...)`. The
bridge does not rely on name-based native lookup.

The registered native surface includes:

- activity reattachment and final runtime release
- native pre-init, init, and tick
- key, menu, and function dispatch
- state save, load, and force refresh
- LCD pixel transfer
- keypad metadata and label snapshots, including per-label roles such as
  underlined menu-opening faceplate legends
- slot selection and X-register fetch
- SAF file selection callbacks

Shared helpers in `jni_bridge.h` centralize `JNIEnv` acquisition,
detach-on-scope-exit for native-owned threads, exception detection and clearing
after Java calls, and fallback Java-string creation. New Android bridge code
should use those helpers instead of open-coded `GetEnv`, `AttachCurrentThread`,
or unchecked `Call*Method` paths.

Development rule:

- Keep the Kotlin external declarations, `JNINativeMethod` table, signatures,
  and implementations aligned in one change.
- Keep app-class lookups and registration failures early in `JNI_OnLoad()` so a
  broken bridge fails at library load time rather than on first use.
- Use `jni_acquire_env(...)`, `jni_release_env(...)`, and
  `jni_check_and_clear_exception(...)` for native-owned JVM work instead of
  duplicating attach, detach, and exception logic at each call site.

## Threading and synchronization

`NativeCoreRuntime` runs the engine loop on a background thread. The JNI bridge
supports that model by keeping shared synchronization in native code:

- `screenMutex` is recursive
- `yieldToAndroidWithMs()` refreshes the LCD, releases the recursive screen
  lock, advances due timer callbacks, lets Android process queued work, sleeps
  briefly, and then reacquires the lock
- `NativeDisplayRefreshLoop` uses `Choreographer.postFrameCallback(...)` on the
  main looper to refresh LCD pixels every frame and to poll keypad snapshots on
  metadata change or the configured idle interval; `ReplicaOverlayController`
  keeps the whole-snapshot gate available behind
  `KeypadRefreshPolicy.ENABLE_UNCHANGED_SNAPSHOT_SKIP`, and the current
  iteration enables that gate again now that `CalculatorKeyView` keeps repeated
  application of the same snapshot geometry-stable
- `android_runtime.c` also supplies Android-backed `PC_BUILD` event-loop shims
  for `g_main_context_iteration()`, `g_timeout_add()`,
  `gtk_events_pending()`, and `gtk_main_iteration()` so staged upstream pause
  and progress loops keep yielding on Android instead of hanging behind no-op
  mocks; treat these entry points as required compatibility behavior, not as
  optional stubs, because upstream program workloads depend on them for pause
  and progress responsiveness
- `scripts/workload-regressions/run_workload_regressions.sh` is the repo-owned Linux host harness
  for that compatibility contract. It compiles the staged core plus the
  Android bridge in `HOST_TOOL_BUILD` and `PC_BUILD`, probes the wait or
  progress shims in `android_runtime.c`, loads canonical `SPIRALk`, and checks
  a large `FACTORS` run through the host-only LCD refresh counter exported by
  `hal/lcd.c`
- native-owned JVM work acquires `JNIEnv` through `jni_acquire_env()` and
  `jni_release_env()` so attach and detach remain scope-bound
- the bridge can update the current activity reference when the activity is
  recreated
- file I/O handoff uses a condition-based native wait path so the calculator
  core can request a file without inventing a second storage protocol

Practical rule:

- when a native change can block on Android UI or storage, make the lock
  boundaries explicit before changing behavior

Final app shutdown uses `releaseNativeRuntime()` to delete the global
`MainActivity` reference and clear the cached method IDs. Activity recreation
continues to use `updateNativeActivityRef()` without tearing down the native
core.

## File I/O boundary

`hal/io.c` uses two Android-specific paths:

- a runtime base path set by `set_android_base_path(...)` for app-internal files
  and subdirectories
- SAF handoff for state, program, RTF export, manual save, and related
  user-facing file operations

The SAF path works as follows:

1. Native code calls `requestAndroidFile(...)` with save or load mode, default
   name, and category.
2. Kotlin launches the correct SAF intent through `StorageAccessCoordinator`.
3. The selected file descriptor is detached from the
   `ParcelFileDescriptor` and returned to native code.
4. Native code wraps the descriptor with `fdopen(...)` and continues using
   standard file I/O.

After `detachFd()`, the `ParcelFileDescriptor` wrapper no longer owns that file
descriptor. Native closes it on the existing `fdopen()` failure path or through
`fclose()` on success.

The runtime base path is separate from the user-selected work directory. The
base path supports internal files; the work-directory contract supports user
data organized through SAF.

For host workload runs, `HOST_TOOL_BUILD` bypasses the Android SAF interception
in `hal/io.c` and uses the runtime base path directly so canonical upstream
program files can be staged and loaded without an Android document-provider
round trip.

## JNI change checklist

1. Update the Kotlin external declaration.
2. Update the registered method table.
3. Update the bridge header and the owning C implementation.
4. Recheck thread and lock behavior if the call can touch UI, storage, or long
  native work.

## Change ownership

- For shared calculator behavior, change the canonical root core and restage it
  into `android/.staged-native/cpp` through the normal Android build flow.
- Change `android/app/src/main/cpp/c47-android` directly only for Android
  bridge, HAL, or stub behavior.
- Do not patch build-only staged upstream C files in place when a tracked
  Android stub or bridge-layer fix can own the compatibility rule.

## 16 KB and packaging contract

The checked-in Android build uses the supported NDK flexible-page-size path:

- `android/app/build.gradle` passes
  `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` to CMake
- the checked-in NDK pin is `29.0.14206865`
- the checked-in AGP version is `9.2.0`

The default checked-in APK target is `arm64-v8a`. The workflow and local
Gradle invocations can temporarily add `x86_64` through `r47.abiFilters` for
emulator-backed test runs, but the shipped debug artifact remains
`arm64-v8a` by default. Any added prebuilt native dependency must also satisfy
the 16 KB requirements for ELF and APK alignment.

The CI lane verifies that contract by checking zip alignment and native library
`LOAD` segment alignment in the built debug APK. The `android-tests` lane uses
the temporary multi-ABI override only for hosted `x86_64` emulator execution.

That artifact verification is the reason packaging changes should be documented
alongside the workflow and Gradle files, not only in the CMake layer.
