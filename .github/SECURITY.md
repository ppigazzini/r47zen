# Security Policy

## Scope

This repository is the Android shell, build pipeline, and maintainer overlay for
the R47 calculator variant. The shared calculator core is upstream C47, tracked
from `https://gitlab.com/rpncalculators/c43.git` (see `upstream.source`).

- Report vulnerabilities in the Android app, its JNI bridge, HAL, packaging,
  release signing, or CI here.
- Report vulnerabilities in the shared C47 core to the upstream project; they
  reach this app on the next upstream sync.

## Supported versions

Only the latest published release is supported. The repository tracks the latest
upstream HEAD, so fixes ship in a new signed release rather than as backports to
older tags.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository
(the repository's **Security** tab -> **Report a vulnerability**). This opens a
private advisory visible only to the maintainer.

Please include the affected component, the app or upstream commit you observed
it on, reproduction steps, and the impact. Do not open a public issue for a
suspected vulnerability.
