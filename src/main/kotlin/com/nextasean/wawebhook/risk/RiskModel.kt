package com.nextasean.wawebhook.risk

import java.time.Instant

/** Where a risk signal came from, so the officer knows which evidence to open. */
enum class RiskSource(val label: String, val labelKo: String) {
    DOCUMENT("Document / forgery analysis", "서류·위조 분석"),
    IDENTITY("Identity registry", "신원 조회"),
    CREDIT("Credit bureau", "신용 조회"),
    BEHAVIOUR("Customer behaviour", "고객 행동"),
}

enum class RiskSeverity(val points: Int, val label: String, val labelKo: String) {
    LOW(10, "Low", "낮음"),
    MEDIUM(20, "Medium", "중간"),
    HIGH(35, "High", "높음"),
}

/**
 * One warning on an application. [code] is stable (used for statistics), [title] / [titleKo] are shown on the dashboard,
 * [detail] is the evidence (never includes a full ID number).
 */
data class RiskFlag(
    val code: String,
    val severity: RiskSeverity,
    val source: RiskSource,
    val title: String,
    val titleKo: String,
    val detail: String,
) {
    /** Forgery / tampering signals are highlighted separately from plain data-quality warnings. */
    val fraudSignal: Boolean get() = code in FRAUD_CODES

    companion object {
        val FRAUD_CODES = setOf(
            RiskCodes.SCREEN_OR_COPY, RiskCodes.TAMPERING_SUSPECTED, RiskCodes.WRONG_DOCUMENT_TYPE, RiskCodes.INVALID_ID_NUMBER,
            RiskCodes.ID_CHANGED_BETWEEN_UPLOADS, RiskCodes.SELFIE_ID_MISMATCH, RiskCodes.MULTIPLE_FACES, RiskCodes.IDENTITY_MISMATCH,
        )
    }
}

/** Stable flag codes; keep in sync with the dashboard legend. */
object RiskCodes {
    const val SCREEN_OR_COPY = "SCREEN_OR_COPY"
    const val TAMPERING_SUSPECTED = "TAMPERING_SUSPECTED"
    const val WRONG_DOCUMENT_TYPE = "WRONG_DOCUMENT_TYPE"
    const val INVALID_ID_NUMBER = "INVALID_ID_NUMBER"
    const val LOW_CONFIDENCE_FIELDS = "LOW_CONFIDENCE_FIELDS"
    const val ID_CHANGED_BETWEEN_UPLOADS = "ID_CHANGED_BETWEEN_UPLOADS"
    const val SELFIE_ID_MISMATCH = "SELFIE_ID_MISMATCH"
    const val MULTIPLE_FACES = "MULTIPLE_FACES"
    const val IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    const val IDENTITY_NOT_FOUND = "IDENTITY_NOT_FOUND"
    const val IDENTITY_UNVERIFIED = "IDENTITY_UNVERIFIED"
    const val CREDIT_IDENTITY_MISMATCH = "CREDIT_IDENTITY_MISMATCH"
    const val NO_CREDIT_HISTORY = "NO_CREDIT_HISTORY"
    const val LOW_CREDIT_SCORE = "LOW_CREDIT_SCORE"
    const val CREDIT_UNVERIFIED = "CREDIT_UNVERIFIED"
    const val REPEATED_RESUBMISSION = "REPEATED_RESUBMISSION"
    const val MANY_FAILED_UPLOADS = "MANY_FAILED_UPLOADS"
    const val AGE_OUT_OF_RANGE = "AGE_OUT_OF_RANGE"
}

/** Customer risk grade shown on the dashboard. A is the best. */
enum class RiskGrade(val label: String, val labelKo: String, val minScore: Int) {
    A("Low risk", "저위험", 0),
    B("Medium risk", "중위험", 15),
    C("High risk", "고위험", 40),
    D("Critical risk", "위험 고객", 70);

    val highRisk: Boolean get() = this == C || this == D

    companion object {
        fun forScore(score: Int, flags: List<RiskFlag>): RiskGrade {
            val byScore = entries.last { score >= it.minScore }
            // A fraud signal of HIGH severity never grades better than C, however small the total.
            val floor = if (flags.any { it.fraudSignal && it.severity == RiskSeverity.HIGH }) C else A
            return if (byScore.ordinal >= floor.ordinal) byScore else floor
        }

        fun parse(raw: String?): RiskGrade? = entries.firstOrNull { it.name == raw }
    }
}

/** Positive signals shown as badges next to the grade. */
enum class CustomerBadge(val label: String, val labelKo: String, val description: String) {
    FINANCIAL_STABILITY(
        "Strong financial consistency and collateral stability",
        "재무 정합성 및 담보 안정성 우수 고객",
        "Identity matches the registry, credit record found with a score of 700 or more, no fraud signal. Collateral data is not collected yet; " +
            "once it is, collateral stability becomes part of this badge.",
    ),
    EXCELLENT_DEALER_REFERRAL(
        "Referred by an excellent dealer",
        "우수 딜러 유입 고객",
        "The application was created by a dealer with tier EXCELLENT.",
    ),
    VERIFIED_IDENTITY(
        "Identity verified",
        "신원 확인 완료 고객",
        "Document analysis accepted the ID document and the registry confirmed the identity.",
    );

    companion object {
        fun parseList(raw: String?): List<CustomerBadge> =
            raw.orEmpty().split(',').mapNotNull { v -> entries.firstOrNull { it.name == v.trim() } }
    }
}

/** How the assessment was produced; shown on the dashboard so nobody mistakes the stub for the real thing. */
enum class RiskMethod(val label: String, val labelKo: String) {
    RULES("Rules on provider results (stub document check)", "규칙 기반 (스텁 문서 검사)"),
    CLAUDE_VISION_RULES("Claude vision analysis + rules", "Claude 비전 분석 + 규칙");
}

data class RiskAssessment(
    val score: Int,
    val grade: RiskGrade,
    val flags: List<RiskFlag>,
    val badges: List<CustomerBadge>,
    val method: RiskMethod,
    val assessedAt: Instant,
) {
    val fraudFlags: List<RiskFlag> get() = flags.filter { it.fraudSignal }

    /** One-line verdict for WhatsApp and the dashboard header. */
    val headline: String
        get() = "Risk grade ${grade.name} (${grade.label}, score $score)" +
            (if (fraudFlags.isNotEmpty()) " - ${fraudFlags.size} fraud/forgery warning(s)" else "") +
            (if (flags.isEmpty()) ", no warnings" else "")
}
