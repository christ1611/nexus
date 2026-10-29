# NEXUS ASEAN WhatsApp onboarding - demo runbook

Step-by-step script for a live demo of the dealer flow, the customer document journey, the automated checks and the
back-office events. Total running time is about 10 minutes. Everything below assumes the Meta webhook is already
registered on the axhub deployment `https://nexus.jbax.axhub.ai/webhook`, so the deployed app only has to be healthy.

---

## 0. What you need on the table

| Item | Why |
| --- | --- |
| Laptop with JDK 21+ and this repo | runs the app on port 8080 |
| The deployed app at `https://nexus.jbax.axhub.ai` | serves the webhook, review page and dashboard (no ngrok tunnel needed) |
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
   ADMIN_API_KEY=any-long-random-string
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

   Expected: `BUILD SUCCESSFUL`, 71 tests, 1 skipped (the live Claude test).

4. Optional but recommended: wipe the local database so application numbers start fresh at `APP-2026-001001`:

   ```bash
   rm -rf data/wa-webhook.mv.db data/documents
   ```

5. Recommended for the dashboard: add `DEMO_SEED_APPLICATIONS=true` to `wa.env` so the first start creates about 40 fake
   applications (three countries, eight weeks of history, risk grades, decisions, agent timelines). The live application you
   create on stage then gets the next number after the demo rows. To regenerate or remove them later:

   ```bash
   curl -s -X POST -H "X-Admin-Key: $ADMIN_API_KEY" -H 'Content-Type: application/json' -d '{"count":40,"reset":true}' http://localhost:8080/admin/demo/seed
   curl -s -X DELETE -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/demo/seed
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

## 3. Act 1 - start the application from the back office (1 minute, no dealer phone needed)

Use this when only one WhatsApp phone is available and it plays the customer. The dealer flow in 3b still works; it is just not needed.

| # | On the laptop (`/review`) | What happens | Say |
| --- | --- | --- | --- |
| 1 | paste the admin key, type a reviewer name, open **➕ 새 신청 만들기 · 고객 WhatsApp 초대** at the top of the queue | the form shows phone, name, dealer, "send invite" | "A back-office user prepares the application; the customer does everything else on WhatsApp." |
| 2 | enter the customer phone (digits with country code), a name, keep **WhatsApp 초대 메시지 전송** ticked, press **신청 생성** | the application number appears with a **QR code** and the `wa.me` START link; the customer phone receives *"Application APP-... has been prepared for you. Reply START to begin"* | "The number is created under the demo dealer; the audit trail records who created it." |
| 3 | on the customer phone reply **START** (or anything), or scan the QR code | the consent buttons arrive; continue with Act 2 | "Any first message from the invited number starts the journey." |

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

Everything here happens on the **customer phone**.

| # | Customer sends | Bot replies | What to point at in the log |
| --- | --- | --- | --- |
| 1 | tap the forwarded link, send `START APP-2026-001001` | greeting + consent text with **I Agree** / **Decline** | `Customer session started` |
| 2 | tap **I Agree** | "Thank you. Step 1 of 2: Please send a photo of your KTP." | `Consent given ... at=<timestamp>` - consent is stored with a timestamp |
| 3 | type `here you go` (text instead of a photo) | the request is simply repeated | the flow is strict about what it expects |
| 4 | send any photo with caption **`blur`** | "Received: KTP ✅ (1 of 2)" then "❌ KTP could not be accepted: Photo is blurry. Step 1 of 2: ..." | `Document stored` then `Document check failed`; the file is under `data/documents/APP-2026-001001/` |
| 5 | send the photo again with caption **`nik7`** | "Received: KTP ✅ (1 of 2)" then "Step 2 of 2: Please send a selfie of yourself holding your KTP..." | `Stub check | PASS | nik=***0007` - fields Budi Santoso, NIK ending in 7, are extracted |
| 6 | send a selfie | "Received: Selfie with KTP ✅ (2 of 2)", "Checking your data...", then after 2 to 6 seconds "✅ Your documents are submitted..." | `Dukcapil mock | outcome=MISMATCH`, `PEFINDO mock | outcome=MISMATCH`, `Verification complete | status=READY_FOR_REVIEW` |

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

## 5. Act 3 - the back office (3 minutes)

**Preferred: the review page.** Open `https://nexus.jbax.axhub.ai/review` (or `http://localhost:8080/review`),
paste `ADMIN_API_KEY`, type your name as reviewer and press **불러오기**.

1. The queue lists the application you just created at the top (심사 대기, grade and fraud warnings visible). Click it.
2. Walk down the detail: the **AI Agent 판단 타임라인** right under the header (open it *before* the customer sends the photos and
   the steps appear live every few seconds: 서류 수신 → 품질 확인 → 필드 추출 → 서류 수신 → 품질 확인 → 신원 조회 → 신용 조회 →
   리스크 평가 → 심사 대기 전환; with `DOCUMENT_CHECK=claude` you also see Claude 비전 분석 with tokens, NIK 구조 검증, the review
   agent's turns with `stop=tool_use`, the tool call and the 에이전트 결정), then the KTP and selfie images (click to enlarge), extracted
   data, Dukcapil / PEFINDO results, the Risk Flags with their evidence, and the officer timeline (서류 접수 완료, 리스크 평가).
3. Tick **신분증 (KTP)** in "고객 서류 재요청", type a reason such as `the photo is blurry`, press **📷 고객에게 재요청**.
   The customer phone receives "our review team ... needs the following again: KTP ..." and "Step 1 of 2"; the dealer gets a heads-up;
   the status becomes 고객 서류 재요청.
4. On the customer phone send a new KTP photo. The checks and provider lookups run again and the application is back in 심사 대기.
5. Press **✓ 승인** with a note. The dealer gets "Application approved" and the customer gets the approval message with the note.
   The timeline now shows 고객 서류 재요청, 승인 with your name.

**Alternative: curl.** Use the admin key from `wa.env`:

```bash
export ADMIN_API_KEY=$(grep '^ADMIN_API_KEY=' wa.env | cut -d= -f2-)
export BASE=http://localhost:8080
```

1. Show the application as the reviewer sees it (extracted fields, provider results, documents):

   ```bash
   curl -s -H "X-Admin-Key: $ADMIN_API_KEY" $BASE/admin/applications/APP-2026-001001 | python3 -m json.tool
   ```

2. Ask for a resubmission. The dealer receives "Resubmission requested" with the reason and a fresh one-time link:

   ```bash
   curl -s -X POST -H "X-Admin-Key: $ADMIN_API_KEY" -H 'Content-Type: application/json' \
     -d '{"reason":"Selfie too dark, please resend"}' \
     $BASE/admin/applications/APP-2026-001001/request-resubmission
   ```

3. Record the decision. The dealer and the customer receive the decision notification:

   ```bash
   curl -s -X POST -H "X-Admin-Key: $ADMIN_API_KEY" -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVED","note":"Welcome aboard"}' \
     $BASE/admin/applications/APP-2026-001001/decision
   ```

4. On the dealer phone tap **Check status** and pick the application: status Approved with the note.

Without the header the endpoints return `401`; a second decision on the same application returns `409`.
`POST .../request-documents` with `{"items":[{"type":"KTP","reason":"the photo is blurry"}]}` is the curl form of step 3 above.

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
     curl -s -H "X-Admin-Key: $ADMIN_API_KEY" $BASE/admin/applications/APP-2026-001002 | python3 -m json.tool
     ```

     Look at `review.summary`, `review.officerReviewRequired` and `documents[].analysis` (each field with its
     confidence and `EXTRACTED` / `NEEDS_OFFICER_REVIEW` / `NOT_VISIBLE` status).

4. Talking points: fields below 0.85 confidence are never used, only flagged; a NIK that contradicts the card goes to
   an officer, not back to the customer; the agent may send the customer back at most twice before it must write the
   summary; if the API is unreachable the document is kept and flagged instead of blocking the customer.

Switch back to `DOCUMENT_CHECK=stub` afterwards if you want the caption tricks again.

---

## 7. Reset between runs

- New application numbers are issued automatically; just create another application from the dealer menu.
- To re-use the **same customer phone** on a new application, send `START <new number>`; the old session is closed.
- To start completely clean: stop the app, delete `data/`, start again (dealers are re-seeded from `wa.env`).
- The dealer's `CANCEL` or a customer's `cancel` ends the current session.

---

## 8. If something goes wrong

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


---

## 7. Dashboard: risk flags, customer risk grade and per-country statistics (2 minutes)

Open `https://nexus.jbax.axhub.ai/dashboard` (or `http://localhost:8080/dashboard`) on the laptop, paste
`ADMIN_API_KEY` and press **불러오기**. Nothing is shown until the key is accepted; the page refreshes every minute.

What to point at:

1. **Summary tiles** - total applications, ready for review, high-risk customers (grade C/D), fraud/forgery warnings, documents
   requested from customers, oldest waiting and average time to decision. Below them the **심사 대기 큐** lists what an officer
   should open next (click a number to jump to `/review`), and the **주간 추이** chart shows applications per week by country.
2. **국가별 통계** - Indonesia, Myanmar and Cambodia are always listed. Dealers `DLR-003` / `DLR-004` are the Myanmar and
   Cambodia demo dealers; give them a phone in `wa.env` to produce rows for those countries (their verification runs on the
   mock NRC / GDI registries and credit bureaus).
3. **Risk Flag · 위조/사기 감지 경고** - the application you created with caption `nik7` appears here as grade **C** with the
   `IDENTITY_MISMATCH` (fraud) and `CREDIT_IDENTITY_MISMATCH` flags. The plain run (default NIK, score 720) is grade **A**
   with the badges **재무 정합성 및 담보 안정성 우수 고객** and, for `DLR-001`, **우수 딜러 유입 고객**.
4. The same grade line reached the dealer on WhatsApp in the "Documents submitted" message.
5. With `DOCUMENT_CHECK=claude`, screen photos, tampering, wrong document types, invalid NIKs and a selfie holding a different
   KTP become additional fraud flags, and the method column switches to "Claude 비전 + 규칙".

Hover a bar or a flag chip for the exact numbers and evidence. `POST /admin/risk/reassess-all` recomputes every grade after a rule change.
