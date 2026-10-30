import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("patch_vivo_browse", ROOT / "patch_vivo_browse.py")
browse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browse)

PLAY = next(item for item in browse.SPECS if item[0] == browse.CONNECTION)


def connection():
    _, method, seam, _, _, _ = PLAY
    stock = (
        ".method " + method + "\n"
        "    .locals 9\n\n"
        + seam + "\n"
        "    return-object v0\n"
        ".end method\n"
    )
    other = ".method public final x()V\n" + seam + "\n.end method\n"
    head = ".class public final Lcom/apple/android/music/player/c0;\n\n"
    return head + other + "\n" + stock, hashlib.sha256(browse.code(stock).encode()).hexdigest()


class AtomicListPlayHookTest(unittest.TestCase):
    def test_hook_lands_in_the_pinned_method_only(self):
        text, digest = connection()
        target, method, seam, _, hook, marker = PLAY
        browse.verify(text, target, method, seam, digest, hook, marker, patched=False)
        patched = browse.insert_hook(text, method, seam, hook)
        browse.verify(patched, target, method, seam, digest, hook, marker, patched=True)
        self.assertEqual(patched.count(marker), 1)
        self.assertLess(patched.index(".method public final x()V"), patched.index(marker))
        self.assertIn(".method public final x()V\n" + seam + "\n.end method", patched)

    def test_drifted_stock_is_rejected(self):
        text, digest = connection()
        target, method, seam, _, hook, marker = PLAY
        drifted = text.replace("return-object v0", "return-object v1")
        with self.assertRaises(AssertionError):
            browse.verify(drifted, target, method, seam, digest, hook, marker, patched=False)

    def test_connection_must_stay_in_its_dex(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            moved = root / "smali" / browse.CONNECTION
            moved.parent.mkdir(parents=True)
            moved.write_text("")
            with self.assertRaises(AssertionError):
                browse.target_path(root, browse.CONNECTION)
            good = root / "smali_classes2" / browse.CONNECTION
            good.parent.mkdir(parents=True)
            moved.unlink()
            good.write_text("")
            self.assertEqual(browse.target_path(root, browse.CONNECTION), good)


if __name__ == "__main__":
    unittest.main()
