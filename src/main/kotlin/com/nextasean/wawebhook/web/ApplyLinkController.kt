package com.nextasean.wawebhook.web

import com.nextasean.wawebhook.application.ApplicationService
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.LinkOpenResult
import com.nextasean.wawebhook.application.SubmitResult
import com.nextasean.wawebhook.conversation.DealerNotifier
import com.nextasean.wawebhook.dealer.DealerRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils

/**
 * The one-time link dealers receive on WhatsApp. Opening it consumes the token and shows a minimal
 * placeholder form; posting the form marks the application as submitted and notifies the dealer.
 */
@RestController
@RequestMapping("/apply")
class ApplyLinkController(
    private val service: ApplicationService,
    private val dealers: DealerRepository,
    private val notifier: DealerNotifier,
) {

    @GetMapping("/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun open(@PathVariable token: String): ResponseEntity<String> = when (val result = service.openLink(token)) {
        is LinkOpenResult.Opened -> page(
            HttpStatus.OK,
            "Application ${result.application.applicationNumber}",
            """
            <p>Dealer: <strong>${dealerName(result.application)}</strong></p>
            <p>Status: ${esc(result.application.status.label)}</p>
            <p>This is the application form placeholder. Submitting it marks the application as submitted and
               sends a confirmation to your WhatsApp.</p>
            <form method="post" action="/apply/${esc(token)}">
              <label>Notes (optional)<br><textarea name="notes" rows="4" cols="50"></textarea></label><br><br>
              <button type="submit">Submit application</button>
            </form>
            """,
        )

        is LinkOpenResult.AlreadyUsed -> page(
            HttpStatus.GONE,
            "Link already used",
            "<p>This one-time link for <strong>${esc(result.application.applicationNumber)}</strong> has already been opened.</p>" +
                "<p>If you still need to complete the form, open WhatsApp, choose <em>Check status</em> and request a new link.</p>",
        )

        is LinkOpenResult.Expired -> page(
            HttpStatus.GONE,
            "Link expired",
            "<p>This link for <strong>${esc(result.application.applicationNumber)}</strong> has expired.</p>" +
                "<p>Open WhatsApp, choose <em>Check status</em> and request a new link.</p>",
        )

        LinkOpenResult.NotFound -> page(HttpStatus.NOT_FOUND, "Link not found", "<p>This link is not valid.</p>")
    }

    @PostMapping("/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun submit(@PathVariable token: String): ResponseEntity<String> = when (val result = service.submit(token)) {
        is SubmitResult.Submitted -> {
            notifier.submitted(result.application)
            page(
                HttpStatus.OK,
                "Application submitted",
                "<p>Thank you. Application <strong>${esc(result.application.applicationNumber)}</strong> has been submitted and is under review.</p>" +
                    "<p>A confirmation was sent to your WhatsApp. You can close this page.</p>",
            )
        }

        is SubmitResult.NotSubmittable -> page(
            HttpStatus.CONFLICT,
            "Cannot submit",
            "<p>Application <strong>${esc(result.application.applicationNumber)}</strong> cannot be submitted with this link " +
                "(status: ${esc(result.application.status.label)}).</p>",
        )

        SubmitResult.NotFound -> page(HttpStatus.NOT_FOUND, "Link not found", "<p>This link is not valid.</p>")
    }

    private fun dealerName(app: DealerApplication): String =
        dealers.findById(app.dealerId).map { "${esc(it.name)} (${esc(it.dealerCode)})" }.orElse("unknown dealer")

    private fun esc(value: String): String = HtmlUtils.htmlEscape(value)

    private fun page(status: HttpStatus, title: String, body: String): ResponseEntity<String> = ResponseEntity.status(status)
        .contentType(MediaType.TEXT_HTML)
        .body(
            """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="theme-color" content="#0a31a8">
            <link rel="icon" type="image/svg+xml" href="/icons/icon.svg"><link rel="icon" type="image/png" sizes="32x32" href="/icons/favicon-32.png"><link rel="apple-touch-icon" sizes="180x180" href="/icons/apple-touch-icon.png">
            <title>${esc(title)} · JB금융그룹 NEXT ASEAN</title>
            <style>
              body{font-family:"SUIT Variable",-apple-system,"Apple SD Gothic Neo","Noto Sans KR","Segoe UI",Roboto,sans-serif;max-width:640px;margin:0 auto;padding:0 1rem 2rem;color:#1a1f2e;background:#f5f7fa;line-height:1.5}
              .brand{display:flex;align-items:center;gap:.6rem;padding:1.1rem 0 1.25rem;border-top:4px solid #0a31a8;margin:0 -1rem 1.25rem;padding-left:1rem;padding-right:1rem;background:#fff;border-bottom:1px solid #e1e5ee}
              .brand .mark{width:36px;height:36px;border-radius:10px;background:linear-gradient(135deg,#0a31a8,#1c56ff);color:#fff;font-weight:800;display:grid;place-items:center;font-size:14px}
              .brand .group{font-size:.8rem;font-weight:700;color:#0a31a8} .brand .svc{color:#728197;font-weight:500;margin-left:.4rem}
              h1{font-size:1.3rem;margin:0 0 .75rem;letter-spacing:-.01em} .card{background:#fff;border:1px solid #e1e5ee;border-radius:16px;padding:1.5rem;box-shadow:0 1px 2px rgba(13,45,119,.06),0 4px 16px rgba(13,45,119,.04)}
              button{background:#0a31a8;color:#fff;border:0;border-radius:999px;padding:.65rem 1.4rem;font:inherit;font-weight:600;font-size:1rem;cursor:pointer} button:hover{background:#1c56ff}
              textarea{width:100%;max-width:100%;border:1px solid #e1e5ee;border-radius:10px;padding:.6rem;font:inherit}
              textarea:focus-visible{outline:none;border-color:#0a31a8;box-shadow:0 0 0 3px #eaf0ff}
              footer{margin-top:1.25rem;font-size:.75rem;color:#728197}
            </style></head>
            <body>
            <div class="brand"><span class="mark" aria-hidden="true">JB</span><div class="group">JB금융그룹 <span class="svc">NEXT ASEAN</span></div></div>
            <div class="card"><h1>${esc(title)}</h1>$body</div>
            <footer>JB금융그룹 · NEXT ASEAN dealer onboarding</footer>
            </body></html>
            """.trimIndent(),
        )
}
