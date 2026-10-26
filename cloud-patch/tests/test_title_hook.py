#!/usr/bin/env python3
import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


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
        *("    " + op for op in hook.ARTIST_SEAM),
        "    move-object/from16 v54, v31",
        "    :goto_27",
        "    and-long v52, v35, v28",
        "    " + hook.ARTIST_VIEW,
        "    move-object/from16 v3, v54",
        "    " + hook.ARTIST_ASSIGN,
        "    return-void",
        ".end method",
        "",
    ))


def stock_fixture():
    stock = fixture()
    for seam in (hook.SEAM, hook.ARTIST_SEAM):
        for op in seam[2:7]:
            stock = stock.replace("    " + op + "\n", "", 1)
    return stock


class TitleHookTest(unittest.TestCase):
    def setUp(self):
        pin = patch.object(hook, "STOCK_CODE_SHA256",
                           hashlib.sha256(hook.code(stock_fixture()).encode()).hexdigest())
        pin.start()
        self.addCleanup(pin.stop)

    def test_valid(self):
        hook.verify_text(fixture())

    def test_valid_unpatched(self):
        stock = stock_fixture()
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
            hook.verify_text(fixture().replace(hook.VIEW, hook.VIEW.replace("->p0:", "->o0:")))

    def test_scratch_register_must_be_dead(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("cmp-long v40, v43, v22",
                                             "move-object/from16 v2, v40"))

    def test_artist_hook_required_once(self):
        for text in (fixture().replace(hook.ARTIST_HOOK, ""),
                     fixture().replace(hook.ARTIST_HOOK, hook.ARTIST_HOOK + "\n" + hook.ARTIST_HOOK)):
            with self.assertRaises(AssertionError):
                hook.verify_text(text)

    def test_artist_binding_identity_and_scratch_are_checked(self):
        for text in (fixture().replace("{v52 .. v54}", "{v53 .. v55}"),
                     fixture().replace("move-object/from16 v53, v8",
                                       "move-object/from16 v53, v9"),
                     fixture().replace("and-long v52, v35, v28",
                                       "move-object/from16 v3, v53"),
                     fixture().replace("move-object/from16 v54, v52",
                                       "move-object/from16 v54, v31"),
                     fixture().replace(hook.ARTIST_VIEW,
                                       hook.ARTIST_VIEW.replace("->o0:", "->p0:"))):
            with self.assertRaises(AssertionError):
                hook.verify_text(text)

    def test_unrelated_stock_instruction_drift_rejected(self):
        with self.assertRaises(AssertionError):
            hook.verify_text(fixture().replace("return-void", "nop\n    return-void"))

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
        self.assertEqual(patch.count(hook.ARTIST_HELPER), 1)
        self.assertIn("+    " + hook.HOOK, patch)
        self.assertIn("+    " + hook.ARTIST_HOOK, patch)
        self.assertIn("     move-result-object v39", patch)
        ui_header = "--- a/smali_classes2/" + hook.TARGET
        self.assertGreater(patch.index(ui_header), patch.index("onAtomicControllerConnected"))
        self.assertEqual(
            hashlib.sha256(patch[:patch.index(ui_header)].encode("utf-8")).hexdigest(),
            "a7e1724d76cdfc65933db8a13de368d3fc4325a2d16b35164ad3b266b3f61375",
            "The six O/c0 lyrics hooks must remain pinned to the audited 1611 port",
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
        end = next((index for index in range(start + 1, len(lines))
                    if lines[index].startswith("@@")), len(lines))
        changes = lines[start + 1:end]
        leading = next(index for index, line in enumerate(changes) if not line.startswith(" "))
        trailing = next(index for index, line in enumerate(reversed(changes))
                        if not line.startswith(" "))
        self.assertGreaterEqual(leading, 3, "GNU patch --fuzz=0 needs full leading context")
        self.assertGreaterEqual(trailing, 3, "GNU patch --fuzz=0 needs full trailing context")
        self.assertEqual(sum(not line.startswith("+") for line in changes), 10)
        self.assertEqual(sum(not line.startswith("-") for line in changes), 19)

    def test_artist_hunk_has_full_context_on_both_sides(self):
        patch_text = (ROOT / "apple-vivo-car-lyrics.patch").read_text(encoding="utf-8")
        hunk = patch_text.split("@@ -3140,10 +3149,19 @@\n", 1)[1].splitlines()
        leading = next(index for index, line in enumerate(hunk) if not line.startswith(" "))
        trailing = next(index for index, line in enumerate(reversed(hunk))
                        if not line.startswith(" "))
        self.assertGreaterEqual(leading, 3)
        self.assertGreaterEqual(trailing, 3)
        self.assertEqual(sum(not line.startswith("+") for line in hunk), 10)
        self.assertEqual(sum(not line.startswith("-") for line in hunk), 19)


if __name__ == "__main__":
    unittest.main()
