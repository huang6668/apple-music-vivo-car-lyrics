#!/usr/bin/env python3
import re
import sys
from pathlib import Path


FIELD = "lockedIsrcFallbackPending:Ljava/util/List;"
PATTERN = re.compile(
    r"new-instance p1, Ljava/util/ArrayList;\n\n"
    r"invoke-direct \{p1\}, Ljava/util/ArrayList;-><init>\(\)V\n\n"
    r"check-cast p1, Ljava/util/List;\n\n"
    r"iput-object p1, p0, Lio/github/proify/lyricon/amprovider/xposed/"
    r"AppleInternalCatalogResolver;->" + re.escape(FIELD)
)

REPLACEMENT = (
    "new-instance p1, Ljava/util/concurrent/CopyOnWriteArrayList;\n\n"
    "invoke-direct {p1}, Ljava/util/concurrent/CopyOnWriteArrayList;-><init>()V\n\n"
    "check-cast p1, Ljava/util/List;\n\n"
    "iput-object p1, p0, Lio/github/proify/lyricon/amprovider/xposed/"
    "AppleInternalCatalogResolver;->" + FIELD
)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(PATTERN) != 1:
        raise SystemExit("Expected exactly one locked ISRC pending list initializer")
    path.write_text(PATTERN.sub(REPLACEMENT, text), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_catalog_concurrency.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(apktool_dir.glob(
        "smali*/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.smali"
    ))
    if len(matches) != 1:
        raise SystemExit(f"Expected one AppleInternalCatalogResolver.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
