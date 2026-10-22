package com.nextasean.wawebhook.risk

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/** Runs [RiskRules] over an application's stored evidence and persists the result on the APPLICATIONS row. */
@Service
class RiskAssessmentService(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val documents: DocumentRepository,
    private val codec: DocumentAnalysisCodec,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Assesses and stores; returns the updated application and the assessment. Other still-open applications that share
     * this one's ID number or phone are re-assessed too, so both sides of a shared ID carry the warning.
     */
    fun assessAndStore(app: DealerApplication, cascade: Boolean = true): Pair<DealerApplication, RiskAssessment> {
        val dealer = dealers.findById(app.dealerId).orElse(null)
        val docs = documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!).map { AnalysedDocument(it, codec.fromJson(it.analysisJson)) }
        val related = related(app)
        val assessment = RiskRules.evaluate(app, dealer, docs, related = related)
        if (cascade) {
            (related.sameIdNumber + related.samePhone).distinctBy { it.id }
                .filter { !it.status.isFinal && it.riskAssessedAt != null }
                .forEach { other ->
                    log.info("Re-assessing related application | number={} | because of {}", other.applicationNumber, app.applicationNumber)
                    assessAndStore(applications.findById(other.id!!).orElse(other), cascade = false)
                }
        }
        val saved = applications.save(
            app.copy(
                riskGrade = assessment.grade.name,
                riskScore = assessment.score,
                riskFlagsJson = objectMapper.writeValueAsString(assessment.flags).take(16_000),
                riskMethod = assessment.method.name,
                customerBadges = assessment.badges.joinToString(",") { it.name }.ifBlank { null },
                // First assessment marks "documents complete" for the dashboard; re-assessments keep that timestamp.
                riskAssessedAt = app.riskAssessedAt ?: assessment.assessedAt,
                updatedAt = Instant.now(),
            ),
        )
        log.info(
            "Risk assessed | number={} | grade={} | score={} | flags={} | fraud={} | badges={} | method={}",
            saved.applicationNumber, assessment.grade, assessment.score, assessment.flags.map { it.code },
            assessment.fraudFlags.size, assessment.badges, assessment.method,
        )
        return saved to assessment
    }

    /** Other applications sharing the ID number or the customer phone, for the cross-application rules. */
    fun related(app: DealerApplication): RelatedApplications = RelatedApplications(
        sameIdNumber = app.extractedNik?.takeIf { it.isNotBlank() }?.let { applications.findByExtractedNik(it) }.orEmpty().filter { it.id != app.id },
        samePhone = app.customerPhone?.takeIf { it.isNotBlank() }?.let { applications.findByCustomerPhone(it) }.orEmpty().filter { it.id != app.id },
    )

    /** Re-runs the rules for every application that has reached verification (after a rule change). */
    fun reassessAll(): Int = applications.findAll()
        .filter { it.verifiedAt != null || it.riskAssessedAt != null || it.reviewDecision != null }
        .onEach { assessAndStore(it) }
        .count()

    /** Reads the stored assessment back without re-running the rules; null when never assessed. */
    fun stored(app: DealerApplication): RiskAssessment? {
        val grade = RiskGrade.parse(app.riskGrade) ?: return null
        val flags = app.riskFlagsJson?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<RiskFlag>>() {}) }
                .onFailure { e -> log.warn("Stored risk flags could not be parsed for {}: {}", app.applicationNumber, e.message) }
                .getOrNull()
        } ?: emptyList()
        return RiskAssessment(
            score = app.riskScore ?: 0,
            grade = grade,
            flags = flags,
            badges = CustomerBadge.parseList(app.customerBadges),
            method = RiskMethod.entries.firstOrNull { it.name == app.riskMethod } ?: RiskMethod.RULES,
            assessedAt = app.riskAssessedAt ?: app.updatedAt,
        )
    }
}
