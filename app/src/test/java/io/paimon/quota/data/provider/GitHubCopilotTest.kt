package io.paimon.quota.data.provider

import io.paimon.quota.data.HttpResp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotTest {

    @Test
    fun parsesPremiumInteractionsBalance() {
        val result = GitHubCopilotProvider.parse(
            HttpResp(
                200,
                """
                {
                  "copilot_plan": "individual",
                  "quota_reset_date_utc": "2026-12-01T00:00:00Z",
                  "quota_snapshots": {
                    "premium_interactions": {
                      "entitlement": 1500,
                      "remaining": 900,
                      "percent_remaining": 60,
                      "unlimited": false
                    },
                    "chat": {"unlimited": true, "percent_remaining": 100},
                    "completions": {"unlimited": true, "percent_remaining": 100}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(result.ok)
        assertEquals("Pro", result.message)
        assertEquals(listOf("Premium requests 余额"), result.tiers.map { it.label })
        assertEquals(40.0, result.tiers[0].usedPct, 0.0001)
        assertEquals("900\n/ 1,500", result.tiers[0].valueText)
        assertTrue(result.tiers.all { it.resetAt != null })
    }

    @Test
    fun parsesCopilotFreeMonthlyQuotas() {
        val result = GitHubCopilotProvider.parse(
            HttpResp(
                200,
                """
                {
                  "access_type_sku": "free_limited_copilot",
                  "limited_user_reset_date": "2026-12-01",
                  "monthly_quotas": {"chat": 50, "completions": 2000},
                  "limited_user_quotas": {"chat": 10, "completions": 500}
                }
                """.trimIndent(),
            ),
        )

        assertTrue(result.ok)
        assertEquals("Free", result.message)
        assertEquals(1, result.tiers.size)
        assertEquals("Premium requests 余额", result.tiers[0].label)
        assertEquals(80.0, result.tiers[0].usedPct, 0.0001)
        assertEquals("10\n/ 50", result.tiers[0].valueText)
    }

    @Test
    fun keepsUnlimitedState() {
        val result = GitHubCopilotProvider.parse(
            HttpResp(
                200,
                """{"quota_snapshots":{"premium_interactions":{"unlimited":true},"chat":{"unlimited":true}}}""",
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("不限量"), result.tiers.map { it.valueText })
    }

    @Test
    fun rejectsResponseWithoutRecognizedQuota() {
        val result = GitHubCopilotProvider.parse(HttpResp(200, "{}"))
        assertFalse(result.ok)
        assertEquals("没有 Premium requests 额度字段", result.error)
    }
}
