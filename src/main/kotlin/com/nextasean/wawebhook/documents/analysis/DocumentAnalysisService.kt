package com.nextasean.wawebhook.documents.analysis

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.documents.DocumentType
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

data class AnalysisRequest(
    val type: DocumentType,
    val file: Path,
    val mimeType: String?,
    val applicationNumber: String? = null,
    /** Date of birth already on file (e.g. from a form), compared with the NIK in addition to the date printed on the card. */
    val knownDob: LocalDate? = null,
)

/** Thrown when the analysis could not be performed (network, auth, rate limit); the caller decides whether to retry. */
class DocumentAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Looks at one document image and returns a structured [DocumentAnalysis]. */
interface DocumentAnalysisService {
    fun analyze(request: AnalysisRequest): DocumentAnalysis
}

/**
 * Claude vision implementation: one Messages API call per image with a JSON schema on the output.
 * The model only transcribes; [AnalysisResultMapper] applies the thresholds and the NIK checks.
 */
class ClaudeDocumentAnalysisService(
    private val client: AnthropicClient,
    private val props: ClaudeProperties,
    private val mapper: AnalysisResultMapper = AnalysisResultMapper(props.minFieldConfidence),
) : DocumentAnalysisService {

    private val log = LoggerFactory.getLogger(javaClass)

    private val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
        .additionalProperties(AnalysisPrompt.SCHEMA.mapValues { JsonValue.from(it.value) })
        .build()

    private val outputConfig: OutputConfig = OutputConfig.builder()
        .effort(OutputConfig.Effort.of(props.effort.lowercase()))
        .format(JsonOutputFormat.builder().schema(schema).build())
        .build()

    private val system: List<TextBlockParam> = listOf(
        TextBlockParam.builder()
            .text(AnalysisPrompt.SYSTEM)
            .cacheControl(CacheControlEphemeral.builder().build())
            .build(),
    )

    override fun analyze(request: AnalysisRequest): DocumentAnalysis {
        val bytes = Files.readAllBytes(request.file)
        val mediaType = mediaTypeOf(request.mimeType, bytes)
        if (mediaType == null) {
            log.warn("Unsupported image | application={} | type={} | mime={}", request.applicationNumber, request.type, request.mimeType)
            return mapper.notAnalysed(request.type, "Unsupported image format (${request.mimeType ?: "unknown"}); send a JPEG, PNG or WEBP photo", props.model)
        }
        if (bytes.size > props.maxImageBytes) {
            return mapper.notAnalysed(request.type, "Image is ${bytes.size / 1024} KB, above the ${props.maxImageBytes / 1024} KB limit", props.model)
        }

        val params = MessageCreateParams.builder()
            .model(props.model)
            .maxTokens(8_000L)
            .systemOfTextBlockParams(system)
            .outputConfig(outputConfig)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(Base64ImageSource.builder().mediaType(mediaType).data(Base64.getEncoder().encodeToString(bytes)).build())
                            .build(),
                    ),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(AnalysisPrompt.userInstruction(request.type)).build()),
                ),
            )
            .build()

        val started = System.currentTimeMillis()
        val response = try {
            client.messages().create(params)
        } catch (e: RateLimitException) {
            throw DocumentAnalysisException("Claude rate limit reached", e)
        } catch (e: BadRequestException) {
            throw DocumentAnalysisException("Claude rejected the request: ${e.message}", e)
        } catch (e: AnthropicServiceException) {
            throw DocumentAnalysisException("Claude API error (${e.statusCode()})", e)
        } catch (e: AnthropicIoException) {
            throw DocumentAnalysisException("Could not reach the Claude API", e)
        } catch (e: AnthropicInvalidDataException) {
            throw DocumentAnalysisException("Unexpected Claude API response", e)
        }
        val usage = response.usage()
        log.info(
            "Document analysed | application={} | type={} | model={} | stop={} | in={} | cacheRead={} | out={} | ms={}",
            request.applicationNumber, request.type, response.model().asString(), response.stopReason().getOrNull()?.asString(),
            usage.inputTokens(), usage.cacheReadInputTokens().getOrNull(), usage.outputTokens(), System.currentTimeMillis() - started,
        )

        val stop = response.stopReason().getOrNull()
        val modelUsage = ModelUsage(
            inputTokens = usage.inputTokens(), cacheReadTokens = usage.cacheReadInputTokens().getOrNull(), outputTokens = usage.outputTokens(),
            durationMs = System.currentTimeMillis() - started, stopReason = stop?.asString(),
        )
        if (stop == StopReason.REFUSAL) {
            val details = response.stopDetails().getOrNull()
            val why = listOfNotNull(details?.category()?.getOrNull()?.asString(), details?.explanation()?.getOrNull()).joinToString(": ")
            return mapper.notAnalysed(request.type, "The model declined to analyse this image${if (why.isNotBlank()) " ($why)" else ""}", props.model).copy(usage = modelUsage)
        }
        if (stop == StopReason.MAX_TOKENS) {
            return mapper.notAnalysed(request.type, "The model response was cut off before it finished", props.model).copy(usage = modelUsage)
        }
        val json = response.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("")
        val analysis = mapper.map(request.type, json, response.model().asString(), request.knownDob).copy(usage = modelUsage)
        log.info(
            "Analysis result | application={} | type={} | detected={} | usable={} | officerReview={} | reasons={}",
            request.applicationNumber, request.type, analysis.documentType, analysis.usable, analysis.needsOfficerReview, analysis.reviewReasons,
        )
        return analysis
    }

    /** Media type from the declared MIME type, falling back to the file's magic bytes. HEIC and PDFs are not accepted by the API. */
    internal fun mediaTypeOf(mimeType: String?, bytes: ByteArray): Base64ImageSource.MediaType? {
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> return Base64ImageSource.MediaType.IMAGE_JPEG
            "image/png" -> return Base64ImageSource.MediaType.IMAGE_PNG
            "image/webp" -> return Base64ImageSource.MediaType.IMAGE_WEBP
            "image/gif" -> return Base64ImageSource.MediaType.IMAGE_GIF
        }
        if (bytes.size < 12) return null
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Base64ImageSource.MediaType.IMAGE_JPEG
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> Base64ImageSource.MediaType.IMAGE_PNG
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> Base64ImageSource.MediaType.IMAGE_WEBP
            String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> Base64ImageSource.MediaType.IMAGE_GIF
            else -> null
        }
    }
}
