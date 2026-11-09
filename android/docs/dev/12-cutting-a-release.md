# Cutting A Release

This page is the ordered procedure for publishing a signed production release
of R47 Zen, what to do when the workflow refuses, and how to re-release a past
build. It is a checklist, not a reference:
[07-ci-and-release-workflow.md](07-ci-and-release-workflow.md#production-release-workflow)
owns what each job does and why,
[10-writing.md](10-writing.md#version-numbers) owns the version rules, and
every step below links the rule it follows.

A release is one dispatch of `.github/workflows/android-release.yml` on `main`.
Nothing else publishes a production release, and every job after the two
resolve jobs runs only on `main`, except in a dry run, which publishes nothing.

## Before you dispatch

1. **The commit is on `main` and `android-ci` is green on it, with the
   upstream HEAD the release will take.** The release builds the dispatched
   ref's overlay with the newest upstream HEAD, and its `require-green-ci` job
   refuses the run unless android-ci published the `-dev` release, with its
   APK, for exactly that pair, which it does only when every required lane
   passed. When upstream moved since the last run on `main`, run android-ci on
   `main` first, or pin `upstream_commit` to a pair it passed.

   ```sh
   gh run list --workflow android-ci.yml --branch main --limit 1
   ```

2. **Read the two numbers you are about to release.**
   `android/r47-defaults.properties` holds the next release: its Play version
   code and its version.

   ```sh
   grep -E '^R47_DEFAULT_ANDROID_VERSION_(CODE|NAME)=' android/r47-defaults.properties
   ```

   The commit after the previous release set them; the workflow refuses them
   unless the code is the previous production release's plus one and the
   version is the next SemVer step
   ([rules](10-writing.md#version-numbers)). The `Check the next release
   numbers` step of `android-ci`'s `upstream-release-gate` job runs the same
   checks on every run and warns when they fail. If app code changed since the
   previous release and the file still names a PATCH step, commit the MINOR
   step first.

3. **Choose the Play track.** `none`, the default, publishes the GitHub
   release alone; `internal` is the internal testing track, `alpha` the
   closed testing track, and `production` the production track
   ([`upload-to-play`](07-ci-and-release-workflow.md#upload-to-play)).

## Dry run

A dry run of the same dispatch, on any branch, builds, signs with a key it makes,
verifies, and publishes nothing; run it after a change to the release lane, before
the release that needs the change:

```sh
gh workflow run android-release.yml --ref <branch> \
  -f confirm_version_code=<R47_DEFAULT_ANDROID_VERSION_CODE> \
  -f confirm_version=<R47_DEFAULT_ANDROID_VERSION_NAME> \
  -f dry_run=true
```

It refuses any `play_track` but `none`, so leave that input out. It does not touch
the release key, either environment, the attestation, the GitHub release, or Play
([dry run](07-ci-and-release-workflow.md#dry-run)), so those still first run at the
release.

## Dispatch

Type the two numbers from step 2 as confirmations:

```sh
gh workflow run android-release.yml --ref main \
  -f confirm_version_code=<R47_DEFAULT_ANDROID_VERSION_CODE> \
  -f confirm_version=<R47_DEFAULT_ANDROID_VERSION_NAME> \
  -f play_track=alpha
gh run watch "$(gh run list --workflow android-release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

`play_release_notes` sets the Play notes (500 characters at most); left blank,
they name the version and the calculator core. In the GitHub UI: Actions ->
Android Release -> Run workflow, with the same inputs.

The jobs run in this order; a job starts when every job above it that it
needs has passed:

```mermaid
flowchart TD
  A[resolve-release-inputs]
  B[resolve-upstream-core]
  R[require-green-ci]
  C[build-production-release-bundle]
  D[verify-production-release]
  E[sign-production-release]
  F[verify-signed-artifacts]
  G[publish-production-release]
  H[upload-to-play<br/>skipped with play_track none]
  S[package-core-source]

  A --> R
  B --> R
  R --> C
  A --> C
  B --> C
  A --> D
  B --> D
  C --> D
  C --> E --> F --> G
  D --> E
  A --> S
  B --> S
  S --> G
  D --> G
  G --> H
```

`verify-production-release` runs the emulator lane with the build job's PGO
profile and launches an `x86_64` build in the shipped shape, and nothing is
published until it passes.

## After it finishes

1. Open the release page and check the notes name the signing certificate. If
   they carry a "Reinstall required" notice, the signer changed: that is
   expected only after a deliberate key rotation.
2. Check one downloaded APK the way the README tells users to
   (`apksigner verify --print-certs`), against the value in
   `android/r47-defaults.properties`.
3. With `internal` or `alpha`, the release is on that track; promote it in
   Play Console when it should reach production. With `production`, Google
   reviews it, and it reaches every user once approved.
4. **Open the next release.** Commit the next code (this one plus one) and
   the next PATCH step in `android/r47-defaults.properties`; the run summary
   prints both lines:

   ```sh
   git commit -m "chore(release): open 0.1.14 (11)" android/r47-defaults.properties
   ```

   Until this commit lands, dev pre-releases read the released version as
   their base and sort before it, and the next release run is refused.

## When the workflow refuses

`resolve-release-inputs` checks the inputs before anything builds, so most
refusals cost a minute. Read the failed step's log; each message names its
cause.

| message | what it means | do this |
|---|---|---|
| `android/r47-defaults.properties says R47_DEFAULT_ANDROID_VERSION_CODE=<x>, confirm_version_code says <y>.` (or `..._VERSION_NAME=...`, `confirm_version`) | the typed confirmation is not the file | dispatch again with the file's value, or fix the file first |
| `R47_DEFAULT_ANDROID_VERSION_CODE must be <n> (previous production release used <m>), got <x>.` | the file's code is not the previous plus one, usually a forgotten "open the next release" commit | commit `<n>` and its version, then dispatch |
| `R47_DEFAULT_ANDROID_VERSION_CODE <x> is above 100000; ...` | a date was written as the code | commit the counter |
| `No -dev release r47zen-<u8>-<a8>-dev with an APK: android-ci has not passed on ...` (in `require-green-ci`) | upstream moved since android-ci last passed on `main`, or android-ci failed on this pair | `gh workflow run android-ci.yml --ref main`, then dispatch once it publishes; or dispatch with `upstream_commit` set to a pair it passed |
| `... the release takes the PATCH step, <v>, not <x>.` | app code is unchanged since the previous release, and the file names another version | commit `<v>` |
| `App code (android/app/src/main/) changed since <prev> ... The release takes the MINOR step, PATCH reset: <v> ...` | app code changed, and the file names a PATCH step | commit `<v>` ([rules](10-writing.md#version-numbers)) |
| `Commit <sha> of the previous release <prev> is on no ref this checkout fetched, so a rerun repeats this: ...` | the previous release's `android_source_commit` is on no branch or tag, as a re-cut followed by a rewrite of `main` leaves it; the job already checks out with `fetch-depth: 0` | push a tag or branch at that commit, from a clone that still has it (`git push origin <sha>:refs/tags/<name>`), then dispatch again |
| `Release tag <tag> already exists; refusing to overwrite a published release.` | that version was released | commit the next version; set `allow_overwrite=true` only to re-cut that release on purpose |
| `allow_overwrite=true re-cuts version code <x>, which Play refuses to take twice; ...` | a re-cut keeps the code, and Play takes a code once | re-cut with `play_track=none`, or release the next code |
| `allow_overwrite=true re-cuts <tag>, so the version must stay <v>, not <x>.` | a re-cut republishes the previous release under its own tag, which stays on the first cut's commit while the release records the new one | set the file back to that release's numbers, or release the next ones |
| `Could not determine whether <tag> exists (git ls-remote exit <n>); ...` | the tag check failed on the network or auth | rerun the failed job |
| `Expected one APK evidence archive on <tag>, found 0.`, `The APK evidence of <tag> records no signing certificate ...`, or `... records no android_source_commit ...` | the previous release lacks the evidence the reinstall notice and the version check read | restore the previous release's APK packaging-evidence asset; the workflow will not publish without it |
| `Expected one APK evidence archive on <tag>, found 2.` | a re-cut whose overlay or core moved published beside the first cut, since every file is named after both commits; a re-cut removes the first cut's four files after its upload, so this comes from a re-cut that did not finish that removal | delete the four files of the cut that is not the release (`gh release delete-asset <tag> <name>`), then dispatch |
| `Missing required production release signing secret for <name>.` | an `R47_RELEASE_*` secret is missing from the `production-release` environment | see [Repository settings](07-ci-and-release-workflow.md#repository-settings) |
| `publish-production-release` fails | the release action, `softprops/action-gh-release` at the version `android-release.yml` pins, creates a non-prerelease as a draft and publishes it only after every upload, so a first publish that failed leaves a draft and no tag; a re-cut, which overwrites, deletes each asset before uploading its new one, so a failed re-cut can leave the published release short of an asset | `gh run rerun <run id> --failed`, which reuses the signed artifacts within their 30-day retention. Before any fresh dispatch instead, delete the leftover draft (`gh release delete r47zen-v<version>`), or the new run uploads beside its assets |
| `upload-to-play` fails with "The service is currently unavailable" | a transient Play 5xx after the GitHub release is published | `gh run rerun <run id> --failed`, which reruns that job alone on the same signed AAB within its 30-day retention |
| `upload-to-play` fails with "Precondition check failed" | an edit was open in Play Console | close it, then rerun the failed job alone; the GitHub release is already published ([limits](07-ci-and-release-workflow.md#upload-to-play)) |
| `upload-to-play` fails on `production` with a permission error | the Play upload account lacks the production release permission | grant it in Play Console ([`upload-to-play`](07-ci-and-release-workflow.md#upload-to-play)), then rerun the failed job alone |
| Play answers that the track does not exist | the track name | the first value to check is `internal` versus `qa` ([limits](07-ci-and-release-workflow.md#upload-to-play)) |

A failure in a build, sign, or verify job is a regression, not a refusal:
nothing was published, so fix it on `main`, wait for `android-ci` to pass, and
dispatch again with the same numbers.

## Re-releasing a past build

Every published build records the upstream core revision it was built from in
`BUILD-METADATA.txt` (`upstream_commit=<sha>`), inside its packaging-evidence
archive and linked from the release notes. The `upstream_commit` dispatch
input feeds that commit back
([`build-production-release-bundle`](07-ci-and-release-workflow.md#build-production-release-bundle)).

1. **Find the upstream commit.** Read the linked upstream commit in the
   release notes, or `upstream_commit=` from `BUILD-METADATA.txt` in the
   release's packaging-evidence archive. Note `android_source_commit=` too if
   you need the overlay (see the limit below).
2. **Re-release the signed production build** with that full 40-character
   commit, as the next release in `android/r47-defaults.properties`:

   ```sh
   gh workflow run android-release.yml --ref main \
     -f upstream_commit=<recorded-sha> \
     -f confirm_version_code=<R47_DEFAULT_ANDROID_VERSION_CODE> \
     -f confirm_version=<R47_DEFAULT_ANDROID_VERSION_NAME>
   ```

   `resolve-upstream-core` then resolves `--locked --commit <sha>`, and the new
   release records the same `upstream_commit` in its own `BUILD-METADATA.txt`.
3. **Reproduce a dev prerelease instead** by dispatching
   `.github/workflows/android-ci.yml` with `-f upstream_commit=<recorded-sha>`.
   A manual dispatch always builds, even when a release for the derived tag
   exists.
4. **Verify** that the new `BUILD-METADATA.txt` reports
   `upstream_commit=<recorded-sha>`. A blank `upstream_commit` resolves the
   newest HEAD, the normal path.

The limit: `upstream_commit` pins only the upstream core. The overlay is the
dispatched ref, and the release jobs run only on `main`, so a re-release
combines the pinned core with the current `main` overlay. A bit-exact rebuild
of an old artifact also needs the overlay at its recorded
`android_source_commit`. The packaged `THIRD-PARTY.spdx.json` takes its
creation time from the commit, or from `SOURCE_DATE_EPOCH` when set, never
from the clock, so it is not what makes two builds of one commit differ.

The local counterpart is the git-ignored `upstream.lock`
(`scripts/upstream-sync/upstream.sh resolve --latest --write-lock`, then trim
it to the commit you want), which pins a developer's own builds without
touching CI.
