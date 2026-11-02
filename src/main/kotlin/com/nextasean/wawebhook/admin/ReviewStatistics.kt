package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.risk.RiskAssessment
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** The statistics tab: review results by month for one market (or all), counted over the same six calendar months as the dealer scorecard. */
object ReviewStatistics {
    /** Key for the figures over every market. */
    const val ALL = "ALL"

    data class Month(
        /** yyyy-MM. An application counts in the month it was created. */
        val month: String,
        val applications: Int,
        val approved: Int,
        /** Not decided yet: in progress, waiting for an officer, or waiting for new documents. */
        val pending: Int,
        val rejected: Int,
        /** approved / decided, like every approval rate on the dashboard; null without a decision. */
        val approvalRate: Double?,
        val avgDecisionHours: Double?,
        /** Assessed without a retake, a re-request, an officer hand-off or a fraud warning; null with nothing assessed. */
        val agentRate: Double?,
        val agentDone: Int,
        val assessed: Int,
        /** Applications with at least one fraud warning. */
        val fraudApplications: Int,
    )
    data class Reason(val code: String, val label: String, val labelKo: String, val count: Int)
    data class FraudOutcome(val rejected: Int, val pending: Int, val approved: Int)
    data class Result(
        val months: List<Month>,
        val rejectReasons: List<Reason>,
        val rejected: Int,
        val fraudTypes: List<Reason>,
        val fraudOutcome: FraudOutcome,
    )

    private const val NO_FLAG = "NO_FLAG"

    fun build(apps: List<DealerApplication>, assessedById: Map<Long?, RiskAssessment>, rework: Set<Long?>, zone: ZoneId, now: Instant, months: Long = DealerScorecard.WINDOW_MONTHS): Result {
        val thisMonth = YearMonth.from(now.atZone(zone))
        val range = (months - 1 downTo 0).map { thisMonth.minusMonths(it) }
        val byMonth = apps.groupBy { YearMonth.from(it.createdAt.atZone(zone)) }
        val rows = range.map { m ->
            val group = byMonth[m].orEmpty()
            val approved = group.count { it.status == ApplicationStatus.APPROVED }
            val rejected = group.count { it.status == ApplicationStatus.REJECTED }
            val assessed = group.filter { assessedById[it.id] != null }
            val agent = assessed.count { it.id !in rework && it.officerReviewRequired != true && assessedById[it.id]!!.fraudFlags.isEmpty() }
            val hours = group.mapNotNull { a ->
                val from = a.riskAssessedAt ?: a.verifiedAt
                if (a.status.isFinal && from != null && a.decidedAt != null) Duration.between(from, a.decidedAt).toMinutes() / 60.0 else null
            }
            Month(
                month = m.toString(), applications = group.size, approved = approved, pending = group.size - approved - rejected, rejected = rejected,
                approvalRate = (approved + rejected).takeIf { it > 0 }?.let { approved.toDouble() / it },
                avgDecisionHours = hours.takeIf { it.isNotEmpty() }?.average(),
                agentRate = assessed.size.takeIf { it > 0 }?.let { agent.toDouble() / it }, agentDone = agent, assessed = assessed.size,
                fraudApplications = assessed.count { assessedById[it.id]!!.fraudFlags.isNotEmpty() },
            )
        }

        val window = range.flatMap { byMonth[it].orEmpty() }
        // There is no reason code on a decision: a rejection is attributed to its most severe risk flag.
        val rejected = window.filter { it.status == ApplicationStatus.REJECTED }
        val reasons = rejected.groupBy { a -> assessedById[a.id]?.flags?.maxByOrNull { it.severity.points } }.entries.groupBy { it.key?.code ?: NO_FLAG }
            .map { (code, entries) ->
                val f = entries.first().key
                Reason(code, f?.title ?: "Condition not satisfied", f?.titleKo ?: "조건 미충족", entries.sumOf { it.value.size })
            }.sortedWith(compareByDescending<Reason> { it.count }.thenBy { it.code })

        val fraudApps = window.filter { assessedById[it.id]?.fraudFlags?.isNotEmpty() == true }
        val types = fraudApps.flatMap { assessedById[it.id]!!.fraudFlags }.groupBy { it.code }
            .map { (code, flags) -> Reason(code, flags.first().title, flags.first().titleKo, flags.size) }
            .sortedWith(compareByDescending<Reason> { it.count }.thenBy { it.code })
        val outcome = FraudOutcome(
            rejected = fraudApps.count { it.status == ApplicationStatus.REJECTED },
            pending = fraudApps.count { !it.status.isFinal },
            approved = fraudApps.count { it.status == ApplicationStatus.APPROVED },
        )
        return Result(rows, reasons, rejected.size, types, outcome)
    }
}
