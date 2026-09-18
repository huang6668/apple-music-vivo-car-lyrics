# AM++ 嵌入版兼容调试

更新：2026-09-17。分支：`embed-ampp-npatch`。

## 上游 PR

已提交 [Zennmn/AM-plus-plus#57](https://github.com/Zennmn/AM-plus-plus/pull/57)，
源码提交 `db55bc6`。包含 nullable nativeLibraryDir 的空值处理，以及
已验证的 `s8.F.B` → `s8.F.x` 查询兼容；不包含本项目的 NPatch 原生库提取代码。
因此该 PR 的空值修复不等同于解决所有原生库加载问题。
JDK 17 / Kotlin 1.9.24 编译并运行了 22 项相关 JVM 测试，全部通过；
提交时尚无上游 Android CI 结果。手机仍保留已验证的 #97，不受 PR 影响。

## 安装约束

- 不卸载 Apple Music，不清除数据；只允许同签名 `adb install -r`。
- 签名证书必须匹配 `config/signing-cert-sha256.txt`。
- r38 的宿主补丁与歌词发布逻辑不变，兼容修复位于嵌入的 AM++ 模块。
- Actions 的 `apple-music-vivo-car-lyrics-ampp-npatched` artifact 才是嵌入版。
  同一次运行发布到 Release 的普通 APK 不含 AM++，两者不可混淆。

## 已验证的故障链

设备：vivo V2454A / Android 16；Apple Music 6.5.2 (1586)；
AM++ v1.5.5；NPatch 741。

### #95：框架已加载，但 HLE 初始化空指针

运行日志同时出现：

```text
loaded in com.apple.android.music; framework=NPatch API=102
HLE metadata runtime install failed
getOrDefault(...) must not be null
```

NPatch 返回的模块 `ApplicationInfo.nativeLibraryDir` 为 null。
Kotlin `runCatching { ... }.getOrDefault("")` 不会替换成功返回的 null，
因此 HLE 在进入歌曲目录查询之前失败。

`ModuleNativeLibrary.java` 修复模块原生库目录：原目录有效则直接使用，
否则从模块 APK 提取匹配进程 ABI 的 `libdexkit.so` 到宿主私有 code cache。
`patch_native_directory.py` 仅替换 HLE 的这一处目录读取。

### #96：初始化已修复，目录查询方法名不匹配

实机确认原生库成功加载、`title_correction: ACTIVE`，
但实际查询重复报错：

```text
java.lang.NoSuchMethodException: s8.F#B(String,Map,Continuation)
```

作者的 APK 使用 `s8.F.B(String, Map, Continuation)`；
r38 使用的源 APK 对应方法是 `s8.F.x(String, Map, Continuation)`，
而 `B` 已变成另一签名。
静态对照两份 DEX：对应查询方法均为 353 条指令，操作码序列一致，
内部均通过 `s8.F$c` 调用目录接口 `u8.h.b`。

`CatalogQueryMethod.java` 保留优先方法查找，
只有 `s8.F` 的首选 `B` 不匹配时才尝试已确认的 `x`。
还需满足实例方法、Object 返回值、String/Map/Continuation 参数约束；
未知映射拒绝猜测。`patch_catalog_query.py` 只替换该查询方法的反射定位。
构建 #97（提交 `dac713a`，Actions run `35206181366`）加入此修复并通过
Java 兼容测试。APK SHA-256：
`28dd00cfc7f80a4432ad4848eb09cdaf9c52b8d191e66c02fe99b7b5735de413`。
已核对固定签名与 r38 备份一致。首次覆盖安装被手机拒绝；
再次通过 `adb install -r` 成功覆盖，设备更新时间为
2026-09-17 17:57:44。命令返回 dexopt profile 校验和警告，
但安装路径和更新时间均已变化，启动后的新日志也证实修复已加载。

2026-09-17 用户确认“生效了”。实机日志验证：

- `Hair Like Snow` 查询得到 `发如雪 / 周杰伦 / 11月的萧邦`，
  并出现应用内元数据覆盖成功记录。
- `Pull Apart` 查询得到 `扯 / 周杰伦 / 我很忙`，
  应用内元数据覆盖成功，通知标题和歌手也出现替换记录。
- #96 的 `s8.F#B(String,Map,Continuation)` 查询异常不再出现。

验证边界：当次 `dumpsys media_session` 仍显示 `Pull Apart, Jay Chou`。
因此本次确认的是用户可见的歌名修正、应用内覆盖及通知文字替换，
不代表系统 MediaSession 或车机端所有显示面均已同步中文名称。
保持 #97 安装，不因这个独立观察继续改动用户已确认可用的版本。

### 独立的歌词字段歧义

`current_song_identity` 和 `custom_lyrics` 仍报告
`player.fragment.e#U` / `player.fragment.l#c` 两个 BaseContentItem 字段有歧义。
这与上述两个歌名查询故障不是同一处错误，不应混作唯一根因。
另外仍有部分页面专用元数据 Hook 未匹配；不能据 `ACTIVE` 宣称所有页面正常。

## 构建与验证

```sh
python3 -m unittest discover -s cloud-patch/tests -p 'test_*.py' -v
gh workflow run 'APK analysis and rebuild' --ref embed-ampp-npatch \
  -f rebuild=true -f embed_ampp=true
```

嵌入步骤允许独立失败，因此必须检查该步骤自身成功且 APK/校验和均存在，
不能只看整个工作流的绿色状态。Java 测试在 `prepare-module.sh` 中执行。

安装前验证 APK 校验和与固定证书；覆盖安装后检查 `pm path`、
`lastUpdateTime`，不要把 dexopt profile 警告直接当成安装失败。

框架文件日志可能只有启动信息，功能健康状态与查询异常应从
`adb shell logcat -d --pid=<Apple Music PID>` 检查。
最终必须确认目录查询返回有效名称，并通过 `dumpsys media_session`
及实际播放页面验证；`title_correction: ACTIVE` 只代表安装阶段完成。
