package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.dealer.DealerTier
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.verification.ProviderOutcome
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Scores a dealer over the last [WINDOW_MONTHS] calendar months from what the back office actually stores: there is no
 * repayment or loan amount data here, so the score uses risk grades, fraud warnings, document rework, approval rate
 * and decided volume. A fraud warning or too little history caps the grade at B, whatever the points say.
 */
object DealerScorecard {
    const val WINDOW_MONTHS = 6L
    /** Best first. */
    val GRADES = listOf("S", "A", "B", "C")
    /** Fewer assessed applications than this and the dealer is listed but not ranked. */
    const val MIN_ASSESSED = 5
    /** The excellent dealer standard (see STANDARD_ROWS on the dashboard). */
    const val MIN_DECIDED = 20
    /** Decided applications that earn the full volume points. */
    const val VOLUME_FULL = 40
    const val MIN_APPROVAL = 0.70
    const val MAX_HIGH_RISK = 0.15
    const val MIN_IDENTITY_MATCH = 0.90
    const val MAX_REWORK = 0.10

    data class Component(val code: String, val label: String, val labelKo: String, val points: Double, val max: Int)
    data class MonthScore(val month: String, val score: Double?)
    data class Result(
        /** False below [MIN_ASSESSED]: no score, grade or rank. */
        val rated: Boolean,
        val score: Double?,
        /** S, A, B or C (C is shown as "watch"). */
        val grade: String?,
        /** The grade the points alone would give, before a cap. */
        val pointsGrade: String?,
        /** FRAUD or VOLUME when the grade was capped at B. */
        val cap: String?,
        val rank: Int?,
        val components: List<Component>,
        val applications: Int,
        val decided: Int,
        val assessed: Int,
        val fraudWarnings: Int,
        val approvalRate: Double?,
        val highRiskRate: Double?,
        val fraudRate: Double?,
        val reworkRate: Double?,
        val identityMatchRate: Double?,
        val avgDecisionHours: Double?,
        val trend: List<MonthScore>,
        /** Meets every measurable criterion of the excellent dealer standard (tenure and conduct are not stored). */
        val meetsStandard: Boolean,
        /** The configured tier disagrees with the numbers: EXCELLENT without meeting the standard, or STANDARD while meeting it. */
        val tierReview: Boolean,
    )

    private fun rate(n: Int, of: Int): Double? = of.takeIf { it > 0 }?.let { n.toDouble() / it }
    private fun fraction(v: Double): Double = v.coerceIn(0.0, 1.0)
    private fun pointsGrade(score: Double): String = when { score >= 92 -> "S"; score >= 70 -> "A"; score >= 50 -> "B"; else -> "C" }

    private fun components(highRisk: Double, fraud: Double, rework: Double, approval: Double, decided: Int, decidedTarget: Double): List<Component> = listOf(
        Component("HIGH_RISK", "Low-risk customers", "고객 리스크", 35 * fraction(1 - highRisk / 0.4), 35),
        Component("FRAUD", "Fraud warnings", "위조·사기 경고", 25 * fraction(1 - fraud / 0.2), 25),
        Component("REWORK", "Document completeness", "서류 완결도", 15 * fraction(1 - rework / 0.25), 15),
        Component("APPROVAL", "Approval rate", "승인율", 15 * fraction((approval - 0.5) / 0.5), 15),
        Component("VOLUME", "Decided volume", "결정 건수", 10 * fraction(decided / decidedTarget), 10),
    )

    /** [rework] holds the ids of applications that needed a retake or a re-request. */
    fun score(tier: DealerTier, group: List<DealerApplication>, assessedById: Map<Long?, RiskAssessment>, rework: Set<Long?>, zone: ZoneId, now: Instant): Result {
        val thisMonth = YearMonth.from(now.atZone(zone))
        val months = (WINDOW_MONTHS - 1 downTo 0).map { thisMonth.minusMonths(it) }
        val start = months.first().atDay(1).atStartOfDay(zone).toInstant()
        val window = group.filter { !it.createdAt.isBefore(start) }

        fun total(apps: List<DealerApplication>, decidedTarget: Double): Pair<Double, List<Component>>? {
            val assessed = apps.mapNotNull { assessedById[it.id] }
            if (assessed.size < MIN_ASSESSED) return null
            val approved = apps.count { it.status == ApplicationStatus.APPROVED }
            val decided = approved + apps.count { it.status == ApplicationStatus.REJECTED }
            val c = components(
                rate(assessed.count { it.grade.highRisk }, assessed.size) ?: 0.0,
                rate(assessed.count { it.fraudFlags.isNotEmpty() }, assessed.size) ?: 0.0,
                rate(apps.count { it.id in rework }, apps.size) ?: 0.0,
                rate(approved, decided) ?: 0.0,
                decided, decidedTarget,
            )
            return c.sumOf { it.points } to c
        }

        val assessed = window.mapNotNull { assessedById[it.id] }
        val approved = window.count { it.status == ApplicationStatus.APPROVED }
        val decidedApps = window.filter { it.status == ApplicationStatus.APPROVED || it.status == ApplicationStatus.REJECTED }
        val highRisk = assessed.count { it.grade.highRisk }
        val fraud = assessed.count { it.fraudFlags.isNotEmpty() }
        val verified = window.filter { it.idVerificationStatus != null }
        val approvalRate = rate(approved, decidedApps.size)
        val highRiskRate = rate(highRisk, assessed.size)
        val fraudRate = rate(fraud, assessed.size)
        val reworkRate = rate(window.count { it.id in rework }, window.size)
        val identity = rate(verified.count { it.idVerificationStatus == ProviderOutcome.MATCH }, verified.size)
        val decisionHours = decidedApps.mapNotNull { a ->
            val from = a.riskAssessedAt ?: a.verifiedAt
            if (from != null && a.decidedAt != null) Duration.between(from, a.decidedAt).toMinutes() / 60.0 else null
        }

        val scored = total(window, VOLUME_FULL.toDouble())
        val points = scored?.first?.let { Math.round(it * 10) / 10.0 }
        val raw = points?.let { pointsGrade(it) }
        val cap = when {
            scored == null || raw == "B" || raw == "C" -> null
            fraud > 0 -> "FRAUD"
            decidedApps.size < MIN_DECIDED -> "VOLUME"
            else -> null
        }
        val meets = decidedApps.size >= MIN_DECIDED && fraud == 0 &&
            (approvalRate ?: 0.0) >= MIN_APPROVAL && (highRiskRate ?: 1.0) <= MAX_HIGH_RISK &&
            (identity ?: 0.0) >= MIN_IDENTITY_MATCH && (reworkRate ?: 1.0) <= MAX_REWORK

        // Each month shows the dealer as of that month's end, with the volume target growing at the same pace, so the last point equals the score.
        val trend = months.mapIndexed { i, m ->
            val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
            val upTo = window.filter { it.createdAt.isBefore(end) }
            MonthScore(m.toString(), total(upTo, VOLUME_FULL * (i + 1.0) / WINDOW_MONTHS)?.first?.let { Math.round(it * 10) / 10.0 })
        }

        return Result(
            rated = scored != null, score = points, grade = if (cap != null) "B" else raw, pointsGrade = raw, cap = cap, rank = null,
            components = scored?.second?.map { it.copy(points = Math.round(it.points * 10) / 10.0) }.orEmpty(),
            applications = window.size, decided = decidedApps.size, assessed = assessed.size, fraudWarnings = fraud,
            approvalRate = approvalRate, highRiskRate = highRiskRate, fraudRate = fraudRate, reworkRate = reworkRate, identityMatchRate = identity,
            avgDecisionHours = decisionHours.takeIf { it.isNotEmpty() }?.average(), trend = trend,
            meetsStandard = meets, tierReview = scored != null && (tier == DealerTier.EXCELLENT) != meets,
        )
    }
}
