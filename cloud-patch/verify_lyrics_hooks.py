#!/usr/bin/env python3
"""Pin the six existing lyrics hooks to the cloud-analyzed 1614 stock methods."""

import argparse
import hashlib
from pathlib import Path
import re


MANAGER = "com/apple/android/music/player/N.smali"
CONNECTION = "com/apple/android/music/player/c0.smali"
HELPER = "Lcom/apple/android/music/player/VivoCarLyrics;"
ATOMIC_BODY = (
    "iget-object v0, p2, LJ4/m2$e;->a:LK4/B$b;\n"
    "iget-object v0, v0, LK4/B$b;->a:LK4/B$c;\n"
    "iget-object v0, v0, LK4/B$d;->a:Ljava/lang/String;\n"
    "invoke-static {v0}, " + HELPER + "->onAtomicControllerConnected(Ljava/lang/String;)V\n"
)
SPECS = (
    (MANAGER, "public final P(Lz3/w;I)V",
     "onNativeMediaItem(Ljava/lang/Object;)V", "{p1}",
     "adb591aea809d6630858decca8bd4c7f163e2b46742c5bc46a3dfe953eb8cf14",
     "if-nez p2, :cond_4", "iget-object p2, p0, Lcom/apple/android/music/player/N;->j:Lz3/w;"),
    (MANAGER,
     "public final onCurrentItemChanged(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "onCurrentItemChanged(Ljava/lang/Object;Ljava/lang/Object;)V", "{p0, p3}",
     "f2e7fd87ac4971d505a5b3e136bdca1002fa7b7bbb4d6f6adce891815549caf6",
     "invoke-static {p1, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "new-instance v1, Lcom/apple/android/music/player/N$e;"),
    (MANAGER,
     "public final onMetadataUpdated(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "onMetadataUpdated(Ljava/lang/Object;Ljava/lang/Object;)V", "{p0, p2}",
     "be6f1d309632977f3115d6e6f6dbbb73719dd0738c94b05c9f601e527a9fe4d9",
     "invoke-static {p2, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "new-instance v0, Lcom/apple/android/music/player/N$g;"),
    (MANAGER,
     "public final onPlaybackError(Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "Lcom/apple/android/music/playback/model/MediaPlayerException;)V",
     "onPlaybackError(Ljava/lang/Object;)V", "{p0}",
     "6f86064caee75b2a69c0d6ea57ad5b34f478d114f3fe2e31fbafea8e5b07d055",
     "invoke-static {p2, v0}, Lkotlin/jvm/internal/l;->g(Ljava/lang/Object;Ljava/lang/String;)V",
     "invoke-virtual {p2}, Ljava/lang/Object;->toString()Ljava/lang/String;"),
    (MANAGER, "public final seekTo(J)V",
     "onSeek(Ljava/lang/Object;J)V", "{p0, p1, p2}",
     "c93220a4fa3520dd8b2fdafa45aed2255a4ecfb9ae04c8654fbffa7b8f62d721",
     "invoke-interface {v0, p1, p2}, Lcom/apple/android/music/playback/controller/MediaPlayerController;"
     "->seekToPosition(J)V",
     "new-instance p1, Lcom/apple/android/music/player/N$w;"),
    (CONNECTION, "public final o(LJ4/m2;LJ4/m2$e;)V",
     "onAtomicControllerConnected(Ljava/lang/String;)V", "{v0}",
     "0f116ceca935577d117b6dd32343ec5b3d0044d061dba3e169c12ae550db3d06",
     "iget-object p0, p0, LJ4/B2;->i:Ljava/lang/String;", "return-void"),
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
    print("1614 six stock-preserving lyrics hooks verified")


if __name__ == "__main__":
    main()
