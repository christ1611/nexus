package com.nextasean.wawebhook.verification

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.VerificationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class VerificationOutcome(val application: DealerApplication, val id: IdVerificationResult, val credit: CreditCheckResult)

/** Picks the providers for the configured country, runs them, and stores the results on the application. */
@Service
class VerificationService(
    idProviders: List<IdVerificationProvider>,
    creditProviders: List<CreditBureauProvider>,
    props: VerificationProperties,
    private val applications: ApplicationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val country = props.country.uppercase()
    private val idProvider = idProviders.firstOrNull { it.country.equals(country, ignoreCase = true) }
        ?: error("No IdVerificationProvider for country '$country' (available: ${idProviders.map { it.country }})")
    private val creditProvider = creditProviders.firstOrNull { it.country.equals(country, ignoreCase = true) }
        ?: error("No CreditBureauProvider for country '$country' (available: ${creditProviders.map { it.country }})")

    init {
        log.info("Verification providers | country={} | id={} | credit={}", country, idProvider.name, creditProvider.name)
    }

    /** Runs both checks with the fields extracted from the KTP and marks the application READY_FOR_REVIEW. */
    fun verifyAndSubmit(application: DealerApplication): VerificationOutcome {
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
            "Verification complete | number={} | id={} | credit={} | score={} | status={}",
            saved.applicationNumber, id.outcome, credit.outcome, credit.score, saved.status,
        )
        return VerificationOutcome(saved, id, credit)
    }
}
