# 已知未解决问题

最后更新：2026-09-04

本文档记录当前版本（r38 基准）中确认存在但尚未解决的问题，以及已经验证行不通的修复方向，避免后续重复踩坑。

---

## 1. 原子随身听进度条不显示（已解决，r38，2026-09-04 实车确认）

> 2026-09-04 用户实车确认：r38（Release `v1.0.0-build-83`）进度条正常。本节保留根因分析和死路表，供后续移植参考。

**现象（r10–r37）：** 歌词正常显示，但原子随身听界面上的进度条控件没有时长数据，始终显示 `--:--`，进度为 0。

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

**实车结果（2026-09-04）：** 进度条正常显示。第 9 节清单第 11 项担心的"首曲短暂 `--:--`"时序问题未被观察到。

**已验证的死路（不要重试）：**

| 方法 | 结论 | 依据 |
|---|---|---|
| 往 `MediaItem.metadata.extras` 写 `DURATION` | 无意义：DURATION 本来就到了，问题是 16 位 | r32 `mDUR=258000` |
| `manager.I(mediaItem, 0)` 重新发布 MediaItem | 破坏进度条：重置 PlaybackState | r34 测试；与 f984add revert 原因相同 |
| compat token 反射路径 A/B/C、`Token.fromToken` | 方向错误，compat 层不是独立通道 | 见上文 |

---

## 2. 仪表盘长歌词截断、每句歌词都重载封面（根因在车联侧，Apple Music 侧不修）

**现象（2026-09-03 实车，r38）：** 车机端一切正常；仪表盘上超过 20 个字的歌词行被截成前 17 个字加 `...`，不滚动；每来一句新歌词，仪表盘的专辑图背景都会随歌词一起重新加载一次。

**根因（2026-09-03，jadx 反编译车联 `com.vivo.car.networking` 6.0.8.3 静态分析确认，APK 存于本仓库 Release `carnetworking-apk-6.0.8.3`，用 `Decompile vivo APK` 工作流可复现）：**

两个问题都在车联的仪表发送类 `gg/d.java`（`HudManager`）里，Apple Music 侧无法影响：

```java
// gg/d.java  内部类 C0317d.excute()  —— CarLife 仪表路径（bb.a.g(la.c.x().u())）
String trim = this.f22279a.getLineLyrics().trim();
...
} else if (trim.length() > 20) {
    trim = trim.substring(0, 17) + "...";      // 写死：>20 字就截成 17 字 + "..."
}
intent.putExtra("song", trim);                 // 仪表只收到一条字符串，没有整首 LRC，没有滚动
intent.putExtra("cover", this.f22280b);        // 每次都附带封面 Bitmap
this.f22281c.p(intent);                        // AIDL 发给 CarLife（com.baidu.carlife.vivo）
```

```java
// gg/d.java  a.l(CarMusicInfo)
if (bb.a.g(la.c.x().u())) {
    if (!carMusicInfo.equals(d.this.f22270b)) {  // CarMusicInfo.equals 包含 lineLyrics
        d.this.n(carMusicInfo, coverBitmap);       // 所以每换一句就整包重发（含封面）
    }
    d.this.r(carMusicInfo);
}
```

- 截断：车联写死 `substring(0,17)+"..."`。仪表通道（CarLife `carlife_vehicle_music_info` intent 的 `song` 字段）只承载一条字符串，协议上不存在滚动或整首歌词。
- 封面重载：`CarMusicInfo.equals()` 把 `lineLyrics` 算进相等判断，行变化即重发完整音乐信息，而封面 Bitmap 是车联自己塞进每个 intent 的；车端收到新封面就重绘背景。

**车联到底怎么拿到歌词的（对后续移植很重要）：**

- 车联 6.0.8.3 **从不读取** `music.media.extras.LYRIC / LYRIC_IS_ALLOWED / NOTICE_CAR`：这三个 key 只在常量类 `aa/b.java` 里定义，整个包里没有任何读取点。
- 车联实际消费的是我们发给原子随身听的整首 LRC 事件：`eg/l.java onExtrasChanged()` 判断 `vivomusicmix.meida.extra.key.action == vivomusicmix.extra.lrc_change` 且 `meidia_id`、`lyric` 非空 → `jg.e.g0(lyric)` → `fg/l.java`（WholeLyricManager，解析器 `ub/e.java`）按播放位置自行切出当前行 → `jg.e.f25613f`。这一个字符串同时喂给车机（`fg.c.y` → LauncherProxy）和仪表（`HudManager`）。整首 LRC 另经 `fg.c.w` 发给车机歌词页。
- 另一条输入是 `MediaMetadata` 里的 `ucar.media.metadata.LYRICS_WHOLE / LYRICS_LINE`（`q9/a.java`、`eg/l.java c()`）。r9–r36 把分页后的 LRC 写在 **session extras** 的同名 key 里，车联从不读 session extras 里的这两个 key，所以当年"分页无效"的真正原因是写错了通道，与仪表固件无关。
- 因此 3.1 节的 `music.media.extras.*` 对 6.0.8.3 是无效负载（保留只是为了兼容其它车联版本），**原子随身听的 `lrc_change` 事件才是车机歌词的真正来源**，不能删。

**Apple Music 侧为什么不修：**

| 方案 | 结论 |
|---|---|
| 把发布的整首 LRC 按 ≤20 字切段（`ClusterLyricsPaginator` 已能算出 `clusterWhole`） | 技术上可让仪表轮播长句，但原子随身听和车机歌词页读的是同一个 key、同一套解析器，会一起变成切段行；而且每段都触发一次封面重发，封面重载问题反而加重。用户决定不采用。 |
| 给车联和原子发不同的 LRC | 不可行：两者都读 `vivomusicmix.extra.key.lyric`，后写覆盖先写。用 mediaId 区分也不行——原子在 `t4/d0.java E0()/z1()` 里要求 mediaId 等于当前歌曲否则隐藏歌词，车联只要求非空。 |
| 通过 `MediaMetadata` 的 `ucar.media.metadata.LYRICS_WHOLE` 单独喂车联 | 需要在歌词加载完成后重发 MediaItem，是第 1 节已验证的死路。 |
| 停止封面重发 | 封面由车联自己附带，Apple Music 无任何杠杆。 |
| 改车联 APK | 需要 vivo 签名私钥，改了装不上。 |

结论：维持 r38 现状。将来若车联新版本改了 `HudManager`（例如 ucar 路径 `ControlChannel.sendMusicInfo` 不截断、只在封面变化时发送），再重新评估。

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
