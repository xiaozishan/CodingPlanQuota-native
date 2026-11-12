# QuotaGauge 性能与体积优化记录

日期：2026-11-08。基于 `versionCode 30` / `versionName 0.5.25`，版本号和签名配置未改。

## 改动

- **启动缓存优先**：`Prefs` 新增轻量 JSON 额度缓存。首次组合同步读取 `SharedPreferences` 中的上次成功结果并立即作为 UI 初始状态，读取路径没有网络调用；后台每家刷新成功后通过 `apply()` 写回。缓存只保留界面展示字段，不保存可能很大或含敏感信息的原始响应，失败结果不会覆盖为缓存成功项。
- **并发刷新、逐卡亮**：所有刷新入口统一走 `coroutineScope + async + awaitAll()`。每个 `async` 完成后立即把该供应商结果合并进 `results`，不再等待最慢的一家。`QuotaRefresh` 日志记录每家的开始相对时间、耗时和刷新总耗时，可在真机上用 `adb logcat -s QuotaRefresh:D` 核验并发时序。
- **网络超时**：连接超时 15s → 6s，读取超时 25s → 8s。
- **Gemini WebView TTL**：同一进程内缓存成功的网页额度结果 5 分钟；缓存检查位于互斥锁内，并发调用命中时不重复加载网页。失败结果不进入 TTL 缓存。
- **R8 / 资源压缩**：Release 开启 `isMinifyEnabled` 和 `isShrinkResources`，使用 AGP 默认优化规则、最小项目规则和 AndroidX/Compose 构件自带 consumer rules。未新增运行时依赖。
- **Compose 确定性小优化**：`Provider`、`Tier`、`QuotaResult` 标记 `@Immutable`；账号分组、合并账号仪表盘条目和原始 JSON 端点分析使用 `remember`。保留已有网格稳定 `key`；未引入 Vulkan/OpenGL、自定义渲染或重型动画。
- **缓存测试**：新增编解码往返、原始响应/失败结果排除、空或损坏缓存容错测试。

## 实测构建数据

同一源码、同一 Release 构建类型下比较：

| 构建 | 字节数 | MiB |
|---|---:|---:|
| 优化前（未混淆 Release） | 6,670,996 | 6.362 |
| 优化后（R8 + 资源压缩 Release） | 1,478,922 | 1.410 |
| 减少 | 5,192,074（77.83%） | — |

根目录原有 `QuotaGauge_v0.5.25.apk` 是 7,211,291 字节（6.877MiB）的 Debug 构建；若相对该包比较，新包减少 79.49%。两种口径分开记录，避免把 Debug/Release 差异算作 R8 收益。

- 构建任务：`:app:testDebugUnitTest :app:assembleRelease`
- 结果：`BUILD SUCCESSFUL`，R8 与资源压缩任务均实际执行。
- 测试：6 个测试，0 失败（其中新增缓存测试 2 个）。
- 产物：`app/build/outputs/apk/release/app-release.apk`
- 优化后 SHA-256：`B2AA3BB83111AF1F88768D00C79338FD2C0A118E5950B26CE84A9BED767A8349`
- 体积要求：1.41MiB，远低于 10MB。

## 签名与验收边界

`apksigner verify -v --print-certs` 实测：

- v1 = false
- **v2 = true**
- **v3 = false**（v3.1 同样为 false）
- v4 = false
- Manifest 无 `application-debuggable` 标记，即 **debuggable = false**

项目原本缺少 `keystore.properties`，本机验收包使用现有 APK 相同的 Android Debug 证书完成打包；构建脚本中的 release/debug 签名逻辑和 v3 关闭设置均未修改。正式发布仍应由发布方提供正式密钥。

当前没有连接中的 Android 设备，因此未伪造冷启动耗时或真机并发日志。启动无网络等待由首次组合直接初始化缓存的代码路径保证，缓存编解码已由单元测试覆盖；真机冷启动显示与逐卡时序仍建议安装产物后复核。可选 Baseline Profile 本次未加入。

## v0.5.26：Antigravity 两池与连续曲率边框

### 池化规则

- `retrieveUserQuota` 与 `fetchAvailableModels` 两条解析路径共用同一个池化函数，只输出 `Claude 池`、`Gemini 池`，不再把逐模型明细放进 `tiers`。
- 模型名包含 `claude` / `gemini`（不区分大小写）才入池，其它模型忽略；池内 `usedPct` 取最大值。
- 重置文案取同池 `resetMs` 最早的条目；该条目没有可用文案或全池没有时间戳时回退到最差条目。重置时间同时兼容 ISO 8601、Unix 秒和毫秒。
- 两个池都为空时，原有兜底请求和错误文案分支未改。

### Squircle 参数与边框

- 新增 `SquircleShape`，每个角由三次贝塞尔曲线构成；`smoothing=0` 使用标准圆弧系数 `0.55228475`，`1` 使用最大平滑系数 `1.0`。默认 `cornerSize=26.dp`、`smoothing=0.6`，尺寸不足时半径自动减半约束，LTR/RTL 共用对称路径。
- 默认参数集中在 `UiDimens`。外层卡片 26.dp，内层面板/控件 10.dp，卡片内容基准内边距 16.dp，满足同心关系 `26 = 10 + 16`。
- 首页卡片、对话框、登录错误面板、按钮、输入框和选择芯片使用对应 squircle；卡片与面板描边为 `0.8.dp`，沿用主题 `outline`，由组件按同一条 Shape 路径绘制。仪表盘弧线、配色和既有间距未改。

### 构建与验收

- 版本：`versionCode 31` / `versionName 0.5.26`；`isMinifyEnabled=true`、`isShrinkResources=true`，签名脚本仍为 v1+v2 开启、v3 关闭。
- 执行：`:app:testReleaseUnitTest :app:assembleRelease`，最终结果 `BUILD SUCCESSFUL`，R8 与资源压缩均执行。
- 单测：9 个，0 失败、0 错误、0 跳过；其中新增 Antigravity 主路径/兜底/未知模型池化测试 3 个。
- 产物：`QuotaGauge_v0.5.26.apk`（同步位于 `app/build/outputs/apk/release/app-release.apk`），**1,478,921 字节（1.410 MiB）**，较 0.5.25 的 1,478,922 字节减少 1 字节。
- SHA-256：`1789CEEC5B60B2B04E425BD18757DA5D96BD628B633E7185395C8CE94E0AB6BE`。
- `apksigner verify -v --print-certs`：v1=false、**v2=true**、**v3=false**、v3.1=false、v4=false。
- 本机仍无正式 release keystore；本次沿用上一版的 Android Debug 证书做验收包，通过临时且被 `.gitignore` 排除的 `keystore.properties` 提供，未改项目签名规则。正式分发仍需正式密钥重签。
- 已完成代码、单测、Release 构建与签名验证；未连接 Android 设备，因此 squircle 的真机视觉与触控验收未执行。
