# Release History

This repository tracks the latest upstream C47 core HEAD by policy: no tracked
file pins a specific `upstream_commit` (see `AGENTS.md` and `upstream.source`).
A build of a given repo commit is therefore reproducible only against the
upstream commit that was HEAD when it was built. This log is the durable,
in-tree record of which upstream commit each published release shipped, so a
release stays traceable even if the upstream host rewrites or loses history.

The same mapping is also recorded outside this file, per release, in:

- the GitHub release notes (the `Sync to commit <short>` line),
- the release asset `BUILD-METADATA.txt` (`upstream_commit=`, `upstream_url=`,
  `android_source_commit=`), and
- the release tag.

The production release workflow prints the row for each published release to the
GitHub Actions job summary (the "Release history row" step in
`android-release.yml`). Append that row here when cutting a release.

## Schema

One row per published release, newest last:

```
| release_tag | version (code) | date (UTC) | upstream_commit | overlay_commit |
```

- `release_tag` -- the published GitHub release tag.
- `version (code)` -- Android `versionName` and `versionCode`.
- `date` -- publish date, `YYYY-MM-DD`.
- `upstream_commit` -- full C47 core commit the release was built against.
- `overlay_commit` -- this repository's commit the release was built from.

## Releases

| release_tag | version (code) | date (UTC) | upstream_commit | overlay_commit |
| --- | --- | --- | --- | --- |
| _(backfill from each release's BUILD-METADATA.txt; new releases appended by the workflow row)_ | | | | |
