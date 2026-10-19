package com.nextasean.wawebhook.verification

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.VerificationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class VerificationOutcome(val application: DealerApplication, val id: IdVerificationResult, val credit: CreditCheckResult)

/**
 * Picks the identity and credit providers for the application's country (falling back to the configured default
 * country), runs them, and stores the results on the application.
 */
@Service
class VerificationService(
    idProviders: List<IdVerificationProvider>,
    creditProviders: List<CreditBureauProvider>,
    props: VerificationProperties,
    private val applications: ApplicationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val defaultCountry = props.country.uppercase()
    private val idByCountry = idProviders.associateBy { it.country.uppercase() }
    private val creditByCountry = creditProviders.associateBy { it.country.uppercase() }

    init {
        require(defaultCountry in idByCountry) { "No IdVerificationProvider for country '$defaultCountry' (available: ${idByCountry.keys})" }
        require(defaultCountry in creditByCountry) { "No CreditBureauProvider for country '$defaultCountry' (available: ${creditByCountry.keys})" }
        val countries = idByCountry.keys intersect creditByCountry.keys
        log.info("Verification providers | default={} | countries={}", defaultCountry, countries.sorted())
        countries.sorted().forEach { c -> log.info("Verification providers | country={} | id={} | credit={}", c, idByCountry[c]?.name, creditByCountry[c]?.name) }
    }

    /** Countries with both an identity and a credit provider. */
    val supportedCountries: Set<String> get() = idByCountry.keys intersect creditByCountry.keys

    /** Runs both checks with the fields extracted from the ID document and marks the application READY_FOR_REVIEW. */
    fun verifyAndSubmit(application: DealerApplication): VerificationOutcome {
        val country = application.country.uppercase().takeIf { it in supportedCountries } ?: run {
            log.warn("No providers for country {} on {}; using default {}", application.country, application.applicationNumber, defaultCountry)
            defaultCountry
        }
        val idProvider = idByCountry.getValue(country)
        val creditProvider = creditByCountry.getValue(country)
        val fields = ExtractedFields(
            name = application.extractedName ?: "",
            nik = application.extractedNik ?: "",
            dob = application.extractedDob ?: "",
            address = application.extractedAddress ?: "",
        )
        val id = idProvider.verify(fields)
        val credit = creditProvider.check(fields)
        val saved = applications.save(
            application.copy(
                status = ApplicationStatus.READY_FOR_REVIEW,
                idVerificationStatus = id.outcome,
                idVerificationDetail = "${id.provider}: ${id.detail}".take(255),
                creditCheckStatus = credit.outcome,
                creditCheckDetail = "${credit.provider}: ${credit.detail}".take(255),
                creditScore = credit.score,
                verifiedAt = Instant.now(),
                linkTokenHash = null,
                linkExpiresAt = null,
                updatedAt = Instant.now(),
            ),
        )
        log.info(
            "Verification complete | number={} | country={} | id={} | credit={} | score={} | status={}",
            saved.applicationNumber, country, id.outcome, credit.outcome, credit.score, saved.status,
        )
        return VerificationOutcome(saved, id, credit)
    }
}
