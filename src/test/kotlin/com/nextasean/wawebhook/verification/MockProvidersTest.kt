package com.nextasean.wawebhook.verification

import com.nextasean.wawebhook.config.VerificationProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MockProvidersTest {

    private val latency = LatencySimulator(VerificationProperties(minLatency = Duration.ZERO, maxLatency = Duration.ZERO))
    private val dukcapil = MockDukcapil(latency)
    private val pefindo = MockPefindo(latency)

    private fun fields(lastDigit: Int) = ExtractedFields("Budi", "317105170590000$lastDigit", "1990-05-17", "Jakarta")

    @Test
    fun `last digit of the NIK drives both providers`() {
        for (d in 0..6) {
            assertEquals(ProviderOutcome.MATCH, dukcapil.verify(fields(d)).outcome, "digit $d")
            assertEquals(ProviderOutcome.MATCH, pefindo.check(fields(d)).outcome, "digit $d")
            assertNotNull(pefindo.check(fields(d)).score)
        }
        assertEquals(ProviderOutcome.MISMATCH, dukcapil.verify(fields(7)).outcome)
        assertEquals(ProviderOutcome.MISMATCH, pefindo.check(fields(7)).outcome)
        assertEquals(ProviderOutcome.NOT_FOUND, dukcapil.verify(fields(8)).outcome)
        assertEquals(ProviderOutcome.NOT_FOUND, pefindo.check(fields(8)).outcome)
        assertEquals(ProviderOutcome.TIMEOUT, dukcapil.verify(fields(9)).outcome)
        assertEquals(ProviderOutcome.TIMEOUT, pefindo.check(fields(9)).outcome)
        assertNull(pefindo.check(fields(9)).score)
    }

    @Test
    fun `Myanmar and Cambodia mocks follow the same last-digit rule`() {
        val cfg = RegionalMockProvidersConfig()
        val providers = listOf(cfg.myanmarRegistry(latency) to cfg.myanmarBureau(latency), cfg.cambodiaRegistry(latency) to cfg.cambodiaBureau(latency))
        for ((registry, bureau) in providers) {
            assertEquals(registry.country, bureau.country)
            assertEquals(ProviderOutcome.MATCH, registry.verify(fields(4)).outcome, registry.name)
            assertEquals(720, bureau.check(fields(4)).score, bureau.name)
            assertEquals(ProviderOutcome.MISMATCH, registry.verify(fields(7)).outcome, registry.name)
            assertEquals(ProviderOutcome.NOT_FOUND, bureau.check(fields(8)).outcome, bureau.name)
        }
        assertEquals(setOf("MM", "KH"), providers.map { it.first.country }.toSet())
    }

    @Test
    fun `latency stays inside the configured window`() {
        val sim = LatencySimulator(VerificationProperties(minLatency = Duration.ofMillis(20), maxLatency = Duration.ofMillis(60)))
        repeat(5) { assert(sim.simulate() in 20..60) }
    }
}
