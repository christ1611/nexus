package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.customer.CustomerSessionRepository
import com.nextasean.wawebhook.dealer.Countries
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.risk.CustomerBadge
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.risk.RiskGrade
import com.nextasean.wawebhook.verification.ProviderOutcome
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Step 5: statistics for the back-office dashboard (`/dashboard` renders them). Everything is computed from the
 * APPLICATIONS / DEALERS / CUSTOMER_SESSIONS rows on each call; the volumes of a demo do not need caching.
 */
@Controller
@RequestMapping("/admin/dashboard")
class DashboardController(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val sessions: CustomerSessionRepository,
    private val risk: RiskAssessmentService,
    props: AppProperties,
) {
    private val zone: ZoneId = props.zoneId

    data class Totals(
        val applications: Int,
        val dealers: Int,
        val inProgress: Int,
        val readyForReview: Int,
        /** Step 4: waiting for the customer to resend documents the officer asked for. */
        val documentsRequested: Int,
        val approved: Int,
        val rejected: Int,
        val assessed: Int,
        val highRisk: Int,
        val fraudWarnings: Int,
        val officerReviewRequired: Int,
        /** Oldest application still waiting for a decision, in hours (null when the queue is empty). */
        val oldestWaitingHours: Double?,
        /** Average time from document completion to decision over decided applications, in hours. */
        val avgDecisionHours: Double?,
    )

    data class FunnelStage(val stage: String, val stageKo: String, val count: Int)

    data class CountryStats(
        val code: String,
        val name: String,
        val nameKo: String,
        val idDocument: String,
        val dealers: Int,
        val applications: Int,
        val inProgress: Int,
        val readyForReview: Int,
        val approved: Int,
        val rejected: Int,
        val assessed: Int,
        val highRisk: Int,
        val fraudWarnings: Int,
        val identityMatchRate: Double?,
        val avgCreditScore: Double?,
        val grades: Map<String, Int>,
        val badges: Map<String, Int>,
    )

    data class GradeCount(val grade: String, val label: String, val labelKo: String, val count: Int)
    data class FlagCount(val code: String, val title: String, val titleKo: String, val severity: String, val fraud: Boolean, val count: Int)
    data class BadgeCount(val code: String, val label: String, val labelKo: String, val count: Int)
    data class DealerStats(val code: String, val name: String, val country: String, val tier: String, val applications: Int, val approved: Int, val highRisk: Int)
    data class FlagSummary(val code: String, val title: String, val titleKo: String, val severity: String, val fraud: Boolean, val detail: String)
    data class RiskRow(
        val applicationNumber: String,
        val country: String,
        val dealerCode: String?,
        val customerName: String?,
        val status: String,
        val grade: String,
        val gradeLabelKo: String,
        val score: Int,
        val method: String,
        val fraudWarnings: Int,
        val flags: List<FlagSummary>,
        val badges: List<String>,
        val badgesKo: List<String>,
        val assessedAt: Instant,
    )
    data class RecentRow(val applicationNumber: String, val country: String, val dealerCode: String?, val status: String, val grade: String?, val createdAt: Instant)

    /** Step 4: one application waiting for an officer, with what makes it urgent. */
    data class QueueRow(
        val applicationNumber: String,
        val country: String,
        val dealerCode: String?,
        val customerName: String?,
        val status: String,
        val grade: String?,
        val score: Int?,
        val fraudWarnings: Int,
        val officerReviewRequired: Boolean,
        val waitingSince: Instant,
        val waitingHours: Double,
    )

    /** One ISO week: applications created, completed (documents + checks done), decided, and high-risk share, per country. */
    data class TrendWeek(
        val weekStart: String,
        val label: String,
        val created: Int,
        val completed: Int,
        val approved: Int,
        val rejected: Int,
        val highRisk: Int,
        val fraudWarnings: Int,
        val createdByCountry: Map<String, Int>,
        val highRiskByCountry: Map<String, Int>,
    )

    data class Stats(
        val generatedAt: Instant,
        val countries: List<String>,
        val totals: Totals,
        val funnel: List<FunnelStage>,
        val byCountry: List<CountryStats>,
        val riskGrades: List<GradeCount>,
        val flagCounts: List<FlagCount>,
        val badges: List<BadgeCount>,
        val dealers: List<DealerStats>,
        val highRiskApplications: List<RiskRow>,
        val recent: List<RecentRow>,
        val reviewQueue: List<QueueRow>,
        val trend: List<TrendWeek>,
    )

    @GetMapping("/stats")
    @ResponseBody
    fun stats(): Stats {
        val allDealers = dealers.findAll().toList()
        val dealerById = allDealers.associateBy { it.id }
        val apps = applications.findAllByOrderByCreatedAtDesc()
        val assessments = apps.mapNotNull { app -> risk.stored(app)?.let { app to it } }
        val consented = sessions.findAll().filter { it.consentAt != null }.map { it.applicationId }.toSet()

        val now = Instant.now()
        val waiting = apps.filter { it.status == ApplicationStatus.READY_FOR_REVIEW }
        val decided = apps.filter { it.status.isFinal && it.decidedAt != null && (it.riskAssessedAt ?: it.verifiedAt) != null }
        val totals = Totals(
            applications = apps.size,
            dealers = allDealers.size,
            inProgress = apps.count { !it.status.isFinal && it.status != ApplicationStatus.READY_FOR_REVIEW },
            readyForReview = waiting.size,
            documentsRequested = apps.count { it.status == ApplicationStatus.DOCUMENTS_REQUESTED },
            approved = apps.count { it.status == ApplicationStatus.APPROVED },
            rejected = apps.count { it.status == ApplicationStatus.REJECTED },
            assessed = assessments.size,
            highRisk = assessments.count { it.second.grade.highRisk },
            fraudWarnings = assessments.count { it.second.fraudFlags.isNotEmpty() },
            officerReviewRequired = apps.count { it.officerReviewRequired == true },
            oldestWaitingHours = waiting.minOfOrNull { hours(waitingSince(it), now) },
            avgDecisionHours = decided.takeIf { it.isNotEmpty() }?.map { hours((it.riskAssessedAt ?: it.verifiedAt)!!, it.decidedAt!!) }?.average(),
        )
        val funnel = listOf(
            FunnelStage("Created by dealer", "딜러 신청 생성", apps.size),
            FunnelStage("Customer linked", "고객 연결", apps.count { it.customerPhone != null }),
            FunnelStage("Consent given", "개인정보 동의", apps.count { it.id in consented }),
            FunnelStage("Documents complete", "서류 제출 완료", apps.count { it.riskAssessedAt != null || it.verifiedAt != null }),
            FunnelStage("Ready for review", "심사 대기", apps.count { it.status == ApplicationStatus.READY_FOR_REVIEW || it.status.isFinal }),
            FunnelStage("Approved", "승인", totals.approved),
        )
        val byCountry = Countries.ALL.map { c ->
            val cApps = apps.filter { it.country.equals(c.code, ignoreCase = true) }
            val cAssessed = assessments.filter { it.first.country.equals(c.code, ignoreCase = true) }
            val verified = cApps.filter { it.idVerificationStatus != null }
            val scores = cApps.mapNotNull { it.creditScore }
            CountryStats(
                code = c.code, name = c.name, nameKo = c.nameKo, idDocument = c.idDocument,
                dealers = allDealers.count { it.country.equals(c.code, ignoreCase = true) },
                applications = cApps.size,
                inProgress = cApps.count { !it.status.isFinal && it.status != ApplicationStatus.READY_FOR_REVIEW },
                readyForReview = cApps.count { it.status == ApplicationStatus.READY_FOR_REVIEW },
                approved = cApps.count { it.status == ApplicationStatus.APPROVED },
                rejected = cApps.count { it.status == ApplicationStatus.REJECTED },
                assessed = cAssessed.size,
                highRisk = cAssessed.count { it.second.grade.highRisk },
                fraudWarnings = cAssessed.count { it.second.fraudFlags.isNotEmpty() },
                identityMatchRate = verified.takeIf { it.isNotEmpty() }?.let { v -> v.count { it.idVerificationStatus == ProviderOutcome.MATCH }.toDouble() / v.size },
                avgCreditScore = scores.takeIf { it.isNotEmpty() }?.average(),
                grades = RiskGrade.entries.associate { g -> g.name to cAssessed.count { it.second.grade == g } },
                badges = CustomerBadge.entries.associate { b -> b.name to cAssessed.count { b in it.second.badges } },
            )
        }
        val riskGrades = RiskGrade.entries.map { g -> GradeCount(g.name, g.label, g.labelKo, assessments.count { it.second.grade == g }) }
        val flagCounts = assessments.flatMap { it.second.flags }
            .groupBy { it.code }
            .map { (code, flags) -> FlagCount(code, flags.first().title, flags.first().titleKo, flags.maxOf { it.severity }.name, flags.first().fraudSignal, flags.size) }
            .sortedWith(compareByDescending<FlagCount> { it.count }.thenBy { it.code })
        val badges = CustomerBadge.entries.map { b -> BadgeCount(b.name, b.label, b.labelKo, assessments.count { b in it.second.badges }) }
        val dealerStats = allDealers.sortedBy { it.dealerCode }.map { d ->
            val dApps = apps.filter { it.dealerId == d.id }
            DealerStats(
                d.dealerCode, d.name, d.country, d.tier.name, dApps.size,
                dApps.count { it.status == ApplicationStatus.APPROVED },
                dApps.count { app -> RiskGrade.parse(app.riskGrade)?.highRisk == true },
            )
        }
        val highRisk = assessments.filter { it.second.grade.highRisk }
            .sortedWith(compareByDescending<Pair<DealerApplication, RiskAssessment>> { it.second.score }.thenByDescending { it.second.assessedAt })
            .take(20)
            .map { (app, a) -> riskRow(app, a, dealerById[app.dealerId]) }
        val recent = apps.take(10).map { RecentRow(it.applicationNumber, it.country, dealerById[it.dealerId]?.dealerCode, it.status.name, it.riskGrade, it.createdAt) }
        val queue = waiting
            .sortedWith(compareByDescending<DealerApplication> { it.riskScore ?: -1 }.thenBy { waitingSince(it) })
            .take(20)
            .map { app ->
                val a = risk.stored(app)
                QueueRow(
                    app.applicationNumber, app.country, dealerById[app.dealerId]?.dealerCode, app.extractedName ?: app.customerName, app.status.name,
                    a?.grade?.name, a?.score, a?.fraudFlags?.size ?: 0, app.officerReviewRequired == true, waitingSince(app), hours(waitingSince(app), now),
                )
            }

        return Stats(now, Countries.ALL.map { it.code }, totals, funnel, byCountry, riskGrades, flagCounts, badges, dealerStats, highRisk, recent, queue, trend(apps, assessments, now))
    }

    /** Since when an application has been waiting for an officer: documents completed, else last update. */
    private fun waitingSince(app: DealerApplication): Instant = app.riskAssessedAt ?: app.verifiedAt ?: app.updatedAt

    private fun hours(from: Instant, to: Instant): Double = Duration.between(from, to).toMinutes() / 60.0

    /** The last [TREND_WEEKS] ISO weeks (Monday start, dashboard time zone), oldest first, always present even when empty. */
    private fun trend(apps: List<DealerApplication>, assessments: List<Pair<DealerApplication, RiskAssessment>>, now: Instant): List<TrendWeek> {
        val thisWeek = LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val assessedById = assessments.associate { it.first.id to it.second }
        fun week(i: Instant?): LocalDate? = i?.let { LocalDate.ofInstant(it, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return (TREND_WEEKS - 1 downTo 0).map { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            val created = apps.filter { week(it.createdAt) == start }
            val completed = apps.filter { week(it.riskAssessedAt ?: it.verifiedAt) == start }
            val decidedThisWeek = apps.filter { it.status.isFinal && week(it.decidedAt ?: it.updatedAt) == start }
            val assessedThisWeek = completed.mapNotNull { assessedById[it.id] }
            TrendWeek(
                weekStart = start.toString(),
                label = "${start.monthValue}/${start.dayOfMonth}",
                created = created.size,
                completed = completed.size,
                approved = decidedThisWeek.count { it.status == ApplicationStatus.APPROVED },
                rejected = decidedThisWeek.count { it.status == ApplicationStatus.REJECTED },
                highRisk = assessedThisWeek.count { it.grade.highRisk },
                fraudWarnings = assessedThisWeek.count { it.fraudFlags.isNotEmpty() },
                createdByCountry = Countries.ALL.associate { c -> c.code to created.count { it.country.equals(c.code, ignoreCase = true) } },
                highRiskByCountry = Countries.ALL.associate { c -> c.code to completed.count { it.country.equals(c.code, ignoreCase = true) && assessedById[it.id]?.grade?.highRisk == true } },
            )
        }
    }

    private companion object {
        const val TREND_WEEKS = 8
    }

    private fun riskRow(app: DealerApplication, a: RiskAssessment, dealer: Dealer?) = RiskRow(
        applicationNumber = app.applicationNumber,
        country = app.country,
        dealerCode = dealer?.dealerCode,
        customerName = app.extractedName ?: app.customerName,
        status = app.status.name,
        grade = a.grade.name,
        gradeLabelKo = a.grade.labelKo,
        score = a.score,
        method = a.method.name,
        fraudWarnings = a.fraudFlags.size,
        flags = a.flags.map { FlagSummary(it.code, it.title, it.titleKo, it.severity.name, it.fraudSignal, it.detail) },
        badges = a.badges.map { it.label },
        badgesKo = a.badges.map { it.labelKo },
        assessedAt = a.assessedAt,
    )

}
