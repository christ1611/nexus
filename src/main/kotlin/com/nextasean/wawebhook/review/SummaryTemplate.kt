package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.documents.analysis.FieldStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-text one-page summary built from the facts alone; the model writes a better one when it is available. */
object SummaryTemplate {

    private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Jakarta"))

    fun render(ctx: ReviewContext, officerReview: Boolean): String = buildString {
        val app = ctx.application
        appendLine("APPLICATION ${app.applicationNumber} - DOCUMENT INTAKE SUMMARY")
        appendLine("Customer: ${app.customerName ?: "unknown"} (+${app.customerPhone ?: "?"}) | Dealer id ${app.dealerId} | Created ${stamp.format(app.createdAt)}")
        appendLine("Decision: ${if (officerReview) "OFFICER REVIEW REQUIRED" else "COMPLETE - no manual review items"} | Document rounds: ${ctx.round + 1}")
        appendLine()
        appendLine("DOCUMENTS")
        for (doc in ctx.documents) {
            val a = doc.analysis
            val line = when {
                !doc.received -> "not received"
                a == null -> "received (no automated analysis)"
                else -> "received | seen as ${a.documentType.label} (${pct(a.documentTypeConfidence)}) | " +
                    (if (a.qualityIssues.isEmpty()) "no quality issues" else a.qualityIssues.joinToString("; ") { "${it.type} ${it.severity}: ${it.detail}" })
            }
            appendLine("- ${doc.type.label}: $line")
        }
        val ktp = ctx.documents.firstOrNull { it.type.name == "KTP" }?.analysis
        if (ktp != null) {
            appendLine()
            appendLine("EXTRACTED DATA (KTP)")
            for (f in ktp.fields) {
                val value = when (f.status) {
                    FieldStatus.EXTRACTED -> f.value
                    FieldStatus.NEEDS_OFFICER_REVIEW -> "${f.value} [NEEDS OFFICER REVIEW]"
                    FieldStatus.NOT_VISIBLE -> "[not visible]"
                }
                appendLine("- ${f.name.replace('_', ' ')}: $value (${pct(f.confidence)})${f.note?.let { " - $it" } ?: ""}")
            }
            ktp.nik?.let { n ->
                appendLine()
                appendLine("NIK VALIDATION: ${if (n.valid) "PASSED" else "FAILED"}")
                appendLine("- Region: ${n.provinceName ?: "unknown province ${n.provinceCode}"} / regency ${n.regencyCode} / district ${n.districtCode}")
                appendLine("- Encoded date of birth: ${n.dobFromNik ?: "invalid"} | gender: ${n.genderFromNik ?: "n/a"} | card: ${n.statedDob ?: "n/a"} / ${n.statedGender ?: "n/a"}")
                n.issues.forEach { appendLine("- Issue: $it") }
            }
        }
        val reasons = ctx.documents.flatMap { d -> d.analysis?.reviewReasons.orEmpty().map { "${d.type.label}: $it" } } +
            ctx.documents.filter { it.record?.needsOfficerReview == true && it.analysis == null }.map { "${it.type.label}: automated analysis was unavailable" }
        appendLine()
        appendLine("ITEMS FOR OFFICER REVIEW")
        if (reasons.isEmpty()) appendLine("- none") else reasons.forEach { appendLine("- $it") }
        appendLine()
        append("NEXT STEP: ${if (officerReview) "officer to verify the flagged items against the images before verification" else "proceed with identity and credit verification"}")
    }

    private fun pct(v: Double) = "${(v * 100).toInt()}%"
}
