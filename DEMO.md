# NEXUS ASEAN WhatsApp onboarding - demo runbook

Step-by-step script for a live demo of the dealer flow, the customer document journey, the automated checks and the
back office, played on **two screens at once**: the dashboard on the left, the review page on the right, the customer's
WhatsApp on a phone. Both pages refresh themselves every 15 seconds and the open application's AI timeline every
2 seconds, so nothing is ever reloaded by hand during the demo. Total running time is about 12 minutes. Everything below
assumes the Meta webhook is already registered on the axhub deployment `https://nexus.jbax.axhub.ai/webhook`, so the
deployed app only has to be healthy.

---

## 0. What you need on the table

| Item | Why |
| --- | --- |
| Laptop with JDK 21+ and this repo | runs the app on port 8080 |
| The deployed app at `https://nexus.jbax.axhub.ai` | serves the webhook, review page and dashboard (no ngrok tunnel needed) |
| A wide screen or projector with **two browser windows side by side** | left `/dashboard`, right `/review` (section 2b) |
| **Dealer phone**: WhatsApp number listed as `DEMO_DEALER_1_PHONE` in `wa.env` | plays the dealer |
| **Customer phone**: any other WhatsApp number added as a test recipient in the Meta dashboard | plays the customer |
| A KTP photo (or any card photo) and a selfie on the customer phone | in stub mode the content does not matter |
| Terminal with `curl` | back-office events (`/admin/**`) |

Both phones must be in the "allowed recipients" list of the Meta test number (WhatsApp > API Setup), otherwise
outgoing messages fail with a 131030 error.

---

## 1. The day before: configuration (5 minutes)

1. Open `wa.env` and check these lines are filled (plain `KEY=VALUE`, no quotes):

   ```
   WHATSAPP_PHONE_NUMBER_ID=...
   WHATSAPP_ACCESS_TOKEN=...        # see step 2
   WHATSAPP_APP_SECRET=...
   WHATSAPP_VERIFY_TOKEN=...
   DEMO_DEALER_1_PHONE=62812xxxxxxx # digits only, with country code
   DEMO_DEALER_1_NAME=...
   DOCUMENT_CHECK=stub              # or claude, see section 6
   ```

2. **Access token.** The temporary token from the Meta dashboard expires after 24 hours. For a demo, generate a
   System User token (Business Settings > System Users > Generate token, permissions `whatsapp_business_messaging`
   and `whatsapp_business_management`) and paste it as `WHATSAPP_ACCESS_TOKEN`. If outgoing messages log `HTTP 401`
   during the demo, this is why.

3. Run the tests once so the build cache is warm and nothing surprises you on the day:

   ```bash
   ./gradlew test
   ```

   Expected: `BUILD SUCCESSFUL`, 88 tests, 1 skipped (the live Claude test).

4. Optional but recommended: wipe the local database so application numbers start fresh at `APP-2026-001001`:

   ```bash
   rm -rf data/wa-webhook.mv.db data/documents
   ```

5. Recommended for the dashboard: add `DEMO_SEED_APPLICATIONS=true` to `wa.env` so the first start creates about 40 fake
   applications (three countries, eight weeks of history, risk grades, decisions, agent timelines). The live application you
   create on stage then gets the next number after the demo rows. To regenerate or remove them later:

   ```bash
   curl -s -X POST -H 'Content-Type: application/json' -d '{"count":40,"reset":true}' http://localhost:8080/admin/demo/seed
   curl -s -X DELETE http://localhost:8080/admin/demo/seed
   ```

---

## 2. Thirty minutes before: start everything (3 minutes)

Terminal 1, the app:

```bash
./gradlew bootRun
```

Wait for `Started WaWebhookApplicationKt` and the line `Verification providers | country=ID | id=Dukcapil (mock) | credit=PEFINDO (mock)`.
Also check the seeding log: `Demo dealer upserted | code=DLR-001 ...`. If it says a dealer was skipped, the phone in
`wa.env` is blank.

Terminal 2 is no longer a tunnel: the app is deployed at `https://nexus.jbax.axhub.ai` (a push to `main` redeploys it,
`axhub deploy create --app nexus --execute` redeploys the same code). Check it is up:

```bash
curl -i https://nexus.jbax.axhub.ai/healthz
```

Terminal 3, smoke test the webhook on the deployment (replace the token):

```bash
curl -i "https://nexus.jbax.axhub.ai/webhook?hub.mode=subscribe&hub.verify_token=<WHATSAPP_VERIFY_TOKEN>&hub.challenge=42"
```

Expected: `200` with body `42`. Then send `hi` from the dealer phone to the business number. If the main menu comes
back, you are ready. Keep Terminal 1 visible on the projector: the structured log lines are part of the story.

---

## 2b. Ten minutes before: the two screens (2 minutes)

Lay the projector out once and leave it; every later step only says *left* or *right*.

| Screen | Open | Then | Keeps itself current |
| --- | --- | --- | --- |
| **Left: dashboard** | `https://nexus.jbax.axhub.ai/dashboard` | click the **진행·대기 큐** tab | every 15 s (the pulse "15초마다 자동 갱신" in the header) |
| **Right: review page** | `https://nexus.jbax.axhub.ai/review` | type your name as reviewer, stay on **심사 작업** | queue and statistics every 15 s; an open application's AI timeline every 2 s |
| **Phone** | the customer's WhatsApp, mirrored if you can | | |

Checks before the audience arrives:

1. Pick the language once with the **한국어 / English** button at the top right of each page; both remember it. The review
   summaries, flag names, tier criteria and copilot answers all follow the switch.
   The left page starts on **진행·대기 큐** for Acts 1 to 3 (pipeline board, every open application's stage, the review queue) and
   moves to **개요** for the tiles, the weekly chart and the copilot in Acts 3 and 4.
2. The left queue and the right queue show the same applications; the demo data (section 1, step 5) gives both something to show.
3. Both pages pause their refresh while their tab is hidden, so keep both windows visible. The review queue also skips a refresh
   while you are typing in an open application, so a note never gets wiped mid-sentence.
4. Resize so the left window shows the summary tiles and the queue at once, and the right window shows the queue list with
   the detail panel below it.

What the audience will see without anyone touching the laptop: the application you create on the right appears on the
left within 15 seconds, walks through the pipeline stage by stage while the customer sends photos, lands in both queues at
the same moment, and leaves both when you decide.

---

## 3. Act 1 - start the application from the back office (1 minute, no dealer phone needed)

Use this when only one WhatsApp phone is available and it plays the customer. The dealer flow in 3b still works; it is just not needed.

| # | Do | Right: review page | Left: dashboard | Say |
| --- | --- | --- | --- | --- |
| 1 | on the right, open **➕ 새 신청 만들기 · 고객 WhatsApp 초대** at the top of the queue | the form shows phone, name, dealer, "send invite" | | "A back-office user prepares the application; the customer does everything else on WhatsApp." |
| 2 | enter the customer phone (digits with country code), a name, keep **WhatsApp 초대 메시지 전송** ticked, press **신청 생성** | the application number appears with a **QR code** and the `wa.me` START link; the customer phone receives *"Application APP-... has been prepared for you. Reply START to begin"* | within 15 s: the new number appears under **진행 중인 신청** at **신청 생성**, and the 온보딩 진행 현황 counter for that stage goes up by one | "The number is created under the demo dealer; the audit trail records who created it. The dashboard already knows; nobody refreshed anything." |
| 3 | on the customer phone reply **START** (or anything), or scan the QR code | click the new application in the queue and leave it open: the pinned **온보딩 진행 단계** bar at the top of the detail moves to 고객 연결 | the row under **진행 중인 신청** moves to **고객 연결** | "Any first message from the invited number starts the journey." |

Keep the application open on the right for the whole of Act 2: its progress bar and its **AI Agent 판단 타임라인** refresh every
2 seconds, so every step appears while the customer is still looking at the phone.

If the invite shows **✗ WhatsApp 전송 실패**, the customer phone has not messaged the business number in the last 24 hours
(Meta's free-form window). Send `hi` from the customer phone first; the invited number is recognised and the journey starts
right away. The QR code / link works regardless.

`curl` form: `POST /admin/applications` with `{"customerPhone":"628...","customerName":"Dewi","dealerCode":"DLR-001","invite":true}`.

## 3b. Act 1 (alternative) - the dealer creates an application (2 minutes)

| # | On the dealer phone | What comes back | Say |
| --- | --- | --- | --- |
| 1 | send `hi` | list menu "NEXUS ASEAN Dealer Desk": New application / Check status / Help | "Only registered dealer numbers get this menu; anyone else gets a polite rejection." |
| 2 | tap **New application** | buttons Yes, create / Cancel | |
| 3 | tap **Yes, create** | application number `APP-2026-001001` plus a one-time form link, then a second message to forward to the customer with a `wa.me/...?text=START APP-2026-001001` link | "The dealer never types anything; the link pre-fills the START message for the customer." |
| 4 | forward that second message to the customer phone | | |
| 5 | optional: tap **Check status** | list of applications with status Draft | |

Side note for a sceptical audience: send `hi` from a third, unregistered number and show the rejection text.

---

## 4. Act 2 - the customer journey (4 minutes)

Everything here happens on the **customer phone**; the two screens follow by themselves.

| # | Customer sends | Bot replies | Right: open application (timeline, 2 s) | Left: dashboard (15 s) |
| --- | --- | --- | --- | --- |
| 1 | tap the forwarded link, send `START APP-2026-001001` | greeting + consent text with **I Agree** / **Decline** | progress bar at 고객 연결 | row at **고객 연결** |
| 2 | tap **I Agree** | "Thank you. Step 1 of 2: Please send a photo of your KTP." | progress bar at 개인정보 동의 → 신분증 (consent is stored with a timestamp; the server log shows `Consent given ... at=`) | row at **신분증** |
| 3 | type `here you go` (text instead of a photo) | the request is simply repeated | nothing new: the flow is strict about what it expects | |
| 4 | send any photo with caption **`blur`** | "Received: KTP ✅ (1 of 2)" then "❌ KTP could not be accepted: Photo is blurry. Step 1 of 2: ..." | the timeline starts: 서류 수신 → 품질 확인 **실패** (blurry); the rejected photo appears under 서류 as a superseded upload | row stays at **신분증** |
| 5 | send the photo again with caption **`nik7`** | "Received: KTP ✅ (1 of 2)" then "Step 2 of 2: Please send a selfie of yourself holding your KTP..." | 서류 수신 → 품질 확인 → 필드 추출: Budi Santoso, NIK ending in 7; the **추출 데이터** card fills in | row at **셀피** |
| 6 | send a selfie | "Received: Selfie with KTP ✅ (2 of 2)", "Checking your data...", then after 2 to 6 seconds "✅ Your documents are submitted..." | 서류 수신 → 품질 확인 → 신원 조회 (Dukcapil mismatch) → 신용 조회 (PEFINDO mismatch) → 리스크 평가 (grade **C**, ⚠ IDENTITY_MISMATCH) → 심사 대기 전환; the review agent's summary appears in the header card, in the language of the page | within 15 s the row leaves the pipeline, **심사 대기** ticks up, the application is first in the **심사 대기 큐** with grade C and ⚠ 1; on the **리스크·사기** tab it is listed under 위조/사기 감지 경고 |

Point at the right screen while the photos are being checked and at the left screen after "Checking your data...": the
same application arrives in both queues within the same quarter minute. Say: "This is the officer's queue and the
manager's dashboard reading the same facts. The grade is computed by rules; the officer still decides."

At the same moment the **dealer phone** receives "Documents submitted" with customer name, masked NIK, ID verification
result and credit bureau result, plus a Check status button.

**Choosing the outcome.** The caption `nik<d>` sets the last digit of the demo NIK, and the mocks decide by that digit:

| Caption | Dukcapil | PEFINDO | Score |
| --- | --- | --- | --- |
| `nik0` to `nik6` (or no caption) | Match | Match | 620 + 25 x digit (e.g. `nik4` = 720) |
| `nik7` | Data mismatch | Data mismatch | none |
| `dup` | (previous customer's NIK) | | same as that customer; **both** applications get the ⚠ `SHARED_ID_NUMBER` flag (same ID card, different person) and drop to grade C |
| `nik8` | Not found | Not found | none |
| `nik9` | Timed out | Timed out | none |

Run the happy path (`nik4`) for the main demo and keep `nik7` or `nik9` for the "what if" question. Each provider
call takes 1 to 3 seconds, so the pause after "Checking your data..." is deliberate.

---

## 5. Act 3 - the back office on two screens (3 minutes)

The application is open on the right and sits at the top of both queues. Each decision on the right changes the left
within 15 seconds.

| # | Right: review page | Phone | Left: dashboard | Say |
| --- | --- | --- | --- | --- |
| 1 | walk down the open application: the KTP and selfie images (click to enlarge), 추출 데이터, Dukcapil / PEFINDO results, the **Risk Flag** card with its evidence, the officer timeline (서류 접수 완료, 리스크 평가) | | | "Everything the officer needs is on one page, with the evidence behind every flag." |
| 2 | open **자동 리뷰 요약** in the header card; flip the language button once | | | "The intake agent wrote the summary twice, Korean and English; the officer reads whichever language the page is in." |
| 3 | under **심사 결정 → 고객 서류 재요청** tick **신분증 (KTP)**, type `the photo is blurry`, press **📷 고객에게 재요청** | the customer receives "our review team ... needs the following again: KTP ..." and "Step 1 of 2"; the dealer gets a heads-up | within 15 s: it has left both queues and is back under **진행 중인 신청** at **신분증** (on the 개요 tab, **고객 서류 재요청 중** ticks up and **심사 대기** down) | "Sending the customer back is one click, and the manager sees it as a stage, not a lost application." |
| 4 | keep the application open | send a new KTP photo (caption `nik4` for the happy path) | the checks and lookups run again (timeline, 2 s); the application returns to both queues, now grade **A** with the badge **재무 정합성 및 담보 안정성 우수 고객** | "Same customer, usable ID: match on both registries, score 720, grade A." |
| 5 | type a note and press **승인** | the dealer gets "Application approved"; the customer gets the approval message with the note | within 15 s: the application leaves the **심사 대기 큐**; switch the left to **개요**: **승인** ticks up and the current week's bar in 주간 추이 grows by one | "The decision is a person's, with a name and a note, and the dashboard reflects it in the same quarter minute." |
| 6 | press **📄 고객 리포트**, then **📑 근거 보고서 PDF** if asked | | | "One page of evidence per decision, both languages, for the audit file." |

Rejecting instead of approving (**거절**) moves the same numbers to **거절**. A second decision on the same application is refused.

**Alternative: curl.** The back office has no login in the demo build:

```bash
export BASE=http://localhost:8080
```

1. Show the application as the reviewer sees it (extracted fields, provider results, documents):

   ```bash
   curl -s $BASE/admin/applications/APP-2026-001001 | python3 -m json.tool
   ```

2. Ask for a resubmission. The dealer receives "Resubmission requested" with the reason and a fresh one-time link:

   ```bash
   curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"reason":"Selfie too dark, please resend"}' \
     $BASE/admin/applications/APP-2026-001001/request-resubmission
   ```

3. Record the decision. The dealer and the customer receive the decision notification:

   ```bash
   curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVED","note":"Welcome aboard"}' \
     $BASE/admin/applications/APP-2026-001001/decision
   ```

4. On the dealer phone tap **Check status** and pick the application: status Approved with the note.

Without the header the endpoints return `401`; a second decision on the same application returns `409`.
`POST .../request-documents` with `{"items":[{"type":"KTP","reason":"the photo is blurry"}]}` is the curl form of step 3 above.

---

## 5b. Act 4 - ask both copilots (2 minutes)

Both pages carry an AI copilot that answers from exactly the figures on screen. The suggested questions are buttons;
click them rather than typing. Answers follow the page language.

| # | Where | Click | What comes back | Say |
| --- | --- | --- | --- | --- |
| 1 | **Left**, 개요 tab, **AI 대시보드 코파일럿** | **현재 고위험 비율 기준을?** | the definition (grade C or D over assessed applications), the excellent dealer bar of 15 %, and the current share overall, per dealer tier and per country, each marked 기준 충족 or 기준 초과, plus any dealers over the bar | "The standard and today's figure in one answer, from the same statistics the tiles show." |
| 2 | **Left** | **심사 대기 큐에서 먼저 볼 신청은?** | the applications at the top of the queue, each with its grade, fraud warnings and waiting time (ask this before Act 3 step 5 and the live application is named first) | "The copilot ranks; the officer opens." |
| 3 | **Right**, open application, **AI 심사 코파일럿** | **이 등급·점수가 나온 이유를 설명해줘** | the flags that make up the score and their evidence, from this application's case file | "Grounded in the case file, nothing invented; ID numbers reach the model masked to their last four digits." |
| 4 | **Right** | **승인해도 될까? 조건은?** | conditions tied to the flags; it will not decide | "It advises. The approve button stays with the person." |
| 5 | **Left**, 우수 딜러 tab | the **우수 딜러 기준** button | the tier criteria, including the 15 % high-risk share the copilot just quoted | |

Without an `ANTHROPIC_API_KEY` the copilots answer from rules (the badge next to the title says so); with one they answer
through Claude from the same case file. Either way the suggested questions work.

---

## 6. Optional act - real document analysis with Claude (3 minutes)

This replaces the stub with Claude vision and the review agent. Do a dry run the day before; the first live request
also warms the prompt cache.

1. In `wa.env` set:

   ```
   DOCUMENT_CHECK=claude
   ANTHROPIC_API_KEY=sk-ant-...
   ```

   Restart the app and check the log line `Claude document analysis enabled | ClaudeProperties(model=claude-opus-5-5, ...)`.

2. Use a **real KTP photo** on the customer phone (a sample card or your own; the image is sent to the Claude API).
   Captions are ignored in this mode. Repeat Act 2 with a new application.

3. What to show:
   - A blurry or glaring photo: the customer gets "❌ KTP could not be accepted: The photo is blurry" and is asked
     again. Log: `Analysis result | detected=KTP | usable=false`.
   - A clean photo: log `Analysis result | detected=KTP | usable=true | officerReview=false | reasons=[]`. The NIK
     has been checked for 16 digits, province code, regency and district, the date of birth encoded in it against the
     date printed on the card, and the female +40 rule against the stated gender.
   - After the selfie, the agent step: `Review agent turn | stop=tool_use` followed by either
     `Review agent requested documents | items=[...]` (the customer receives a message naming the document and the
     reason) or `Review agent wrote summary` with the one-page summary printed in the log.
   - The summary and per-document analysis in the admin view:

     ```bash
     curl -s $BASE/admin/applications/APP-2026-001002 | python3 -m json.tool
     ```

     Look at `review.summary`, `review.officerReviewRequired` and `documents[].analysis` (each field with its
     confidence and `EXTRACTED` / `NEEDS_OFFICER_REVIEW` / `NOT_VISIBLE` status).

4. Talking points: fields below 0.85 confidence are never used, only flagged; a NIK that contradicts the card goes to
   an officer, not back to the customer; the agent may send the customer back at most twice before it must write the
   summary; if the API is unreachable the document is kept and flagged instead of blocking the customer.

Switch back to `DOCUMENT_CHECK=stub` afterwards if you want the caption tricks again.

---

## 7. Dashboard reference: what each tab shows (read before the demo, not during)

The dashboard is already open on the left (section 2b); this is what to point at when the audience asks. Nothing is shown
until the key is accepted; the page refreshes every 15 seconds and pauses while its window is hidden.

What to point at:

1. **개요 tab, summary tiles** - total applications, in progress, ready for review, approved, rejected, high-risk customers (grade C/D),
   fraud/forgery warnings, officer review required, documents requested from customers, oldest waiting, average time to decision and
   applications stalled for a day. The **주간 추이** chart shows applications per week by country with the high-risk count above each week.
   **진행·대기 큐 tab** - every open application at its pipeline stage (신청 생성 → 고객 연결 → 개인정보 동의 → 신분증 → 셀피 → 신원·신용 조회 →
   리스크 평가 → 심사 → 결정) and the **심사 대기 큐** an officer should open next (click a number to jump to `/review`).
2. **국가·딜러 tab** - Indonesia, Myanmar and Cambodia are always listed. Dealers `DLR-003` / `DLR-004` are the Myanmar and
   Cambodia demo dealers; give them a phone in `wa.env` to produce rows for those countries (their verification runs on the
   mock NRC / GDI registries and credit bureaus).
3. **리스크·사기 tab, Risk Flag · 위조/사기 감지 경고** - the application you created with caption `nik7` appears here as grade **C** with the
   `IDENTITY_MISMATCH` (fraud) and `CREDIT_IDENTITY_MISMATCH` flags. The plain run (default NIK, score 720) is grade **A**
   with the badges **재무 정합성 및 담보 안정성 우수 고객** and, for `DLR-001`, **우수 딜러 유입 고객**.
4. The same grade line reached the dealer on WhatsApp in the "Documents submitted" message.
5. With `DOCUMENT_CHECK=claude`, screen photos, tampering, wrong document types, invalid NIKs and a selfie holding a different
   KTP become additional fraud flags, and the method column switches to "Claude 비전 + 규칙".

6. **AI 에이전트 tab** - what the pipeline and the review agent did across all applications; **우수 딜러 tab** - the tier comparison,
   the ranking and the **우수 딜러 기준** button with the criteria.

Hover a bar or a flag chip for the exact numbers and evidence. `POST /admin/risk/reassess-all` recomputes every grade after a rule change.

---

## 8. Reset between runs

- New application numbers are issued automatically; just create another application from the dealer menu.
- To re-use the **same customer phone** on a new application, send `START <new number>`; the old session is closed.
- To start completely clean: stop the app, delete `data/`, start again (dealers are re-seeded from `wa.env`).
- The dealer's `CANCEL` or a customer's `cancel` ends the current session.

---

## 9. If something goes wrong

| Symptom | Cause | Fix |
| --- | --- | --- |
| Dealer sends `hi`, nothing comes back, no log line | deployment down or webhook not subscribed | check `https://nexus.jbax.axhub.ai/healthz` and `axhub deploy status --app nexus`; Meta dashboard > Webhooks > `messages` subscribed |
| Log shows the message but reply fails with `401` from Graph API | access token expired | new System User token in `wa.env`, restart |
| Reply fails with error `131030` | phone not in the test recipients list | add it in WhatsApp > API Setup |
| Dealer gets the polite rejection instead of the menu | phone not seeded | check `DEMO_DEALER_1_PHONE` (digits only), restart, look for `Demo dealer upserted` |
| Customer's `START` says "could not find application" | typo or old number | copy the number from the dealer's message |
| "already linked to another phone" | the application was started from another phone earlier | create a new application |
| Image is accepted but no check happens / check takes long | `demo-data.check-delay` is 2 s, providers 1 to 3 s each | normal; up to 8 s total |
| App refuses to start: `Missing required WhatsApp configuration` | a key is blank in `wa.env` | fill it; names are listed in the error |
| Claude mode: `Analysis unavailable` in log, document passes but flagged | API key wrong, no credit, or network | check the key; the flow deliberately does not block the customer |

Useful while the app is stopped:

```bash
java -cp "$(find ~/.gradle/caches -name 'h2-2*.jar' | head -1)" org.h2.tools.Shell \
  -url jdbc:h2:file:./data/wa-webhook -user sa -password "" -sql "SELECT application_number, status, extracted_nik, review_decision FROM APPLICATIONS;"
```
