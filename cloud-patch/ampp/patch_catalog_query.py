"""Adapt only the catalog query method lookup, leaving r38 and other hooks untouched."""
import pathlib
import re
import sys


def patch(source):
    pattern = re.compile(
        r"(?m)^\.method private final findDirectCatalogQueryMethod"
        r"\(Ljava/lang/Class;Ljava/lang/String;\)Ljava/lang/reflect/Method;"
        r"\n.*?^\.end method$",
        re.DOTALL,
    )
    if len(pattern.findall(source)) != 1:
        raise ValueError("Expected exactly one private catalog query resolver")
    return pattern.sub(
        ".method private final findDirectCatalogQueryMethod"
        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Method;\n"
        "    .locals 1\n\n"
        "    invoke-static {p1, p2}, Ldev/amenhancer/compat/CatalogQueryMethod;"
        "->resolve(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Method;\n"
        "    move-result-object v0\n"
        "    return-object v0\n"
        ".end method",
        source,
    )


if __name__ == "__main__":
    root = pathlib.Path(sys.argv[1])
    files = list(root.glob(
        "smali*/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.smali"
    ))
    if len(files) != 1:
        raise SystemExit("Expected exactly one AppleInternalCatalogResolver.smali")
    files[0].write_text(patch(files[0].read_text()))
    print("Patched AM++ catalog query lookup:", files[0])
