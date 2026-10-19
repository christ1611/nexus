package com.nextasean.wawebhook.documents

import com.fasterxml.jackson.annotation.JsonProperty
import com.nextasean.wawebhook.config.WhatsAppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.URI

data class DownloadedMedia(val bytes: ByteArray, val mimeType: String?, val sha256: String?)

/** Fetches media sent by customers; faked in tests. */
interface MediaDownloader {
    fun download(mediaId: String): DownloadedMedia
}

/**
 * Two-step Cloud API download: `GET /{media-id}` returns a short-lived URL, which is then fetched
 * with the same bearer token.
 */
@Service
class GraphMediaDownloader(
    private val graphRestClient: RestClient,
    private val props: WhatsAppProperties,
) : MediaDownloader {

    private val log = LoggerFactory.getLogger(javaClass)

    data class MediaMetadata(
        val id: String? = null,
        val url: String? = null,
        @JsonProperty("mime_type") val mimeType: String? = null,
        val sha256: String? = null,
        @JsonProperty("file_size") val fileSize: Long? = null,
    )

    override fun download(mediaId: String): DownloadedMedia {
        val meta = graphRestClient.get()
            .uri("/{version}/{mediaId}", props.graphApiVersion, mediaId)
            .retrieve()
            .body(MediaMetadata::class.java)
        val url = meta?.url ?: error("Graph API returned no download URL for media $mediaId")
        val bytes = graphRestClient.get()
            .uri(URI.create(url))
            .retrieve()
            .body(ByteArray::class.java) ?: ByteArray(0)
        log.info("Media downloaded | mediaId={} | mimeType={} | bytes={}", mediaId, meta.mimeType, bytes.size)
        return DownloadedMedia(bytes, meta.mimeType, meta.sha256)
    }
}
