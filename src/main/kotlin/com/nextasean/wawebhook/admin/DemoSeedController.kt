package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.demo.DemoDataSeeder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Demo data for the dashboard and the review page. Never sends WhatsApp messages. */
@RestController
@RequestMapping("/admin/demo")
class DemoSeedController(private val seeder: DemoDataSeeder) {

    data class SeedRequest(val count: Int? = null, val reset: Boolean = false, val seed: Long? = null)

    @GetMapping("/seed")
    fun status(): Map<String, Any> = mapOf("demoApplications" to seeder.count())

    /** Creates demo applications (default 40, max 200); `reset=true` removes the previous demo rows first. */
    @PostMapping("/seed")
    fun seed(@RequestBody(required = false) body: SeedRequest?): DemoDataSeeder.SeedResult =
        seeder.seed(body?.count ?: DemoDataSeeder.DEFAULT_COUNT, body?.reset ?: false, body?.seed ?: DemoDataSeeder.DEFAULT_SEED)

    /** Removes every demo application together with its sessions, documents, events and agent steps. */
    @DeleteMapping("/seed")
    fun clear(): Map<String, Int> = mapOf("removed" to seeder.clear())
}
