package com.nextasean.wawebhook.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.config.OllamaProperties
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.sun.net.httpserver.HttpServer
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake Ollama server stands in for the real one: the request shape, the reply, and the fallback to rules. */
@SpringBootTest(properties = ["app.copilot-engine=ollama"])
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:ollamatest;DB_CLOSE_DELAY=-1"])
class OllamaCopilotTest @Autowired constructor(private val mockMvc: MockMvc) {

    companion object {
        private val received = mutableListOf<Map<*, *>>()
        @Volatile private var healthy = true
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/chat") { ex ->
                received += ObjectMapper().readValue(ex.requestBody.readAllBytes(), Map::class.java)
                val (code, reply) = if (healthy) 200 to """{"model":"fake:1b","message":{"role":"assistant","content":" 답변입니다 "},"prompt_eval_count":120,"eval_count":7}"""
                else 500 to """{"error":"model failed"}"""
                val bytes = reply.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val url get() = "http://127.0.0.1:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun ollamaUrl(registry: DynamicPropertyRegistry) = registry.add("ollama.base-url") { url }
    }

    private fun client(baseUrl: String = url, engine: String = "ollama") =
        OllamaCopilot(AppProperties(copilotEngine = engine), OllamaProperties(baseUrl = baseUrl, model = "fake:1b", numCtx = 4096), RestClient.builder())

    @AfterTest
    fun reset() { healthy = true; received.clear() }

    @Test
    fun `the conversation, file and options reach the server and the reply is parsed`() {
        val reply = client().chat("SYSTEM RULES", "# Case file", listOf(ReviewCopilotService.Turn("user", "earlier"), ReviewCopilotService.Turn("assistant", "answered")), "why?", "Rina")
        assertNotNull(reply)
        assertEquals("답변입니다", reply.text)
        assertEquals(120L, reply.inputTokens)
        val body = received.single()
        assertEquals("fake:1b", body["model"])
        assertEquals(false, body["stream"])
        assertEquals(4096, (body["options"] as Map<*, *>)["num_ctx"])
        val messages = (body["messages"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"] })
        assertTrue((messages[0]["content"] as String).contains("SYSTEM RULES") && (messages[0]["content"] as String).contains("# Case file"))
        assertEquals("[Reviewer: Rina] why?", messages.last()["content"])
    }

    @Test
    fun `verified figures travel with the new question only`() {
        client().chat("s", "c", listOf(ReviewCopilotService.Turn("user", "earlier"), ReviewCopilotService.Turn("assistant", "answered")), "why?", null, "- APP-1 grade D")
        val messages = (received.single()["messages"] as List<*>).map { (it as Map<*, *>)["content"] as String }
        assertEquals("earlier", messages[1])
        assertTrue(messages.last().startsWith("why?") && messages.last().contains("- APP-1 grade D"))
    }

    @Test
    fun `an unreachable or failing server yields no reply`() {
        assertNull(client(baseUrl = "http://127.0.0.1:1").chat("s", "c", emptyList(), "q", null))
        healthy = false
        assertNull(client().chat("s", "c", emptyList(), "q", null))
    }

    @Test
    fun `the engine is off unless configured`() {
        assertTrue(client().enabled)
        assertFalse(client(engine = "auto").enabled)
    }

    @Test
    fun `the dashboard copilot answers through ollama and falls back to rules when it fails`() {
        val ask = { mockMvc.post("/admin/dashboard/copilot") { contentType = MediaType.APPLICATION_JSON; content = """{"question":"국가별 현황을 요약해줘"}""" } }
        ask().andExpect {
            status { isOk() }
            jsonPath("$.mode") { value("ollama") }
            jsonPath("$.model") { value("fake:1b") }
            jsonPath("$.answer") { value("답변입니다") }
        }
        val system = ((received.single()["messages"] as List<*>).first() as Map<*, *>)["content"] as String
        assertTrue(system.contains("# Portfolio file") && system.contains("## Review queue"))
        val asked = ((received.single()["messages"] as List<*>).last() as Map<*, *>)["content"] as String
        assertTrue(asked.contains("국가별 현황"), "the rule engine's figures for the topic go with the question")

        healthy = false
        ask().andExpect {
            status { isOk() }
            jsonPath("$.mode") { value("rules") }
            jsonPath("$.answer") { value(containsString("인도네시아")) }
        }
    }
}
