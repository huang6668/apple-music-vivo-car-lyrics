# 独立歌名修正（不依赖 AM++）实施计划

> 状态：**计划已批准，尚未实施**。本文档只记录方案、已确认的事实和未决问题，不代表代码已改动。

## 1. 背景

目标：只要 AM++ 的“修正歌曲名”功能，移植到 Apple Music 7.0.0-beta（1607），不需要 AM++ 的其他功能。

### 1.1 AM++ 嵌入方案为何失败

AM++ 的标题修正链路：

```
TitleCorrectionFeature.install
  └─ getTitleCorrectionEnabled() == true
       └─ TargetAdaptation.getHleMetadata().install()
            └─ HLE 覆盖注册表 + AppleInternalCatalogResolver（多语言/批量/缓存）
```

在 1607 上依次遇到的问题：

| 问题 | 已尝试的处理 | 结果 |
| --- | --- | --- |
| `titleCorrectionEnabled` 默认值为 false | `patch_force_title_correction.py`（1c7bb9a）强制设为 true | 通过 |
| 版本 profile 关卡拒绝 1607 | `patch_force_embedded_profile.py`（3ebc33c）强制让 `supports()` 返回 true | 通过 |
| `AppleMusicHostProfiles.find` 对 1607 返回 null | — | `AppleMusicHostFactory.appleMusic` 抛出 `IllegalStateException: Required value was null` |

结论：缺的是整套 host profile（类名、方法名映射），绕过关卡只会把失败往后推，这条路不可维护。上面两个 patch 视为死路，只作为可选实验保留（`embed_ampp`）。

### 1.2 新方向

在已经注入的 helper dex（`VivoCarLyrics`）里直接实现标题修正，使用 Apple Music 自带的播放对象和 catalog 服务：

- 可见目标：Apple Music **正在播放页** + **通知**中的标题。
- 不变约束：**绝不重新发布 MediaItem / 重建 MediaMetadata**，Atomic 歌词、seek、封面、进度条的行为保持 r38 基线不变。

## 2. 已确认的 1607 映射

| 用途 | 1607 中的符号 | 备注 |
| --- | --- | --- |
| 播放管理器 | `com.apple.android.music.player.S` | 旧版为 `player.Q` |
| 当前项 getter | `S.c()`，回退到 `S.a()` | |
| MediaItem | `z3.v`，metadata 字段 `d` | |
| MediaMetadata | `z3.x`，标题字段 `a`（`CharSequence`，jadx 中为 `f87814a`） | extras Bundle 字段是 `J`（旧版为 `I`） |
| metadata 转换器 | `player.Q.b(z3.x)` | 用 `metadata.a` 调用 `setTitle` |
| metadata 构建 | `player.Q.f(MediaPlayerController, PlayerQueueItem)`（`m17335f`） | 通过 `item.getTitle()` 取标题 |
| 队列项实现 | `StoreMediaItem`，包级私有字段 `title:Ljava/lang/String;` | 没有 setter；`PlayerMediaItem` 接口只有 `getTitle()` |
| 媒体库查询 | `C13426U`（`MediaPlaybackManager$lookupCurrentItemInLibrary$1`） | 会再次调用 `S.P(...)`，只更新库状态/喜欢状态，不改标题 |
| Catalog 查询 | `w9.Q.F` / `w9.a.F`：实例方法 `Object X(String, Map, Continuation)` | 旧版为 `s8.F.x`、`u8.E.v` |

Catalog 方法的匹配规则沿用 `cloud-patch/ampp/java/dev/amenhancer/compat/CatalogQueryMethod.java`：首选名 → 重命名映射 → 唯一签名兜底，有歧义时拒绝，不猜。

### 2.1 现有的 6 个 smali hook（保持不变）

见 `cloud-patch/apple-vivo-car-lyrics.patch`：

| Hook 位置 | 调用 |
| --- | --- |
| `S.P(Lz3/v;I)V` | `onNativeMediaItem(p1)` |
| `S.onCurrentItemChanged` | `onCurrentItemChanged(p0, p3)` |
| `S.onMetadataUpdated` | `onMetadataUpdated(p0, p2)` |
| `S.onPlaybackError` | `onPlaybackError(p0)` |
| `S.seekTo(J)` | `onSeek(p0, J)` |
| `e0.o(LJ4/f2;LJ4/f2$e;)V` | `onAtomicControllerConnected(包名)` |

## 3. 实施步骤

### 第 1 步：在 `VivoCarLyrics` 中新增独立的标题状态

- 与 `LyricsState` 及歌词发布状态完全分离。
- 跟踪：单调递增的请求 generation、队列 ID、规范化后的 catalog/store ID、persistentId、原始标题、进行中标记、解析结果，以及按稳定 catalog 身份做键的有界 LRU 缓存。
- `persistentId` 仅作为回退身份和日志上下文，不能直接当作 Catalog song ID 发起查询；只有确认是正十进制 Catalog ID 才能进入请求。
- 复用现有工具：`queueMediaId`、`queueKey`、`currentMediaItem`、`getMetadataExtras`、`invokeOptional`、`findCompatibleMethod`、主线程 `Handler`，不另起一套反射框架。
- `onCurrentItemChanged` 中初始化/重置；`onMetadataUpdated` 只在 ID 更完整时补全并重试。每个稳定身份/generation 只发一次请求，而不是每次 metadata 回调都发。
- 异步回调里重新校验 manager、generation、队列 ID、catalog ID；对应的曲目已经切走时，只写缓存，不改当前显示。

### 第 2 步：精简的 catalog 标题解析器

- 写在 `VivoCarLyrics` 中或作为独立的辅助类（由 `cloud-patch/rebuild.sh` 一起编译），**不引入任何 AM++ 类**，也不打包 LSPosed/NPatch 模块。
- 候选顺序：`w9.Q.F` → `w9.a.F` → 旧版 `s8.F.x` / `u8.E.v`，全部经过严格的签名校验（实例方法；参数恰好是 `String`、`Map`、`kotlin.coroutines.Continuation` 接口；返回 `Object`；有歧义就拒绝）。
- 通过代理 `Continuation` 异步调用，只解析歌曲标题。反射失败、结果为空、非歌曲内容、与原标题相同，都按无操作处理。
- **编码前需先确认**（来源：AM++ 1.6.4 的 dexdump + 1607 分析产物）：宿主 catalog 实例怎么获取、请求 URL/参数形状、响应中标题所在的路径。
- 不移植：AM++ 设置、HLE 注册表、艺人/专辑本地化、缓存持久化、原生 DexKit 加载、功能安装机制。

### 第 3 步：原地修改内存中的标题，绝不发布替换对象

- 在现有的 `onNativeMediaItem(Object)` hook 中，在 Atomic 能力位/时长处理之后，若 `ITEM_QUEUE_ID` 与当前标题状态匹配，就把缓存中的修正结果写入 `z3.x.a`。
- 若队列项是兼容的 `StoreMediaItem`，同时写入它的 `title` 字段，这样 Apple Music 之后自己重建 metadata（`Q.f` → `getTitle()`）时也能拿到修正值。字段不存在时不做任何操作。
- 在正在播放页的标题接缝处新增**一个** smali hook（stock 标题计算之后、写入 UI 绑定/状态之前）：
  - 入参：带标题的队列/内容对象 + stock 字符串；返回 `CharSequence`。
  - 缓存未命中或身份不匹配时原样返回 stock 值。
  - 注册现有的 UI 状态/绑定，catalog 结果返回后在主线程通过同一机制刷新。
- 禁止调用 `S.P(...)`、`S.I(...)`、`publishMetadata(...)` 及任何等价的 MediaItem/MediaMetadata 重建方法；`publishMetadata` 保持明确的空操作。

### 第 4 步：扩展 patch 和校验（在确定 UI 接缝之后）

- `S.smali` / `e0.smali` 中现有的 6 个 hook 原样保留。
- 在确定的正在播放页 binder/mapper 中只加一个标题 hook，`.locals` 只增加必要的最小值；在 patch 注释里写明 1607 的方法签名、源值寄存器和插入点。
- `cloud-patch/apply.sh`：校验目标类/方法唯一存在、静态 helper 调用恰好出现一次、插入点位于 stock 标题计算和 UI 赋值之间。
- `cloud-patch/rebuild.sh`：最终回编译检查中加入同样的唯一性/顺序断言，以及 helper dex 中的标题修正标记；现有的 S/e0 hook、签名、manifest、helper dex 完整性检查全部保留。

### 第 5 步：回归测试

- 标题状态机的纯 Java 单元测试：稳定 ID 选择、缓存命中/未命中、重复回调抑制、切歌后迟到的结果、过期队列 ID 被拒绝、空结果/同名结果、播放出错时重置。
- 在 `cloud-patch/tests/catalog_query/CatalogQueryMethodTest.java` 旁边加解析器方法测试：`w9.Q.F`、`w9.a.F`、旧版映射、继承方法、错误的参数/返回类型/静态方法、歧义候选。
- 扩展 `cloud-patch/tests/verify_source_contract.py`：
  - 继续断言没有 MediaItem 重新发布、没有 metadata 替换。
  - 目前全局禁止 `setFieldValue(`，需要加一个**范围受限的例外**：只允许在专用的标题修正方法中写标题字段，`publishMetadata`、Atomic、session extras 路径中依然禁止。
  - 继续断言 `7|8|16` 能力位和 `publishMetadata` 空操作契约。
- 新增 patch 文本测试：标题 UI hook 的描述符、调用次数，以及相对 stock 标题赋值的位置。分页测试和 embed 兼容测试照常运行。

### 第 6 步：与 AM++ 嵌入隔离，更新文档

- 普通构建（`embed_ampp=false`）即包含独立实现，不下载、不初始化 AM++/NPatch。
- 现有的 AM++ 嵌入脚本/产物作为单独的历史实验保留，不作为普通构建或测试的前提。
- 更新 `docs/AMPP_INTEGRATION.zh-CN.md` 和 `docs/APK_UPDATE_GUIDE.zh-CN.md`：1607 的 profile 失败原因、新的独立架构、标题显示范围、1607 映射，以及“标题修正不重新发布歌词/Atomic metadata”这一不变约束。

## 4. 验证

1. 在 GitHub Actions 中跑 Python 源码/patch 契约测试，以及 Java 分页、catalog 反射、标题状态测试（不在本机安装 Android 构建工具）。
2. 触发普通构建：`rebuild=true`、`embed_ampp=false`；在报告中检查 helper 标记字符串、最终 dex/回编译结果、smali hook 位置、固定签名证书、Atomic 服务 manifest action。
3. 在设备上安装签名后的 APK，强制停止并重启 Apple Music，抓应用进程的 logcat，确认 catalog 解析和标题写入没有反射错误。
4. 测三类曲目：需要修正的英文标题、已本地化的标题、无匹配/非歌曲项。确认结果返回前显示原标题，返回后只在匹配的曲目上显示修正标题（正在播放页 + 通知）。
5. 在请求进行中快速切歌，确认旧结果不会出现在新歌上。
6. 车机回归：歌词加载、逐行更新、Atomic 重连、进度条时长、seek、封面稳定性。
7. `dumpsys media_session` 只作观察，不作为通过标准（build #97 上 AM++ 修正了应用内和通知标题，但 media_session 中仍是原始英文标题）。

## 5. 风险与未决问题

| 项 | 说明 |
| --- | --- |
| 正在播放页标题接缝 | 确切的 smali 方法（`getTitle()` → player binder/ViewModel）尚未定位，是第 3、4 步的前置条件。 |
| Catalog 调用细节 | 宿主实例获取方式、请求参数、响应解析路径尚未从 AM++ 1.6.4 dexdump / 1607 产物中提取，是第 2 步的前置条件。 |
| 写字段与源码契约冲突 | `verify_source_contract.py` 全局禁止 `setFieldValue(`，需要把例外限定到专用方法，避免松动对 metadata 的保护。 |
| media_session 标题 | 由于禁止重新发布 MediaMetadata，车机/系统媒体会话中的标题可能仍是原标题，这是有意的取舍，不属于本计划的目标范围。 |
| 混淆名漂移 | 后续 Apple Music 版本的 `z3.x.a`、`w9.Q.F` 等名字可能改变，解析器必须在签名不匹配时安静地不做任何操作。 |
| 约束 | 所有 APK 分析/打包都在 GitHub Actions 中进行；push/gh 前先设置 7897 端口代理并确认推送成功；r38 歌词通道行为冻结。 |
