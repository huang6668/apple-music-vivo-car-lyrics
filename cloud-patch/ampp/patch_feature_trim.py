#!/usr/bin/env python3
import re
import sys
from pathlib import Path


METHOD_SIGNATURE = re.compile(
    r"\.method private static final "
    r"productionFeatureInstallationModule"
    r"\(Ldev/amenhancer/module/hook/LyricsTypefaceSession;\)"
    r"Ldev/amenhancer/module/hook/FeatureInstallationModule;"
)

TRIMMED_METHOD = """.method private static final productionFeatureInstallationModule(Ldev/amenhancer/module/hook/LyricsTypefaceSession;)Ldev/amenhancer/module/hook/FeatureInstallationModule;
    .locals 6

    new-instance v0, Ldev/amenhancer/module/hook/FeatureInstallationModule;

    const/4 v1, 0x1

    new-array v1, v1, [Ldev/amenhancer/module/hook/FeatureInstallationPlan;

    new-instance v2, Ldev/amenhancer/module/hook/FeatureInstallationPlan;

    new-instance v3, Ldev/amenhancer/module/hook/TitleCorrectionFeature;

    invoke-direct {v3}, Ldev/amenhancer/module/hook/TitleCorrectionFeature;-><init>()V

    check-cast v3, Ldev/amenhancer/module/hook/FeatureHook;

    const/4 v4, 0x0

    const/4 v5, 0x2

    invoke-direct {v2, v3, v4, v5, v4}, Ldev/amenhancer/module/hook/FeatureInstallationPlan;-><init>(Ldev/amenhancer/module/hook/FeatureHook;Lkotlin/jvm/functions/Function1;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

    const/4 v3, 0x0

    aput-object v2, v1, v3

    invoke-static {v1}, Lkotlin/collections/CollectionsKt;->listOf([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v1

    new-instance v2, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$5;

    sget-object v3, Ldev/amenhancer/module/hook/LayoutInflationRegistry;->INSTANCE:Ldev/amenhancer/module/hook/LayoutInflationRegistry;

    invoke-direct {v2, v3}, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$5;-><init>(Ljava/lang/Object;)V

    check-cast v2, Lkotlin/jvm/functions/Function0;

    new-instance v3, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda2;

    invoke-direct {v3, p0}, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda2;-><init>(Ldev/amenhancer/module/hook/LyricsTypefaceSession;)V

    new-instance v4, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda3;

    invoke-direct {v4}, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda3;-><init>()V

    new-instance p0, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$8;

    sget-object v5, Ldev/amenhancer/module/hook/ModernXposedRuntime;->INSTANCE:Ldev/amenhancer/module/hook/ModernXposedRuntime;

    invoke-direct {p0, v5}, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$8;-><init>(Ljava/lang/Object;)V

    move-object v5, p0

    check-cast v5, Lkotlin/jvm/functions/Function2;

    invoke-direct/range {v0 .. v5}, Ldev/amenhancer/module/hook/FeatureInstallationModule;-><init>(Ljava/util/List;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function3;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;)V

    return-object v0
.end method
"""


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    match = METHOD_SIGNATURE.search(text)
    if not match:
        raise SystemExit("productionFeatureInstallationModule method not found")

    method_start = match.start()
    method_end = text.find(".end method", method_start)
    if method_end < 0:
        raise SystemExit("productionFeatureInstallationModule end not found")
    method_end += len(".end method")

    original_method = text[method_start:method_end]
    if "TitleCorrectionFeature" not in original_method:
        raise SystemExit("Unexpected productionFeatureInstallationModule layout")

    text = text[:method_start] + TRIMMED_METHOD + text[method_end:]
    path.write_text(text, encoding="utf-8")


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
