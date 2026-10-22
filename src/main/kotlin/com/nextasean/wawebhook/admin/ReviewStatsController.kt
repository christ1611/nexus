package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.dealer.Countries
import com.nextasean.wawebhook.review.ReviewAuditService
import com.nextasean.wawebhook.review.ReviewEvent
import com.nextasean.wawebhook.review.ReviewEventKind
import com.nextasean.wawebhook.review.ReviewEventRepository
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.risk.RiskGrade
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Statistics for the officers themselves, shown on the "심사 통계" tab of `/review`: what is waiting and how urgent it is,
 * how fast decisions are made, who did what (from `REVIEW_EVENTS`), and how past decisions went per AI risk grade.
 * Everything is computed from the rows on each call, like [DashboardController].
 */
@Controller
@RequestMapping("/admin/review")
class ReviewStatsController(
    private val applications: ApplicationRepository,
    private val events: ReviewEventRepository,
    private val risk: RiskAssessmentService,
    props: AppProperties,
) {
    private val zone: ZoneId = props.zoneId

    /** What is on the officers' desk right now. */
    data class Workload(
        val readyForReview: Int,
        /** Waiting for the customer to resend documents an officer or the agent asked for. */
        val documentsRequested: Int,
        /** Waiting for the dealer to resubmit the form. */
        val resubmissionRequested: Int,
        /** Waiting applications the review agent flagged for a human. */
        val officerReviewRequired: Int,
        val highRiskWaiting: Int,
        val fraudWaiting: Int,
        val oldestWaitingHours: Double?,
        val avgWaitingHours: Double?,
        /** Waiting applications by age: under 24 h, 1-3 days, over 3 days. */
        val waitingUnder24h: Int,
        val waiting1to3d: Int,
        val waitingOver3d: Int,
        val queueByCountry: List<CountryQueue>,
        val queueByGrade: List<GradeQueue>,
    )

    data class CountryQueue(val code: String, val nameKo: String, val name: String, val waiting: Int, val highRisk: Int, val oldestWaitingHours: Double?)
    data class GradeQueue(val grade: String, val labelKo: String, val label: String, val waiting: Int)

    /** How fast the desk moves. */
    data class Throughput(
        val decidedToday: Int,
        val decided7d: Int,
        val decided30d: Int,
        val approved7d: Int,
        val rejected7d: Int,
        val approved30d: Int,
        val rejected30d: Int,
        /** Share of approvals among decisions in the last 30 days (null when none). */
        val approvalRate30d: Double?,
        val avgDecisionHours7d: Double?,
        val avgDecisionHours30d: Double?,
        /** Customer document re-requests by officers in the last 7 / 30 days. */
        val documentsRequested7d: Int,
        val documentsRequested30d: Int,
        /** Decided applications that needed two or more review rounds. */
        val multiRoundDecided: Int,
        val avgRoundsDecided: Double?,
    )

    /** One calendar day (dashboard time zone), oldest first. */
    data class DailyRow(val date: String, val label: String, val approved: Int, val rejected: Int, val documentsRequested: Int, val completed: Int)

    /** One officer, from the audit trail (`REVIEW_EVENTS.actor`). */
    data class ReviewerRow(
        val name: String,
        val approved: Int,
        val rejected: Int,
        val documentsRequested: Int,
        val resubmissionsRequested: Int,
        val reassessments: Int,
        val notes: Int,
        val decisions7d: Int,
        val lastActivityAt: Instant?,
    )

    /** How decisions went for each AI risk grade; tells officers how much the grade has matched outcomes so far. */
    data class GradeOutcome(val grade: String, val labelKo: String, val label: String, val decided: Int, val approved: Int, val rejected: Int, val approvalRate: Double?)

    /** Risk flags on the applications waiting right now, most frequent first. */
    data class QueueFlag(val code: String, val title: String, val titleKo: String, val severity: String, val fraud: Boolean, val count: Int)

    /** Agent "officer review required" verdicts compared with what officers finally decided. */
    data class AgentAlignment(
        val flaggedDecided: Int,
        val flaggedApproved: Int,
        val flaggedRejected: Int,
        val notFlaggedDecided: Int,
        val notFlaggedApproved: Int,
        val notFlaggedRejected: Int,
    )

    data class Stats(
        val generatedAt: Instant,
        val workload: Workload,
        val throughput: Throughput,
        val daily: List<DailyRow>,
        val reviewers: List<ReviewerRow>,
        val gradeOutcomes: List<GradeOutcome>,
        val queueFlags: List<QueueFlag>,
        val agentAlignment: AgentAlignment,
        val demoApplications: Int,
    )

    @GetMapping("/stats")
    @ResponseBody
    fun stats(): Stats {
        val now = Instant.now()
        val apps = applications.findAllByOrderByCreatedAtDesc()
        val allEvents = events.findAll().toList()
        val waiting = apps.filter { it.status == ApplicationStatus.READY_FOR_REVIEW }
        val waitingRisk = waiting.associate { it.id to risk.stored(it) }
        val decided = apps.filter { it.status.isFinal }
        val since7d = now.minus(Duration.ofDays(7))
        val since30d = now.minus(Duration.ofDays(30))
        val today = LocalDate.ofInstant(now, zone)

        fun decidedAt(app: DealerApplication): Instant = app.decidedAt ?: app.updatedAt
        fun decisionHours(app: DealerApplication): Double? = (app.riskAssessedAt ?: app.verifiedAt)?.let { hours(it, decidedAt(app)) }
        val decided7d = decided.filter { decidedAt(it) >= since7d }
        val decided30d = decided.filter { decidedAt(it) >= since30d }
        val waitingHours = waiting.map { hours(waitingSince(it), now) }

        val workload = Workload(
            readyForReview = waiting.size,
            documentsRequested = apps.count { it.status == ApplicationStatus.DOCUMENTS_REQUESTED },
            resubmissionRequested = apps.count { it.status == ApplicationStatus.RESUBMISSION_REQUESTED },
            officerReviewRequired = waiting.count { it.officerReviewRequired == true },
            highRiskWaiting = waiting.count { waitingRisk[it.id]?.grade?.highRisk == true },
            fraudWaiting = waiting.count { waitingRisk[it.id]?.fraudFlags?.isNotEmpty() == true },
            oldestWaitingHours = waitingHours.maxOrNull(),
            avgWaitingHours = waitingHours.takeIf { it.isNotEmpty() }?.average(),
            waitingUnder24h = waitingHours.count { it < 24 },
            waiting1to3d = waitingHours.count { it >= 24 && it < 72 },
            waitingOver3d = waitingHours.count { it >= 72 },
            queueByCountry = Countries.ALL.map { c ->
                val cw = waiting.filter { it.country.equals(c.code, ignoreCase = true) }
                CountryQueue(c.code, c.nameKo, c.name, cw.size, cw.count { waitingRisk[it.id]?.grade?.highRisk == true }, cw.maxOfOrNull { hours(waitingSince(it), now) })
            },
            queueByGrade = RiskGrade.entries.map { g -> GradeQueue(g.name, g.labelKo, g.label, waiting.count { waitingRisk[it.id]?.grade == g }) } +
                GradeQueue("NONE", "미평가", "Not assessed", waiting.count { waitingRisk[it.id] == null }),
        )

        val officerDocRequests = allEvents.filter { it.kind == ReviewEventKind.DOCUMENTS_REQUESTED && it.isOfficer }
        val throughput = Throughput(
            decidedToday = decided.count { LocalDate.ofInstant(decidedAt(it), zone) == today },
            decided7d = decided7d.size,
            decided30d = decided30d.size,
            approved7d = decided7d.count { it.status == ApplicationStatus.APPROVED },
            rejected7d = decided7d.count { it.status == ApplicationStatus.REJECTED },
            approved30d = decided30d.count { it.status == ApplicationStatus.APPROVED },
            rejected30d = decided30d.count { it.status == ApplicationStatus.REJECTED },
            approvalRate30d = decided30d.takeIf { it.isNotEmpty() }?.let { d -> d.count { it.status == ApplicationStatus.APPROVED }.toDouble() / d.size },
            avgDecisionHours7d = decided7d.mapNotNull(::decisionHours).takeIf { it.isNotEmpty() }?.average(),
            avgDecisionHours30d = decided30d.mapNotNull(::decisionHours).takeIf { it.isNotEmpty() }?.average(),
            documentsRequested7d = officerDocRequests.count { it.createdAt >= since7d },
            documentsRequested30d = officerDocRequests.count { it.createdAt >= since30d },
            multiRoundDecided = decided.count { it.reviewRounds >= 2 },
            avgRoundsDecided = decided.takeIf { it.isNotEmpty() }?.map { it.reviewRounds.toDouble() }?.average(),
        )

        val daily = (DAILY_DAYS - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            fun on(i: Instant?) = i != null && LocalDate.ofInstant(i, zone) == day
            DailyRow(
                date = day.toString(),
                label = "${day.monthValue}/${day.dayOfMonth}",
                approved = decided.count { it.status == ApplicationStatus.APPROVED && on(decidedAt(it)) },
                rejected = decided.count { it.status == ApplicationStatus.REJECTED && on(decidedAt(it)) },
                documentsRequested = officerDocRequests.count { on(it.createdAt) },
                completed = apps.count { on(it.riskAssessedAt ?: it.verifiedAt) },
            )
        }

        val reviewers = allEvents.filter { it.isOfficer }
            .groupBy { it.actor!! }
            .map { (name, evs) ->
                ReviewerRow(
                    name = name,
                    approved = evs.count { it.kind == ReviewEventKind.APPROVED },
                    rejected = evs.count { it.kind == ReviewEventKind.REJECTED },
                    documentsRequested = evs.count { it.kind == ReviewEventKind.DOCUMENTS_REQUESTED },
                    resubmissionsRequested = evs.count { it.kind == ReviewEventKind.RESUBMISSION_REQUESTED },
                    reassessments = evs.count { it.kind == ReviewEventKind.RISK_REASSESSED },
                    notes = evs.count { it.kind == ReviewEventKind.NOTE_ADDED },
                    decisions7d = evs.count { (it.kind == ReviewEventKind.APPROVED || it.kind == ReviewEventKind.REJECTED) && it.createdAt >= since7d },
                    lastActivityAt = evs.maxOfOrNull { it.createdAt },
                )
            }
            .sortedWith(compareByDescending<ReviewerRow> { it.approved + it.rejected }.thenBy { it.name })

        val gradeOutcomes = RiskGrade.entries.map { g ->
            val d = decided.filter { RiskGrade.parse(it.riskGrade) == g }
            val approved = d.count { it.status == ApplicationStatus.APPROVED }
            GradeOutcome(g.name, g.labelKo, g.label, d.size, approved, d.size - approved, d.takeIf { it.isNotEmpty() }?.let { approved.toDouble() / it.size })
        }

        val queueFlags = waitingRisk.values.filterNotNull().flatMap(RiskAssessment::flags)
            .groupBy { it.code }
            .map { (code, flags) -> QueueFlag(code, flags.first().title, flags.first().titleKo, flags.maxOf { it.severity }.name, flags.first().fraudSignal, flags.size) }
            .sortedWith(compareByDescending<QueueFlag> { it.count }.thenBy { it.code })

        val flagged = decided.filter { it.officerReviewRequired == true }
        val notFlagged = decided.filter { it.officerReviewRequired != true }  // null = stub mode, the agent never flagged it
        val alignment = AgentAlignment(
            flaggedDecided = flagged.size,
            flaggedApproved = flagged.count { it.status == ApplicationStatus.APPROVED },
            flaggedRejected = flagged.count { it.status == ApplicationStatus.REJECTED },
            notFlaggedDecided = notFlagged.size,
            notFlaggedApproved = notFlagged.count { it.status == ApplicationStatus.APPROVED },
            notFlaggedRejected = notFlagged.count { it.status == ApplicationStatus.REJECTED },
        )

        return Stats(now, workload, throughput, daily, reviewers, gradeOutcomes, queueFlags, alignment, apps.count { it.demoSeed })
    }

    /** Events done by a named officer, not by the pipeline. */
    private val ReviewEvent.isOfficer: Boolean get() = !actor.isNullOrBlank() && actor != ReviewAuditService.SYSTEM

    /** Since when an application has been waiting for an officer: documents completed, else last update. */
    private fun waitingSince(app: DealerApplication): Instant = app.riskAssessedAt ?: app.verifiedAt ?: app.updatedAt

    private fun hours(from: Instant, to: Instant): Double = Duration.between(from, to).toMinutes() / 60.0

    private companion object {
        const val DAILY_DAYS = 14
    }
}
