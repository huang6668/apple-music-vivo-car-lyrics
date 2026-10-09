#!/usr/bin/env python3
"""Force titleCorrectionEnabled to true in ModuleSettings.

The default value of titleCorrectionEnabled is false, and the settings UI that
would let users toggle it is not present in the NPatch-embedded build. Rewrite
getTitleCorrectionEnabled() to return true unconditionally so the feature is
always active without needing any user interaction.
"""
import re
import sys
from pathlib import Path


TARGET = "Ldev/amenhancer/module/model/ModuleSettings;"

METHOD = re.compile(
    r"(?ms)^\.method public final getTitleCorrectionEnabled\(\)Z\n"
    r".*?"
    r"^\.end method$"
)

FORCED_TRUE = (
    ".method public final getTitleCorrectionEnabled()Z\n"
    "    .locals 1\n\n"
    "    const/4 v0, 0x1\n\n"
    "    return v0\n"
    ".end method"
)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    methods = METHOD.findall(text)
    if len(methods) != 1:
        raise SystemExit(
            f"Expected one getTitleCorrectionEnabled in ModuleSettings, found {len(methods)}"
        )
    path.write_text(METHOD.sub(FORCED_TRUE, text), encoding="utf-8")
    print("Forced titleCorrectionEnabled=true:", path)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_force_title_correction.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(apktool_dir.glob("smali*/dev/amenhancer/module/model/ModuleSettings.smali"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one ModuleSettings.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
