# CodingPlanQuota-native（额度盘）

AI 编程套餐额度查看器，原生 Kotlin + Jetpack Compose 重写版。  
把各家 AI 服务的额度、余额、重置时间集中到一屏，手机上单列、平板上双列铺满。

## 支持的供应商

| 类型 | 说明 |
|:--|:--|
| DeepSeek | 余额（按 100 元满偏换算弧长） |
| Kimi | 5 小时窗口 / 本周窗口 |
| 智谱 GLM / 智谱团队版 | 配额与用量 |
| MiniMax / MiMo Token Plan | 配额 |
| 火山方舟 | 配额 |
| ZenMux / OpenCode Go | 配额（ZenMux 需自定义 Base URL） |
| ChatGPT Plus | OpenAI OAuth 登录，读 5 小时 / 本周额度 |
| Codex（ChatGPT / feelol） | access_token 方式 |
| Claude | OAuth access_token |
| Gemini（网页版） | Google 账号登录，读 gemini.google.com/usage 的 5 小时窗口与周额度 |
| Antigravity | Google OAuth + PKCE |
| GitHub Copilot | GitHub OAuth token，显示 Premium requests 剩余次数与月度总额 |
| 千问 Coding Plan | 默认使用阿里云控制台会话 Cookie，显示 5 小时 / 每周 / 每月额度；套餐 API Key 可作备用，支持国内 / 国际站与自定义控制台分区 |
| 自定义 JSON | 自填 Base URL 与解析规则 |

## 主要功能

- **圆弧仪表**：每条指标一个 270° 圆弧，纯色圆头；额度按「用得多 → 偏红」、余额按「剩得少 → 偏红」连续取色。
- **多账号合并**：设置里「多账号合并显示」默认开启，同一家服务的多个账号合并到一张卡；关闭后一账号一卡。
- **前台自动刷新**：回到前台立即刷新，默认每 60 秒定时刷新；设置里可开关并调整为 30 秒至 10 分钟，后台自动停止。
- **余额直读**：DeepSeek 等余额类卡片在弧心直接显示 `¥金额`，不显示百分比。
- **响应式布局**：手机（<600dp）单列；平板（≥600dp）固定两列并铺满屏宽；卡片内组件自适应。
- **登录方式**：ChatGPT Plus 走 OpenAI OAuth（PKCE），Gemini / Antigravity 走 Google OAuth，凭据加密保存在本机；同时保留手贴 token / session JSON 作为备用。
- **失败可见**：解析失败会明确提示（如「未登录或页面未加载」），不会用其他数据冒充。

## 构建

```bash
# 需要 JDK 17+、Android SDK 36
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

签名要求：`v2 = true`、`v3 = false`（部分国产 ROM 的安装器无法解析 v3 签名块）。

## 版本

当前版本：**0.6.7**（versionCode 42）

- 0.6.7：Dock 改连续曲率圆角；末尾增加 84dp 全透明滚动占位；卡片左滑展开红色删除按钮，带回弹与收起动画；千问默认改用控制台 Cookie，失败显示状态码和响应片段
- 0.6.6：底部 Dock 圆角增至 30dp
- 0.6.5：关闭系统状态栏 / 导航栏强制对比遮罩
- 0.6.4：系统栏零遮罩边到边，首页内容可穿到状态栏下；底部 Dock 仅胶囊保留白底，列表延伸到胶囊后方
- 0.6.3：首页大标题改为列表首项并随内容自然滚出，不再固定或反向滑回；Dock 选中项增加柔和光晕
- 0.6.2：收紧 Dock 高度与内外间距，压缩首页、设置和编辑页顶部留白；首页标题栏随列表滚动收起、反向滚动即恢复
- 0.6.1：Dock 改为酷安风白色长条、主色选中与中间圆角方形实心加号，平板侧栏同步更新
- 0.6.0：千问 Coding Plan 支持自定义控制台地址（分区）；留空仍自动探测国内 / 国际站
- 0.5.29：手机改用底部玻璃 Dock、平板改用左侧玻璃 Dock；＋ 打开带动画的供应商选择底部抽屉
- 0.5.28：千问 Coding Plan 改用套餐专属 API Key，接入 `queryCodingPlanInstanceInfoV2` 真实用量接口；不再使用 Cookie，支持国内 / 国际站自动探测
- 0.5.27：新增千问套餐额度卡片（旧 Qwen Cloud Cookie 方案，0.5.28 已整体替换）
- 0.5.25：GitHub Copilot 改显示 Premium requests 余额，不再展示 Chat / 代码补全百分比
- 0.5.24：回到前台立即刷新，默认每 60 秒自动刷新；设置里可开关并调整间隔
- 0.5.23：新增 GitHub Copilot，解析 Chat / 代码补全额度，兼容 Free 套餐字段
- 0.5.22：圆弧改纯色圆头，彻底消除渐变接缝色块；平板两列铺满
- 0.5.21：纯色弧替代 SweepGradient
- 0.5.20：去掉内容限宽，两列铺满屏宽
- 0.5.18：多账号合并改为按供应商通用，余额弧只显示金额
- 0.5.15：270° 圆弧仪表、绿→红连续取色
- 0.5.13：Gemini 网页端额度、ChatGPT OAuth、平板响应式

发布页：<https://github.com/xiaozishan/CodingPlanQuota-native/releases>

## 说明

- 所有凭据只保存在本机，使用 Android Keystore 加密。
- 网页端额度依赖页面结构，接口变动时可能短暂失效，失败会明确提示而不是给假数据。
