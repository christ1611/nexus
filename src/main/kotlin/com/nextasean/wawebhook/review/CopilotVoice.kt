package com.nextasean.wawebhook.review

/**
 * Conversational framing for the rule-based copilot answers, so they read like an assistant's reply rather than a
 * report: a short opener that acknowledges the question and a closing hint. Deterministic per question (the same
 * question gets the same wording), varied across questions.
 */
object CopilotVoice {
    private val OPENERS = listOf(
        "질문하신 내용을 기준으로 저장된 데이터를 살펴봤어요.",
        "네, 이 건의 기록을 바탕으로 정리해 드릴게요.",
        "확인해 봤습니다. 핵심만 먼저 말씀드리면 이렇습니다.",
        "좋은 질문이에요. 저장된 평가와 조회 결과를 기준으로 답하겠습니다.",
        "이 건의 근거를 다시 훑어봤어요.",
    )
    private val CLOSERS = listOf(
        "더 깊이 볼 항목이 있으면 이어서 물어보세요.",
        "특정 Flag나 조회 결과를 짚어 주시면 그 부분만 자세히 설명해 드릴게요.",
        "최종 판단은 심사원께서 하시되, 필요한 근거는 제가 더 찾아 드릴게요.",
        "다른 각도의 질문도 괜찮아요. 서류, 조회, 추천 중 무엇이든요.",
    )
    private val PORTFOLIO_OPENERS = listOf(
        "지금 대시보드에 올라온 수치를 기준으로 정리해 드릴게요.",
        "네, 최신 집계를 바탕으로 살펴봤어요.",
        "확인했습니다. 포트폴리오 전체를 기준으로 답하겠습니다.",
        "좋은 질문이에요. 현재 통계에서 보이는 흐름은 이렇습니다.",
    )
    private val PORTFOLIO_CLOSERS = listOf(
        "국가, 딜러, 주간 추이 중 더 파고들 항목이 있으면 말씀해 주세요.",
        "특정 신청을 짚어 주시면 심사 화면에서 바로 이어서 볼 수 있어요.",
        "이 수치가 어떻게 나왔는지 궁금하면 계속 물어보세요.",
    )

    fun open(question: String, subject: String): String = pick(OPENERS, question + subject) + "\n\n"
    fun close(question: String): String = "\n\n" + pick(CLOSERS, question)
    fun openPortfolio(question: String): String = pick(PORTFOLIO_OPENERS, question) + "\n\n"
    fun closePortfolio(question: String): String = "\n\n" + pick(PORTFOLIO_CLOSERS, question)

    private fun pick(options: List<String>, seed: String): String = options[Math.floorMod(seed.trim().lowercase().hashCode(), options.size)]
}
