package com.nextasean.wawebhook.documents.analysis

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.documents.DocumentType
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calls the real Claude API with a synthetic KTP-like card. Runs only when ANTHROPIC_API_KEY is set:
 * `ANTHROPIC_API_KEY=sk-ant-... ./gradlew test --tests '*ClaudeDocumentAnalysisLiveTest*'`
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeDocumentAnalysisLiveTest {

    private val props = ClaudeProperties()
    private val service = ClaudeDocumentAnalysisService(AnthropicOkHttpClient.fromEnv(), props)

    @Test
    fun `synthetic KTP is recognised, fields are read and the NIK is validated`() {
        val file = Files.createTempFile("ktp", ".png")
        ImageIO.write(syntheticKtp(), "png", file.toFile())

        val a = service.analyze(AnalysisRequest(DocumentType.KTP, file, "image/png", applicationNumber = "LIVE-TEST"))
        println(a.rawJson)
        println(a.reviewReasons)

        assertEquals(DetectedDocumentType.KTP, a.documentType)
        assertTrue(a.usable, a.rejectionReason ?: "")
        assertEquals("3171051705900001", a.field("nik")?.value)
        assertEquals("BUDI SANTOSO", a.field("name")?.value)
        assertEquals("1990-05-17", a.field("date_of_birth")?.value)
        assertEquals(true, a.nik?.valid, a.nik?.issues.toString())
    }

    private fun syntheticKtp(): BufferedImage {
        val img = BufferedImage(1012, 638, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(214, 232, 248)
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 34)
        g.drawString("PROVINSI DKI JAKARTA", 250, 60)
        g.drawString("JAKARTA PUSAT", 330, 100)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 40)
        g.drawString("NIK  : 3171051705900001", 40, 170)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        val lines = listOf(
            "Nama                 : BUDI SANTOSO",
            "Tempat/Tgl Lahir     : JAKARTA, 17-05-1990",
            "Jenis Kelamin        : LAKI-LAKI        Gol. Darah : O",
            "Alamat               : JL. MERDEKA NO. 10",
            "     RT/RW           : 003/005",
            "     Kel/Desa        : GAMBIR",
            "     Kecamatan       : GAMBIR",
            "Agama                : ISLAM",
            "Status Perkawinan    : KAWIN",
            "Pekerjaan            : KARYAWAN SWASTA",
            "Kewarganegaraan      : WNI",
            "Berlaku Hingga       : SEUMUR HIDUP",
        )
        lines.forEachIndexed { i, line -> g.drawString(line, 40, 220 + i * 34) }
        g.color = Color.GRAY
        g.fillRect(780, 200, 190, 240)
        g.dispose()
        return img
    }
}
