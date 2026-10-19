<a id="korean"></a>
# NEXT ASEAN WhatsApp 온보딩 웹훅 (wa-webhook)

**한국어** | [English](#english)

WhatsApp Cloud API 기반 딜러·고객 온보딩 봇입니다. Spring Boot 3 + Kotlin + Gradle로 작성했고,
내장 H2 DB에 상태 머신을 저장합니다. 딜러가 WhatsApp에서 신청서를 만들고, 고객이 같은 채널로
동의·KTP(주민등록증)·셀피를 제출하면 문서 검사와 신원·신용 조회(목업)를 거쳐 심사 대기 상태로 넘어갑니다.

---

## 1. 개발 현황 (2026-10-19 기준)

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| Step 1 | 웹훅 수신: `GET /webhook` 검증 핸드셰이크, `POST /webhook` 서명 검증(`X-Hub-Signature-256`), 비동기 처리, 중복 메시지 무시, 메시지·상태 로그 | ✅ 완료 |
| Step 2 | 딜러 대화 흐름: 메인 메뉴(리스트), 신규 신청(버튼 → 신청번호 + 1회용 링크), 상태 조회, 미등록 번호 거절, 데모 딜러 시드 | ✅ 완료 |
| Step 2b | 백오피스 이벤트: 폼 제출, 재제출 요청, 승인/거절 알림을 딜러에게 WhatsApp으로 푸시 (`/admin/**`) | ✅ 완료 |
| Step 3 | 고객 여정: wa.me `START <신청번호>` 링크, 동의 버튼, KTP → 셀피 수집(미디어 다운로드·저장), 스텁 문서 검사, 목업 Dukcapil + PEFINDO 조회, `READY_FOR_REVIEW` 전환, 양측 알림 | ✅ 완료 |
| Step 3b | Claude 문서 분석 + 리뷰 에이전트 (`DOCUMENT_CHECK=claude`): 비전 분석(JSON 스키마), NIK 검증, 신뢰도 임계값, 도구 사용 에이전트(`request_documents` / `write_summary`), 규칙 기반 폴백 | ✅ 코드·테스트 완료, **실 API 미검증** |
| Step 4 | 심사자 UI / 백오피스 화면, 고객에게 결정 알림, 고객 직접 재제출 흐름 | ⬜ 미착수 |
| Step 5 | 통계 대시보드(`/dashboard`): 국가별 통계(인도네시아·미얀마·캄보디아), **Fraud/위조 감지 Risk Flag**, **고객 리스크 등급(A~D) AI 표시**, 고객 배지(재무 정합성·우수 딜러 유입), 퍼널·딜러별 현황. 딜러 국가·등급, 국가별 조회 제공자(목업) | ✅ 완료 (2026-10-19) |
| 운영 | 실 Meta 미디어 다운로드 검증, PostgreSQL + Flyway, 영속 중복 제거, 시크릿·문서 암호화, 관측성, ngrok 대신 고정 도메인 | ⬜ 미착수 |

- 테스트: `./gradlew test` → **69개 통과, 1개 스킵** (라이브 Claude 테스트는 `ANTHROPIC_API_KEY`가 있을 때만 실행).
- Meta 웹훅은 ngrok 예약 도메인(`amaretto-tipoff-excess.ngrok-free.dev`)에 이미 등록·검증되어 있어 터널만 올리면 됩니다.
- 데모 진행 순서는 `DEMO.md`, 남은 작업의 우선순위는 `NEXT_STEPS.md`에 정리되어 있습니다.

### 알려진 제약

- Meta 대시보드의 임시 액세스 토큰은 24시간 후 만료됩니다. 발신 로그에 `HTTP 401`이 보이면 System User 토큰으로 교체하세요.
- 중복 메시지 캐시는 인메모리라 재시작·다중 인스턴스 간에 공유되지 않습니다.
- 고객에게 보내는 승인/거절 알림은 아직 없고 딜러에게만 전송됩니다.
- 24시간 고객 서비스 창 밖의 알림은 템플릿 메시지가 필요하며 아직 적용하지 않았습니다.

---

## 2. 딜러 대화 흐름

| 딜러 입력 | 봇 응답 | 저장 상태 |
| --- | --- | --- |
| 아무 텍스트 또는 `MENU` | 메인 메뉴 리스트: 신규 신청 / 상태 조회 / 도움말 | `MAIN_MENU` |
| 신규 신청 | 버튼: 예, 생성 / 취소 | `NEW_APPLICATION_CONFIRM` |
| 예, 생성 | 신청번호 `APP-YYYY-NNNNNN` + 1회용 폼 링크(24시간) | `MAIN_MENU` |
| 상태 조회 | 해당 딜러의 신청 목록(최신순) | `STATUS_SELECT` |
| 신청 선택 | 상태 상세; 폼이 아직 필요하면 `새 링크 받기` / `지금 재제출` 버튼 | `MAIN_MENU` |

등록되지 않은 번호가 보내면 정중한 거절 메시지를 보냅니다.

---

## 3. 고객 여정 (Step 3)

딜러가 신청서를 만들면 봇은 고객에게 전달할 메시지도 함께 보냅니다.
링크 형식은 `https://wa.me/<비즈니스 번호>?text=START%20<신청번호>`이며, 비즈니스 번호는
`WHATSAPP_BUSINESS_PHONE` 또는 (비어 있으면) 첫 웹훅의 `display_phone_number`에서 가져옵니다.

| 고객 입력 | 봇 동작 | 세션 상태 |
| --- | --- | --- |
| `START APP-2026-001001` | 전화번호를 신청서에 연결, 동의 문구 + 동의 / 거절 버튼 전송 | `AWAITING_CONSENT` |
| 동의 (시각 저장) | "Step 1 of 2: KTP 사진을 보내 주세요." | `AWAITING_KTP` |
| 이미지 | `GET /{media-id}`로 다운로드 → `data/documents/<신청번호>/KTP-<ts>.jpg` 저장 → "Received: KTP ✅ (1 of 2)" → 문서 검사 실행 | |
| 검사 PASS | "Step 2 of 2: 셀피를 보내 주세요 ..." | `AWAITING_SELFIE` |
| 검사 FAIL | "❌ KTP could not be accepted: Photo is blurry. Step 1 of 2: ..." (같은 항목 재요청) | 변경 없음 |
| 이미지 대신 텍스트 | 현재 요청을 다시 안내 | 변경 없음 |
| 셀피 PASS | "Checking your data..." → 목업 Dukcapil + PEFINDO 실행 → 결과 저장 → 상태 `READY_FOR_REVIEW` → 고객에게 제출 완료, 딜러에게 요약 전송 | `COMPLETED` |
| `CANCEL` | 세션 취소; 딜러 번호라면 딜러 메뉴로 복귀 | `CANCELLED` |

고객 세션은 `CUSTOMER_SESSIONS`에, 딜러 대화는 `CONVERSATIONS`에 따로 저장됩니다.
활성 세션이 있는 번호는 항상 고객 흐름으로 라우팅되므로, 딜러가 같은 번호로 고객 역할을 연기할 수 있습니다.

### 데모 제어 (`src/main/resources/demo-data.yml`)

- `StubDocumentCheck`는 `demo-data.check-delay`(2초)만큼 기다린 뒤 샘플 이름·NIK·생년월일·주소로 PASS를 반환합니다.
  기본 NIK는 4로 끝나(신용 점수 720) 리스크 등급 **A**와 "재무 정합성 우수" 배지가 나오고, 캡션 `nik1`은 645점으로 **B**, `nik7`은 불일치로 **C**(위조 경고)가 됩니다.
- 이미지 캡션에 `blur`가 있으면 "Photo is blurry"로 FAIL.
- 이미지 캡션에 `nik7`(임의 숫자)이 있으면 데모 NIK 마지막 자리를 덮어씁니다.
- 목업 제공자는 NIK 마지막 자리로 결과를 정합니다: 0~6 일치, 7 불일치, 8 미발견, 9 타임아웃. 1~3초 지연을 흉내 냅니다.
- `VERIFICATION_COUNTRY`(기본 `ID`)로 제공자 쌍을 고르며, 해당 국가의 제공자가 없으면 앱이 기동을 거부합니다.

---

## 4. Claude 문서 분석과 리뷰 에이전트 (`DOCUMENT_CHECK=claude`)

`DOCUMENT_CHECK=claude`와 `ANTHROPIC_API_KEY`를 설정하면 스텁 대신 Claude API 두 단계가 동작합니다.
기본 모델은 `claude-opus-5-5`이고 `CLAUDE_MODEL`로 바꿀 수 있습니다.

1. **`DocumentAnalysisService`** — 모든 이미지를 비전으로 보고 스키마가 강제된 JSON을 반환합니다.
   감지된 문서 종류, 품질 문제(흐림 / 반사 / 잘림 / 어두움 / 가림 / 저해상도 / 화면 촬영, 각각 심각도 포함),
   KTP 각 필드와 신뢰도, 자유 메모가 들어 있습니다. 규칙 적용은 모델이 아니라 코드가 합니다.
   - `claude.min-field-confidence`(0.85) 미만 필드는 **담당자 검토 필요**로 표시하고 사용하지 않습니다.
   - NIK는 16자리, 알려진 도(province) 코드, 0이 아닌 시·군 코드, 7~12자리에 인코딩된 생년월일과 카드 인쇄 생년월일 비교
     (여성은 일 +40, 성별 표기와의 일관성 확인), 0이 아닌 일련번호를 검사합니다.
   - HIGH 심각도 문제나 잘못된 문서 종류는 검사 실패이며 고객에게 즉시 그 사진을 다시 요청합니다.
   - 분석 결과는 `DOCUMENTS.analysis_json`에 저장됩니다.
2. **리뷰 에이전트 (`ClaudeReviewAgent`)** — 두 문서가 모두 수락되면 한 번 실행됩니다. 케이스 파일을 읽고
   두 도구 중 하나를 반드시 호출합니다.
   - `request_documents`: 고객에게 어떤 문서가 왜 필요한지 WhatsApp으로 보내고, 기존 기록은 superseded로 표시하며 요청된 단계만 반복합니다.
   - `write_summary`: 담당자용 한 페이지 요약을 `APPLICATIONS.review_summary`에 저장하고 `GET /admin/applications/{number}`의 `review`에 노출합니다.
   - 코드 가드레일: 문서가 없거나 쓸 수 없으면 남은 라운드 안에서 항상 재요청, `claude.max-review-rounds`(2) 이후에는 반드시 요약 작성,
     모델·API 실패 시 규칙 기반 판단과 템플릿 요약으로 폴백. NIK를 확신 있게 읽지 못하면 제공자 조회를 건너뛰고
     `officer_review_required = true`로 `READY_FOR_REVIEW`에 두며 딜러에게 심사팀 검토 중이라고 알립니다.

Opus 5.5는 강제 tool choice를 거부하므로 에이전트는 프롬프트로 유도하고 코드에서 검증합니다.
Claude 서버 측 거절 폴백은 켜지 않았고, 거절은 "담당자 검토 필요"로 처리합니다.

---

## 5. Risk Flag · 고객 리스크 등급 · 통계 대시보드 (Step 5)

> **Q. 단순 서류 확인 외에 Fraud/위조 감지 경고(Risk Flag) 화면과 "리스크 있는 고객" 요약(고객 리스크 등급 AI 표시)이 포함되어 있는가?**
>
> **A. 네, Step 5부터 포함됩니다.** Step 3b까지는 문서 분석이 "사진을 다시 찍어 달라"는 품질 판정과 담당자 검토 플래그에만 쓰였고,
> 위조·사기 신호를 모아 보여 주는 화면은 없었습니다. Step 5에서 모든 신청에 대해 **Risk Flag 목록, 0~100 리스크 점수, A~D 리스크 등급,
> 고객 배지**를 계산해 저장하고, `/dashboard`에 "Risk Flag · 위조/사기 감지 경고" 섹션과 국가별·등급별 통계로 표시합니다.
> 등급은 Claude 비전 분석이 찾아낸 근거(화면 재촬영, 변조, 서류 종류 불일치, NIK 구조 오류, 셀피 신분증 불일치 등)와 신원·신용 조회,
> 고객 행동을 **코드의 규칙**이 합산해 산출합니다. 모델이 등급을 직접 정하지 않으므로 모든 등급은 설명 가능하고 재계산할 수 있습니다.
> 스텁 모드에서는 비전 근거가 없어 조회 결과·행동 규칙만 적용되며, 대시보드에 평가 방식("Claude 비전 + 규칙" / "규칙(스텁)")을 함께 표시합니다.

### 5.1 Risk Flag (위조·사기 감지 경고)

| 코드 | 출처 | 심각도 | 의미 | 위조 신호 |
| --- | --- | --- | --- | --- |
| `SCREEN_OR_COPY` | 서류 분석 | 높음 (LOW 품질 이슈면 중간) | 화면 재촬영 또는 복사본으로 보임 | ⚠ |
| `TAMPERING_SUSPECTED` | 서류 분석 | 중간 | 서류 일부가 가려지거나 변조된 흔적 | ⚠ |
| `WRONG_DOCUMENT_TYPE` | 서류 분석 | 중간 | 접수된 서류가 요청한 종류와 다름 | ⚠ |
| `INVALID_ID_NUMBER` | 서류 분석 | 높음 | NIK 16자리·지역 코드·생년월일 인코딩 검증 실패 | ⚠ |
| `ID_CHANGED_BETWEEN_UPLOADS` | 서류 분석 | 높음 | 재업로드 사이에 신분증 번호가 바뀜 | ⚠ |
| `SELFIE_ID_MISMATCH` | 서류 분석 | 높음 | 셀피에 든 신분증 번호와 제출 서류 번호가 다름 | ⚠ |
| `MULTIPLE_FACES` | 서류 분석 | 중간 | 셀피에 얼굴이 1명이 아님 | ⚠ |
| `LOW_CONFIDENCE_FIELDS` | 서류 분석 | 낮음 | 핵심 필드 판독 신뢰도 낮음 | |
| `IDENTITY_MISMATCH` | 신원 조회 | 높음 | 등록부 데이터와 서류 불일치 | ⚠ |
| `IDENTITY_NOT_FOUND` | 신원 조회 | 높음 | 등록부에 없는 번호 | |
| `IDENTITY_UNVERIFIED` | 신원 조회 | 중간 | 조회 시간 초과 | |
| `CREDIT_IDENTITY_MISMATCH` | 신용 조회 | 중간 | 신용 기록의 신원 불일치 | |
| `NO_CREDIT_HISTORY` / `LOW_CREDIT_SCORE` / `CREDIT_UNVERIFIED` | 신용 조회 | 낮음 / 중간 / 낮음 | 기록 없음 / 650점 미만 / 시간 초과 | |
| `REPEATED_RESUBMISSION` / `MANY_FAILED_UPLOADS` / `AGE_OUT_OF_RANGE` | 고객 행동 | 중간 / 낮음 / 중간 | 재요청 2회 이상 / 검사 실패 3회 이상 / 연령 17~80세 밖 | |

### 5.2 고객 리스크 등급 (AI 표시)

- 점수 = 플래그 심각도 합계 (낮음 10, 중간 20, 높음 35, 최대 100).
- **A 저위험** 0~14 · **B 중위험** 15~39 · **C 고위험** 40~69 · **D 위험 고객** 70 이상. 심각도 "높음"의 위조 신호가 하나라도 있으면 최소 C.
- 저장 위치: `APPLICATIONS.risk_grade / risk_score / risk_flags_json / risk_method / customer_badges / risk_assessed_at`.
- 계산 시점: 고객 서류가 모두 접수되어 조회가 끝난 직후(담당자 인계 경로 포함). 딜러에게 가는 "Documents submitted" 메시지에도
  `Risk grade: C (High risk) - ⚠️ 1 fraud/forgery warning`과 배지가 한 줄로 들어갑니다.
- 재계산: `POST /admin/applications/{number}/assess-risk`(한 건), `POST /admin/risk/reassess-all`(규칙 변경 후 전체).

### 5.3 고객 배지 (요약 정보)

| 배지 | 조건 |
| --- | --- |
| **재무 정합성 및 담보 안정성 우수 고객** | 신원 조회 일치 + 신용 기록 일치 및 점수 700 이상 + 위조 신호 없음 + 등급 A. 담보 데이터는 아직 수집하지 않으므로 현재는 신용·신원 정합성으로 판정하며, 담보 필드가 추가되면 조건에 합류합니다. |
| **우수 딜러 유입 고객** | 신청을 만든 딜러의 등급이 `EXCELLENT` (`DEMO_DEALER_n_TIER`) |
| **신원 확인 완료 고객** | 신원 조회 일치 + 위조 신호 없음 |

### 5.4 국가별 통계 (Indonesia · Myanmar · Cambodia)

- 딜러에 `country`(ID / MM / KH)와 `tier`가 생겼고, 신청서는 생성 시점에 딜러의 국가를 복사합니다(`APPLICATIONS.country`).
- 신원·신용 조회는 **신청서 국가**에 맞는 제공자 쌍을 고릅니다. ID = Dukcapil + PEFINDO 목업, MM = Myanmar NRC registry + Myanmar Credit Bureau 목업,
  KH = Cambodia GDI registry + Credit Bureau Cambodia 목업. 모두 NIK 마지막 자리 규칙을 공유합니다. 제공자가 없는 국가는 `VERIFICATION_COUNTRY` 기본값으로 폴백합니다.
- 대시보드의 국가별 표는 세 나라를 항상 표시합니다(데이터가 없어도 0으로). 열: 딜러 수, 신청 수, 진행 중, 심사 대기, 승인, 거절, 고위험(C·D), 위조 경고,
  신원 일치율, 평균 신용 점수, 등급 A/B/C/D 분포, 배지 수.

### 5.5 대시보드 사용법

1. 앱 실행 후 브라우저에서 `http://localhost:8080/dashboard` (또는 ngrok 도메인 `/dashboard`)을 엽니다. 페이지 자체는 공개지만 데이터가 없습니다.
2. `ADMIN_API_KEY`를 입력하고 **불러오기**를 누르면 `GET /admin/dashboard/stats`를 `X-Admin-Key` 헤더로 호출합니다. 키는 브라우저 세션 저장소에만 남고 60초마다 자동 갱신됩니다.
3. 섹션: 요약 타일(총 신청·진행 중·심사 대기·승인·거절·고위험·위조 경고·담당자 검토), 국가별 신청 현황(상태 누적 막대), 리스크 등급 분포, 국가별 통계 표,
   **Risk Flag · 위조/사기 감지 경고**(고위험 고객 목록: 등급·점수·플래그 칩·배지·평가 방식), Risk Flag 발생 건수, 고객 배지 요약, 온보딩 퍼널, 딜러별 현황, 최근 신청.
4. 차트는 외부 라이브러리 없이 인라인 SVG로 그리며 라이트/다크 모드와 색각 이상 검증(국가 3색: 파랑·주황·청록, 등급은 상태색 + 아이콘 + 라벨)을 거쳤습니다.

통계 JSON(`GET /admin/dashboard/stats`) 최상위 키: `totals`, `funnel`, `byCountry`, `riskGrades`, `flagCounts`, `badges`, `dealers`, `highRiskApplications`, `recent`.

---

## 6. 백오피스 이벤트와 Admin API

딜러에게 WhatsApp으로 푸시되는 이벤트:

- 1회용 링크로 폼 제출 → "Application received, under review"
- `POST /admin/applications/{number}/request-resubmission` + `{"reason": "..."}` → 새 1회용 링크와 함께 재제출 요청
- `POST /admin/applications/{number}/decision` + `{"decision": "APPROVED"|"REJECTED", "note": "..."}` → 결정 알림

Admin 엔드포인트(`GET /admin/dealers`, `GET /admin/applications`, `GET /admin/applications/{number}`, `GET /admin/dashboard/stats`,
`POST /admin/applications/{number}/assess-risk`, `POST /admin/risk/reassess-all`, 위 두 POST)는
`X-Admin-Key` 헤더가 `ADMIN_API_KEY`와 일치해야 합니다. `GET /admin/applications/{number}`는 문서별 감지 종류,
검사 결과, 담당자 검토 플래그, 저장된 분석, 그리고 `risk` 블록(등급·점수·플래그·배지·평가 방식)을 함께 반환합니다.
1회용 링크는 `GET /apply/{token}`을 한 번만 열 수 있고, 자리표시자 폼을 제출하면 신청서가 `SUBMITTED`로 바뀝니다.

---

## 7. 데이터 모델

테이블: `DEALERS`(국가·등급 포함), `CONVERSATIONS`(딜러당 1행, 현재 상태), `APPLICATIONS`(국가·리스크 등급·플래그·배지 포함), `CUSTOMER_SESSIONS`, `DOCUMENTS`.
스키마는 `src/main/resources/schema.sql`에 있고, H2 파일은 `./data/`에 첫 기동 시 생성됩니다.

데모 딜러는 `wa.env`의 `DEMO_DEALER_n_PHONE` / `_NAME` / `_COUNTRY` / `_TIER`(n = 1~4)에서 읽어
기동 시 `DLR-001`~`DLR-004` 코드로 upsert합니다. 기본값은 1·2번 인도네시아(1번 `EXCELLENT`), 3번 미얀마, 4번 캄보디아이며,
전화번호가 비어 있으면 경고만 남기고 건너뜁니다.

코드 구조 (`src/main/kotlin/com/nextasean/wawebhook/`):

| 패키지 | 역할 |
| --- | --- |
| `webhook/` | 수신 웹훅, 서명 검증, 중복 제거, 비동기 처리 |
| `conversation/` | 딜러 상태 머신, 메시지 라우터, 딜러 알림 |
| `customer/` | 고객 세션 상태 머신과 문구 |
| `documents/`, `documents/analysis/` | 미디어 다운로드·저장, 스텁/Claude 문서 검사, NIK 검증 |
| `review/` | 리뷰 에이전트(Claude / 규칙 기반), 요약 템플릿 |
| `risk/` | Risk Flag 규칙(`RiskRules`), 등급·배지 모델, 평가 저장 서비스 |
| `verification/` | 신원·신용 조회 제공자 레지스트리(목업) |
| `application/` | 신청서 생성, 1회용 링크, 상태 전환 |
| `admin/`, `web/` | Admin API와 키 필터, 대시보드 통계 API, 1회용 링크·대시보드 페이지 (`static/dashboard.html`) |
| `whatsapp/` | Graph API 클라이언트, 발신 메시지 모델 |
| `config/` | 환경 변수 바인딩, Claude 설정 |

---

## 8. 설정

작업 디렉터리에 `wa.env`(또는 `.env`)를 두고 `KEY=VALUE` 형식으로 작성합니다(`wa.env.example` 참고).
같은 이름의 실제 환경 변수가 있으면 그것이 우선합니다.

| 키 | 용도 |
| --- | --- |
| `WHATSAPP_PHONE_NUMBER_ID` | Graph API `/{version}/{phone-number-id}/messages` 엔드포인트 |
| `WHATSAPP_ACCESS_TOKEN` | 발신 메시지 Bearer 토큰 |
| `WHATSAPP_APP_SECRET` | 수신 POST의 `X-Hub-Signature-256` 검증 |
| `WHATSAPP_VERIFY_TOKEN` | GET 핸드셰이크의 `hub.verify_token`과 비교 |
| `DEMO_DEALER_n_PHONE`, `_NAME`, `_COUNTRY`, `_TIER` (n = 1~4) | 데모 딜러 WhatsApp 번호(숫자만), 이름, 국가(ID/MM/KH), 등급(STANDARD/EXCELLENT) |
| `ADMIN_API_KEY` | `/admin/**` 공유 키 |
| `APP_PUBLIC_BASE_URL` | 1회용 링크의 기본 URL(기본값은 ngrok 도메인) |
| `WHATSAPP_BUSINESS_PHONE` | wa.me 링크용 비즈니스 번호(선택, 없으면 웹훅에서 학습) |
| `VERIFICATION_COUNTRY` | 제공자가 없는 국가의 폴백 제공자 쌍(기본 `ID`); 평소에는 신청서 국가로 선택 |
| `DOCUMENT_CHECK` | `stub`(기본) 또는 `claude` |
| `ANTHROPIC_API_KEY` | `DOCUMENT_CHECK=claude`일 때 필수 |
| `CLAUDE_MODEL` | Claude 모델(기본 `claude-opus-5-5`) |

필수 값이 비어 있으면 앱이 기동을 거부합니다. 시크릿은 로그에 남기지 않습니다.
선택 설정은 `src/main/resources/application.yml`에 있습니다
(`whatsapp.graph-api-version`, `webhook.dedupe-cache-size`, `server.port`, `claude.*`).

---

## 9. 실행

JDK 21 이상이 `PATH`에 있어야 합니다. Gradle은 설치할 필요 없이 래퍼를 사용합니다.

```bash
./gradlew bootRun
```

또는 jar를 한 번 빌드해서 실행합니다.

```bash
./gradlew bootJar
java -jar build/libs/wa-webhook-0.0.1-SNAPSHOT.jar
```

서버는 8080 포트에서 대기합니다.

---

## 10. 로컬 테스트

단위·통합 테스트 실행:

```bash
./gradlew test
```

검증 핸드셰이크(`<verify-token>`은 `wa.env` 값으로 치환):

```bash
curl -i -G http://localhost:8080/webhook \
  --data-urlencode hub.mode=subscribe \
  --data-urlencode 'hub.verify_token=<verify-token>' \
  --data-urlencode hub.challenge=1158201444
```

기대 결과: `200 OK`, `Content-Type: text/plain`, 본문 `1158201444`. 토큰이 틀리면 `403`.

서명된 POST(배달 완료 상태를 흉내 내며 외부 호출은 없음):

```bash
BODY='{"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","statuses":[{"id":"wamid.TEST","status":"delivered","timestamp":"1700000000","recipient_id":"15550001111"}]}}]}]}'
SECRET=$(grep '^WHATSAPP_APP_SECRET=' wa.env | cut -d= -f2-)
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
curl -i -X POST http://localhost:8080/webhook \
  -H 'Content-Type: application/json' \
  -H "X-Hub-Signature-256: sha256=$SIG" \
  -d "$BODY"
```

기대 결과: `200 OK`와 `Status update | messageId=wamid.TEST | status=delivered ...` 로그.
서명이 틀리면 `401`.

Admin API와 대시보드 통계 확인:

```bash
curl -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/applications
curl -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/dashboard/stats
open http://localhost:8080/dashboard
```

---

## 11. ngrok으로 Meta 연결

1. 앱 실행: `./gradlew bootRun`
2. 다른 터미널에서 예약 도메인으로 ngrok 실행:
   `ngrok http --url=amaretto-tipoff-excess.ngrok-free.dev 8080`
   (구버전 ngrok은 `--url=` 대신 `--domain=`)
3. 터널 확인: `curl -i "https://amaretto-tipoff-excess.ngrok-free.dev/webhook?hub.mode=subscribe&hub.verify_token=<verify-token>&hub.challenge=42"`
   가 `42`를 반환해야 합니다.
4. Meta 대시보드(developers.facebook.com > My Apps > 앱 > WhatsApp > Configuration):
   - Callback URL: `https://amaretto-tipoff-excess.ngrok-free.dev/webhook`
   - Verify token: `WHATSAPP_VERIFY_TOKEN` 값
   - "Verify and save" 클릭 → 앱 로그에 `Webhook verification succeeded`
   - Webhook fields에서 "Manage" → `messages` 구독
5. WhatsApp > API Setup에서 개인 번호를 테스트 번호의 허용 수신자로 추가한 뒤 메시지를 보내면
   로그에 수신 메시지가 찍히고 봇 응답이 도착합니다.

---

## 12. 다음 할 일 (요약)

자세한 우선순위는 `NEXT_STEPS.md`를 보세요.

- **A. 기존 기능 강화**: 실 Meta 미디어 다운로드 검증, 읽음 표시·입력 중 표시, Graph API 재시도와 `OUTBOUND_LOG`, 미등록 번호 거절 속도 제한, 세션 타임아웃
- **B. Step 4 심사 / 백오피스**: 심사자 UI(대시보드에서 신청 상세·서류 열람·결정까지), 고객에게 결정 알림, 고객 직접 재제출
- **B2. Step 5 후속**: 담보 데이터 수집 후 "담보 안정성" 배지 조건 반영, Claude에게 리스크 요약 문장 생성 위임(현재 등급·플래그는 규칙 기반), 기간 필터와 추세 차트, 실 KTP로 임계값 튜닝
- **C. 실 연동**: `ANTHROPIC_API_KEY`로 라이브 테스트 후 `DOCUMENT_CHECK=claude` 실사용, 실 Dukcapil / PEFINDO 클라이언트, 병렬 조회와 실제 타임아웃
- **D. 운영 준비**: PostgreSQL + Flyway, 영속 중복 제거 저장소, 시크릿·문서 암호화, 관측성(Actuator), 고정 HTTPS 도메인
- **E. 제품 다듬기**: 인도네시아어 현지화, 24시간 창 밖 템플릿 메시지, 딜러 통계

---
---

<a id="english"></a>

# English

[한국어](#korean) | **English**

Spring Boot 3 (Kotlin, Gradle) webhook for the WhatsApp Cloud API.
_Current status (19 Oct 2026): Steps 1-3b and Step 5 (statistics dashboard with fraud/forgery risk flags, AI risk grade A-D, customer badges and per-country statistics for Indonesia, Myanmar and Cambodia) done, 69 tests passing (1 skipped live Claude test); Step 4 review UI and production hardening not started. See the Korean section above or `NEXT_STEPS.md` for the full status table._

## Step 5 - Risk flags, customer risk grade and the statistics dashboard

**Does the product show fraud / forgery warnings (risk flags) and a "risky customer" summary beyond the plain document check?**
Yes, since Step 5. Every application that completes the document journey gets a list of `RiskFlag`s, a 0-100 score, a grade
**A (low) / B (medium) / C (high) / D (critical)** and customer badges, stored on `APPLICATIONS` and shown on `/dashboard`
(section "Risk Flag - fraud / forgery warnings"). The evidence comes from the Claude vision analysis (screen photo or photocopy,
tampering, wrong document type, structurally invalid NIK, ID changed between uploads, selfie holding a different ID, several
faces), the identity registry and credit bureau results, and customer behaviour (repeated resubmission, many failed uploads,
age out of range). The rules that turn evidence into flags and grades live in code (`risk/RiskRules.kt`), so every grade is
explainable and can be recomputed with `POST /admin/risk/reassess-all`. In stub mode only the provider and behaviour rules apply,
and the dashboard labels the method ("Claude vision + rules" vs "rules (stub)").

Badges: **Strong financial consistency and collateral stability** (identity match, credit match with score >= 700, no fraud
signal, grade A; collateral data is not collected yet), **Referred by an excellent dealer** (dealer tier `EXCELLENT`), **Identity verified**.

Countries: dealers carry `country` (ID / MM / KH) and `tier`; applications copy the dealer's country; verification picks the provider
pair for that country (Dukcapil + PEFINDO mocks for ID, mock NRC registry + credit bureau for MM, mock GDI registry + credit bureau for
KH). `GET /admin/dashboard/stats` returns totals, funnel, per-country statistics (always all three countries), grade distribution,
flag counts, badge counts, per-dealer counts, the high-risk list and recent applications. The page at `/dashboard` renders it with
inline SVG charts after you enter the admin key.

- `GET /webhook` answers Meta's verification handshake.
- `POST /webhook` validates `X-Hub-Signature-256` (HMAC-SHA256 with the app secret) over the raw body,
  returns `200` immediately and processes the event on a background thread.
- Every incoming message is logged (sender, type, text or media ID); status updates
  (sent / delivered / read / failed, with error codes) are logged too.
- Registered dealers get a conversation flow (interactive list menu and reply buttons) backed by a
  state machine in an embedded H2 database; unregistered numbers get a polite rejection.
- Redelivered message IDs are ignored (in-memory, bounded cache; not shared between instances).

## Dealer conversation flow

| Dealer action | Bot reply | Stored state |
| --- | --- | --- |
| any text, or `MENU` | main menu list: New application / Check status / Help | `MAIN_MENU` |
| New application | buttons: Yes, create / Cancel | `NEW_APPLICATION_CONFIRM` |
| Yes, create | application number `APP-YYYY-NNNNNN` plus a one-time form link (24 h) | `MAIN_MENU` |
| Check status | list of the dealer's applications (newest first) | `STATUS_SELECT` |
| pick an application | status detail; `Get new link` / `Resubmit now` button when a form is still expected | `MAIN_MENU` |

## Customer journey (Step 3)

When a dealer creates an application, the bot also sends a customer-facing message with a
`https://wa.me/<business number>?text=START%20<application number>` link to forward. The business number
comes from `WHATSAPP_BUSINESS_PHONE` or, when blank, from the `display_phone_number` of the first webhook.

| Customer sends | Bot | Session state |
| --- | --- | --- |
| `START APP-2026-001001` | links the phone to the application, sends consent text with I Agree / Decline buttons | `AWAITING_CONSENT` |
| I Agree (timestamp stored) | "Step 1 of 2: Please send a photo of your KTP." | `AWAITING_KTP` |
| image | downloads it via `GET /{media-id}`, stores it under `data/documents/<number>/KTP-<ts>.jpg`, replies "Received: KTP ✅ (1 of 2)", runs the document check | |
| check PASS | "Step 2 of 2: Please send a selfie ..." | `AWAITING_SELFIE` |
| check FAIL | "❌ KTP could not be accepted: Photo is blurry. Step 1 of 2: ..." (same item asked again) | unchanged |
| text instead of image | the current request is repeated | unchanged |
| selfie PASS | "Checking your data...", then mock Dukcapil + PEFINDO run, results saved, status `READY_FOR_REVIEW`, customer gets "Your documents are submitted", dealer gets a summary | `COMPLETED` |
| `CANCEL` | session cancelled; a dealer phone returns to the dealer menu | `CANCELLED` |

Customer sessions live in `CUSTOMER_SESSIONS`, separate from the dealer `CONVERSATIONS`. A phone with an
active session is always routed to the customer flow, so a dealer can play the customer with the same number.

Demo controls (see `src/main/resources/demo-data.yml`):

- `StubDocumentCheck` waits `demo-data.check-delay` (2 s) and returns PASS with the sample name, NIK, DOB and address.
- Image caption containing `blur` fails the check with "Photo is blurry".
- Image caption containing `nik7` (any digit) overrides the last digit of the demo NIK.
- Mock providers decide by the last NIK digit: 0-6 match, 7 mismatch, 8 not found, 9 timeout, with 1-3 s simulated latency.
- `VERIFICATION_COUNTRY` (default `ID`) selects the provider pair; the app refuses to start if none exist for that country.

### Claude document analysis and review agent (`DOCUMENT_CHECK=claude`)

With `DOCUMENT_CHECK=claude` and `ANTHROPIC_API_KEY` set, the stub is replaced by two Claude API steps
(model `claude-opus-5-5` by default, `CLAUDE_MODEL` to override):

1. **`DocumentAnalysisService`** looks at every image with vision and returns structured JSON (schema-enforced):
   detected document type, quality issues (blur / glare / cropped / too dark / obstructed / low resolution / screen photo,
   each with a severity), every KTP field with a confidence, and free-text notes. The code, not the model, then applies
   the rules: a field below `claude.min-field-confidence` (0.85) is marked **needs officer review** and never used;
   the NIK is checked for 16 digits, a known province code and non-zero regency/district codes, the date of birth
   encoded in digits 7-12 against the date printed on the card (day +40 for women, and the +40 rule against the stated
   gender), and a non-zero serial. A HIGH severity issue or the wrong document type fails the check, so the customer is
   asked for that photo again immediately. The analysis is stored on the `DOCUMENTS` row (`analysis_json`).
2. **Review agent** (`ClaudeReviewAgent`) runs once both documents are accepted. It reads the case file and must call one
   of two tools: `request_documents` (the customer gets a WhatsApp message naming the documents and the reason, the old
   records are marked superseded, and only the requested steps are repeated) or `write_summary` (a one-page summary for
   the officer, stored in `APPLICATIONS.review_summary` and shown under `review` in `GET /admin/applications/{number}`).
   Guardrails in code: a missing or unusable document is always requested while rounds remain, after
   `claude.max-review-rounds` (2) the agent must write the summary, and any model or API failure falls back to a
   rule-based decision with a template summary. When the NIK was not confidently read, no provider check is run; the
   application goes to `READY_FOR_REVIEW` with `officer_review_required = true` and the dealer is told it is with the
   review team.

`GET /admin/applications/{number}` now also lists the documents with the detected type, check result, officer-review
flag and the stored analysis. Opus 5.5 rejects forced tool choice, so the agent is steered by prompt and validated in
code; Claude's server-side refusal fallback is not enabled, a refusal is treated as "needs officer review".

Back-office events are pushed to the dealer on WhatsApp:

- form submitted through the one-time link: "Application received, under review"
- `POST /admin/applications/{number}/request-resubmission` with `{"reason": "..."}`: resubmission request with a fresh one-time link
- `POST /admin/applications/{number}/decision` with `{"decision": "APPROVED"|"REJECTED", "note": "..."}`: decision notification

Admin endpoints (`GET /admin/dealers`, `GET /admin/applications`, and the two above) require the
`X-Admin-Key` header matching `ADMIN_API_KEY`. The one-time link opens `GET /apply/{token}` once and
shows a placeholder form whose submission marks the application `SUBMITTED`.

Tables: `DEALERS`, `CONVERSATIONS` (one row per dealer with the current state), `APPLICATIONS`, `CUSTOMER_SESSIONS`, `DOCUMENTS`.
The H2 file lives in `./data/` and is created on first start.

Demo dealers come from `DEMO_DEALER_1_PHONE` / `DEMO_DEALER_2_PHONE` (and `_NAME`) in `wa.env`
and are upserted at startup under codes `DLR-001` / `DLR-002`. Entries without a phone are skipped
with a warning.

## Configuration

Put a `wa.env` (or `.env`) file in the working directory with plain `KEY=VALUE` lines
(see `wa.env.example`). Real environment variables with the same names take precedence.

| Key | Used for |
| --- | --- |
| `WHATSAPP_PHONE_NUMBER_ID` | Graph API `/{version}/{phone-number-id}/messages` endpoint |
| `WHATSAPP_ACCESS_TOKEN` | Bearer token for outgoing messages |
| `WHATSAPP_APP_SECRET` | Verifying `X-Hub-Signature-256` on incoming POSTs |
| `WHATSAPP_VERIFY_TOKEN` | Compared with `hub.verify_token` on the GET handshake |
| `DEMO_DEALER_1_PHONE`, `DEMO_DEALER_2_PHONE` | WhatsApp numbers (digits only) of the two demo dealers |
| `ADMIN_API_KEY` | Shared key for the `/admin/**` endpoints |
| `APP_PUBLIC_BASE_URL` | Base URL in one-time links (defaults to the ngrok domain) |
| `WHATSAPP_BUSINESS_PHONE` | Business number for wa.me links (optional, learned from webhooks otherwise) |
| `VERIFICATION_COUNTRY` | Provider pair for identity and credit checks (default `ID`) |
| `DOCUMENT_CHECK` | `stub` (default) or `claude` for Claude vision analysis and the review agent |
| `ANTHROPIC_API_KEY` | Claude API key, required when `DOCUMENT_CHECK=claude` |
| `CLAUDE_MODEL` | Claude model (default `claude-opus-5-5`) |

The app refuses to start if any of these is missing. Secrets are never logged.
Optional overrides live in `src/main/resources/application.yml`
(`whatsapp.graph-api-version`, `webhook.dedupe-cache-size`, `server.port`).

## Run

Requires JDK 21 or newer on the `PATH` (no Gradle install needed).

```bash
./gradlew bootRun
```

or build a jar once and run it:

```bash
./gradlew bootJar
java -jar build/libs/wa-webhook-0.0.1-SNAPSHOT.jar
```

The server listens on port 8080.

## Test locally

Run the unit and integration tests:

```bash
./gradlew test
```

Verification handshake (replace `<verify-token>` with the value from `wa.env`):

```bash
curl -i -G http://localhost:8080/webhook \
  --data-urlencode hub.mode=subscribe \
  --data-urlencode 'hub.verify_token=<verify-token>' \
  --data-urlencode hub.challenge=1158201444
```

Expected: `200 OK`, `Content-Type: text/plain`, body `1158201444`. A wrong token returns `403`.

Signed POST (simulates a delivered status, no outbound call is made):

```bash
BODY='{"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","statuses":[{"id":"wamid.TEST","status":"delivered","timestamp":"1700000000","recipient_id":"15550001111"}]}}]}]}'
SECRET=$(grep '^WHATSAPP_APP_SECRET=' wa.env | cut -d= -f2-)
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
curl -i -X POST http://localhost:8080/webhook \
  -H 'Content-Type: application/json' \
  -H "X-Hub-Signature-256: sha256=$SIG" \
  -d "$BODY"
```

Expected: `200 OK` and a `Status update | messageId=wamid.TEST | status=delivered ...` log line.
Without a valid signature the endpoint returns `401`.

## Connect to Meta via ngrok

1. Start the app: `./gradlew bootRun`.
2. In another terminal, start ngrok on your reserved domain:
   `ngrok http --url=amaretto-tipoff-excess.ngrok-free.dev 8080`
   (older ngrok versions use `--domain=` instead of `--url=`).
3. Check the tunnel: `curl -i "https://amaretto-tipoff-excess.ngrok-free.dev/webhook?hub.mode=subscribe&hub.verify_token=<verify-token>&hub.challenge=42"`
   should return `42`.
4. In the Meta dashboard (developers.facebook.com > My Apps > your app > WhatsApp > Configuration):
   - Callback URL: `https://amaretto-tipoff-excess.ngrok-free.dev/webhook`
   - Verify token: the value of `WHATSAPP_VERIFY_TOKEN`
   - Click "Verify and save"; the app log prints `Webhook verification succeeded`.
   - Under Webhook fields click "Manage" and subscribe to `messages`.
5. WhatsApp > API Setup: add your personal number to the allowed recipients for the test number,
   then send a message from your phone to the test number. The log shows the incoming message
   and the `Echo: ...` reply arrives on your phone.
