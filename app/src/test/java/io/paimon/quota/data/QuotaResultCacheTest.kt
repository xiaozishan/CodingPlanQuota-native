package io.paimon.quota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaResultCacheTest {
    @Test
    fun roundTripPreservesDisplayDataAndOmitsRawResponsesAndFailures() {
        val successful = QuotaResult(
            ok = true,
            tiers = listOf(
                Tier("5 小时", 42.5, resetAt = "11-08 13:00"),
                Tier("本周", 0.0, valueText = "剩余 12 次"),
            ),
            balanceText = "余额 ¥12.50",
            balanceAmount = 12.5,
            message = "Pro",
            at = 1_763_000_000_000L,
            raw = "raw response must not be persisted",
        )

        val encoded = QuotaResultCache.encode(
            mapOf(
                "provider-1" to successful,
                "failed" to QuotaResult(false, error = "network", raw = "do not save"),
            ),
        )
        val decoded = QuotaResultCache.decode(encoded)

        assertEquals(setOf("provider-1"), decoded.keys)
        assertEquals(successful.copy(raw = null), decoded["provider-1"])
        assertNull(decoded["provider-1"]?.raw)
        assertFalse(encoded.contains("raw response must not be persisted"))
        assertFalse(encoded.contains("do not save"))
    }

    @Test
    fun missingOrMalformedCacheIsSafeToIgnore() {
        assertTrue(QuotaResultCache.decode(null).isEmpty())
        assertTrue(QuotaResultCache.decode("").isEmpty())
        assertTrue(QuotaResultCache.decode("{broken").isEmpty())
        assertTrue(QuotaResultCache.decode("[]").isEmpty())
    }
}
