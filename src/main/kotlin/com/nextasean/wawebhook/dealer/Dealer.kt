package com.nextasean.wawebhook.dealer

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

/** Dealer quality tier; EXCELLENT dealers earn their customers the "referred by an excellent dealer" badge. */
enum class DealerTier(val label: String, val labelKo: String) {
    STANDARD("Standard dealer", "일반 딜러"),
    EXCELLENT("Excellent dealer", "우수 딜러");

    companion object {
        fun parse(raw: String?): DealerTier = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD
    }
}

@Table("DEALERS")
data class Dealer(
    @Id val id: Long? = null,
    /** WhatsApp ID: country code + number, digits only (e.g. 6281234567890). */
    val phone: String,
    val name: String,
    val dealerCode: String,
    val active: Boolean = true,
    /** ISO 3166-1 alpha-2 of the market the dealer sells in; see [Countries]. */
    val country: String = Countries.DEFAULT,
    val tier: DealerTier = DealerTier.STANDARD,
    val createdAt: Instant = Instant.now(),
)

interface DealerRepository : CrudRepository<Dealer, Long> {
    fun findByPhone(phone: String): Dealer?
    fun findByDealerCode(dealerCode: String): Dealer?
}

/** Normalises a phone number to WhatsApp's digits-only form. */
fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }

/** The markets the product serves. Statistics are always reported for all of them, even when a market has no data yet. */
object Countries {
    const val DEFAULT = "ID"

    data class Country(val code: String, val name: String, val nameKo: String, val idDocument: String)

    val ALL: List<Country> = listOf(
        Country("ID", "Indonesia", "인도네시아", "KTP"),
        Country("MM", "Myanmar", "미얀마", "NRC"),
        Country("KH", "Cambodia", "캄보디아", "Khmer ID"),
    )

    fun byCode(code: String?): Country? = ALL.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

    /** Upper-cased code when it is a known market, otherwise [DEFAULT]. */
    fun normalize(code: String?): String = byCode(code)?.code ?: DEFAULT
}
