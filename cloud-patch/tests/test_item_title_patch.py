#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("patch_item_title", ROOT / "patch_item_title.py")
patch_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch_module)


def stock_fixture(locals_zero=True):
    locals_line = "    .locals 0" if locals_zero else "    .registers 1"
    return (
        ".class public Lcom/apple/android/music/model/BaseContentItem;\n"
        ".super Lcom/apple/android/music/model/BaseCollectionItemView;\n\n"
        ".method public getTitle()Ljava/lang/String;\n"
        f"{locals_line}\n\n"
        "    .line 1\n"
        "    iget-object p0, p0, Lcom/apple/android/music/model/BaseContentItem;->name:Ljava/lang/String;\n\n"
        "    return-object p0\n"
        ".end method\n"
    )


def stock_attributes_fixture():
    return (
        ".class public final Lcom/apple/android/music/mediaapi/models/internals/Attributes;\n"
        ".super LA6/b;\n\n"
        ".method public final getName()Ljava/lang/String;\n"
        "    .registers 2\n\n"
        "    .line 1\n"
        "    iget-object v0, p0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;->name:Ljava/lang/String;\n\n"
        "    return-object v0\n"
        ".end method\n"
    )


def stock_playlist_fixture():
    return (
        ".class public final Lcom/apple/android/music/collection/mediaapi/controller/PlaylistPageController;\n"
        ".super LA6/b;\n\n"
        ".method private final addTrackModel(Lcom/apple/android/music/mediaapi/models/MediaEntity;I)Lcom/airbnb/epoxy/w;\n"
        "    .locals 7\n\n"
        "    .line 1\n"
        "    invoke-virtual {p0}, Lo7/x;->getViewLifecycleOwner()Landroidx/lifecycle/LifecycleOwner;\n"
        "    return-object v0\n"
        ".end method\n"
    )


def stock_album_fixture():
    return (
        ".class public final Lcom/apple/android/music/collection/mediaapi/controller/AlbumPageController;\n"
        ".super LA6/b;\n\n"
        ".method private final getItemTitle(Lcom/apple/android/music/mediaapi/models/MediaEntity;)Ljava/lang/String;\n"
        "    .locals 2\n\n"
        "    .line 1\n"
        "    invoke-virtual {p1}, Lcom/apple/android/music/mediaapi/models/MediaEntity;->getAttributes()Lcom/apple/android/music/mediaapi/models/internals/Attributes;\n"
        "    return-object v0\n"
        ".end method\n"
    )


class ItemTitlePatchTest(unittest.TestCase):
    def test_patch_and_verify(self):
        stock = stock_fixture()
        patch_module.verify_text(stock, patched=False)
        with self.assertRaises(AssertionError):
            patch_module.verify_text(stock, patched=True)

        patched = patch_module.patch_text(stock)
        patch_module.verify_text(patched, patched=True)
        with self.assertRaises(AssertionError):
            patch_module.verify_text(patched, patched=False)

        self.assertIn("VivoCarLyrics;->resolveItemTitle", patched)
        self.assertIn(".locals 1", patched)
        self.assertIn("->name:Ljava/lang/String;", patched)

    def test_idempotent_patch(self):
        stock = stock_fixture()
        patched1 = patch_module.patch_text(stock)
        patched2 = patch_module.patch_text(patched1)
        self.assertEqual(patched1, patched2)

    def test_attributes_patch_and_verify(self):
        stock = stock_attributes_fixture()
        patch_module.verify_attributes_text(stock, patched=False)
        with self.assertRaises(AssertionError):
            patch_module.verify_attributes_text(stock, patched=True)

        patched = patch_module.patch_attributes_text(stock)
        patch_module.verify_attributes_text(patched, patched=True)
        with self.assertRaises(AssertionError):
            patch_module.verify_attributes_text(patched, patched=False)

        self.assertIn("VivoCarLyrics;->resolveItemTitle", patched)
        self.assertIn("iput-object v0, p0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;->name:Ljava/lang/String;", patched)

        # Idempotent
        self.assertEqual(patched, patch_module.patch_attributes_text(patched))

    def test_playlist_patch_and_verify(self):
        stock = stock_playlist_fixture()
        patch_module.verify_playlist_text(stock, patched=False)
        with self.assertRaises(AssertionError):
            patch_module.verify_playlist_text(stock, patched=True)

        patched = patch_module.patch_playlist_text(stock)
        patch_module.verify_playlist_text(patched, patched=True)
        with self.assertRaises(AssertionError):
            patch_module.verify_playlist_text(patched, patched=False)

        self.assertIn("VivoCarLyrics;->onPlaylistTrack", patched)
        self.assertEqual(patched, patch_module.patch_playlist_text(patched))

    def test_album_patch_and_verify(self):
        stock = stock_album_fixture()
        patch_module.verify_album_text(stock, patched=False)
        with self.assertRaises(AssertionError):
            patch_module.verify_album_text(stock, patched=True)

        patched = patch_module.patch_album_text(stock)
        patch_module.verify_album_text(patched, patched=True)
        with self.assertRaises(AssertionError):
            patch_module.verify_album_text(patched, patched=False)

        self.assertIn("VivoCarLyrics;->onPlaylistTrack", patched)
        self.assertEqual(patched, patch_module.patch_album_text(patched))

    def test_target_path_lookup(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            target = root / "smali_classes2" / patch_module.TARGET
            target.parent.mkdir(parents=True)
            target.write_text(stock_fixture(), encoding="utf-8")

            found = patch_module.target_path(root)
            self.assertEqual(found, target)

            # Duplicate target must raise AssertionError
            dup = root / "smali_classes3" / patch_module.TARGET
            dup.parent.mkdir(parents=True)
            dup.write_text(stock_fixture(), encoding="utf-8")
            with self.assertRaises(AssertionError):
                patch_module.target_path(root)


if __name__ == "__main__":
    unittest.main()
