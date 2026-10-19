package com.nextasean.wawebhook.dealer

import com.nextasean.wawebhook.config.DemoProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** Upserts the demo dealers from `demo.dealers` (DEMO_DEALER_n_PHONE / _NAME / _COUNTRY / _TIER in wa.env). */
@Component
class DemoDealerSeeder(
    private val props: DemoProperties,
    private val dealers: DealerRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        props.dealers.forEachIndexed { index, demo ->
            val phone = normalizePhone(demo.phone)
            if (demo.code.isBlank() || phone.isBlank()) {
                log.warn(
                    "Demo dealer #{} ({}) skipped: no phone configured. Set DEMO_DEALER_{}_PHONE in wa.env.",
                    index + 1, demo.code.ifBlank { "no code" }, index + 1,
                )
                return@forEachIndexed
            }
            val name = demo.name.ifBlank { demo.code }
            val country = Countries.normalize(demo.country)
            val tier = DealerTier.parse(demo.tier)
            val existing = dealers.findByDealerCode(demo.code)
            when {
                existing == null -> {
                    dealers.save(Dealer(phone = phone, name = name, dealerCode = demo.code, country = country, tier = tier))
                    log.info("Seeded demo dealer | code={} | name={} | phone={} | country={} | tier={}", demo.code, name, phone, country, tier)
                }
                existing.phone != phone || existing.name != name || existing.country != country || existing.tier != tier -> {
                    dealers.save(existing.copy(phone = phone, name = name, active = true, country = country, tier = tier))
                    log.info("Updated demo dealer | code={} | name={} | phone={} | country={} | tier={}", demo.code, name, phone, country, tier)
                }
                else -> log.info("Demo dealer present | code={} | phone={} | country={} | tier={}", demo.code, phone, country, tier)
            }
        }
    }
}
