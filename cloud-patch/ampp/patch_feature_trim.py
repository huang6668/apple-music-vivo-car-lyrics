#!/usr/bin/env python3
"""Install only AM++'s TitleCorrectionFeature.

Rewrites just the feature-list literal inside productionFeatureInstallationModule and keeps
the rest of the original body (host factory, typeface binding, runtime lambdas) verbatim,
so the patch survives AM++ renaming those collaborators between releases.
"""
import re
import sys
from pathlib import Path


HOOK = "Ldev/amenhancer/module/hook/"
PLAN = HOOK + "FeatureInstallationPlan;"

METHOD = re.compile(
    r"(?ms)^\.method private static final productionFeatureInstallationModule"
    r"\(L[^;]+;\)" + re.escape(HOOK) + r"FeatureInstallationModule;\n.*?^\.end method$"
)

# From the array-size constant through the listOf() call that turns the array into a List.
FEATURE_LIST = re.compile(
    r"(?ms)^\s*const(?:/4|/16)? v1, 0x[0-9a-f]+\n(?:\s*\.line \d+\n)?"
    r"\s*new-array v1, v1, \[" + re.escape(PLAN) + r"\n.*?"
    r"^\s*invoke-static \{v1\}, Lkotlin/collections/CollectionsKt;->listOf"
    r"\(\[Ljava/lang/Object;\)Ljava/util/List;\n"
)

SINGLE_FEATURE_LIST = f"""
    const/4 v1, 0x1

    new-array v1, v1, [{PLAN}

    new-instance v2, {PLAN}

    new-instance v3, {HOOK}TitleCorrectionFeature;

    invoke-direct {{v3}}, {HOOK}TitleCorrectionFeature;-><init>()V

    check-cast v3, {HOOK}FeatureHook;

    const/4 v4, 0x0

    const/4 v5, 0x2

    invoke-direct {{v2, v3, v4, v5, v4}}, {PLAN[:-1]};-><init>({HOOK}FeatureHook;Lkotlin/jvm/functions/Function1;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

    const/4 v3, 0x0

    aput-object v2, v1, v3

    invoke-static {{v1}}, Lkotlin/collections/CollectionsKt;->listOf([Ljava/lang/Object;)Ljava/util/List;
"""


def trim(method: str) -> str:
    if "TitleCorrectionFeature" not in method:
        raise SystemExit("productionFeatureInstallationModule no longer installs TitleCorrectionFeature")
    lists = FEATURE_LIST.findall(method)
    if len(lists) != 1:
        raise SystemExit(f"Expected one feature list in productionFeatureInstallationModule, found {len(lists)}")
    return FEATURE_LIST.sub(lambda _: SINGLE_FEATURE_LIST, method)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    methods = METHOD.findall(text)
    if len(methods) != 1:
        raise SystemExit(f"Expected one productionFeatureInstallationModule, found {len(methods)}")
    path.write_text(METHOD.sub(lambda m: trim(m.group(0)), text), encoding="utf-8")
    print("Trimmed AM++ features to TitleCorrectionFeature:", path)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_feature_trim.py <apktool-directory>")
    apktool_dir = Path(sys.argv[1])
    matches = sorted(apktool_dir.glob("smali*/dev/amenhancer/module/hook/FeatureInstallationKt.smali"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one FeatureInstallationKt.smali, found {len(matches)}")
    patch_file(matches[0])


if __name__ == "__main__":
    main()
