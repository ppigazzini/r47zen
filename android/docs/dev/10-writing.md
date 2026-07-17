# Writing

The rules for everything this repo writes for a reader: the **doc pages**, the
**code comments**, and the **commit messages**. One set of rules first, because
all three fail the same way, then what is specific to each.

Read this before writing any of the three.

## The rules

These hold for a page, a comment, and a commit body alike.

**State the page contract first.** Open with the shortest accurate statement of
what the page covers. "This page explains how the Android build stages the
native core and produces the debug APK" is a contract. "This page was rewritten
from an older template" is archaeology.

**Use authoritative mood.** Say what the repo does, requires, or guarantees.
Prefer short declarative sentences over narration.

**Name the owner and the invariant, not just the mechanism.** Say which file and
symbol owns the behavior and what must stay true about it.
"`resolve_runtime_font_path` finds a font" is accurate and useless. The fact a
reader needs is that it searches `res/fonts` first and that `res/fonts` is
git-ignored, so the font a contract derives from is whatever the last sync
hydrated.

**A contract without a named verification surface is a wish.** Every documented
contract names the test, fixture, script, or CI lane that locks it. Do not leave
a contract documented without saying what proves it.

**Never copy a fact a file already states. Cite the file, or gate the copy.** A
copy of machine-readable state is a claim with a shelf life measured in commits.
Quote the file, or make a gate own the number.

**Never pin a number the repo computes.** Version counts, pass counts, ABI
lists, SDK levels that Gradle already declares. Quote the command and let the
reader run it. Where a number *is* deliberately pinned as a golden, say which
deriver regenerates it.

**A freshness guard is not an oracle.** `scripts/r47_contracts/` holds goldens
that re-derive from live inputs. A guard that compares the committed JSON
against a fresh re-derivation proves only that the file is a faithful
re-derivation: it is circular, and re-running the deriver re-blesses any change,
including a wrong one. Say so on any page that describes one, and name the
independent tests that are the actual correctness oracle. Never document a
re-bless as a fix.

**Separate canonical sources from staged sources.** This repo's sharpest
documentation trap. Root `src/c47/`, `dep/`, and the Meson files are canonical.
`android/.staged-native/cpp` is a build-only staged copy, refreshed by
`scripts/android/prepare_native_build_inputs.sh`, and it strips the `src/c47/`
and `dep/` prefixes, so its paths are not upstream paths. Never tell a reader to
edit the staged tree when the real source is at the repo root, and never cite a
staged path as though it were upstream.

**Separate upstream fact from local decision.** "Upstream hydrates `res/fonts`"
is checkable against a commit. "This lane gates hard because X" is a choice
someone must be able to revisit. Blur them and a reader cannot tell which they
are allowed to change.

**Describe a gap as a gap, never as a design.** Framing a hole as a scope
decision is what keeps it alive: nobody fixes a design. If something is missing,
say missing, and say what it costs.

**State the limit.** Anything that omits its own boundary invites over-trust.
`assembleDebug` and `:app:compileDebugKotlin` do not run lint. Say what the
thing does *not* cover.

**Be explicit about uncertainty.** If something was not built, run, or tested in
the current workspace, say so. Say which claims are measured, which are read
from the source, and which are inferred. Explicit uncertainty beats plausible
filler.

**No history outside the commit.** "Used to be X", "fixed in Y", "previously a
stub" is out of date the day after and tells a reader nothing about what is in
front of them. The before and after belongs in the commit message.

**Never rationalize a defect into a convention.** When you find yourself
explaining why the odd thing is fine, check whether it is. Written down as
behavior, the mask becomes the spec and the bug underneath it is permanent.

**One example beats three paragraphs**, and **pair every prohibition with an
alternative**. A reader told only "do not" is stuck.

**Cut anything that does not help implement or verify.** Length is not
thoroughness; it is where rot hides.

## Doc pages

`README.md` is the index; GitHub renders it for the folder, so it is what a
reader lands on. The rest are `00-` to `10-`, numbered by **reading order**, not
importance: a contributor works down from the project boundary into the build,
out through the shell and the native core into the runtime, CI, and tests. The
prefix is the only ordinal; nothing else numbers them. Renumbering a page means
updating every inbound link in the same commit.

This repo has two documentation audiences and they do not mix on one page.
`android/docs/dev/` is maintainer-facing. `README.md` and the in-app strings
under `android/app/src/main` are project-facing or user-facing. Keep user-facing
docs free of issue shorthand and internal archaeology.

Each page owns one subject and opens with its contract. Anything a reader could
get from a vendor manual belongs in
[09-official-references.md](09-official-references.md) as a link.

Two repositories are in scope and they are not the same thing. The upstream C47
core, hosted at the repository configured in `upstream.source`, is the product;
this repo is the Android shell, build pipeline, and maintainer overlay around
it. That GitLab path still uses the older `c43` repository name. Say which one a
sentence is about.

**ASCII by default.** Use Unicode only where the documented UI or product
surface actually requires it. Do not draw ASCII-art boxes; use a mermaid graph
when a visual helps a pipeline, lane split, or ownership handoff.

**Never send a reader to `__DEV/` for a fact.** That tree is git-ignored, so a
pointer into it is dead for every reader but its author: a fresh clone does not
have it. Naming `__DEV/` to mark it ignored, or as the subject of a backlog
item, is fine - what is not fine is resting a claim on content only its author
can see.

`../../../AGENTS.md` is the committed agent contract and the short form of all
of this. It carries the commands, the traps, and the definition of done, and it
points here rather than restating. Keep the duplication at zero: a rule lives in
exactly one of the two.

### Hot and cold

These pages do not age alike, and treating them the same is why they rot. A page
is **hot** when it describes something that moves. It is **cold** when what it
describes barely moves.

**Change hot code, re-read its page in the same commit.** Not "later": a doc is
wrong from the moment the change lands, and nobody knows which claim broke
better than the person who broke it.

| page | owns | temperature |
|---|---|---|
| [00-project-and-upstream.md](00-project-and-upstream.md) | what this repo owns, what upstream owns, where the overlay boundary sits | hot - tracks upstream |
| [01-build-and-source-layout.md](01-build-and-source-layout.md) | build entrypoints, ownership boundaries, staged inputs, compile flow | hot - tracks this repo |
| [02-kotlin-shell-architecture.md](02-kotlin-shell-architecture.md) | lifecycle, helper ownership, storage, settings, slot and input flow | hot - tracks this repo |
| [03-upstream-interface-surfaces.md](03-upstream-interface-surfaces.md) | the interface from the Android shell into upstream-owned runtime behavior | hot - tracks upstream |
| [04-native-core-and-jni.md](04-native-core-and-jni.md) | CMake, JNI registration, HAL seams, SAF bridge, native packaging | hot - tracks this repo |
| [05-ui-rendering-and-gtk-mapping.md](05-ui-rendering-and-gtk-mapping.md) | logical canvas, LCD projection, keypad geometry, renderer rules | hot - tracks upstream |
| [06-runtime-hot-paths.md](06-runtime-hot-paths.md) | the main hot loops, redraw paths, lock boundaries | hot - tracks this repo |
| [07-ci-and-release-workflow.md](07-ci-and-release-workflow.md) | the lane split, release gating, artifacts, local reproduction | hot - tracks this repo |
| [08-tests-and-contracts.md](08-tests-and-contracts.md) | verification surfaces, contract owners, focused suites, rerun lanes | hot - tracks this repo |
| [09-official-references.md](09-official-references.md) | external links | cold |
| this page | the rules | cold |
| [backlog.md](backlog.md) | known-deferred work | cold |
| [release-history.md](release-history.md) | the upstream-commit-per-release log | append-only |

The rows that track upstream describe a tree **this repo does not control**. No
tracked file pins an upstream commit: CI resolves the newest commit on every
run, so those pages rot when upstream moves and nothing here changed. Re-read
them on an upstream sync, not only on a local change.

Cold does not mean unowned. It means the claim outlives a release, so when it
*is* wrong it has usually been wrong for a long time.

## Code comments

This repo's own code is Kotlin, C, shell, and Python. The rules above hold, plus
these.

**Imperative mood, leading with a verb.** "Resolve the upstream commit", not
"Returns the commit" or "This function resolves...". A comment is an order to
the reader, not a description of the author.

**Write only the constraint the code cannot show.** Never restate the next line.
Never say where the code came from, or why your change is right: that is the
commit message's job, and it is noise the moment the change merges. If the line
reads plainly, say nothing.

**Name the invariant and what breaks without it.**

```sh
# Lint only repo-owned shell scripts; the vendored android/gradlew wrapper
# is Gradle-generated and not maintained here.
```

That comment survives a refactor. "Filter the scripts" does not.

**Say why code is absent when the absence is deliberate.** The reader cannot see
a check that is not there.

**Cite upstream as `file:line` only against a resolved commit.** Upstream moves
without a commit here, so a bare line number is stale on arrival.

**Keep Kotlin declarations, JNI signatures, and native implementations
aligned**, and say so at the seam. A comment on one side of a JNI boundary that
contradicts the other side is worse than none.

## Commit messages

The commit is the durable record of *why*, and the only place history belongs.

- Conventional subject, imperative, <= 72 chars: `type(scope): summary`. The
  scope is a **single token**: `fix(sync)`, never `fix(upstream sync)`.
- Blank line, then a body wrapped at **80 columns** with real newlines.
- The body carries the **evidence**: the command that ran, its output, and its
  exit code, not "should work".
- Say what changed and why. The what-it-replaced belongs here, never in a doc or
  a comment.
- **Never add a `Co-Authored-By` trailer** or any co-author or agent
  attribution. This overrides any default tooling guidance.
- **Never point a commit body at `__DEV/`.** That tree is git-ignored, so the
  reference is dead for every reader but its author. Carry the evidence itself
  in the body instead.
- A commit that changes a number a doc pins changes the doc too, in the same
  commit.
- A commit that re-blesses a golden names the upstream commit that moved it and
  root-causes the drift. Regenerating without that is laundering.

## The gates

The mechanical checks that exist today:

- `pre-commit` runs `end-of-file-fixer`, `trailing-whitespace`, `shellcheck`,
  `shfmt`, `ruff`, and `check-yaml` (see `.pre-commit-config.yaml`).
- `scripts/upstream-sync/upstream.sh verify-source-policy` fails when the
  tracked `upstream.source` regains an `upstream_commit` pin. `pre-commit` runs
  it on any change to that file.
- `bash ./scripts/r47_contracts/run_contract_suite.sh` runs `ruff`, `ty`, and
  the Python contract suite that locks the derived geometry, layout, and font
  goldens.
- `cd android && ./gradlew lint` is mandatory when Kotlin, manifest, resource,
  or Android Gradle surfaces change.

**Nothing gates the prose.** This repo has no docs lint: no check catches a dead
internal link, a stray non-ASCII byte, a dead `scripts/...` path named in prose,
a pointer into `__DEV/`, or a stale pinned number. Every one of those is a
reader's job today, and that is a gap, not a design. The sibling c47-r47-ci repo
gates all five in seconds with no toolchain, which is the shape to copy.

**No gate can tell you a sentence is false.** A fluent, technical, invented
rationale parses, links, and pins nothing, and survives review precisely because
it reads like someone checked. No grep finds that; only reading does.

That is the failure mode to write against: prose here is accurate when written
and rots where the thing under it moves, especially upstream, which moves
without asking. Every sentence is a claim with a shelf life, so prefer the claim
that stays true: name the owner and the invariant, cite the file, and point at
the command for the number.
