# Android Development Docs

These pages are the maintainer reference for R47 Zen, the Android shell, build
pipeline, and maintainer overlay around the upstream C47 calculator core. The
core is not tracked here: a sync hydrates it from upstream, and the build
stages a copy of it for the NDK. The pages are code-facing, not end-user
usage docs.

[AGENTS.md](../../../AGENTS.md) at the repository root is the one-page short
form: the commands, the traps, and the rules, each linked to the page here
that owns it. Read it first, then the project page and the build page; the
rest of the set assumes you know what this repo owns, what upstream owns, and
where the overlay boundary sits.

## Maintainer Doc Flow

```mermaid
flowchart TD
  A[00 project and upstream]
  B[01 build and source layout]
  C[02 Kotlin shell architecture]
  D[03 upstream interface surfaces]
  E[04 native core and JNI]
  F[05 UI rendering and GTK mapping]
  G[06 runtime hot paths]
  H[07 CI and release workflow]
  I[08 tests and contracts]
  J[09 official references]
  K[10 writing]
  L[11 reviewing a change]
  M[12 cutting a release]

  A --> B
  B --> C
  B --> D
  B --> E
  C --> F
  D --> F
  E --> G
  F --> G
  G --> H
  H --> I
  H --> M
  A -.->|before writing anything| K
  A -.->|before reviewing a change| L
  A -.->|when a page cites a vendor doc| J
```

The solid path is the reading order for the code. 10 and 11 are not the end
of it: read 10 before you write a page, a comment, or a commit, and 11 before
you review one.

## Read In Order

- [00-project-and-upstream.md](00-project-and-upstream.md): what this repo is,
  what the upstream C47 project is, what this repo owns, and how the Android
  overlay interfaces with upstream-owned sources and runtime behavior.
- [01-build-and-source-layout.md](01-build-and-source-layout.md): build
  entrypoints, ownership boundaries, staged inputs, compile flow, and
  checkout-sensitive root surfaces.
- [02-kotlin-shell-architecture.md](02-kotlin-shell-architecture.md): Android
  lifecycle, helper ownership, storage, settings, slot flow, input flow, and
  direct live-stop routing.
- [03-upstream-interface-surfaces.md](03-upstream-interface-surfaces.md): the
  detailed interface from the Android shell into upstream-owned runtime
  behavior.
- [04-native-core-and-jni.md](04-native-core-and-jni.md): CMake, JNI
  registration, Android HAL seams, SAF bridge behavior, and native packaging.
- [05-ui-rendering-and-gtk-mapping.md](05-ui-rendering-and-gtk-mapping.md):
  logical canvas, LCD projection, keypad geometry, and renderer rules.
- [06-runtime-hot-paths.md](06-runtime-hot-paths.md): the main hot loops,
  redraw paths, and regression-sensitive lock boundaries.
- [07-ci-and-release-workflow.md](07-ci-and-release-workflow.md): GitHub
  Actions lane split, release gating, artifacts, and local reproduction.
- [08-tests-and-contracts.md](08-tests-and-contracts.md): the maintainer map
  of verification surfaces, contract owners, focused test suites, and first
  rerun lanes.
- [09-official-references.md](09-official-references.md): official Android,
  NDK, Gradle, Kotlin, GitHub Actions, and upstream reference surfaces.
- [10-writing.md](10-writing.md): the rules for the doc pages, the code
  comments, and the commit messages, plus which pages run hot.
- [11-reviewing-a-change.md](11-reviewing-a-change.md): the four checks for a
  change or an audit that crosses a seam, and where each seam's state
  inventory lives.
- [12-cutting-a-release.md](12-cutting-a-release.md): the ordered release
  procedure, what each refusal means and how to recover, and re-releasing a
  past build.

## By Task

- build break, stale staged inputs, or checkout drift:
  [01-build-and-source-layout.md](01-build-and-source-layout.md)
- JNI, SAF, or Android-native bridge change:
  [03-upstream-interface-surfaces.md](03-upstream-interface-surfaces.md) and
  [04-native-core-and-jni.md](04-native-core-and-jni.md)
- renderer, geometry, or keypad-scene drift:
  [05-ui-rendering-and-gtk-mapping.md](05-ui-rendering-and-gtk-mapping.md),
  [06-runtime-hot-paths.md](06-runtime-hot-paths.md), and
  [08-tests-and-contracts.md](08-tests-and-contracts.md)
- verification planning, contract ownership, or CI test routing:
  [08-tests-and-contracts.md](08-tests-and-contracts.md) and
  [07-ci-and-release-workflow.md](07-ci-and-release-workflow.md)
- long-running stop parity, live touch `EXIT` handling, or desktop-stop
  comparisons:
  [02-kotlin-shell-architecture.md](02-kotlin-shell-architecture.md),
  [03-upstream-interface-surfaces.md](03-upstream-interface-surfaces.md),
  [06-runtime-hot-paths.md](06-runtime-hot-paths.md),
  [08-tests-and-contracts.md](08-tests-and-contracts.md), and
  [09-official-references.md](09-official-references.md)
- writing a page, a code comment, or a commit message:
  [10-writing.md](10-writing.md)
- reviewing a change, or auditing code, where state crosses a thread, the JNI
  boundary, or a cache: [11-reviewing-a-change.md](11-reviewing-a-change.md)
- cutting a release or re-releasing a past build:
  [12-cutting-a-release.md](12-cutting-a-release.md)

## By Symptom

Start from what you see; each row names the usual cause and the page that
owns the fix.

| symptom | usual cause | go to |
|---|---|---|
| clang or NDK errors in upstream files you did not touch, after switching upstream commits | the staged tree mixes two cores | remove `android/.staged-native`, `build.sim`, `android/app/.cxx`, and `android/app/build`, then [rebuild from the top](01-build-and-source-layout.md#when-to-rebuild-from-the-top) |
| a CI lane fails with no commit here | upstream HEAD moved; every lane resolves it | pin `upstream.lock` to the last good commit to tell the two apart ([00](00-project-and-upstream.md#what-the-upstream-project-is)) |
| `--android-only` refuses to build | the staged inputs no longer match the sources | the full build ([build entry points](01-build-and-source-layout.md#build-entry-points)) |
| a contract golden fails after a sync | an upstream input it derives from moved | the [provenance ledger](08-tests-and-contracts.md#upstream-provenance-ledger), before any re-bless |
| Gradle says "No matching toolchains found" | the pinned build JDK is not installed | [checked-in defaults](01-build-and-source-layout.md#checked-in-defaults); `build_android.sh --doctor` names it |
| lint fails in CI on a change that built locally | builds do not run lint | `cd android && ./gradlew lint` ([the gates](10-writing.md#the-gates)) |
| docs lint fails on a page you did not edit | a path you renamed or a file you added is named, or missing, there | [the gates](10-writing.md#the-gates) lists each check |
| a key label, a softkey, or the keypad layout looks wrong | scene export, render spec, or projection | [debug by symptom](05-ui-rendering-and-gtk-mapping.md#debug-by-symptom) |
| the UI stalls while a program runs | a long native run that does not yield | [yield and SAF I/O boundary](06-runtime-hot-paths.md#yield-and-saf-io-boundary) and [the residual hang](06-runtime-hot-paths.md#residual-hang-nan-driven-non-yielding-runs) |
| a release dispatch, or its Play upload, fails | an input refusal, or Play state | [when the workflow refuses](12-cutting-a-release.md#when-the-workflow-refuses) |

## Glossary

Each term links the page that owns it.

- **overlay**: this repository, laid over the upstream tree; it owns the
  Android shell, the build, and these docs
  ([00](00-project-and-upstream.md#what-this-repository-is)).
- **hydrate**: write the upstream core's tree into the working tree with
  `upstream.sh sync`; `src/`, `dep/`, and `res/` exist only after it
  ([00](00-project-and-upstream.md#working-tree-after-hydration)).
- **roadblock pin**: an `upstream_commit` in the git-ignored `upstream.lock`,
  or the `upstream_commit` dispatch input, that holds one upstream revision;
  never in a tracked file
  ([00](00-project-and-upstream.md#what-the-upstream-project-is)).
- **staged tree**: `android/.staged-native/cpp`, the build-only copy of the
  core CMake compiles, with the `src/` and `dep/` prefixes stripped
  ([01](01-build-and-source-layout.md#canonical-versus-staged-native-inputs)).
- **glue**: the repo-owned C under `android/app/src/main/cpp/r47zen` that
  `glue_sources.txt` lists: the JNI bridge and the HAL
  ([04](04-native-core-and-jni.md#native-library-shape)).
- **core thread**: the one thread that runs the native core and owns its
  lifecycle ([06](06-runtime-hot-paths.md#core-thread-coordinator)).
- **generation**: a counter the core bumps when the LCD or the keypad scene
  changes, so a reader copies only on a change
  ([06](06-runtime-hot-paths.md#frame-refresh-loop)).
- **scene**: one keypad state, every key's labels, roles, and flags, as the
  core exports it ([05](05-ui-rendering-and-gtk-mapping.md#display-and-scene-handoff)).
- **seam**: a place where state crosses a thread, the JNI boundary, or a
  cache ([11](11-reviewing-a-change.md#the-four-checks)).
- **lane**: one CI job, or a local command that reproduces it
  ([07](07-ci-and-release-workflow.md#ci-at-a-glance)).
- **golden**: a committed file a test compares against, re-derived from live
  inputs; a **freshness guard** proves only that it re-derives, and an
  **oracle** is a test rooted in facts outside it
  ([08](08-tests-and-contracts.md#python-contract-suite)).
- **`-dev` prerelease**: the snapshot build `android-ci` publishes from
  `main` and prunes on a TTL, installed as a separate app id and
  signed by the prerelease key; a **signed release** is the production build
  `android-release` publishes
  ([07](07-ci-and-release-workflow.md#dev-pre-release-retention),
  [12](12-cutting-a-release.md)).

## Maintainer Update Workflow

Use one promotion workflow when a non-trivial task changes Android behavior,
contracts, or verification.

1. Draft the task analysis, options, implementation notes, and verification
  outside the maintained pages first; nothing a reader needs may live only in
  a draft.
2. Keep exploratory notes and provisional claims in the draft while the code
  and test result are still moving.
3. After the implementation and focused verification settle, promote the final
  contract changes into every affected maintained page in one pass.
4. Keep these pages authoritative and durable. Leave dead ends, experiments,
  and superseded commit ideas in the draft instead of carrying them into
  the maintained reference set.

[10-writing.md](10-writing.md) owns how these pages are written and marks which
of them run hot; it is not restated here.

## Build Entry Points

AGENTS.md's short workflow lists the commands.
[01-build-and-source-layout.md](01-build-and-source-layout.md#build-entry-points)
owns what each one does, and when the module-local Gradle lane is valid;
[canonical versus staged native inputs](01-build-and-source-layout.md#canonical-versus-staged-native-inputs)
owns which tree to edit.

## Repo-Owned Automation Layout

- `scripts/` owns repo-only automation.
- `scripts/upstream-sync/` owns grouped upstream resolution and sync helpers.
- `scripts/android/` owns Android build, staging, packaging, and helper
  implementation.
- `scripts/r47_contracts/` owns the checked-in physical and Android UI
  contract sources plus the Python derivation and validation tools that lock
  Kotlin geometry and layout owners to those sources, including the shared
  keyboard-layout contract JSON consumed by JVM parity tests. The grouped
  maintained entry point is `bash ./scripts/r47_contracts/run_contract_suite.sh`.
- `scripts/keypad-fixtures/`, `scripts/package-notices/`, and
  `scripts/workload-regressions/` own the fixture export, notice generation,
  and host workload lanes.

If a change crosses Kotlin, JNI, and rendering ownership at the same time, read
the project page, the build page, the Kotlin page, the JNI page, and the
upstream-interface page before editing.
