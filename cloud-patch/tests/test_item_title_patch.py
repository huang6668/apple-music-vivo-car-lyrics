#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("patch_item_title", ROOT / "patch_item_title.py")
item = importlib.util.module_from_spec(spec)
spec.loader.exec_module(item)


def getter_fixture(target):
    text = ".class public L" + target.removesuffix(".smali") + ";\n"
    for artist in (False, True):
        signature, load, _, _, _ = item.getter_spec(target, artist)
        text += (
            ".method " + signature + "\n"
            "    .locals 0\n\n"
            "    .line 1\n"
            "    " + load + "\n\n"
            "    .line 2\n"
            "    .line 3\n"
            "    return-object p0\n"
            ".end method\n"
        )
    return text


def controller_fixture(target, signature):
    return (
        ".class public final L" + target.removesuffix(".smali") + ";\n"
        ".method " + signature + "\n"
        "    .locals 24\n\n"
        "    .annotation system Ldalvik/annotation/Signature;\n"
        "        value = { \"fixture\" }\n"
        "    .end annotation\n\n"
        "    .line 1\n"
        "    invoke-virtual {p1}, Lcom/apple/android/music/mediaapi/models/MediaEntity;"
        "->getAttributes()Lcom/apple/android/music/mediaapi/models/internals/Attributes;\n"
        "    move-result-object v0\n"
        "    return-object v0\n"
        ".end method\n"
    )


def playback_fixture():
    signature, load, _, _, _ = item.getter_spec(item.TARGET_PLAYBACK, artist=True)
    return (
        ".class public Lcom/apple/android/music/model/BasePlaybackItem;\n"
        ".method " + signature + "\n.locals 0\n.line 1\n    " + load + "\n"
        "    return-object p0\n.end method\n"
        ".method public getNowPlayingSubtitle()Ljava/lang/String;\n"
        "    .locals 2\n.line 1\n"
        "    new-instance v0, Ljava/lang/StringBuilder;\n"
        "    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V\n"
        "    iget-object v1, p0, Lcom/apple/android/music/model/BaseContentItem;->artistName:Ljava/lang/String;\n"
        "    if-eqz v1, :cond_0\n"
        "    invoke-virtual {v1}, Ljava/lang/String;->isEmpty()Z\n"
        "    move-result v1\n"
        "    if-nez v1, :cond_0\n"
        "    iget-object p0, p0, Lcom/apple/android/music/model/BaseContentItem;->artistName:Ljava/lang/String;\n"
        "    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
        "    :cond_0\n"
        "    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n"
        "    move-result-object p0\n"
        "    return-object p0\n"
        ".end method\n"
    )


def song_fixture():
    return (
        ".class public Lcom/apple/android/music/model/Song;\n"
        ".method public getSubTitle()Ljava/lang/String;\n"
        "    .locals 1\n.line 1\n"
        "    iget-object v0, p0, Lcom/apple/android/music/model/Song;->subTitle:Ljava/lang/String;\n"
        "    if-nez v0, :cond_0\n"
        "    iget-object p0, p0, Lcom/apple/android/music/model/BaseContentItem;->artistName:Ljava/lang/String;\n"
        "    return-object p0\n"
        "    :cond_0\n"
        "    return-object v0\n"
        ".end method\n"
    )


class ItemTitlePatchTest(unittest.TestCase):
    def setUp(self):
        pins = {}
        for target, signature in ((item.TARGET_PLAYLIST, item.PLAYLIST_METHOD),
                                  (item.TARGET_ALBUM, item.ALBUM_METHOD)):
            text = controller_fixture(target, signature)
            block = item.method_block(text, signature).group()
            pins[target] = hashlib.sha256(item.code(block).encode()).hexdigest()
        pin = patch.dict(item.ENTRY_PINS, pins)
        pin.start()
        self.addCleanup(pin.stop)

    def test_both_getters_preserve_receiver_stock_load_and_return(self):
        for target, patch_fn, verify_fn in (
            (item.TARGET, item.patch_text, item.verify_text),
            (item.TARGET_ATTR, item.patch_attributes_text, item.verify_attributes_text),
        ):
            with self.subTest(target=target):
                stock = getter_fixture(target)
                verify_fn(stock)
                changed = patch_fn(stock)
                verify_fn(changed, patched=True)
                self.assertEqual(changed, patch_fn(changed))
                for artist in (False, True):
                    signature, load, capture, call, _ = item.getter_spec(target, artist)
                    body = item.code(item.method_block(changed, signature).group())
                    recovered = body.replace(capture, "", 1).replace(call, "", 1)
                    recovered = recovered.replace(".locals 1\n", ".locals 0\n", 1)
                    self.assertEqual(recovered, item.code(item.method_block(stock, signature).group()))
                    self.assertIn(capture + load + "\n", body)

    def test_artist_getter_is_required(self):
        for target, verify_fn in ((item.TARGET, item.verify_text),
                                  (item.TARGET_ATTR, item.verify_attributes_text)):
            stock = getter_fixture(target)
            signature = item.getter_spec(target, artist=True)[0]
            block = item.method_block(stock, signature)
            missing = stock[:block.start()] + stock[block.end():]
            with self.assertRaises(AssertionError):
                verify_fn(missing)

    def test_getters_reject_duplicates_wrong_registers_and_stock_drift(self):
        for target, patch_fn, verify_fn in (
            (item.TARGET, item.patch_text, item.verify_text),
            (item.TARGET_ATTR, item.patch_attributes_text, item.verify_attributes_text),
        ):
            stock = getter_fixture(target)
            changed = patch_fn(stock)
            bad_values = (
                changed.replace("invoke-static {v0, p0}", "invoke-static {p0, v0}", 1),
                changed.replace("move-result-object p0", "move-result-object v0", 1),
                changed.replace("return-object p0", "return-object v0", 1),
                changed.replace("move-object v0, p0", "move-object v0, p1", 1),
                changed.replace(".locals 1", ".locals 2", 1),
                changed.replace(".end method", "nop\n.end method", 1),
                changed + changed,
            )
            for value in bad_values:
                with self.assertRaises(AssertionError):
                    verify_fn(value, patched=True)
            with self.assertRaises(AssertionError):
                verify_fn(changed)
            with self.assertRaises(AssertionError):
                patch_fn(stock.replace("->name:", "->otherName:", 1))

    def test_attributes_keep_receiver_for_both_field_writes(self):
        changed = item.patch_attributes_text(getter_fixture(item.TARGET_ATTR))
        for field in ("name", "artistName"):
            write = (
                "iput-object p0, v0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;"
                "->" + field + ":Ljava/lang/String;"
            )
            self.assertIn(write, changed)
            with self.assertRaises(AssertionError):
                item.verify_attributes_text(changed.replace(write, ""), patched=True)

    def test_playback_override_and_raw_artist_subtitle_are_both_corrected(self):
        stock = playback_fixture()
        item.verify_playback_text(stock)
        changed = item.patch_playback_text(stock)
        item.verify_playback_text(changed, patched=True)
        self.assertEqual(changed, item.patch_playback_text(changed))
        self.assertEqual(changed.count("VivoCarLyrics;->resolveItemArtist"), 2)
        self.assertIn("move-object v2, p0", changed)
        self.assertIn("invoke-static {v2, p0}", changed)
        subtitle = item.method_block(changed, item.ARTIST_RETURN_SPECS[item.TARGET_PLAYBACK][0]).group()
        self.assertIn(".locals 3", subtitle)
        recovered = item.code(subtitle).replace("move-object v2, p0\n", "", 1)
        recovered = recovered.replace(item.artist_return_call("v2"), "", 1).replace(
            ".locals 3\n", ".locals 2\n", 1
        )
        self.assertEqual(recovered, item.code(item.method_block(
            stock, item.ARTIST_RETURN_SPECS[item.TARGET_PLAYBACK][0]
        ).group()))

    def test_song_only_translates_raw_artist_fallback_and_keeps_explicit_subtitle(self):
        stock = song_fixture()
        item.verify_song_text(stock)
        changed = item.patch_song_text(stock)
        item.verify_song_text(changed, patched=True)
        self.assertEqual(changed, item.patch_song_text(changed))
        body = item.code(changed)
        fallback = (
            "iget-object p0, p0, Lcom/apple/android/music/model/BaseContentItem;"
            "->artistName:Ljava/lang/String;\n"
        )
        self.assertIn(fallback + item.artist_return_call("v1") + "return-object p0\n", body)
        self.assertIn(":cond_0\nreturn-object v0\n", body)
        self.assertNotIn("VivoCarLyrics", body.split("\n:cond_0\n", 1)[1])

    def test_subtitle_hooks_reject_live_register_changes_or_modified_stock_branches(self):
        for target, stock, patch_fn, verify_fn in (
            (item.TARGET_PLAYBACK, playback_fixture(), item.patch_playback_text, item.verify_playback_text),
            (item.TARGET_SONG, song_fixture(), item.patch_song_text, item.verify_song_text),
        ):
            changed = patch_fn(stock)
            receiver = item.ARTIST_RETURN_SPECS[target][3]
            for value in (
                changed.replace("move-object " + receiver + ", p0", "move-object v0, p0"),
                changed.replace("invoke-static {" + receiver + ", p0}",
                                "invoke-static {p0, " + receiver + "}"),
                changed.replace("if-nez", "if-eqz"),
                changed.replace("return-object p0", "return-object v0", 1),
                changed.replace(".end method", "nop\n.end method", 1),
            ):
                with self.assertRaises(AssertionError):
                    verify_fn(value, patched=True)
            with self.assertRaises(AssertionError):
                verify_fn(changed)

    def test_controller_entry_uses_register_safe_range_without_changing_locals(self):
        for target, signature, patch_fn, verify_fn in (
            (item.TARGET_PLAYLIST, item.PLAYLIST_METHOD, item.patch_playlist_text, item.verify_playlist_text),
            (item.TARGET_ALBUM, item.ALBUM_METHOD, item.patch_album_text, item.verify_album_text),
        ):
            stock = controller_fixture(target, signature)
            verify_fn(stock)
            changed = patch_fn(stock)
            verify_fn(changed, patched=True)
            self.assertEqual(changed, patch_fn(changed))
            self.assertEqual(item.code(changed).replace(item.code(item.PLAYLIST_HOOK), "", 1),
                             item.code(stock))
            self.assertIn(".locals 24", changed)
            self.assertIn("invoke-static/range {p0 .. p1}", changed)

    def test_controller_rejects_shifted_or_duplicate_hook_and_stock_drift(self):
        for target, signature, patch_fn, verify_fn in (
            (item.TARGET_PLAYLIST, item.PLAYLIST_METHOD, item.patch_playlist_text, item.verify_playlist_text),
            (item.TARGET_ALBUM, item.ALBUM_METHOD, item.patch_album_text, item.verify_album_text),
        ):
            changed = patch_fn(controller_fixture(target, signature))
            for value in (
                changed.replace(item.PLAYLIST_HOOK, item.PLAYLIST_HOOK * 2),
                changed.replace("{p0 .. p1}", "{p1 .. p2}"),
                changed.replace(".locals 24", ".locals 23"),
                changed.replace("return-object v0", "nop\n    return-object v0"),
                changed.replace(item.PLAYLIST_HOOK, "").replace(
                    "    return-object v0", item.PLAYLIST_HOOK + "    return-object v0"),
            ):
                with self.assertRaises(AssertionError):
                    verify_fn(value, patched=True)

    def test_unique_case_sensitive_target_in_pinned_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "smali_classes2" / item.TARGET
            path.parent.mkdir(parents=True)
            path.write_text(getter_fixture(item.TARGET), encoding="utf-8")
            self.assertEqual(item.target_path(root), path)
            duplicate = root / "smali_classes3" / item.TARGET
            duplicate.parent.mkdir(parents=True)
            duplicate.write_text("", encoding="utf-8")
            with self.assertRaises(AssertionError):
                item.target_path(root)
            path.unlink()
            with self.assertRaises(AssertionError):
                item.target_path(root)
            with self.assertRaises(AssertionError):
                item.verify_text(getter_fixture(item.TARGET).replace(
                    "Lcom/apple/android/music/model/BaseContentItem;",
                    "Lcom/apple/android/music/model/Basecontentitem;", 1))


if __name__ == "__main__":
    unittest.main()
