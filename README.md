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
| 千问 Coding Plan Individual | Qwen Cloud 国际站网页 Cookie，显示 5 小时 / 每周 / 每月额度 |
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

当前版本：**0.5.26**（versionCode 31）

- 0.5.26：新增千问 Coding Plan Individual，接入 Qwen Cloud 控制台真实用量接口（Cookie 鉴权）
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
