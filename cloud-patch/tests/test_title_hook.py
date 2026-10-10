#!/usr/bin/env python3
import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify_title_hook", ROOT / "verify_title_hook.py")
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


def fixture():
    return "\n".join((
        ".class public final Lq8/na;",
        ".method public final " + hook.METHOD,
        "    .locals 58",
        "    " + hook.RECEIVER,
        "    " + hook.ITEM,
        "    .line 401",
        *("    " + op for op in hook.SEAM),
        "    cmp-long v40, v43, v22",
        "    " + hook.VIEW,
        "    move-object/from16 v2, v42",
        "    " + hook.ASSIGN,
        "    " + hook.EXPLICIT_ASSIGN,
        "    return-void",
        ".end method",
        "",
    ))


class TitleHookTest(unittest.TestCase):
    def test_valid(self):
        hook.verify_text(fixture())

    def test_valid_unpatched(self):
        stock = fixture()
        for op in hook.SEAM[2:7]:
            stock = stock.replace("    " + op + "\n", "", 1)
        hook.verify_text(stock, unpatched=True)
        with self.assertRaises(AssertionError):
            hook.verify_text(stock)

    def test_unpatched_rejects_existing_hook(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture(), unpatched=True)

    def test_missing_hook(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace(hook.HOOK, ""))

    def test_duplicate_hook(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace(hook.HOOK, hook.HOOK + "\n" + hook.HOOK))

    def test_wrong_registers(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("{v39 .. v41}", "{v40 .. v42}"))

    def test_wrong_method(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace(hook.METHOD, "z()V"))

    def test_case_collided_class(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("Lq8/na;", "Lq8/Na;"))

    def test_duplicate_method(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture() + fixture())

    def test_preserve_original_register_count(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace(".locals 58", ".locals 59"))

    def test_preserve_stock_copy(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("move-object/from16 v42, v39",
                                             "move-object/from16 v42, v31"))

    def test_preserve_title_assignment(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace(hook.VIEW, hook.VIEW.replace("->q0:", "->p0:")))

    def test_scratch_register_must_be_dead(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("cmp-long v40, v43, v22",
                                             "move-object/from16 v2, v40"))

    def test_unique_target_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for dex in ("smali_classes2", "smali_classes3"):
                path = root / dex / hook.TARGET
                path.parent.mkdir(parents=True)
                path.write_text(fixture(), encoding="utf-8")
            with self.assertRaises(AssertionError):
                hook.verify_tree(root)

    def test_patch_seam_and_original_hooks(self):
        patch = (ROOT / "apple-vivo-car-lyrics.patch").read_text(encoding="utf-8")
        self.assertEqual(patch.count(hook.HELPER), 1)
        self.assertIn("+    " + hook.HOOK, patch)
        self.assertIn("     move-result-object v39", patch)
        ui_header = "--- a/smali_classes2/" + hook.TARGET
        self.assertGreater(patch.index(ui_header), patch.index("onAtomicControllerConnected"))
        self.assertEqual(
            hashlib.sha256(patch[:patch.index(ui_header)].encode("utf-8")).hexdigest(),
            "6bf19b9d96cb292a1f201ede980980b29427c2294ad103242f9718cc365baa9e",
            "The six frozen S/e0 hooks must remain byte-identical",
        )
        self.assertEqual(patch.count("\n--- a/"), 2)
        for name in ("onNativeMediaItem", "onCurrentItemChanged", "onMetadataUpdated",
                     "onPlaybackError", "onSeek", "onAtomicControllerConnected"):
            self.assertEqual(patch.count("->" + name + "("), 1)

    def test_title_hunk_has_full_context_on_both_sides(self):
        patch = (ROOT / "apple-vivo-car-lyrics.patch").read_text(encoding="utf-8")
        title_hunk = patch.split("--- a/smali_classes2/" + hook.TARGET, 1)[1]
        lines = title_hunk.splitlines()
        start = next(index for index, line in enumerate(lines) if line.startswith("@@"))
        changes = lines[start + 1:]
        leading = next(index for index, line in enumerate(changes) if not line.startswith(" "))
        trailing = next(index for index, line in enumerate(reversed(changes))
                        if not line.startswith(" "))
        self.assertGreaterEqual(leading, 3, "GNU patch --fuzz=0 needs full leading context")
        self.assertGreaterEqual(trailing, 3, "GNU patch --fuzz=0 needs full trailing context")
        self.assertEqual(sum(not line.startswith("+") for line in changes), 10)
        self.assertEqual(sum(not line.startswith("-") for line in changes), 19)


if __name__ == "__main__":
    unittest.main()
