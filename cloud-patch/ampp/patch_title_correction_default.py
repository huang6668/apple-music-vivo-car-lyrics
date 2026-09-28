#!/usr/bin/env python3
import sys
from pathlib import Path


METHOD = (
    ".method public final getTitleCorrectionEnabled()Z\n"
    "    .locals 0\n\n"
    "    .line 23\n"
    "    iget-boolean p0, p0, "
    "Ldev/amenhancer/module/model/ModuleSettings;->titleCorrectionEnabled:Z\n\n"
    "    return p0\n"
    ".end method"
)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(METHOD) != 1:
        raise SystemExit("Expected exactly one getTitleCorrectionEnabled method")
    patched = (
        ".method public final getTitleCorrectionEnabled()Z\n"
        "    .locals 1\n\n"
        "    const/4 p0, 0x1\n\n"
        "    return p0\n"
        ".end method"
    )
    path.write_text(text.replace(METHOD, patched), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_title_correction_default.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(apktool_dir.glob("smali*/dev/amenhancer/module/model/ModuleSettings.smali"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one ModuleSettings.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
