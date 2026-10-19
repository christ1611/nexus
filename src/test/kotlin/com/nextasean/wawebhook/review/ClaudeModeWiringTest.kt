package com.nextasean.wawebhook.review

import com.anthropic.client.AnthropicClient
import com.nextasean.wawebhook.customer.CustomerConversationService
import com.nextasean.wawebhook.documents.ClaudeDocumentCheck
import com.nextasean.wawebhook.documents.DocumentCheckService
import com.nextasean.wawebhook.documents.StubDocumentCheck
import com.nextasean.wawebhook.documents.analysis.ClaudeDocumentAnalysisService
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisService
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Boots the context in Claude mode (no API call is made) and checks the conditional beans replace the stub. */
@SpringBootTest(properties = ["app.document-check=claude", "claude.api-key=sk-ant-test-not-a-real-key"])
@ActiveProfiles("test")
@Import(FakeIntegrationsConfig::class)
class ClaudeModeWiringTest @Autowired constructor(private val ctx: ApplicationContext) {

    @Test
    fun `claude beans are wired and the stub is gone`() {
        assertIs<ClaudeDocumentCheck>(ctx.getBean(DocumentCheckService::class.java))
        assertIs<ClaudeDocumentAnalysisService>(ctx.getBean(DocumentAnalysisService::class.java))
        assertIs<ClaudeReviewAgent>(ctx.getBean(ReviewAgent::class.java))
        ctx.getBean(AnthropicClient::class.java)
        assertTrue(ctx.getBeansOfType(StubDocumentCheck::class.java).isEmpty())
        ctx.getBean(CustomerConversationService::class.java)
        assertEquals(1, ctx.getBeansOfType(ReviewAgent::class.java).size)
    }
}
