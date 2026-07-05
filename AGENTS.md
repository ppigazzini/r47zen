# AGENTS.md

This file is the tracked operating contract for agents and contributors working
in the R47 Zen repository. It defines process and documentation standards, not
roadmap or feature priority. Each task still needs its own outcome and
acceptance criteria.

This repository is the Android shell, build pipeline, and maintainer overlay for
the R47 calculator variant implemented in the upstream C47 core. It is neither a
generic Android app nor a plain upstream mirror.

Only `android/`, `scripts/`, `.github/`, and a small set of root files
(`README.md`, `upstream.source`, `pyproject.toml`, `uv.lock`,
`.pre-commit-config.yaml`, `.gitignore`, `COPYING`, this file) are tracked. The
upstream tree (`src/`, `Makefile`, `meson.build`, `docs/`, `tools/`,
`subprojects/`, `dep/`, `res/`) is hydrated from the upstream commit pinned in
`upstream.source` and is excluded by the `.gitignore` reopen rule.

## Purpose

Make reliable, reviewable changes while preserving upstream-sync safety, Android
platform compliance, native core correctness, build reproducibility, and
documentation freshness.

Non-goals:

- Do not silently convert this repo into a generic Android Studio sample app.
- Do not optimize the Android shell by breaking simulator, Meson, or DMCP
  workflows unless the task explicitly allows that tradeoff.
- Do not treat the staged Android native tree as the canonical source of truth.

## Roles (Multi-Discipline Review)

Act like a small team and explicitly evaluate decisions from these viewpoints:

- Software architect: ownership boundaries, coupling, maintainability, blast
  radius.
- Android platform engineer: app identity, manifest behavior, storage policy,
  target SDK implications, PiP, large-screen constraints, packaging.
- NDK and JNI engineer: ABI policy, 16 KB page-size support, JNI shape,
  threading, symbol exposure, native packaging correctness.
- Core and embedded engineer: correctness of the upstream C47 core, generated
  assets, calculator behavior, source-of-truth boundaries.
- Build and release engineer: Makefile, Meson, Gradle, CMake, sync flow,
  reproducibility, artifact quality.
- Technical writer: clarity, freshness, scope control, and the distinction
  between canonical sources, staged sources, and maintainer-only notes.

## Working Style

- Restate the concrete goal and constraints in plain language.
- Confirm what "done" means via explicit or inferred acceptance criteria.
- Gather evidence from the codebase before proposing changes. Do not guess.
- Prefer small, composable changes that can be tested and reviewed.
- When multiple viable approaches exist, present 2-3 options and pick one.
- If assumptions are necessary, list them. Ask only the minimum targeted
  questions needed to avoid rework.
- For behavior changes, add or update tests when feasible.
- Avoid big-bang refactors unless explicitly requested.

## Engineering Principles

Preserve canonical sources and staged sources as separate concepts.

- Root `src/c47/`, `dep/`, Meson files, and generator outputs are the main
  source surfaces.
- `android/.staged-native/cpp/c47`, `generated`, `decNumberICU`, and `gmp` are
  the authoritative staged Android build inputs unless a task explicitly targets
  that staging area.
- The former tracked directories
  `android/app/src/main/cpp/{c47,generated,decNumberICU,gmp}` are retired
  snapshot paths and must stay absent during normal builds.
- `android/compat/mini-gmp-fallback` is an explicit staging-only compatibility
  fallback for public checkouts, not a canonical shared-native source root.
- Prefer changing canonical sources or the staging logic over hand-editing
  copied files.

Keep the sync contract intact.

- Local-only files must survive `scripts/upstream-sync/upstream.sh` sync
  operations.
- The synced upstream `src/` tree is authoritative after sync. This repo may add
  Android and build scaffolding, but it must not own, restore, or silently
  override `src/**` paths.
- Generic restore loops in CI or `scripts/upstream-sync/upstream.sh` must exclude
  `src/`, including `src/**/meson.build`, because a stale local manifest can
  silently replace the upstream build graph and break simulator or Android
  builds.
- If you move or add local-only files that must survive the upstream overlay,
  update `scripts/upstream-sync/upstream.sh` or document the risk explicitly.
- `upstream.source` pins only `upstream_url` and `upstream_ref` (currently
  `HEAD`); by policy no tracked file pins a specific `upstream_commit`. CI and
  the release lane resolve the newest commit on every run, so builds track the
  latest upstream HEAD, not a fixed core. A pin is optional and local only:
  record it in the git-ignored `upstream.lock` (delete it to resume following
  HEAD). Two builds of the same repo commit on different days can therefore
  compile different cores; the upstream commit a release shipped is recorded
  after the fact in the release tag, the published `BUILD-METADATA`, and the
  tracked `android/docs/dev/release-history.md`, not in a source pin.

Choose the right surface for the change.

- Do not patch build-only staged Android copies when the real source lives at
  the repo root.
- Do not fix Android symptoms only in Kotlin if the root cause is in the shared
  native core.
- Do not fix shared native behavior only in the staged Android tree if the same
  bug can reappear after the next sync or rebuild.

Respect Android-native platform contracts.

- Keep `applicationId`, `namespace`, SDK levels, ABIs, and 16 KB behavior
  intentional and explicit.
- Treat SAF, PiP, storage URIs, and app identity as high-consequence surfaces.
- Prefer official Gradle, CMake, NDK, and Android platform guidance over
  folklore.

Keep Kotlin, JNI, and native code aligned.

- Every JNI signature change must be checked on both sides.
- Threading assumptions must stay explicit.
- Avoid widening the JNI surface casually.

Performance and operability matter.

- Avoid unnecessary work on hot UI or core-loop paths.
- Prefer explicit generated-asset flows over duplicated manual edits.
- Keep builds and sync flows observable and debuggable.

## Implementation Process

For changes beyond trivial edits:

1. Establish baseline behavior.
2. Gather evidence from the codebase and the relevant docs.
3. Identify risks: sync survivability, Android behavior, build drift, JNI drift,
   packaging drift.
4. Implement the smallest change that satisfies the acceptance criteria.
5. Run the smallest relevant checks first, then broader verification when
   practical.
6. Keep exploratory analysis and provisional claims in the task working doc while
   code and focused verification are still moving.
7. When maintained docs are in scope, promote the verified final contracts into
   every affected maintained doc in one coherent pass.
8. End the task with the working doc aligned to the final code and maintained
   docs, including one final squash-ready conventional commit when the task uses
   a working doc.

## Verification Standards

- Prefer automated verification over manual checks when feasible.
- Build or check the smallest relevant surface first.
- Root native or simulator changes: verify `make sim`, then run the narrowest
  relevant test target (`make test`) when practical.
- Android changes: prefer `./scripts/android/build_android.sh` when the task
  touches staging, generated files, or the full Android flow. Use
  `cd android && ./gradlew assembleDebug` only when the staged tree is already
  current and the task is module-local.
- Treat `cd android && ./gradlew lint` as mandatory when Kotlin files or Android
  Gradle surfaces change; `assembleDebug` and `:app:compileDebugKotlin` do not
  run lint automatically.
- Packaging-sensitive changes: verify ABI output and 16 KB alignment.
- Sync changes: validate that `scripts/upstream-sync/upstream.sh` still preserves
  local-only files that matter to the task
  (`scripts/upstream-sync/upstream.sh verify-restore-boundary`).
- Docs changes: verify every claim against live code, scripts, and the official
  references.
- If tests cannot be run, state why and what was checked instead.

## Definition Of Done

A task is done when:

- The relevant target builds, or the inability to build is explicitly stated.
- Relevant tests pass, or the inability to run them is explicitly stated.
- Cross-surface implications were checked for any touched canonical or staged
  source.
- The sync contract was checked if the task adds or moves local-only files.
- Docs and internal notes reflect the new behavior when needed.
- External references, if used, point to official or canonical sources.
- The final recommendation and residual risks are explicit.

## Safety And Repo Hygiene

- Never run destructive commands unless explicitly requested.
- Do not add dependencies, packaging complexity, or persistent workaround logic
  without a concrete reason.
- Keep changes scoped. Do not mix unrelated refactors into feature work.
- Do not bypass build or packaging issues with undocumented shell-script hacks.
- Do not rely on staged Android copies as the only place a fix exists unless the
  task is explicitly about that staging area.

## Documentation Standards

This repo has two documentation audiences. Do not mix them casually:
`android/docs/dev/` is maintainer-facing; `README.md` and in-app
strings are project-facing or user-facing.

Core rules:

1. State the page contract first.
2. Use authoritative mood. Say what the repo does, requires, or guarantees.
3. Keep one page for one job.
4. Prefer concrete facts, defaults, constraints, and verified examples over
   narration.
5. A maintainer doc that describes a contract must name the verification surface
   that locks that contract.
6. Include a small graph or routing map for multi-step flows, lane splits, or
   ownership handoffs when skimming prose would be slow.
7. Be explicit about uncertainty. If something was not built or tested in the
   current workspace, say so.
8. Keep user-facing docs free of internal/local references, issue shorthand, and
   internal/local archaeology.
9. Use ASCII by default for maintainer docs. Do not draw ASCII-art boxes; use a
   mermaid graph when a visual helps.
10. Every example command, path, version, ABI, SDK level, and file name must be
    correct on the current codebase.
11. Do not describe placeholder values as if they were release-ready values.
12. Do not import assumptions from the upstream C43/C47 history or older internal
    docs without re-verifying them here.

Avoid hype, vague claims such as "seamless" or "future-proof" without proof,
apologies, and speculative future work presented as current behavior. Never copy
a value into docs just because it appeared once in an old note, script comment,
or imported template.

## Evidence Requirements (External References)

When citing external behavior, APIs, or best practices:

- Use official or canonical sources first.
- Link the exact page used.
- Prefer Android and NDK primary documentation over blogs.
- If a non-official source is used, label it clearly as secondary.

## References To Always Analyze

- `README.md`
- `scripts/android/build_android.sh`
- `scripts/upstream-sync/upstream.sh`
- `Makefile`
- `meson.build` and relevant subdir `meson.build` files
- `android/build.gradle`, `android/gradle.properties`, and
  `android/settings.gradle`
- `android/app/build.gradle`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/cpp/CMakeLists.txt`
- `android/docs/dev/README.md`
- `android/docs/dev/10-build-and-source-layout.md`
- `android/app/src/main/java/io/github/ppigazzini/r47zen/MainActivity.kt` and
  `android/app/src/main/java/io/github/ppigazzini/r47zen/SettingsActivity.kt`
  when the task touches Android behavior
- `android/app/src/main/cpp/r47zen/jni_registration.c`,
  `android/app/src/main/cpp/r47zen/jni_activity_bridge.c`,
  `android/app/src/main/cpp/r47zen/jni_lifecycle.c`, and
  `android/app/src/main/cpp/r47zen/hal/io.c` when the task touches JNI, storage,
  lifecycle, or display behavior

## Official References (Pinned)

- Android and NDK: `https://developer.android.com/`, `https://developer.android.com/ndk`
- CMake: `https://cmake.org/cmake/help/latest/`
- Meson: `https://mesonbuild.com/`
- GNU Make: `https://www.gnu.org/software/make/manual/make.html`
- Upstream core repo: `https://gitlab.com/rpncalculators/c43.git`
- Upstream wiki: `https://gitlab.com/h2x/c47-wiki/-/wikis/home`
