package com.nextasean.wawebhook.customer

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

enum class CustomerSessionState(val active: Boolean) {
    AWAITING_CONSENT(true),
    AWAITING_KTP(true),
    AWAITING_SELFIE(true),
    VERIFYING(true),
    COMPLETED(false),
    DECLINED(false),
    CANCELLED(false),
}

/** One customer journey for one application; separate from the dealer conversation. */
@Table("CUSTOMER_SESSIONS")
data class CustomerSession(
    @Id val id: Long? = null,
    val phone: String,
    val customerName: String? = null,
    val applicationId: Long,
    val state: CustomerSessionState = CustomerSessionState.AWAITING_CONSENT,
    val consentAt: Instant? = null,
    val declinedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

interface CustomerSessionRepository : CrudRepository<CustomerSession, Long> {
    fun findByPhoneOrderByCreatedAtDesc(phone: String): List<CustomerSession>
    fun findByApplicationIdOrderByCreatedAtDesc(applicationId: Long): List<CustomerSession>
}

fun CustomerSessionRepository.findActiveByPhone(phone: String): CustomerSession? =
    findByPhoneOrderByCreatedAtDesc(phone).firstOrNull { it.state.active }
