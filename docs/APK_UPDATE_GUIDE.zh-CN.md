# Apple Music 车联与原子随身听歌词 APK 更新与交接指南

最后整理日期：2026-10-10（r38 歌词行为基线 + 1607 独立标题修正）

> 2026-10-10 功能定义纠正：现有独立标题实现是系统 locale + 内存 LRU 实验，
> 不是 AM++ 的所选地区解析与持久缓存功能。build #146 云端通过不代表移植完成，
> 手机效果未确认。后续以 `STANDALONE_TITLE_CORRECTION_PLAN.zh-CN.md` 第 0 节为准；
> 本文的旧 locale 描述仅记录现有实现，不作为目标契约。

本文档是给"拿到新版 Apple Music APKM 的下一个 AI"看的移植手册。目标：移植车机歌词、原子随身听歌词、进度条和独立标题修正，在 GitHub Actions 构建测试 APK。默认 `embed_ampp=false`，普通 APK 即包含独立实现；AM++ 嵌入仅保留为历史实验。1607 新标题路径的设备效果仍待验证。

**读本文档时的三条铁律：**

1. 混淆类名、字段名、Smali 行号（`P.smali`、`c0.smali`、`v3/t`、`E3.B2` 等）只是 6.5.2 的定位线索，每个新版本都必须重新定位，不能照抄。
2. 协议字段、能力位、Hook 语义、禁止事项（第 3、4、5、6 节）是跨版本稳定的，必须原样保留。
3. 所有构建与反编译都在 GitHub Actions 里做，本地 Mac 不安装 Java / SDK / apktool / jadx。

---

## 0. 快速路线图（新版 APK 到手后按顺序做）

| 步骤 | 做什么 | 产物 / 验收 |
|---|---|---|
| A | 把下载到的 APKM 解成 base/split APK；记录版本、包名与哈希；把 splits 装进 payload（第 7.1 节） | `payload.sha256` 更新，`payload.tar.part.*` 每片 < 25 MB |
| B | 只分析不重建：`gh workflow run "APK analysis and rebuild" -f rebuild=false` | 下载 `apk-results-<run>-report`，拿到 `focused-sources.tar.gz`、`location-hits.txt` |
| C | 重新找出 6 个歌词 Hook、1 个标题 UI Hook 和全部反射目标 | 一张"旧名 → 新名"对照表 |
| D | 改 `VivoCarLyrics.java` 的反射目标；改 `apple-vivo-car-lyrics.patch`、`apply.sh`、`rebuild.sh` 中的类路径 / 方法签名 / marker | 本地 `python3 cloud-patch/tests/verify_source_contract.py` 通过 |
| E | 提交、推送（需代理），确认 `HEAD == origin/<branch>` 后先构建普通歌词 APK | Release `v1.0.0-build-N` 含 APK 与 sha256 |
| F | 检查普通构建的独立标题测试、UI Hook 与通知刷新路径 | `embed_ampp=false`，不依赖 AM++ / NPatch |
| G | 交给用户实车测试（第 9 节清单），未实测的项目标"待实车验证" | 更新第 13 节变更记录、`docs/KNOWN_ISSUES.zh-CN.md` |

---

## 1. 已验证基线

- 原始 APK：Apple Music 6.5.2，包名 `com.apple.android.music`，versionCode `1586`
- 原始 APK SHA-256：`a05a36a5678015fd49d8c73aed2087e7a2f8f3232376733a2cf2f82623895736`
- 目标车联包：`com.vivo.car.networking` 6.0.8.3（JoviInCar 车机）
- 目标原子随身听：`com.vivo.musicwidgetmix` 6.2.5.6（APK 存放在本仓库 Release `atomic-apk-6.2.5.6`）
- 冻结实车行为基线：r38 = GitHub Release `v1.0.0-build-83`，构建标识 `vivo-car-atomic-seek-bit-r38-2026-09-03`，不是最新构建声明
- 签名：GitHub Secrets 里的固定 PKCS12 测试密钥，证书 SHA-256 pin 在 `config/signing-cert-sha256.txt`
- 辅助类在 6.5.2 中被加入为 `classes5.dex`（`rebuild.sh` 会自动选下一个未占用编号）

r38 实车状态（2026-09-04 确认）：车机歌词、原子随身听歌词、原子随身听进度条全部正常。

**r38 是用户确认的冻结基线（2026-09-03）。** 后续所有方案（包括新版 Apple Music 的移植）都以这一版的行为为准：只发 3.1 + 3.2 的字段，不做 LRC 切段，不向仪表单独推送，不重发 MediaItem。仪表长句截断与逐句封面重载属车联侧限制，已决定不处理（`docs/KNOWN_ISSUES.zh-CN.md` 第 2 节），不要再提出绕过方案。

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

### 4.1 1607 已确认映射

| 用途 | Apple Music 7.0.0-beta（1607） |
|---|---|
| 播放管理器 / 原生发布 | `player.S` / `S.P(Lz3/v;I)V` |
| 当前项 / metadata / extras | `S.c()`，回退 `a()`；`z3.v.d`；`z3.x.J` |
| metadata 标题 / 转换器 | `z3.x.a:CharSequence`；`player.Q.b(z3.x)` |
| 队列模型标题 | 兼容 `StoreMediaItem.title` 字段 |
| Atomic 连接 | `e0.o(LJ4/f2;LJ4/f2$e;)V` |
| Catalog | holder companion 获取实例；`w9.Q.F` / `w9.a.F`；旧版 `s8.F.x` / `u8.E.v` |
| UI 标题 | `q8.na.l()V`，`.locals 58`，`ma.t0` item 在 `v8`，stock `getTitle()` 结果在 `v39` |
| UI 刷新 | 同一 `ma.q0(PlaybackItem)` 重新绑定，不修改 UI 模型 |
| 通知刷新 | `J4.Y2.g(session,boolean)` → `X1` → `a2` → `p.a` → `d2.f`；读取当前 `player.i0().a` |

目录请求遵循系统 locale，不修改共享宿主 storefront；缓存按稳定 catalog 身份与
locale 隔离。切换语言后阻止新的旧语言结果写入；已原位修改的标题可能保留到
新语言查询成功，不保证立即恢复原标题。异步结果重新验证 manager、generation、队列 ID 与 catalog ID；
旧曲结果仅可入缓存。反射签名、歌曲类型或响应 ID 不匹配时 fail-closed，保留 stock
标题，不猜测任意首个响应实体或非歌曲内容。

macOS 默认文件系统可能在解压时混淆 `na.smali` / `Na.smali`、
`d2.smali` / `D2.smali`。用 `tar -xOzf focused-sources.tar.gz <精确成员路径>`
读取并核对 `.class` 描述符，不能把被覆盖的文件当成小写类的证据。

## 5. 六个必要 Hook

以下六个歌词 Hook 保持冻结。1607 另外只有一个标题 UI Hook（5.1），不可为标题刷新改变以下调用：

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

### 5.1 独立标题 UI Hook

`q8.na.l()V` 在 stock `getTitle()` 返回后调用
`VivoCarLyrics.correctPlayerTitle(Object binding, Object item, CharSequence stock)`；
返回值沿原寄存器流到标题 TextView 的两个 stock adapter，不改歌手、专辑或 UI 模型。
复用已确认失活的 `v40/v41`，`.locals 58` 不变；异步结果通过同一 binding 的
`q0(item)` 重新求值。`verify_title_hook.py` 检查唯一类/方法、寄存器、
stock 计算与 UI 赋值顺序，最终回编译后再次执行。

## 6. 元数据与封面刷新规则

- 当前歌词行变化：只调用会话 Extras 发布接口（3.1 + 3.2(c) 的空 action）。
- 完整歌词 / 状态变化：只通过会话 Extras 发原子 `lrc_change` 事件，**不**重建 MediaMetadata。
- `publishMetadata()` 仍是空操作（`return false`），禁止重发 MediaItem、替换或重建 MediaMetadata。标题专用路径仅可原位写现有 `z3.x.a` 与兼容 `StoreMediaItem.title`，不放宽歌词、Atomic、session extras 的写入限制。
- 原子能力位：只能在 `advertiseAtomicLyricSupport()` 里对原生 MediaItem 的 extras 原位 `putLong`，禁止 `new Bundle(`、禁止重发。
- Apple Music 自己覆盖元数据时，下一次原生发布 Hook 会再次幂等补位，不需要额外动作。
- 标题结果匹配当前身份后原位写标题，并通过宿主 `Y2.g(session,boolean)` 独立刷新 Notification。这不是重建 MediaItem / MediaMetadata，不可换成 `S.P`、`S.I` 或等价发布方法。系统 MediaSession / 车机标题仅观察，不承诺所有显示面同步。

## 7. 更新 APK 的标准流程

### 7.1 把新 APKM 解包并装进 payload

新版本通常先下载为 `.apkm`。`.apkm` 本质是 zip 容器，里面是 base APK 和多个 `split_config.*.apk`。原始 APK 不进 Git 历史，而是把这些 split APK 放进 `payload.tar/input/splits/`，再把 `payload.tar` 切成 `payload.tar.part.000…`（每片 20 MiB，最后一片 < 25 MB）。CI 重新合并 splits 并分析、应用歌词与独立标题补丁；只有显式选择历史实验时才执行 AM++ 嵌入。

当前推荐 payload 内部结构（CI 脚本也在里面）：

```text
input/SHA256SUMS                       # input/splits 模式下保留空文件
input/splits/base.apk                  # APKM 解包得到的 base APK
input/splits/split_config.*.apk        # ABI / density 等 split APK
config/search-patterns.txt             # analyze.sh 的 grep 模式
scripts/ci/reconstruct.sh              # 合并 input/splits，或拼接并校验 input/parts
scripts/ci/analyze.sh                  # aapt2 badging / apktool d / jadx --deobf / grep
scripts/ci/install-tools.sh, rebuild.sh, apply-patches.sh   # 旧版脚本，已被 cloud-patch/ 取代
```

新版本操作（全部用 macOS 自带命令，不装工具）：

```bash
# 1. 解开 APKM（只解包，不合并、不重签名）
mkdir -p /tmp/payload && cat payload.tar.part.* | tar -xf - -C /tmp/payload
unzip -q "/path/新版.apkm" -d /tmp/payload/input/splits

# 2. 只保留 CI 需要的 split；如果 APKM 里带 icon.png / info.json，先移出 splits 目录
# base.apk 与 split_config.*.apk 必须齐全，尤其是 arm64_v8a / xxxhdpi

# 3. 记录 APKEditor 将在 CI 合并出的目标文件名；APK_NAME 与 workflow 保持一致
APK_NAME=apple-music-6-5-4.apk

# 4. input/splits 模式不使用 input/SHA256SUMS；保留空文件仅为兼容现有 payload 结构。
#    payload.tar 的完整性由 payload.sha256 保证，split APK 由 APKEditor 在 CI 中合并。
: > /tmp/payload/input/SHA256SUMS

# 5. 重新打包、切片、写校验
( cd /tmp/payload && tar -cf /tmp/payload.tar . )
find payload.tar.part.* -delete
split -b 20m -d -a 3 /tmp/payload.tar payload.tar.part.
shasum -a 256 /tmp/payload.tar | sed 's#  .*#  payload.tar#' > payload.sha256
```

然后把 `.github/workflows/apk-pipeline.yml` 里的 `APK_NAME` 改成新文件名，并在第 1 节记录新 APKM / base APK / split 的 SHA-256 与版本。

注意事项：

- 不要把 APKM 当 APK 直接放进 payload；`reconstruct.sh` 需要的是 `input/splits/` 目录。
- 不要只放 `base.apk`，否则 ABI / 资源 split 缺失。
- 不要手工删除或改写 split 的签名；签名校验失败时先重新下载 APKM。
- `input/parts/` 是旧的单 APK 切片路径；使用 APKM 更新时优先用 `input/splits/`。

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
6. 独立标题须另行确认生成 binder 的 `getTitle()` → TextView adapter 接缝；打补丁前运行 `verify_title_hook.py <decoded-root> --unpatched`，之后与最终回编译后运行默认校验。六个歌词 Hook 不因标题逻辑改动。

### 7.5 额外 DEX

`rebuild.sh` 扫描 APK 里已有的 `classes*.dex`，自动选下一个未占用编号，不需要手改。

### 7.6 重建与验证

```bash
gh workflow run "APK analysis and rebuild" --ref <branch> \
  -f rebuild=true -f embed_ampp=false -f standalone_module=false
```

CI 验证源码/patch 契约、Java 状态机与 catalog 测试、标题 UI 补丁前后接缝、apktool 重建、javac/d8、helper marker、zipalign、固定签名与证书 pin、包名/版本、manifest 合作 action 唯一性、六个歌词 Hook 和单一标题 Hook，以及 helper DEX 签名前后一致。最终回编译再次检查 Hook 顺序。成功后创建 Release `v1.0.0-build-<run_number>`，附 APK 与 `.sha256`；构建成功不等于设备回归通过。

```bash
gh release download v1.0.0-build-<N> -p '*.apk' -p '*.sha256' -D downloads/
```

### 7.7 历史 AM++ 嵌入实验（非默认安装目标）

仅在明确需要复现旧方案时运行。普通 `embed_ampp=false` 构建已包含独立标题修正，
不得把嵌入成功当成普通构建前提。1607 的 AM++ 1.6.4
`AppleMusicHostProfiles.find(1607)` 返回 null，随后 host factory 抛出
`Required value was null`；强制开关或绕过版本检查不能补齐 profile。
下述流程保留为旧版历史记录，不表示支持 1607：

```bash
gh workflow run "APK analysis and rebuild" --ref <branch> \
  -f rebuild=true -f embed_ampp=true -f standalone_module=false
gh run watch <run-id> --exit-status
```

显式测试历史嵌入版时对应 artifact 为：

```text
apple-music-vivo-car-lyrics-ampp-npatched
├── apple-music-vivo-car-lyrics-ampp-npatched.apk
└── apple-music-vivo-car-lyrics-ampp-npatched.apk.sha256
```

不要混淆普通 APK 和历史嵌入版。当前独立实现不需要 root、LSPosed 或 NPatch；
`combined-lsp-module` 仍是实验性方案，不用于日常安装。

内嵌版包含：

- r38 歌词补丁：车机歌词、原子随身听歌词、原子随身听进度条。
- AM++ v1.6.2 的目录查询兼容修复，包括 6.5.3 的 `u8.E#B` → `u8.E#v`。
- AM++ 功能层被裁剪为只安装“修正歌曲名”；液态玻璃默认关闭。
- AM++ 设置界面未裁剪，所以旧设置项仍会显示。显示相同不代表包相同，应以
  `dumpsys package com.apple.android.music` 的 `lastUpdateTime` 和 APK SHA-256 判断。

安装命令只允许覆盖安装：

```bash
shasum -a 256 -c apple-music-vivo-car-lyrics-ampp-npatched.apk.sha256
adb install -r apple-music-vivo-car-lyrics-ampp-npatched.apk
adb shell dumpsys package com.apple.android.music | grep -E 'versionName|versionCode|lastUpdateTime'
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
12. 独立标题：播放页与通知在请求完成前显示原标题，之后只修正匹配曲目；同名、空结果、无匹配或非歌曲内容保持 stock。
13. 请求中快速切歌、重复队列曲目与 metadata 补全时，旧结果不得落到新曲；切换系统语言后不得复用旧语言缓存。
14. 检查应用进程 logcat 中的目录与绑定反射错误。`dumpsys media_session` 只作观察，不作为标题修正通过条件。1607 新路径以上项目仍待设备验证。

## 10. 普通外挂方案的边界

包名为 `cn.kuwo.player` 的桥接 App（`kuwo-bridge/`，独立 workflow）可以解决车联识别和控制转发，但普通 Android App 只能读取 Apple Music 通过公共 MediaSession 暴露的数据，拿不到私有 `PlayerLyricsViewModel` 里的完整同步歌词。修改 Apple Music APK 仍是当前最完整的自动歌词方案。

## 11. 文件职责

```text
.github/workflows/apk-pipeline.yml       分析 + 重建 + Release 入口（workflow_dispatch，输入 rebuild）
.github/workflows/vivo-decompile.yml     jadx 反编译 vivo 侧 APK（原子随身听 / 车联），上传源码 artifact
.github/workflows/kuwo-bridge.yml        独立的 KuWo 桥接原型构建
payload.tar.part.* / payload.sha256      APKM 解出的 split APK + CI 脚本 + search-patterns（见 7.1）
cloud-patch/apple-vivo-car-lyrics.patch  六个冻结歌词 Hook + 一个独立标题 UI Hook
cloud-patch/verify_title_hook.py          标题 UI 的目标、寄存器与 stock 赋值顺序校验
cloud-patch/apply.sh                     打补丁、插入 manifest action、校验 Hook 位置
cloud-patch/rebuild.sh                   apktool b、编译辅助类、加 DEX、zipalign、固定签名、全部终检
cloud-patch/java/.../VivoCarLyrics.java  歌词通道与独立标题集成；不重新发布 metadata
cloud-patch/java/.../TitleCorrectionState.java  标题身份、generation、locale 隔离有界缓存
cloud-patch/java/.../CatalogQueryMethod.java / CatalogTitleResolver.java  独立宿主 catalog 查询
cloud-patch/java/.../ClusterLyricsPaginator.java  仪表分页（r37 起不再发布，保留编译与测试）
cloud-patch/tests/verify_source_contract.py       源码契约：禁止重发 MediaItem、要求能力位 8 与 16
cloud-patch/tests/.../ClusterLyricsPaginatorTest.java
cloud-patch/embed-ampp.sh                把已打歌词补丁的 APK 包进 NPatch，并嵌入裁剪版 AM++
cloud-patch/ampp/prepare-module.sh       AM++ 原生库 / 查询兼容修复与功能裁剪
config/signing-cert-sha256.txt           固定签名证书 pin
docs/KNOWN_ISSUES.zh-CN.md               未解决问题与已验证死路
docs/AI_HANDOFF_PROMPT.zh-CN.md          交给下一个 AI 的提示词模板
```

## 12. 完成标准

后续 AI 只有在以下条件全部满足时才能说"适配完成"：

- 新版本已重新分析，第 4 节全部反射目标与第 5 节六个 Hook 均按语义确认，不是盲套旧补丁。
- `verify_source_contract.py` 通过；GitHub Actions 构建成功并产出 Release。
- 独立标题的状态机、catalog、patch 测试通过，标题 UI 接缝的补丁前与最终回编译校验通过；实际播放页、通知、快速切歌与语言切换仍待设备验证。
- Manifest 含 `com.vivo.musicwidgetmix.support.service`，`support_event` 发布值包含 `7|8|16`。
- 明确说明签名和卸载风险。
- 第 9 节清单至少完成一次实车测试；未实测的项目必须明确标为"待实车验证"。
- 在第 13 节追加变更记录，并更新 `docs/KNOWN_ISSUES.zh-CN.md`。

## 13. 版本变更记录

### 2026-10-10 - 1607 独立标题修正

- 普通构建 `embed_ampp=false` 包含独立实现，不加载 AM++ / NPatch。
- 保留六个歌词 Hook；新增 `q8.na.l()` 字符串 Hook，通过同一 binding 刷新播放页。
- 标题专用路径原位更新标题，宿主通知服务独立刷新，不重建或重新发布 metadata。
- 请求与缓存跟随系统 locale，异步结果做当前身份校验；未知签名和无效响应 fail-closed。
- AM++ 1607 profile 失败作为历史实验记录保留；静态映射已确认，设备与实车运行效果待验证。

### 2026-09-28 - 标准化 APKM 更新与内嵌 AM++ 构建流程

- 更新入口改为接收 `.apkm`，在本地只用 `unzip` 解出 `input/splits/base.apk` 与 `split_config.*.apk`；APKEditor 合并仍在 GitHub Actions 的 `reconstruct.sh` 中完成。
- 新版本移植仍必须重新定位六个歌词 Hook 与全部反射目标，不允许盲套 6.5.2 / 6.5.3 混淆名。
- 日常手机安装目标是 `embed_ampp=true`、`standalone_module=false` 生成的 `apple-music-vivo-car-lyrics-ampp-npatched` artifact。
- 内嵌 AM++ 只安装“修正歌曲名”功能；设置界面未裁剪，旧选项仍会显示，不代表功能存在。
- 明确当前手机没有 root / LSPosed，`combined-lsp-module` 不作为日常安装目标。

### r39 (2026-10-07) - 适配 Apple Music 7.0.0-beta (versionCode 1607)

- **混淆宿主类重映射**：
  - 播放管理器由 `Q` 变为 `S`（`com.apple.android.music.player.S`）
  - 原生 MediaItem 发布方法由 `Q.F(Lv3/t;I)V` 变为 `S.P(Lz3/v;I)V`
  - 原生 MediaItem 类由 `v3.t` 变为 `z3.v`；其内部元数据类由 `v3.s` 变为 `z3.x`
  - **关键变更**：元数据 Extras Bundle 字段名由 `I` 变为 `J`（`z3.x->J: Landroid/os/Bundle;`）
  - MediaSession 回调连接方法由 `e0.p(LE3/t2;LE3/t2$e;)V` 变为 `e0.o(LJ4/f2;LJ4/f2$e;)V`
  - PlaybackItem 转换器由 `com.apple.android.music.player.P.b` 变为 `com.apple.android.music.player.Q.b(Lz3/x;)`
  - AM++ 歌名修正目录查询类由 `s8.F.x` / `u8.E.v` 变为 `w9.Q.F` / `w9.a.F`
- **代码改动**：
  - `VivoCarLyrics.java`:
    - 新增 `getMetadataExtras` 优先读取字段 `J`，回退字段 `I`，兼容新旧版本
    - `currentPlaybackItem` 依次尝试 `player.Q`、`P`、`O` 转换器
    - 构建标识更新为 `vivo-car-atomic-seek-bit-r40-2026-10-08`
  - `apple-vivo-car-lyrics.patch`: 重新针对 7.0.0-beta (1607) 的 `S.smali` 与 `e0.smali` 精准生成 6 处 Hook
  - `apply.sh` / `rebuild.sh`: 更新混淆签名检验与 post-rebuild 契约断言至 `S.smali` 与 7.0.0 方法签名
  - `AppleMusicLyricsHook.java` / `CatalogQueryMethod.java`: 动态兼容新旧类名与方法名

### r40 (2026-10-08) - 修复 7.0.0-beta 当前 MediaItem getter 漏配

- **问题**：r39 只把原生发布方法适配为 `S.P(Lz3/v;I)V`，但 helper 仍通过 `S.a()` 获取当前 MediaItem。7.0.0 中 `S.a()` 返回 `z3.C`（常量 `null`），实际 MediaItem getter 已变为 `S.c(): Lz3/v;`。
- **影响**：`metadataHasAtomicSupport()` 判断失败、`currentPlaybackItem()` 返回 `null`，歌词加载不会启动，车机与原子随身听都无歌词。
- **修复**：新增 `currentMediaItem(manager)`，7.0 优先反射 `c()`，失败或为空时回退旧版 `a()`；两份 helper 同步修改，构建标识更新为 `vivo-car-atomic-seek-bit-r40-2026-10-08`。

### 2026-09-03 - 车联 6.0.8.3 静态分析（无代码改动）

反编译车联 APK（Release `carnetworking-apk-6.0.8.3`）确认：仪表长句截断（`HudManager` 写死 17 字 + `...`）和逐句封面重载（`CarMusicInfo.equals` 含 `lineLyrics`，每次重发都附带封面）都是车联侧固定逻辑，Apple Music 侧不修；同时发现车联 6.0.8.3 不读 `music.media.extras.*`，车机歌词实际来自原子随身听的 `lrc_change` 事件。详见 3.1 节与 `docs/KNOWN_ISSUES.zh-CN.md` 第 2 节。

### r38 (2026-09-03) - 原子随身听进度条：补上 seek 能力位 16（Build #83）

通过新增的 `Decompile vivo APK` 工作流对原子随身听 6.2.5.6 做静态分析，定位到进度条根因：`SeekBarLayout` 只在 `duration > 0 && (support_event & 16) == 16` 时显示时间和进度；合作控制器 `c0` 原样使用应用发布的 `support_event`，而补丁只写了 `7 | 8 = 15`。详见 `docs/KNOWN_ISSUES.zh-CN.md` 第 1 节。

- `VivoCarLyrics.advertiseAtomicLyricSupport` 新增 `ATOMIC_SEEK_SUPPORT_EVENT = 16`，发布值变为 `7 | 8 | 16 = 31`
- `verify_source_contract.py` 要求该常量出现在能力位代码中
- 修复 r37 遗留的两个构建阻塞：诊断探针里残留的 `META_LINE` 引用，以及 `rebuild.sh` 仍在检查 r36 marker 和已删除的 `ucar.*` 字符串
- 构建标识：`vivo-car-atomic-seek-bit-r38-2026-09-03`；2026-09-04 实车确认进度条正常显示、随播放前进、可拖动

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
