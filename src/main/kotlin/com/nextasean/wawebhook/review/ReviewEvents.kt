package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.application.DealerApplication
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** What happened to an application in the back office; shown as the timeline on the review page. */
enum class ReviewEventKind(val label: String, val labelKo: String) {
    DOCUMENTS_COMPLETE("Customer documents complete", "고객 서류 접수 완료"),
    RISK_ASSESSED("Risk assessed", "리스크 평가"),
    RISK_REASSESSED("Risk re-assessed by officer", "리스크 재평가"),
    DOCUMENTS_REQUESTED("Documents requested from customer", "고객 서류 재요청"),
    RESUBMISSION_REQUESTED("Form resubmission requested from dealer", "딜러 폼 재제출 요청"),
    NOTE_ADDED("Officer note", "심사 메모"),
    APPROVED("Approved", "승인"),
    REJECTED("Rejected", "거절"),
}

@Table("REVIEW_EVENTS")
data class ReviewEvent(
    @Id val id: Long? = null,
    val applicationId: Long,
    val kind: ReviewEventKind,
    /** Officer name typed in the review UI, "system" for automatic steps, null when unknown. */
    val actor: String? = null,
    val detail: String? = null,
    val createdAt: Instant = Instant.now(),
)

interface ReviewEventRepository : CrudRepository<ReviewEvent, Long> {
    fun findByApplicationIdOrderByCreatedAtAsc(applicationId: Long): List<ReviewEvent>
}

/** Appends to the per-application audit trail. Failures are logged, never propagated: the trail must not break the flow. */
@Service
class ReviewAuditService(private val events: ReviewEventRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(app: DealerApplication, kind: ReviewEventKind, actor: String? = null, detail: String? = null): ReviewEvent? = try {
        events.save(ReviewEvent(applicationId = app.id!!, kind = kind, actor = actor?.trim()?.take(80)?.ifBlank { null }, detail = detail?.trim()?.take(1000)?.ifBlank { null }))
            .also { log.info("Review event | number={} | kind={} | actor={} | detail={}", app.applicationNumber, kind, it.actor, it.detail) }
    } catch (e: Exception) {
        log.error("Could not record review event {} for {}", kind, app.applicationNumber, e)
        null
    }

    fun timeline(app: DealerApplication): List<ReviewEvent> = app.id?.let(events::findByApplicationIdOrderByCreatedAtAsc).orEmpty()

    companion object {
        const val SYSTEM = "system"
    }
}
