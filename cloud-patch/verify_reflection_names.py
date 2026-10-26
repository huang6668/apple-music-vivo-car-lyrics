#!/usr/bin/env python3
"""Preflight the version-specific reflection names VivoCarLyrics depends on.

The smali hooks are pinned by ``verify_lyrics_hooks.py`` / ``verify_title_hook.py`` /
``patch_item_title.py`` / ``patch_catalog_region.py``. The names this script checks own
the *other* half of the port: the class, field and method names the helper looks up by
string at runtime. They are not covered by any other verifier and fail silently (the
helper just keeps the stock value), so a new APK version must re-confirm every entry.

Style note: the whole project does not use only unittest but the parent asked
for a small test, so the fixtures here are synthetic smali text.

Usage:
    python3 verify_reflection_names.py <decoded_root> [--preflight]

``--preflight`` is what ``apply.sh`` runs against the freshly decoded stock tree before
``patch`` touches anything. Without it the same assertions run in "patched" mode, which
only means the report header differs: these names live in the helper DEX, so patching
smali must never change them.
"""

import argparse
import re
import sys
from pathlib import Path


# Each entry: (label, smali file, regex that must match, what breaks if it stops matching)
SPECS = (
    (
        "MediaItem metadata class",
        "smali/z3/y.smali",
        (
            r"^\.field public final a:Ljava/lang/CharSequence;$",
            r"^\.field public final b:Ljava/lang/CharSequence;$",
        ),
        "writeCorrectedTrack cannot write the corrected title/artist",
    ),
    (
        "MediaItem metadata extras bundle",
        "smali/z3/y.smali",
        (r"^\.field public final J:Landroid/os/Bundle;$",),
        "applyTitleCorrection cannot read MediaItem metadata",
    ),
    (
        "MediaItem -> metadata field",
        "smali/z3/w.smali",
        (r"^\.field public final d:Lz3/y;$",),
        "applyTitleCorrection cannot reach the metadata",
    ),
    (
        "metadata -> PlaybackItem converter",
        "smali_classes2/com/apple/android/music/player/N.smali",
        (r"^\.method public static final b\(Lz3/y;\)Lcom/apple/android/music/model/PlaybackItem;$",),
        "currentPlaybackItem probes fail and the player page keeps the stock name",
    ),
    (
        "notification refresh method",
        "smali/J4/b3.smali",
        (r"^\.method public final g\(LJ4/k2;Z\)Lag/a\$a;$",),
        "refreshTitleNotification cannot rebuild the stock notification",
    ),
    (
        "notification session class",
        "smali/J4/k2.smali",
        (r"^\.class public LJ4/k2;$",),
        "the notification refresh chain resolves the wrong session class",
    ),
    (
        "notification session -> service field",
        "smali/J4/z2.smali",
        (r"^\.field public final f:Landroid/content/Context;$",),
        "refreshTitleNotification cannot walk session.w2.f to the service",
    ),
    (
        "service -> session field",
        "smali_classes2/com/apple/android/music/player/MediaPlaybackService.smali",
        (
            r"^\.field public volatile y:LJ4/x1\$b;$",
            r"^\.method public final j\(LJ4/k2\$e;\)LJ4/x1\$b;$",
        ),
        "the service/session identity check in refreshTitleNotification fails closed",
    ),
    (
        "service base class",
        "smali/J4/x1.smali",
        (r"^\.super LJ4/b3;$",),
        "MediaPlaybackService is no longer a J4.Z2, so the g() lookup fails",
    ),
    (
        "player -> session manager field",
        "smali_classes2/com/apple/android/music/player/k0.smali",
        (r"^\.field public b:LJ4/x1\$b;$",),
        "refreshTitleNotification cannot reach the session from the manager",
    ),
    (
        "manager current item",
        "smali_classes2/com/apple/android/music/player/O.smali",
        (
            r"^\.field public final a:Lcom/apple/android/music/player/k0;$",
            r"^\.method public final c\(\)Lz3/w;$",
        ),
        "currentMediaItem returns nothing and title correction never applies",
    ),
    (
        "player page rebind setters",
        "smali_classes2/q8/la.smali",
        (
            r"^\.method public abstract q0\(Lcom/apple/android/music/model/PlaybackItem;\)V$",
            r"^\.field public final o0:Lcom/apple/android/music/common/views/CustomTextView;$",
            r"^\.field public final p0:Lcom/apple/android/music/common/views/CustomTextView;$",
        ),
        "the player page never refreshes after an async title/artist arrives",
    ),
    (
        "player page subtitle source",
        "smali_classes2/com/apple/android/music/player/b1.smali",
        (r"^\.method public static y0\(Lcom/apple/android/music/model/PlaybackItem;\)Ljava/lang/String;$",),
        "the player page artist hook has no source method to read",
    ),
    (
        "now playing item class",
        "smali_classes2/com/apple/android/music/playback/model/StoreMediaItem.smali",
        (
            r"^\.class public final Lcom/apple/android/music/playback/model/StoreMediaItem;$",
            r"^\.field artistName:Ljava/lang/String;$",
            r"^\.field title:Ljava/lang/String;$",
        ),
        "the now playing item's title/artist cannot be corrected",
    ),
    (
        "catalog api holder",
        "smali_classes2/com/apple/android/music/mediaapi/repository/MediaApiRepositoryHolder.smali",
        (r"^\.field public static final Companion:Lcom/apple/android/music/mediaapi/repository/MediaApiRepositoryHolder\$Companion;$",),
        "findCatalogApi cannot reach the companion",
    ),
    (
        "catalog api accessor",
        "smali_classes2/com/apple/android/music/mediaapi/repository/MediaApiRepositoryHolder$Companion.smali",
        (r"^\.method public final getMediaApi\(\)Lw9/a;$",),
        "findCatalogApi returns no instance and every catalog query fails",
    ),
    (
        "catalog query method",
        "smali_classes2/w9/a.smali",
        (r"^\.method public abstract F\(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;\)Ljava/lang/Object;$",),
        "CatalogQueryMethod has no F(String, Map, Continuation) to call",
    ),
)


def smali_root(root):
    return Path(root)


def read(root, relative):
    path = Path(root) / relative
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8")


def verify(root):
    """Returns (checked_lines, problems, notes)."""
    checked = []
    problems = []
    notes = []
    for label, relative, patterns, impact in SPECS:
        text = read(root, relative)
        if text is None:
            problems.append("Missing smali file for %s: %s" % (label, relative))
            continue
        for pattern in patterns:
            if not re.search(pattern, text, re.M):
                problems.append(
                    "%s: %s no longer matches %s -- %s" % (label, relative, pattern, impact)
                )
            else:
                checked.append("%s -> %s :: %s" % (label, relative, pattern))
    return checked, problems, notes


def verify_playback_item_setter(root):
    """The one name this script does *not* hardcode: whatever rebinds the item.

    1611 renamed the binding setter (``q0`` takes the PlaybackItem, ``r0`` takes the
    player model), so pin ``r0`` by string and fail the port. Assert the setter exists
    by signature instead, and report the name.
    """
    text = read(root, "smali_classes2/q8/la.smali")
    if text is None:
        return None, "Missing q8/la.smali for the PlaybackItem setter"
    matches = re.findall(
        r"^\.method public abstract (\w+)\(Lcom/apple/android/music/model/PlaybackItem;\)V$",
        text, re.M,
    )
    if len(matches) != 1:
        return None, ("Expected exactly one abstract PlaybackItem setter in q8/ma.smali, found %r" %
                      (matches,))
    return matches[0], None


# Class names the helper resolves by string at runtime, with the version they were
# confirmed against and what they are used for. These are informational: the objects
# they name are host classes a new APK may legitimately rename, and the fix is to
# update the helper, not to relax this script.
RUNTIME_CLASSES = (
    ("com.apple.android.music.AppleMusicApplication$a", "application/context for the disk cache"),
    ("com.apple.android.music.player.MediaPlaybackService", "notification refresh identity check"),
    ("com.apple.android.music.playback.model.StoreMediaItem", "now playing item fields"),
    ("com.apple.android.music.player.VivoCarLyrics", "helper itself"),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Decoded apktool root directory")
    parser.add_argument("--preflight", action="store_true",
                        help="Stock pre-patch mode (same assertions, different header)")
    args = parser.parse_args()

    if not (args.root / "AndroidManifest.xml").is_file():
        raise SystemExit("Not an apktool root: %s" % args.root)

    checked, problems, _ = verify(args.root)
    setter, setter_problem = verify_playback_item_setter(args.root)
    if setter_problem:
        problems.append(setter_problem)

    header = "stock preflight" if args.preflight else "patched tree"
    for line in checked:
        print("ok %s" % line)
    if setter:
        print("ok playback page item setter -> smali_classes2/q8/la.smali :: %s(PlaybackItem)V" %
              setter)
    for name, purpose in RUNTIME_CLASSES:
        if not name.startswith("com.apple.android.music.player.VivoCarLyrics"):
            print("note runtime class %s (%s) must be re-confirmed from the jadx report" %
                  (name, purpose))

    if problems:
        for problem in problems:
            print("FAIL %s" % problem, file=sys.stderr)
        raise SystemExit("Reflection name preflight failed (%s): %d problem(s)" %
                         (header, len(problems)))
    print("1611 reflection name preflight verified (%s): %d names" % (header, len(checked) + 1))


if __name__ == "__main__":
    main()
