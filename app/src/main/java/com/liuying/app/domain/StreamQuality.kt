package com.liuying.app.domain

import com.liuying.app.domain.model.Stream

enum class Resolution(val label: String, val height: Int) {
    UHD_4K("4K", 2160), QHD("1440p", 1440), FHD("1080p", 1080), HD("720p", 720), SD("SD", 480)
}

enum class VideoCodec(val label: String) { HEVC("HEVC"), H264("H.264"), AV1("AV1"), VP9("VP9"), XVID("XviD") }

enum class StreamKind { Direct, External, YouTube, Torrent, Unsupported }

data class QualityInfo(
    val resolution: Resolution? = null,
    val hdr: Boolean = false,
    val hdr10Plus: Boolean = false,
    val dolbyVision: Boolean = false,
    val codec: VideoCodec? = null,
    val audio: List<String> = emptyList(),
    val channels: String? = null,
    val source: String? = null,
    val sizeBytes: Long? = null,
    val seeds: Int? = null,
    val releaseGroup: String? = null,
    /** ISO 3166 region codes taken from flag emoji (best-effort language hint). */
    val languageFlags: List<String> = emptyList(),
) {
    /** Short chips for the stream row, best-effort; the raw text is always shown too. */
    val badges: List<String>
        get() = buildList {
            resolution?.let { add(it.label) }
            if (dolbyVision) add("Dolby Vision")
            if (hdr10Plus) add("HDR10+") else if (hdr) add("HDR")
            codec?.let { add(it.label) }
            audio.firstOrNull()?.let { add(it) }
            channels?.let { add(it) }
            source?.let { add(it) }
            sizeBytes?.let { add(formatSize(it)) }
        }

    companion object {
        fun formatSize(bytes: Long): String {
            val gb = bytes / 1_073_741_824.0
            return if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb)
            else String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
        }
    }
}

object StreamQualityParser {
    private val o = setOf(RegexOption.IGNORE_CASE)
    private const val B = "(?<![\\p{L}\\d])"      // not preceded by letter/digit
    private const val E = "(?![\\p{L}\\d])"       // not followed by letter/digit

    private val res2160 = Regex("${B}(?:2160p?|4k|uhd)${E}|\\d{3,4}x2160", o)
    private val res1440 = Regex("${B}(?:1440p|2k)${E}|\\d{3,4}x1440", o)
    private val res1080 = Regex("${B}(?:1080[pi]?|fhd)${E}|\\d{3,4}x1080", o)
    private val res720 = Regex("${B}720[pi]?${E}|\\d{3,4}x720", o)
    // A bare "SD" tag is common ("SD CAM"); SDR is excluded by the word boundary, not by listing variants.
    private val resSd = Regex("${B}(?:480p|576p|360p|dvdrip|sdtv|sd)${E}|\\d{3,4}x(?:480|576)", o)

    private val dv = Regex("dolby[ .\\-]?vision|${B}dovi${E}|${B}dv${E}", o)
    private val hdr10p = Regex("hdr10\\+|hdr10plus", o)
    private val hdrAny = Regex("${B}hdr10${E}|${B}hdr(?![\\p{L}])|hdr10\\+", o)

    private val hevc = Regex("${B}(?:x265|h[ .]?265|hevc)${E}", o)
    private val h264 = Regex("${B}(?:x264|h[ .]?264|avc)${E}", o)
    private val av1 = Regex("${B}av1${E}", o)
    private val vp9 = Regex("${B}vp9${E}", o)
    private val xvid = Regex("${B}(?:xvid|divx)${E}", o)

    private val audioPatterns = listOf(
        "Atmos" to Regex("atmos", o),
        "TrueHD" to Regex("true[ .\\-]?hd", o),
        "DTS-HD MA" to Regex("dts[ .\\-]?hd([ .\\-]?ma)?", o),
        "DTS:X" to Regex("dts[ .\\-]?x${E}", o),
        "DTS" to Regex("${B}dts${E}", o),
        "DD+" to Regex("${B}(?:e[ .\\-]?ac[ .\\-]?3|ddp|dd\\+)", o),
        "DD" to Regex("${B}(?:ac[ .\\-]?3|dd[ .]?[257]\\.[01])", o),
        "AAC" to Regex("${B}aac${E}", o),
        "FLAC" to Regex("${B}flac${E}", o),
        "Opus" to Regex("${B}opus${E}", o),
    )
    private val channelsRx = Regex("(?<![\\d.])([257]\\.[01])(?![\\d])")

    private val sourcePatterns = listOf(
        "REMUX" to Regex("remux", o),
        "BluRay" to Regex("blu[ .\\-]?ray|${B}bdrip${E}|${B}brrip${E}", o),
        "WEB-DL" to Regex("web[ .\\-]?dl", o),
        "WEBRip" to Regex("web[ .\\-]?rip", o),
        "HDTV" to Regex("${B}hdtv${E}", o),
        "CAM" to Regex("${B}(?:hd)?cam(?:rip)?${E}|telesync|${B}hdts${E}", o),
    )

    private val sizeRx = Regex("(\\d+(?:[.,]\\d+)?)\\s?(gib|gb|mib|mb)", o)
    private val seedsRx = Regex("(?:👤\\s*|seeds?[:=]?\\s*|seeders?[:=]?\\s*)(\\d+)", o)
    private val groupRx = Regex("-([A-Za-z0-9]{2,12})$")
    private val extRx = Regex("\\.(mkv|mp4|avi|m4v|ts|webm|mov)$", o)

    fun parse(stream: Stream): QualityInfo = parse(
        text = listOfNotNull(stream.name, stream.title, stream.description).joinToString("\n"),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
    )

    fun parse(text: String, filename: String? = null, videoSize: Long? = null): QualityInfo {
        val hay = if (filename.isNullOrBlank()) text else "$text\n$filename"
        val resolution = when {
            res2160.containsMatchIn(hay) -> Resolution.UHD_4K
            res1440.containsMatchIn(hay) -> Resolution.QHD
            res1080.containsMatchIn(hay) -> Resolution.FHD
            res720.containsMatchIn(hay) -> Resolution.HD
            resSd.containsMatchIn(hay) -> Resolution.SD
            else -> null
        }
        val plus = hdr10p.containsMatchIn(hay)
        val codec = when {
            hevc.containsMatchIn(hay) -> VideoCodec.HEVC
            h264.containsMatchIn(hay) -> VideoCodec.H264
            av1.containsMatchIn(hay) -> VideoCodec.AV1
            vp9.containsMatchIn(hay) -> VideoCodec.VP9
            xvid.containsMatchIn(hay) -> VideoCodec.XVID
            else -> null
        }
        val audio = audioPatterns.filter { it.second.containsMatchIn(hay) }.map { it.first }
        val size = videoSize ?: sizeRx.find(hay)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val mult = if (m.groupValues[2].lowercase().startsWith("g")) 1_073_741_824.0 else 1_048_576.0
            (n * mult).toLong()
        }
        val group = filename?.trim()?.replace(extRx, "")?.let { groupRx.find(it)?.groupValues?.get(1) }
        return QualityInfo(
            resolution = resolution,
            hdr = hdrAny.containsMatchIn(hay),
            hdr10Plus = plus,
            dolbyVision = dv.containsMatchIn(hay),
            codec = codec,
            audio = audio,
            channels = channelsRx.find(hay)?.groupValues?.get(1),
            source = sourcePatterns.firstOrNull { it.second.containsMatchIn(hay) }?.first,
            sizeBytes = size,
            seeds = seedsRx.find(hay)?.groupValues?.get(1)?.toIntOrNull(),
            releaseGroup = group,
            languageFlags = flags(hay),
        )
    }

    /** Regional-indicator pairs (flag emoji) -> region codes. */
    internal fun flags(s: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i < s.length) {
                val cp2 = s.codePointAt(i)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    i += Character.charCount(cp2)
                    out += "" + ('A' + (cp - 0x1F1E6)) + ('A' + (cp2 - 0x1F1E6))
                }
            }
        }
        return out.toList()
    }

    fun kindOf(stream: Stream): StreamKind {
        val url = stream.url?.trim().orEmpty()
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> StreamKind.Direct
            url.startsWith("magnet:", true) || !stream.infoHash.isNullOrBlank() -> StreamKind.Torrent
            !stream.ytId.isNullOrBlank() -> StreamKind.YouTube
            !stream.externalUrl.isNullOrBlank() -> StreamKind.External
            else -> StreamKind.Unsupported
        }
    }
}
