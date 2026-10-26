#!/usr/bin/env python3
"""Pin the six existing lyrics hooks to the cloud-analyzed 1611 stock methods."""

import argparse
import hashlib
from pathlib import Path
import re


MANAGER = "com/apple/android/music/player/O.smali"
CONNECTION = "com/apple/android/music/player/c0.smali"
HELPER = "Lcom/apple/android/music/player/VivoCarLyrics;"
ATOMIC_BODY = (
    "iget-object v0, p2, LJ4/k2$e;->a:LK4/B$b;\n"
    "iget-object v0, v0, LK4/B$b;->a:LK4/B$c;\n"
    "iget-object v0, v0, LK4/B$d;->a:Ljava/lang/String;\n"
    "invoke-static {v0}, " + HELPER + "->onAtomicControllerConnected(Ljava/lang/String;)V\n"
)
SPECS = (
    (MANAGER, "public final P(Lz3/w;I)V",
     "onNativeMediaItem(Ljava/lang/Object;)V", "{p1}",
     "31a25e9f4285cc2bf738fcb738fbacf91373eadf35e0ed5c0afcc5dc0f4e7dc8",
     "if-nez p2, :cond_4", "iget-object p2, p0, Lcom/apple/android/music/player/O;->j:Lz3/w;"),
    (MANAGER,
     "public final onCurrentItemChanged(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "onCurrentItemChanged(Ljava/lang/Object;Ljava/lang/Object;)V", "{p0, p3}",
     "6a9f600318f3ec946ce6e0b789b3957298c4b1b13d75f67bd382e964f8fd098e",
     "invoke-static {p1, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "new-instance v1, Lcom/apple/android/music/player/O$e;"),
    (MANAGER,
     "public final onMetadataUpdated(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "onMetadataUpdated(Ljava/lang/Object;Ljava/lang/Object;)V", "{p0, p2}",
     "8286de7cfe258dab19e2a08569731037fca33f789dacdda6fe7a638595bd2e45",
     "invoke-static {p2, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "new-instance v0, Lcom/apple/android/music/player/O$g;"),
    (MANAGER,
     "public final onPlaybackError(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/MediaPlayerException;)V",
     "onPlaybackError(Ljava/lang/Object;)V", "{p0}",
     "7602fe780173d07565b18d68a05bf39e61f539ffdd534272a245f92334ca8cbf",
     "invoke-static {p2, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "invoke-virtual {p2}, Ljava/lang/Object;->toString()Ljava/lang/String;"),
    (MANAGER, "public final seekTo(J)V",
     "onSeek(Ljava/lang/Object;J)V", "{p0, p1, p2}",
     "fcc9193c210b670383b1501af23b2392cbbc6daa40e02b50097cdd00cd1926fd",
     "invoke-interface {v0, p1, p2}, Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "->seekToPosition(J)V",
     "new-instance p1, Lcom/apple/android/music/player/O$w;"),
    (CONNECTION, "public final o(LJ4/k2;LJ4/k2$e;)V",
     "onAtomicControllerConnected(Ljava/lang/String;)V", "{v0}",
     "09461f8838450e0f488f71975cae21b1408fa335c1d860828e74180d2460b40d",
     "iget-object p0, p0, LJ4/z2;->i:Ljava/lang/String;", "return-void"),
)


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def target_path(root, target):
    paths = list(root.glob("smali*/" + target))
    assert len(paths) == 1, "Lyrics target must exist in exactly one dex: " + target
    path = paths[0]
    assert path.relative_to(root).parts[0] == "smali_classes2", (
        "Lyrics target moved dex: " + str(path)
    )
    return path


def verify_method(text, spec, unpatched=False):
    target, signature, helper, registers, stock_hash, before, after = spec
    expected_class = "L" + target.removesuffix(".smali") + ";"
    assert re.search(r"^\.class [^\n]* " + re.escape(expected_class) + r"$", text, re.M), (
        "Wrong case-sensitive lyrics class: " + target
    )
    blocks = re.findall(
        r"(?ms)^\.method " + re.escape(signature) + r"\n.*?^\.end method$", text
    )
    assert len(blocks) == 1, "Lyrics method must exist exactly once: " + signature
    block = code(blocks[0])
    marker = HELPER + "->" + helper
    expected = 0 if unpatched else 1
    assert text.count(marker) == expected, "Unexpected helper count: " + helper
    call = "invoke-static " + registers + ", " + marker + "\n"
    inserted = ATOMIC_BODY if target == CONNECTION else call
    if not unpatched:
        assert block.count(inserted) == 1, "Lyrics helper argument registers drifted: " + helper
        assert before + "\n" + inserted + after + "\n" in block, (
            "Lyrics helper moved outside its stock seam: " + helper
        )
        block = block.replace(inserted, "", 1)
        if target == CONNECTION:
            assert ".locals 1\n" in block, "Atomic hook must use exactly one extra local"
            block = block.replace(".locals 1\n", ".locals 0\n", 1)
    assert hashlib.sha256(block.encode()).hexdigest() == stock_hash, (
        "Lyrics hook must preserve all stock instructions, labels and locals: " + helper
    )


def verify_tree(root, unpatched=False):
    for spec in SPECS:
        path = target_path(root, spec[0])
        verify_method(path.read_text(encoding="utf-8"), spec, unpatched=unpatched)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded_root", type=Path)
    parser.add_argument("--unpatched", action="store_true")
    args = parser.parse_args()
    verify_tree(args.decoded_root, unpatched=args.unpatched)
    print("1611 six stock-preserving lyrics hooks verified")


if __name__ == "__main__":
    main()
