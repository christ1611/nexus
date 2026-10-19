package com.nextasean.wawebhook.verification

import com.nextasean.wawebhook.config.VerificationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** Fields the document check extracts from an identity document. */
data class ExtractedFields(val name: String, val nik: String, val dob: String, val address: String)

enum class ProviderOutcome(val label: String) {
    MATCH("Match"),
    MISMATCH("Data mismatch"),
    NOT_FOUND("Not found"),
    TIMEOUT("Timed out"),
}

data class IdVerificationResult(val provider: String, val outcome: ProviderOutcome, val detail: String, val latencyMs: Long)

data class CreditCheckResult(val provider: String, val outcome: ProviderOutcome, val detail: String, val score: Int?, val latencyMs: Long)

/** Checks identity data against the national population registry of [country]. */
interface IdVerificationProvider {
    val country: String
    val name: String
    fun verify(fields: ExtractedFields): IdVerificationResult
}

/** Looks up the applicant at the credit bureau of [country]. */
interface CreditBureauProvider {
    val country: String
    val name: String
    fun check(fields: ExtractedFields): CreditCheckResult
}

/** Demo rule shared by the mocks: the last digit of the NIK decides the outcome. */
object DemoOutcome {
    fun fromNik(nik: String): ProviderOutcome = when (nik.trim().lastOrNull()?.digitToIntOrNull()) {
        in 0..6 -> ProviderOutcome.MATCH
        7 -> ProviderOutcome.MISMATCH
        8 -> ProviderOutcome.NOT_FOUND
        9 -> ProviderOutcome.TIMEOUT
        else -> ProviderOutcome.NOT_FOUND
    }
}

/** Sleeps for a random duration inside the configured latency window (0 for tests). */
@Component
class LatencySimulator(private val props: VerificationProperties) {
    fun simulate(): Long {
        val min = props.minLatency.toMillis().coerceAtLeast(0)
        val max = props.maxLatency.toMillis().coerceAtLeast(min)
        val delay = if (max == min) min else ThreadLocalRandom.current().nextLong(min, max + 1)
        if (delay > 0) Thread.sleep(delay)
        return delay
    }
}

@Component
class MockDukcapil(private val latency: LatencySimulator) : IdVerificationProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    override val country = "ID"
    override val name = "Dukcapil (mock)"

    override fun verify(fields: ExtractedFields): IdVerificationResult {
        val took = latency.simulate()
        val outcome = DemoOutcome.fromNik(fields.nik)
        val detail = when (outcome) {
            ProviderOutcome.MATCH -> "NIK, name and date of birth match the population registry"
            ProviderOutcome.MISMATCH -> "NIK found but name/date of birth do not match the registry"
            ProviderOutcome.NOT_FOUND -> "NIK is not registered"
            ProviderOutcome.TIMEOUT -> "Dukcapil did not respond in time"
        }
        log.info("Dukcapil mock | nik=***{} | outcome={} | latencyMs={}", fields.nik.takeLast(4), outcome, took)
        return IdVerificationResult(name, outcome, detail, took)
    }
}

@Component
class MockPefindo(private val latency: LatencySimulator) : CreditBureauProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    override val country = "ID"
    override val name = "PEFINDO (mock)"

    override fun check(fields: ExtractedFields): CreditCheckResult {
        val took = latency.simulate()
        val outcome = DemoOutcome.fromNik(fields.nik)
        val lastDigit = fields.nik.trim().lastOrNull()?.digitToIntOrNull() ?: 0
        val score = if (outcome == ProviderOutcome.MATCH) 620 + lastDigit * 25 else null
        val detail = when (outcome) {
            ProviderOutcome.MATCH -> "Credit record found, score $score, no delinquencies in the last 24 months"
            ProviderOutcome.MISMATCH -> "Identity data does not match the bureau record"
            ProviderOutcome.NOT_FOUND -> "No credit record for this NIK"
            ProviderOutcome.TIMEOUT -> "PEFINDO did not respond in time"
        }
        log.info("PEFINDO mock | nik=***{} | outcome={} | score={} | latencyMs={}", fields.nik.takeLast(4), outcome, score, took)
        return CreditCheckResult(name, outcome, detail, score, took)
    }
}

/**
 * Generic mock population-registry provider for markets without a dedicated client yet (Myanmar, Cambodia).
 * Same demo rule as [MockDukcapil]: the last digit of the ID number decides the outcome.
 */
class MockRegistryProvider(
    override val country: String,
    override val name: String,
    private val registryLabel: String,
    private val latency: LatencySimulator,
) : IdVerificationProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun verify(fields: ExtractedFields): IdVerificationResult {
        val took = latency.simulate()
        val outcome = DemoOutcome.fromNik(fields.nik)
        val detail = when (outcome) {
            ProviderOutcome.MATCH -> "ID number, name and date of birth match the $registryLabel"
            ProviderOutcome.MISMATCH -> "ID number found but name/date of birth do not match the $registryLabel"
            ProviderOutcome.NOT_FOUND -> "ID number is not registered"
            ProviderOutcome.TIMEOUT -> "$name did not respond in time"
        }
        log.info("{} | id=***{} | outcome={} | latencyMs={}", name, fields.nik.takeLast(4), outcome, took)
        return IdVerificationResult(name, outcome, detail, took)
    }
}

/** Generic mock credit bureau for markets without a dedicated client yet. Scores follow the same rule as [MockPefindo]. */
class MockBureauProvider(
    override val country: String,
    override val name: String,
    private val latency: LatencySimulator,
) : CreditBureauProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(fields: ExtractedFields): CreditCheckResult {
        val took = latency.simulate()
        val outcome = DemoOutcome.fromNik(fields.nik)
        val lastDigit = fields.nik.trim().lastOrNull()?.digitToIntOrNull() ?: 0
        val score = if (outcome == ProviderOutcome.MATCH) 620 + lastDigit * 25 else null
        val detail = when (outcome) {
            ProviderOutcome.MATCH -> "Credit record found, score $score, no delinquencies in the last 24 months"
            ProviderOutcome.MISMATCH -> "Identity data does not match the bureau record"
            ProviderOutcome.NOT_FOUND -> "No credit record for this ID number"
            ProviderOutcome.TIMEOUT -> "$name did not respond in time"
        }
        log.info("{} | id=***{} | outcome={} | score={} | latencyMs={}", name, fields.nik.takeLast(4), outcome, score, took)
        return CreditCheckResult(name, outcome, detail, score, took)
    }
}

/** Registers the mock providers for the markets that have no dedicated client yet. */
@org.springframework.context.annotation.Configuration
class RegionalMockProvidersConfig {
    @org.springframework.context.annotation.Bean
    fun myanmarRegistry(latency: LatencySimulator): IdVerificationProvider =
        MockRegistryProvider("MM", "Myanmar NRC registry (mock)", "national registration database", latency)

    @org.springframework.context.annotation.Bean
    fun myanmarBureau(latency: LatencySimulator): CreditBureauProvider = MockBureauProvider("MM", "Myanmar Credit Bureau (mock)", latency)

    @org.springframework.context.annotation.Bean
    fun cambodiaRegistry(latency: LatencySimulator): IdVerificationProvider =
        MockRegistryProvider("KH", "Cambodia GDI registry (mock)", "civil registry", latency)

    @org.springframework.context.annotation.Bean
    fun cambodiaBureau(latency: LatencySimulator): CreditBureauProvider = MockBureauProvider("KH", "Credit Bureau Cambodia (mock)", latency)
}
