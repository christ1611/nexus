# NEXT ASEAN 온보딩 — AI 활용 설명서 (AI usage in this project)

_기준일: 2026-10-22. 코드 기준으로 작성했으며, 각 항목에 구현 파일을 적어 두었습니다. 영어 요약은 맨 아래에 있습니다._

이 프로젝트는 **Claude API**를 두 곳에서 호출하고, 나머지 판단은 **코드 규칙**으로 합니다.
원칙은 하나입니다. **모델은 "보이는 것"을 구조화해서 기술하고, "결정"(재촬영 요청·등급·플래그·승인/거절)은 코드와 심사자가 합니다.**
그래서 모든 등급과 경고는 근거 문장과 함께 심사 화면에 표시되고, 테스트로 재현됩니다.

---

## 1. 한눈에 보기

| 단계 | 담당 | 무엇을 하나 | 입력 → 출력 | 코드가 쥔 가드레일 | 모델이 없을 때 |
| --- | --- | --- | --- | --- | --- |
| ① 서류 판독·품질 | **Claude (vision)** `documents/analysis/DocumentAnalysisService.kt`, `AnalysisPrompt.kt` | 고객이 WhatsApp으로 보낸 사진 1장을 JSON 스키마대로 기술 | 이미지 → 서류 종류·신뢰도, 품질 문제(blur/glare/화면 재촬영…), **위변조 증거(`tamper_signals`)**, 필드 17개 + 필드별 신뢰도, 메모 | 필드 신뢰도 임계값(`CLAUDE_MIN_FIELD_CONFIDENCE`, 기본 0.85) 미만은 "담당자 검토" 상태로만 저장. 모델은 추측·보정 금지(프롬프트). 품질 HIGH면 재촬영, **위조 의심은 재촬영이 아니라 심사자에게** | 스텁 검사(`StubDocumentCheck`): 샘플 필드, 캡션 트릭 |
| ② NIK 구조 검증 | **코드** `NikValidator.kt` | 16자리, 지역 코드(시·도 38개), 생년월일(+40 여성) 인코딩, 카드의 생년월일·성별과 대조 | NIK + 카드 필드 → `NikValidation(issues)` | 검증 실패 시 NIK 필드는 자동 사용 금지(조회에 쓰지 않음) | 항상 동작 (Claude 모드에서만 NIK가 "읽혀" 들어옴) |
| ③ 셀피 대조 | **Claude (vision)** 같은 호출, `SELFIE_FIELDS` | 셀피 속 얼굴 수, 선명도, 들고 있는 신분증의 NIK, **얼굴과 카드 사진의 유사 여부(`face_matches_ktp_photo`)** | 이미지 → 5개 관찰값 | "no"일 때만 플래그, "unclear"는 플래그 없음(보조 신호이지 전문 얼굴인식이 아님) | 스텁: 관찰값 없음 |
| ④ 리뷰 에이전트 | **Claude (tool use)** `review/ClaudeReviewAgent.kt` | 두 서류가 모두 들어오면 케이스 파일을 읽고 **도구 2개 중 하나**를 호출: `request_documents`(고객에게 어떤 서류를 왜 다시 받을지) 또는 `write_summary`(심사자용 요약) | 분석 JSON·추출 필드·라운드 수 → 도구 호출 1회 | 재요청 횟수 상한(`claude.max-review-rounds`), 도구 입력 검증, 요약은 코드가 템플릿으로 재구성(`SummaryTemplate.kt`), 모델 거절·오류 시 **규칙 기반 폴백**(`ReviewAgent.kt`) | 규칙 기반 에이전트 |
| ⑤ 신원·신용 조회 | 코드 + 외부 제공자(현재 목업) `verification/` | Dukcapil/PEFINDO(ID), NRC/MM, GDI/KH 목업 | 추출 필드 → MATCH/MISMATCH/NOT_FOUND/TIMEOUT, 점수 | 조회는 **검증된 NIK**가 있을 때만 | — |
| ⑥ 중복 제출 교차 조회 | **코드** `RiskAssessmentService.related()` + `RiskRules.crossApplicationFlags` | 같은 NIK가 다른 신청에, 같은 전화번호가 다른 NIK로 제출됐는지 DB 조회 | 신청 → 관련 신청 목록 | 양쪽 신청을 모두 재평가(열린 건만). 근거에는 신청번호·이름·전화 뒷자리만, 전체 NIK는 절대 기록 안 함 | 항상 동작(스텁 모드 포함) |
| ⑦ Risk Flag·등급·배지 | **코드** `risk/RiskRules.kt` | ①~⑥의 근거를 점수화: 낮음 10·중간 20·높음 35, A/B/C/D, 위조 신호 HIGH 하나면 최소 C | 근거 → 플래그 목록(코드·출처·심각도·근거 문장), 점수, 등급, 배지 | 결정은 전부 코드. 대시보드에 평가 방식("Claude 비전 + 규칙" / "규칙(스텁)") 표시 | 조회 결과·행동·중복 규칙만 적용 |
| ⑧ 기록 | 코드 `trace/`, `review/ReviewEvents.kt` | 모델 호출마다 토큰·지연·stop reason, 도구 호출, 폴백, 가드레일 개입을 `AGENT_STEPS`에 기록 | — | 심사 화면 "AI Agent 판단 타임라인", 대시보드 "AI 에이전트 활동", 심사 통계 "에이전트 판단 vs 심사 결과" | — |

⑨ **코파일럿 (질문 답변)** — 심사 화면(`review/ReviewCopilotService.kt`, 한 신청의 케이스 파일)과 대시보드(`admin/DashboardCopilotService.kt`, 통계 전체의 포트폴리오 파일)에서 심사원의 질문에 Claude가 답합니다. 근거는 시스템 블록으로 캐시해 넣고, 모델은 설명·비교·우선순위 제안만 하며 아무것도 저장·결정하지 않습니다. API 키가 없으면 같은 데이터를 규칙으로 요약합니다.

모델·키 설정: `DOCUMENT_CHECK=claude`, `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`(기본 `claude-opus-5-5`), `CLAUDE_MIN_FIELD_CONFIDENCE`, `CLAUDE_MAX_REVIEW_ROUNDS`. 기본은 `stub`.

---

## 2. 신규 요구사항이 어디에 구현됐나 (2026-10-22)

### 2.1 NIK 신분증 위변조 검증

세 겹으로 봅니다. 한 겹은 모델, 두 겹은 코드입니다.

1. **구조 검증(코드)** — `NikValidator`: 자릿수, 시·도 코드, 군·구/동 코드 `00` 금지, 생년월일 인코딩(여성 +40), 일련번호 `0000` 금지, 카드에 인쇄된 생년월일·성별과 일치 여부. 실패 → `INVALID_ID_NUMBER`(높음, 위조 신호).
2. **지역 교차 검증(코드)** — NIK 앞 두 자리의 시·도와 카드 상단 `PROVINSI` 헤더(모델이 읽은 `province` 필드)가 다르면 `ID_REGION_MISMATCH`(높음, 위조 신호). 모델이 헤더를 확신 있게 읽었을 때만 비교합니다. 표기 차이(`PROVINSI DKI JAKARTA` / `JAKARTA`)는 같은 지역으로 봅니다.
3. **시각적 위조 증거(모델이 기술, 코드가 판정)** — 프롬프트와 JSON 스키마에 `tamper_signals[]`를 두었습니다: `FONT_MISMATCH`(필드 간 글꼴·인쇄 불일치), `TEXT_MISALIGNED`, `DIGITAL_EDITING`(클론·블러 패치·잘린 가장자리), `PHOTO_REPLACED`(증명사진 교체), `LAYOUT_MISMATCH`, `SECURITY_FEATURE_MISSING`, `FIELD_INCONSISTENT`, `OTHER` — 각각 심각도와 "카드의 어디가 어떻게" 설명. 하나라도 있으면 `FORGERY_SIGNS`(가장 강한 신호가 HIGH면 높음, 아니면 중간; 위조 신호). 위조 의심 서류는 **고객에게 재촬영을 요청하지 않고** 담당자 검토로 넘깁니다(위조범에게 다시 찍을 기회를 주지 않기 위해).
4. **셀피 대조** — 들고 있는 카드의 NIK가 제출 서류와 다르면 `SELFIE_ID_MISMATCH`, 얼굴이 카드 사진과 다르다고 모델이 답하면 `SELFIE_FACE_MISMATCH`(둘 다 높음, 위조 신호). 얼굴 대조는 보조 신호이며 전문 얼굴인식 솔루션을 대체하지 않습니다.
5. **등록부 대조** — Dukcapil 조회 불일치 `IDENTITY_MISMATCH`(높음, 위조 신호).

### 2.2 Fraud / 위조 감지 경고

- 위조 신호 코드(⚠)는 `RiskFlag.FRAUD_CODES`에 모여 있고, 하나라도 심각도 "높음"이면 등급이 최소 **C**로 내려갑니다.
- 표시 위치: 딜러 WhatsApp 요약("N fraud/forgery warning(s)"), 심사 큐의 ⚠ 칩, 신청 상세의 **Risk Flag · 근거** 카드(출처·심각도·근거 문장), 서류 카드의 **위조 의심 요소** 칩(모델이 본 것 그대로), 대시보드 "리스크·사기" 탭, 심사 통계 탭의 "대기 중 Risk Flag".
- 전체 목록은 `README.md` 5.1 표.

### 2.3 동일 신분증으로 여러 명이 제출

- 문서 검사가 끝나 NIK가 추출되는 순간 `RiskAssessmentService.related()`가 같은 NIK·같은 전화번호의 다른 신청을 조회합니다.
- **다른 사람**(이름 또는 전화가 다름)이 같은 NIK → `SHARED_ID_NUMBER`(높음, 위조 신호). 근거: 상대 신청번호·상태·이름·전화 뒷자리.
- **같은 사람**(이름·전화 같음)이 또 신청 → `DUPLICATE_APPLICATION`(중간, 위조 신호 아님).
- **같은 전화번호**로 다른 NIK → `PHONE_USED_WITH_OTHER_ID`(높음, 위조 신호).
- 양쪽 다 경고를 받습니다: 새 신청을 평가할 때 **아직 결정되지 않은** 관련 신청도 함께 재평가합니다(결정 완료 건은 건드리지 않음).
- 데모: 두 번째 고객이 KTP 사진 캡션에 `dup`을 쓰면 스텁이 직전 고객의 NIK를 제출합니다. 스텁의 기본 샘플 NIK는 신청번호 끝 세 자리를 일련번호에 넣어 고객마다 다릅니다(우연한 중복 방지). 데모 시더에는 `SHARED_ID`·`FORGERY` 시나리오가 포함돼 대시보드에서 바로 보입니다.

---

## 3. Risk Flag별 근거 출처

| 플래그 | 근거를 만든 쪽 | 판정한 쪽 |
| --- | --- | --- |
| `FORGERY_SIGNS`, `SCREEN_OR_COPY`, `TAMPERING_SUSPECTED`, `WRONG_DOCUMENT_TYPE`, `MULTIPLE_FACES`, `SELFIE_FACE_MISMATCH`, `LOW_CONFIDENCE_FIELDS` | Claude 비전(기술) | 코드 규칙 |
| `INVALID_ID_NUMBER`, `ID_REGION_MISMATCH`, `SELFIE_ID_MISMATCH`, `ID_CHANGED_BETWEEN_UPLOADS` | Claude가 읽은 값 + 코드 검증 | 코드 규칙 |
| `IDENTITY_*`, `CREDIT_*`, `LOW_CREDIT_SCORE`, `NO_CREDIT_HISTORY` | 외부 조회(목업) | 코드 규칙 |
| `SHARED_ID_NUMBER`, `DUPLICATE_APPLICATION`, `PHONE_USED_WITH_OTHER_ID` | DB 교차 조회 | 코드 규칙 |
| `REPEATED_RESUBMISSION`, `MANY_FAILED_UPLOADS`, `AGE_OUT_OF_RANGE` | 고객 행동 기록 | 코드 규칙 |

등급(A~D)과 점수는 어떤 경우에도 모델이 정하지 않습니다.

---

## 4. 안전장치와 데이터 취급

- **신뢰도 임계값**: 임계값 미만 필드는 "NEEDS_OFFICER_REVIEW"로만 저장되고 조회·추출에 쓰이지 않습니다.
- **구조화 출력**: 모든 모델 호출은 JSON 스키마로 강제(`AnalysisPrompt.SCHEMA`), 파싱 실패·거절·미지원 형식은 "분석 불가"로 처리되어 심사자에게 갑니다(`AnalysisResultMapper.notAnalysed`).
- **재요청 상한**: 에이전트의 서류 재요청은 `max-review-rounds`까지, 그 뒤엔 요약만 씁니다. 심사자 재요청도 같은 카운터를 씁니다.
- **개인정보**: 로그·근거 문장에는 NIK 뒷 4자리만(`***1234`). 원본 모델 응답(`rawJson`)은 감사용으로 DOCUMENTS 행에 저장(길이 상한).
- **관측**: 호출마다 입력/캐시/출력 토큰, 지연, stop reason을 기록 → 타임라인·대시보드·심사 통계에 표시.
- **폴백**: 모델 불가 시 서류는 보존되고 "담당자 검토"로, 리뷰 에이전트는 규칙 기반으로 전환. 서비스는 멈추지 않습니다.

---

## 5. 아직 안 한 것 / 주의

- **실 API 미검증**: Claude 경로는 코드·단위 테스트(스크립트된 응답)까지 완료, 실 키로 라이브 호출은 아직입니다(`ANTHROPIC_API_KEY`가 있으면 `ClaudeDocumentAnalysisLiveTest`가 자동 실행). 실 KTP로 신뢰도·위조 임계값 튜닝이 필요합니다.
- 얼굴 대조와 위조 증거는 **보조 신호**입니다. 결정은 심사자가 합니다. 전문 FR·문서 포렌식 벤더가 들어오면 같은 플래그 코드에 연결하면 됩니다.
- 중복 제출 조회는 현재 **추출된 NIK 문자열**과 **전화번호**로만 합니다(얼굴 임베딩·이름 유사도 없음).
- 스텁 모드에서는 ①·③·④의 모델 근거가 없어 조회·행동·중복 규칙만 적용됩니다. 대시보드가 평가 방식을 표시합니다.

---

## English summary

Claude is called in exactly two places: (1) **vision analysis** of each WhatsApp photo, returning schema-bound JSON (document type, quality issues, **forgery evidence `tamper_signals`**, 17 fields with calibrated confidence, selfie observations including **`face_matches_ktp_photo`**), and (2) the **review agent**, a single tool-use turn choosing `request_documents` or `write_summary` with a bounded number of rounds and a rule-based fallback. Everything that decides (retake vs officer, NIK structure validation, NIK-vs-printed-province check, cross-application duplicate lookup, risk flags, score, grade A-D, badges) is deterministic code in `risk/RiskRules.kt`, so every grade is explainable and tested.

New on 22 Oct 2026: `FORGERY_SIGNS` (model-described editing evidence, severity from the strongest signal), `ID_REGION_MISMATCH` (NIK province vs card header), `SELFIE_FACE_MISMATCH`, and the cross-application checks `SHARED_ID_NUMBER` (same ID, different person), `DUPLICATE_APPLICATION` (same person again) and `PHONE_USED_WITH_OTHER_ID`; both sides of a shared ID are re-assessed while still open. Forgery evidence never triggers a customer retake; it routes the case to an officer. The stub check now derives a unique sample NIK per application; the caption `dup` reuses the previous customer's NIK for demos. The live Claude path is still unverified against the real API.
