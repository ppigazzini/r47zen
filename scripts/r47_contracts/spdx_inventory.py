"""Check the SPDX inventory an APK or AAB ships.

The packaging evidence runs this on the THIRD-PARTY.spdx.json it unpacks, so a
release cannot ship an inventory that states a false fact the build used to
write: a Google module located on Maven Central, a purl that names no
repository, a module with no checksum, a license the build could not read, a
linked component left out, or a package nothing relates to the app.

The Google groups are android/settings.gradle's r47GoogleMavenGroups, the list
the repository filter and the generator both use: a module in one of them, or in
a subgroup, came from Google's repository, and any other from Maven Central.

Usage: python3 scripts/r47_contracts/spdx_inventory.py <spdx.json> <settings.gradle>
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Final
from urllib.parse import quote

GOOGLE_MAVEN: Final = "https://dl.google.com/dl/android/maven2"
MAVEN_CENTRAL: Final = "https://repo.maven.apache.org/maven2"
# The one module whose POM declares no license: Guava's empty placeholder.
LICENSE_UNREAD_ALLOWED: Final = frozenset({"com.google.guava:listenablefuture"})
# What the app links outside Maven; each must be in the inventory.
LINKED_COMPONENTS: Final = (
    "r47-upstream-core",
    "decNumberICU",
    "PCG Random Number Generation for C",
    "mini-gmp fallback",
)
_FILE_EXTENSIONS: Final = ("aar", "jar", "module", "pom")
_GROUP_LIST: Final = re.compile(r"def r47GoogleMavenGroups = \[([^\]]*)\]")
_PURL: Final = re.compile(
    r"pkg:maven/(?P<group>[^/]+)/(?P<module>[^@]+)@(?P<version>[^?]+)"
    r"(?:\?repository_url=(?P<repository>.+))?$",
)
_SHA256: Final = re.compile(r"[0-9a-f]{64}")
_ARGUMENT_COUNT: Final = 3


def google_groups(settings_text: str) -> tuple[str, ...]:
    """Return settings.gradle's r47GoogleMavenGroups."""
    match = _GROUP_LIST.search(settings_text)
    if match is None:
        return ()
    return tuple(re.findall(r"'([^']+)'", match.group(1)))


def repository_for(group: str, groups: tuple[str, ...]) -> str:
    """Return the repository a module of GROUP resolves from."""
    if any(group == name or group.startswith(f"{name}.") for name in groups):
        return GOOGLE_MAVEN
    return MAVEN_CENTRAL


def _purl(package: dict) -> str | None:
    for reference in package.get("externalRefs", []):
        if reference.get("referenceType") == "purl":
            return reference.get("referenceLocator")
    return None


def _maven_problems(
    package: dict,
    purl: str,
    groups: tuple[str, ...],
) -> list[str]:
    name = package.get("name", "?")
    match = _PURL.match(purl)
    if match is None:
        return [f"{name}: purl {purl} is not pkg:maven/<group>/<module>@<version>"]
    group, module, version = match["group"], match["module"], match["version"]
    repository = repository_for(group, groups)
    problems = []
    expected = (
        f"{repository}/{group.replace('.', '/')}/{module}/{version}/{module}-{version}"
    )
    location = package.get("downloadLocation", "")
    if not any(location == f"{expected}.{ext}" for ext in _FILE_EXTENSIONS):
        problems.append(
            f"{name}: downloadLocation {location} is not a file under {expected}",
        )
    if match["repository"] != quote(repository.removeprefix("https://"), safe=""):
        problems.append(f"{name}: purl {purl} does not name {repository}")
    if not any(
        checksum.get("algorithm") == "SHA256"
        and _SHA256.fullmatch(checksum.get("checksumValue", ""))
        for checksum in package.get("checksums", [])
    ):
        problems.append(f"{name}: no SHA256 checksum")
    if (
        package.get("licenseDeclared") == "NOASSERTION"
        and name not in LICENSE_UNREAD_ALLOWED
    ):
        problems.append(f"{name}: licenseDeclared is NOASSERTION")
    return problems


def problems(document: dict, groups: tuple[str, ...]) -> list[str]:
    """Return what is wrong with DOCUMENT, or an empty list."""
    if not groups:
        return ["settings.gradle names no r47GoogleMavenGroups"]
    found = []
    if document.get("spdxVersion") != "SPDX-2.3":
        found.append(f"spdxVersion is {document.get('spdxVersion')}, not SPDX-2.3")
    packages = document.get("packages", [])
    relationships = document.get("relationships", [])
    known = {package.get("SPDXID") for package in packages} | {"SPDXRef-DOCUMENT"}
    found.extend(
        f"a relationship names {relationship.get(end)}, which is no package"
        for relationship in relationships
        for end in ("spdxElementId", "relatedSpdxElement")
        if relationship.get(end) not in known
    )
    described = [
        r.get("relatedSpdxElement")
        for r in relationships
        if r.get("spdxElementId") == "SPDXRef-DOCUMENT"
        and r.get("relationshipType") == "DESCRIBES"
    ]
    if len(described) != 1:
        found.append(f"the document DESCRIBES {len(described)} packages, not one")
    related = {
        r.get(end)
        for r in relationships
        for end in ("spdxElementId", "relatedSpdxElement")
    }
    names = {package.get("name") for package in packages}
    found.extend(
        f"{name} is missing: the app links it"
        for name in LINKED_COMPONENTS
        if name not in names
    )
    for package in packages:
        name = package.get("name", "?")
        if package.get("SPDXID") not in related:
            found.append(f"{name}: no relationship ties it to the app")
        purl = _purl(package)
        if purl is not None and purl.startswith("pkg:maven/"):
            found.extend(_maven_problems(package, purl, groups))
    return found


def main(argv: list[str]) -> int:
    """Check the inventory argv[1] against the Google groups of argv[2]."""
    if len(argv) != _ARGUMENT_COUNT:
        sys.stderr.write(__doc__ or "")
        return 2
    document = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    groups = google_groups(Path(argv[2]).read_text(encoding="utf-8"))
    found = problems(document, groups)
    for problem in found:
        sys.stderr.write(f"SPDX inventory: {problem}\n")
    if found:
        return 1
    maven = sum(
        1 for p in document["packages"] if (_purl(p) or "").startswith("pkg:maven/")
    )
    sys.stdout.write(
        f"SPDX inventory: {len(document['packages'])} packages, {maven} from Maven,"
        " each located, checked, and related.\n",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
