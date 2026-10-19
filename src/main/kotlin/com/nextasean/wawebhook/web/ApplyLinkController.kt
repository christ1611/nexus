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
            <title>${esc(title)} - NEXT ASEAN</title>
            <style>
              body{font-family:system-ui,sans-serif;max-width:640px;margin:2rem auto;padding:0 1rem;color:#222;background:#fafafa}
              h1{font-size:1.4rem} .card{background:#fff;border:1px solid #ddd;border-radius:8px;padding:1.25rem}
              button{background:#0b5fff;color:#fff;border:0;border-radius:6px;padding:.6rem 1rem;font-size:1rem}
              textarea{width:100%;max-width:100%}
            </style></head>
            <body><div class="card"><h1>${esc(title)}</h1>$body</div></body></html>
            """.trimIndent(),
        )
}
