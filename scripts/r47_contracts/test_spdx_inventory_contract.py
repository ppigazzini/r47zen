"""Hold the SPDX inventory check the packaging evidence runs.

spdx_inventory.py must pass the fixture, which is cut from the inventory the
generator writes, and fail each seeded false fact: a Google module located on
Maven Central, a purl with no repository, a missing checksum, an unread license,
PCG left out, a package nothing relates, a relationship to no package, and a
second DESCRIBES. The generator and the checker must name the same repositories,
settings.gradle's two filters the same groups, PCG's notice the header upstream
ships, and check_compliance_assets.sh must run the check.
"""

from __future__ import annotations

import copy
import json
import re
import unittest
from typing import Final

from r47_contracts._repo_paths import REPO_ROOT
from r47_contracts.spdx_inventory import (
    GOOGLE_MAVEN,
    MAVEN_CENTRAL,
    google_groups,
    problems,
    repository_for,
)

_FIXTURE: Final = (
    REPO_ROOT / "scripts" / "r47_contracts" / "data" / "spdx_inventory_fixture.json"
)
_SETTINGS: Final = REPO_ROOT / "android" / "settings.gradle"
_BUILD: Final = REPO_ROOT / "android" / "app" / "build.gradle"
_CHECK: Final = REPO_ROOT / "scripts" / "android" / "check_compliance_assets.sh"
_PCG_HEADER: Final = REPO_ROOT / "src" / "c47" / "mathematics" / "pcg_basic.h"
_PCG_NOTICE: Final = (
    REPO_ROOT
    / "android"
    / "compliance"
    / "repo-notices"
    / "source-texts"
    / "pcg-basic-notice.txt"
)
_KTX: Final = "androidx.core:core-ktx"


def _groups() -> tuple[str, ...]:
    return google_groups(_SETTINGS.read_text(encoding="utf-8"))


def _fixture() -> dict:
    return json.loads(_FIXTURE.read_text(encoding="utf-8"))


def _package(document: dict, name: str) -> dict:
    return next(p for p in document["packages"] if p["name"] == name)


class SpdxInventoryContractTest(unittest.TestCase):
    """The checker passes what the generator writes and fails each seed."""

    def assert_fails(self, document: dict, fragment: str) -> None:
        """Fail unless the checker reports a problem containing FRAGMENT."""
        found = problems(document, _groups())
        if not any(fragment in problem for problem in found):
            raise AssertionError((fragment, found))

    def test_fixture_passes(self) -> None:
        """The fixture, cut from a generated inventory, has no problem."""
        found = problems(_fixture(), _groups())
        if found:
            raise AssertionError(found)

    def test_google_module_on_central_fails(self) -> None:
        """core-ktx located at Maven Central."""
        document = _fixture()
        package = _package(document, _KTX)
        package["downloadLocation"] = package["downloadLocation"].replace(
            GOOGLE_MAVEN,
            MAVEN_CENTRAL,
        )
        self.assert_fails(document, "is not a file under")

    def test_purl_without_its_repository_fails(self) -> None:
        """A purl with no repository_url names Maven Central by the purl spec."""
        document = _fixture()
        reference = _package(document, _KTX)["externalRefs"][0]
        reference["referenceLocator"] = reference["referenceLocator"].split("?")[0]
        self.assert_fails(document, "does not name")

    def test_missing_checksum_fails(self) -> None:
        """A Maven module with no SHA256 fails."""
        document = _fixture()
        del _package(document, _KTX)["checksums"]
        self.assert_fails(document, "no SHA256")

    def test_unread_license_fails_but_for_the_placeholder(self) -> None:
        """NOASSERTION fails, except on Guava's empty listenablefuture."""
        document = _fixture()
        _package(document, _KTX)["licenseDeclared"] = "NOASSERTION"
        self.assert_fails(document, "licenseDeclared is NOASSERTION")
        allowed = _fixture()
        package = _package(allowed, _KTX)
        package["licenseDeclared"] = "NOASSERTION"
        package["name"] = "com.google.guava:listenablefuture"
        reference = package["externalRefs"][0]
        reference["referenceLocator"] = reference["referenceLocator"].replace(
            "androidx.core/core-ktx",
            "com.google.guava/listenablefuture",
        )
        found = [p for p in problems(allowed, _groups()) if "licenseDeclared" in p]
        if found:
            raise AssertionError(found)

    def test_missing_pcg_fails(self) -> None:
        """PCG is linked, so an inventory without it fails."""
        document = _fixture()
        document["packages"] = [
            p
            for p in document["packages"]
            if p["name"] != "PCG Random Number Generation for C"
        ]
        document["relationships"] = [
            r
            for r in document["relationships"]
            if r["relatedSpdxElement"] != "SPDXRef-pcg-basic"
        ]
        self.assert_fails(document, "PCG Random Number Generation for C is missing")

    def test_unrelated_package_fails(self) -> None:
        """A package no relationship names fails."""
        document = _fixture()
        document["relationships"] = [
            r
            for r in document["relationships"]
            if r["spdxElementId"] != "SPDXRef-xlsxio"
        ]
        self.assert_fails(document, "xlsxio: no relationship")

    def test_relationship_to_no_package_fails(self) -> None:
        """A relationship naming an absent package fails."""
        document = _fixture()
        document["relationships"].append(
            {
                "spdxElementId": "SPDXRef-r47zen-source",
                "relationshipType": "DEPENDS_ON",
                "relatedSpdxElement": "SPDXRef-absent",
            },
        )
        self.assert_fails(document, "SPDXRef-absent, which is no package")

    def test_second_describes_fails(self) -> None:
        """The document describes the app alone."""
        document = _fixture()
        extra = copy.deepcopy(document["relationships"][0])
        extra["relatedSpdxElement"] = "SPDXRef-xlsxio"
        document["relationships"].append(extra)
        self.assert_fails(document, "DESCRIBES 2 packages")

    def test_group_rule_takes_subgroups_only(self) -> None:
        """androidx.core is Google's; com.androidx and com.google.androidx are not."""
        groups = _groups()
        cases = {
            "androidx": GOOGLE_MAVEN,
            "androidx.core": GOOGLE_MAVEN,
            "com.google.android.material": GOOGLE_MAVEN,
            "com.androidx": MAVEN_CENTRAL,
            "com.google.androidx": MAVEN_CENTRAL,
            "org.jetbrains.kotlin": MAVEN_CENTRAL,
        }
        for group, repository in cases.items():
            if repository_for(group, groups) != repository:
                raise AssertionError(group)

    def test_settings_filters_name_the_same_groups(self) -> None:
        """The plugin block repeats r47GoogleMavenGroups, which it cannot see."""
        text = _SETTINGS.read_text(encoding="utf-8")
        plugin_block = text[
            text.index("pluginManagement {") : text.index(
                "dependencyResolutionManagement {",
            )
        ]
        repeated = tuple(
            re.findall(r'includeGroupAndSubgroups\("([^"]+)"\)', plugin_block),
        )
        if not _groups() or repeated != _groups():
            raise AssertionError((repeated, _groups()))

    def test_generator_uses_the_same_groups_and_repositories(self) -> None:
        """build.gradle reads the settings list and names the checker's two URLs."""
        text = _BUILD.read_text(encoding="utf-8")
        for needle in (
            "gradle.ext.r47GoogleMavenGroups",
            f"'{GOOGLE_MAVEN}'",
            f"'{MAVEN_CENTRAL}'",
        ):
            if needle not in text:
                raise AssertionError(needle)

    def test_pcg_notice_is_the_header_upstream_ships(self) -> None:
        """The notice is pcg_basic.h's opening comment, word for word."""
        lines = _PCG_HEADER.read_text(encoding="utf-8").splitlines()[1:21]
        header = "\n".join(re.sub(r"^ \* ?|^ \*$", "", line) for line in lines) + "\n"
        if header != _PCG_NOTICE.read_text(encoding="utf-8"):
            raise AssertionError(_PCG_NOTICE)

    def test_evidence_runs_the_check(self) -> None:
        """check_compliance_assets.sh runs spdx_inventory.py on what it unpacked."""
        text = _CHECK.read_text(encoding="utf-8")
        for needle in ("r47_contracts/spdx_inventory.py", "android/settings.gradle"):
            if needle not in text:
                raise AssertionError(needle)


if __name__ == "__main__":
    unittest.main()
