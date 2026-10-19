package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/** Guards every path under /admin/ with a shared key sent in the `X-Admin-Key` header. */
@Component
class AdminKeyFilter(private val props: AppProperties) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/admin/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (props.adminApiKey.isBlank()) {
            log.warn("Admin endpoint called but ADMIN_API_KEY is not configured | path={}", request.requestURI)
            return reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin API is disabled: set ADMIN_API_KEY")
        }
        val provided = request.getHeader(HEADER).orEmpty()
        if (!MessageDigest.isEqual(provided.toByteArray(), props.adminApiKey.toByteArray())) {
            log.warn("Admin request rejected: bad or missing {} | path={}", HEADER, request.requestURI)
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid $HEADER header")
        }
        filterChain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$message"}""")
    }

    companion object {
        const val HEADER = "X-Admin-Key"
    }
}
