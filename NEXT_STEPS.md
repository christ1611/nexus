# NEXT ASEAN WhatsApp webhook - where we are and what comes next

_Last updated: 20 Oct 2026_

## Current state (done and tested, 71 tests green, 1 skipped live test)

- **Step 1 - Webhook**: `GET /webhook` verification, `POST /webhook` with `X-Hub-Signature-256` check,
  async processing, duplicate message IDs ignored, structured logging of messages and statuses.
- **Step 2 - Dealer flow**: state machine in `CONVERSATIONS`, main menu (list), new application
  (buttons -> `APP-YYYY-NNNNNN` + one-time link), check status, resubmission request and decision
  notifications via `/admin/**`, polite rejection for unregistered numbers, demo dealer seeding.
- **Step 3 - Customer journey**: wa.me `START <number>` link for the dealer to forward, `CUSTOMER_SESSIONS`
  state machine, consent buttons with timestamp, KTP then selfie collection with media download to
  `data/documents/<number>/`, `StubDocumentCheck` (caption `blur` fails, `nik<d>` overrides NIK last digit),
  mock Dukcapil + PEFINDO driven by the NIK's last digit, results saved, `READY_FOR_REVIEW`, both sides notified.

- **Step 3b - Claude document analysis + review agent** (`DOCUMENT_CHECK=claude`): `documents/analysis/` (vision call with
  JSON schema, `NikValidator`, confidence threshold -> "needs officer review"), `documents/ClaudeDocumentCheck.kt` (adapter),
  `review/` (tool-using agent: `request_documents` via WhatsApp or `write_summary`; rule-based fallback and guardrails).
  Default stays `stub`, so the demo and the 35 earlier tests are unchanged. Not yet exercised against the live API from
  this machine (no key here); `ClaudeDocumentAnalysisLiveTest` runs automatically when `ANTHROPIC_API_KEY` is set.

- **Step 5 - Statistics dashboard + risk flags** (19 Oct 2026): `risk/` (`RiskRules` -> flags, score, grade A-D, badges;
  `RiskAssessmentService` stores them on APPLICATIONS after verification and puts a "Risk grade" line in the dealer summary),
  dealer `country` (ID/MM/KH) + `tier`, application `country`, per-country mock providers (MM, KH) selected per application,
  `GET /admin/dashboard/stats`, `POST /admin/applications/{n}/assess-risk`, `POST /admin/risk/reassess-all`, and the page
  `/dashboard` (`static/dashboard.html`, inline SVG, Korean-first labels, admin key entered in the page). Demo NIK now ends in 4
  so the happy path is grade A with the "financial stability" badge; caption `nik7` produces grade C with a fraud warning.

- **Step 4 - Reviewer back office** (20 Oct 2026): `/review` (`static/review.html`): work queue with filters, application detail
  with document images (`GET /admin/applications/{n}/documents/{id}/file`), analysis details, extracted data, provider results,
  risk flags with evidence, agent summary and an audit timeline (`REVIEW_EVENTS`, `review/ReviewEvents.kt`). Actions: approve /
  reject (dealer **and customer** notified, `CustomerNotifier`, `decided_by/at`), request documents from the customer
  (`POST .../request-documents` -> `CustomerConversationService.requestDocuments`: supersedes records, status `DOCUMENTS_REQUESTED`,
  reopens the customer session at that step, customer + dealer messaged; new photo re-runs checks and verification), dealer form
  resubmission, re-assess risk, notes. `reviewer` is accepted on every POST. Dashboard additions: review queue with waiting times,
  8-week trend chart, documents-requested / oldest-waiting / avg-decision tiles, links to `/review?app=`.

- **Step 4b - AI agent timeline** (20 Oct 2026): `trace/AgentTrace.kt` (`AGENT_STEPS`, `AgentTraceService`, `TraceKind`). Recorded from
  `CustomerConversationService` (document received, vision analysis / stub quality check, NIK validation, field extraction, agent
  decision, customer messaged, provider lookups, risk assessment, ready for review, officer request), `ClaudeReviewAgent` (every turn
  with stop reason + tokens, tool calls with input, retries, fallback, guardrails) and the decision endpoint. `DocumentAnalysis.usage`
  carries tokens / latency / stop reason of the vision call. Review page: flow strip + stepper, live polling of
  `GET /admin/applications/{n}/agent-trace` every 4 s while the application is active. Dashboard: "AI 에이전트 활동" card (`stats.agent`).

Code map: `src/main/kotlin/com/nextasean/wawebhook/` -> `webhook/` (inbound), `conversation/` (dealer flow +
router), `customer/` (customer flow + customer notifier), `documents/` (+ `analysis/`), `review/` (agent step + audit events),
`trace/` (agent timeline), `risk/` (flags, grade, badges), `verification/`, `application/`, `admin/` (admin API + dashboard stats),
`web/` (one-time link page, dashboard + review pages), `whatsapp/` (Graph API client). Schema in `src/main/resources/schema.sql`,
demo data in `demo-data.yml`.

## To do first next session (10 minutes)

1. Fill `DEMO_DEALER_1_PHONE` / `DEMO_DEALER_2_PHONE` in `wa.env` (digits only, e.g. `62812xxxxxxx`).
2. Start the app (`./gradlew bootRun`) and ngrok
   (`ngrok http --url=amaretto-tipoff-excess.ngrok-free.dev 8080`). The Meta webhook is already registered
   and verified; it only needs the tunnel to be up.
3. Check the access token: the dashboard's temporary token expires after 24 h. If outgoing messages log
   HTTP 401 from the Graph API, generate a System User token and update `WHATSAPP_ACCESS_TOKEN`.
4. Run the live demo from a dealer phone: `hi` -> New application -> Yes, create -> forward the wa.me message
   -> from the customer phone send `START ...` -> I Agree -> KTP photo (try caption `blur` once) -> selfie
   -> watch "Checking your data..." and the dealer summary.
5. Open `/review`, paste the admin key, type a reviewer name, pick the application: send the customer back for the KTP
   (📷 고객에게 재요청), let the customer resend it, then approve. Both phones receive the decision.
   (curl still works: `POST /admin/applications/{number}/request-documents`, `.../decision`, `.../request-resubmission`.)

## Possible next steps (pick by priority)

### A. Harden what exists
- Real media download has not been exercised against Meta yet (tests use a fake). Verify the two-step download
  works with the real token and that the saved JPEG opens.
- Confirm `display_phone_number` learned from webhooks produces a working wa.me link; otherwise set
  `WHATSAPP_BUSINESS_PHONE`.
- Mark incoming messages as read / show typing indicator while the 2-5 s checks run (Graph API
  `status: read` + `typing_indicator`), so the customer sees activity.
- Retry/backoff for Graph API sends and an `OUTBOUND_LOG` table so failures are visible and re-sendable.
- Rate-limit the polite rejection for unregistered numbers (one per hour per number).
- Session timeouts: expire customer sessions idle for N hours and tell the customer how to resume.

### B. Step 4 follow-ups (the back office itself is done)
- Agent timeline: with a real `ANTHROPIC_API_KEY`, check the turn / tool-call steps on live Claude responses (only the stub path and
  the scripted agent are covered by tests); consider streaming the model's text into the panel instead of showing it after the turn.
- Agent timeline: a per-application "cost" line (sum of tokens) and the timeline as a downloadable audit export.
- Per-reviewer login and roles instead of the shared key plus a typed name; show who is working on what.
- Decision notifications to customers outside the 24 h window need a pre-approved template (see E); `customerNotified: false`
  in the response is the symptom.
- Let Claude phrase the "please resend" reason for the customer in Bahasa Indonesia / Burmese / Khmer from the officer's note.
- Decision reason templates and a required reason on rejection.

### B2. Step 5 follow-ups
- Collateral data is not collected yet; once it is, add it to the "financial consistency and collateral stability" badge rule.
- Let Claude write a short natural-language risk narrative per application (the grade and flags stay rule-based).
- Date-range filter on the dashboard (the 8-week trend chart exists; a custom range and per-dealer trends do not).
- Tune `RiskRules` thresholds on real KTP photos and real provider responses.

### C. Replace stubs with real integrations
- Claude document check: run `ANTHROPIC_API_KEY=... ./gradlew test --tests '*Live*'`, then a live WhatsApp run with
  `DOCUMENT_CHECK=claude`; tune `claude.min-field-confidence` and the prompts on real KTP photos; consider passing the
  KTP image along with the selfie so the agent can compare the two faces.
- `IdVerificationProvider` / `CreditBureauProvider`: real Dukcapil and PEFINDO clients behind the same
  interfaces; add providers for other countries (the registry already selects by `VERIFICATION_COUNTRY`).
- Run the two provider calls in parallel and apply a real timeout instead of the simulated one.

### D. Production readiness
- Database: move from the H2 file to PostgreSQL and add Flyway migrations (replace `schema.sql`).
- Dedupe store: persist processed message IDs (table or Redis) so restarts and multiple instances are safe.
- Secrets: encrypt the access token at rest or load from a secret manager; rotate `ADMIN_API_KEY`.
- Document storage: encrypt at rest or move to object storage with short-lived signed URLs; define retention.
- Observability: request IDs in logs, metrics for messages in/out, failed sends, check durations; health
  endpoint (Spring Actuator).
- Deploy behind a stable HTTPS domain instead of ngrok; update the callback URL in the Meta dashboard.

### E. Product polish
- Localisation (Bahasa Indonesia first; wording is centralised in `DealerMessages` / `CustomerMessages`).
- Message templates for notifications sent outside the 24 h customer service window (decision notifications
  will fail if the customer has been silent for more than 24 h; a pre-approved template fixes that).
- Dealer analytics: counts per status in the dealer menu; weekly summary.

## Useful commands

```bash
./gradlew test                       # all tests (H2 in-memory, fake Graph API)
./gradlew bootRun                    # run on :8080 reading wa.env
curl -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/applications
curl -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/dashboard/stats   # dashboard numbers
open http://localhost:8080/dashboard                                                 # dashboard page
# inspect DB while app is stopped:
java -cp "$(find ~/.gradle/caches -name 'h2-2*.jar' | head -1)" org.h2.tools.Shell \
  -url jdbc:h2:file:./data/wa-webhook -user sa -password "" -sql "SELECT * FROM APPLICATIONS;"
```
