# 千问 Coding Plan 控制台会话额度接口

调研与改造日期：2026-11-12。

## 结论

从 0.6.7 起，安卓端接入**阿里云百炼 Coding Plan**，默认使用对应区域控制台的会话 Cookie 查询真实的 5 小时、每周、每月请求次数额度。套餐专属 API Key（通常为 `sk-sp-`）仍保留为可选备用；Cookie 非空时只发送 Cookie，不与 API Key 混发。

这两个产品不能混用：

- 本实现：`coding.dashscope.aliyuncs.com` / `coding-intl.dashscope.aliyuncs.com` 对应的 **Coding Plan**；额度查询走下方控制台 RPC。
- 旧实现：`home.qwencloud.com` 的 **Individual Token Plan**，其 `personal/api/v2/usage` 是另一套接口和响应结构。
- 普通 DashScope 按量 API Key 不等于 Coding Plan 专属 Key；App 会如实显示接口错误，不伪造额度。
- 此 Cookie 是 `bailian.console.aliyun.com` / `modelstudio.console.alibabacloud.com` 的控制台会话，不是旧版 `home.qwencloud.com` 的 Cookie。

## 区域与端点

| 区域 | 控制台 Origin | `currentRegionId` | `commodityCode` |
|:--|:--|:--|:--|
| 国际站 | `https://modelstudio.console.alibabacloud.com` | `ap-southeast-1` | `sfm_codingplan_public_intl` |
| 国内站 | `https://bailian.console.aliyun.com` | `cn-beijing` | `sfm_codingplan_public_cn` |

两区都使用同一个只读 RPC：

```text
POST {Origin}/data/api.json
  ?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2
  &product=broadscope-bailian
  &api=queryCodingPlanInstanceInfoV2
  &currentRegionId={区域}
```

控制台会话请求头（0.6.7 默认）：

```text
Cookie: 完整控制台 Cookie
Content-Type: application/json
Accept: application/json
Origin: <上表 Origin>
Referer: <对应区域 Coding Plan 控制台页面>
```

备用 API Key 请求头：

```text
Authorization: Bearer API_KEY
x-api-key: API_KEY
X-DashScope-API-Key: API_KEY
Content-Type: application/json
Accept: application/json
Origin: <上表 Origin>
Referer: <对应区域 Coding Plan 控制台页面>
```

JSON 请求体：

```json
{
  "queryCodingPlanInstanceInfoRequest": {
    "commodityCode": "sfm_codingplan_public_intl",
    "onlyLatestOne": true
  }
}
```

国内站把商品代码换成 `sfm_codingplan_public_cn`。控制台地址留空时，App 先请求国际站，未成功时自动请求国内站。三种 Key 头一起发送是兼容性实现，不代表官方承诺每个头都单独必需。

从 0.6.0 起，编辑页新增「控制台地址（分区，可空）」：

- 留空：保持上述国际站 → 国内站自动探测；
- 填国内或国际控制台首页 / 分区页面 / 完整 `/data/api.json` 地址：只请求对应分区，
  不再用另一分区的结果掩盖配置错误；
- 也允许自定义 HTTPS 控制台 / 代理 Origin。官方域名优先决定分区，自定义地址可在
  path、query 或 SPA fragment 中带 `ap-southeast-1` / `cn-beijing`（例如 `currentRegionId`）；
  未标分区时默认使用国内商品代码。

无论采用哪个地址，请求都会归一为 `{Origin}/data/api.json?...currentRegionId=...`。
仅接受 HTTPS，并拒绝带 userinfo 的地址，避免明文泄露 Key 或误认实际收件方。
自定义控制台会收到控制台 Cookie 或备用 Coding Plan API Key，应只填写自己信任的地址。

> 该额度 RPC 是控制台内部接口，不是官方公开、稳定性受保证的 REST API。0.6.7 优先使用控制台会话，正是为了兼容要求控制台登录的账号或区域。

## 响应解析

从多层 `data` / `DataV2`（包括字符串内嵌 JSON）中找到 `codingPlanInstanceInfos`，只采用 `status=VALID` 或 `ACTIVE` 的实例；兼容无状态字段的单实例响应。套餐名优先读 `instanceType`，其次 `planName` / `instanceName` / `packageName` / `specCode`。

额度计数位于实例的 `codingPlanQuotaInfo`：

| 窗口 | 已用 | 总额 | 下次重置 |
|:--|:--|:--|:--|
| 5 小时 | `per5HourUsedQuota` | `per5HourTotalQuota` | `per5HourQuotaNextRefreshTime` |
| 每周 | `perWeekUsedQuota` | `perWeekTotalQuota` | `perWeekQuotaNextRefreshTime` |
| 每月 | `perBillMonthUsedQuota` | `perBillMonthTotalQuota` | `perBillMonthQuotaNextRefreshTime` |

解析兼容 `perFiveHour*` / `perMonth*` 别名、数字字符串、秒 / 毫秒时间戳及 ISO 8601 时间。已用百分比为 `used / total * 100`；缺少已用值、总额或总额不为正时，不制造 0% 窗口。

## 证据与交叉核对

1. **阿里云官方 CLI**：`bl usage coding-plan` 使用相同 RPC、商品代码、`codingPlanInstanceInfos` 和三窗口字段。
   - <https://github.com/modelstudioai/cli/blob/main/packages/commands/src/commands/usage/coding-plan.ts>
   - 官方 CLI 的控制台模式使用 Console Access Token；本 App 默认直接复用用户提供的控制台 Cookie，也保留下述多个独立实现验证过的 API Key 请求头作为备用，三种鉴权路径不混淆。
2. **Qwen Code 官方文档**：确认 Coding Plan 专属 API Key、国内 / 国际推理 Base URL 与区域差异。
   - <https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/#alibaba-cloud-coding-plan>
3. **CodexBar**：`AlibabaCodingPlanUsageFetcher.swift` 明确实现 API Key 的三个请求头、两个区域及同样的响应字段。
   - <https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Alibaba/AlibabaCodingPlanUsageFetcher.swift>
   - <https://github.com/steipete/CodexBar/blob/main/docs/alibaba-coding-plan.md>
4. 另外交叉检查了 Quotio、OpenUsageCN、OmniRoute、multivibe 的独立实现，端点、请求头、商品代码和字段一致。

## 限制与安全

- 会话过期、分区错误或接口变化时，卡片会显示 HTTP 状态码与最多 500 字符的响应片段；不再只显示笼统的「失败」。
- 未读取或使用本机任何千问凭据；本次验证包括官方与多份开源实现交叉核对、无凭据端点行为检查，以及本地响应解析单元测试，不声称完成真实账号端到端测试。
- Cookie 存入现有 Android Keystore 加密的敏感 extra 字段，备用 API Key 存入加密主凭据；旧版千问 Cookie 不再在配置加载时删除。
- 所有请求均为只读额度查询；响应原文不写入启动缓存，避免额外保存账号信息。
