# 已知未解决问题

最后更新：2026-09-03

本文档记录当前版本（r38 基准）中确认存在但尚未解决的问题，以及已经验证行不通的修复方向，避免后续重复踩坑。

---

## 1. 原子随身听进度条不显示（r38 已定位根因，待实车验证）

**现象：** 歌词正常显示，但原子随身听界面上的进度条控件没有时长数据，始终显示 `--:--`，进度为 0。

**根因（2026-09-03，jadx 反编译原子随身听 6.2.5.6 静态分析确认）：**

进度条的显示条件不在时长，而在能力位。原子随身听 `SeekBarLayout` 是否显示时间和进度由 `MusicWidgetManager`（`t4.d0`）的两个方法决定：

```java
// t4/d0.java
public boolean Y0() {            // isSupportSeek
    if (B0() <= 0) return false; // B0() = getDuration()
    return b1.c(P0(), 16);       // P0() = getSupportEvent(); b1.c(a, m) == ((a & m) == m)
}
public boolean Z0() {            // isSupportTimeInfo，逻辑完全相同
    if (B0() <= 0) return false;
    return b1.c(P0(), 16);
}
```

`SeekBarLayout.refreshPosition()` 在 `isSupportTimeInfo == false` 时直接 `setProgress(0)` 并把当前/总时长都设为 `--:--`，根本不去看 DURATION。

`getSupportEvent()` 的值取决于原子随身听选择的控制器：

| 控制器 | 何时选中 | support_event 来源 |
|---|---|---|
| `y2`（通用 MediaSession） | 包名不在合作名单 | 原子自己算：`PlaybackState.actions` 含 `ACTION_SEEK_TO(256)` 则 23（7\|16），否则 7 |
| `c0`（合作控制器） | 应用 Manifest 声明 `com.vivo.musicwidgetmix.support.service` | **原样读取** `MediaMetadata` 里的 `vivomusicmix.media.metadata.support_event` |

我们的补丁只写了 `7 | 8 = 15`（r32 诊断 `mSE=15` 与之吻合），缺少 bit 16。所以在合作路径下 `Y0()/Z0()` 恒为 false，进度条永远 `--:--`；而在通用路径下原子自己把 16 位补上了，进度条正常。这就是 "manifest action 一加进度条就没" 的真正原因，与 DURATION 无关。

**之前 "compat 与框架层是两条独立通道" 的结论是错的。** 原子随身听自带的 `MediaControllerCompat`（`MediaControllerImplApi21`）就是 `new android.media.session.MediaController(context, token.getToken())` 的薄包装，`getMetadata()` 直接 `MediaMetadataCompat.fromMediaMetadata(framework.getMetadata())`。r32 用框架层读到的 `mDUR=258000 mSE=15` 就是 `c0.k0()` 看到的值：时长本来就有，只是 16 位没有。r33 的 `fromToken` 返回 null 应是补丁侧反射失败，不能作为两层独立的证据。

**已知的能力位含义（来自原子随身听源码）：**

| bit | 用途 | 依据 |
|---|---|---|
| 1/2/4 | 基础播控（原子对未知应用默认 7） | `b1.a()` / `getSupportEvent()` 默认值 |
| 8 | 歌词 | `f5/n.java`、`f5/b.java`：`b1.c(P0(), 8)` |
| 16 | 进度条 / seek / 时间显示 | `t4/d0.java` `Y0()/Z0()` |
| 32 | 自定义颜色 / 互传 | `SettingsMainView`、锁屏组件 |
| 64 | 列表入口 | `MusicControlPanelView`、`f5/w0.java` |
| 128 | 未确认 | `f5/w0.java` |

**r38 修复：** `VivoCarLyrics.advertiseAtomicLyricSupport` 改为 OR 入 `7 | 8 | 16 = 31`。Apple Music 的 PlaybackState 本身带 `ACTION_SEEK_TO`，seek 走 `MediaControllerCompat.getTransportControls().seekTo()`，无需其它改动。

**仍需实车确认：**

1. 进度条是否随之出现（预期出现）。
2. 换歌瞬间 Media3 的 DURATION 可能短暂为 0（`B0() <= 0`），`SeekBarLayout.refresh()` 只在两个标志都为 false 时重新评估，理论上会自愈；若观察到首曲 `--:--` 而后续正常，就是这个时序。

**已验证的死路（不要重试）：**

| 方法 | 结论 | 依据 |
|---|---|---|
| 往 `MediaItem.metadata.extras` 写 `DURATION` | 无意义：DURATION 本来就到了，问题是 16 位 | r32 `mDUR=258000` |
| `manager.I(mediaItem, 0)` 重新发布 MediaItem | 破坏进度条：重置 PlaybackState | r34 测试；与 f984add revert 原因相同 |
| compat token 反射路径 A/B/C、`Token.fromToken` | 方向错误，compat 层不是独立通道 | 见上文 |

---

## 2. 仪表盘（instrument cluster）长歌词不滚动

> r37 起已不再向仪表推送任何 `ucar.media.metadata.*` 键，本节仅作历史记录；若将来恢复仪表歌词，需要重新面对下面的问题。

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
