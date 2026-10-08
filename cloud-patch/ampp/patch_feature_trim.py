#!/usr/bin/env python3
import re
import sys
from pathlib import Path


# Match the method declaration regardless of modifier combination or parameter list.
# v1.6.2: private static final, takes LyricsTypefaceSession.
# v1.6.4: modifiers and/or parameter type may differ.
METHOD_SIGNATURE = re.compile(
    r"(\.method [\w -]+productionFeatureInstallationModule"
    r"\([^)]*\)"
    r"Ldev/amenhancer/module/hook/FeatureInstallationModule;)"
)

# v1.6.2: static, takes LyricsTypefaceSession as p0 (or p1 if instance).
# v1.6.4: parameter dropped; session-dependent lambda ($2) is gone, so we pass null
#         for the Function3 slot it occupied in FeatureInstallationModule.<init>.
def _trimmed_method(decl_line: str) -> str:
    has_session = "LyricsTypefaceSession;" in decl_line
    if has_session:
        session_reg = "p0" if " static " in decl_line else "p1"
        lambda2_block = """\
    new-instance v3, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda2;

    invoke-direct {{v3, {session}}}, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda2;-><init>(Ldev/amenhancer/module/hook/LyricsTypefaceSession;)V
""".format(session=session_reg)
        # In the session variant p0/p1 is reused for the $8 lambda after the lambda2 block.
        lambda8_reg = session_reg
    else:
        # No session parameter: allocate a fresh register for the $8 lambda and pass
        # null for the Function3 argument (the session-bound callback is unused when
        # title correction is the only active feature).
        lambda2_block = """\
    const/4 v3, 0x0
"""
        lambda8_reg = "v3"

    return """{decl}
    .locals 6

    new-instance v0, Ldev/amenhancer/module/hook/FeatureInstallationModule;

    const/4 v1, 0x1

    new-array v1, v1, [Ldev/amenhancer/module/hook/FeatureInstallationPlan;

    new-instance v2, Ldev/amenhancer/module/hook/FeatureInstallationPlan;

    new-instance v3, Ldev/amenhancer/module/hook/TitleCorrectionFeature;

    invoke-direct {{v3}}, Ldev/amenhancer/module/hook/TitleCorrectionFeature;-><init>()V

    check-cast v3, Ldev/amenhancer/module/hook/FeatureHook;

    const/4 v4, 0x0

    const/4 v5, 0x2

    invoke-direct {{v2, v3, v4, v5, v4}}, Ldev/amenhancer/module/hook/FeatureInstallationPlan;-><init>(Ldev/amenhancer/module/hook/FeatureHook;Lkotlin/jvm/functions/Function1;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

    const/4 v3, 0x0

    aput-object v2, v1, v3

    invoke-static {{v1}}, Lkotlin/collections/CollectionsKt;->listOf([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v1

    new-instance v2, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$5;

    sget-object v3, Ldev/amenhancer/module/hook/LayoutInflationRegistry;->INSTANCE:Ldev/amenhancer/module/hook/LayoutInflationRegistry;

    invoke-direct {{v2, v3}}, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$5;-><init>(Ljava/lang/Object;)V

    check-cast v2, Lkotlin/jvm/functions/Function0;

{lambda2}
    new-instance v4, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda3;

    invoke-direct {{v4}}, Ldev/amenhancer/module/hook/FeatureInstallationKt$$ExternalSyntheticLambda3;-><init>()V

    new-instance {l8}, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$8;

    sget-object v5, Ldev/amenhancer/module/hook/ModernXposedRuntime;->INSTANCE:Ldev/amenhancer/module/hook/ModernXposedRuntime;

    invoke-direct {{{l8}, v5}}, Ldev/amenhancer/module/hook/FeatureInstallationKt$productionFeatureInstallationModule$8;-><init>(Ljava/lang/Object;)V

    move-object v5, {l8}

    check-cast v5, Lkotlin/jvm/functions/Function2;

    invoke-direct/range {{v0 .. v5}}, Ldev/amenhancer/module/hook/FeatureInstallationModule;-><init>(Ljava/util/List;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function3;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;)V

    return-object v0
.end method
""".format(decl=decl_line, lambda2=lambda2_block, l8=lambda8_reg)


def patch_file(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    match = METHOD_SIGNATURE.search(text)
    if not match:
        # Dump every .method line that mentions the function so the build log
        # shows the exact declaration format for debugging.
        candidates = [
            line.strip()
            for line in text.splitlines()
            if "productionFeatureInstallationModule" in line and line.strip().startswith(".method")
        ]
        import sys
        print("DEBUG: .method lines containing productionFeatureInstallationModule:", file=sys.stderr)
        for c in candidates:
            print(" ", c, file=sys.stderr)
        if not candidates:
            print("  (none found — searched file:", path, ")", file=sys.stderr)
        raise SystemExit("productionFeatureInstallationModule method not found")

    decl_line = match.group(1)
    method_start = match.start()
    method_end = text.find(".end method", method_start)
    if method_end < 0:
        raise SystemExit("productionFeatureInstallationModule end not found")
    method_end += len(".end method")

    original_method = text[method_start:method_end]
    if "TitleCorrectionFeature" not in original_method:
        raise SystemExit("Unexpected productionFeatureInstallationModule layout")

    text = text[:method_start] + _trimmed_method(decl_line) + text[method_end:]
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
