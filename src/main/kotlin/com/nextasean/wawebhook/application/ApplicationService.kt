package com.nextasean.wawebhook.application

import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.dealer.Dealer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.Year
import java.util.Base64
import java.util.HexFormat

/** An application together with a freshly issued one-time link (the raw token is never stored). */
data class IssuedLink(val application: DealerApplication, val url: String, val expiresAt: Instant)

sealed interface LinkOpenResult {
    data class Opened(val application: DealerApplication) : LinkOpenResult
    data class AlreadyUsed(val application: DealerApplication) : LinkOpenResult
    data class Expired(val application: DealerApplication) : LinkOpenResult
    data object NotFound : LinkOpenResult
}

sealed interface SubmitResult {
    data class Submitted(val application: DealerApplication) : SubmitResult
    data class NotSubmittable(val application: DealerApplication) : SubmitResult
    data object NotFound : SubmitResult
}

enum class Decision { APPROVED, REJECTED }

@Service
class ApplicationService(
    private val applications: ApplicationRepository,
    private val jdbcClient: JdbcClient,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    fun create(dealer: Dealer): IssuedLink {
        val now = Instant.now()
        val number = nextApplicationNumber()
        val token = newToken()
        val saved = applications.save(
            DealerApplication(
                applicationNumber = number,
                dealerId = dealer.id!!,
                status = ApplicationStatus.DRAFT,
                country = dealer.country,
                linkTokenHash = hash(token),
                linkExpiresAt = now.plus(props.linkTtl),
                createdAt = now,
                updatedAt = now,
            ),
        )
        log.info("Application created | number={} | dealer={}", number, dealer.dealerCode)
        return IssuedLink(saved, linkUrl(token), saved.linkExpiresAt!!)
    }

    /** Issues a new one-time link, invalidating any previous one. Only for applications that still accept a form. */
    @Transactional
    fun reissueLink(application: DealerApplication): IssuedLink? {
        if (!application.status.acceptsSubmission) return null
        val now = Instant.now()
        val token = newToken()
        val saved = applications.save(
            application.copy(
                linkTokenHash = hash(token),
                linkExpiresAt = now.plus(props.linkTtl),
                linkOpenedAt = null,
                updatedAt = now,
            ),
        )
        log.info("Link reissued | number={}", application.applicationNumber)
        return IssuedLink(saved, linkUrl(token), saved.linkExpiresAt!!)
    }

    /** First open consumes the link; later opens are rejected. */
    @Transactional
    fun openLink(token: String): LinkOpenResult {
        val app = applications.findByLinkTokenHash(hash(token)) ?: return LinkOpenResult.NotFound
        val now = Instant.now()
        return when {
            app.linkOpenedAt != null -> LinkOpenResult.AlreadyUsed(app)
            app.linkExpiresAt == null || now.isAfter(app.linkExpiresAt) -> LinkOpenResult.Expired(app)
            !app.status.acceptsSubmission -> LinkOpenResult.AlreadyUsed(app)
            else -> LinkOpenResult.Opened(applications.save(app.copy(linkOpenedAt = now, updatedAt = now)))
        }
    }

    /** Accepts the form for a link that was opened; the token is retired afterwards. */
    @Transactional
    fun submit(token: String): SubmitResult {
        val app = applications.findByLinkTokenHash(hash(token)) ?: return SubmitResult.NotFound
        if (app.linkOpenedAt == null || !app.status.acceptsSubmission) return SubmitResult.NotSubmittable(app)
        val now = Instant.now()
        val saved = applications.save(
            app.copy(status = ApplicationStatus.SUBMITTED, linkTokenHash = null, linkExpiresAt = null, updatedAt = now),
        )
        log.info("Application submitted | number={}", saved.applicationNumber)
        return SubmitResult.Submitted(saved)
    }

    @Transactional
    fun requestResubmission(application: DealerApplication, reason: String): IssuedLink? {
        if (application.status.isFinal) return null
        val flagged = applications.save(
            application.copy(
                status = ApplicationStatus.RESUBMISSION_REQUESTED,
                resubmissionReason = reason.trim().take(500),
                updatedAt = Instant.now(),
            ),
        )
        log.info("Resubmission requested | number={} | reason={}", flagged.applicationNumber, reason)
        return reissueLink(flagged)
    }

    @Transactional
    fun decide(application: DealerApplication, decision: Decision, note: String?, reviewer: String? = null): DealerApplication? {
        if (application.status.isFinal) return null
        val now = Instant.now()
        val saved = applications.save(
            application.copy(
                status = if (decision == Decision.APPROVED) ApplicationStatus.APPROVED else ApplicationStatus.REJECTED,
                decisionNote = note?.trim()?.take(500)?.ifBlank { null },
                decidedBy = reviewer?.trim()?.take(80)?.ifBlank { null },
                decidedAt = now,
                linkTokenHash = null,
                linkExpiresAt = null,
                updatedAt = now,
            ),
        )
        log.info("Decision recorded | number={} | decision={} | by={}", saved.applicationNumber, decision, saved.decidedBy ?: "n/a")
        return saved
    }

    fun findByNumber(number: String): DealerApplication? = applications.findByApplicationNumber(number.trim().uppercase())

    fun recentForDealer(dealerId: Long, limit: Int = 10): List<DealerApplication> =
        applications.findByDealerIdOrderByCreatedAtDesc(dealerId).take(limit)

    private fun nextApplicationNumber(): String {
        val seq = jdbcClient.sql("SELECT NEXT VALUE FOR application_number_seq").query(Long::class.java).single()
        return "APP-${Year.now(props.zoneId)}-${"%06d".format(seq)}"
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun linkUrl(token: String): String = "${props.publicBaseUrl.trimEnd('/')}/apply/$token"

    companion object {
        fun hash(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }
}
