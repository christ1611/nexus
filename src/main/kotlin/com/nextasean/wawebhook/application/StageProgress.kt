package com.nextasean.wawebhook.application

import com.nextasean.wawebhook.customer.CustomerSession
import com.nextasean.wawebhook.customer.CustomerSessionRepository
import com.nextasean.wawebhook.customer.CustomerSessionState
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * The onboarding pipeline as one ordered list of stages, derived from the application row, the customer session and
 * the documents. The review page shows it as a stepper above the agent timeline and the dashboard counts active
 * applications per stage, so both screens read the same definition of "where is this application right now".
 */
enum class OnboardingStage(val label: String, val labelKo: String) {
    CREATED("Application created", "신청 생성"),
    CUSTOMER_LINKED("Customer linked", "고객 연결"),
    CONSENT("Consent", "개인정보 동의"),
    KTP("ID card", "신분증"),
    SELFIE("Selfie with ID", "셀피"),
    VERIFICATION("Identity & credit lookups", "신원·신용 조회"),
    RISK("Risk assessment", "리스크 평가"),
    REVIEW("Officer review", "심사"),
    DECISION("Decision", "결정"),
}

/** DONE and SKIPPED count as passed; CURRENT is the stage being worked; WARN needs attention; FAIL ended the stage badly. */
enum class StageState { DONE, CURRENT, PENDING, WARN, FAIL, SKIPPED }

data class StageView(val code: String, val label: String, val labelKo: String, val state: StageState, val at: Instant?, val note: String?)

data class StageProgress(
    val stages: List<StageView>,
    /** Code of the stage the application is at now. */
    val current: String,
    val currentKo: String,
    val done: Int,
    val total: Int,
    val percent: Int,
    /** Hours since the last change, only while the application is still open. */
    val idleHours: Double?,
)

@Service
class StageProgressService(
    private val sessions: CustomerSessionRepository,
    private val documents: DocumentRepository,
) {
    fun forApplication(app: DealerApplication, now: Instant = Instant.now()): StageProgress {
        val id = app.id ?: return of(app, null, emptyList(), now)
        return of(app, sessions.findByApplicationIdOrderByCreatedAtDesc(id).firstOrNull(), documents.findByApplicationIdOrderByCreatedAtAsc(id), now)
    }

    /** One pass over the session and document tables for a list of applications. */
    fun forAll(apps: Collection<DealerApplication>, now: Instant = Instant.now()): Map<Long, StageProgress> {
        if (apps.isEmpty()) return emptyMap()
        val latestSession = sessions.findAll().groupBy { it.applicationId }.mapValues { (_, s) -> s.maxBy { it.createdAt } }
        val docsByApp = documents.findAll().groupBy { it.applicationId }.mapValues { (_, d) -> d.sortedBy { it.createdAt } }
        return apps.mapNotNull { app -> app.id?.let { it to of(app, latestSession[it], docsByApp[it].orEmpty(), now) } }.toMap()
    }

    fun of(app: DealerApplication, session: CustomerSession?, docs: List<DocumentRecord>, now: Instant = Instant.now()): StageProgress {
        val status = app.status
        val final = status.isFinal
        val requested = status == ApplicationStatus.DOCUMENTS_REQUESTED

        fun document(type: DocumentType, stage: OnboardingStage): StageView {
            val accepted = docs.lastOrNull { it.type == type && !it.superseded && it.checkPassed == true }
            val latest = docs.lastOrNull { it.type == type }
            return when {
                accepted != null && !requested -> stage.view(StageState.DONE, accepted.createdAt, if (accepted.needsOfficerReview == true) "담당자 확인 필요" else null)
                accepted != null && requested -> stage.view(StageState.WARN, accepted.createdAt, "심사자가 재요청 · 고객 재제출 대기")
                latest != null && latest.checkPassed == false -> stage.view(StageState.WARN, latest.createdAt, "재촬영 요청: " + (latest.checkReason ?: "품질 미달"))
                latest != null && latest.superseded -> stage.view(StageState.WARN, latest.createdAt, "재제출 대기")
                latest != null -> stage.view(StageState.CURRENT, latest.createdAt, "검사 중")
                else -> stage.view(StageState.PENDING, null, null)
            }
        }

        val created = OnboardingStage.CREATED.view(
            StageState.DONE, app.createdAt,
            when (status) {
                ApplicationStatus.RESUBMISSION_REQUESTED -> "딜러 폼 재제출 요청 중"
                ApplicationStatus.DRAFT -> if (app.linkTokenHash != null) "딜러 폼 미제출" else null
                else -> null
            },
        )
        val linked = if (app.customerPhone != null) OnboardingStage.CUSTOMER_LINKED.view(StageState.DONE, session?.createdAt, null)
        else OnboardingStage.CUSTOMER_LINKED.view(StageState.PENDING, null, "고객이 START 링크를 아직 열지 않음")
        val consent = when {
            session?.consentAt != null -> OnboardingStage.CONSENT.view(StageState.DONE, session.consentAt, null)
            session?.state == CustomerSessionState.DECLINED -> OnboardingStage.CONSENT.view(StageState.FAIL, session.declinedAt, "고객이 동의를 거부")
            session?.state == CustomerSessionState.CANCELLED -> OnboardingStage.CONSENT.view(StageState.WARN, session.updatedAt, "세션 취소됨")
            else -> OnboardingStage.CONSENT.view(StageState.PENDING, null, null)
        }
        val ktp = document(DocumentType.KTP, OnboardingStage.KTP)
        val selfie = document(DocumentType.SELFIE_KTP, OnboardingStage.SELFIE)
        val verification = when {
            app.verifiedAt != null -> OnboardingStage.VERIFICATION.view(StageState.DONE, app.verifiedAt, listOfNotNull(app.idVerificationStatus?.let { "신원 " + it.label }, app.creditCheckStatus?.let { "신용 " + it.label }).joinToString(" · ").ifBlank { null })
            app.riskAssessedAt != null -> OnboardingStage.VERIFICATION.view(StageState.SKIPPED, app.riskAssessedAt, "조회 생략 · 담당자 확인")
            else -> OnboardingStage.VERIFICATION.view(StageState.PENDING, null, null)
        }
        val risk = if (app.riskAssessedAt != null) OnboardingStage.RISK.view(StageState.DONE, app.riskAssessedAt, app.riskGrade?.let { "등급 $it" + (app.riskScore?.let { s -> " · ${s}점" } ?: "") })
        else OnboardingStage.RISK.view(StageState.PENDING, null, null)
        val review = when {
            final -> OnboardingStage.REVIEW.view(StageState.DONE, app.decidedAt, app.decidedBy?.let { "심사자 $it" })
            status == ApplicationStatus.READY_FOR_REVIEW -> OnboardingStage.REVIEW.view(StageState.CURRENT, app.riskAssessedAt ?: app.verifiedAt ?: app.updatedAt, if (app.officerReviewRequired == true) "담당자 검토 필요" else "심사 대기")
            requested -> OnboardingStage.REVIEW.view(StageState.PENDING, null, "고객 서류 재요청 중")
            else -> OnboardingStage.REVIEW.view(StageState.PENDING, null, null)
        }
        val decision = when (status) {
            ApplicationStatus.APPROVED -> OnboardingStage.DECISION.view(StageState.DONE, app.decidedAt, "승인")
            ApplicationStatus.REJECTED -> OnboardingStage.DECISION.view(StageState.FAIL, app.decidedAt, "거절")
            else -> OnboardingStage.DECISION.view(StageState.PENDING, null, null)
        }

        var stages = listOf(created, linked, consent, ktp, selfie, verification, risk, review, decision)
        // The current stage is the first one not passed; a plain PENDING stage there becomes CURRENT.
        val passed = setOf(StageState.DONE, StageState.SKIPPED)
        val currentIndex = stages.indexOfFirst { it.state !in passed && !(it.code == OnboardingStage.DECISION.name && it.state == StageState.FAIL) }
            .let { if (it < 0) stages.lastIndex else it }
        stages = stages.mapIndexed { i, s -> if (i == currentIndex && s.state == StageState.PENDING) s.copy(state = StageState.CURRENT) else s }
        val done = stages.count { it.state in passed || (it.code == OnboardingStage.DECISION.name && it.state == StageState.FAIL) }
        val current = stages[currentIndex]
        return StageProgress(
            stages = stages,
            current = current.code,
            currentKo = current.labelKo,
            done = done,
            total = stages.size,
            percent = (done * 100) / stages.size,
            idleHours = if (final) null else Duration.between(app.updatedAt, now).toMinutes() / 60.0,
        )
    }

    private fun OnboardingStage.view(state: StageState, at: Instant?, note: String?) = StageView(name, label, labelKo, state, at, note)
}
