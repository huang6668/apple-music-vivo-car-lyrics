"""Patch only AM++'s HLE native directory read, preserving its other feature code."""
import pathlib
import re
import sys


def patch(source):
    pattern = re.compile(
        r"(?m)^(\s*)iget-object ([vp]\d+), ([vp]\d+), "
        r"Landroid/content/pm/ApplicationInfo;->nativeLibraryDir:Ljava/lang/String;$"
    )
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError("Expected one HleMetadataRuntime nativeLibraryDir read, got %d" % len(matches))
    return pattern.sub(
        lambda m: m[1] + "invoke-static {" + m[3] + "}, "
        "Ldev/amenhancer/compat/ModuleNativeLibrary;->resolve(Landroid/content/pm/ApplicationInfo;)Ljava/lang/String;"
        + "\n" + m[1] + "move-result-object " + m[2],
        source,
    )


if __name__ == "__main__":
    root = pathlib.Path(sys.argv[1])
    files = list(root.glob("smali*/dev/amenhancer/module/hook/HleMetadataRuntime.smali"))
    if len(files) != 1:
        raise SystemExit("Expected exactly one HleMetadataRuntime.smali")
    files[0].write_text(patch(files[0].read_text()))
    print("Patched AM++ HLE native directory lookup:", files[0])
