package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
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
import java.time.Instant

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
) {

    data class Totals(
        val applications: Int,
        val dealers: Int,
        val inProgress: Int,
        val readyForReview: Int,
        val approved: Int,
        val rejected: Int,
        val assessed: Int,
        val highRisk: Int,
        val fraudWarnings: Int,
        val officerReviewRequired: Int,
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
    )

    @GetMapping("/stats")
    @ResponseBody
    fun stats(): Stats {
        val allDealers = dealers.findAll().toList()
        val dealerById = allDealers.associateBy { it.id }
        val apps = applications.findAllByOrderByCreatedAtDesc()
        val assessments = apps.mapNotNull { app -> risk.stored(app)?.let { app to it } }
        val consented = sessions.findAll().filter { it.consentAt != null }.map { it.applicationId }.toSet()

        val totals = Totals(
            applications = apps.size,
            dealers = allDealers.size,
            inProgress = apps.count { !it.status.isFinal && it.status != ApplicationStatus.READY_FOR_REVIEW },
            readyForReview = apps.count { it.status == ApplicationStatus.READY_FOR_REVIEW },
            approved = apps.count { it.status == ApplicationStatus.APPROVED },
            rejected = apps.count { it.status == ApplicationStatus.REJECTED },
            assessed = assessments.size,
            highRisk = assessments.count { it.second.grade.highRisk },
            fraudWarnings = assessments.count { it.second.fraudFlags.isNotEmpty() },
            officerReviewRequired = apps.count { it.officerReviewRequired == true },
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

        return Stats(Instant.now(), Countries.ALL.map { it.code }, totals, funnel, byCountry, riskGrades, flagCounts, badges, dealerStats, highRisk, recent)
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
