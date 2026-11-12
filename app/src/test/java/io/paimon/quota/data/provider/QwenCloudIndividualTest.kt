package io.paimon.quota.data.provider

import io.paimon.quota.data.HttpResp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import io.paimon.quota.data.QuotaApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenCloudIndividualTest {

    @Test
    fun missingCookieProducesSpecificErrorThroughProviderRouter() = runBlocking {
        val result = QuotaApi.fetch(
            Provider(
                id = "qwen-test",
                kind = ProviderKind.QWEN_CLOUD_INDIVIDUAL,
                name = "千问测试",
            ),
        )
        assertFalse(result.ok)
        assertEquals("还没填 Qwen Cloud 网页 Cookie（推理 API Key 不能查额度）", result.error)
    }

    @Test
    fun parsesLiveMonthlyOnlyShape() {
        val result = QwenCloudIndividualProvider.parse(
            usageResponse = envelope(
                """{"per1MonthPercentage":0.2336,"per1MonthResetTime":1795190400000}""",
            ),
            subscriptionResponse = envelope(
                """{"specCode":"pro","status":"VALID","remainingDays":44}""",
            ),
            quotaResponse = envelope(
                """{"pro":{"five_hour":12000,"monthly":180000}}""",
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("本月"), result.tiers.map { it.label })
        assertEquals(23.36, result.tiers[0].usedPct, 0.0001)
        assertEquals("42,048\n/ 180,000", result.tiers[0].valueText)
        assertTrue(result.tiers[0].resetAt != null)
        assertEquals("Token Plan pro · 剩余 44 天", result.message)
    }

    @Test
    fun parsesFiveHourAndWeeklyWindows() {
        val result = QwenCloudIndividualProvider.parse(
            usageResponse = envelope(
                """{"per5HourPercentage":0.2,"per1WeekPercentage":0.5}""",
            ),
            subscriptionResponse = envelope("""{"specCode":"Lite"}"""),
            quotaResponse = envelope(
                """{"lite":{"five_hour":1000,"weekly":4000}}""",
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("5 小时", "本周"), result.tiers.map { it.label })
        assertEquals(20.0, result.tiers[0].usedPct, 0.0001)
        assertEquals(50.0, result.tiers[1].usedPct, 0.0001)
        assertEquals("200\n/ 1,000", result.tiers[0].valueText)
        assertEquals("2,000\n/ 4,000", result.tiers[1].valueText)
    }

    @Test
    fun acceptsPercentageScaleAndStringEmbeddedJson() {
        val usage = HttpResp(
            200,
            """{"data":"{\"DataV2\":{\"data\":{\"data\":{\"per5HourPercentage\":23.36,\"per1WeekPercentage\":100}}}}"}""",
        )
        val result = QwenCloudIndividualProvider.parse(usage)

        assertTrue(result.ok)
        assertEquals(23.36, result.tiers[0].usedPct, 0.0001)
        assertEquals(100.0, result.tiers[1].usedPct, 0.0001)
        assertNull(result.tiers[0].valueText)
    }

    @Test
    fun keepsZeroPercentAsRealUsage() {
        val result = QwenCloudIndividualProvider.parse(
            envelope("""{"per1MonthPercentage":0}"""),
        )
        assertTrue(result.ok)
        assertEquals(0.0, result.tiers.single().usedPct, 0.0001)
    }

    @Test
    fun rejectsResponseWithoutUsageWindows() {
        val result = QwenCloudIndividualProvider.parse(envelope("{}"))
        assertFalse(result.ok)
        assertEquals("千问返回中没有 5 小时 / 本周 / 本月额度字段", result.error)
    }

    @Test
    fun reportsExpiredConsoleSession() {
        val result = QwenCloudIndividualProvider.parse(
            HttpResp(200, """{"code":"ConsoleNeedLogin"}"""),
        )
        assertFalse(result.ok)
        assertEquals("千问登录已失效，请重新复制 Qwen Cloud Cookie", result.error)
    }

    @Test
    fun normalizesCopiedCookieHeader() {
        assertEquals(
            "login_qwencloud_ticket=ticket; cna=anonymous",
            QwenCloudIndividualProvider.normalizeCookie(
                "Cookie: login_qwencloud_ticket=ticket;\r\n cna=anonymous",
            ),
        )
    }

    @Test
    fun rejectsInferenceKeyInsteadOfSendingIt() {
        val error = runCatching {
            QwenCloudIndividualProvider.normalizeCookie("sk-not-a-cookie")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("这里需要 Qwen Cloud 网页 Cookie，不是推理 API Key", error?.message)
    }

    private fun envelope(dataJson: String): HttpResp = HttpResp(
        200,
        """{"data":{"DataV2":{"data":{"data":$dataJson}}}}""",
    )
}
