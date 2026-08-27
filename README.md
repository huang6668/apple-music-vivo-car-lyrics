# Apple Music vivo Car Lyrics

Private, manually triggered GitHub Actions workflow for analyzing and rebuilding the user-provided Apple Music 6.5.2 APK against the vivo JoviInCar lyrics adaptation notes.

## Scope

- The original APK is kept out of Git history and uploaded only as size-limited parts.
- Analysis runs in GitHub Actions; no Android SDK, apktool, jadx, or signing tools are installed on the local Mac.
- Rebuild is opt-in and produces a newly signed test APK. It cannot replace Apple's signed package.

## KuWo bridge prototype

`kuwo-bridge/` is an independent companion app whose application id is
`cn.kuwo.player`, one of the KuWo package names recognized by the vivo car app.
It does not replace or modify the official Apple Music installation. Instead, it
reads the official Apple Music media session through Android notification access,
publishes a proxy media session under the KuWo package, and forwards playback
controls back to Apple Music.

The prototype accepts a manually pasted LRC file so the car lyrics transport can
be tested without depending on Apple Music's private lyric classes. Lyrics are
sent through the media-session Extras keys used by the car integration. Track
metadata and artwork are only republished when the track changes; seek, rewind,
and fast-forward update the lyric line immediately.

### Test flow

1. Run the **Build KuWo media bridge prototype** workflow and download its APK artifact.
2. Install the APK without installing KuWo itself.
3. Open **酷我音乐**, enable notification access, then play a track in official Apple Music.
4. Paste an LRC file and tap **发送歌词到代理会话** for the first protocol test.
5. Connect the phone to the vivo car system and select the KuWo music source if needed.

This is a compatibility prototype. Whether a specific car firmware accepts a
third-party proxy session must be verified on the target vehicle.

## Workflow

1. Upload `input/SHA256SUMS` and `input/parts/*` for the APK.
2. Run **Actions -> APK analysis and rebuild** with `rebuild=false`.
3. Review the location report before enabling rebuild.
4. Run again with `rebuild=true` only after an explicit patch has been added under `patches/`.

The workflow is deliberately `workflow_dispatch` only. It does not run for pull requests or pushes.
