# Release History

This repository tracks the latest upstream C47 core HEAD by policy: no tracked
file pins a specific `upstream_commit` (see `AGENTS.md` and `upstream.source`).
A build of a given repo commit is therefore reproducible only against the
upstream commit that was HEAD when it was built. This log is the in-tree index
of which upstream commit each published release shipped, so a release stays
traceable from the tracked tree.

The authoritative per-release record lives in the release assets, and this log
is backfilled from them:

- the release asset names encode both short commits
  (`r47zen-<upstreamShort>-<overlayShort>-release...`),
- the GitHub release notes carry the `Sync to commit <short>` line,
- the release asset `BUILD-METADATA.txt` (inside the packaging-evidence zip)
  carries the full `upstream_commit=`, `upstream_url=`, and
  `android_source_commit=`, and
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
- `upstream_commit` -- the C47 core commit the release was built against, as the
  short SHA carried in the release asset names and notes; the full SHA is in that
  release's `BUILD-METADATA.txt`.
- `overlay_commit` -- this repository's commit the release was built from (full
  SHA where it resolves in the tracked history, else the short SHA).

## Releases

| release_tag | version (code) | date (UTC) | upstream_commit | overlay_commit |
| --- | --- | --- | --- | --- |
| r47zen-v0.1.0-signed.20260525 | 0.1.0 (20260525) | 2026-05-25 | 294d2805 | 3f7a304fc6dcf6be0c26686bfdd0bb4d73e4a633 |
| r47zen-v0.1.0-signed.20260526 | 0.1.0 (20260526) | 2026-05-26 | 294d2805 | 3f7a304fc6dcf6be0c26686bfdd0bb4d73e4a633 |
| r47zen-v0.1.1-signed.20260527 | 0.1.1 (20260527) | 2026-05-27 | c23d75a6 | 731553df28b2d6819460e665dcd7e51f0140ccf1 |
| r47zen-v0.1.2-signed.20260527 | 0.1.2 (20260527) | 2026-05-27 | c23d75a6 | f3e97a6be3b4d1a332aad942bf510df8227674e9 |
| r47zen-v0.1.3-signed.20260527 | 0.1.3 (20260527) | 2026-05-27 | c23d75a6 | 2d795afb4859ac1d25b67a2e6f9925060274e5e1 |
| r47zen-v0.1.3-signed.20260530 | 0.1.3 (20260530) | 2026-05-30 | fa3933cc | 2d795afb4859ac1d25b67a2e6f9925060274e5e1 |
| r47zen-v0.1.4-signed.20260602 | 0.1.4 (20260602) | 2026-06-02 | f2e2b3f4 | d815c8ec3ff08c395dab1d06b42173a0d92b5899 |
| r47zen-v0.1.4-signed.20260603 | 0.1.4 (20260603) | 2026-06-03 | 05e8f906 | 93edaf3a25c0dfbd425b5a4ba7916082f99046b8 |
| r47zen-v0.1.4-signed.2026060303 | 0.1.4 (2026060303) | 2026-06-03 | 05e8f906 | 93edaf3a25c0dfbd425b5a4ba7916082f99046b8 |
| r47zen-v0.1.4-signed.2026060501 | 0.1.4 (2026060501) | 2026-06-04 | 0a676130 | fd02c6a53ee78641314e44a6f85dc74e62eddd46 |
| r47zen-v0.1.5-signed.2026060502 | 0.1.5 (2026060502) | 2026-06-05 | 0a676130 | bbd16626a874b193cac354849f01b2fc35c3b268 |
| r47zen-v0.1.5-signed.2026060601 | 0.1.5 (2026060601) | 2026-06-06 | 0a676130 | c8a07ab74eeff3c76f68f621eac0eaeffe6e265c |
| r47zen-v0.1.5-signed.2026060602 | 0.1.5 (2026060602) | 2026-06-06 | 0a676130 | c8a07ab74eeff3c76f68f621eac0eaeffe6e265c |
| r47zen-v0.1.5-signed.2026060603 | 0.1.5 (2026060603) | 2026-06-06 | 5b925867 | 9fc325490ee9c1a8dd855ab1ffec2a03e17f3b90 |
| r47zen-v0.1.5-signed.2026060604 | 0.1.5 (2026060604) | 2026-06-06 | 5b925867 | af501ab61ce26bec22a3289b2666dd005133941a |
| r47zen-v0.1.5-signed.2026060701 | 0.1.5 (2026060701) | 2026-06-07 | beaf3187 | af501ab61ce26bec22a3289b2666dd005133941a |
| r47zen-v0.1.5-signed.2026060702 | 0.1.5 (2026060702) | 2026-06-07 | 2bed6a86 | af501ab61ce26bec22a3289b2666dd005133941a |
| r47zen-v0.1.5-signed.2026060703 | 0.1.5 (2026060703) | 2026-06-07 | 655871f8 | d796719e6b5bb5f69d0fcd7b08eb9b02fe081997 |
| r47zen-v0.1.5-signed.2026060801 | 0.1.5 (2026060801) | 2026-06-08 | cf0a993c | 8e0e30eb3a3c2eb76d4e26c8d94585fb8dac2b37 |
| r47zen-v0.1.5-signed.2026060901 | 0.1.5 (2026060901) | 2026-06-09 | 9f3474b0 | 8e0e30eb3a3c2eb76d4e26c8d94585fb8dac2b37 |
| r47zen-v0.1.6-signed.2026061001 | 0.1.6 (2026061001) | 2026-06-10 | 9f3474b0 | 7594783befab2449a15f064e0758bf2c4961ba4d |
| r47zen-version-0.1.6-signed.2026061101 | ersion-0.1.6 (2026061101) | 2026-06-11 | 89077c2b | 7594783befab2449a15f064e0758bf2c4961ba4d |
| r47zen-v0.1.6-signed.2026061201 | 0.1.6 (2026061201) | 2026-06-12 | faf7c3ce | 7594783befab2449a15f064e0758bf2c4961ba4d |
| r47zen-v0.1.7-signed.2026061401 | 0.1.7 (2026061401) | 2026-06-14 | faf7c3ce | b8174db5cc32acabfc45e8da9ff91986591130a9 |
| r47zen-v0.1.7-signed.2026061402 | 0.1.7 (2026061402) | 2026-06-14 | a05fc513 | b8174db5cc32acabfc45e8da9ff91986591130a9 |
| r47zen-v0.1.7-signed.2026061403 | 0.1.7 (2026061403) | 2026-06-14 | 8ef3099e | b8174db5cc32acabfc45e8da9ff91986591130a9 |
| r47zen-v0.1.7-signed.2026061701 | 0.1.7 (2026061701) | 2026-06-17 | c9db2714 | 1a6885f846eb76fd21f0853477bcb47c8300cf6a |
| r47zen-v0.1.7-signed.2026061702 | 0.1.7 (2026061702) | 2026-06-17 | c9db2714 | 276eecc53e6ac60a5a0763df2ecee7896a5304a9 |
| r47zen-v0.1.7-signed.2026061901 | 0.1.7 (2026061901) | 2026-06-19 | 9e430a8e | 82a876fdfe85bd36fcdcbce8ef304128c3212659 |
| r47zen-v0.1.7-signed.2026061902 | 0.1.7 (2026061902) | 2026-06-19 | e8633cb3 | 82a876fdfe85bd36fcdcbce8ef304128c3212659 |
| r47zen-v0.1.7-signed.2026062201 | 0.1.7 (2026062201) | 2026-06-22 | 8b10171b | 41b77785045b1bbf70350a47c17205ade0462151 |
| r47zen-v0.1.7-signed.2026062401 | 0.1.7 (2026062401) | 2026-06-24 | d9c0c88a | 41b77785045b1bbf70350a47c17205ade0462151 |
| r47zen-v0.1.7-signed.2026062801 | 0.1.7 (2026062801) | 2026-06-28 | 0688e9cf | 41b77785045b1bbf70350a47c17205ade0462151 |
| r47zen-v0.1.7-signed.2026062901 | 0.1.7 (2026062901) | 2026-06-29 | 59c1a22f | 41b77785045b1bbf70350a47c17205ade0462151 |
| r47zen-v0.1.7-signed.2026063001 | 0.1.7 (2026063001) | 2026-06-30 | 4b7cf0e7 | c4dd1bbde7dc7941aedbe48dd84b4528b0331f5b |
| r47zen-v0.1.7-signed.2026063002 | 0.1.7 (2026063002) | 2026-06-30 | 9543cbd6 | c4dd1bbde7dc7941aedbe48dd84b4528b0331f5b |
| r47zen-v0.1.7-signed.2026070301 | 0.1.7 (2026070301) | 2026-07-03 | f553b07f | e950cf0ee998f70af82efa0a85eeb5deb64fb30a |
| r47zen-v0.1.7-signed.2026070601 | 0.1.7 (2026070601) | 2026-07-06 | c35ac066 | 2afed9486c704a398b55acb07226a49ed8a8644e |
| r47zen-v0.1.8-signed.2026070801 | 0.1.8 (2026070801) | 2026-07-08 | b1192edb | dee09b309c53d0a5f86af620556f1abedbe72fe0 |
| r47zen-v0.1.8-signed.2026070901 | 0.1.8 (2026070901) | 2026-07-09 | 0caee2ad | 725f05d401c7f7ef0f56fee5db93f0b62fb41839 |
