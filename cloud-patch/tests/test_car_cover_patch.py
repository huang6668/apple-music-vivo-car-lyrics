import importlib.util
import hashlib
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("patch_car_cover", ROOT / "patch_car_cover.py")
cover = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cover)


def stock():
    return (
        ".class public final LJ4/t;\n"
        ".super Ljava/lang/Object;\n\n"
        ".method " + cover.METHOD + "\n"
        + cover.SEAM + "\n"
        "    return-object v0\n"
        ".end method\n"
    )


class CarCoverHookTest(unittest.TestCase):
    def setUp(self):
        self.original = stock()
        self.pin = patch.object(
            cover, "STOCK_SHA256", hashlib.sha256(self.original.encode("utf-8")).hexdigest()
        )
        self.pin.start()
        self.addCleanup(self.pin.stop)
        method_block = re.findall(
            r"(?ms)^\.method " + re.escape(cover.METHOD) + r"\n.*?^\.end method$", self.original
        )[0]
        pin_code = patch.object(
            cover, "STOCK_CODE_SHA256", hashlib.sha256(cover.code(method_block).encode("utf-8")).hexdigest()
        )
        pin_code.start()
        self.addCleanup(pin_code.stop)

    def test_preserves_all_stock_bytes_and_registers(self):
        cover.verify_text(self.original, patched=False)
        changed = self.original.replace(cover.SEAM, cover.PATCHED_SEAM)
        cover.verify_text(changed, patched=True)
        self.assertEqual(changed.replace(cover.HOOK, ""), self.original)

    def test_rejects_drift_duplicate_or_wrong_case(self):
        for text in (
            self.original.replace(".locals 3", ".locals 4"),
            self.original.replace("LJ4/t;", "LJ4/T;"),
            self.original.replace("return-object v0", "return-object v1"),
            self.original + self.original,
        ):
            with self.assertRaises(AssertionError):
                cover.verify_text(text, patched=False)

    def test_requires_once_at_entry(self):
        changed = self.original.replace(cover.SEAM, cover.PATCHED_SEAM)
        for text in (
            self.original,
            changed.replace(cover.HOOK, cover.HOOK * 2),
            changed.replace(cover.HOOK, "").replace("    return-object", cover.HOOK + "    return-object"),
        ):
            with self.assertRaises(AssertionError):
                cover.verify_text(text, patched=True)

    def test_unique_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "smali" / cover.TARGET
            path.parent.mkdir(parents=True)
            path.write_text(self.original, encoding="utf-8")
            self.assertEqual(path, cover.target_path(root))
            duplicate = root / "smali_classes2" / cover.TARGET
            duplicate.parent.mkdir(parents=True)
            duplicate.write_text(self.original, encoding="utf-8")
            with self.assertRaises(AssertionError):
                cover.target_path(root)


if __name__ == "__main__":
    unittest.main()
