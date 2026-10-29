# Apple Music vivo Car and Atomic Player Lyrics

Private, manually triggered GitHub Actions workflow for analyzing and rebuilding the user-provided Apple Music APKM with shared lyrics support for vivo JoviInCar and Atomic Player, plus standalone title correction.

## Scope

- The original APK is kept out of Git history and uploaded only as size-limited parts.
- Analysis runs in GitHub Actions; no Android SDK, apktool, jadx, or signing tools are installed on the local Mac.
- Rebuild is opt-in and produces a test APK signed by a fixed PKCS12 key stored in GitHub Secrets.
- The pinned test certificate lets later builds update one another, but it cannot replace Apple's signed package or older builds signed with a different temporary key.
- One in-process lyrics state machine (`VivoCarLyrics`) loads and parses Apple Music's private lyrics. The full timestamped LRC is published once per track through the `vivomusicmix.*` `lrc_change` session-Extras event; Atomic Player, the vivo head unit and the cluster all consume that one event (the car app cuts lines itself). The legacy `music.media.extras.*` head-unit keys, which 车联 6.0.8.3 never reads, were dropped in r46.
- Current-line changes only touch session Extras. `MediaMetadata` is never rebuilt and the `MediaItem` is never republished by the helper, so the native progress bar and artwork stay intact.
- `MediaPlaybackService` advertises `com.vivo.musicwidgetmix.support.service` so Atomic Player selects its cooperation controller, and `vivomusicmix.media.metadata.support_event` is ORed in place to `7|8|16|64` (transport + lyrics + seek/time + playlist entry). Bit 16 is what makes Atomic Player render the progress bar, and bit 64 is what makes its 播放列表 entry react at all — the cooperation controller reads the value verbatim and the entry is a silent no-op without it (r55, found by decompiling Atomic Player 6.2.5.6; see §8 of the upgrade guide).
- Instrument-cluster (`ucar.media.metadata.*`) publishing was removed in r37; `ClusterLyricsPaginator` is kept compiled but unused.
- Current baseline: r38, GitHub Release `v1.0.0-build-83`, helper marker `vivo-car-atomic-seek-bit-r38-2026-09-03`. Head-unit lyrics, Atomic Player lyrics and the Atomic progress bar are all confirmed working in the car (2026-09-04); r38 is the frozen baseline for future ports.
- The build is self-contained: title/artist correction lives in the helper DEX and needs no Xposed-style module or wrapper shell. The current port targets Apple Music 7.0.0-beta 1611 and corrects both title and artist display, with mainland-first StoreFront fallback (`cn` -> `tw` -> `hk`) and Simplified Chinese conversion. Its bounded persistent cache is isolated as `cn_v1`; ordinary account, playback, and lyrics requests remain untouched. The current helper marker is `vivo-car-cn-track-r55-1611-2026-10-29`; the 1611 port's CI builds are #173 (r50), #174 (r51, which drops the per-line empty-Extras heartbeat) and the r52 rebuild that restores the inline cover bitmap, all GitHub Releases with the signed test APK. The previous release #170 was r47 on 1609. Everything is CI-verified only — no 1611 build has been validated on a device yet.
- r47 fixes the head-unit timestamp prefix by shipping one parseable `[mm:ss.cc]text` row per line (inline stamps and line separators stripped, `^` mapped to `＾`, header tags dropped only on a whole-row match, untimed rows no longer merged, timestamps clamped per row instead of squeezing the timeline). r48 terminates every row — the last one included — with CRLF: 车联's reader (`ub/e.e`, verified in smali) takes `substring(start, length-1)` whenever a row does not end with a line break, so an LF-only payload loses the last character of every line and drops one-character lines, and an all-empty parse makes the reader fall back to raw rows whose literal `[mm:ss.cc]` prefixes then reach the screen.
- The head-unit album art showing the previous track's cover is a 车联 defect, not an Apple Music one: the async cover compression callback (`jg/e$d.a(player, songId, url, bitmap)`) compares only the player package, so a late result for the previous track rewrites the global cover holder and is pushed with `ILauncherManager.A3(...)`. The instrument cluster is unaffected because its CarLife Intent always embeds the current bitmap. Deferring lyrics and republishing MediaItem stay dead ends. r50 tried to fix the head-unit cover from the Apple Music side by stripping the inline bitmaps at `MediaMetadataCompat` construction (`J4/t.n`), which does make 车联 skip `MusicCoverStore` — but it also starves the instrument cluster, whose `HudManager` only reads `CarMusicInfo.getCoverBitmap()` and never re-notifies after the URL cover arrives (r52 reverts it). The two displays cannot both be right from the app side; changing 车联 still needs vivo's signing key. See §8.1 and §9 of the upgrade guide.
- The AM++ / NPatch route was abandoned on 2026-10-13; its scripts, workflow inputs and artifacts were removed.

## KuWo bridge prototype

`kuwo-bridge/` is an independent companion app whose application id is
`cn.kuwo.player`, one of the KuWo package names recognized by the vivo car app.
It does not replace or modify the official Apple Music installation. Instead, it
reads the official Apple Music media session through Android notification access,
publishes a proxy media session under the KuWo package, and forwards playback
controls back to Apple Music.

The bridge first reads lyric text exposed by Apple Music's public media-session
metadata and sends it through the media-session Extras keys used by the car
integration. A manually pasted LRC remains only as a fallback for protocol
testing when Apple Music exposes a lyrics-available flag but not the lyric body.
Track metadata and artwork are only republished when the track changes; seek,
rewind, and fast-forward update the lyric line immediately.

### Test flow

1. Run the **Build KuWo media bridge prototype** workflow and download its APK artifact.
2. Install the APK without installing KuWo itself.
3. Open **酷我音乐**, enable notification access, then play a track in official Apple Music.
4. Confirm the status says Apple Music lyrics were read automatically. If the
   official session exposes only a lyrics-available flag, paste an LRC file as a
   fallback protocol test.
5. Connect the phone to the vivo car system and select the KuWo music source if needed.

This is a compatibility prototype. Whether a specific car firmware accepts a
third-party proxy session must be verified on the target vehicle.

## Workflow

1. The original APKM is unzipped locally into `payload.tar` (`input/splits/base.apk` and `split_config.*.apk`), then stored as `payload.tar.part.*`; GitHub Actions merges the splits with APKEditor.
2. Run **Actions -> APK analysis and rebuild** with `rebuild=false` and review the `apk-results-<run>-report` artifact.
3. Run again with `rebuild=true` once `cloud-patch/` is ready; a successful run publishes Release `v1.0.0-build-<run>`.
4. Verify the APK's checksum and pinned signature, then update with `adb install -r`.
5. **Actions -> Decompile vivo APK** decompiles a vivo-side APK stored as a GitHub Release (`atomic-apk-6.2.5.6` for Atomic Player, `carnetworking-apk-6.0.8.3` for the car app) when the vivo side needs to be inspected.

All workflows are `workflow_dispatch` only and must exist on `main` to be triggered by name.
Rebuilds require the repository secrets `ANDROID_SIGNING_KEY_BASE64` and
`ANDROID_SIGNING_PASSWORD`; the certificate digest is pinned in
`config/signing-cert-sha256.txt` so an accidental key change fails the build.

## Future Apple Music updates

- [Future APK upgrade and porting guide (新版 APK 适配与升级指南)](docs/FUTURE_APK_UPGRADE_GUIDE.zh-CN.md) — the single document for porting: injection points, per-version reflection names, upgrade steps, vivo protocol fields, verified dead ends, signing limits

For a new Apple Music version, hand the new APKM plus this guide to the AI.
The obfuscated classes and Smali hook locations must be rediscovered for every
version; class names are not stable interfaces, but the protocol fields,
capability bits, and the six hook semantics are.
