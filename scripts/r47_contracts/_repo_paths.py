"""Shared path helpers for the repo-owned R47 contract suite."""

from __future__ import annotations

from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent
DATA_ROOT = PACKAGE_ROOT / "data"
REPO_ROOT = PACKAGE_ROOT.parents[1]
ANDROID_APP_ROOT = REPO_ROOT / "android" / "app" / "src" / "main"
ANDROID_CPP_ROOT = ANDROID_APP_ROOT / "cpp" / "c47-android"
ANDROID_RES_ROOT = ANDROID_APP_ROOT / "res"
ANDROID_TEST_RESOURCES_ROOT = (
    REPO_ROOT / "android" / "app" / "src" / "test" / "resources"
)
KEYPAD_FIXTURES_ROOT = ANDROID_TEST_RESOURCES_ROOT / "keypad-fixtures"
KOTLIN_R47_ROOT = ANDROID_APP_ROOT / "java" / "com" / "example" / "r47"
STAGED_NATIVE_C47_ROOT = REPO_ROOT / "android" / ".staged-native" / "cpp" / "c47"
R47_PHYSICAL_GEOMETRY_DATA_PATH = DATA_ROOT / "r47_physical_geometry.json"
R47_ANDROID_UI_CONTRACT_PATH = DATA_ROOT / "r47_android_ui_contract.json"
