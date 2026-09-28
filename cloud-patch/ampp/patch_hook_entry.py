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
    method_locals = re.search(r"^    \.locals (?P<count>\d+)$", original, re.MULTILINE)
    if not method_locals:
        raise SystemExit("HookEntry.onPackageReady locals declaration not found")
    locals_count = int(method_locals.group("count"))
    if locals_count < 4:
        original = original.replace(
            method_locals.group(0),
            "    .locals 4",
            1,
        )

    insert_at = original.find(
        "    invoke-direct {p0, p1, v0}, "
        "Ldev/amenhancer/module/hook/HookEntry;->installApplicationBootstrap"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n"
    )
    if insert_at < 0:
        raise SystemExit("HookEntry application bootstrap call not found")
    insert_at += len(
        "    invoke-direct {p0, p1, v0}, "
        "Ldev/amenhancer/module/hook/HookEntry;->installApplicationBootstrap"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n"
    )
    dispatch = (
        "\n"
        "    invoke-interface {p1}, "
        "Lio/github/libxposed/api/XposedModuleInterface$PackageReadyParam;->getPackageName()Ljava/lang/String;\n"
        "    move-result-object p1\n"
        "    invoke-static {p1, v0}, "
        "Lcom/vivo/musicwidgetmix/lyrics/ModuleDispatch;->install"
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V\n"
    )
    patched = original[:insert_at] + dispatch + original[insert_at:]
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
