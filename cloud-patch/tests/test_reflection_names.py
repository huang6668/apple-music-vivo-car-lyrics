#!/usr/bin/env python3
"""Synthetic fixtures for verify_reflection_names.py (no real APK tree needed)."""

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import verify_reflection_names as names  # noqa: E402


BINDING_SETTER = r"^\.method public abstract q0\(Lcom/apple/android/music/model/PlaybackItem;\)V$"


def sample_line(pattern):
    """Turn a SPECS regex into the smali line it is meant to match."""
    return re.sub(r"\\(.)", r"\1", pattern.lstrip("^").rstrip("$"))


def restore(root):
    """Rebuild every fixture file from *all* specs that target it.

    Two specs share smali/z3/y.smali, and the PlaybackItem setter lives in the same file
    as the player page fields, so restoring one spec's text alone would delete the rest.
    """
    by_file = {}
    for _, relative, patterns, _ in names.SPECS:
        by_file.setdefault(relative, []).extend(patterns)
    binding = by_file["smali_classes2/q8/ma.smali"]
    binding[:] = [BINDING_SETTER if pattern == BINDING_SETTER.replace("q0", "\\w+") else pattern
                  for pattern in binding]
    if BINDING_SETTER not in binding:
        binding.append(BINDING_SETTER)
    for relative, patterns in by_file.items():
        fixture(root, relative, "\n".join(sample_line(pattern) for pattern in patterns) + "\n")




def fixture(root, relative, body):
    path = Path(root) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


class ReflectionNamesTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name)
        fixture(self.root, "AndroidManifest.xml", "<manifest />")
        restore(self.root)

    def tearDown(self):
        self._dir.cleanup()

    def test_complete_fixture_passes(self):
        checked, problems, _ = names.verify(self.root)
        self.assertEqual(problems, [])
        self.assertEqual(len(checked), sum(len(spec[2]) for spec in names.SPECS))
        self.assertEqual(names.verify_playback_item_setter(self.root), ("q0", None))

    def test_each_spec_fails_when_its_name_changes(self):
        """Every spec must be load-bearing: break just its file, expect just its failures."""
        for label, relative, patterns, impact in names.SPECS:
            with self.subTest(label=label):
                fixture(self.root, relative, ".class public Lother/Renamed;\n")
                _, problems, _ = names.verify(self.root)
                failures = [problem for problem in problems if problem.startswith(label + ":")]
                self.assertEqual(len(failures), len(patterns),
                                 "expected %d failing pattern(s) for %s" % (len(patterns), label))
                self.assertTrue(all(impact in failure for failure in failures),
                                "each failure must say what breaks: " + label)
                restore(self.root)
        _, problems, _ = names.verify(self.root)
        self.assertEqual(problems, [])

    def test_missing_file_is_reported(self):
        (self.root / "smali/z3/y.smali").unlink()
        _, problems, _ = names.verify(self.root)
        self.assertTrue(any("smali/z3/y.smali" in problem for problem in problems))

    def test_ambiguity_is_rejected(self):
        marker = sample_line(BINDING_SETTER) + "\n"
        fixture(self.root, "smali_classes2/q8/ma.smali",
                marker +
                ".method public abstract z9(Lcom/apple/android/music/model/PlaybackItem;)V\n")
        try:
            setter, problem = names.verify_playback_item_setter(self.root)
            self.assertIsNone(setter)
            self.assertIn("exactly one", problem)
        finally:
            fixture(self.root, "smali_classes2/q8/ma.smali", marker)

    def test_runtime_class_notes_cover_the_application_class(self):
        self.assertTrue(any(name.startswith("com.apple.android.music.AppleMusicApplication")
                            for name, _ in names.RUNTIME_CLASSES))


if __name__ == "__main__":
    unittest.main()
