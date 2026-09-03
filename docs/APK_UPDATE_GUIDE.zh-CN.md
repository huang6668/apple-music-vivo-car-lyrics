# Apple Music 车联与原子随身听歌词 APK 更新与交接指南

最后整理日期：2026-09-03（r38 基线）

本文档是给"拿到新版 Apple Music APK 的下一个 AI"看的移植手册。目标：只依靠本仓库和本文档，把车机歌词、原子随身听歌词和进度条这三项功能重新做到新版 Apple Music 上，并在 GitHub Actions 里构建出可安装的测试 APK。

**读本文档时的三条铁律：**

1. 混淆类名、字段名、Smali 行号（`P.smali`、`c0.smali`、`v3/t`、`E3.B2` 等）只是 6.5.2 的定位线索，每个新版本都必须重新定位，不能照抄。
2. 协议字段、能力位、Hook 语义、禁止事项（第 3、4、5、6 节）是跨版本稳定的，必须原样保留。
3. 所有构建与反编译都在 GitHub Actions 里做，本地 Mac 不安装 Java / SDK / apktool / jadx。

---

## 0. 快速路线图（新版 APK 到手后按顺序做）

| 步骤 | 做什么 | 产物 / 验收 |
|---|---|---|
| A | 记录新 APK 的 SHA-256、包名、版本；把 APK 装进 payload（第 7.1 节） | `payload.sha256` 更新，`payload.tar.part.*` 每片 < 25 MB |
| B | 只分析不重建：`gh workflow run "APK analysis and rebuild" -f rebuild=false` | 下载 `apk-results-<run>-report`，拿到 `focused-sources.tar.gz`、`location-hits.txt` |
| C | 用第 4 节的"定位配方"重新找出 6 个 Hook 点和全部反射目标 | 一张"旧名 → 新名"对照表 |
| D | 改 `VivoCarLyrics.java` 的反射目标；改 `apple-vivo-car-lyrics.patch`、`apply.sh`、`rebuild.sh` 中的类路径 / 方法签名 / marker | 本地 `python3 cloud-patch/tests/verify_source_contract.py` 通过 |
| E | 提交、推送（需代理），确认 `HEAD == origin/<branch>` 后 `gh workflow run ... -f rebuild=true` | Release `v1.0.0-build-N` 含 APK 与 sha256 |
| F | 交给用户实车测试（第 9 节清单），未实测的项目标"待实车验证" | 更新第 13 节变更记录、`docs/KNOWN_ISSUES.zh-CN.md` |

---

## 1. 已验证基线

- 原始 APK：Apple Music 6.5.2，包名 `com.apple.android.music`，versionCode `1586`
- 原始 APK SHA-256：`a05a36a5678015fd49d8c73aed2087e7a2f8f3232376733a2cf2f82623895736`
- 目标车联包：`com.vivo.car.networking` 6.0.8.3（JoviInCar 车机）
- 目标原子随身听：`com.vivo.musicwidgetmix` 6.2.5.6（APK 存放在本仓库 Release `atomic-apk-6.2.5.6`）
- 最新构建：r38 = GitHub Release `v1.0.0-build-83`，构建标识 `vivo-car-atomic-seek-bit-r38-2026-09-03`
- 签名：GitHub Secrets 里的固定 PKCS12 测试密钥，证书 SHA-256 pin 在 `config/signing-cert-sha256.txt`
- 辅助类在 6.5.2 中被加入为 `classes5.dex`（`rebuild.sh` 会自动选下一个未占用编号）

r38 实车状态：车机歌词、原子随身听歌词均正常；原子随身听进度条根因已定位并修复，待实车确认（见 `docs/KNOWN_ISSUES.zh-CN.md` 第 1 节）。

## 2. 要实现的行为

补丁代码运行在 Apple Music 自身进程中，从它的私有歌词对象获取完整歌词和时间轴，由同一个状态机（`VivoCarLyrics`）发布给 vivo 车机和原子随身听。

必须保留的行为：

1. 换歌后自动加载当前歌曲歌词，不需要用户打开歌词页。
2. 每 250 毫秒根据播放位置计算当前歌词行。
3. 快进或拖动进度时立即切换到目标歌词行，并在短延迟后用播放器实际位置校正。
4. 歌词行变化只更新 MediaSession Extras，**不重建 MediaMetadata**，否则车端每句都重新加载专辑图。
5. 原子随身听能力位只在 Apple Music 原生发布 MediaItem 的路径中**原位 OR 入**，辅助类绝不重发 MediaItem（会重置 PlaybackState，进度条消失）。
6. 不修改解码器、音频格式、AudioTrack、ExoPlayer 音频输出、音频焦点或码率选择。
7. 原子随身听只在换歌、完整歌词到达或状态变化时收到完整 LRC 事件；逐句更新必须把事件 action 清空。
8. 原子随身听建立控制器连接后，短延迟补发当前状态，并在播放期间低频重发。
9. r37 起不再向仪表盘（instrument cluster）推送 `ucar.media.metadata.*`；`ClusterLyricsPaginator` 保留编译但不再用于发布。

## 3. 协议字段（跨版本稳定，必须原样保留）

### 3.1 vivo 车机（JoviInCar）——写入 MediaSession Extras

```text
music.media.extras.LYRIC             = 当前歌词行（String）
music.media.extras.LYRIC_IS_ALLOWED  = true
music.media.extras.NOTICE_CAR        = true
```

发布方式：取 Apple Music 的 MediaSession 管理器，调用其"设置会话 Extras"的方法（6.5.2 中为 `P.a` → `k0`，方法 `j(Bundle)`）。Apple Music 的 Extras 更新是合并式的，所以每次逐句更新都要显式清空 `vivomusicmix.meida.extra.key.action`（见 3.2）。

**重要（2026-09-03 反编译车联 6.0.8.3 确认）：** 车联 6.0.8.3 并不读取上面三个 key，车机和仪表的歌词实际来自 3.2(c) 的原子随身听 `lrc_change` 整首 LRC 事件——车联在 `eg/l.java onExtrasChanged()` 里消费同一个事件并自己按时间切行。所以 3.2(c) 事件是车机歌词的真正来源，**不能删也不能改拼写**；3.1 的三个 key 保留是为了兼容其它车联版本。仪表长句截断与逐句封面重载是车联 `HudManager` 的固定逻辑，详见 `docs/KNOWN_ISSUES.zh-CN.md` 第 2 节。

### 3.2 vivo 原子随身听——两处写入

**(a) AndroidManifest：** 在 `com.apple.android.music.player.MediaPlaybackService` 已有的、含 `android.media.browse.MediaBrowserService` 的那个 `intent-filter` 里追加：

```text
com.vivo.musicwidgetmix.support.service
```

服务必须保持 `android:exported="true"`。这个 action 让原子随身听选择**合作控制器**（`c0`），歌词事件只有合作控制器才处理，所以 **action 必须加，不能撤**。`apply.sh` 会自动插入并校验它在整个 manifest 里只出现一次。

**(b) MediaMetadata Extras（原生 MediaItem 发布路径中原位 OR）：**

```text
vivomusicmix.media.metadata.support_event = 原值 | 7 | 8 | 16   （= 31）
```

能力位含义（来自原子随身听 6.2.5.6 反编译源码，`utils/b1.c(a, m) == ((a & m) == m)`）：

| bit | 用途 | 原子源码依据 |
|---|---|---|
| 1 / 2 / 4 | 基础播控（原子对未知应用默认 7） | `getSupportEvent()` 默认值 |
| 8 | 歌词 | `f5/n.java`、`f5/b.java`：`b1.c(P0(), 8)` |
| 16 | 进度条 / seek / 时间显示 | `t4/d0.java` `Y0()/Z0()`：`duration > 0 && b1.c(P0(), 16)` |
| 32 | 自定义颜色 / 互传 | `SettingsMainView`、锁屏组件 |
| 64 | 列表入口 | `MusicControlPanelView`、`f5/w0.java` |
| 128 | 未确认 | `f5/w0.java` |

合作控制器 `c0.k0()` **原样读取**这个值，不会自己补位；通用控制器 `y2` 才会根据 `ACTION_SEEK_TO` 自行算出 23。所以走合作路径时缺 16 位就永远 `--:--`——这就是 r10–r37 期间"加了 action 进度条就没"的真正原因。

**(c) MediaSession Extras（完整 LRC 事件）：**

```text
vivomusicmix.meida.extra.key.action   = "vivomusicmix.extra.lrc_change"（有事件时）/ ""（逐句更新时）
vivomusicmix.extra.key.meidia_id      = 当前公开 android.media.metadata.MEDIA_ID
vivomusicmix.extra.key.lyric          = 完整带时间戳 LRC
```

`meida` / `meidia` 是协议的真实拼写，不能改。切歌时先发一次空歌词事件（`lrc_change` + 新曲 ID + 空 lyric）清除原子内存中的上一首；公开 ID 尚未出现时用新队列项的 store ID 兜底，连兜底也没有就发空 ID。

### 3.3 vivo 侧的参考源码

需要查原子随身听或车联内部逻辑时，运行 workflow **Decompile vivo APK**（`.github/workflows/vivo-decompile.yml`，输入 `release_tag` 和 `label`），下载 `<label>-decompile-<run>` artifact。已上传的 APK：

| Release tag | 包 | label |
|---|---|---|
| `atomic-apk-6.2.5.6` | 原子随身听 `com.vivo.musicwidgetmix` 6.2.5.6 | `atomic`（默认） |
| `carnetworking-apk-6.0.8.3` | 车联 `com.vivo.car.networking` 6.0.8.3 | `carnetworking` |

原子随身听关键文件：

```text
com/vivo/musicwidgetmix/controller/c3.java   控制器工厂（根据包名 / manifest action 选控制器）
com/vivo/musicwidgetmix/controller/c0.java   合作控制器：k0() 读 support_event 与 DURATION；内部类 b 处理 lrc_change
com/vivo/musicwidgetmix/controller/y2.java   通用 MediaSession 控制器：j0() 自算 support_event
t4/d0.java                                   MusicWidgetManager：Y0()/Z0() 决定进度条是否显示；E0()/z1() 要求 lrc 的 mediaId 等于当前歌曲
view/SeekBarLayout.java                      refreshPosition()：!isSupportTimeInfo 时显示 --:--
com/vivo/musicwidgetmix/lrc/e.java           LRC 解析器（与车联 ub/e.java 同源）
utils/b1.java                                位运算工具 c(a, m)
```

车联关键文件：

```text
aa/b.java        协议常量（music.media.extras.*、ucar.media.metadata.*、vivomusicmix.*）
eg/l.java        MediaClientDelegate：c(MediaMetadata) 读 ucar.* 键；onExtrasChanged() 消费 lrc_change 事件
jg/e.java        MusicInfoHolder：g0()/h0() 设整首歌词，f25613f 为当前行，N() 组装 CarMusicInfo
fg/l.java        WholeLyricManager：按播放位置从整首 LRC 切出当前行
ub/e.java        LyricParseManager：LRC 解析
gg/d.java        HudManager：仪表发送（CarLife 路径截断 17 字 + "..."，每次附带封面）
gg/f.java        LauncherProxy：车机歌词页 N(whole)/P(line)
com/vivo/ucar/databus/ControlChannel.java    ucar 路径 sendMusicInfo()
```

原子随身听的 `MediaControllerCompat` 只是 `android.media.session.MediaController` 的薄包装，compat 层与框架层是同一个 session，不是两条通道。

## 4. 辅助类的反射目标与定位配方

辅助类 `cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java`（约 1860 行）通过反射访问 Apple Music 私有对象。下面按"用途 → 6.5.2 名称 → 怎么在新版里找"列出全部目标。新版必须逐条重新确认。

| 用途 | 6.5.2 名称 | 定位配方（在 jadx 输出 / focused-sources 中） |
|---|---|---|
| 播放管理器（Hook 宿主） | `com.apple.android.music.player.P`（`smali_classes2/.../P.smali`） | 实现 `MediaPlayerController.Listener`（含 `onCurrentItemChanged`、`onMetadataUpdated`、`onPlaybackError`），并有 `seekTo(J)` 调用 `MediaPlayerController.seekToPosition(J)` 的类 |
| 当前 MediaItem 获取 | `P.a()` 返回 `v3.t`（Media3 `MediaItem`） | 播放管理器里返回类型为 Media3 MediaItem 的无参方法 |
| 原生 MediaItem 发布方法 | `P.I(Lv3/t;I)V`，内部 `if-nez p2` 守卫后 `hashCode()` 再 `iput j` | 接收 MediaItem 的方法，内部比较 hashCode 后存入字段并触发 `onMediaMetadataChanged` |
| MediaItem → metadata | 字段 `t.d`（`MediaItem.mediaMetadata`） | Media3 `MediaItem` 类中类型为 `MediaMetadata` 的字段 |
| metadata → extras Bundle | 字段 `MediaMetadata.I` | Media3 `MediaMetadata` 中类型为 `Bundle` 的字段（Media3 源码中叫 `extras`） |
| MediaItem → mediaId | 字段 `t.a` | Media3 `MediaItem` 中的 `mediaId` String 字段 |
| MediaSession 管理器 | `P.a` 字段 → `k0` | 播放管理器持有的、包裹 Media3 `MediaSession` 的对象 |
| 设置会话 Extras | `k0.j(Bundle)` | 管理器里最终调用 `MediaSession.setSessionExtras(Bundle)` 的方法 |
| MediaPlayerController | `k0.h` | 管理器里类型为 `MediaPlayerController` 的字段；用于 `getCurrentPosition()` / `getDuration()` / `getPlaybackState()` |
| 主线程 Handler | `P.b` | 播放管理器里的 `Handler` 字段（延迟任务需在其 looper 上跑） |
| 歌词 ViewModel | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel` | 类名未混淆；确认 `loadLyrics(PlaybackItem)` 与 `getLyricsResult()` 仍在 |
| LiveData Observer 接口 | `androidx.lifecycle.L` | 反查 `getLyricsResult()` 返回的 LiveData 的 `observeForever` 参数类型 |
| TTML 行访问 | `com.apple.android.music.ttml.i`：`b()` 行数、`a(int)` 取行、`getBegin()`、`getHtmlLineText()`、`getSections()` | `ttml` 包里带 `getBegin`/`getHtmlLineText` 的行对象及其容器 |
| 队列项 → PlaybackItem | `com.apple.android.music.player.O.b(metadata)` | 把 MediaMetadata 转成 `PlaybackItem` 的静态转换器 |
| Application 实例 | `com.apple.android.music.AppleMusicApplication$a.c()` / `a()` | Kotlin companion 的静态获取方法 |
| 队列项字段 | `getItem()`、`getQueueId()`、`getPlaybackQueueId()`、`getPersistentId()`、`getSubscriptionStoreId()`、`getDurationInMillis()`、`hasLyrics()`、`hasCustomLyrics()`、`getCustomLyrics()` | `PlayerQueueItem` / `PlaybackItem` 公开接口，通常不混淆 |
| Apple 私有 metadata key | `com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID`、`...ITEM_QUEUE_ID` | 字符串常量，grep 即可 |
| 诊断用（可忽略） | `I3.l`（MediaRouter 注册表）、`c`/`e` | 仅 `DIAGNOSTIC_MODE=true` 时使用，新版找不到可以让探针返回 null |

定位的一般方法：先在 `location-hits.txt` 里 grep 未混淆的字符串（`seekToPosition`、`onMediaMetadataChanged`、`setSessionExtras`、`METADATA_KEY_MEDIA_ID`、`PlayerLyricsViewModel`），找到宿主类后再用 jadx 伪代码看字段类型和方法签名。不要只按方法名长度或字母顺序猜。

## 5. 六个必要 Hook

补丁在两个 Smali 文件里插入六个 `invoke-static` 调用（见 `cloud-patch/apple-vivo-car-lyrics.patch`）：

```text
VivoCarLyrics.onNativeMediaItem(Object mediaItem)
VivoCarLyrics.onCurrentItemChanged(Object playbackManager, Object newQueueItem)
VivoCarLyrics.onMetadataUpdated(Object playbackManager, Object queueItem)
VivoCarLyrics.onPlaybackError(Object playbackManager)
VivoCarLyrics.onSeek(Object playbackManager, long targetPositionMs)
VivoCarLyrics.onAtomicControllerConnected(String controllerPackageName)
```

| Hook | 6.5.2 位置 | 语义 / 位置要求 |
|---|---|---|
| `onNativeMediaItem` | `P.I(Lv3/t;I)V`，紧跟 `if-nez p2, :cond_4` 之后、`hashCode()` 之前，传 `p1` | Apple Music 原生发布 MediaItem 的路径。Hook 在发布前向该 MediaItem 的 metadata extras **原位** OR 入 `support_event`。`apply.sh` 校验顺序：守卫 → Hook → `hashCode` → `iput j` |
| `onCurrentItemChanged` | `P.onCurrentItemChanged(MediaPlayerController, PlayerQueueItem, PlayerQueueItem)`，参数空检查之后，传 `p0, p3` | 换歌。清除旧歌词、递增 generation、延迟加载新歌词、给原子发空歌词事件 |
| `onMetadataUpdated` | `P.onMetadataUpdated(MediaPlayerController, PlayerQueueItem)`，传 `p0, p2` | Apple Music 重新发送当前元数据。重新加载歌词状态 |
| `onPlaybackError` | `P.onPlaybackError(MediaPlayerController, MediaPlayerException)`，传 `p0` | 清空歌词，发布失败状态 |
| `onSeek` | `P.seekTo(J)`，紧跟 `invoke-interface ... seekToPosition(J)V` 之后，传 `p0, p1, p2`（long 占两个寄存器） | 必须拿到与 `seekToPosition` 相同的目标毫秒 |
| `onAtomicControllerConnected` | `c0.e(LE3/B2;LE3/B2$e;)V`（Media3 `MediaSession.Callback.onPostConnect`），从 `ControllerInfo` 取包名：`B2$e.a` → `C$b.a` → `C$d.a`（String）；`.locals 0` 改 `1` | 仅当包名 == `com.vivo.musicwidgetmix` 时补发；不得对所有控制器广播 |

每个 Hook 在其方法体内和整个文件内都必须**恰好出现一次**（`apply.sh` 与 `rebuild.sh` 都会检查）。新版本重新定位后，`apply.sh` 里的 `manager_target` / `connection_target` 路径、六个方法签名、以及 `rebuild.sh` 最后一段 Python 里的同一组签名和 `native_order` 锚点都要同步修改。

## 6. 元数据与封面刷新规则

- 当前歌词行变化：只调用会话 Extras 发布接口（3.1 + 3.2(c) 的空 action）。
- 完整歌词 / 状态变化：只通过会话 Extras 发原子 `lrc_change` 事件，**不**重建 MediaMetadata。
- `publishMetadata()` 在当前实现中是空操作（`return false`），`verify_source_contract.py` 强制它保持空操作，并禁止整个源码里出现 `invokeRequired(manager, "I", newMediaItem` 和 `setFieldValue(`。
- 原子能力位：只能在 `advertiseAtomicLyricSupport()` 里对原生 MediaItem 的 extras 原位 `putLong`，禁止 `new Bundle(`、禁止重发。
- Apple Music 自己覆盖元数据时，下一次原生发布 Hook 会再次幂等补位，不需要额外动作。

## 7. 更新 APK 的标准流程

### 7.1 把新 APK 装进 payload

原始 APK 不进 Git 历史，而是打进 `payload.tar` 再切成 `payload.tar.part.000…`（每片 20 MiB，最后一片 < 25 MB）。CI 第一步会 `cat payload.tar.part.* > payload.tar`，校验 `payload.sha256`，解到 `payload/` 后在其中运行后续脚本。

payload.tar 内部结构（CI 脚本也在里面）：

```text
input/SHA256SUMS                       # "<sha256>  apple-music-X-Y-Z.apk"
input/parts/apple-music-X-Y-Z.apk.part.000 …   # 原 APK 切片，每片 < 25 MB
config/search-patterns.txt             # analyze.sh 的 grep 模式
scripts/ci/reconstruct.sh              # 拼接 input/parts 并校验 SHA
scripts/ci/analyze.sh                  # aapt2 badging / apktool d / jadx --deobf / grep
scripts/ci/install-tools.sh, rebuild.sh, apply-patches.sh   # 旧版脚本，已被 cloud-patch/ 取代
```

新版本操作（全部用 macOS 自带命令，不装工具）：

```bash
# 1. 解开旧 payload（在临时目录）
mkdir -p /tmp/payload && cat payload.tar.part.* | tar -xf - -C /tmp/payload
# 2. 换 APK
rm /tmp/payload/input/parts/*
split -b 20m -d -a 3 "/path/新版.apk" /tmp/payload/input/parts/apple-music-X-Y-Z.apk.part.
shasum -a 256 "/path/新版.apk" | sed 's#  .*#  apple-music-X-Y-Z.apk#' > /tmp/payload/input/SHA256SUMS
# 3. 重新打包、切片、写校验
( cd /tmp/payload && tar -cf /tmp/payload.tar . )
rm payload.tar.part.* && split -b 20m -d -a 3 /tmp/payload.tar payload.tar.part.
shasum -a 256 /tmp/payload.tar | sed 's#  .*#  payload.tar#' > payload.sha256
rm -rf /tmp/payload /tmp/payload.tar
```

然后把 `.github/workflows/apk-pipeline.yml` 里的 `APK_NAME` 改成新文件名，并在第 1 节记录新 SHA-256 / 版本。

### 7.2 只分析，不重建

```bash
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
git push origin <branch>
git rev-parse HEAD origin/<branch>        # 两个 hash 必须一致再触发
gh workflow run "APK analysis and rebuild" --ref <branch> -f rebuild=false
gh run watch <run-id> --exit-status
gh run download <run-id> -n apk-results-<run>-report -D /tmp/report
```

`workflow_dispatch` 类型的工作流必须已存在于默认分支 `main` 才能按名字触发（新建的 workflow 先合到 main）。

检查 artifact：

```text
badging.txt / permissions.txt / original-signature.txt   APK 完整性、包名、版本
apktool.log / jadx.log / jadx-exit-code.txt              反编译是否成功
location-hits.txt                                        search-patterns 命中行（定位起点）
focused-paths.txt / focused-context.txt                  MediaSession / lyrics 相关文件与上下文
focused-sources.tar.gz                                   上述文件的完整源码（jadx java + smali）
legacy-bridge-*.txt / session-class-*.txt                Media3 legacy 桥接与 session 类
```

用第 4、5 节的配方在 `focused-sources.tar.gz` 里重新定位；如果覆盖不全，改 `apk-pipeline.yml` 的 `pattern` / `roots` 或 `config/search-patterns.txt`（后者在 payload 里）后重跑分析。

### 7.3 更新辅助类

逐条核对第 4 节表格，修改 `VivoCarLyrics.java` 中的字符串常量与反射调用。原则：

- 找不到的目标要报错到日志并让该功能降级，不要为了通过编译静默吞掉所有异常。
- 更新 `BUILD_MARKER`（格式 `vivo-car-atomic-<描述>-rNN-YYYY-MM-DD`），同步改 `rebuild.sh` 的 `HELPER_MARKERS`。
- 本地跑 `python3 cloud-patch/tests/verify_source_contract.py`（系统自带 python3 即可）。

### 7.4 重做 Smali 补丁

1. 从 `focused-sources.tar.gz` 里取新版两个宿主 Smali，按第 5 节插入六个 `invoke-static`。
2. 用 `diff -u` 生成新的 `cloud-patch/apple-vivo-car-lyrics.patch`（路径相对 `work/apktool`，`--strip=1`）。注意 `.locals` 是否够用（`onAtomicControllerConnected` 需要一个临时寄存器）。
3. 改 `apply.sh`：`manager_target`、`connection_target`、六个签名、`native_order` 锚点。
4. 改 `rebuild.sh` 末尾 Python：同一组签名、`P.smali` / `c0.smali` 的 glob、`native_order`。
5. Manifest 插入由 `apply.sh` 自动完成，前提是 `MediaPlaybackService` 类名与 `intent-filter` 结构不变；变了就改脚本里的服务名。

### 7.5 额外 DEX

`rebuild.sh` 扫描 APK 里已有的 `classes*.dex`，自动选下一个未占用编号，不需要手改。

### 7.6 重建与验证

```bash
gh workflow run "APK analysis and rebuild" --ref <branch> -f rebuild=true
```

CI 会依次验证：契约测试 → apktool 重建 → javac/d8 → helper marker → zipalign → 固定签名 + 证书 pin → 包名/版本一致 → manifest 中合作 action 恰好一次且与 MediaBrowser 同一 filter → 六个 Hook 各恰好一次 → helper DEX 签名前后一致。成功后自动创建 Release `v1.0.0-build-<run_number>`，附 APK 与 `.sha256`。

```bash
gh release download v1.0.0-build-<N> -p '*.apk' -p '*.sha256' -D downloads/
```

## 8. 安装与签名限制

重建 APK 使用自有测试签名，不再拥有 Apple 官方签名。

- 不能覆盖安装官方 Apple Music；从官方版切换需先卸载（会清除已下载歌曲）。
- 新签名可能影响 Apple 登录、DRM、推送或完整性检查，测试前确认账号可重新登录。
- 同一固定密钥构建的版本之间可以直接覆盖安装。

固定签名配置：

```text
GitHub Secret: ANDROID_SIGNING_KEY_BASE64 / ANDROID_SIGNING_PASSWORD
Key alias:     apple-music-vivo-car-lyrics
证书 SHA-256:  19de023cf6b5b4d02d4151b306dd6389a7720d36b066607bf43ba9ce61fb9e66（config/signing-cert-sha256.txt）
```

密钥与密码不进 Git；私钥丢失后新构建无法覆盖旧安装。

## 9. 实车测试清单

1. 普通在线歌曲能播放，音质设置与修改前一致；无损 / 杜比全景声不受影响。
2. 换歌后标题、歌手、专辑、封面正确。
3. 车机歌词自动出现，正常播放时逐行同步。
4. 前后拖动后，歌词在约 250 毫秒内跳到正确位置。
5. 每句歌词变化时专辑图不重新加载。
6. 暂停、恢复、上一首、下一首正确。
7. 无歌词歌曲显示正确状态，不残留上一首歌词。
8. 断开并重新连接车联后仍能恢复。
9. 原子随身听：首次连接、断开重连、Apple Music 已播放后再打开，都能显示当前完整歌词。
10. 原子随身听：连续切歌不残留上一首，无歌词歌曲清空歌词。
11. 原子随身听：进度条显示时长并随播放前进，拖动可 seek；若首曲短暂 `--:--` 后自愈，记录为 DURATION 时序（KNOWN_ISSUES 第 1 节）。

## 10. 普通外挂方案的边界

包名为 `cn.kuwo.player` 的桥接 App（`kuwo-bridge/`，独立 workflow）可以解决车联识别和控制转发，但普通 Android App 只能读取 Apple Music 通过公共 MediaSession 暴露的数据，拿不到私有 `PlayerLyricsViewModel` 里的完整同步歌词。修改 Apple Music APK 仍是当前最完整的自动歌词方案。

## 11. 文件职责

```text
.github/workflows/apk-pipeline.yml       分析 + 重建 + Release 入口（workflow_dispatch，输入 rebuild）
.github/workflows/vivo-decompile.yml     jadx 反编译 vivo 侧 APK（原子随身听 / 车联），上传源码 artifact
.github/workflows/kuwo-bridge.yml        独立的 KuWo 桥接原型构建
payload.tar.part.* / payload.sha256      原 APK 切片 + CI 脚本 + search-patterns（见 7.1）
cloud-patch/apple-vivo-car-lyrics.patch  六个 Smali Hook 的 unified diff
cloud-patch/apply.sh                     打补丁、插入 manifest action、校验 Hook 位置
cloud-patch/rebuild.sh                   apktool b、编译辅助类、加 DEX、zipalign、固定签名、全部终检
cloud-patch/java/.../VivoCarLyrics.java  歌词加载、时间轴、逐句发布、原子事件、能力位
cloud-patch/java/.../ClusterLyricsPaginator.java  仪表分页（r37 起不再发布，保留编译与测试）
cloud-patch/tests/verify_source_contract.py       源码契约：禁止重发 MediaItem、要求能力位 8 与 16
cloud-patch/tests/.../ClusterLyricsPaginatorTest.java
config/signing-cert-sha256.txt           固定签名证书 pin
docs/KNOWN_ISSUES.zh-CN.md               未解决问题与已验证死路
docs/AI_HANDOFF_PROMPT.zh-CN.md          交给下一个 AI 的提示词模板
```

## 12. 完成标准

后续 AI 只有在以下条件全部满足时才能说"适配完成"：

- 新版本已重新分析，第 4 节全部反射目标与第 5 节六个 Hook 均按语义确认，不是盲套旧补丁。
- `verify_source_contract.py` 通过；GitHub Actions 构建成功并产出 Release。
- Manifest 含 `com.vivo.musicwidgetmix.support.service`，`support_event` 发布值包含 `7|8|16`。
- 明确说明签名和卸载风险。
- 第 9 节清单至少完成一次实车测试；未实测的项目必须明确标为"待实车验证"。
- 在第 13 节追加变更记录，并更新 `docs/KNOWN_ISSUES.zh-CN.md`。

## 13. 版本变更记录

### 2026-09-03 - 车联 6.0.8.3 静态分析（无代码改动）

反编译车联 APK（Release `carnetworking-apk-6.0.8.3`）确认：仪表长句截断（`HudManager` 写死 17 字 + `...`）和逐句封面重载（`CarMusicInfo.equals` 含 `lineLyrics`，每次重发都附带封面）都是车联侧固定逻辑，Apple Music 侧不修；同时发现车联 6.0.8.3 不读 `music.media.extras.*`，车机歌词实际来自原子随身听的 `lrc_change` 事件。详见 3.1 节与 `docs/KNOWN_ISSUES.zh-CN.md` 第 2 节。

### r38 (2026-09-03) - 原子随身听进度条：补上 seek 能力位 16（Build #83）

通过新增的 `Decompile vivo APK` 工作流对原子随身听 6.2.5.6 做静态分析，定位到进度条根因：`SeekBarLayout` 只在 `duration > 0 && (support_event & 16) == 16` 时显示时间和进度；合作控制器 `c0` 原样使用应用发布的 `support_event`，而补丁只写了 `7 | 8 = 15`。详见 `docs/KNOWN_ISSUES.zh-CN.md` 第 1 节。

- `VivoCarLyrics.advertiseAtomicLyricSupport` 新增 `ATOMIC_SEEK_SUPPORT_EVENT = 16`，发布值变为 `7 | 8 | 16 = 31`
- `verify_source_contract.py` 要求该常量出现在能力位代码中
- 修复 r37 遗留的两个构建阻塞：诊断探针里残留的 `META_LINE` 引用，以及 `rebuild.sh` 仍在检查 r36 marker 和已删除的 `ucar.*` 字符串
- 构建标识：`vivo-car-atomic-seek-bit-r38-2026-09-03`；待实车确认进度条

### r37 (2026-09-03) - 停止向仪表推送歌词

删除 `META_LINE / META_WHOLE / META_STATUS` 的 session Extras 写入，只保留车机 `music.media.extras.*` 和原子随身听 `vivomusicmix.*`。`ClusterLyricsPaginator` 保留编译。该版本本身未成功构建（见 r38）。

### r35 (2026-09-02) - 诊断关闭基准（Build #82 为 r36）

关闭 `DIAGNOSTIC_MODE`，撤销 r34 的 `manager.I()` 重发布。诊断代码保留编译但门控。歌词（车机 + 原子）正常；进度条未解决。

### r34 (2026-09-02) - MediaItem 重发布尝试（已撤销）

`onAtomicControllerConnected` 时调用 `manager.I(mediaItem, 0)`，结果进度条更糟：重发 MediaItem 会重置 PlaybackState。已列入死路。

### r23–r33 (2026-09-02) - 原子随身听进度条诊断

11 轮诊断。框架层读到 `mDUR=258000 mSE=15`（r32）。当时得出"compat 与框架层是两条独立通道"的结论，r38 反编译证明该结论错误，真正缺的是 `mSE` 里的 16 位。

### r12 (2026-09-01) - 三方合并：车机 + 仪表分页 + 原子随身听

将 release-44（96312d1，车机与仪表分页）与 379acd9（原子随身听支持）合并至 `feat/atomic-lyrics-on-main`。`LyricsState` 同时保存原始和分页时间轴；`onNativeMediaItem` / `onAtomicControllerConnected` Hook 与能力位写入自此进入主线。构建标识 `vivo-car-atomic-lyrics-on-main-r12-2026-09-01`。

### r9–r21 (2026-08-29 ~ 09-01)

r9：仪表分页初版（`ClusterLyricsPaginator`）；当时为保进度条撤销过 manifest action（r38 已证明不必）。r10–r21：原子随身听调试、能力位基线、null extras 防护、duration republish 上 looper。
