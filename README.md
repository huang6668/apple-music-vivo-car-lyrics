# Apple Music vivo Car and Atomic Player Lyrics

Private, manually triggered GitHub Actions workflow for analyzing and rebuilding the user-provided Apple Music 6.5.2 APK with shared lyrics support for vivo JoviInCar and Atomic Player.

## Scope

- The original APK is kept out of Git history and uploaded only as size-limited parts.
- Analysis runs in GitHub Actions; no Android SDK, apktool, jadx, or signing tools are installed on the local Mac.
- Rebuild is opt-in and produces a test APK signed by a fixed PKCS12 key stored in GitHub Secrets.
- The pinned test certificate lets later builds update one another, but it cannot replace Apple's signed package or older builds signed with a different temporary key.
- One in-process lyrics state machine (`VivoCarLyrics`) loads and parses Apple Music's private lyrics. The head unit receives the current line through `music.media.extras.*` session Extras; Atomic Player receives the full timestamped LRC through `vivomusicmix.*` session Extras.
- Current-line changes only touch session Extras. `MediaMetadata` is never rebuilt and the `MediaItem` is never republished by the helper, so the native progress bar and artwork stay intact.
- `MediaPlaybackService` advertises `com.vivo.musicwidgetmix.support.service` so Atomic Player selects its cooperation controller, and `vivomusicmix.media.metadata.support_event` is ORed in place to `7|8|16` (transport + lyrics + seek/time). Bit 16 is what makes Atomic Player render the progress bar on that path (found by decompiling Atomic Player 6.2.5.6; see `docs/KNOWN_ISSUES.zh-CN.md`).
- Instrument-cluster (`ucar.media.metadata.*`) publishing was removed in r37; `ClusterLyricsPaginator` is kept compiled but unused.
- Current baseline: r38, GitHub Release `v1.0.0-build-83`, helper marker `vivo-car-atomic-seek-bit-r38-2026-09-03`. Progress bar fix awaits in-car confirmation.

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

1. The original APK lives inside `payload.tar.part.*` (`input/parts/*` + `input/SHA256SUMS` + `scripts/ci/*`), verified by `payload.sha256`.
2. Run **Actions -> APK analysis and rebuild** with `rebuild=false` and review the `apk-results-<run>-report` artifact.
3. Run again with `rebuild=true` once `cloud-patch/` is ready; a successful run publishes Release `v1.0.0-build-<run>`.
4. **Actions -> Decompile vivo APK** decompiles the Atomic Player APK stored under Release `atomic-apk-6.2.5.6` when the vivo side needs to be inspected.

All workflows are `workflow_dispatch` only and must exist on `main` to be triggered by name.
Rebuilds require the repository secrets `ANDROID_SIGNING_KEY_BASE64` and
`ANDROID_SIGNING_PASSWORD`; the certificate digest is pinned in
`config/signing-cert-sha256.txt` so an accidental key change fails the build.

## Future Apple Music updates

- [APK update and porting guide](docs/APK_UPDATE_GUIDE.zh-CN.md) — roadmap, stable protocol contract, reflection/hook relocation recipes, payload packaging, CI commands
- [Prompt template for the next AI](docs/AI_HANDOFF_PROMPT.zh-CN.md)
- [Known issues and dead ends](docs/KNOWN_ISSUES.zh-CN.md)

For a new Apple Music version, hand the new APK plus the prompt template to the AI.
The obfuscated classes and Smali hook locations must be rediscovered for every
version; the 6.5.2 class names and line numbers are not stable interfaces, but the
protocol fields, capability bits, and the six hook semantics are.
