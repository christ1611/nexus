package com.nextasean.wawebhook.review

/**
 * Scope gate in front of both copilots: only questions about the credit onboarding work (applications, risk, fraud,
 * documents, lookups, dealers, markets, lending) reach an engine. It runs before Claude, Ollama and the rules, so an
 * off-topic question costs nothing and gets the same polite answer in every mode. The system prompts carry the same
 * rule as a second layer for questions that pass here.
 */
object CopilotScope {
    enum class Desk { REVIEW, DASHBOARD }

    /**
     * Strong terms belong to the credit and onboarding work on their own (Korean and Indonesian matched anywhere, English
     * at the start of a word). Weak terms (markets, "customer", "status", "trend") also appear in everyday questions, so
     * they count only in pairs: "국가별 현황" is work, "인도네시아 수도는?" is not.
     */
    private val STRONG_KO = terms(
        "신청", "심사", "승인", "거절", "보류", "온보딩", "리스크", "위험", "등급", "점수", "플래그", "배지", "위조", "사기", "변조", "도용", "연결망",
        "서류", "신분증", "셀피", "재요청", "재제출", "판독", "신원", "금융", "대출", "여신", "신용", "상환", "연체", "금리", "이자", "할부", "담보",
        "소득", "재무", "부채", "한도", "조회", "채무", "딜러", "승인율", "포트폴리오", "대시보드", "에이전트", "코파일럿", "파이프라인",
        "pinjaman", "kredit", "cicilan", "angsuran", "nasabah", "bunga",
    )
    private val WEAK_KO = terms(
        "고객", "국가", "시장", "인도네시아", "미얀마", "캄보디아", "통계", "현황", "추이", "주간", "월별", "비율", "실적", "전체", "요약",
        "대기", "큐", "경고", "중복", "조작", "검토", "근거", "보고서", "추출", "문서", "자동화",
    )
    private val STRONG_EN = words(
        "applica", "approv", "reject", "review", "officer", "onboard", "queue", "backlog", "risk", "grade", "score", "flag", "badge", "fraud",
        "forg", "tamper", "document", "ktp", "nik", "selfie", "id card", "identity", "verif", "lookup", "dukcapil", "pefindo", "registry",
        "bureau", "nrc", "loan", "lend", "credit", "repay", "interest rate", "instal", "collateral", "income", "debt", "financ", "underwrit",
        "kyc", "aml", "dealer", "applicant", "portfolio", "dashboard", "copilot", "ai agent", "ask the customer",
    )
    private val WEAK_EN = words(
        "customer", "country", "countries", "market", "indonesia", "myanmar", "cambodia", "trend", "weekly", "monthly", "statistic", "decision",
        "pending", "duplicate", "evidence", "warning", "agent", "pipeline", "summar", "status",
    )

    /**
     * Subjects that are never review work. They win over everything, including work words and the follow-up rule, so
     * "한국 대통령은?" is declined even right after an on-topic question.
     */
    private val OFF_TOPIC_KO = terms(
        "대통령", "총리", "국회", "정치", "정당", "선거", "수도는", "날씨", "기온", "여행", "관광", "맛집", "음식", "레시피", "요리", "영화", "드라마",
        "노래", "가수", "연예인", "아이돌", "스포츠", "축구", "야구", "농구", "올림픽", "월드컵", "게임", "연애", "운세", "별자리", "농담", "소설",
        "코딩", "프로그래밍", "파이썬", "번역해", "숙제",
        // people outside the work: roles, and names that are likely to be tried in a demo
        // whole phrases only: 선수금, 배우자 and 금융감독원 are finance words
        "운동선수", "축구선수", "야구선수", "영화배우", "배우가", "배우는", "영화감독", "유명인", "연예", "셀럽", "인플루언서", "유튜버",
        "손흥민", "이강인", "김민재", "황희찬", "박지성", "류현진", "김연아", "오타니", "리오넬 메시", "호날두",
        "방탄소년단", "블랙핑크", "뉴진스", "아이유", "싸이", "봉준호", "이재용", "일론 머스크", "머스크",
        "트럼프", "바이든", "윤석열", "이재명", "문재인", "김정은", "시진핑", "푸틴", "조코위", "프라보워",
    )
    private val OFF_TOPIC_EN = words(
        "president", "prime minister", "politic", "election", "capital of", "capital city", "weather", "travel", "tourist", "restaurant",
        "recipe", "movie", "film", "song", "singer", "celebrit", "sport", "football", "soccer", "baseball", "world cup", "olympic", "game",
        "joke", "poem", "horoscope", "python", "javascript", "translate",
        "athlete", "actor", "actress", "celebrity", "influencer", "youtuber",
    )
    /** Names, matched as whole words ("trump" is not "trumpet"). */
    private val OFF_TOPIC_NAMES = Regex(
        "\\b(" + listOf("son heung", "heung-min", "messi", "ronaldo", "bts", "blackpink", "newjeans", "elon musk", "musk", "trump", "biden", "putin", "xi jinping", "jokowi", "prabowo")
            .joinToString("|") { Regex.escape(it) } + ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun terms(vararg t: String) = Regex(t.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    private fun words(vararg t: String) = Regex("\\b(" + t.joinToString("|") { Regex.escape(it) } + ")", RegexOption.IGNORE_CASE)

    /** Distinct weak terms in the question. */
    private fun weakCount(q: String): Int =
        (WEAK_KO.findAll(q).map { it.value.lowercase() } + WEAK_EN.findAll(q).map { it.value.lowercase() }).toSet().size

    /** Application, dealer and ID numbers point at the case data even without other words. */
    private val IDENTIFIERS = Regex("\\bAPP-\\d{4}-\\d+\\b|\\bDLR-\\d+\\b|\\b\\d{16}\\b", RegexOption.IGNORE_CASE)

    /** A short follow-up ("왜?", "더 자세히", "and the second one?") continues an on-topic conversation. */
    private const val FOLLOW_UP_MAX_CHARS = 30

    /**
     * Words that make a short question a continuation rather than a new subject. "손흥민은?" is short but names a new
     * subject, so it is not a follow-up; "그럼 두 번째는?" is.
     */
    private val FOLLOW_UP = Regex(
        "왜|이유|더|자세히|구체적|그럼|그러면|그건|그거|그게|그 건|이건|다음|첫 ?번째|두 ?번째|세 ?번째|나머지|어떻게|예시|근거|정리|요약|설명|다시|맞아|확실" +
            "|\\b(why|how|more|detail|explain|again|then|next|first|second|third|other|rest|example|summar|sure|really)",
        RegexOption.IGNORE_CASE,
    )

    private val HANGUL = Regex("[가-힣]")

    /** True when the text names a subject that is never review work: built-in ones plus [extraBlocked] (app.copilot-blocked-terms). */
    fun mentionsBlocked(text: String, extraBlocked: List<String> = emptyList()): Boolean =
        OFF_TOPIC_KO.containsMatchIn(text) || OFF_TOPIC_EN.containsMatchIn(text) || OFF_TOPIC_NAMES.containsMatchIn(text) ||
            extraBlocked.any { it.isNotBlank() && text.contains(it.trim(), ignoreCase = true) }

    fun inScope(question: String, history: List<ReviewCopilotService.Turn>, extraBlocked: List<String> = emptyList()): Boolean {
        val q = question.trim()
        if (mentionsBlocked(q, extraBlocked)) return false
        if (STRONG_KO.containsMatchIn(q) || STRONG_EN.containsMatchIn(q) || IDENTIFIERS.containsMatchIn(q) || weakCount(q) >= 2) return true
        // Only a follow-up to a question that was itself in scope; the prompt rule is the second layer for these.
        val lastUser = history.lastOrNull { it.role != "assistant" && it.content.isNotBlank() }?.content
        return q.length <= FOLLOW_UP_MAX_CHARS && FOLLOW_UP.containsMatchIn(q) && lastUser != null && inScope(lastUser, emptyList(), extraBlocked)
    }

    /** The fixed reply to an off-topic question, in the question's language, with examples for this copilot; null when in scope. */
    fun decline(question: String, history: List<ReviewCopilotService.Turn>, desk: Desk, extraBlocked: List<String> = emptyList()): String? {
        if (inScope(question, history, extraBlocked)) return null
        return refusal(question, desk)
    }

    /** The fixed refusal, in the question's language; also replaces a model answer that drifted onto a blocked subject. */
    fun refusal(question: String, desk: Desk): String {
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
