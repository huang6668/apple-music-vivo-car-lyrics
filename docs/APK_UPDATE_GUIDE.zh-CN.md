# Apple Music 车联与原子随身听歌词 APK 更新与交接指南

最后整理日期：2026-09-01

本文档用于把新版 Apple Music APK 交给后续 AI 重新分析、适配并通过 GitHub Actions 构建。不要把本文档中的混淆类名、字段名或补丁行号当成跨版本稳定接口。

## 1. 已验证基线

- 原始 APK：Apple Music 6.5.2
- 包名：`com.apple.android.music`
- versionCode：`1586`
- 原始 APK SHA-256：`a05a36a5678015fd49d8c73aed2087e7a2f8f3232376733a2cf2f82623895736`
- 目标车联包：`com.vivo.car.networking` 6.0.8.3
- 已成功产出 run-6 调试签名测试 APK；该版本使用了 Actions 临时密钥。
- 2026-08-28 起改用 GitHub Secrets 中的固定测试密钥，并在仓库中校验证书 SHA-256。
- 构建环境全部在 GitHub Actions 中安装，本地 Mac 不需要 Android SDK、Java、Gradle、apktool 或 jadx。

## 2. 要实现的行为

修改代码运行在 Apple Music 自身进程中，从它的私有歌词对象获取完整歌词和时间轴，再由同一状态机发布给 vivo 车联和原子随身听。

必须保留以下行为：

1. 换歌后自动加载当前歌曲歌词。
2. 每 250 毫秒根据播放位置计算当前歌词行。
3. 快进或拖动进度时立即切换到目标歌词行，不等待下一次自然播放回调。
4. 歌词行变化只更新 MediaSession Extras，不能每句都重建歌曲元数据，否则车端会重复加载专辑图。
5. 只在 Apple Music 原生发布 MediaMetadata 的路径中幂等补入原子歌词能力位；辅助类不额外重发 MediaItem。
6. 不修改解码器、音频格式、AudioTrack、ExoPlayer 音频输出、音频焦点或码率选择，避免影响音质。
7. 原子随身听只在换歌、完整歌词或状态变化时收到完整 LRC；普通逐句更新必须清空原子事件 action，不能每 250 毫秒重复触发整段歌词解析。
8. 原子随身听建立 MediaSession 控制器连接后，按短延迟补发当前状态，避免晚打开时等待下一轮低频保活。
9. 仪表端使用独立的分页时间轴：单页最多 20 个 UTF-16 单元，长句按时间戳分成多页；车机和原子随身听继续使用原始歌词时间轴。

## 3. vivo 车联协议字段

写入歌曲元数据 Extras：

```text
ucar.media.metadata.LYRICS_LINE
ucar.media.metadata.LYRICS_WHOLE
ucar.media.metadata.LYRICS_STATUS
```

`LYRICS_STATUS` 当前约定：

```text
0 = 成功
1 = 无歌词
2 = 加载中
3 = 加载失败
```

写入 MediaSession Extras：

```text
music.media.extras.LYRIC
music.media.extras.LYRIC_IS_ALLOWED
music.media.extras.NOTICE_CAR
```

当前行放入 `music.media.extras.LYRIC`；另外两个字段设为 `true`。

仪表端读取的是歌曲 `MediaMetadata` 中的 `ucar.*` 字段，不读取上述 Session Extras。当前实现只在换歌、完整分页歌词、状态变化或原生元数据覆盖时重建一次 Metadata；普通逐句变化不重建。这样可以消除 Apple Music 自身造成的逐句封面刷新，但车联在解析分页 LRC 后仍可能因内部 `CarMusicInfo` 更新而重发封面，必须以实车结果为准。

仪表固件会把超过 20 个 UTF-16 单元的当前行截成前 17 个单元加省略号，且不会自行横向滚动。因此辅助类需要把长句预先拆成不超过 20 个 UTF-16 单元的多条 LRC，并为每页分配递增时间戳，模拟翻页显示。

## 3.1 vivo 原子随身听协议

协议完整接入通常要求在 `com.apple.android.music.player.MediaPlaybackService` 的现有 `intent-filter` 中增加：

```text
com.vivo.musicwidgetmix.support.service
```

该服务必须继续保持 `android:exported="true"`，并保留标准的：

```text
android.media.browse.MediaBrowserService
```

原子随身听从 MediaSession Extras 读取完整 LRC：

```text
vivomusicmix.meida.extra.key.action = vivomusicmix.extra.lrc_change
vivomusicmix.extra.key.meidia_id = 当前公开 MEDIA_ID
vivomusicmix.extra.key.lyric = 完整带时间戳 LRC
vivomusicmix.media.metadata.support_event = 原有能力位 | 8
```

`meida` 和 `meidia` 是协议中的真实拼写，不能修正。歌词能力位 `8` 必须放入 MediaMetadata；缺失时原子随身听会把合作控制器判定为不支持歌词，即使收到完整 LRC 事件也不会显示。原子随身听晚连接后不会主动向 Apple Music 拉取当前歌词，因此成功、无歌词或失败事件需在短延迟后重发，并在播放期间低频重发。逐句车联 Extras 更新必须把 `vivomusicmix.meida.extra.key.action` 设为空字符串，避免旧的 `lrc_change` 被 Apple Music 的合并式 Extras 更新持续保留。

当前 r9 基线为了保留 Apple Music 原生进度条，已撤销 Manifest 中的原子合作 action，只保留协议字段和连接回调代码。因此本轮仪表分页改动不宣称原子随身听已经完整兼容；是否能选中合作控制器取决于设备和固件。若后续恢复该 action，必须同时复测原子随身听进度条、歌词和连续切歌。

切歌时应立即发送一次空歌词事件。优先携带已经确认属于新队列项的公开 `android.media.metadata.MEDIA_ID`；公开 ID 尚未出现时可暂用新队列项的 store ID。若连 fallback ID 也没有，仍须发送 `lrc_change + 空 meidia_id + 空 lyric`，原子随身听会用该事件清除内存中的上一首歌词，不能继续沿用旧歌曲 ID。

## 4. 6.5.2 中的已知实现

这些名称只用于帮助定位，更新后可能全部变化：

- 播放管理器 Smali：`smali_classes2/com/apple/android/music/player/P.smali`
- 辅助类：`com.apple.android.music.player.VivoCarLyrics`
- 歌词 ViewModel：`com.apple.android.music.player.viewmodel.PlayerLyricsViewModel`
- 加载方法：`loadLyrics(playbackItem)`
- 歌词结果：`getLyricsResult()`
- TTML 行访问类：`com.apple.android.music.ttml.i`
- 当前播放项转换器：`com.apple.android.music.player.O`
- Application 获取入口：`com.apple.android.music.AppleMusicApplication$a`
- LiveData Observer 在该版本中混淆为：`androidx.lifecycle.L`

辅助类通过反射访问这些私有对象，因此新版首先要检查类、方法、构造函数和字段是否仍然存在。

## 5. 六个必要 Hook

当前补丁在播放管理器和 MediaSession 连接回调中插入六个调用：

```text
onNativeMediaItem(nativeMediaItem)
onCurrentItemChanged(playbackManager, newQueueItem)
onMetadataUpdated(playbackManager, queueItem)
onPlaybackError(playbackManager)
onSeek(playbackManager, targetPositionMs)
onAtomicControllerConnected(controllerPackageName)
```

新版本必须根据方法语义重新定位：

### 5.1 原生元数据发布

寻找 Apple Music 用当前 MediaItem 通知 `onMediaMetadataChanged` 的原生发布路径。在发布前只向现有 Metadata Extras 原位写入 `support_event | 8`，不得构造替代 MediaItem，也不得从辅助代码再次调用播放管理器的发布方法。

### 5.2 当前歌曲改变

寻找接收新队列项、更新当前 MediaItem 或切换播放项的方法。Hook 后应清除上一首歌词、增加 generation，并延迟加载新歌词。

### 5.3 元数据重新发布

寻找 Apple Music 重建或重新发送当前 MediaMetadata 的路径。Hook 用于重新加载歌词状态；原子歌词能力位由 5.1 的原生发布 Hook 补入。

### 5.4 播放错误

寻找当前播放项错误回调。Hook 应清空歌词并发布失败状态。

### 5.5 Seek

寻找最终调用 `MediaPlayerController.seekToPosition(long)` 的路径。必须把同一个目标毫秒位置传给 `onSeek`，立即发布对应歌词行。

### 5.6 原子随身听连接

寻找 Media3 的 `onPostConnect` 回调，从 controller info 读取包名。仅当包名为 `com.vivo.musicwidgetmix` 时调用连接补发；不得对所有车联或蓝牙控制器广播完整 LRC。

不要只依据旧 Smali 行号打补丁。应结合 jadx 伪代码、Smali 方法签名、调用关系和参数语义验证。

## 6. 元数据与封面刷新优化

车端专辑图重复加载的根因是每句歌词变化都触发完整 MediaMetadata 更新。

正确策略：

- 当前歌词行变化：只调用 Apple Music MediaSession 管理器的 Extras 发布接口。
- 完整歌词或状态变化：更新 MediaItem 内部 Metadata Extras，再让播放管理器发布新的 MediaItem。
- Apple Music 自己覆盖元数据且车联字段消失：延迟补写一次。
- 仪表元数据去重只比较分页后的 `whole` 和 `status`，不能因为原始当前行变化而重建。
- 仪表 `LYRICS_LINE` 在 Metadata 写入时取当前分页行；后续翻页由 `LYRICS_WHOLE` 中的时间戳驱动。
- 原子随身听能力位：Apple Music 原生发布 MediaItem 时，只向它已有的 Metadata Extras 原位 OR 歌词能力位 `8`（`vivomusicmix.media.metadata.support_event`），然后让原生流程继续发布同一个对象，不替换 MediaItem，避免重置原生进度条。
- Apple Music 自己覆盖元数据：下一次原生发布 Hook 会再次幂等补入能力位。
- 原子随身听完整 LRC 通过 Session Extras 发布（`vivomusicmix.extra.key.lyric`），并在其控制器连接后重放一次。

6.5.2 中，辅助类通过反射访问：

```text
MediaItem 字段 d          -> metadata
Metadata 字段 I           -> extras Bundle
播放管理器方法 I(item, 0) -> Apple Music 原生 MediaItem 发布路径，只在方法内部插入能力位 Hook
MediaSession 管理器方法 j(Bundle) -> 发布会话 Extras
```

这些短名称高度不稳定，新版本必须重新确认。

## 7. 更新 APK 的标准流程

### 阶段 A：保存输入信息

1. 记录新版文件名、版本号、versionCode 和 SHA-256。
2. 不覆盖旧版本记录；为新版本保留独立说明或 Git 分支。
3. 私有 APK 不要提交到公开仓库。
4. 如需提交到 GitHub，将 APK 分割为小于 25 MB 的分片，并更新校验文件。

示例命名：

```text
input/parts/apple-music-X-Y-Z.apk.part.000
input/parts/apple-music-X-Y-Z.apk.part.001
input/SHA256SUMS
```

同时更新 workflow 中的 `APK_NAME`。

### 阶段 B：只分析，不重建

先运行 `APK analysis and rebuild`，参数 `rebuild=false`。

检查 artifact 中：

```text
badging.txt
permissions.txt
original-signature.txt
apktool.log
jadx.log
location-hits.txt
focused-context.txt
focused-sources.tar.gz
```

确认 APK 完整、包名正确，并重新定位第 4、5、6 节描述的对象。

### 阶段 C：更新辅助类

检查 `cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java` 中所有反射目标：

- 类名
- 构造函数
- 方法名和参数
- 字段名
- LiveData Observer 接口
- TTML 歌词行结构
- 当前播放位置和时长获取路径

如果 Apple Music 改用不同歌词模型，应按新模型调整解析，不要为了通过编译而吞掉所有错误。

### 阶段 D：重新制作 Smali 补丁

1. 在新版 apktool 输出中定位五个 Hook。
2. 生成新的 `cloud-patch/apple-vivo-car-lyrics.patch`。
3. 更新 `cloud-patch/apply.sh` 中目标 Smali 路径。
4. 更新 marker 检查，确保五个 Hook 均存在且位于对应方法体内。
5. 先执行补丁校验，再允许重建。

### 阶段 E：处理额外 DEX

当前 6.5.2 基线把辅助类加入为 `classes5.dex`；构建脚本会扫描 APK 中已有的 `classes*.dex`，自动选择下一个未占用的名称。

新版如果已经存在 `classes5.dex`，不能覆盖。应扫描现有 `classes*.dex`，选择下一个未使用编号，并同步修改：

- DEX 文件名
- 重复文件保护
- artifact 验证逻辑
- 报告文件名

### 阶段 F：重建和验证

运行 workflow，参数 `rebuild=true`。必须验证：

```text
apktool 重建成功
zipalign 成功
apksigner verify 成功
目标辅助类存在于新增 DEX
六个车联协议字符串、五个原子随身听协议字符串和辅助类 marker 存在
导出的 MediaPlaybackService 同时声明标准 MediaBrowser 和原子随身听合作 action
六个 Smali Hook 存在于对应方法体内
输出 APK SHA-256 已生成
```

## 8. 安装与签名限制

重建 APK 使用自有测试签名，不再拥有 Apple 官方签名。

- 通常不能覆盖安装官方 Apple Music。
- 卸载官方 App 通常会清除该 App 的数据和已下载歌曲。
- 新签名可能影响 Apple 登录、DRM、推送、账号校验或厂商完整性检查。
- 测试前应确认账号可重新登录，并接受离线下载需要重新下载。
- 不要声称调试签名包与官方包完全等价。

当前固定签名配置：

```text
GitHub Secret: ANDROID_SIGNING_KEY_BASE64
GitHub Secret: ANDROID_SIGNING_PASSWORD
Key alias: apple-music-vivo-car-lyrics
证书 SHA-256: 19de023cf6b5b4d02d4151b306dd6389a7720d36b066607bf43ba9ce61fb9e66
证书指纹文件: config/signing-cert-sha256.txt
```

密钥文件和密码不能提交到 Git。必须保留离线备份；若固定私钥丢失或被替换，之后的 APK 也无法覆盖安装。

run-7 是第一份使用当前固定签名的基线 APK，证书 SHA-256 与上面的固定指纹一致。后续使用同一组 Secrets 构建的 APK 可以直接覆盖 run-7，无需重复卸载安装。

run-6 APK 的证书 SHA-256 是
`40960a482d8ed1f69b1eaf06490add675cfd23d58b3d87d47906aae4bdb6c468`，其 Actions 临时私钥未保留。因此从 run-6 或 Apple 官方签名版本切换到固定签名版本时，仍需先卸载再安装。

## 9. 实车测试清单

1. 普通在线歌曲能播放，音质设置与修改前一致。
2. 无损、杜比全景声等原有能力没有被音频代码改动影响。
3. 换歌后标题、歌手、专辑、封面正确。
4. 歌词能自动出现，不需要打开歌词页面。
5. 正常播放时逐行同步。
6. 向前和向后拖动后，歌词在约 250 毫秒内跳到正确位置。
7. 每句歌词变化时专辑图不重新加载。
8. 暂停、恢复、上一首、下一首正确。
9. 无歌词歌曲显示正确状态，不残留上一首歌词。
10. 断开并重新连接车联后仍能恢复。
11. 原子随身听首次连接、断开重连和 Apple Music 已播放后再打开时都能显示当前完整歌词。
12. 原子随身听连续切歌不残留上一首歌词，无歌词歌曲会清空歌词。
13. 超过 20 个 UTF-16 单元的中英文长句会按时间戳显示后续分页，而不是永久停留在前 17 个字加省略号。

第 7 项只能确认 Apple Music 没有逐句重建 Metadata；如果车联自身在歌词分页变化时仍刷新封面，应记录为车联端行为，不能宣称已由 Apple Music 单包彻底消除。

## 10. 普通外挂方案的边界

包名为 `cn.kuwo.player` 的桥接 App 可以解决车联识别和控制转发，但普通 Android App只能读取 Apple Music 通过公共 MediaSession 暴露的数据。

Apple Music 6.5.2 的完整同步歌词主要存在私有 `PlayerLyricsViewModel` 和 TTML 对象中。普通外挂通常只能看到“有歌词”标记，不能保证得到歌词正文。因此：

- 无 Root、无修改：不能保证自动读取完整同步歌词。
- Root/LSPosed：可以在 Apple Music 进程中 Hook，但版本更新仍可能需要重新适配。
- 修改 Apple Music APK：当前最完整的自动歌词方案，但有重签名和更新适配成本。
- 独立歌词源：可避免修改 Apple Music，但歌词版本和时间轴可能与 Apple Music 不一致。

## 11. 文件职责

```text
.github/workflows/apk-pipeline.yml
  GitHub Actions 分析、重建和上传入口。

cloud-patch/apple-vivo-car-lyrics.patch
  对指定 Apple Music 版本的五个 Smali Hook。

cloud-patch/apply.sh
  应用补丁并验证 Hook marker。

cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java
  加载私有歌词、解析时间轴、同步当前行和完整歌词。

cloud-patch/java/com/apple/android/music/player/ClusterLyricsPaginator.java
  为仪表生成不超过 20 个 UTF-16 单元的分页 LRC 和递增时间戳。

cloud-patch/rebuild.sh
  重建原 APK、编译辅助类、加入额外 DEX、对齐、固定签名和验证。

config/signing-cert-sha256.txt
  固定测试签名证书的 SHA-256 pin；与 Secrets 中私钥不匹配时构建失败。

config/search-patterns.txt
  新版本分析时的初始搜索词。
```

## 12. 完成标准

后续 AI 只有在以下条件全部满足时才能说”适配完成”：

- 新版本已重新分析，不是盲目套用旧补丁。
- 五个 Hook 均按语义确认。
- 辅助类的全部反射目标已验证。
- GitHub Actions 构建成功。
- APK 签名、包名、DEX 和协议 marker 验证成功。
- 明确说明签名和卸载风险。
- 实车歌词、快进同步和封面刷新至少完成一次测试；未实测时必须明确标为”待实车验证”。

## 13. 版本变更记录

### r38 (2026-09-03) - 原子随身听进度条：补上 seek 能力位 16

通过 GitHub Actions 新增的 `Decompile Atomic Player APK` 工作流（`.github/workflows/atomic-decompile.yml`，从 Release `atomic-apk-6.2.5.6` 下载 APK 用 jadx 反编译）对原子随身听 6.2.5.6 做静态分析，定位到进度条根因：`SeekBarLayout` 只在 `duration > 0 && (support_event & 16) == 16` 时显示时间和进度；合作控制器 `c0` 原样使用应用发布的 `vivomusicmix.media.metadata.support_event`，而补丁只写了 `7 | 8 = 15`。详见 `docs/KNOWN_ISSUES.zh-CN.md` 第 1 节。

- `VivoCarLyrics.advertiseAtomicLyricSupport` 新增 `ATOMIC_SEEK_SUPPORT_EVENT = 16`，发布值变为 `7 | 8 | 16 = 31`
- `verify_source_contract.py` 要求该常量出现在能力位代码中
- 顺带修复 r37 遗留的两个构建阻塞：诊断探针里残留的 `META_LINE` 引用（改为字面量），以及 `rebuild.sh` 仍在检查 r36 marker 和已删除的 `ucar.*` 字符串
- 构建标识：`vivo-car-atomic-seek-bit-r38-2026-09-03`

### r37 (2026-09-03) - 停止向仪表推送歌词

删除 `META_LINE / META_WHOLE / META_STATUS` 的 session Extras 写入，只保留车机 `music.media.extras.*` 和原子随身听 `vivomusicmix.*`。`ClusterLyricsPaginator` 保留编译。该版本本身未成功构建（见 r38）。

### r12 (2026-09-01) - 三方合并：车机 + 仪表分页 + 原子随身听

将 release-44 分支（96312d1，车机和仪表分页）与 379acd9（原子随身听支持）合并至 feat/atomic-lyrics-on-main。

**保留特性：**
- 车机歌词：META_WHOLE / META_LINE / META_STATUS 写入 MediaMetadata Extras
- 仪表分页：ClusterLyricsPaginator 把长句拆成 ≤20 UTF-16 单元的多页 LRC；仪表使用 clusterWhole / clusterTimes / clusterTexts
- 原子随身听：vivomusicmix.* 协议字段、onNativeMediaItem 和 onAtomicControllerConnected 回调、能力位写入

**关键实现：**
- LyricsState 同时保存原始和分页时间轴
- META_WHOLE 使用 clusterWhole（仪表读取），vivomusicmix.extra.key.lyric 使用原始 whole（原子随身听读取）
- publishSessionExtras 中为仪表动态计算 clusterLine，为车机保留原始 safeLine
- 两条歌词路径完全独立，互不干扰

**构建标识：**
- BUILD_MARKER: vivo-car-atomic-lyrics-on-main-r12-2026-09-01
- 包含 ClusterLyricsPaginator.java 和 VivoCarLyrics.java
- apply.sh 验证 onNativeMediaItem 和 onAtomicControllerConnected 在正确位置
- rebuild.sh 验证所有协议字符串和分页 marker

### r21 (2026-09-01) - 原子随身听进度条修复（duration republish on looper）

在 r12 之前的分支中完成，修复原子随身听进度条问题。duration republish 改为在 playback manager 的 looper 上执行，避免线程检查抛出 InvocationTargetException。

### r10-r20 - 原子随身听调试与诊断

在 379acd9 分支中完成，包括 duration 诊断、能力位基线调整、null extras 防护等。这些改动已合并至 r12。

### r35 (2026-09-02) - 诊断关闭基准

关闭 `DIAGNOSTIC_MODE`（改为 `false`），撤销 r34 中的 `manager.I()` 重发布调用。诊断字段和方法保留编译但全部被门控，下次需要重新诊断时直接改回 `true` 即可，无需另行添加代码。

**当前状态：**
- 歌词（每首曲目）：正常，lrc 时间轴精确同步
- 车机歌词路径（music.media.extras.*）：正常
- 仪表分页（ucar.media.metadata.*）：代码存在，但仪表长歌词滚动问题**未解决**（见 KNOWN_ISSUES.md）
- 原子随身听歌词（vivomusicmix.extra.key.lyric）：正常，每首歌都有
- 原子随身听进度条：**未解决**（见 KNOWN_ISSUES.md）

**已确认的死路（后续不要重试）：**
- 往 `MediaItem.metadata.extras` 写 `DURATION`：对框架层 `android.media.session.MediaController` 有效（r32 诊断证明 `mDUR=258000`），但 Atomic 走的是独立的 compat session，`MediaSessionCompat.Token.fromToken(frameworkToken)` 返回 null（r33 诊断证明），两层完全独立，extras 写入对 compat 层无影响
- `manager.I(mediaItem, 0)` 重新发布 MediaItem：会重置 PlaybackState，进度条反而消失（r34 测试证明；与 f984add revert 原因相同）
- compat token 反射路径（A/B/C）：本地播放时全部 null；`I3.l` 是 MediaRouter/AirPlay 注册表，只在 Cast 活跃时有值

**构建标识：** vivo-car-atomic-diag-off-r35-2026-09-02

### r34 (2026-09-02) - MediaItem 重发布尝试（已撤销）

在 `onAtomicControllerConnected` 时调用 `manager.I(mediaItem, 0)` 触发 Media3 重新运行 `LegacyConversions`，目的是让 compat session 在播放器已知时长后更新 `METADATA_KEY_DURATION`。测试结果：进度条不但没有出现，连显示也变成不正常。原因与 f984add 中记录的完全相同——MediaItem 重新发布会重置 PlaybackState，进度条依赖 PlaybackState，发布一次进度条就消失。已在 r35 中撤销。

### r23–r33 (2026-09-02) - 原子随身听进度条诊断

对 Atomic Player 进度条问题进行了 11 轮诊断。核心发现：

1. 框架层数据完全正确（r32）：通过 `android.media.session.MediaController`（框架层）读到 `mDUR=258000 mSE=15 psST=3 psPOS=14078`，说明 Media3 的框架 session 里时长和能力位都正确
2. compat token 无法转换（r33）：`MediaSessionCompat.Token.fromToken(frameworkToken)` 返回 null，证明框架 token 和 Atomic 通过 `MediaBrowserCompat` 连接的那个 compat session 是两个完全独立注册的 session，不是同一个 session 的两种视图
3. 所有 compat token 路径（A/B/C）在本地播放时全部 null；`I3.l`（Apple Music 的 MediaRouter 注册表）在本地播放时 singleton 存在但其 MediaSessionCompat 字段为空（只在 AirPlay/Cast 活跃时有值）

诊断证明：问题不在于数据有没有写入，而在于 Atomic 的 compat session 和我们操作的框架层根本是两条独立的数据通道，目前没有找到从 Apple Music 侧修改、在不破坏 PlaybackState 的前提下向 compat session 注入 DURATION 的方法。

### r12 (2026-09-01) - 三方合并：车机 + 仪表分页 + 原子随身听

将 release-44 分支（96312d1，车机和仪表分页）与 379acd9（原子随身听支持）合并至 feat/atomic-lyrics-on-main。

**保留特性：**
- 车机歌词：META_WHOLE / META_LINE / META_STATUS 写入 MediaMetadata Extras
- 仪表分页：ClusterLyricsPaginator 把长句拆成 ≤20 UTF-16 单元的多页 LRC；仪表使用 clusterWhole / clusterTimes / clusterTexts
- 原子随身听：vivomusicmix.* 协议字段、onNativeMediaItem 和 onAtomicControllerConnected 回调、能力位写入

**关键实现：**
- LyricsState 同时保存原始和分页时间轴
- META_WHOLE 使用 clusterWhole（仪表读取），vivomusicmix.extra.key.lyric 使用原始 whole（原子随身听读取）
- publishSessionExtras 中为仪表动态计算 clusterLine，为车机保留原始 safeLine
- 两条歌词路径完全独立，互不干扰

**构建标识：**
- BUILD_MARKER: vivo-car-atomic-lyrics-on-main-r12-2026-09-01
- 包含 ClusterLyricsPaginator.java 和 VivoCarLyrics.java
- apply.sh 验证 onNativeMediaItem 和 onAtomicControllerConnected 在正确位置
- rebuild.sh 验证所有协议字符串和分页 marker

### r21 (2026-09-01) - 原子随身听进度条修复（duration republish on looper）

在 r12 之前的分支中完成，修复原子随身听进度条问题。duration republish 改为在 playback manager 的 looper 上执行，避免线程检查抛出 InvocationTargetException。

### r10-r20 - 原子随身听调试与诊断

在 379acd9 分支中完成，包括 duration 诊断、能力位基线调整、null extras 防护等。这些改动已合并至 r12。

### r9 (2026-08-29) - 仪表分页初版（release-44）

在 96312d1 提交中完成，引入 ClusterLyricsPaginator，按 20 UTF-16 单元拆分长句，为仪表生成独立时间轴。
