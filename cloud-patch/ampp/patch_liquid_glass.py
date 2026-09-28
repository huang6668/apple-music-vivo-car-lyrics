"""Force-disable AM++'s phone liquid glass feature for Vivo builds."""

import pathlib
import re
import sys


METHOD_PATTERN = re.compile(
    r"(?m)^\.method public final getPhoneLiquidGlassEnabled\(\)Z$.*?^\.end method$",
    re.DOTALL,
)

DISABLED_METHOD = """
.method public final getPhoneLiquidGlassEnabled()Z
    .locals 1

    const/4 v0, 0x0

    return v0
.end method
""".strip()


def patch(source):
    matches = METHOD_PATTERN.findall(source)
    if len(matches) != 1:
        raise ValueError(
            "Expected one ModuleSettings.getPhoneLiquidGlassEnabled method, got %d"
            % len(matches)
        )
    return METHOD_PATTERN.sub(DISABLED_METHOD, source, count=1)


if __name__ == "__main__":
    root = pathlib.Path(sys.argv[1])
    files = list(
        root.glob("smali*/dev/amenhancer/module/model/ModuleSettings.smali")
    )
    if len(files) != 1:
        raise SystemExit("Expected exactly one ModuleSettings.smali")
    files[0].write_text(patch(files[0].read_text()))
    print("Force-disabled AM++ phone liquid glass:", files[0])
