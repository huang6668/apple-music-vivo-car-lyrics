# 已知未解决问题

最后更新：2026-09-02

本文档记录当前版本（r35 基准）中确认存在但尚未解决的问题，以及已经验证行不通的修复方向，避免后续重复踩坑。

---

## 1. 原子随身听进度条不显示

**现象：** 歌词正常显示，但原子随身听界面上的进度条控件没有时长数据，始终显示为空或 0/0。

**已确认的根因（r32 + r33 诊断数据）：**

原子随身听的进度条依赖它通过 `MediaBrowserCompat → MediaControllerCompat`（support library compat 层）连接时读到的 `METADATA_KEY_DURATION`。这个值由 Media3 的 `LegacyConversions` 从 `player.getDuration()` 独立计算并写入 compat session，和 Apple Music 的 MediaItem extras 是两条完全独立的数据通道。

具体诊断结果：
- r32：通过框架层 `android.media.session.MediaController` 读到 `mDUR=258000 mSE=15 psST=3`，框架层数据完全正确。
- r33：`MediaSessionCompat.Token.fromToken(frameworkToken)` 返回 null，证明框架 token 和原子随身听连接的 compat session 是两个完全独立注册的 session，不是同一个 session 的两种视图。

**已验证的死路（不要重试）：**

| 方法 | 结论 | 依据 |
|---|---|---|
| 往 `MediaItem.metadata.extras` 写 `DURATION` | 无效：只影响框架层，compat 层完全独立 | r33 诊断 `D5e compat=tok=null` |
| `manager.I(mediaItem, 0)` 重新发布 MediaItem | 破坏进度条：重置 PlaybackState，进度条反而消失 | r34 测试；与 f984add revert 原因相同 |
| compat token 反射路径 A（`I3.l.e()`）| 本地播放时返回 null；`I3.l` 是 MediaRouter/AirPlay 注册表，只在 Cast 活跃时有值 | r28 诊断 `D1 A=sing=ok` 但 token=null |
| compat token 反射路径 B（`k0.h` 字段遍历）| null | r28 诊断 |
| compat token 反射路径 C（`k0.b` 字段遍历）| null | r28 诊断 |
| `MediaSessionCompat.Token.fromToken(frameworkToken)` | 返回 null；两个 session 完全独立，不可互转 | r33 诊断 `D5e compat=tok=null cDUR=-2` |

**可能的后续调查方向（未尝试）：**

原子随身听在 `onConnect` 回调时可能通过 `MediaBrowserCompat.subscribe` 主动拉取一次 metadata，如果那个时刻 compat session 里的 DURATION 已经是正确值，进度条就会正常。需要确认：

1. 原子随身听 `c0` 控制器的 `onMetadataChanged` 回调在 Atomic 连接后是否会被再次触发（如果 Media3 在 Atomic 连接之前已经把正确 DURATION 写入 compat session，那么 Atomic 连接时拿到的值应该已经是正确的——那样的话问题根本不是 DURATION 缺失，而是另一个原因）。
2. `c0` 里进度条 widget 是否有额外的显示条件（除 DURATION 之外）。

这两个问题只能通过对原子随身听 APK 的更深入静态分析（jadx 反编译 `c0.java`）或者通过给原子随身听的 `onMetadataChanged` 打诊断日志来确认，目前没有条件。

---

## 2. 仪表盘（instrument cluster）长歌词不滚动

**现象：** 仪表盘分页逻辑代码已存在（`ClusterLyricsPaginator`），但在实车上超过 20 个 UTF-16 单元的长句仍然不会滚动，而是永久停留在前 17 个字加省略号（`…`）。

**已知情况：**

`ClusterLyricsPaginator` 会把长句拆成多条不超过 20 UTF-16 单元的子句，并为每条子句分配递增时间戳，写入 `clusterWhole`。`META_WHOLE`（`ucar.media.metadata.LYRICS_WHOLE`）里发送的就是这个分页后的 LRC。

问题尚未定位到具体位置，有以下几种可能：

1. **仪表盘固件读取逻辑**：仪表盘可能只取整首歌词 LRC 里的第一条时间轴条目作为"当前行"，而不是根据播放位置动态选择。如果固件的滚动逻辑不依赖时间轴而是依赖 `LYRICS_LINE`（当前行），那么分页写入 `LYRICS_WHOLE` 无效，需要改为在 `LYRICS_LINE` 里直接按时间轴翻页。
2. **时间戳间距太小**：分页子句的时间戳递增量（当前为逐毫秒递增）可能被固件忽略或合并，导致显示效果没有变化。需要测试较大的时间间隔（如每页间隔 500-1000ms）。
3. **分页逻辑在超长中文字符上有 bug**：中文字符 UTF-16 编码通常每字 1 个单元，但部分 emoji 和罕见汉字是 2 个单元（代理对）。`ClusterLyricsPaginator` 的 20 单元切割是否正确处理了代理对需要验证。

**未尝试的调试步骤：**

- 在 `DIAGNOSTIC_MODE = true` 时把 `clusterWhole` 内容打印到原子随身听歌词头部（目前诊断只打印 session probe 数据），确认分页 LRC 内容确实发送出去了。
- 检查实车上仪表盘固件对 `META_LINE` 和 `META_WHOLE` 的处理优先级：如果固件优先使用 `META_LINE` 作为显示内容，那么分页需要在 `META_LINE` 上实现动态翻页，而不只是写 `META_WHOLE`。
- 对应的代码路径：`VivoCarLyrics.java` 的 `publishSessionExtras` 方法里，`clusterLine` 的计算逻辑（通过 `lineForPosition` 从 `clusterTimes / clusterTexts` 选当前行）和 `META_LINE` 的写入。

---

## 3. 诊断工具保留说明

`VivoCarLyrics.java` 里的诊断代码（`probeSessionAsAtomicSees`、`diagnosticHeader` 等方法，以及 `sessionProbeDiag`、`atomicConnectDiag`、`trackStartUptime` 字段）在 r35 中全部保留编译，通过 `DIAGNOSTIC_MODE = false` 门控。

再次需要诊断时，只需把该常量改为 `true` 并触发构建，歌词文本头部会出现 D1-D6 诊断行，不需要重新添加代码。

诊断行格式（`DIAGNOSTIC_MODE = true` 时）：
```
D1 A=<tokenPathA> B=<tokenPathB> C=<tokenPathC> D=<tokenPathD> [meta=y/n ps=y/n]
D2 mDUR=<duration> mSE=<supportEvent>
D3 psST=<state> psPOS=<position> psACT=<actions(hex)>
D4 keys=<metaKeyCount> ucarLine=y/n
D5 ourSE=<ourSupportEvent> ourDUR=<ourDuration>
D6 conn=y@<ms>/n
D5e compat=<compatCtlStatus> cDUR=<compatDuration>
```
