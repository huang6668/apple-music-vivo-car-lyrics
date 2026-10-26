#!/usr/bin/env python3
"""Pin the 1611 final HTTP seam; only resolver-owned lookups are localized."""
import argparse
import hashlib
from pathlib import Path
import re

TARGET = "y9/k.smali"
METHOD = (
    "public final a(Ljava/lang/String;Ljava/lang/String;ZLjava/util/LinkedHashMap;"
    "Ljava/util/Map;Lkk/C;Lhi/c;)Ljava/lang/Object;"
)
STOCK_SHA256 = "fb6aa2f119ac7949a776f69f585cf3efab83d456ffc97a36b5e812c8e162a9a5"
STOCK_CODE_SHA256 = "18a0f21237399ca30d5b23d74bbed2fd56359c7f7eb00f39e511601ea6a21d7b"
HOOK = (
    "    # Fixed mainland localization is restricted to resolver-owned request tokens.\n"
    "    invoke-static {p1, p4, p5}, "
    "Lcom/apple/android/music/player/CatalogTitleResolver;->correctCatalogRequest"
    "(Ljava/lang/String;Ljava/util/LinkedHashMap;Ljava/util/Map;)Ljava/lang/String;\n"
    "\n"
    "    move-result-object p1\n"
    "\n"
)
SEAM = "    .locals 2\n\n    .line 1\n    new-instance v0, LOj/l;"
PATCHED_SEAM = "    .locals 2\n\n" + HOOK + "    .line 1\n    new-instance v0, LOj/l;"


def code(text):
    return "\n".join(line.strip() for line in text.splitlines()
                     if line.strip() and not line.strip().startswith(("#", ".line "))) + "\n"


def verify_text(text, patched=False):
    assert text.startswith(".class public final Ly9/k;"), "Wrong case-sensitive HTTP class"
    blocks = re.findall(
        r"(?ms)^\.method " + re.escape(METHOD) + r"\n.*?^\.end method$", text
    )
    assert len(blocks) == 1, "Expected unique final HTTP method"
    block = blocks[0]
    marker = "CatalogTitleResolver;->correctCatalogRequest"
    assert text.count(marker) == (1 if patched else 0), "Unexpected region hook count"
    if patched:
        hook_code = code(HOOK)
        canonical = code(text)
        entry = ".method " + METHOD + "\n.locals 2\n" + hook_code + "new-instance v0, LOj/l;"
        assert entry in canonical, "HTTP entry registers or seam drifted"
        # Apktool discards comments during reassembly; check all stock instructions and labels.
        stock_code = canonical.replace(hook_code, "", 1)
        assert hashlib.sha256(stock_code.encode()).hexdigest() == STOCK_CODE_SHA256, (
            "Patched HTTP method must preserve all stock code"
        )
    else:
        assert SEAM in block, "HTTP entry registers or seam drifted"
        assert hashlib.sha256(text.encode("utf-8")).hexdigest() == STOCK_SHA256, (
            "1611 HTTP source drifted; re-analyze rather than patching an unknown method"
        )


def target_path(root):
    paths = sorted(root.glob("smali*/" + TARGET))
    assert len(paths) == 1, "Expected one case-sensitive final HTTP class"
    assert paths[0].parent.parent.name == "smali_classes2", "HTTP class moved DEX"
    return paths[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    path = target_path(args.decoded)
    text = path.read_text(encoding="utf-8")
    if args.apply:
        verify_text(text)
        text = text.replace(SEAM, PATCHED_SEAM, 1)
        verify_text(text, patched=True)
        path.write_text(text, encoding="utf-8")
    else:
        verify_text(text, patched=True)
    print("Fixed mainland HTTP seam verified")


if __name__ == "__main__":
    main()
