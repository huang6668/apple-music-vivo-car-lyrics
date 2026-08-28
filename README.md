# Apple Music vivo Car and Atomic Player Lyrics

Private, manually triggered GitHub Actions workflow for analyzing and rebuilding the user-provided Apple Music 6.5.2 APK with shared lyrics support for vivo JoviInCar and Atomic Player.

## Scope

- The original APK is kept out of Git history and uploaded only as size-limited parts.
- Analysis runs in GitHub Actions; no Android SDK, apktool, jadx, or signing tools are installed on the local Mac.
- Rebuild is opt-in and produces a test APK signed by a fixed PKCS12 key stored in GitHub Secrets.
- The pinned test certificate lets later builds update one another, but it cannot replace Apple's signed package or older builds signed with a different temporary key.
- One in-process lyrics state machine loads and parses Apple Music lyrics. Car lyrics receive only the current line during playback; Atomic Player receives the complete timestamped LRC on track or lyrics changes, immediately after its controller connects, and through a low-frequency replay fallback.
- Apple Music's exported `MediaPlaybackService` also advertises `com.vivo.musicwidgetmix.support.service`, so Atomic Player selects its cooperation controller instead of the generic controller that has no lyrics support.

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

1. Upload `input/SHA256SUMS` and `input/parts/*` for the APK.
2. Run **Actions -> APK analysis and rebuild** with `rebuild=false`.
3. Review the location report before enabling rebuild.
4. Run again with `rebuild=true` only after the reviewed patch under `cloud-patch/` is ready.

The workflow is deliberately `workflow_dispatch` only. It does not run for pull requests or pushes.
Rebuilds require the repository secrets `ANDROID_SIGNING_KEY_BASE64` and
`ANDROID_SIGNING_PASSWORD`; the certificate digest is pinned in
`config/signing-cert-sha256.txt` so an accidental key change fails the build.

## Future Apple Music updates

- [APK update and handoff guide](docs/APK_UPDATE_GUIDE.zh-CN.md)
- [Prompt template for the next AI](docs/AI_HANDOFF_PROMPT.zh-CN.md)

For a new Apple Music version, provide the new APK and both documents to the AI.
The obfuscated classes and Smali hook locations must be rediscovered for every
version; the 6.5.2 class names and line numbers are not stable interfaces.
