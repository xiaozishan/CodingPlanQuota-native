# 千问 Coding Plan Individual 接口调研

调研日期：2026-11-11。

## 结论

Qwen Cloud 国际站的 Individual Token Plan 可以读取真实套餐额度，但**不能使用推理 API Key**。安卓端采用与官方控制台相同的网页 Cookie 会话，先取得 `sec_token`，再调用控制台网关。

这里接入的是 `home.qwencloud.com` 的 Individual Token Plan，不是阿里云百炼按量 API 余额，也不是 `platform.qianwenai.com` 的大陆站会话。

### 不要与百炼 Coding Plan 混用

2026-11-12 的交叉调研还确认了阿里云百炼官方 CLI 的 `bl usage coding-plan`：它调用 `zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2`，使用百炼控制台 Bearer Access Token，返回 `codingPlanInstanceInfos`。那是另一项产品、另一套鉴权和响应结构，不能把本页的 Qwen Cloud Cookie 或 `personal/api/v2/usage` 拿来冒充百炼 Coding Plan 额度。若以后支持百炼 Coding Plan，应新增独立供应商，并参考[官方 CLI 实现](https://github.com/modelstudioai/cli/blob/fa8330c27862635f7d769c5488c33a06a47063d5/packages/commands/src/commands/usage/coding-plan.ts)；不要要求用户把高权限 AK/SK 直接内置到 APK。

## 接口

1. `GET https://home.qwencloud.com/tool/user/info.json`
   - 头：`Cookie: <用户复制的完整 Cookie>`
   - 从 `data.secToken` 取得安全令牌。
2. `POST https://cs-data.qwencloud.com/data/api.json`
   - 查询参数：`product=sfm_bailian`、`action=IntlBroadScopeAspnGateway`、`api=<接口名>`。
   - 表单：`sec_token`、`region=ap-southeast-1`、`params=<JSON>` 等。
   - `params` 含 `Api`、`V=1.0`、`Data.commodityCode=sfm_tokenplansolo_public_intl` 和控制台 `cornerstoneParam`。
   - 用量接口：`zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/usage`。
   - 可选套餐接口：`.../subscription`；可选次数配置：`.../quota-config`。
3. 用量字段：
   - 5 小时：`per5HourPercentage` / `per5HourResetTime`。
   - 每周：`per1WeekPercentage` / `per1WeekResetTime`。
   - 每月：`per1MonthPercentage` / `per1MonthResetTime`（兼容 `perMonth*`）。
   - 比例可能是 0–1 或 0–100；套餐次数从 `five_hour` / `weekly` / `monthly` 读取。

## 证据

- CodexBar 的 Qwen Cloud 文档和当前实现明确列出上述三个接口、Cookie 鉴权及 International Individual 产品代码：
  - <https://github.com/steipete/CodexBar/blob/main/docs/qwen-cloud.md>
  - <https://github.com/steipete/CodexBar/tree/main/Sources/CodexBarCore/Providers/QwenCloud>
- `alvinlui-hk/dsh-coding-plan-usage` 在 2026-11-07 的实现包含相同请求格式和真实响应样例；其解析测试已在本机通过：
  - <https://github.com/alvinlui-hk/dsh-coding-plan-usage/tree/279675d08582d7f976a585e4131cfb9dd36589c1>
- 无凭据实测 `GET /tool/user/info.json` 返回 HTTP 200 和 `{"code":"ConsoleNeedLogin","successResponse":false}`，证明控制台入口在线且应用能明确识别未登录状态。

未读取或使用本机任何千问凭据，因此本次不包含登录态下的端到端实测。接口属于控制台内部 API，后续字段变化时应用会报解析错误，不会伪造额度。

## 安全与展示

- Cookie 放在 `Provider.extra["cookie"]`，沿用 `Prefs` 的 Android Keystore 加密存储。
- 固定只向 `home.qwencloud.com` 与 `cs-data.qwencloud.com` 发送 Cookie，不开放自定义域名。
- 缺少 Cookie、把推理 Key 当成 Cookie、登录失效、无 `sec_token` 和额度字段缺失分别给出明确错误。
- `usage` 是必需接口；`subscription` / `quota-config` 仅补充套餐名、剩余天数和次数，可选接口失败不会掩盖已取得的真实用量。
