package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.review.CopilotScope.Desk
import com.nextasean.wawebhook.review.ReviewCopilotService.Turn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scope gate lets every work question through and declines everything else before an engine is called. */
class CopilotScopeTest {

    /** The suggested-question chips on both pages, read from the HTML so new chips are covered automatically. */
    private fun chips(page: String): List<String> {
        val html = Files.readString(Path.of("src/main/resources/static/$page"))
        val block = Regex("const COPILOT_PROMPTS = \\[(.*?)\\];", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        return Regex("'([^']+)'").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every suggested question on both pages is in scope, in Korean and English`() {
        val all = chips("review.html") + chips("dashboard.html")
        assertTrue(all.size >= 20, "chips found: $all")
        all.forEach { assertTrue(CopilotScope.inScope(it, emptyList()), "chip blocked: $it") }
    }

    @Test
    fun `work questions pass`() {
        listOf(
            "왜 이 점수가 나왔어?", "승인해도 될까?", "이 고객 대출 상환 능력은 어때?", "APP-2026-001007 어때?", "DLR-003 실적은?",
            "Is the credit score good enough for the loan?", "Any fraud on this applicant?", "Berapa cicilan nasabah ini?",
        ).forEach { assertTrue(CopilotScope.inScope(it, emptyList()), "blocked: $it") }
    }

    @Test
    fun `off-topic questions are declined in the question's language`() {
        listOf("오늘 서울 날씨 어때?", "김치찌개 레시피 알려줘", "이 영화 요약해줘", "파이썬으로 정렬 코드 짜줘", "농담 하나 해줘").forEach {
            val reply = assertNotNull(CopilotScope.decline(it, emptyList(), Desk.REVIEW), "passed: $it")
            assertTrue(reply.contains("심사"), reply)
        }
        listOf("What's the weather in Jakarta?", "Write me a poem about the sea", "Who won the World Cup?").forEach {
            val reply = assertNotNull(CopilotScope.decline(it, emptyList(), Desk.DASHBOARD), "passed: $it")
            assertTrue(reply.startsWith("This copilot only answers"), reply)
        }
    }

    @Test
    fun `a short follow-up passes only after an in-scope question`() {
        val onTopic = listOf(Turn("user", "왜 이 점수가 나왔어?"), Turn("assistant", "등급 A ..."))
        assertNull(CopilotScope.decline("더 자세히", onTopic, Desk.REVIEW))
        assertNull(CopilotScope.decline("And why?", onTopic, Desk.REVIEW))
        val offTopic = listOf(Turn("user", "오늘 날씨 어때?"), Turn("assistant", "..."))
        assertNotNull(CopilotScope.decline("더 자세히", offTopic, Desk.REVIEW))
        // A long new question has to stand on its own, even mid-conversation.
        assertNotNull(CopilotScope.decline("그건 그렇고 이번 주말에 갈 만한 여행지 좀 추천해 줄 수 있을까요?", onTopic, Desk.REVIEW))
    }
}
