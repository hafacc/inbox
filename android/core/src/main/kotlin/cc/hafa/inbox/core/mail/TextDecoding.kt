package cc.hafa.inbox.core.mail

import java.nio.charset.Charset

// The WHATWG Encoding Standard's labels, as web's TextDecoder reads them, each to the Java charset
// that decodes the same way. WINDOWS_1252, REPLACEMENT and X_USER_DEFINED are decoded by hand.
private const val WINDOWS_1252 = "windows-1252"
private const val REPLACEMENT = "replacement"
private const val X_USER_DEFINED = "x-user-defined"

private val ENCODING_LABELS: Map<String, List<String>> = mapOf(
    "UTF-8" to listOf("unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "utf-8", "utf8", "x-unicode20utf8"),
    "IBM866" to listOf("866", "cp866", "csibm866", "ibm866"),
    "ISO-8859-2" to listOf("csisolatin2", "iso-8859-2", "iso-ir-101", "iso8859-2", "iso88592", "iso_8859-2", "iso_8859-2:1987", "l2", "latin2"),
    "ISO-8859-3" to listOf("csisolatin3", "iso-8859-3", "iso-ir-109", "iso8859-3", "iso88593", "iso_8859-3", "iso_8859-3:1988", "l3", "latin3"),
    "ISO-8859-4" to listOf("csisolatin4", "iso-8859-4", "iso-ir-110", "iso8859-4", "iso88594", "iso_8859-4", "iso_8859-4:1988", "l4", "latin4"),
    "ISO-8859-5" to listOf(
        "csisolatincyrillic", "cyrillic", "iso-8859-5", "iso-ir-144", "iso8859-5", "iso88595", "iso_8859-5", "iso_8859-5:1988",
    ),
    "ISO-8859-6" to listOf(
        "arabic", "asmo-708", "csiso88596e", "csiso88596i", "csisolatinarabic", "ecma-114", "iso-8859-6", "iso-8859-6-e",
        "iso-8859-6-i", "iso-ir-127", "iso8859-6", "iso88596", "iso_8859-6", "iso_8859-6:1987",
    ),
    "ISO-8859-7" to listOf(
        "csisolatingreek", "ecma-118", "elot_928", "greek", "greek8", "iso-8859-7", "iso-ir-126", "iso8859-7", "iso88597",
        "iso_8859-7", "iso_8859-7:1987", "sun_eu_greek",
    ),
    "ISO-8859-8" to listOf(
        "csiso88598e", "csisolatinhebrew", "hebrew", "iso-8859-8", "iso-8859-8-e", "iso-ir-138", "iso8859-8", "iso88598",
        "iso_8859-8", "iso_8859-8:1988", "visual", "csiso88598i", "iso-8859-8-i", "logical",
    ),
    "ISO-8859-10" to listOf("csisolatin6", "iso-8859-10", "iso-ir-157", "iso8859-10", "iso885910", "l6", "latin6"),
    "ISO-8859-13" to listOf("iso-8859-13", "iso8859-13", "iso885913"),
    "ISO-8859-14" to listOf("iso-8859-14", "iso8859-14", "iso885914"),
    "ISO-8859-15" to listOf("csisolatin9", "iso-8859-15", "iso8859-15", "iso885915", "iso_8859-15", "l9"),
    "ISO-8859-16" to listOf("iso-8859-16"),
    "KOI8-R" to listOf("cskoi8r", "koi", "koi8", "koi8-r", "koi8_r"),
    "KOI8-U" to listOf("koi8-ru", "koi8-u"),
    "x-MacRoman" to listOf("csmacintosh", "mac", "macintosh", "x-mac-roman"),
    "x-windows-874" to listOf("dos-874", "iso-8859-11", "iso8859-11", "iso885911", "tis-620", "windows-874"),
    "windows-1250" to listOf("cp1250", "windows-1250", "x-cp1250"),
    "windows-1251" to listOf("cp1251", "windows-1251", "x-cp1251"),
    WINDOWS_1252 to listOf(
        "ansi_x3.4-1968", "ascii", "cp1252", "cp819", "csisolatin1", "ibm819", "iso-8859-1", "iso-ir-100", "iso8859-1",
        "iso88591", "iso_8859-1", "iso_8859-1:1987", "l1", "latin1", "us-ascii", "windows-1252", "x-cp1252",
    ),
    "windows-1253" to listOf("cp1253", "windows-1253", "x-cp1253"),
    "windows-1254" to listOf(
        "cp1254", "csisolatin5", "iso-8859-9", "iso-ir-148", "iso8859-9", "iso88599", "iso_8859-9", "iso_8859-9:1989", "l5",
        "latin5", "windows-1254", "x-cp1254",
    ),
    "windows-1255" to listOf("cp1255", "windows-1255", "x-cp1255"),
    "windows-1256" to listOf("cp1256", "windows-1256", "x-cp1256"),
    "windows-1257" to listOf("cp1257", "windows-1257", "x-cp1257"),
    "windows-1258" to listOf("cp1258", "windows-1258", "x-cp1258"),
    "x-MacCyrillic" to listOf("x-mac-cyrillic", "x-mac-ukrainian"),
    // WHATWG's GBK decoder is its gb18030 decoder.
    "GB18030" to listOf("chinese", "csgb2312", "csiso58gb231280", "gb2312", "gb_2312", "gb_2312-80", "gbk", "iso-ir-58", "x-gbk", "gb18030"),
    "Big5-HKSCS" to listOf("big5", "big5-hkscs", "cn-big5", "csbig5", "x-x-big5"),
    "EUC-JP" to listOf("cseucpkdfmtjapanese", "euc-jp", "x-euc-jp"),
    "ISO-2022-JP" to listOf("csiso2022jp", "iso-2022-jp"),
    "windows-31j" to listOf("csshiftjis", "ms932", "ms_kanji", "shift-jis", "shift_jis", "sjis", "windows-31j", "x-sjis"),
    "x-windows-949" to listOf(
        "cseuckr", "csksc56011987", "euc-kr", "iso-ir-149", "korean", "ks_c_5601-1987", "ks_c_5601-1989", "ksc5601", "ksc_5601",
        "windows-949",
    ),
    REPLACEMENT to listOf("csiso2022kr", "hz-gb-2312", "iso-2022-cn", "iso-2022-cn-ext", "iso-2022-kr", "replacement"),
    "UTF-16BE" to listOf("unicodefffe", "utf-16be"),
    "UTF-16LE" to listOf("csunicode", "iso-10646-ucs-2", "ucs-2", "unicode", "unicodefeff", "utf-16", "utf-16le"),
    X_USER_DEFINED to listOf("x-user-defined"),
)

private val ENCODING_BY_LABEL: Map<String, String> =
    ENCODING_LABELS.flatMap { (encoding, labels) -> labels.map { it to encoding } }.toMap()

// Where windows-1252 differs from Latin-1: bytes 0x80–0x9F. Those it leaves undefined read as the C1 control.
private const val WINDOWS_1252_HIGH =
    "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f" +
        "\u0090‘’“”•–—˜™š›œ\u009džŸ"

/** The character windows-1252 reads for a byte from 0x80 to 0x9F, as WHATWG defines it. */
internal fun windows1252High(byte: Int): Char = WINDOWS_1252_HIGH[byte - 0x80]

private fun decodeWindows1252(bytes: ByteArray): String = buildString(bytes.size) {
    for (signed in bytes) {
        val byte = signed.toInt() and 0xFF
        append(if (byte in 0x80..0x9F) windows1252High(byte) else byte.toChar())
    }
}

private fun decodeUserDefined(bytes: ByteArray): String = buildString(bytes.size) {
    for (signed in bytes) {
        val byte = signed.toInt() and 0xFF
        append(if (byte < 0x80) byte.toChar() else (0xF780 + byte - 0x80).toChar())
    }
}

private val UNICODE_ENCODINGS = setOf("UTF-8", "UTF-16BE", "UTF-16LE")

// Other names for a charset, for runtimes (Android's or the JVM's) that know it by one of them.
private val OTHER_NAMES: Map<String, List<String>> = mapOf(
    "x-MacRoman" to listOf("macintosh"),
    "x-MacCyrillic" to listOf("x-mac-cyrillic"),
    "x-windows-874" to listOf("windows-874", "TIS-620"),
    "Big5-HKSCS" to listOf("Big5"),
    "windows-31j" to listOf("Shift_JIS"),
    "x-windows-949" to listOf("windows-949", "EUC-KR"),
)

private fun javaCharset(encoding: String): Charset? =
    (listOf(encoding) + OTHER_NAMES[encoding].orEmpty()).firstNotNullOfOrNull { name ->
        runCatching { Charset.forName(name) }.getOrNull()
    }

/**
 * Decode [bytes] in the charset a [label] names, as the WHATWG Encoding Standard (and so web's
 * TextDecoder) reads labels: `iso-8859-1` and `us-ascii` mean windows-1252, a leading byte order
 * mark goes, and a label it doesn't know, or maps to "replacement", reads as UTF-8.
 */
fun decodeText(bytes: ByteArray, label: String = "utf-8"): String {
    val encoding = ENCODING_BY_LABEL[label.trim().lowercase()] ?: "UTF-8"
    return when (encoding) {
        WINDOWS_1252 -> decodeWindows1252(bytes)
        X_USER_DEFINED -> decodeUserDefined(bytes)
        else -> {
            val charset = if (encoding == REPLACEMENT) null else javaCharset(encoding)
            val decoded = String(bytes, charset ?: Charsets.UTF_8)
            if ((charset == null || encoding in UNICODE_ENCODINGS) && decoded.startsWith('﻿')) decoded.drop(1) else decoded
        }
    }
}
