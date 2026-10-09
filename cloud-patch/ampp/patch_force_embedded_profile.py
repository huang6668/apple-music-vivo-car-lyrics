#!/usr/bin/env python3
"""Allow AM++'s embedded bootstrap to initialize for Apple Music beta 1607.

AM++ 1.6.4 only initializes its embedded configuration on host builds listed in
its production-profile table. Apple Music 7.0.0-beta (1607) is not in that
table, despite the module's catalog-query code supporting this host. The outer
HookEntry still limits execution to Apple Music's main process; this patch only
removes the stale version-table gate.
"""
import re
import sys
from pathlib import Path


METHOD = re.compile(
    r"(?ms)^\.method public final supports\("
    r"Ldev/amenhancer/module/hook/TargetBuild;\)Z\n.*?^\.end method$"
)

SUPPORTED = (
    ".method public final supports(Ldev/amenhancer/module/hook/TargetBuild;)Z\n"
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
            f"Expected one EmbeddedBootstrap.supports method, found {len(methods)}"
        )
    path.write_text(METHOD.sub(SUPPORTED, text), encoding="utf-8")
    print("Enabled AM++ embedded bootstrap for Apple Music beta 1607:", path)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_force_embedded_profile.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(
        apktool_dir.glob("smali*/dev/amenhancer/module/hook/EmbeddedBootstrap.smali")
    )
    if len(matches) != 1:
        raise SystemExit(f"Expected one EmbeddedBootstrap.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
