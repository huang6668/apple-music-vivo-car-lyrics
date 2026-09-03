# 交给下一次 AI 的任务模板

Apple Music 出新版本时，把下面这段话、新版 APK 和本仓库一起交给 AI。替换尖括号中的内容即可。

```text
我要把新版 Apple Music Android APK 移植本仓库已有的 vivo 车机歌词 + 原子随身听歌词/进度条功能。

输入：
- 新版 APK：<APK 路径或上传文件名>
- 仓库：huang6668/apple-music-vivo-car-lyrics（当前基线 r38，见 docs/APK_UPDATE_GUIDE.zh-CN.md 第 1 节）
- 必读文档（按顺序）：
  1. docs/APK_UPDATE_GUIDE.zh-CN.md   —— 移植手册，第 0 节是路线图，第 3 节协议字段必须原样保留，第 4/5 节是定位配方
  2. docs/KNOWN_ISSUES.zh-CN.md       —— 已验证的死路，不要重试
  3. cloud-patch/                     —— 现有补丁、辅助类、apply.sh / rebuild.sh、契约测试

环境铁律：
1. 本地 Mac 不安装 Java / Android SDK / apktool / jadx / Gradle；只用系统自带的 unzip、strings、grep、split、shasum、python3 做只读检查，分析与构建全部在 GitHub Actions 里完成。
2. git push / gh 之前先 export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897；触发 workflow 前用 git rev-parse HEAD origin/<branch> 确认已推送。
3. 在新分支上工作，构建成功并经我确认后再合并 main。workflow_dispatch 只能触发已存在于 main 的 workflow。

要做的事（对应指南第 0 节路线图）：
A. 记录新 APK 的 SHA-256、包名、versionName、versionCode；按指南 7.1 把 APK 切片装进 payload.tar.part.*，更新 payload.sha256 和 workflow 里的 APK_NAME。
B. 先只分析：gh workflow run "APK analysis and rebuild" --ref <branch> -f rebuild=false，下载 apk-results-<run>-report。
C. 用指南第 4 节表格逐条重新定位辅助类的反射目标，用第 5 节重新定位六个 Hook：
   - onNativeMediaItem：原生 MediaItem 发布路径，守卫之后、hashCode 之前
   - onCurrentItemChanged / onMetadataUpdated / onPlaybackError：MediaPlayerController.Listener 回调
   - onSeek：紧跟 MediaPlayerController.seekToPosition(long) 之后，传同一个目标毫秒
   - onAtomicControllerConnected：Media3 onPostConnect，从 ControllerInfo 取包名
   给我一张"6.5.2 旧名 → 新版名"对照表，说明每条是怎么确认的。
D. 修改 VivoCarLyrics.java 反射目标、apple-vivo-car-lyrics.patch、apply.sh、rebuild.sh 的路径/签名/marker；更新 BUILD_MARKER；本地 python3 cloud-patch/tests/verify_source_contract.py 必须通过。
E. 推送后 gh workflow run "APK analysis and rebuild" --ref <branch> -f rebuild=true，等 Release v1.0.0-build-N 产出。
F. 在指南第 13 节追加变更记录；如有新的未解决问题写入 KNOWN_ISSUES。

必须保持不变的协议（指南第 3 节）：
- 车机 Session Extras：music.media.extras.LYRIC / LYRIC_IS_ALLOWED / NOTICE_CAR
- Manifest：MediaPlaybackService 的 MediaBrowser intent-filter 里必须有 com.vivo.musicwidgetmix.support.service（apply.sh 自动插入）
- MediaMetadata Extras：vivomusicmix.media.metadata.support_event 原位 OR 入 7|8|16（=31），只在原生 MediaItem 发布路径中做
- Session Extras：vivomusicmix.meida.extra.key.action / vivomusicmix.extra.key.meidia_id / vivomusicmix.extra.key.lyric（拼写不能改）
- 逐句更新只改 Session Extras，绝不重建 MediaMetadata、绝不重发 MediaItem
- 不改任何音频解码 / 输出 / 焦点 / 码率代码
- 固定测试签名（ANDROID_SIGNING_KEY_BASE64 / ANDROID_SIGNING_PASSWORD / config/signing-cert-sha256.txt）不得轮换

最终给我：
- 修改摘要与旧名→新名对照表
- GitHub Actions 运行链接、Release 链接、APK SHA-256
- 安装 / 签名 / 卸载风险
- 指南第 9 节实车测试清单中哪些项未验证（一律标"待实车验证"）

真实性要求：
- 没有实车验证时，必须写"待实车验证"，不能宣称功能已恢复。
- 遇到新版结构变化先分析再改，不要为了构建通过而删掉 apply.sh / rebuild.sh / verify_source_contract.py 里的校验或静默吞掉异常。
- 不要声称普通外挂 App 能跨进程读取 Apple Music 私有同步歌词，也不要声称新签名 APK 可以直接覆盖官方包。
- 原子随身听内部逻辑有疑问时，运行 "Decompile vivo APK" workflow 看源码，不要凭猜测改能力位。
```
