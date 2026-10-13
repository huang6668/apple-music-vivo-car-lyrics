# 交接文档：Apple Music 歌手名称本地化未生效排查与修复指南

> **1609 修复进度（2026-10-12）**：已改为基于用户提供的 Apple Music
> 7.0.0-beta / versionCode 1609，新增播放页歌手绑定、单曲双字段查询、
> 旧缓存补查和异步请求顺序保护。云端打包与最终 APK 校验正在进行，
> 设备显示效果尚未验证。详见 [1609 修复记录](ARTIST_LOCALIZATION_1609.zh-CN.md)。
> 下方 `100a059`、Build #159 及 `e1` / `z3.x` 映射均为 1607 历史排查记录，
> 不能直接用于新版 APK。

> **更新时间**：2026-10-12  
> **当前基线**：Commit [`100a059`](https://github.com/huang6668/apple-music-vivo-car-lyrics/commit/100a059)  
> **最新构建**：GitHub Actions [Build #159](https://github.com/huang6668/apple-music-vivo-car-lyrics/releases/tag/v1.0.0-build-159)（Run ID: 38185528827）  
> **涉及分支**：`main` 与 `feat/full-surface-title-correction`（两者已完全同步）

---

## 1. 问题背景与现象描述

- **用户反馈**：
  1. “如果听歌单里的歌那么歌手名称没有变，如果去搜索歌曲播放，歌手名称会变成中国地区的名称。改一下让歌单里的歌手名也会一同更改”。
  2. 安装测试 Build #158 / #159 后反馈：“歌手名并没有变”。
- **目标**：无论是从歌单播放、专辑播放、还是在歌单列表查看，歌手名均应与搜索播放保持一致，统一显示中国大陆地区的简体中文名称。

---

## 2. 目前已完成的代码改动

### 2.1 方案 A（界面切换与列表滚动流畅度重构）—— 已全部生效并通过 CI
1. **UI 读取无锁化**：`TitleCorrectionState` 新增 `ConcurrentHashMap<String, TrackInfo> fastTrackCache`，主线程列表渲染时直接内存无锁读取，解决切歌和切页面的掉帧问题。
2. **反射全局缓存**：`VivoCarLyrics` 中对 Epoxy 和 Item 的探测增加了 `METHOD_CACHE` 和 `FIELD_CACHE`，避免频繁反射。
3. **取消强制重构，增加防抖**：移除了 `requestForcedModelBuild`，改为 300ms 延迟防抖机制；在 `VivoCarLyrics` 中通过 `AtomicLong REFRESH_SEQUENCE` 代际版本号实现防抖（解耦了对 `Handler.removeCallbacks` 的依赖，契约测试 100% 通过）。
4. **批量预取（Batch Prefetch）**：歌单滑动浏览时按 100 首一批在后台聚合并发请求中国大陆 Catalog API。

### 2.2 歌手名称提取与存储基础设施 —— 已完成
1. **Catalog API 双字段解析**：`CatalogTitleResolver` 支持提取 `name`（歌名）与 `artistName`（歌手名），并调用 `ChineseConverter.toSimplified`。
2. **复合持久化与内存缓存**：`TitleCacheStore` 与 `TitleCorrectionState` 支持 `(title, artist)` 存储，向后兼容旧版 `\t` 分隔符格式。
3. **Smali 注入（初步）**：`cloud-patch/patch_item_title.py` Hook 了 `BaseContentItem.getArtistName()` 与 `Attributes.getArtistName()`。

---

## 3. 核心突破：歌手名未生效的底层根本原因（Root Cause Analysis）

在解包深入分析 `apk-results-159-report` 中的 smali 源码（特别是 `q8/na.smali` 和 `com/apple/android/music/player/e1.smali`）后，确认了以下三大关键断点：

### 关键断点 1：播放界面（Now Playing）绑定歌手根本没有调用 `getArtistName()`！
- **反编译定位**：
  查看播放界面数据绑定类 `q8/na.smali`（继承自 `q8/ma.smali`）：
  - 歌名（Title）控件是 `q0: CustomTextView`，它调用了 `v8.getTitle()`，在此处通过 `apple-vivo-car-lyrics.patch` 插入了 `correctPlayerTitle` Hook。
  - **歌手（Artist）控件是 `p0: CustomTextView`**，其赋值代码位于 `q8/na.smali`：
    ```smali
    # q8/na.smali 约 line 3190
    invoke-static {v8}, Lcom/apple/android/music/player/e1;->y0(Lcom/apple/android/music/model/PlaybackItem;)Ljava/lang/String;
    move-result-object v52
    move-object/from16 v54, v52
    ...
    # q8/na.smali 约 line 4202
    iget-object v0, v1, Lq8/ma;->p0:Lcom/apple/android/music/common/views/CustomTextView;
    move-object/from16 v3, v54
    invoke-static {v0, v3}, LI2/e;->a(Landroid/widget/TextView;Ljava/lang/CharSequence;)V
    ```
- **查看 `e1.y0(PlaybackItem)` 的实现**（`com/apple/android/music/player/e1.smali`）：
  ```smali
  .method public static y0(Lcom/apple/android/music/model/PlaybackItem;)Ljava/lang/String;
      ...
      invoke-interface {p0}, Lcom/apple/android/music/model/PlaybackItem;->getNowPlayingSubtitle()Ljava/lang/String;
      # 或者调用 CollectionItemView.getSubTitle()
      return-object p0
  .end method
  ```
  **结论**：播放界面展示歌手名时，调用的是 `PlaybackItem.getNowPlayingSubtitle()` 或 `CollectionItemView.getSubTitle()`，**压根没有调用 `BaseContentItem.getArtistName()`**！因此之前只 patch `getArtistName()` 在播放页完全失效！

---

### 关键断点 2：单曲播放时 `startTitleQuery` 只查歌名，不查歌手名
- **定位**：`cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java:501`：
  ```java
  CatalogTitleResolver.query(api, request.catalogId, TITLE_LANGUAGE, "F",
          new CatalogTitleResolver.Callback() {
              @Override public void onTitle(final String rawTitle) {
  ```
- **问题**：`CatalogTitleResolver.query` 的回调只有 `onTitle`，没有提取 `artist`。
- **后果**：
  - 单曲播放触发网络查询时，`TITLE_STATE.complete(request, title)` 内部没有得到新的 `artist`，导致内存缓存中的 `artist` 依然为空。
  - 随后调用 `persistTitle(request.catalogId, title)` 只将歌名存入磁盘，没有存入歌手名。

---

### 关键断点 3：歌单列表项（Epoxy Model）的歌手数据源
- 在歌单界面（`PlaylistPageController`），添加歌曲行使用的是 `addTrackModel(MediaEntity entity, int index)`。
- 我们目前在 `addTrackModel` 入口调用了 `VivoCarLyrics.onPlaylistTrack(this, entity)`：
  ```java
  String stockArtist = stringValue(invokeOptional(attrs, "getArtistName"));
  invokeOptional(attrs, "setArtistName", ChineseConverter.toSimplified(stockArtist));
  ```
- 如果缓存尚未命中（初次进入），虽然调用了 `enqueuePrefetch(id)`，但在异步回调回来前，页面已经用未汉化的属性渲染完成了；同时，`CollectionItemView` 的副标题展示也可能来自 `getSubTitle()` 或 `getSubtitle()`。

---

## 4. 下一个会话的排查与修复方案（Recommended Action Plan）

### 任务 1：修复播放界面（Now Playing）的歌手名展示
在 `q8/na.smali` 或 `com/apple/android/music/player/e1.smali` 中增加对歌手的 Hook：
- **方案 A（最直接且影响面广）**：
  在 `com/apple/android/music/player/e1.smali` 的 `y0` 方法返回处添加 Hook：
  ```smali
  invoke-static {p0, v0}, Lcom/apple/android/music/player/VivoCarLyrics;->resolvePlayerArtist(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
  ```
  这样无论播放哪里的歌曲，只要通过 `e1.y0` 获取副标题/歌手名，都会直接走缓存修正为简体中文。
- **方案 B（针对数据绑定层）**：
  在 `cloud-patch/apple-vivo-car-lyrics.patch` 中对 `q8/na.smali` 补充 patch：
  在 `invoke-static {v8}, Lcom/apple/android/music/player/e1;->y0...` 之后，将结果传给 `VivoCarLyrics.correctPlayerArtist(v1, v8, v52)`。

### 任务 2：改造 `startTitleQuery` 支持单曲查询歌手名
- 修改 `VivoCarLyrics.startTitleQuery`，调用能同时返回 `(title, artist)` 的查询接口（或者扩展 `CatalogTitleResolver.queryTrack`）。
- 在回调中调用 `TITLE_STATE.putCachedTrack(request.catalogId, title, artist)` 并执行 `titleCacheStore.putTrack` 持久化。
- 确保 `writeCorrectedTitle` 能够同时将 `artist` 写入 `z3.x` 元数据字段 `b` 以及 `StoreMediaItem.artistName`。

### 任务 3：歌单列表项副标题覆盖
- 检查 `BaseContentItem.smali` 中的 `getSubTitle()`、`getSubtitle()` 以及 `getNowPlayingSubtitle()`，必要时同样注入 `resolveItemArtist`。

---

## 5. 常用验证与构建指令

> 以下命令为 1607 时期的历史记录（`py -3`、直接推 main 等做法已不适用）。
> AM++ 路线已于 2026-10-13 废弃，workflow 不再有 `embed_ampp` / `standalone_module` 输入。

```bash
# 1. 本地代码契约检查
py -3 cloud-patch/tests/verify_source_contract.py
py -3 cloud-patch/tests/test_p0_classes.py
py -3 -m unittest cloud-patch/tests/test_item_title_patch.py

# 2. 推送远程（main 和 feat 两个分支保持一致）
git add .
git commit -m "fix(title): ..."
git push origin main
git push origin main:feat/full-surface-title-correction

# 3. 触发 GitHub Actions 打包流水线
gh workflow run apk-pipeline.yml --ref feat/full-surface-title-correction \
  -f rebuild=true
```
