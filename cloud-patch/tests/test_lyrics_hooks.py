#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
module_spec = importlib.util.spec_from_file_location(
    "verify_lyrics_hooks", ROOT / "verify_lyrics_hooks.py"
)
lyrics = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(lyrics)


def fixture(spec, patched=False):
    target, signature, helper, registers, _, before, after = spec
    locals_count = 1 if patched or target != lyrics.CONNECTION else 0
    inserted = ""
    if patched:
        inserted = (lyrics.ATOMIC_BODY if target == lyrics.CONNECTION else
                    "invoke-static " + registers + ", " + lyrics.HELPER + "->" + helper + "\n")
    return (
        ".class public final L" + target.removesuffix(".smali") + ";\n"
        ".method " + signature + "\n.locals " + str(locals_count) + "\n"
        + before + "\n" + inserted + after + "\n.end method\n"
    )


def pinned_spec(spec):
    block = fixture(spec).split("\n", 1)[1]
    values = list(spec)
    values[4] = hashlib.sha256(lyrics.code(block).encode()).hexdigest()
    return tuple(values)


class LyricsHooksTest(unittest.TestCase):
    def test_stock_and_patched_preserve_all_six_method_instructions(self):
        for original_spec in lyrics.SPECS:
            spec = pinned_spec(original_spec)
            with self.subTest(helper=spec[2]):
                lyrics.verify_method(fixture(spec), spec, unpatched=True)
                lyrics.verify_method(fixture(spec, patched=True), spec)

    def test_wrong_arguments_or_duplicate_helpers_are_rejected(self):
        for original_spec in lyrics.SPECS:
            spec = pinned_spec(original_spec)
            text = fixture(spec, patched=True)
            call = "invoke-static " + spec[3] + ", " + lyrics.HELPER + "->" + spec[2]
            for changed in (text.replace(call, call.replace(spec[3], "{p2}")),
                            text.replace(call, call + "\n" + call),
                            text.replace(call, "")):
                with self.subTest(helper=spec[2]):
                    with self.assertRaises(AssertionError):
                        lyrics.verify_method(changed, spec)

    def test_changed_stock_labels_locals_or_instruction_rejected(self):
        for original_spec in lyrics.SPECS:
            spec = pinned_spec(original_spec)
            text = fixture(spec, patched=True)
            for changed in (text.replace(".locals 1", ".locals 2"),
                            text.replace(spec[5], spec[5] + "\nnop"),
                            text.replace(".end method", ":extra_label\n.end method")):
                with self.assertRaises(AssertionError):
                    lyrics.verify_method(changed, spec)

    def test_reject_wrong_class_case_and_duplicate_methods(self):
        for original_spec in lyrics.SPECS:
            spec = pinned_spec(original_spec)
            text = fixture(spec, patched=True)
            for changed in (text.replace("L" + spec[0].removesuffix(".smali") + ";",
                                         "Lother/Class;", 1),
                            text + text):
                with self.assertRaises(AssertionError):
                    lyrics.verify_method(changed, spec)

    def test_hooks_cannot_be_applied_twice(self):
        for original_spec in lyrics.SPECS:
            spec = pinned_spec(original_spec)
            with self.assertRaises(AssertionError):
                lyrics.verify_method(fixture(spec, patched=True), spec, unpatched=True)

    def test_targets_require_unique_original_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "smali_classes2" / lyrics.MANAGER
            target.parent.mkdir(parents=True)
            target.write_text("", encoding="utf-8")
            self.assertEqual(lyrics.target_path(root, lyrics.MANAGER), target)
            wrong = root / "smali_classes3" / lyrics.MANAGER
            wrong.parent.mkdir(parents=True)
            wrong.write_text("", encoding="utf-8")
            with self.assertRaises(AssertionError):
                lyrics.target_path(root, lyrics.MANAGER)
            target.unlink()
            with self.assertRaises(AssertionError):
                lyrics.target_path(root, lyrics.MANAGER)

    def test_build_checks_source_and_final_apk(self):
        apply_text = (ROOT / "apply.sh").read_text(encoding="utf-8")
        rebuild_text = (ROOT / "rebuild.sh").read_text(encoding="utf-8")
        self.assertIn('python3 "$lyrics_verifier" work/apktool --unpatched', apply_text)
        self.assertIn('python3 "$lyrics_verifier" work/apktool', apply_text)
        self.assertIn('verify_lyrics_hooks.py" "$FINAL_MANIFEST_DIR"', rebuild_text)


if __name__ == "__main__":
    unittest.main()
