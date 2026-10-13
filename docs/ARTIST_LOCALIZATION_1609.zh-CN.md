# 1609 歌手显示修复记录

更新：2026-10-12。目标为用户提供的 Apple Music 7.0.0-beta，
versionCode 1609，四 ABI、七 DPI 的 APKM。

## 范围

- 默认手机 APK，不依赖任何 Hook 框架（AM++ / NPatch / LSPosed 路线已于
  2026-10-13 废弃）。
- 不提供地区模式；继续使用 `cn_v1` 缓存、中国大陆优先查询及已有的
  `cn -> tw -> hk` 缺失回退，结果统一转为简体。
- “本地化”使用目录返回的 `artistName`，不对歌手名做机器翻译。
  如果目录没有中文艺名，允许保留目录原名；查询缺失或失败时保留宿主显示。
- 保留六个歌词 Hook、车机/Atomic Extras 协议及能力位 `7|8|16`。
  `publishMetadata()` 仍为空操作，不重新发布 MediaItem、PlaybackState 或封面。

## 已确认映射

| 用途 | 1609 |
| --- | --- |
| 播放管理器 | `com.apple.android.music.player.P` |
| 原生 MediaItem 入口 | `P.P(Lz3/w;I)V` |
| Session 回调 | `player.d0.o(LJ4/g2;LJ4/g2$e;)V` |
| 元数据 / MediaItem | `z3.y` / `z3.w` |
| 元数据标题 / 歌手 / Extras | `y.a` / `y.b` / `y.J` |
| 元数据到播放项转换器 | `player.O.b(Lz3/y;)PlaybackItem` |
| 播放页绑定 | `q8.na.l()V`，58 locals 保持不变 |
| 标题 / 歌手控件 | `q8.ma.o0` / `q8.ma.n0` |
| 播放页重绑定 | `q8.ma.q0(PlaybackItem)`（`r0(a1)` 不是 item setter） |
| 原始副标题计算 | `player.a1.y0(PlaybackItem)` |
| 通知刷新 | `J4.Z2.g(J4.g2, boolean)` |

## 修复内容

1. `a1.y0` 的副标题结果在绑定层经 `correctPlayerArtist` 修正。
   普通歌曲不再依赖 `getArtistName()` 是否被界面调用。
   古典作曲者显示和 contentType 9 的格式化副标题保留宿主行为。
   `BasePlaybackItem` 自有歌手 getter 与 `Song.getSubTitle()` 的
   歌手回退分支也单独校验，显式歌曲副标题及专辑名不被覆盖。
2. 单曲 ID/ISRC 查询使用 `queryTrack`，返回同一匹配歌曲的标题与歌手；
   账号目录仅用于 ISRC 身份探测，不作为本地化结果。
   目录解析读取响应的原始字段，不调用已经被展示缓存 Hook 修改的 getter。
3. 内存及磁盘通过 `artistResolved` 区分旧纯标题缓存和完整查询。
   旧标题立即显示并补查歌手；成功结果的空歌手不会造成重复查询。
4. 后台持久化捕获不可变的 `(catalogId, title, artist)`，
   不读取届时可能已经切换的当前播放状态。
5. 播放页重绑定按 generation、对象及双字段值去重。
   同一首歌只改变歌手时也会刷新，不改变歌词/进度发布路径。
6. 歌单/专辑的歌手缓存独立于标题命中，预取完成会刷新活跃列表和当前播放项。
   异步缓存更新按请求顺序保护，旧批次及旧磁盘回读不能覆盖较新完整结果。
   无响应预取在 15 秒后释放占位，迟到结果不应用；播放请求超时后不再派发新的备用查询，
   已在途的迟到结果只写入内存与磁盘缓存，不改界面。

## 验证

- 本地仅运行 Python 契约/补丁测试及 shell 语法检查。
- Java 单测、集成测试、APK 解包/编译、固定签名和最终 smali 校验均在
  GitHub Actions 运行，不在本地安装构建依赖。
- 构建编号、校验结果与 APK 摘要在最终构建完成后补入。
- 尚未连接可用 adb 设备，因此不能把 CI 成功视为实机歌手显示验证。
