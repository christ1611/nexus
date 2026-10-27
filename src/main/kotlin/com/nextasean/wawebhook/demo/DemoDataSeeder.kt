package com.nextasean.wawebhook.demo

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.config.DemoProperties
import com.nextasean.wawebhook.customer.CustomerSession
import com.nextasean.wawebhook.customer.CustomerSessionRepository
import com.nextasean.wawebhook.customer.CustomerSessionState
import com.nextasean.wawebhook.dealer.Countries
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.dealer.DealerTier
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.DetectedDocumentType
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import com.nextasean.wawebhook.documents.analysis.ExtractedField
import com.nextasean.wawebhook.documents.analysis.FieldStatus
import com.nextasean.wawebhook.documents.analysis.ModelUsage
import com.nextasean.wawebhook.documents.analysis.NikValidator
import com.nextasean.wawebhook.documents.analysis.TamperSignal
import com.nextasean.wawebhook.documents.analysis.TamperSignalType
import com.nextasean.wawebhook.documents.analysis.QualityIssue
import com.nextasean.wawebhook.documents.analysis.QualityIssueType
import com.nextasean.wawebhook.documents.analysis.Severity
import com.nextasean.wawebhook.review.ReviewEvent
import com.nextasean.wawebhook.review.ReviewEventKind
import com.nextasean.wawebhook.review.ReviewEventRepository
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.trace.AgentStep
import com.nextasean.wawebhook.trace.AgentStepRepository
import com.nextasean.wawebhook.trace.AgentTraceService
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.trace.TraceStatus
import com.nextasean.wawebhook.verification.ProviderOutcome
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fake applications for demos: realistic-looking customers across Indonesia, Myanmar and Cambodia, spread over the last
 * eight weeks, with documents (placeholder images), provider results, risk grades from the real [com.nextasean.wawebhook.risk.RiskRules],
 * officer decisions, review events and agent-timeline steps. Everything is written straight to the database: no WhatsApp
 * message is sent. Rows are marked `demo_seed = true` so they can be removed again with [clear].
 */
@Service
class DemoDataSeeder(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val sessions: CustomerSessionRepository,
    private val documents: DocumentRepository,
    private val events: ReviewEventRepository,
    private val steps: AgentStepRepository,
    private val risk: RiskAssessmentService,
    private val codec: DocumentAnalysisCodec,
    private val jdbcClient: JdbcClient,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class SeedResult(val created: Int, val removed: Int, val total: Int)

    private enum class Scenario { CLEAN, MEDIUM, IDENTITY_MISMATCH, NOT_FOUND, TIMEOUT, SCREEN_PHOTO, INVALID_NIK, SELFIE_MISMATCH, FORGERY, SHARED_ID, SHARED_PHONE, IN_PROGRESS, DRAFT }

    /** NIK of the last clean Indonesian application seeded in this run; [Scenario.SHARED_ID] submits it again under another name. */
    private var lastSeededNik: String? = null
    private var lastSeededName: String? = null
    /** Phone and dealer of that application; [Scenario.SHARED_PHONE] reuses the phone, and both shared scenarios usually come through the same dealer. */
    private var lastSeededPhone: String? = null
    private var lastSeededDealerId: Long? = null

    private class Persona(val name: String, val dob: LocalDate, val gender: String, val address: String, val phone: String)

    /** Creates [count] demo applications; with [reset] the previous demo rows are removed first. */
    @Transactional
    fun seed(count: Int = DEFAULT_COUNT, reset: Boolean = false, seed: Long = DEFAULT_SEED): SeedResult {
        val removed = if (reset) clear() else 0
        lastSeededNik = null
        lastSeededName = null
        lastSeededPhone = null
        lastSeededDealerId = null
        val random = Random(seed + applications.findByDemoSeedTrue().size)
        val dealersByCountry = ensureDealers()
        val now = Instant.now()
        var created = 0
        repeat(count.coerceIn(1, MAX_COUNT)) { i ->
            val randomCountry = when {
                random.nextDouble() < 0.6 -> "ID"
                random.nextDouble() < 0.5 -> "MM"
                else -> "KH"
            }
            // The first few are fixed so every screen has its showcase case (fraud flags, officer hand-off, queue, in-progress);
            // after that scenarios are random, with a fifth of them landing in the last two days.
            val fixed = i < SHOWCASE.size && count >= SHOWCASE.size
            val country = if (fixed) "ID" else randomCountry
            var scenario = if (fixed) SHOWCASE[i].first else scenarioFor(random, country)
            // Excellent dealers bring in more volume and cleaner customers: a weighted pick, the showcase fraud cases filed by
            // standard dealers, and most risky random scenarios re-rolled to clean when an excellent dealer drew them.
            val pool = dealersByCountry.getValue(country)
            val weighted = pool.flatMap { d -> List(if (d.tier == DealerTier.EXCELLENT) 2 else 1) { d } }
            val standard = pool.filter { it.tier != DealerTier.EXCELLENT }
            val ringDealer = if (scenario in SHARED) lastSeededDealerId?.let { id -> pool.firstOrNull { it.id == id } }?.takeIf { random.nextDouble() < 0.7 } else null
            val dealer = ringDealer ?: if (fixed && scenario in RISKY && standard.isNotEmpty()) standard[random.nextInt(standard.size)] else weighted[random.nextInt(weighted.size)]
            if (!fixed && dealer.tier == DealerTier.EXCELLENT && scenario in RISKY && random.nextDouble() < 0.6) scenario = Scenario.CLEAN
            val daysAgo = when {
                fixed -> SHOWCASE[i].second
                random.nextDouble() < 0.2 -> random.nextDouble() * 2
                else -> (TREND_DAYS * (1 - sqrt(random.nextDouble()))).coerceAtMost(TREND_DAYS - 0.1)
            }
            // The customer journey below adds up to about four hours of offsets, so nothing may start later than that.
            val createdAt = now.minus(Duration.ofMinutes((daysAgo * 24 * 60).toLong() + random.nextInt(0, 600)))
                .let { if (scenario == Scenario.DRAFT) it else minOf(it, now.minus(JOURNEY_MARGIN)) }
            createOne(random, country, dealer, scenario, createdAt, now, i)
            created++
        }
        val total = applications.findByDemoSeedTrue().size
        log.info("Demo data seeded | created={} | removed={} | demoApplications={}", created, removed, total)
        return SeedResult(created, removed, total)
    }

    /** Removes every demo application with its sessions, documents (and files), events and agent steps. */
    @Transactional
    fun clear(): Int {
        val apps = applications.findByDemoSeedTrue()
        apps.forEach { app ->
            val id = app.id!!
            steps.deleteAll(steps.findByApplicationIdOrderByCreatedAtAscIdAsc(id))
            events.deleteAll(events.findByApplicationIdOrderByCreatedAtAsc(id))
            documents.findByApplicationIdOrderByCreatedAtAsc(id).forEach { d ->
                runCatching { Files.deleteIfExists(Path.of(d.filePath)) }
                documents.delete(d)
            }
            sessions.deleteAll(sessions.findByApplicationIdOrderByCreatedAtDesc(id))
            applications.delete(app)
            val dir = Path.of(props.documentsDir).resolve(app.applicationNumber)
            if (Files.isDirectory(dir)) runCatching { Files.walk(dir).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
                .onFailure { log.warn("Demo document folder {} could not be removed: {}", dir, it.message) }
        }
        dealers.findAll().filter { it.dealerCode.startsWith(DEMO_DEALER_PREFIX) && applications.findByDealerIdOrderByCreatedAtDesc(it.id!!).isEmpty() }
            .forEach { dealers.delete(it) }
        log.info("Demo data cleared | removed={}", apps.size)
        return apps.size
    }

    fun count(): Int = applications.findByDemoSeedTrue().size

    /** The configured dealers plus the demo roster ([DEMO_DEALERS]) per country; roster dealers are created on demand and removed by [clear] once unused. */
    private fun ensureDealers(): Map<String, List<Dealer>> {
        DEMO_DEALERS.forEachIndexed { i, (suffix, name, tier) ->
            val code = "$DEMO_DEALER_PREFIX$suffix"
            if (dealers.findByDealerCode(code) == null) {
                // Not a dialable range; the notifiers skip demo rows anyway.
                dealers.save(Dealer(phone = "6280000%05d".format(100 + i), name = name, dealerCode = code, country = suffix.substringBefore('-'), tier = tier))
                log.info("Demo dealer created | code={} | name={} | tier={}", code, name, tier)
            }
        }
        return Countries.ALL.associate { c -> c.code to dealers.findAll().filter { it.active && it.country.equals(c.code, ignoreCase = true) } }
    }

    /** True when every roster dealer exists, i.e. the stored demo data was made by this version of the seeder. */
    fun rosterComplete(): Boolean = DEMO_DEALERS.all { dealers.findByDealerCode("$DEMO_DEALER_PREFIX${it.first}") != null }

    private fun scenarioFor(random: Random, country: String): Scenario {
        // Fraud rings: now and then an Indonesian application reuses the last clean customer's ID number or phone.
        if (country == "ID" && lastSeededNik != null) {
            val ring = random.nextDouble()
            if (ring < 0.05) return Scenario.SHARED_ID
            if (ring < 0.10) return Scenario.SHARED_PHONE
        }
        val r = random.nextDouble()
        return when {
            r < 0.42 -> Scenario.CLEAN
            r < 0.58 -> Scenario.MEDIUM
            r < 0.65 -> Scenario.IDENTITY_MISMATCH
            r < 0.69 -> Scenario.NOT_FOUND
            r < 0.73 -> Scenario.TIMEOUT
            r < 0.78 -> if (country == "ID") Scenario.SCREEN_PHOTO else Scenario.MEDIUM
            r < 0.83 -> if (country == "ID") Scenario.INVALID_NIK else Scenario.NOT_FOUND
            r < 0.86 -> if (country == "ID") Scenario.SELFIE_MISMATCH else Scenario.IDENTITY_MISMATCH
            r < 0.89 -> if (country == "ID") Scenario.FORGERY else Scenario.MEDIUM
            r < 0.95 -> Scenario.IN_PROGRESS
            else -> Scenario.DRAFT
        }
    }

    private fun createOne(random: Random, country: String, dealer: Dealer, scenario: Scenario, createdAt: Instant, now: Instant, index: Int) {
        var persona = persona(random, country, index)
        // A shared ID or phone must come from a different person: never reuse the previous customer's name.
        var tries = 0
        while (scenario in SHARED && persona.name == lastSeededName && tries++ < 10) persona = persona(random, country, index)
        if (scenario == Scenario.SHARED_PHONE && lastSeededPhone != null) persona = Persona(persona.name, persona.dob, persona.gender, persona.address, lastSeededPhone!!)
        val number = nextNumber(createdAt)
        var app = applications.save(
            DealerApplication(applicationNumber = number, dealerId = dealer.id!!, status = ApplicationStatus.DRAFT, country = country, demoSeed = true, createdAt = createdAt, updatedAt = createdAt),
        )
        if (scenario == Scenario.DRAFT) {
            // Dealer-side states without a customer yet: plain draft, form submitted, or a form sent back to the dealer.
            val r = random.nextDouble()
            if (r < 0.35) applications.save(app.copy(status = ApplicationStatus.SUBMITTED, updatedAt = createdAt.plus(Duration.ofMinutes(random.nextLong(10, 300)))))
            else if (r < 0.5) applications.save(app.copy(status = ApplicationStatus.RESUBMISSION_REQUESTED, resubmissionReason = "Income proof missing on the form", updatedAt = createdAt.plus(Duration.ofHours(random.nextLong(2, 30)))))
            return
        }

        // Customer links and consents a little later.
        val startAt = createdAt.plus(Duration.ofMinutes(random.nextLong(5, 180)))
        val consentAt = startAt.plus(Duration.ofMinutes(random.nextLong(1, 15)))
        app = applications.save(app.copy(customerPhone = persona.phone, customerName = persona.name.substringBefore(' '), updatedAt = consentAt))

        if (scenario == Scenario.IN_PROGRESS) {
            val state = if (random.nextBoolean()) CustomerSessionState.AWAITING_KTP else CustomerSessionState.AWAITING_SELFIE
            sessions.save(CustomerSession(phone = persona.phone, customerName = app.customerName, applicationId = app.id!!, state = state, consentAt = consentAt, createdAt = startAt, updatedAt = consentAt))
            if (state == CustomerSessionState.AWAITING_SELFIE) {
                val t = consentAt.plus(Duration.ofMinutes(3))
                val idNumber = idNumber(random, country, persona, lastDigit = random.nextInt(0, 7))
                storeDocument(app, DocumentType.KTP, persona, idNumber, t, passed = true, analysis = null)
                step(app, TraceKind.DOCUMENT_RECEIVED, TraceStatus.INFO, AgentTraceService.CUSTOMER, "KTP photo received", "82 KB, image/jpeg", t)
                step(app, TraceKind.QUALITY_CHECK, TraceStatus.OK, AgentTraceService.STUB, "KTP: accepted", "Demo check (stub mode)", t.plusSeconds(2), 2100)
                step(app, TraceKind.FIELD_EXTRACTION, TraceStatus.OK, AgentTraceService.STUB, "Sample fields used (stub mode): NIK ***${idNumber.takeLast(4)}", null, t.plusSeconds(2))
                applications.save(app.copy(extractedName = persona.name, extractedNik = idNumber, extractedDob = persona.dob.toString(), extractedAddress = persona.address, updatedAt = t))
            }
            return
        }

        // Full document journey.
        val claudeMode = scenario in setOf(Scenario.SCREEN_PHOTO, Scenario.INVALID_NIK, Scenario.SELFIE_MISMATCH, Scenario.FORGERY) || (country == "ID" && scenario != Scenario.SHARED_ID && random.nextDouble() < 0.35)
        // A shared ID reuses the NIK of the last clean Indonesian application: a different person submitting the same card.
        val reusedNik = if (scenario == Scenario.SHARED_ID && country == "ID") lastSeededNik else null
        val lastDigit = reusedNik?.last()?.digitToInt() ?: when (scenario) {
            Scenario.IDENTITY_MISMATCH, Scenario.SELFIE_MISMATCH -> 7
            Scenario.NOT_FOUND -> 8
            Scenario.TIMEOUT -> 9
            else -> random.nextInt(0, 7)
        }
        val idNumber = reusedNik ?: idNumber(random, country, persona, lastDigit, encodeWrongDob = scenario == Scenario.INVALID_NIK)
        if (country == "ID" && scenario in setOf(Scenario.CLEAN, Scenario.MEDIUM)) { lastSeededNik = idNumber; lastSeededName = persona.name; lastSeededPhone = persona.phone; lastSeededDealerId = dealer.id }
        sessions.save(CustomerSession(phone = persona.phone, customerName = app.customerName, applicationId = app.id!!, state = CustomerSessionState.COMPLETED, consentAt = consentAt, createdAt = startAt, updatedAt = consentAt.plus(Duration.ofMinutes(12))))
        var t = consentAt.plus(Duration.ofMinutes(random.nextLong(1, 6)))
        var round = 0

        // Optional blurry first attempt (fails the check right away).
        if (random.nextDouble() < 0.25) {
            storeDocument(app, DocumentType.KTP, persona, idNumber, t, passed = false, reason = "Photo is blurry", analysis = null)
            step(app, TraceKind.DOCUMENT_RECEIVED, TraceStatus.INFO, AgentTraceService.CUSTOMER, "KTP photo received", "61 KB, image/jpeg", t)
            step(app, if (claudeMode) TraceKind.VISION_ANALYSIS else TraceKind.QUALITY_CHECK, TraceStatus.FAIL, if (claudeMode) AgentTraceService.claude(MODEL) else AgentTraceService.STUB, "KTP: rejected", "BLUR HIGH: text on the card is not legible", t.plusSeconds(3), if (claudeMode) 4200 else 2000)
            step(app, TraceKind.CUSTOMER_MESSAGED, TraceStatus.DECISION, AgentTraceService.SYSTEM, "Asked the customer to retake the KTP", "Photo is blurry", t.plusSeconds(3))
            t = t.plus(Duration.ofMinutes(random.nextLong(1, 4)))
        }

        // Accepted KTP.
        val ktpAnalysis = if (claudeMode) ktpAnalysis(idNumber, persona, scenario) else null
        storeDocument(app, DocumentType.KTP, persona, idNumber, t, passed = true, analysis = ktpAnalysis)
        step(app, TraceKind.DOCUMENT_RECEIVED, TraceStatus.INFO, AgentTraceService.CUSTOMER, "KTP photo received", "88 KB, image/jpeg", t)
        if (ktpAnalysis != null) {
            step(
                app, TraceKind.VISION_ANALYSIS, TraceStatus.OK, AgentTraceService.claude(MODEL), "Seen as ${ktpAnalysis.documentType.label} (${(ktpAnalysis.documentTypeConfidence * 100).toInt()}%), usable",
                listOf(
                    if (ktpAnalysis.qualityIssues.isEmpty()) "No quality issues" else ktpAnalysis.qualityIssues.joinToString("; ") { "${it.type} ${it.severity}: ${it.detail}" },
                    if (ktpAnalysis.tamperSignals.isEmpty()) "No forgery evidence" else "Forgery evidence: " + ktpAnalysis.tamperSignals.joinToString("; ") { "${it.type} ${it.severity}: ${it.detail}" },
                ).joinToString(". "),
                t.plusSeconds(4), ktpAnalysis.usage?.durationMs, mapOf("documentType" to ktpAnalysis.documentType.name, "confidence" to ktpAnalysis.documentTypeConfidence, "usage" to ktpAnalysis.usage, "qualityIssues" to ktpAnalysis.qualityIssues, "tamperSignals" to ktpAnalysis.tamperSignals),
            )
            val n = ktpAnalysis.nik!!
            step(
                app, TraceKind.NIK_VALIDATION, if (n.valid) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.RULES,
                if (n.valid) "NIK structure valid (${n.provinceName})" else "NIK validation failed: ${n.issues.first()}",
                "Encoded DOB ${n.dobFromNik} (${n.genderFromNik}) vs card ${n.statedDob} (${n.statedGender})", t.plusSeconds(4), null, mapOf("issues" to n.issues, "dobMatches" to n.dobMatches, "genderMatches" to n.genderMatches),
            )
            step(
                app, TraceKind.FIELD_EXTRACTION, if (n.valid) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.RULES,
                if (n.valid) "Name, NIK, date of birth and address read with confidence >= 0.85" else "Required fields not confident enough: no automatic verification",
                if (n.valid) null else "needs officer review: nik", t.plusSeconds(4),
            )
        } else {
            step(app, TraceKind.QUALITY_CHECK, TraceStatus.OK, AgentTraceService.STUB, "KTP: accepted", "Demo check (stub mode): no vision model configured", t.plusSeconds(2), 2000)
            step(app, TraceKind.FIELD_EXTRACTION, TraceStatus.OK, AgentTraceService.STUB, "Sample fields used (stub mode): NIK ***${idNumber.takeLast(4)}", null, t.plusSeconds(2))
        }
        val nikUsable = ktpAnalysis?.nik?.valid != false
        if (nikUsable) app = applications.save(app.copy(extractedName = persona.name, extractedNik = idNumber, extractedDob = persona.dob.toString(), extractedAddress = persona.address, updatedAt = t))

        // Selfie.
        t = t.plus(Duration.ofMinutes(random.nextLong(1, 3)))
        val selfieAnalysis = if (claudeMode) selfieAnalysis(idNumber, scenario, random) else null
        storeDocument(app, DocumentType.SELFIE_KTP, persona, idNumber, t, passed = true, analysis = selfieAnalysis)
        step(app, TraceKind.DOCUMENT_RECEIVED, TraceStatus.INFO, AgentTraceService.CUSTOMER, "Selfie with KTP photo received", "104 KB, image/jpeg", t)
        if (selfieAnalysis != null) {
            step(app, TraceKind.VISION_ANALYSIS, TraceStatus.OK, AgentTraceService.claude(MODEL), "Seen as Selfie holding a KTP (${(selfieAnalysis.documentTypeConfidence * 100).toInt()}%), usable", "No quality issues", t.plusSeconds(4), selfieAnalysis.usage?.durationMs, mapOf("usage" to selfieAnalysis.usage, "fields" to selfieAnalysis.fields))
        } else {
            step(app, TraceKind.QUALITY_CHECK, TraceStatus.OK, AgentTraceService.STUB, "Selfie with KTP: accepted", "Demo check (stub mode)", t.plusSeconds(2), 2000)
        }

        // Review agent (Claude mode only).
        var summary: String? = null
        var officerReview: Boolean? = null
        if (claudeMode) {
            t = t.plusSeconds(6)
            officerReview = !nikUsable
            val turnMs = random.nextLong(5_000, 14_000)
            step(
                app, TraceKind.AGENT_TURN, TraceStatus.INFO, AgentTraceService.claude(MODEL), "Turn 1: stop=tool_use -> tool write_summary",
                if (officerReview) "The NIK fails its structural validation; this is a case for the officer rather than a retake." else "All required fields are legible and the NIK validates. Writing the summary.",
                t, turnMs, mapOf("attempt" to 1, "stopReason" to "tool_use", "tool" to "write_summary", "model" to MODEL, "inputTokens" to random.nextInt(2_600, 3_400), "cacheReadTokens" to 1_980, "outputTokens" to random.nextInt(380, 720), "durationMs" to turnMs),
            )
            summary = summaryText(number, persona, idNumber, ktpAnalysis, officerReview)
            step(app, TraceKind.AGENT_TOOL_CALL, TraceStatus.DECISION, AgentTraceService.claude(MODEL), "write_summary(...)", "officer_review_required=$officerReview | " + summary.lineSequence().take(2).joinToString(" / "), t.plusMillis(turnMs), null, mapOf("tool" to "write_summary", "input" to mapOf("officer_review_required" to officerReview, "summary" to summary)))
            step(app, TraceKind.AGENT_DECISION, if (officerReview) TraceStatus.WARN else TraceStatus.OK, AgentTraceService.claude(MODEL), "Decision: write the officer summary" + (if (officerReview) " - officer review required" else " - no manual items"), summary.lineSequence().take(3).joinToString(" / "), t.plusMillis(turnMs), null, mapOf("tool" to "write_summary", "officerReviewRequired" to officerReview))
            t = t.plusMillis(turnMs)
        }

        // Verification (or hand-off when the NIK could not be used).
        var idStatus: ProviderOutcome? = null; var creditStatus: ProviderOutcome? = null; var score: Int? = null
        var idDetail: String? = null; var creditDetail: String? = null
        val completedAt: Instant
        if (!nikUsable) {
            step(app, TraceKind.HANDOFF, TraceStatus.WARN, AgentTraceService.RULES, "No confidently read ID number: provider lookups skipped, officer must verify", null, t.plusSeconds(1))
            completedAt = t.plusSeconds(1)
        } else {
            val (idName, creditName) = providers(country)
            idStatus = ProviderOutcome.entries.first { it == outcomeFor(lastDigit) }
            creditStatus = when (scenario) { Scenario.IDENTITY_MISMATCH, Scenario.SELFIE_MISMATCH -> ProviderOutcome.MISMATCH; Scenario.NOT_FOUND -> ProviderOutcome.NOT_FOUND; Scenario.TIMEOUT -> ProviderOutcome.TIMEOUT; else -> ProviderOutcome.MATCH }
            score = when {
                creditStatus != ProviderOutcome.MATCH -> null
                scenario == Scenario.MEDIUM -> random.nextInt(600, 649)
                else -> random.nextInt(700, 790)
            }
            idDetail = "$idName: " + when (idStatus) {
                ProviderOutcome.MATCH -> "ID number, name and date of birth match the registry"
                ProviderOutcome.MISMATCH -> "ID number found but name/date of birth do not match the registry"
                ProviderOutcome.NOT_FOUND -> "ID number is not registered"
                else -> "$idName did not respond in time"
            }
            creditDetail = "$creditName: " + when (creditStatus) {
                ProviderOutcome.MATCH -> "Credit record found, score $score, no delinquencies in the last 24 months"
                ProviderOutcome.MISMATCH -> "Identity data does not match the bureau record"
                ProviderOutcome.NOT_FOUND -> "No credit record for this ID number"
                else -> "$creditName did not respond in time"
            }
            val idMs = random.nextLong(900, 2_900); val crMs = random.nextLong(900, 2_900)
            step(app, TraceKind.ID_VERIFICATION, if (idStatus == ProviderOutcome.MATCH) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.provider(idName), "$idName: ${idStatus.label}", idDetail.substringAfter(": "), t.plusMillis(idMs), idMs, mapOf("outcome" to idStatus.name))
            step(app, TraceKind.CREDIT_CHECK, if (creditStatus == ProviderOutcome.MATCH) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.provider(creditName), "$creditName: ${creditStatus.label}${score?.let { ", score $it" } ?: ""}", creditDetail.substringAfter(": "), t.plusMillis(idMs + crMs), crMs, mapOf("outcome" to creditStatus.name, "score" to score))
            completedAt = t.plusMillis(idMs + crMs)
        }
        app = applications.save(
            app.copy(
                status = ApplicationStatus.READY_FOR_REVIEW, idVerificationStatus = idStatus, idVerificationDetail = idDetail?.take(255), creditCheckStatus = creditStatus,
                creditCheckDetail = creditDetail?.take(255), creditScore = score, verifiedAt = if (nikUsable) completedAt else null,
                reviewDecision = if (claudeMode) "SUMMARY_WRITTEN" else null, reviewSummary = summary, officerReviewRequired = officerReview, reviewedAt = if (claudeMode) completedAt else null,
                reviewRounds = round, updatedAt = completedAt,
            ),
        )

        // Risk rules run for real; then the timestamps are moved back to the completion time.
        val (assessed, assessment) = risk.assessAndStore(app)
        app = applications.save(assessed.copy(riskAssessedAt = completedAt, updatedAt = completedAt))
        step(app, TraceKind.RISK_ASSESSMENT, when { assessment.grade.highRisk -> TraceStatus.WARN; assessment.flags.isEmpty() -> TraceStatus.OK; else -> TraceStatus.INFO }, AgentTraceService.RULES, assessment.headline, if (assessment.flags.isEmpty()) "No risk flags" else assessment.flags.joinToString("; ") { "${it.code} (${it.severity.name.lowercase()})" }, completedAt, null, mapOf("grade" to assessment.grade.name, "score" to assessment.score, "flags" to assessment.flags.map { it.code }))
        step(app, TraceKind.SUBMITTED, TraceStatus.OK, AgentTraceService.SYSTEM, "Application is ready for review", "Customer and dealer notified", completedAt)
        event(app, ReviewEventKind.DOCUMENTS_COMPLETE, "system", if (nikUsable) "ID verification: ${idStatus!!.label}; credit bureau: ${creditStatus!!.label}${score?.let { ", score $it" } ?: ""}" else "Handed to officer without provider checks (ID number not read confidently)", completedAt)
        event(app, ReviewEventKind.RISK_ASSESSED, "system", assessment.headline, completedAt)

        // Officer outcome depends on how long ago the documents were completed.
        val waitingHours = Duration.between(completedAt, now).toHours()
        val officer = OFFICERS[random.nextInt(OFFICERS.size)]
        val decideProbability = if (waitingHours > 48) 0.85 else 0.4
        when {
            waitingHours < 6 -> Unit // still in the queue
            waitingHours < 72 && assessment.grade.highRisk && country == "ID" && (scenario == Scenario.SCREEN_PHOTO || random.nextDouble() < 0.3) -> {
                // Officer sent the customer back for the KTP; still waiting for the new photo.
                val at = completedAt.plus(Duration.ofHours(random.nextLong(1, 6)))
                val reason = "KTP: the photo looks like a screen capture, please photograph the physical card"
                documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!).filter { it.type == DocumentType.KTP && it.checkPassed == true }.forEach { documents.save(it.copy(superseded = true)) }
                app = applications.save(app.copy(status = ApplicationStatus.DOCUMENTS_REQUESTED, resubmissionReason = reason, reviewDecision = "OFFICER_REQUEST_DOCUMENTS", reviewRounds = round + 1, reviewedAt = at, extractedNik = null, extractedName = null, extractedDob = null, extractedAddress = null, updatedAt = at))
                sessions.findByApplicationIdOrderByCreatedAtDesc(app.id!!).forEach { sessions.save(it.copy(state = CustomerSessionState.CANCELLED, updatedAt = at)) }
                sessions.save(CustomerSession(phone = persona.phone, customerName = app.customerName, applicationId = app.id!!, state = CustomerSessionState.AWAITING_KTP, consentAt = consentAt, createdAt = at, updatedAt = at))
                event(app, ReviewEventKind.DOCUMENTS_REQUESTED, officer, reason, at)
                step(app, TraceKind.OFFICER_REQUEST, TraceStatus.DECISION, AgentTraceService.officer(officer), "Officer sent the customer back for KTP", reason, at, null, null, round + 1)
            }
            random.nextDouble() < decideProbability -> {
                val at = completedAt.plus(Duration.ofMinutes(random.nextLong(40, 36 * 60)).coerceAtMost(Duration.between(completedAt, now).minusMinutes(1)))
                val approve = when (assessment.grade.name) { "A" -> true; "B" -> random.nextDouble() < 0.85; "C" -> random.nextDouble() < 0.3; else -> false }
                val note = if (approve) APPROVE_NOTES[random.nextInt(APPROVE_NOTES.size)] else REJECT_NOTES[random.nextInt(REJECT_NOTES.size)]
                if (random.nextDouble() < 0.3) event(app, ReviewEventKind.NOTE_ADDED, officer, OFFICER_NOTES[random.nextInt(OFFICER_NOTES.size)], at.minus(Duration.ofMinutes(20)))
                app = applications.save(app.copy(status = if (approve) ApplicationStatus.APPROVED else ApplicationStatus.REJECTED, decisionNote = note, decidedBy = officer, decidedAt = at, updatedAt = at))
                event(app, if (approve) ReviewEventKind.APPROVED else ReviewEventKind.REJECTED, officer, note, at)
                step(app, TraceKind.OFFICER_DECISION, if (approve) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.officer(officer), "Officer ${if (approve) "approved" else "rejected"} the application", note, at)
            }
            else -> Unit
        }
    }

    // ---------- helpers ----------

    private fun nextNumber(createdAt: Instant): String {
        val seq = jdbcClient.sql("SELECT NEXT VALUE FOR application_number_seq").query(Long::class.java).single()
        return "APP-${Year.from(createdAt.atZone(props.zoneId))}-${"%06d".format(seq)}"
    }

    private fun persona(random: Random, country: String, index: Int): Persona {
        val names = NAMES.getValue(country)
        val name = names[random.nextInt(names.size)]
        val female = index % 3 == 1
        val dob = LocalDate.of(random.nextInt(1968, 2003), random.nextInt(1, 13), random.nextInt(1, 28))
        val addresses = ADDRESSES.getValue(country)
        // Not a dialable range: the notifiers skip demo rows anyway, but the number must never belong to a real person.
        val phone = when (country) { "ID" -> "620000"; "MM" -> "950000"; else -> "855000" } + "%06d".format(random.nextInt(100_000, 999_999))
        return Persona(name, dob, if (female) "PEREMPUAN" else "LAKI-LAKI", addresses[random.nextInt(addresses.size)], phone)
    }

    /** 16-digit number in the KTP layout (province, regency, district, DDMMYY with +40 for women, serial); the last digit drives the mock providers. */
    private fun idNumber(random: Random, country: String, persona: Persona, lastDigit: Int, encodeWrongDob: Boolean = false): String {
        val province = if (country == "ID") PROVINCES[random.nextInt(PROVINCES.size)] else "%02d".format(random.nextInt(10, 90))
        val regency = "%02d".format(random.nextInt(1, 12))
        val district = "%02d".format(random.nextInt(1, 25))
        val dob = if (encodeWrongDob) persona.dob.minusYears(3).plusMonths(2) else persona.dob
        val day = dob.dayOfMonth + if (persona.gender == "PEREMPUAN") 40 else 0
        val date = "%02d%02d%02d".format(day, dob.monthValue, dob.year % 100)
        val serial = "%03d".format(random.nextInt(1, 999)) + lastDigit
        return province + regency + district + date + serial
    }

    private fun outcomeFor(lastDigit: Int): ProviderOutcome = when (lastDigit) { in 0..6 -> ProviderOutcome.MATCH; 7 -> ProviderOutcome.MISMATCH; 8 -> ProviderOutcome.NOT_FOUND; else -> ProviderOutcome.TIMEOUT }

    private fun providers(country: String): Pair<String, String> = when (country) {
        "MM" -> "Myanmar NRC registry (mock)" to "Myanmar Credit Bureau (mock)"
        "KH" -> "Cambodia GDI registry (mock)" to "Credit Bureau Cambodia (mock)"
        else -> "Dukcapil (mock)" to "PEFINDO (mock)"
    }

    private fun ktpAnalysis(nik: String, persona: Persona, scenario: Scenario): DocumentAnalysis {
        val validation = NikValidator.validate(nik, persona.dob.toString(), persona.gender)
        val issues = if (scenario == Scenario.SCREEN_PHOTO) listOf(QualityIssue(QualityIssueType.SCREEN_PHOTO, Severity.MEDIUM, "Moire pattern and a screen bezel are visible around the card")) else emptyList()
        fun f(name: String, value: String?, conf: Double, status: FieldStatus = FieldStatus.EXTRACTED, note: String? = null) = ExtractedField(name, value, conf, status, note)
        val fields = listOf(
            f("nik", nik, if (validation.valid) 0.97 else 0.93, if (validation.valid) FieldStatus.EXTRACTED else FieldStatus.NEEDS_OFFICER_REVIEW, if (validation.valid) null else "digits legible but the encoded date of birth does not match the card"),
            f("name", persona.name, 0.96), f("place_of_birth", "Jakarta", 0.9), f("date_of_birth", persona.dob.toString(), 0.95), f("gender", persona.gender, 0.98),
            f("address", persona.address.substringBefore(","), 0.91), f("rt_rw", "003/005", 0.88), f("village", persona.address.substringAfter(", ").substringBefore(","), 0.86),
            f("district", "Gambir", 0.86), f("religion", "ISLAM", 0.9), f("marital_status", "KAWIN", 0.9), f("occupation", "KARYAWAN SWASTA", 0.88), f("nationality", "WNI", 0.99), f("valid_until", "SEUMUR HIDUP", 0.97),
            f("province", NikValidator.PROVINCES[nik.substring(0, 2)]?.uppercase() ?: "DKI JAKARTA", 0.95), f("city", if (nik.startsWith("31")) "JAKARTA PUSAT" else "KOTA ${persona.address.substringAfterLast(", ").substringBefore(" ").uppercase()}", 0.9),
        )
        val tamper = if (scenario == Scenario.FORGERY) listOf(
            TamperSignal(TamperSignalType.PHOTO_REPLACED, Severity.HIGH, "The portrait has a sharp rectangular edge and a lighter background than the card; it looks pasted over the original photo"),
            TamperSignal(TamperSignalType.FONT_MISMATCH, Severity.MEDIUM, "The NIK digits are thinner and slightly larger than the other printed fields"),
        ) else emptyList()
        val reasons = (if (validation.valid) emptyList() else listOf("NIK validation failed: ${validation.issues.joinToString("; ")}")) +
            tamper.filter { it.severity != Severity.LOW }.map { "Possible forgery ${it.type} (${it.severity}): ${it.detail}" }
        return DocumentAnalysis(
            requestedType = DocumentType.KTP, documentType = DetectedDocumentType.KTP, documentTypeConfidence = 0.98, typeMatchesRequest = true, qualityIssues = issues, usable = true,
            tamperSignals = tamper, fields = fields, nik = validation, needsOfficerReview = reasons.isNotEmpty(), reviewReasons = reasons,
            modelNotes = when (scenario) {
                Scenario.SCREEN_PHOTO -> "The card appears to be displayed on a phone screen rather than photographed directly."
                Scenario.FORGERY -> "The card layout is otherwise consistent with a genuine e-KTP, but the portrait and the NIK line look edited."
                else -> null
            },
            model = MODEL, rawJson = "<demo seed: no model call>", usage = ModelUsage(2_210, 1_980, 540, 4_300, "end_turn"),
        )
    }

    private fun selfieAnalysis(nik: String, scenario: Scenario, random: Random): DocumentAnalysis {
        val held = if (scenario == Scenario.SELFIE_MISMATCH) nik.dropLast(6) + "%06d".format(random.nextInt(100_000, 999_999)) else nik
        return DocumentAnalysis(
            requestedType = DocumentType.SELFIE_KTP, documentType = DetectedDocumentType.SELFIE_WITH_KTP, documentTypeConfidence = 0.95, typeMatchesRequest = true, qualityIssues = emptyList(), usable = true,
            fields = listOf(ExtractedField("face_count", "1", 0.99, FieldStatus.EXTRACTED), ExtractedField("face_clearly_visible", "true", 0.97, FieldStatus.EXTRACTED), ExtractedField("ktp_held_in_frame", "true", 0.96, FieldStatus.EXTRACTED), ExtractedField("nik_on_held_ktp", held, 0.9, FieldStatus.EXTRACTED), ExtractedField("face_matches_ktp_photo", if (scenario == Scenario.FORGERY) "no" else "yes", 0.88, FieldStatus.EXTRACTED, if (scenario == Scenario.FORGERY) "jaw line and eye spacing differ from the card portrait" else null)),
            nik = null, needsOfficerReview = false, reviewReasons = emptyList(), modelNotes = null, model = MODEL, rawJson = "<demo seed: no model call>", usage = ModelUsage(1_900, 1_980, 210, 3_600, "end_turn"),
        )
    }

    private fun summaryText(number: String, persona: Persona, nik: String, ktp: DocumentAnalysis?, officerReview: Boolean): String = buildString {
        appendLine("APPLICATION $number - DOCUMENT INTAKE SUMMARY")
        appendLine("Applicant: ${persona.name}, born ${persona.dob}, ${persona.gender.lowercase()}; NIK ***${nik.takeLast(4)}")
        appendLine()
        appendLine("Documents received: KTP (clear, all fields legible), selfie holding the KTP (one face, card in frame).")
        appendLine("Extracted data: name ${persona.name} (0.96), date of birth ${persona.dob} (0.95), address ${persona.address.substringBefore(",")} (0.91).")
        if (ktp?.nik?.valid == false) appendLine("NIK validation: FAILED - ${ktp.nik.issues.joinToString("; ")}. The NIK is marked NEEDS OFFICER REVIEW and was not used for verification.")
        else appendLine("NIK validation: passed (${ktp?.nik?.provinceName ?: "region recognised"}, encoded date of birth and gender match the card).")
        appendLine()
        appendLine(if (officerReview) "ITEMS FOR OFFICER REVIEW: compare the NIK digits on the image with the card's printed date of birth before verification." else "ITEMS FOR OFFICER REVIEW: none.")
        append("NEXT STEP: ${if (officerReview) "officer to verify the NIK, then run identity and credit verification" else "proceed with identity and credit verification"}")
    }

    private fun storeDocument(app: DealerApplication, type: DocumentType, persona: Persona, idNumber: String, at: Instant, passed: Boolean, reason: String? = null, analysis: DocumentAnalysis?): DocumentRecord {
        val dir = Path.of(props.documentsDir).resolve(app.applicationNumber)
        Files.createDirectories(dir)
        val file = dir.resolve("${type.name}-demo-${at.toEpochMilli()}.png")
        runCatching { ImageIO.write(DemoImages.render(type, persona.name, idNumber, blurry = !passed), "png", file.toFile()) }
            .onFailure { log.warn("Demo image could not be written: {}", it.message) }
        return documents.save(
            DocumentRecord(
                applicationId = app.id!!, type = type, mediaId = "demo-${app.applicationNumber}-${type.name}-${at.toEpochMilli()}", filePath = file.toAbsolutePath().toString(), mimeType = "image/png",
                caption = null, checkPassed = passed, checkReason = reason, detectedType = analysis?.documentType?.name, needsOfficerReview = analysis?.needsOfficerReview,
                analysisJson = analysis?.let(codec::toJson), createdAt = at,
            ),
        )
    }

    private fun step(app: DealerApplication, kind: TraceKind, status: TraceStatus, actor: String, title: String, detail: String?, at: Instant, durationMs: Long? = null, data: Any? = null, round: Int = 0) {
        val json = data?.let { runCatching { codec.toJsonAny(it) }.getOrNull() }
        steps.save(AgentStep(applicationId = app.id!!, kind = kind, status = status, actor = actor, title = title.take(200), detail = detail?.take(1000), dataJson = json?.take(8000), durationMs = durationMs, round = round, createdAt = at))
    }

    private fun event(app: DealerApplication, kind: ReviewEventKind, actor: String, detail: String?, at: Instant) {
        events.save(ReviewEvent(applicationId = app.id!!, kind = kind, actor = actor, detail = detail?.take(1000), createdAt = at))
    }

    companion object {
        const val DEFAULT_COUNT = 40
        const val MAX_COUNT = 200
        const val DEFAULT_SEED = 20261020L
        const val TREND_DAYS = 52.0
        val JOURNEY_MARGIN: Duration = Duration.ofHours(4)
        const val DEMO_DEALER_PREFIX = "DLR-DEMO-"
        /** Demo dealer roster: code suffix (country first), name, tier. Two or three per country so the dealer tab can compare tiers. */
        val DEMO_DEALERS = listOf(
            Triple("ID-1", "PT Astra Mobil Jakarta", DealerTier.EXCELLENT), Triple("ID-2", "Sinar Motor Surabaya", DealerTier.EXCELLENT),
            Triple("ID-3", "Mandiri Oto Bandung", DealerTier.STANDARD), Triple("ID-4", "Jaya Motor Medan", DealerTier.STANDARD),
            Triple("MM-1", "Yangon Auto Center", DealerTier.EXCELLENT), Triple("MM-2", "Mandalay Motors", DealerTier.STANDARD), Triple("MM-3", "Golden Land Cars", DealerTier.STANDARD),
            Triple("KH-1", "Phnom Penh Auto", DealerTier.EXCELLENT), Triple("KH-2", "Angkor Motor", DealerTier.STANDARD), Triple("KH-3", "Mekong Cars", DealerTier.STANDARD),
        )
        /** Scenarios that end in a fraud signal or a failed lookup; excellent dealers draw them less often. */
        private val RISKY = setOf(Scenario.IDENTITY_MISMATCH, Scenario.NOT_FOUND, Scenario.SCREEN_PHOTO, Scenario.INVALID_NIK, Scenario.SELFIE_MISMATCH, Scenario.FORGERY)
        /** Cross-application scenarios that build a fraud ring with the last clean customer. */
        private val SHARED = setOf(Scenario.SHARED_ID, Scenario.SHARED_PHONE)
        const val MODEL = "claude-opus-5-5"
        /** Scenario and age in days of the first applications of every seed run. */
        private val SHOWCASE = listOf(
            Scenario.CLEAN to 0.08, Scenario.SCREEN_PHOTO to 0.4, Scenario.INVALID_NIK to 0.6, Scenario.IDENTITY_MISMATCH to 0.9, Scenario.MEDIUM to 1.2,
            Scenario.SHARED_ID to 1.1, Scenario.FORGERY to 0.3, Scenario.SHARED_PHONE to 0.7,
            Scenario.SELFIE_MISMATCH to 1.6, Scenario.NOT_FOUND to 2.5, Scenario.IN_PROGRESS to 0.05, Scenario.TIMEOUT to 3.5, Scenario.CLEAN to 5.0,
        )
        val PROVINCES = listOf("31", "32", "33", "35", "51", "12", "73")
        val OFFICERS = listOf("Officer Kim", "Officer Lee", "Officer Sari")
        val APPROVE_NOTES = listOf("All checks clear", "Documents and registry match; approved", "Approved - good credit history", "Verified identity, score above threshold")
        val REJECT_NOTES = listOf("Identity could not be confirmed with the registry", "Document appears to be a screen capture", "Bureau record does not match the applicant", "ID number fails validation; customer did not resend")
        val OFFICER_NOTES = listOf("Called the dealer to confirm the customer's address", "Second look at the selfie: card number matches", "Waiting for dealer feedback on income proof")
        val NAMES = mapOf(
            "ID" to listOf("Budi Santoso", "Siti Rahayu", "Agus Wijaya", "Dewi Lestari", "Rizky Pratama", "Nur Aini", "Andi Saputra", "Rina Kurniawati", "Hendra Gunawan", "Putri Maharani", "Eko Prasetyo", "Fitri Handayani"),
            "MM" to listOf("Aung Ko Ko", "Su Su Hlaing", "Zaw Min Htun", "Khin Mar Lar", "Kyaw Zin Oo", "Thandar Win", "Htet Naing", "Moe Moe Aye"),
            "KH" to listOf("Sok Dara", "Chan Sreyneang", "Vann Rithy", "Keo Chanthou", "Pich Samnang", "Ly Sophea", "Heng Vuthy", "Nhem Kunthea"),
        )
        val ADDRESSES = mapOf(
            "ID" to listOf("Jl. Merdeka No. 10, Gambir, Jakarta Pusat 10110", "Jl. Sudirman Kav. 25, Setiabudi, Jakarta Selatan 12920", "Jl. Diponegoro No. 7, Citarum, Bandung 40115", "Jl. Pemuda No. 118, Semarang Tengah, Semarang 50132", "Jl. Raya Darmo No. 60, Wonokromo, Surabaya 60241", "Jl. Gatot Subroto No. 3, Denpasar 80111"),
            "MM" to listOf("No. 45, Pyay Road, Kamayut Township, Yangon", "No. 12, 26th Street, Chanayethazan, Mandalay", "No. 8, Bogyoke Street, Taunggyi"),
            "KH" to listOf("#23, Street 271, Toul Tompoung, Phnom Penh", "#7, Sivatha Road, Svay Dangkum, Siem Reap", "#110, Mondul 3, Battambang"),
        )
    }
}

/** Placeholder images so the review page has something to show for seeded applications. */
object DemoImages {
    fun render(type: DocumentType, name: String, idNumber: String, blurry: Boolean): BufferedImage {
        val (w, h) = if (type == DocumentType.KTP) 640 to 400 else 480 to 640
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        if (type == DocumentType.KTP) {
            g.color = Color(0x3a, 0x7b, 0xd5); g.fillRect(0, 0, w, h)
            g.color = Color(0x5a, 0x94, 0xe0); g.fillRoundRect(24, 24, w - 48, h - 48, 24, 24)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 22); g.drawString("PROVINSI DKI JAKARTA", 48, 64); g.drawString("JAKARTA PUSAT", 48, 92)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20); g.drawString("NIK  : $idNumber", 48, 140)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
            listOf("Nama           : ${name.uppercase()}", "Tempat/Tgl Lahir : JAKARTA", "Alamat         : JL. MERDEKA NO. 10", "Kewarganegaraan : WNI", "Berlaku Hingga : SEUMUR HIDUP").forEachIndexed { i, s -> g.drawString(s, 48, 176 + i * 30) }
            g.color = Color(0xe8, 0xee, 0xf8); g.fillRect(w - 190, 130, 130, 160)
            g.color = Color(0x9a, 0xb0, 0xcc); g.fillOval(w - 160, 150, 70, 70); g.fillRoundRect(w - 180, 225, 110, 70, 40, 40)
            g.color = Color(0xff, 0xdd, 0x55); g.font = Font(Font.SANS_SERIF, Font.BOLD, 26); g.drawString("DEMO SAMPLE - NOT A REAL ID", 48, h - 44)
        } else {
            g.color = Color(0xd9, 0xcf, 0xc2); g.fillRect(0, 0, w, h)
            g.color = Color(0xf1, 0xd3, 0xb8); g.fillOval(140, 110, 200, 240)
            g.color = Color(0x4a, 0x3a, 0x30); g.fillArc(130, 90, 220, 140, 0, 180)
            g.color = Color(0x3a, 0x7b, 0xd5); g.fillRoundRect(60, 380, 220, 140, 16, 16)
            g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 14); g.drawString("NIK $idNumber", 72, 420); g.drawString(name.uppercase(), 72, 445)
            g.color = Color(0x60, 0x50, 0x45); g.font = Font(Font.SANS_SERIF, Font.BOLD, 24); g.drawString("DEMO SELFIE - NOT A REAL PERSON", 40, h - 36)
        }
        if (blurry) {
            g.color = Color(255, 255, 255, 150); g.fillRect(0, 0, w, h)
            g.color = Color(0x30, 0x30, 0x30); g.font = Font(Font.SANS_SERIF, Font.BOLD, 34); g.drawString("(blurry)", w / 2 - 60, h / 2)
        }
        g.dispose()
        return img
    }
}

/** Seeds demo applications at startup when `demo.seed-applications=true` (DEMO_SEED_APPLICATIONS) and none exist yet. */
@Component
@Order(100)
class DemoApplicationSeederRunner(private val props: DemoProperties, private val seeder: DemoDataSeeder) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!props.seedApplications) return
        val existing = seeder.count()
        if (existing > 0) {
            if (seeder.rosterComplete()) {
                log.info("Demo applications already present ({}); not seeding again. POST /admin/demo/seed with reset=true to regenerate.", existing)
                return
            }
            log.info("Demo applications present ({}) but from an older seed layout; regenerating with the current dealer roster.", existing)
        }
        val result = seeder.seed(props.seedCount, reset = existing > 0)
        log.info("Demo applications seeded at startup | created={} | total={}", result.created, result.total)
    }
}
