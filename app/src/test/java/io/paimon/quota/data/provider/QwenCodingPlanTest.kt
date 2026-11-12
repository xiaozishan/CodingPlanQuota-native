package io.paimon.quota.data.provider

import io.paimon.quota.data.HttpResp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import io.paimon.quota.data.QuotaApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenCodingPlanTest {

    @Test
    fun missingApiKeyProducesSpecificErrorThroughProviderRouter() = runBlocking {
        val result = QuotaApi.fetch(
            Provider(
                id = "qwen-test",
                kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问测试",
            ),
        )
        assertFalse(result.ok)
        assertEquals("还没填千问 Coding Plan API Key", result.error)
    }

    @Test
    fun parsesAllThreeWindowsFromNestedLiveShape() {
        val result = QwenCodingPlanProvider.parse(
            envelope(
                """
                {
                  "codingPlanInstanceInfos": [
                    {
                      "status": "VALID",
                      "instanceType": "pro",
                      "remainingDays": 44,
                      "codingPlanQuotaInfo": {
                        "per5HourUsedQuota": 25,
                        "per5HourTotalQuota": 100,
                        "per5HourQuotaNextRefreshTime": 1794506400000,
                        "perWeekUsedQuota": "50",
                        "perWeekTotalQuota": "200",
                        "perWeekQuotaNextRefreshTime": "2026-11-16T00:00:00+08:00",
                        "perBillMonthUsedQuota": 10,
                        "perBillMonthTotalQuota": 1000,
                        "perBillMonthQuotaNextRefreshTime": 1795190400000
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("5 小时", "本周", "本月"), result.tiers.map { it.label })
        assertEquals(25.0, result.tiers[0].usedPct, 0.0001)
        assertEquals(25.0, result.tiers[1].usedPct, 0.0001)
        assertEquals(1.0, result.tiers[2].usedPct, 0.0001)
        assertEquals("25\n/ 100", result.tiers[0].valueText)
        assertEquals("50\n/ 200", result.tiers[1].valueText)
        assertNotNull(result.tiers[0].resetAt)
        assertNotNull(result.tiers[1].resetAt)
        assertEquals("Coding Plan pro · 剩余 44 天", result.message)
    }

    @Test
    fun parsesDirectDataAndStringEmbeddedJson() {
        val embedded = JSONObject()
            .put(
                "data",
                JSONObject()
                    .put(
                        "DataV2",
                        JSONObject().put(
                            "data",
                            JSONObject().put(
                                "data",
                                JSONObject().put(
                                    "codingPlanInstanceInfos",
                                    """[{"status":"ACTIVE","planName":"lite","codingPlanQuotaInfo":{"per5HourUsedQuota":0,"per5HourTotalQuota":1200}}]""",
                                ),
                            ).toString(),
                        ),
                    ),
            )
        val result = QwenCodingPlanProvider.parse(HttpResp(200, embedded.toString()))

        assertTrue(result.ok)
        assertEquals(0.0, result.tiers.single().usedPct, 0.0001)
        assertEquals("0\n/ 1,200", result.tiers.single().valueText)
        assertEquals("Coding Plan lite", result.message)
    }

    @Test
    fun skipsInvalidInstanceAndUsesValidQuota() {
        val result = QwenCodingPlanProvider.parse(
            envelope(
                """
                {"codingPlanInstanceInfos":[
                  {"status":"EXPIRED","codingPlanQuotaInfo":{"per5HourUsedQuota":99,"per5HourTotalQuota":100}},
                  {"status":"VALID","instanceType":"starter","codingPlanQuotaInfo":{"perWeekUsedQuota":20,"perWeekTotalQuota":80}}
                ]}
                """.trimIndent(),
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("本周"), result.tiers.map { it.label })
        assertEquals(25.0, result.tiers.single().usedPct, 0.0001)
        assertEquals("Coding Plan starter", result.message)
    }

    @Test
    fun rejectsExpiredOnlyInstanceInsteadOfShowingItsQuota() {
        val result = QwenCodingPlanProvider.parse(
            envelope(
                """{"codingPlanInstanceInfos":[{"status":"EXPIRED","codingPlanQuotaInfo":{"per5HourUsedQuota":99,"per5HourTotalQuota":100}}]}""",
            ),
        )
        assertFalse(result.ok)
        assertEquals("未找到有效 Coding Plan 套餐额度；请确认这是套餐专属 API Key", result.error)
    }

    @Test
    fun rejectsResponseWithoutMeasurableCounters() {
        val result = QwenCodingPlanProvider.parse(
            envelope("""{"codingPlanInstanceInfos":[{"status":"VALID","codingPlanQuotaInfo":{}}]}"""),
        )
        assertFalse(result.ok)
        assertEquals("千问返回中没有可计算的 5 小时 / 本周 / 本月额度", result.error)
    }

    @Test
    fun reportsConsoleLoginRequirementWithoutCookieFallback() {
        val result = QwenCodingPlanProvider.parse(
            HttpResp(200, """{"code":"ConsoleNeedLogin","successResponse":false}"""),
        )
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("API Key"))
        assertTrue(result.error.orEmpty().contains("ConsoleNeedLogin"))
    }

    @Test
    fun buildsOfficialApiKeyRequestsForBothRegions() {
        val international = QwenCodingPlanRegion.INTERNATIONAL
        val china = QwenCodingPlanRegion.CHINA
        val headers = QwenCodingPlanProvider.apiHeaders(international, "test-key")

        assertEquals("Bearer test-key", headers["Authorization"])
        assertEquals("test-key", headers["x-api-key"])
        assertEquals("test-key", headers["X-DashScope-API-Key"])
        assertFalse(headers.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertTrue(QwenCodingPlanProvider.requestUrl(international).startsWith(
            "https://modelstudio.console.alibabacloud.com/data/api.json?",
        ))
        assertTrue(QwenCodingPlanProvider.requestUrl(international).contains(
            "action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2",
        ))
        assertTrue(QwenCodingPlanProvider.requestUrl(china).startsWith(
            "https://bailian.console.aliyun.com/data/api.json?",
        ))
        assertEquals(
            "sfm_codingplan_public_intl",
            JSONObject(QwenCodingPlanProvider.requestBody(international))
                .getJSONObject("queryCodingPlanInstanceInfoRequest")
                .getString("commodityCode"),
        )
        assertEquals(
            "sfm_codingplan_public_cn",
            JSONObject(QwenCodingPlanProvider.requestBody(china))
                .getJSONObject("queryCodingPlanInstanceInfoRequest")
                .getString("commodityCode"),
        )
    }

    @Test
    fun resolvesOfficialConsoleOverridesAndCanonicalRpcUrl() {
        val china = QwenCodingPlanProvider.resolveConsole(
            "https://bailian.console.aliyun.com/cn-beijing/?tab=model#/efm/coding_plan",
        )!!
        val international = QwenCodingPlanProvider.resolveConsole(
            "https://modelstudio.console.alibabacloud.com/data/api.json" +
                "?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2",
        )!!

        assertEquals("cn-beijing", china.regionId)
        assertEquals("sfm_codingplan_public_cn", china.commodityCode)
        assertEquals("https://bailian.console.aliyun.com", china.gateway)
        assertEquals("ap-southeast-1", international.regionId)
        assertEquals("sfm_codingplan_public_intl", international.commodityCode)
        assertTrue(QwenCodingPlanProvider.requestUrl(international).startsWith(
            "https://modelstudio.console.alibabacloud.com/data/api.json?",
        ))
        assertTrue(QwenCodingPlanProvider.requestUrl(international).contains(
            "currentRegionId=ap-southeast-1",
        ))
    }

    @Test
    fun buildsRequestForCustomHttpsConsoleAndExplicitRegion() {
        val endpoint = QwenCodingPlanProvider.resolveConsole(
            "https://quota.example.com:8443/proxy?currentRegionId=ap-southeast-1",
        )!!
        val headers = QwenCodingPlanProvider.apiHeaders(endpoint, "test-key")

        assertEquals("https://quota.example.com:8443", endpoint.gateway)
        assertEquals("ap-southeast-1", endpoint.regionId)
        assertEquals("sfm_codingplan_public_intl", endpoint.commodityCode)
        assertEquals("https://quota.example.com:8443", headers["Origin"])
        assertEquals("Bearer test-key", headers["Authorization"])
        assertTrue(QwenCodingPlanProvider.requestUrl(endpoint).startsWith(
            "https://quota.example.com:8443/data/api.json?",
        ))
        assertEquals(
            "sfm_codingplan_public_intl",
            JSONObject(QwenCodingPlanProvider.requestBody(endpoint))
                .getJSONObject("queryCodingPlanInstanceInfoRequest")
                .getString("commodityCode"),
        )
    }

    @Test
    fun rejectsUnsafeConsoleUrls() {
        assertNull(QwenCodingPlanProvider.resolveConsole("http://bailian.console.aliyun.com"))
        assertNull(QwenCodingPlanProvider.resolveConsole("https://key@bailian.console.aliyun.com"))
        assertNull(QwenCodingPlanProvider.resolveConsole("https://"))
        assertNull(QwenCodingPlanProvider.resolveConsole("not a url"))
    }

    @Test
    fun rejectsInvalidCustomConsoleBeforeAnyNetworkRequest() = runBlocking {
        val result = QwenCodingPlanProvider.fetch(
            Provider(
                id = "qwen-custom-invalid",
                kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问自定义分区",
                baseUrl = "http://bailian.console.aliyun.com",
                token = "test-key",
            ),
        )
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("HTTPS"))
    }

    @Test
    fun resolvesHashStyleInternationalConsole() {
        val endpoint = QwenCodingPlanProvider.resolveConsole(
            "https://proxy.example/#/ap-southeast-1/",
        )!!
        assertEquals("ap-southeast-1", endpoint.regionId)
        assertEquals("sfm_codingplan_public_intl", endpoint.commodityCode)
    }

    @Test
    fun automaticRoutingStopsAfterInternationalSuccess() = runBlocking {
        val calls = mutableListOf<String>()
        val result = QwenCodingPlanProvider.fetch(
            Provider(id = "qwen-auto-ok", kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问自动", token = "test-key"),
        ) { url, _, _ ->
            calls += url
            envelope("""{"codingPlanInstanceInfos":[{"status":"ACTIVE","codingPlanQuotaInfo":{"per5HourUsedQuota":1,"per5HourTotalQuota":2}}]}""")
        }

        assertTrue(result.ok)
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("https://modelstudio.console.alibabacloud.com/"))
    }

    @Test
    fun automaticRoutingFallsBackToChinaAfterInternationalFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val result = QwenCodingPlanProvider.fetch(
            Provider(id = "qwen-auto-fallback", kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问自动", token = "test-key"),
        ) { url, _, _ ->
            calls += url
            if (url.startsWith("https://modelstudio.console.alibabacloud.com/")) {
                HttpResp(200, """{"code":"ConsoleNeedLogin","successResponse":false}""")
            } else {
                envelope("""{"codingPlanInstanceInfos":[{"status":"ACTIVE","codingPlanQuotaInfo":{"per5HourUsedQuota":1,"per5HourTotalQuota":2}}]}""")
            }
        }

        assertTrue(result.ok)
        assertEquals(2, calls.size)
        assertTrue(calls[0].startsWith("https://modelstudio.console.alibabacloud.com/"))
        assertTrue(calls[1].startsWith("https://bailian.console.aliyun.com/"))
    }

    @Test
    fun customConsoleUsesExactlyOneRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val result = QwenCodingPlanProvider.fetch(
            Provider(id = "qwen-custom-one", kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问自定义", baseUrl = "https://proxy.example/ap-southeast-1/", token = "test-key"),
        ) { url, _, _ ->
            calls += url
            envelope("""{"codingPlanInstanceInfos":[{"status":"ACTIVE","codingPlanQuotaInfo":{"per5HourUsedQuota":1,"per5HourTotalQuota":2}}]}""")
        }

        assertTrue(result.ok)
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("https://proxy.example/data/api.json?"))
        assertTrue(calls.single().contains("currentRegionId=ap-southeast-1"))
    }

    @Test
    fun invalidCustomConsoleHasZeroNetworkCalls() = runBlocking {
        var requestCount = 0
        val result = QwenCodingPlanProvider.fetch(
            Provider(id = "qwen-custom-zero", kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问自定义", baseUrl = "http://proxy.example", token = "test-key"),
        ) { _, _, _ ->
            requestCount++
            error("非法地址不应发起网络请求")
        }

        assertFalse(result.ok)
        assertEquals(0, requestCount)
    }

    private fun envelope(dataJson: String): HttpResp = HttpResp(
        200,
        """{"data":{"DataV2":{"data":{"data":$dataJson}}}}""",
    )
}
