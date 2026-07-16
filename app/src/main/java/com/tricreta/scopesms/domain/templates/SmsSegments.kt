package com.tricreta.scopesms.domain.templates

/** How a message will be encoded on the wire. */
enum class SmsEncoding {

    /** GSM 03.38. 160 chars in one segment, 153 per segment once it splits. */
    GSM_7BIT,

    /**
     * UTF-16. 70 chars in one segment, 67 once it splits.
     *
     * One character outside the GSM alphabet — a curly apostrophe pasted from
     * elsewhere, an emoji — drops the *whole* message to this and less than
     * halves its capacity.
     */
    UCS2,
}

/**
 * The measured length of a rendered message.
 *
 * @param units septets for [SmsEncoding.GSM_7BIT] (an extended character counts
 *   two), UTF-16 code units for [SmsEncoding.UCS2] (an emoji counts two).
 * @param remainingInLastSegment how much more fits before another segment is
 *   added.
 */
data class SmsLength(
    val encoding: SmsEncoding,
    val units: Int,
    val segments: Int,
    val remainingInLastSegment: Int,
) {
    /** True once the message costs the agent more than one SMS. */
    val willSplit: Boolean get() = segments > 1
}

/**
 * Counts SMS segments for a rendered message body (BUILD-PLAN Phase 4).
 *
 * ## Why the agent cares
 * Gateways bill per segment, not per message. A 161-character reply costs
 * double a 160-character one, and a single curly apostrophe forces UCS-2 and
 * makes an 80-character reply cost double for no visible reason. This app sends
 * one SMS per unmatched payment, every day, on the agent's account — so the
 * Phase 7 editor shows a live count while they type, and this is what computes
 * it. It is advisory: the SCOPE gateway does the real encoding.
 *
 * The unmatched template is the one to watch, since `{bundle_list}` grows with
 * every bundle the agent adds.
 */
object SmsSegments {

    private const val GSM_SINGLE = 160
    private const val GSM_MULTIPART = 153 // 7 septets go to the concatenation header
    private const val UCS2_SINGLE = 70
    private const val UCS2_MULTIPART = 67

    fun measure(body: String): SmsLength {
        val septets = gsm7SeptetsOrNull(body)

        return if (septets != null) {
            build(SmsEncoding.GSM_7BIT, septets, GSM_SINGLE, GSM_MULTIPART)
        } else {
            // UTF-16 code units, not characters: an emoji is a surrogate pair
            // and occupies two units on the wire.
            build(SmsEncoding.UCS2, body.length, UCS2_SINGLE, UCS2_MULTIPART)
        }
    }

    fun segmentsFor(body: String): Int = measure(body).segments

    private fun build(encoding: SmsEncoding, units: Int, single: Int, multipart: Int): SmsLength {
        val segments = when {
            units <= single -> 1
            else -> (units + multipart - 1) / multipart
        }
        val capacity = if (segments == 1) single else segments * multipart

        return SmsLength(
            encoding = encoding,
            units = units,
            segments = segments,
            remainingInLastSegment = capacity - units,
        )
    }

    /**
     * Septet count if [body] is GSM 03.38-encodable, or null if any character
     * isn't — in which case the whole message goes UCS-2.
     *
     * Known approximation: a real encoder will not let an escape pair straddle a
     * segment boundary, and pushes it whole into the next segment instead. That
     * can make an extension-heavy message one segment longer than counted here.
     * It needs an extension character to land exactly on a boundary, which no
     * realistic Swahili/English price list contains at all, and the cost of
     * being wrong is an off-by-one in an advisory hint. Not worth the
     * complexity.
     */
    private fun gsm7SeptetsOrNull(body: String): Int? {
        var septets = 0
        for (char in body) {
            septets += when (char) {
                in GSM_BASIC -> 1
                in GSM_EXTENDED -> 2 // ESC + the character
                else -> return null
            }
        }
        return septets
    }

    /** GSM 03.38 default alphabet. Membership only — table order is irrelevant here. */
    private val GSM_BASIC: Set<Char> = buildSet {
        addAll("@£\$¥èéùìòÇ\nØø\rÅå".toList())
        addAll("Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ".toList())
        addAll(" !\"#¤%&'()*+,-./".toList())
        addAll("0123456789:;<=>?".toList())
        addAll("¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§".toList())
        addAll("¿abcdefghijklmnopqrstuvwxyzäöñüà".toList())
    }

    /**
     * The GSM 03.38 extension table: each of these is sent as ESC + char and
     * costs two septets rather than one.
     *
     * Note what is *absent*: the curly quotes that phone keyboards and
     * copy-paste produce (U+2018/U+2019/U+201C/U+201D). Those are not GSM
     * characters at all, so a single one forces the entire message to UCS-2 —
     * the most likely way for the agent's template to silently double in price.
     */
    /**
     * Form feed, by code point.
     *
     * As a literal it is an invisible control character that editors and shell
     * round-trips quietly eat — and losing it would be undetectable by eye while
     * silently mispricing any message containing one.
     *
     * Declared before [GSM_EXTENDED] because it is read by that initialiser, and
     * an `object`'s properties initialise in declaration order.
     */
    private val FORM_FEED = 12.toChar()

    private val GSM_EXTENDED: Set<Char> = buildSet {
        add(FORM_FEED)
        addAll("^{}\\[~]|€".toList())
    }
}
