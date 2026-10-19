package com.nextasean.wawebhook.documents

import com.nextasean.wawebhook.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Stores downloaded documents as `<documents-dir>/<application number>/<TYPE>-<timestamp>.<ext>`. */
@Component
class DocumentStorage(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun save(applicationNumber: String, type: DocumentType, media: DownloadedMedia): Path {
        val dir = Path.of(props.documentsDir).resolve(applicationNumber.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        Files.createDirectories(dir)
        val file = dir.resolve("${type.name}-${stamp.format(Instant.now())}.${extensionFor(media.mimeType)}")
        Files.write(file, media.bytes)
        log.info("Document stored | application={} | type={} | file={} | bytes={}", applicationNumber, type, file, media.bytes.size)
        return file.toAbsolutePath()
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "application/pdf" -> "pdf"
        else -> "bin"
    }
}
