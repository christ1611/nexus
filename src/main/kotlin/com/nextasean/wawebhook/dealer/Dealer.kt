package com.nextasean.wawebhook.dealer

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

@Table("DEALERS")
data class Dealer(
    @Id val id: Long? = null,
    /** WhatsApp ID: country code + number, digits only (e.g. 6281234567890). */
    val phone: String,
    val name: String,
    val dealerCode: String,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

interface DealerRepository : CrudRepository<Dealer, Long> {
    fun findByPhone(phone: String): Dealer?
    fun findByDealerCode(dealerCode: String): Dealer?
}

/** Normalises a phone number to WhatsApp's digits-only form. */
fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }
