# Official References

This page groups the canonical external references that back the maintainer
docs and the checked-in Android workflow.

## Reference Map

```mermaid
flowchart TD
  A[Need external reference]
  B[Toolchain and build]
  C[Kotlin and architecture]
  D[Native and packaging]
  E[CI and release]
  F[Storage and manifest]
  G[View and rendering]

  A --> B
  A --> C
  A --> D
  A --> E
  A --> F
  A --> G
```

## Upstream project surfaces

- [C47 GitLab project](https://gitlab.com/rpncalculators/c43): the authoritative
  upstream source repository consumed by this Android overlay. The GitLab path
  still uses the historical `c43` name even though the project identifies
  itself as C47.
- [C47 lblGtoXeq.c run loop](https://gitlab.com/rpncalculators/c43/-/blob/master/src/c47/programming/lblGtoXeq.c):
  authoritative upstream run-loop source used when comparing Android stop-key
  behavior against the desktop simulator's `R/S` and `EXIT` parity during
  long-running program execution.
- [C47 keyboard.c desktop key dispatch](https://gitlab.com/rpncalculators/c43/-/blob/master/src/c47/keyboard.c):
  upstream `PC_BUILD` key-callback surface to inspect when desktop input seems
  to publish stop intent earlier than Android.
- [C47 addons.c key polling helpers](https://gitlab.com/rpncalculators/c43/-/blob/master/src/c47/c47Extensions/addons.c):
  authoritative `anyKeyWaiting()` and `exitKeyWaiting()` source used when a
  stop-path analysis depends on the desktop simulator's polled `EXIT`
  behavior.
- [C47 project wiki](https://gitlab.com/rpncalculators/c43/-/wikis/home):
  upstream project-maintained wiki surface linked from the upstream GitLab
  project when project-specific behavior or history matters.
- [C47 community wiki](https://gitlab.com/h2x/c47-wiki/-/wikis/home): community
  documentation hub that covers the broader C47 ecosystem, including R47
  variant context and user-facing project documentation.

## Spring 2026 toolchain references

- [Android Gradle plugin 9.2.0 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes):
  official Android build release notes for the AGP line used by this repo;
  the current compatibility table lists JDK `17`, SDK Build Tools `36.0.0`,
  and API `36.1` support for the checked-in AGP line.
- [Kotlin release process](https://kotlinlang.org/docs/releases.html):
  official JetBrains release page documenting the language, tooling, and bug-fix
  cadence; the current page shows Kotlin `2.3.21` as the latest stable line in
  Spring 2026.
- [Gradle 9.5.0 release notes](https://docs.gradle.org/9.5.0/release-notes.html):
  official Gradle release notes for the wrapper version now checked in.
- [Improve the Performance of Gradle Builds](https://docs.gradle.org/current/userguide/performance.html):
  official Gradle guidance for establishing a baseline, preferring
  workflow-specific task graphs, and using daemon, cache, parallelism, and
  incremental inputs before reaching for riskier CI toggles.
- [Command-Line Interface](https://docs.gradle.org/current/userguide/command_line_interface.html):
  official Gradle CLI reference for running multiple tasks in one invocation,
  setting `--max-workers`, and keeping CI task graphs explicit instead of
  splitting overlapping work across extra Gradle processes.
- [Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html):
  official Gradle guidance for reusing configuration work across repeated task
  graphs; the Android connected-test wrapper enables it in CI because the lane
  repeats the same release-variant task graph across grouped selections.
- [Build Environment Configuration](https://docs.gradle.org/current/userguide/build_environment.html):
  official reference for `gradle.properties`, command-line precedence, and the
  documented homes for settings such as `org.gradle.parallel`,
  `org.gradle.caching`, `org.gradle.workers.max`, and `org.gradle.jvmargs`.
- [Version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html):
  Gradle guidance for centralizing dependency coordinates in
  `gradle/libs.versions.toml` and consuming them through `libs` accessors.
- [Android 16](https://developer.android.com/about/versions/16): official
  platform overview for API `36`, the checked-in compile and target SDK level.
- [CMake release notes index](https://cmake.org/cmake/help/latest/release/index.html):
  official release-note index for tracking when it is worth leaving the current
  checked-in CMake line.
- [ccache manual](https://ccache.dev/manual/latest.html): official compiler
  cache reference for the Linux and Windows simulator lanes; use it when
  changing cache directories, compiler wrappers, or hit-rate expectations.

## Architecture and Kotlin

- [Guide to app architecture](https://developer.android.com/topic/architecture):
  separation of concerns, state ownership, lifecycle boundaries, and
  single-source-of-truth guidance. The current page was last updated
  2026-04-14.
- [Threads and threading performance](https://developer.android.com/topic/performance/threads):
  main-thread budget, worker-thread ownership, and queue-based background-work
  guidance used for the deadline-driven core-thread design and the rule that the
  keypad refresh path must keep long or numerous work off the main thread. The
  current page was last updated 2024-01-03.
- [Choreographer.FrameCallback](https://developer.android.com/reference/android/view/Choreographer.FrameCallback):
  official frame-callback contract for `doFrame(...)`. The callback runs on the
  `Looper` thread attached to the `Choreographer` when a new display frame is
  being rendered. Use this when documenting UI-side cadence fields or other
  frame-sensitive polling loops such as `NativeDisplayRefreshLoop`.
- [ANRs](https://developer.android.com/topic/performance/vitals/anr):
  official foreground input-dispatch timeout, main-thread lock-contention, and
  ANR trace guidance used when Android-specific hangs look like UI-thread
  stalls rather than pure core-thread starvation. The current page was last
  updated 2026-03-05.
- [Fundamentals of testing Android apps](https://developer.android.com/training/testing/fundamentals):
  official Android guidance for local versus instrumented tests, test scope,
  and dependency decoupling; use this when deciding whether a repo contract
  belongs in Robolectric, instrumentation, or a host script.
- [Test your app's accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing):
  official Android guidance for TalkBack, Switch Access, Accessibility
  Scanner, pre-launch accessibility reports, and other verification surfaces to
  use when a UI change claims accessibility improvement. The current page was
  last updated 2026-04-16.
- [Get a result from an activity](https://developer.android.com/training/basics/intents/result):
  Activity Result API registration and lifecycle contract for SAF launchers.
- [ActivityResultCaller](https://developer.android.com/reference/androidx/activity/result/ActivityResultCaller):
  AndroidX API reference for `registerForActivityResult()` and unconditional
  registration rules.
- [Develop Android apps with Kotlin](https://developer.android.com/kotlin):
  Android-specific Kotlin guidance and tooling entry point.
- [Adopt Kotlin for large teams](https://developer.android.com/kotlin/adopt-for-large-teams):
  official Android guidance for migration sequencing, shared conventions,
  review policy, and tooling expectations in larger Android codebases. The
  current page was last updated 2026-03-06.
- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html):
  official file naming, formatting, immutability, and test-naming guidance for
  Kotlin maintainability.
- [Kotlin language documentation](https://kotlinlang.org/docs/home.html):
  language-level reference.

## Launcher icon pipeline

- [AdaptiveIconDrawable reference](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable):
  authoritative Android runtime model for adaptive icon foreground/background
  layers and mask behavior.
- [Splash screen guidance](https://developer.android.com/develop/ui/views/launch/splash-screen):
  launcher icon interaction with splash-screen surfaces on modern Android.
- [Google Play icon design specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications):
  Play listing icon requirements (512x512 PNG) which are separate from runtime
  launcher resources.

Current repository icon surfaces:

- Canonical SVG source artifact:
  `android/app/src/main/assets/icons/r47zen.svg`
- Adaptive icon XML:
  `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and
  `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Runtime vectors and fallbacks:
  `android/app/src/main/res/drawable/ic_launcher_foreground.xml`,
  `android/app/src/main/res/drawable/ic_launcher_legacy.xml`,
  `android/app/src/main/res/mipmap/ic_launcher.xml`, and
  `android/app/src/main/res/mipmap/ic_launcher_round.xml`

## Native and build integration

- [Configure your app module](https://developer.android.com/build/configure-app-module):
  package identity, SDK levels, and build-type fundamentals.
- [Add C and C++ code to your project](https://developer.android.com/studio/projects/add-native-code):
  the official Gradle plus CMake integration path.
- [Android ABIs](https://developer.android.com/ndk/guides/abis): ABI baselines,
  `abiFilters`, and the generic `arm64-v8a` contract this repo keeps for the
  shipped default artifact.
- [Android CPU features](https://developer.android.com/ndk/guides/cpu-features):
  native feature-probing guidance to use only if the repo ever adds same-ABI
  runtime dispatch.
- [Profile-guided Optimization](https://developer.android.com/ndk/guides/pgo):
  official NDK guidance for host-driven core optimization after the maintained
  Android ThinLTO baseline: start with representative workloads, build with
  `-fprofile-generate`, make Android app-processes write raw profiles
  explicitly, merge them with the same NDK `llvm-profdata`, and then rebuild
  with `-fprofile-use`. The doc also notes that library profiles are generally
  reusable across architectures unless the library has architecture-specific
  code paths. In this repo, the maintained host-core training surface is the
  broad `broad-ci` `testSuite` base plus the imported `.p47` overlay merged
  into one host profile; keep that separate from the host compatibility rerun
  and Android test lanes. If Android app-process profile collection is ever
  added, treat it as a separate experiment rather than as a replacement for
  host-core training or app benchmarking.
- [Benchmark your app](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview):
  official Android benchmark map, updated 2026-05-19. Use Macrobenchmark for
  out-of-process end-user flows such as startup, scrolling, and animations, and
  use Microbenchmark only for isolated hot functions or UI subroutines after a
  profiler or trace has already identified the bottleneck.
- [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview):
  official Android app benchmarking guidance. Benchmark a release-like,
  profileable app from a separate `com.android.test` module and treat the
  generated JSON or trace artifacts as a distinct app-performance surface, not
  as a substitute for shared-core PGO corpus selection.
- [System tracing overview](https://developer.android.com/topic/performance/tracing):
  official Perfetto and system tracing overview. Use tracing for root-cause
  analysis once a benchmark or workload points at a regression; it complements
  native-core PGO and Android benchmarking rather than replacing either one.
- [Slow rendering](https://developer.android.com/topic/performance/vitals/render):
  official Android jank and render-time guidance, updated 2026-05-19. Treat
  16.67 ms as the 60 fps frame budget, validate jank on release-like builds,
  use Perfetto or Systrace for frame-level diagnosis, and keep field reporting
  separate for slow and frozen frames.
- [Inspect GPU rendering](https://developer.android.com/topic/performance/rendering/inspect-gpu-rendering):
  official developer-option guidance for mapping on-screen frame bars to the
  rendering pipeline, updated 2026-05-19. Use it as a fast local visualizer for
  input, layout, draw, upload, and GPU-wait pressure before deeper tracing.
- [Configure the NDK for the Android Gradle plugin](https://developer.android.com/studio/projects/configure-agp-ndk):
  `ndkVersion` guidance for AGP-based projects, including the command-line
  `sdkmanager` package syntax this repo uses in CI.
- [JNI tips](https://developer.android.com/ndk/guides/jni-tips): explicit
  registration, thread attachment, reference management, exception rules, and
  the guidance to minimize marshalling and prefer region-style copy calls when
  a simple copy contract is enough. The current page was last updated
  2026-03-06.
- [JNI performance article alias](https://developer.android.com/training/articles/perf-jni):
  stable alias that currently resolves to the maintained JNI guidance page;
  useful when historical Android notes still reference the older URL.
- [simpleperf](https://developer.android.com/ndk/guides/simpleperf): official
  native profiler for symbol, DSO, thread, and call-graph attribution before
  hot-path micro-optimization. Current guidance is to identify the hottest
  DSOs, functions, and threads first, then inspect call graphs before changing
  code. Use it after a physical-device benchmark or canonical workload timing
  run points at a regression; the in-app developer HUD is only a quick local
  smoke signal.
- [Custom trace events in native code](https://developer.android.com/topic/performance/tracing/custom-events-native):
  official ATrace or Perfetto guidance for Android-owned native spans and
  thread naming.
- [Clang Users Manual](https://clang.llvm.org/docs/UsersManual.html): official
  Clang optimization, profile-generation, and profile-use reference behind NDK
  PGO flag interpretation. It distinguishes sampling from instrumentation
  profiles, recommends representative workloads, and treats IR-based
  instrumentation as the preferred optimization profile format.
- [How To Build With PGO](https://llvm.org/docs/HowToBuildWithPGO.html): LLVM
  reference for instrumentation, raw profile collection, merged profdata use,
  and benchmark selection. It emphasizes that representative training coverage
  produces better profiles than narrow microbench-only training.
- [llvm-profdata](https://llvm.org/docs/CommandGuide/llvm-profdata.html):
  official merge and inspection tool for indexed profile data. Use the
  `llvm-profdata` build that matches the producing Clang or NDK revision,
  `merge` raw profiles before `-fprofile-use`, and `show` or `overlap` when
  diagnosing profile quality.
- [target_compile_options](https://cmake.org/cmake/help/latest/command/target_compile_options.html):
  target-scoped compile-flag ownership, including config-specific generator
  expressions.
- [target_link_options](https://cmake.org/cmake/help/latest/command/target_link_options.html):
  target-scoped link-flag ownership for the Android ThinLTO plus `lld` path.
- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes):
  packaging, ELF alignment, and testing guidance for native apps.

## CI and release plumbing

- [Build your app for release to users](https://developer.android.com/build/build-for-release):
  APK, AAB, and signing guidance for the release lane defined by this repo.
- [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756):
  official Google Play guidance for upload keys, app-signing-key custody,
  bundle-first release flow, and upload-key reset behavior.
- [Enable app optimization with R8](https://developer.android.com/build/shrink-code):
  current Android guidance to enable minify and resource shrinking for release
  builds.
- [Building and testing Java with Gradle](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle):
  GitHub Actions guidance for Gradle cache setup, Java toolchain setup, and
  Gradle-oriented workflow structure.
- [Security hardening for GitHub Actions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions):
  GitHub Actions guidance for least-privilege `GITHUB_TOKEN` policy, action
  pinning, trusted-code boundaries, and secret-exposure minimization in
  workflow design.
- [Using secrets in GitHub Actions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions):
  GitHub guidance for repository and environment secrets, shell-safe secret
  handling, and the Base64 binary-blob pattern used for small upload keystores.
- [Deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments):
  environment protection rules, required reviewers, deployment branch
  restrictions, and environment-secret gating for the protected release lane.
- [Events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule):
  schedule-trigger semantics, default-branch-only execution, UTC defaults, and
  delay or drop behavior relevant to the daily signed dev-prerelease lane.
- [Manually running a workflow](https://docs.github.com/en/actions/managing-workflow-runs/manually-running-a-workflow):
  the maintainer entry point for versioned protected releases outside the
  scheduled dev-prerelease lane.
- [Passing information between jobs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/pass-job-outputs):
  GitHub Actions guidance for promoting step outputs through
  `jobs.<job_id>.outputs` and consuming them in dependent jobs through
  `needs.<job_id>.outputs.*`.
- [Control workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency):
  workflow-level concurrency controls used to cancel superseded runs for the
  same pull request or ref.
- [Store and share data with workflow artifacts](https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-what-your-workflow-does/storing-and-sharing-data-from-a-workflow):
  artifact upload and download behavior for GitHub Actions.
- [Manage releases in a repository](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository):
  the release model used by the main-branch snapshot lane.

## Google Play setup and policy

- [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152):
  Play Console app creation, package-name permanence, app contact details, and
  the initial Play App Signing and declaration flow.
- [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455):
  App content requirements such as privacy policy, ads declaration, app access,
  target audience, content ratings, and special declarations.
- [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469):
  required Data safety submission flow, including the requirement that every
  Play app completes the form.
- [User Data](https://support.google.com/googleplay/android-developer/answer/10144311):
  privacy-policy requirements, data-use restrictions, disclosure rules, and the
  account-deletion rule when an app creates user accounts.
- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878):
  current new-app and update target SDK floors for Google Play distribution.
- [Best practices for your store listing](https://support.google.com/googleplay/android-developer/answer/13393723):
  app title, short description, full description, icon, screenshot, and feature
  graphic rules used for store-facing naming and anti-impersonation review.
- [Manage target audience and app content settings](https://support.google.com/googleplay/android-developer/answer/9867159):
  target-age selection, neutral age screen guidance, and child-directed scope
  boundaries.
- [Google Play Families Policies](https://support.google.com/googleplay/android-developer/answer/9893335):
  child-directed app rules, underage data limits, ad constraints, and legal
  obligations when a product targets children.
- [Content rating requirements for apps, games, and the ads served on both](https://support.google.com/googleplay/android-developer/answer/9859655):
  IARC questionnaire and rating-authority flow for Play publication.
- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465):
  closed-test and production-access gate that can block first release even when
  the bundle is otherwise ready.

## Storage and file access

- [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files):
  SAF create, open, tree access, and persistable URI permissions.
- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup):
  backup defaults plus the `fullBackupContent` and `dataExtractionRules`
  formats used by the manifest.

## Manifest and platform behavior

- [<activity>](https://developer.android.com/guide/topics/manifest/activity-element):
  exported, `resizeableActivity`, `screenOrientation`, `configChanges`, and
  `onNewIntent()`-relevant launch-mode behavior.

## View-based UI and rendering

- [Add haptic feedback to events](https://developer.android.com/develop/ui/views/haptics/haptic-feedback):
  official Android haptics guidance for view-based feedback, predefined
  `VibrationEffect` usage, fallback tradeoffs, and keypress interaction
  constants. The doc includes press/release examples, but this app now keeps a
  press-only keypad pulse for calculator interaction, defaults to the Android
  system response through a dedicated toggle, and reserves the custom
  `0..100 ms` slider for explicit app-owned override behavior.
- [Keep your app responsive](https://developer.android.com/training/articles/perf-anr):
  official Android guidance for 5 s input deadlines, minimizing main-thread
  work, minimizing lock contention, and using Perfetto or CPU profiling when
  responsiveness regresses.
- [Rendering](https://developer.android.com/topic/performance/rendering):
  official entry point for reducing overdraw, optimizing view hierarchies, and
  using Profile GPU Rendering on View-based UI.
- [Slow rendering](https://developer.android.com/topic/performance/vitals/render):
  official jank and frozen-frame guidance for the 16 ms, 700 ms, and 5 s
  thresholds, plus the UI-thread and `RenderThread` split used when diagnosing
  rendering regressions.
- [Benchmark your app](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview):
  official Android benchmarking overview for choosing Macrobenchmark versus
  Microbenchmark, preventing regressions, and treating benchmarking as a
  repeatable quality surface rather than an ad hoc profiler session.
- [Write a Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview):
  official release-like Android app benchmark setup for startup and complex UI
  flows. Use a separate `com.android.test` module, benchmark a `profileable`
  non-debuggable target configured as close to release as possible, collect the
  JSON and trace outputs, and treat emulator numbers as non-representative.
- [Overview of measuring app performance](https://developer.android.com/topic/performance/measuring-performance):
  official Android triage guide for startup, scroll jank, and other app
  performance problems before committing to one benchmark or profiling surface.
- [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview):
  official ART install-time optimization guidance for app startup and critical
  user journeys. The current guidance says many apps see about 30% faster first
  launch or runtime on covered paths, that profile-generation and release
  builds need different minify settings, and that release builds should remain
  minified while profile generation stays unobfuscated.
- [JankStats library](https://developer.android.com/topic/performance/jankstats):
  official per-window frame-reporting and UI-state annotation library. Use it
  only as optional Android-local observability when stateful jank reports help;
  it is not this repo's primary CI or contract surface.
- [BufferQueue and Gralloc](https://source.android.com/docs/core/graphics/arch-bq-gralloc):
  AOSP graphics-pipeline reference explaining that Android already moves
  graphics buffers through `BufferQueue` by handle rather than by copying.
  Use this when evaluating and rejecting app-local triple buffering for a
  View-based LCD path.
- [Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles):
  official Android guidance for subtle frequent touch feedback, matching
  effect strength to event importance, and avoiding overly long or buzzy
  keypress vibrations.
- [VibrationEffect](https://developer.android.com/reference/android/os/VibrationEffect):
  Android API reference for one-shot and waveform amplitude bounds, predefined
  effects, and newer composition APIs.
- [Implement dark theme](https://developer.android.com/develop/ui/views/theming/darktheme):
  official DayNight, dark-theme, and Force Dark guidance for view-based apps;
  use this when deciding whether a settings surface should follow the system,
  opt into a dedicated dark theme, or expose an in-app override.
- [AppCompatDelegate](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate):
  official AppCompat night-mode override API reference, including
  `setLocalNightMode()` and its `uiMode` recreation behavior.
- [Display content edge-to-edge in views](https://developer.android.com/develop/ui/views/layout/edge-to-edge):
  visible-system-bar, inset, icon-contrast, and scrim guidance for view-based
  activities that draw behind or alongside system bars.
- [Core app quality guidelines](https://developer.android.com/docs/quality-guidelines/core-app-quality):
  current Android quality baseline for standard components, adaptive parity,
  touch-target sizing, content descriptions, and readable visuals across form
  factors.
- [Adaptive app quality guidelines](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality):
  current Android adaptive-layout guidance for apps that should fill the
  available window and keep critical flows usable across phones, tablets,
  foldables, and resizable windows.
- [Hide system bars for immersive mode](https://developer.android.com/develop/ui/views/layout/immersive):
  `WindowInsetsControllerCompat.hide()` and `show()` behavior plus transient
  bar guidance for fullscreen content.
- [Responsive/adaptive design with views](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views):
  official large-screen and multi-window guidance for view-based apps,
  including `ConstraintLayout` recommendations and alternative layout-resource
  qualifiers such as `layout-w600dp`.
- [Use window size classes](https://developer.android.com/develop/ui/views/layout/use-window-size-classes):
  breakpoint model for adaptive layouts.
- [Add a font as an XML resource](https://developer.android.com/develop/ui/views/text-and-emoji/fonts-in-xml):
  official Android guidance for bundling font files or families and retrieving
  them as `Typeface` resources; use this when evaluating future Android-only
  font-family packaging, but keep in mind that this repo also uses the same
  canonical TTFs as native raster-font inputs under repo-root `res/fonts`.
- [Autosize TextViews](https://developer.android.com/develop/ui/views/text-and-emoji/autosizing-textview):
  official Android guidance for bounded dynamic `TextView` content, uniform
  autosize ranges, and preset sizes; use this when a text surface becomes truly
  dynamic, but keep keypad legends on the contract-owned fitted-sizing path
  unless the geometry contract is intentionally reopened.
- [Preference components and attributes](https://developer.android.com/develop/ui/views/components/settings/components-and-attributes):
  `PreferenceScreen`, `PreferenceCategory`, `SwitchPreferenceCompat`, summary
  attributes, dependency relationships, `SeekBarPreference`, and XML ownership
  guidance for settings screens.
- [Use saved Preference values](https://developer.android.com/develop/ui/views/components/settings/use-saved-values):
  preference persistence plus `OnPreferenceChangeListener` and
  `OnSharedPreferenceChangeListener` guidance when settings text, summary
  providers, or behavior must update at runtime.
- [Principles for improving app accessibility](https://developer.android.com/guide/topics/ui/accessibility/principles):
  official Android guidance for meaningful labels, built-in accessibility
  features, and using cues other than color; use this when settings copy or
  display themes risk relying on color alone.
- [WCAG 2.2 Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html):
  canonical secondary accessibility reference for minimum text contrast ratios;
  use this after the Android implementation docs when a palette decision needs
  an explicit contrast floor.
- [WCAG 2.2 Use of Color](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html):
  canonical secondary accessibility reference for avoiding color-only meaning;
  use this after the Android implementation docs when evaluating whether a UI
  surface still communicates through text, shape, or layout.
- [Layout basics](https://m3.material.io/foundations/layout/understanding-layout/overview):
  current Material 3 guidance for canonical-layout-first design, panes,
  spacers, and window-size-class thinking.
- [Lists](https://m3.material.io/components/lists/overview):
  current Material 3 guidance for list scanning, slots, and the December 2025
  expressive update to list selection treatment.
- [Button groups](https://m3.material.io/components/button-groups/overview):
  current Material 3 replacement for deprecated segmented buttons when a future
  custom settings surface needs grouped option controls.
- [Switch](https://m3.material.io/components/switch/overview):
  current Material 3 guidance for standard on-off settings controls, visible
  selected state, and the stock rounded switch shape used by
  `SwitchPreferenceCompat`.
- [Create a custom drawing](https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing):
  `Canvas`, `Paint`, measurement, and drawing guidance for custom views,
  including the rule to create drawing objects ahead of time and move
  size-dependent geometry into `onSizeChanged()`.
- [Optimize a custom view](https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view):
  official custom-view hot-path guidance for keeping `onDraw()` lean,
  eliminating avoidable allocations during drawing or animation, minimizing
  unnecessary `invalidate()` calls, avoiding stray `requestLayout()` churn, and
  preferring shallow hierarchies or custom `ViewGroup` ownership when
  application-specific layout assumptions reduce work.
- [How Android draws views](https://developer.android.com/guide/topics/ui/how-android-draws):
  official measure, layout, and draw-pass overview for View-based rendering,
  including invalid-region behavior and when `requestLayout()` rather than
  `invalidate()` is the correct owner path.
- [Canvas.drawText](https://developer.android.com/reference/android/graphics/Canvas#drawText(java.lang.String,float,float,android.graphics.Paint)):
  direct text-draw API for custom `Canvas` owners that render text with a
  caller-supplied `Paint`.
- [Paint](https://developer.android.com/reference/android/graphics/Paint):
  official Android API reference for `ANTI_ALIAS_FLAG`,
  `SUBPIXEL_TEXT_FLAG`, `LINEAR_TEXT_FLAG`, `measureText(...)`, and the text
  measurement and font-metrics APIs that can change both glyph metrics and draw
  behavior; the `LINEAR_TEXT_FLAG` entry explicitly notes that it disables font
  hinting and is intended for smooth scale transitions rather than as a default
  steady-state keypad legend flag.
- [TextPaint](https://developer.android.com/reference/android/text/TextPaint):
  `Paint` subclass used for text measurement and drawing in widget text paths.
- [AOSP TextView.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java):
  current platform source initializes widget text with
  `mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG)`, which explains why the
  earlier explicit antialias experiment on this repo's widget-backed main-key
  path could appear to do nothing before the keypad text path was unified under
  custom painting.
- [Layout](https://developer.android.com/reference/android/text/Layout):
  Android's base text-layout class for visual elements on screen; built with a
  `TextPaint`, exposes `draw(Canvas)`, and documents that its `TextPaint`
  remains in active use for drawing and measuring text.
- [StaticLayout](https://developer.android.com/reference/android/text/StaticLayout):
  widget-oriented text layout path that Android explicitly contrasts with the
  direct `Canvas.drawText(...)` route for custom display objects.
- [PrecomputedText.Params](https://developer.android.com/reference/android/text/PrecomputedText.Params):
  Android text-measurement contract object that packages the `TextPaint`, break
  strategy, hyphenation, and text-direction inputs used for layout work outside
  a final `TextView` or `StaticLayout`.
- [Slow rendering](https://developer.android.com/topic/performance/vitals/render):
  official Android vitals guidance for keeping View-based rendering under the
  frame budget and for avoiding avoidable UI-thread allocation or draw-path
  work when investigating jank.
- [Make custom views more accessible (Views)](https://developer.android.com/guide/topics/ui/accessibility/views/custom-views):
  directional-controller, click-action, accessibility-event, and
  accessibility-node guidance for custom interactive views.
