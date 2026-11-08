package com.aifusion.scanner.ai

import com.aifusion.scanner.CameraFrameStats

/**
 * Lightweight, offline scan-quality advisor.
 * This is an interpretable image-statistics engine, not a trained neural model.
 * Edge strength is a texture/detail proxy, not a calibrated focus or depth measurement.
 */
data class ScanAssessment(
    val qualityScore: Int,
    val headline: String,
    val guidance: String,
    val issues: List<String>
)

object ScanIntelligenceEngine {
    fun assess(frame: CameraFrameStats): ScanAssessment {
        val issues = mutableListOf<String>()
        var penalty = 0

        if (frame.sampleCount < 128) {
            issues += "Sampel imej tidak mencukupi untuk penilaian yang stabil."
            penalty += 35
        }

        val tooDark = frame.meanLuma < 52.0 || frame.underexposedRatio > 0.40
        val tooBright = frame.meanLuma > 205.0 || frame.overexposedRatio > 0.35
        if (tooDark) {
            issues += "Imej terlalu gelap; tambah cahaya dan halakan lampu secara sekata."
            penalty += 30
        }
        if (tooBright) {
            issues += "Kawasan terlalu terang; kurangkan pantulan atau cahaya terus."
            penalty += 25
        }
        if (frame.lumaStdDev < 16.0) {
            issues += "Kontras rendah; pilih sudut dengan lebih banyak bentuk dan tekstur."
            penalty += 20
        }
        if (frame.edgeStrength < 5.5) {
            issues += "Isyarat butiran/tepi rendah; dekatkan objek atau pilih permukaan bertekstur."
            penalty += 15
        }

        val score = (100 - penalty).coerceIn(0, 100)
        val headline = if (issues.isEmpty()) "KUALITI FRAME MEMADAI" else "PERLU PELARASAN IMBASAN"
        val guidance = when {
            tooDark -> "Utamakan pencahayaan sebelum meneruskan imbasan."
            tooBright -> "Laraskan cahaya supaya permukaan tidak hilang detail."
            frame.lumaStdDev < 16.0 -> "Cari sudut dengan perbezaan tona yang lebih jelas."
            frame.edgeStrength < 5.5 -> "Pastikan objek mempunyai detail yang boleh dijejak."
            issues.isNotEmpty() -> issues.first()
            else -> "Kekalkan cahaya sekata dan gerakkan telefon perlahan-lahan."
        }
        return ScanAssessment(score, headline, guidance, issues)
    }
}
