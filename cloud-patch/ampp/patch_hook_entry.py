#!/usr/bin/env python3
import re
import sys
from pathlib import Path


METHOD = re.compile(
    r"\.method public onPackageReady"
    r"\(Lio/github/libxposed/api/XposedModuleInterface\$PackageReadyParam;\)V"
)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    match = METHOD.search(text)
    if not match:
        raise SystemExit("HookEntry.onPackageReady method not found")
    method_start = match.start()
    method_end = text.find(".end method", method_start)
    if method_end < 0:
        raise SystemExit("HookEntry.onPackageReady end not found")
    method_end += len(".end method")
    original = text[method_start:method_end]

    marker = (
        "    invoke-direct {p0, p1, v0}, "
        "Ldev/amenhancer/module/hook/HookEntry;->installApplicationBootstrap"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n\n"
        "    return-void\n"
        ".end method"
    )
    replacement = (
        "    invoke-direct {p0, p1, v0}, "
        "Ldev/amenhancer/module/hook/HookEntry;->installApplicationBootstrap"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n\n"
        "    invoke-interface {p1}, "
        "Lio/github/libxposed/api/XposedModuleInterface$PackageReadyParam;->getPackageName()Ljava/lang/String;\n\n"
        "    move-result-object v1\n\n"
        "    invoke-static {v1, v0}, "
        "Lcom/vivo/musicwidgetmix/lyrics/AtomicServiceDiscoveryHook;->install"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n\n"
        "    return-void\n"
        ".end method"
    )
    if marker not in original:
        raise SystemExit("HookEntry package dispatch insertion point not found")
    patched = original.replace(marker, replacement)
    path.write_text(text[:method_start] + patched + text[method_end:], encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_hook_entry.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(apktool_dir.glob("smali*/dev/amenhancer/module/hook/HookEntry.smali"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one HookEntry.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
