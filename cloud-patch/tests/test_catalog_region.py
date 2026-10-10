import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("patch_catalog_region", ROOT / "patch_catalog_region.py")
region = importlib.util.module_from_spec(spec)
spec.loader.exec_module(region)


def stock():
    return (".class public final Ly9/k;\n.method " + region.METHOD + "\n"
            + region.SEAM + "\n    return-object p1\n.end method\n")


class RegionHookTest(unittest.TestCase):
    def setUp(self):
        self.original = stock()
        self.pin = patch.object(region, "STOCK_SHA256",
                                hashlib.sha256(self.original.encode()).hexdigest())
        self.pin.start()
        self.addCleanup(self.pin.stop)
        pin_code = patch.object(region, "STOCK_CODE_SHA256",
                                hashlib.sha256(region.code(self.original).encode()).hexdigest())
        pin_code.start()
        self.addCleanup(pin_code.stop)

    def test_preserves_all_stock_bytes_and_locals(self):
        region.verify_text(self.original)
        changed = self.original.replace(region.SEAM, region.PATCHED_SEAM)
        region.verify_text(changed, patched=True)
        region.verify_text(changed.replace(
            "    # Fixed mainland localization is restricted to resolver-owned request tokens.\n",
            ""), patched=True)
        self.assertEqual(changed.replace(region.HOOK, ""), self.original)

    def test_rejects_drift_duplicate_or_wrong_case(self):
        for text in (self.original.replace(".locals 2", ".locals 3"),
                     self.original.replace("Ly9/k;", "Ly9/K;"),
                     self.original.replace("return-object p1", "return-object p2"),
                     self.original + self.original):
            with self.assertRaises(AssertionError):
                region.verify_text(text)

    def test_requires_once_at_entry(self):
        changed = self.original.replace(region.SEAM, region.PATCHED_SEAM)
        for text in (self.original, changed.replace(region.HOOK, region.HOOK * 2),
                     changed.replace(region.HOOK, "").replace("    return-object", region.HOOK
                                                             + "    return-object")):
            with self.assertRaises(AssertionError):
                region.verify_text(text, patched=True)

    def test_unique_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "smali_classes2" / region.TARGET
            path.parent.mkdir(parents=True)
            path.write_text(self.original)
            self.assertEqual(path, region.target_path(root))
            duplicate = root / "smali_classes3" / region.TARGET
            duplicate.parent.mkdir(parents=True)
            duplicate.write_text(self.original)
            with self.assertRaises(AssertionError):
                region.target_path(root)
