package com.nextasean.wawebhook.review

/**
 * Scope gate in front of both copilots: only questions about the credit onboarding work (applications, risk, fraud,
 * documents, lookups, dealers, markets, lending) reach an engine. It runs before Claude, Ollama and the rules, so an
 * off-topic question costs nothing and gets the same polite answer in every mode. The system prompts carry the same
 * rule as a second layer for questions that pass here.
 */
object CopilotScope {
    enum class Desk { REVIEW, DASHBOARD }

    /** Finance and onboarding vocabulary, matched anywhere in the question (Korean, Indonesian and the product's own terms). */
    private val DOMAIN_ANYWHERE = Regex(
        listOf(
            // review and onboarding
            "신청", "심사", "승인", "거절", "보류", "검토", "근거", "보고서", "온보딩", "대기", "큐",
            // risk and fraud
            "리스크", "위험", "등급", "점수", "플래그", "배지", "위조", "사기", "변조", "조작", "중복", "도용", "연결망", "경고",
            // documents and identity
            "서류", "문서", "신분증", "셀피", "재요청", "재제출", "추출", "판독", "신원",
            // credit and lending
            "금융", "대출", "여신", "신용", "상환", "연체", "금리", "이자", "할부", "담보", "소득", "재무", "부채", "한도", "조회", "채무",
            // portfolio
            "딜러", "고객", "국가", "시장", "인도네시아", "미얀마", "캄보디아", "통계", "현황", "추이", "주간", "월별", "비율", "승인율", "포트폴리오", "대시보드",
            "에이전트", "자동화", "코파일럿", "파이프라인",
            // Indonesian
            "pinjaman", "kredit", "cicilan", "angsuran", "nasabah", "bunga",
        ).joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    /** English terms, matched at the start of a word so short ones do not hit inside unrelated words. */
    private val DOMAIN_WORDS = Regex(
        "\\b(" + listOf(
            "applica", "approv", "reject", "decision", "review", "officer", "onboard", "queue", "backlog", "pending",
            "risk", "grade", "score", "flag", "badge", "fraud", "forg", "tamper", "duplicate", "evidence", "warning",
            "document", "ktp", "nik", "selfie", "id card", "identity", "verif", "lookup", "dukcapil", "pefindo", "registry", "bureau", "nrc",
            "loan", "lend", "credit", "repay", "interest rate", "instal", "collateral", "income", "debt", "financ", "underwrit", "kyc", "aml",
            "dealer", "customer", "applicant", "country", "countries", "market", "indonesia", "myanmar", "cambodia", "portfolio", "dashboard",
            "trend", "statistic", "agent", "pipeline", "copilot",
        ).joinToString("|") { Regex.escape(it) } + ")",
        RegexOption.IGNORE_CASE,
    )

    /** Application, dealer and ID numbers point at the case data even without other words. */
    private val IDENTIFIERS = Regex("\\bAPP-\\d{4}-\\d+\\b|\\bDLR-\\d+\\b|\\b\\d{16}\\b", RegexOption.IGNORE_CASE)

    /** A short follow-up ("왜?", "더 자세히", "and the second one?") continues an on-topic conversation. */
    private const val FOLLOW_UP_MAX_CHARS = 30

    private val HANGUL = Regex("[가-힣]")

    fun inScope(question: String, history: List<ReviewCopilotService.Turn>): Boolean {
        val q = question.trim()
        if (DOMAIN_ANYWHERE.containsMatchIn(q) || DOMAIN_WORDS.containsMatchIn(q) || IDENTIFIERS.containsMatchIn(q)) return true
        // Only a follow-up to a question that was itself in scope; the prompt rule is the second layer for these.
        val lastUser = history.lastOrNull { it.role != "assistant" && it.content.isNotBlank() }?.content
        return q.length <= FOLLOW_UP_MAX_CHARS && lastUser != null && inScope(lastUser, emptyList())
    }

    /** The fixed reply to an off-topic question, in the question's language, with examples for this copilot; null when in scope. */
    fun decline(question: String, history: List<ReviewCopilotService.Turn>, desk: Desk): String? {
        if (inScope(question, history)) return null
        val korean = HANGUL.containsMatchIn(question) || !Regex("[A-Za-z]").containsMatchIn(question)
        return when (desk) {
            Desk.REVIEW -> if (korean) {
                "이 코파일럿은 지금 보고 있는 신청의 심사 관련 질문에만 답해요 (리스크·사기 근거, 서류, 신원·신용 조회, 승인 조건 등).\n예: '이 등급·점수가 나온 이유를 설명해줘', '승인해도 될까? 조건은?'"
            } else {
                "This copilot only answers questions about reviewing the open application (risk and fraud evidence, documents, identity and credit lookups, approval conditions).\nFor example: 'Explain why this grade and score came out' or 'Can this be approved? Under what conditions?'"
            }
            Desk.DASHBOARD -> if (korean) {
                "이 코파일럿은 온보딩·심사 현황에 관한 금융 업무 질문에만 답해요 (심사 대기, 리스크·사기, 국가·딜러 실적, 추이 등).\n예: '심사 대기 큐에서 먼저 볼 신청은?', '국가별 고위험 비율을 비교해줘'"
            } else {
                "This copilot only answers questions about the onboarding and review portfolio (the review queue, risk and fraud, markets and dealers, trends).\nFor example: 'Which applications should officers look at first?' or 'Compare the high-risk share by country'"
            }
        }
    }

    /** The same rule for the system prompts, as the second layer behind [decline]. */
    const val PROMPT_RULE = "- Scope: answer only questions about this credit onboarding and review work (applications, credit risk, fraud, " +
        "documents, identity and credit lookups, dealers, markets, lending). For anything else (general knowledge, coding, " +
        "entertainment, personal topics, other companies), reply with one short sentence in the language of the question saying " +
        "you only answer questions about this review work, and nothing else."
}
