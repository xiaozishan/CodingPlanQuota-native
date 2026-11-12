package io.paimon.quota.data.provider

import io.paimon.quota.data.Tier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityTest {

    @Test
    fun poolsBucketsByModelFamily() {
        val tiers = invokeParser(
            "parseBuckets",
            """
            {
              "buckets": [
                {"model_id":"claude-sonnet","remaining_fraction":0.1,"reset_time":"2026-11-08T12:00:00Z"},
                {"modelId":"CLAUDE-opus","remainingFraction":0.8,"resetTime":"2026-11-08T10:00:00Z"},
                {"model":"gemini-3-pro","remaining_percent":25,"reset_time":"2026-11-08T11:00:00Z"},
                {"model":"gpt-other","remaining_fraction":0.0,"reset_time":"2026-11-08T09:00:00Z"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Claude 池", "Gemini 池"), tiers.map { it.label })
        assertEquals(90.0, tiers[0].usedPct, 0.0001)
        assertEquals(75.0, tiers[1].usedPct, 0.0001)
        assertEquals(resetTextFor("2026-11-08T10:00:00Z"), tiers[0].resetAt)
        assertTrue(tiers.none { it.label.contains("gpt", ignoreCase = true) })
    }

    @Test
    fun poolsAvailableModelsFallback() {
        val tiers = invokeParser(
            "parseAvailableModels",
            """
            {
              "models": [
                {"modelId":"claude-a","quotaInfo":{"remainingFraction":0.5,"resetTime":"2026-11-08T12:00:00Z"}},
                {"model":"claude-b","quota_info":{"remaining_percent":20,"reset_time":"2026-11-08T09:00:00Z"}},
                {"model_id":"gemini-a","remainingFraction":0.75,"resetTime":"2026-11-08T11:00:00Z"},
                {"model":"unknown","remainingFraction":0.0}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Claude 池", "Gemini 池"), tiers.map { it.label })
        assertEquals(80.0, tiers[0].usedPct, 0.0001)
        assertEquals(25.0, tiers[1].usedPct, 0.0001)
        assertEquals(resetTextFor("2026-11-08T09:00:00Z"), tiers[0].resetAt)
    }

    @Test
    fun ignoresBucketsOutsideBothPools() {
        val tiers = invokeParser(
            "parseBuckets",
            """{"buckets":[{"model":"gpt-other","remaining_fraction":0.2}]}""",
        )
        assertTrue(tiers.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeParser(name: String, body: String): List<Tier> {
        val method = AntigravityProvider::class.java.getDeclaredMethod(name, JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(AntigravityProvider, JSONObject(body)) as List<Tier>
    }

    private fun resetTextFor(value: String): String? =
        io.paimon.quota.data.resetText(value)
}
