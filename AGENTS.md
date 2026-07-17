# AGENTS.md

Instructions for AI agents and new contributors working in this repository. Read
this before touching anything. It is short on purpose: it is only what a
newcomer gets wrong *before* reading
[android/docs/dev/](android/docs/dev/README.md), which is where the detail
lives.

**Docs are part of the change, not after it.** Each page in `android/docs/dev/`
is a live claim about the thing it describes - and several of them describe
**upstream**, a tree that moves without a commit here. Change something a page
claims, fix the page in the SAME commit; sync upstream, re-read the pages that
track it. [android/docs/dev/10-writing.md](android/docs/dev/10-writing.md)
carries the rules for everything this repo writes for a reader - pages,
comments, and commit messages alike - and maps every page to what it owns and
which run hot. Nothing gates the prose: this repo has no docs lint, so a dead
link, a stale number, or a sentence that has become false is yours to catch.

## What this repository is

This is the **Android shell, build pipeline, and maintainer overlay** for the
R47 calculator variant implemented in the upstream C47 core.

- The upstream C47 core is the product. Its authoritative source is the
  repository configured in `upstream.source`, currently
  <https://gitlab.com/rpncalculators/c43.git>. That GitLab path still uses the
  older `c43` repository name; the core this repo consumes is C47.
- **Tracked here:** `android/`, `scripts/`, `.github/`, and a small set of root
  files (`README.md`, `AGENTS.md`, `CLAUDE.md`, `upstream.source`,
  `pyproject.toml`, `uv.lock`, `.pre-commit-config.yaml`, `.gitignore`,
  `COPYING`).
- **Hydrated, not tracked:** `src/`, `Makefile`, `meson.build`, `docs/`,
  `tools/`, `subprojects/`, `dep/`, and `res/` come from upstream on sync and
  are excluded by the `.gitignore` reopen rule. `res/fonts` is among them, so
  the font assets a contract derives from are whatever the last sync hydrated.

## What this repository is not

- Not a generic Android app, and not a plain upstream mirror.
- Not the place to own `src/**`. This repo adds Android and build scaffolding;
  it must never restore or silently override upstream-owned paths.
- Not a project where the staged Android native tree is the source of truth.

## Non-negotiables

1. **This repo tracks the latest upstream HEAD.** No tracked file pins an
   `upstream_commit`; `upstream.source` carries only `upstream_url` and
   `upstream_ref`. A pin is local and optional: put it in the git-ignored
   `upstream.lock`. `scripts/upstream-sync/upstream.sh verify-source-policy`
   enforces this and pre-commit runs it. Two builds of the same repo commit on
   different days can therefore compile different cores.
2. **`__DEV/` is git-ignored and maintainer-only.** Never commit it, and never
   rest a tracked claim on content only its author can see - a fresh clone does
   not have it. Naming the path to mark it ignored is fine. Tracked
   documentation lives in `android/docs/dev/`.
3. **ASCII by default** in tracked docs. No ASCII-art boxes; use mermaid.
4. **Conventional commits**, carrying the evidence rather than "should work".
   [android/docs/dev/10-writing.md](android/docs/dev/10-writing.md#commit-messages)
   owns the format; do not restate it here.
5. **Never add a `Co-Authored-By` trailer** or any agent attribution to a
   commit.
6. **Do not run destructive commands** unless asked. In particular `git stash
   drop`, `git reflog expire`, `git gc --prune`, and force-pushes.

## Read this first

| you want to | read |
|---|---|
| understand what this repo owns and where the upstream boundary sits | [00-project-and-upstream.md](android/docs/dev/00-project-and-upstream.md) |
| fix a build break, stale staged inputs, or checkout drift | [01-build-and-source-layout.md](android/docs/dev/01-build-and-source-layout.md) |
| change lifecycle, storage, settings, or input flow | [02-kotlin-shell-architecture.md](android/docs/dev/02-kotlin-shell-architecture.md) |
| call into upstream-owned runtime behavior | [03-upstream-interface-surfaces.md](android/docs/dev/03-upstream-interface-surfaces.md) |
| change JNI, SAF, the HAL, or native packaging | [04-native-core-and-jni.md](android/docs/dev/04-native-core-and-jni.md) |
| chase renderer, geometry, or keypad-scene drift | [05-ui-rendering-and-gtk-mapping.md](android/docs/dev/05-ui-rendering-and-gtk-mapping.md) |
| touch a hot loop, a redraw path, or a lock boundary | [06-runtime-hot-paths.md](android/docs/dev/06-runtime-hot-paths.md) |
| understand or add a CI lane, or cut a release | [07-ci-and-release-workflow.md](android/docs/dev/07-ci-and-release-workflow.md) |
| find what locks a contract, or which lane to rerun | [08-tests-and-contracts.md](android/docs/dev/08-tests-and-contracts.md) |
| find an authoritative external reference | [09-official-references.md](android/docs/dev/09-official-references.md) |
| write a doc, a code comment, or a commit message | [10-writing.md](android/docs/dev/10-writing.md) |

## This file, and CLAUDE.md

`AGENTS.md` is the cross-tool convention (stewarded by the Agentic AI Foundation
under the Linux Foundation; read natively by Codex, Cursor, Aider, Jules, and
others). **Claude Code does not read it** - it reads `CLAUDE.md` only. The root
`CLAUDE.md` therefore carries nothing but a pointer and an `@AGENTS.md` import,
the syntax Anthropic documents for exactly this case; the sibling zfish, z47,
and c47-r47-ci repos use the same shape. It is not a duplicate: edit this file,
never that one. A symlink would also work but breaks on Windows without
Developer Mode.

## The short version of the workflow

```bash
# hydrate or refresh the authoritative upstream core
bash ./scripts/upstream-sync/upstream.sh sync --auto --write-lock

# the canonical Android debug build (stages native inputs, then Gradle)
./scripts/android/build_android.sh

# host and staging readiness
./scripts/android/build_android.sh --doctor
```

The module-local lane is only valid when the staged native inputs are already
current:

```bash
cd android && ./gradlew assembleDebug
```

Gradle needs the SDK on the path via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`android/local.properties`. The SDK location is machine-specific; re-derive it
rather than trusting a remembered path.

## The verification discipline

Run focused checks first, then broader verification when practical. If a check
cannot run, say why and record what was checked instead.

1. **Lint is not implied.** `cd android && ./gradlew lint` is mandatory when
   Kotlin, manifest, resource, or Android Gradle surfaces change.
   `assembleDebug` and `:app:compileDebugKotlin` do not run it.
2. **A freshness guard is not an oracle.** The goldens under
   `scripts/r47_contracts/` re-derive from live inputs, so a guard that compares
   the committed file against a fresh re-derivation is circular: re-running the
   deriver re-blesses any change, including a wrong one. Before re-blessing,
   confirm the independent correctness tests still pass and root-cause the
   drift.
3. **Upstream drift is not a regression, and not a licence either.** When a
   golden moves because upstream moved, name the upstream commit and show what
   changed. Regenerating without that is laundering.
4. **Verify against a live tree, not memory.** When a local note and upstream
   disagree, upstream wins.
5. **Retract what you cannot prove.** Say which claims are measured, which are
   read from the source, and which are inferred.

The maintained entry points:

```bash
bash ./scripts/r47_contracts/run_contract_suite.sh   # ruff + ty + contracts
make sim && make test                                # upstream simulator core
```

## Facts that surprise people

- **A lane failing does not mean this repo changed.** Every lane resolves
  upstream HEAD at runtime, so an upstream commit breaks CI here with no commit
  here. Pin `upstream.lock` locally to tell the two apart.
- **Switching upstream commits locally corrupts the staged tree**, and it
  surfaces as unrelated clang/NDK errors. Remove `android/.staged-native`,
  `build.sim`, `android/app/.cxx`, and `android/app/build` before re-staging.
- **The staged tree strips the `src/c47/` and `dep/` prefixes**, so a path under
  `android/.staged-native/cpp/` is not an upstream path. Do not cite it as one.
- **`res/fonts` is git-ignored and hydrated from upstream**, so a font asset can
  change with no commit here and move a derived golden.
- **The retired paths
  `android/app/src/main/cpp/{c47,generated,decNumberICU,gmp}` must stay absent**
  during normal builds. Android-owned native glue lives in
  `android/app/src/main/cpp/r47zen`.

## Definition of done

- The relevant target builds and the relevant tests pass, or the inability to
  run them is stated.
- Cross-surface implications were checked for any touched canonical or staged
  source, and Kotlin, JNI, and native signatures still agree.
- The sync contract was checked if the task adds or moves local-only files
  (`scripts/upstream-sync/upstream.sh verify-restore-boundary`).
- Every page whose claims the change touched was updated in the same commit.
- Tracked docs are ASCII and do not reference `__DEV/`.
- Residual risks are explicit.
