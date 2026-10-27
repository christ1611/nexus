package com.nextasean.wawebhook.risk

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Fraud network: applications tied together by the same ID number, the same phone number or the same name and date
 * of birth, grouped into clusters ("rings"), with the dealers they came through. The risk rules raise a flag on the
 * later application of such a pair; this view draws the whole ring so an officer sees the pattern, not one case.
 * Everything is recomputed on each call from the APPLICATIONS rows; identifiers are masked to their last four digits.
 */
@Service
class FraudNetworkService(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
) {
    data class Node(
        val id: String,
        /** `application` or `dealer`. */
        val type: String,
        val label: String,
        val cluster: Int? = null,
        val number: String? = null,
        val name: String? = null,
        val country: String? = null,
        val status: String? = null,
        val grade: String? = null,
        val score: Int? = null,
        val highRisk: Boolean = false,
        val dealerCode: String? = null,
        val idMasked: String? = null,
        val phoneMasked: String? = null,
        val createdAt: Instant? = null,
        val tier: String? = null,
        val applications: Int? = null,
    )

    data class Link(val source: String, val target: String, val kind: String, val label: String, val labelKo: String, val detail: String? = null)

    data class Cluster(
        val id: Int,
        /** HIGH when a shared ID number or phone ties different people together, MEDIUM for duplicates and name matches only. */
        val severity: String,
        val applications: List<String>,
        val dealers: List<String>,
        val kinds: List<String>,
        val sharedIds: Int,
        val sharedPhones: Int,
        val duplicates: Int,
        val sameNames: Int,
        val highRisk: Int,
        /** True when every application of the ring came through one dealer. */
        val singleDealer: Boolean,
        val summary: String,
        val summaryKo: String,
        val latestAt: Instant?,
    )

    data class Network(val generatedAt: Instant, val scanned: Int, val linkedApplications: Int, val nodes: List<Node>, val links: List<Link>, val clusters: List<Cluster>) {
        fun clusterOf(applicationNumber: String): Cluster? = clusters.firstOrNull { applicationNumber in it.applications }
        /** The ties (not dealer links) of one application, as (kind, other application node, detail). */
        fun tiesOf(applicationNumber: String): List<Triple<String, Node, String?>> {
            val me = nodes.firstOrNull { it.type == "application" && it.number == applicationNumber } ?: return emptyList()
            val byId = nodes.associateBy { it.id }
            return links.filter { it.kind != DEALER && (it.source == me.id || it.target == me.id) }
                .mapNotNull { l -> byId[if (l.source == me.id) l.target else l.source]?.let { Triple(l.kind, it, l.detail) } }
        }
    }

    fun network(): Network {
        val apps = applications.findAllByOrderByCreatedAtDesc()
        val dealerById = dealers.findAll().associateBy { it.id }
        val links = mutableListOf<Link>()
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long { var r = x; while (parent.getOrDefault(r, r) != r) r = parent.getValue(r); parent[x] = r; return r }
        fun union(a: Long, b: Long) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        fun tie(a: DealerApplication, b: DealerApplication, kind: String, label: String, labelKo: String, detail: String) {
            links += Link(a.id.toString(), b.id.toString(), kind, label, labelKo, detail)
            union(a.id!!, b.id!!)
        }

        // Same ID number: a different person is identity misuse (SAME_ID); the same person is a duplicate application.
        apps.filter { !nik(it).isNullOrBlank() }.groupBy { nik(it)!! }.values.filter { it.size > 1 }.forEach { group ->
            pairs(group).forEach { (a, b) ->
                if (samePerson(a, b)) tie(a, b, DUPLICATE, "Same customer, two applications", "동일 고객 중복 신청", "ID ***${nik(a)!!.takeLast(4)}")
                else tie(a, b, SAME_ID, "Same ID number, different person", "같은 신분증 번호, 다른 사람", "ID ***${nik(a)!!.takeLast(4)}")
            }
        }
        // Same phone number with different ID numbers: one phone submitting several identities.
        apps.filter { !phone(it).isNullOrBlank() }.groupBy { phone(it)!! }.values.filter { it.size > 1 }.forEach { group ->
            pairs(group).filter { (a, b) -> val na = nik(a); val nb = nik(b); !na.isNullOrBlank() && !nb.isNullOrBlank() && na != nb }
                .forEach { (a, b) -> tie(a, b, SAME_PHONE, "Same phone, different ID numbers", "같은 전화번호, 다른 신분증 번호", "phone ***${phone(a)!!.takeLast(4)}") }
        }
        // Same name and date of birth under different ID numbers: the weakest signal, shown but ranked below the others.
        apps.filter { !nik(it).isNullOrBlank() && !it.extractedName.isNullOrBlank() && !it.extractedDob.isNullOrBlank() }
            .groupBy { it.extractedName!!.trim().uppercase() + "|" + it.extractedDob }.values.filter { it.size > 1 }.forEach { group ->
                pairs(group).filter { (a, b) -> nik(a) != nik(b) && phone(a) != phone(b) }
                    .forEach { (a, b) -> tie(a, b, SAME_NAME, "Same name and date of birth, different ID numbers", "같은 이름·생년월일, 다른 신분증 번호", "name ${a.extractedName!!.trim().take(20)}") }
            }

        val groups = apps.filter { parent.containsKey(it.id!!) }.groupBy { find(it.id!!) }.values
            .map { g -> g.sortedBy { it.createdAt } }
            .sortedWith(compareByDescending<List<DealerApplication>> { g -> g.size }.thenByDescending { g -> g.maxOf { it.createdAt } })
        val clusterOf = HashMap<Long, Int>()
        val clusters = groups.mapIndexed { i, g ->
            val id = i + 1
            g.forEach { clusterOf[it.id!!] = id }
            val ids = g.map { it.id.toString() }.toSet()
            val cl = links.filter { it.source in ids && it.target in ids }
            val kinds = cl.map { it.kind }.distinct()
            val sharedIds = cl.count { it.kind == SAME_ID }
            val sharedPhones = cl.count { it.kind == SAME_PHONE }
            val duplicates = cl.count { it.kind == DUPLICATE }
            val sameNames = cl.count { it.kind == SAME_NAME }
            val dealerCodes = g.mapNotNull { dealerById[it.dealerId]?.dealerCode }.distinct()
            val highRisk = g.count { RiskGrade.parse(it.riskGrade)?.highRisk == true }
            val severity = if (sharedIds > 0 || sharedPhones > 0) "HIGH" else "MEDIUM"
            val partsEn = listOfNotNull(
                "${g.size} applications",
                sharedIds.takeIf { it > 0 }?.let { "$it shared ID number${if (it > 1) "s" else ""}" },
                sharedPhones.takeIf { it > 0 }?.let { "$it shared phone${if (it > 1) "s" else ""}" },
                duplicates.takeIf { it > 0 }?.let { "$it duplicate${if (it > 1) "s" else ""}" },
                sameNames.takeIf { it > 0 }?.let { "$it name match${if (it > 1) "es" else ""}" },
                if (dealerCodes.size == 1) "all via dealer ${dealerCodes.first()}" else "${dealerCodes.size} dealers",
                highRisk.takeIf { it > 0 }?.let { "$it high-risk" },
            )
            val partsKo = listOfNotNull(
                "신청 ${g.size}건",
                sharedIds.takeIf { it > 0 }?.let { "신분증 번호 공유 $it" },
                sharedPhones.takeIf { it > 0 }?.let { "전화번호 공유 $it" },
                duplicates.takeIf { it > 0 }?.let { "중복 신청 $it" },
                sameNames.takeIf { it > 0 }?.let { "이름·생년월일 일치 $it" },
                if (dealerCodes.size == 1) "모두 딜러 ${dealerCodes.first()} 경유" else "딜러 ${dealerCodes.size}곳",
                highRisk.takeIf { it > 0 }?.let { "고위험 $it" },
            )
            Cluster(
                id, severity, g.map { it.applicationNumber }, dealerCodes, kinds, sharedIds, sharedPhones, duplicates, sameNames, highRisk,
                dealerCodes.size == 1, partsEn.joinToString(" · "), partsKo.joinToString(" · "), g.maxOfOrNull { it.createdAt },
            )
        }

        val linked = groups.flatten()
        val nodes = mutableListOf<Node>()
        linked.forEach { a ->
            val d = dealerById[a.dealerId]
            nodes += Node(
                id = a.id.toString(), type = "application", label = a.applicationNumber, cluster = clusterOf[a.id!!], number = a.applicationNumber,
                name = a.extractedName ?: a.customerName, country = a.country, status = a.status.name, grade = a.riskGrade, score = a.riskScore,
                highRisk = RiskGrade.parse(a.riskGrade)?.highRisk == true, dealerCode = d?.dealerCode,
                idMasked = nik(a)?.let { "***" + it.takeLast(4) }, phoneMasked = phone(a)?.let { "***" + it.takeLast(4) }, createdAt = a.createdAt,
            )
        }
        linked.mapNotNull { dealerById[it.dealerId] }.distinctBy { it.id }.forEach { d: Dealer ->
            val count = linked.count { it.dealerId == d.id }
            nodes += Node(id = "dealer-${d.id}", type = "dealer", label = d.dealerCode, name = d.name, country = d.country, tier = d.tier.name, dealerCode = d.dealerCode, applications = count)
            linked.filter { it.dealerId == d.id }.forEach { a -> links += Link(a.id.toString(), "dealer-${d.id}", DEALER, "Filed by dealer", "접수 딜러") }
        }
        return Network(Instant.now(), apps.size, linked.size, nodes, links, clusters)
    }

    private fun nik(a: DealerApplication): String? = a.extractedNik?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
    private fun phone(a: DealerApplication): String? = a.customerPhone?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }

    /** Mirrors the risk rules: the same phone and (where known) the same name means one customer. */
    private fun samePerson(a: DealerApplication, b: DealerApplication): Boolean {
        val nameA = (a.extractedName ?: a.customerName)?.trim()?.uppercase()
        val nameB = (b.extractedName ?: b.customerName)?.trim()?.uppercase()
        val samePhone = phone(a) != null && phone(a) == phone(b)
        val sameName = nameA.isNullOrBlank() || nameB.isNullOrBlank() || nameA == nameB
        return samePhone && sameName
    }

    private fun <T> pairs(list: List<T>): List<Pair<T, T>> = list.indices.flatMap { i -> (i + 1 until list.size).map { j -> list[i] to list[j] } }

    companion object {
        const val SAME_ID = "SAME_ID"
        const val SAME_PHONE = "SAME_PHONE"
        const val DUPLICATE = "DUPLICATE"
        const val SAME_NAME = "SAME_NAME"
        const val DEALER = "DEALER"
        val KIND_LABELS = mapOf(
            SAME_ID to ("same ID number" to "같은 신분증 번호"), SAME_PHONE to ("same phone" to "같은 전화번호"),
            DUPLICATE to ("duplicate application" to "중복 신청"), SAME_NAME to ("same name & DOB" to "같은 이름·생년월일"),
        )
    }
}
