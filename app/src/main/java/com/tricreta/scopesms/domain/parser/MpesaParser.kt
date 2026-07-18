package com.tricreta.scopesms.domain.parser

import com.tricreta.scopesms.domain.money.KshAmount

/**
 * Turns a raw M-Pesa SMS into an [MpesaPayment], or says why it couldn't.
 *
 * Pure, synchronous, no I/O — it runs on the detect-and-decide path that
 * CLAUDE.md constraint 5 requires to survive ~10 messages in 1–3 seconds, and
 * it must be testable on the JVM without Robolectric.
 *
 * ### What it parses
 * The **business till** confirmation, which is not the person-to-person format
 * most M-Pesa regexes online target. The one real sample we have (CLAUDE.md):
 *
 * ```
 * UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh20.00 received from
 * 254700000000 Skycope Bonke. New Account balance is Ksh1300.22.
 * Transaction cost, Ksh0.00.
 * ```
 *
 * Note `Confirmed.on` and `PMKsh20.00` — M-Pesa's spacing is not reliable, so
 * every gap below is `\s*` or `\s+` by intent, never a literal space.
 *
 * ### 🔴 Known limitation — read before trusting this
 * BUILD-PLAN Phase 2 requires 5–10 real redacted samples before the regex is
 * final. **We still have exactly one** (see memory.md). This parser is
 * therefore built to be *strict about structure and tolerant about spacing*,
 * and the test suite encodes the variants we can reason about (long names,
 * initials, commas in amounts, missing balance/cost) rather than variants we
 * have observed. That is a real gap, not a solved problem: until the agent
 * supplies more samples, treat an unexpected `Rejection.NOT_A_RECEIVED_MESSAGE`
 * in the wild as "the regex is wrong", not "the message was junk".
 *
 * [PATTERNS] is an ordered list precisely so a newly-discovered variant is a
 * new entry plus a test, not a rewrite.
 */
object MpesaParser {

    /**
     * Ordered list of accepted "money received" shapes. First match wins.
     *
     * Named groups are used so a pattern can order its fields differently from
     * the others — which is exactly how M-Pesa variants tend to differ.
     */
    private val PATTERNS: List<Regex> = listOf(
        // The client's confirmed till format.
        //
        //   <CODE> Confirmed.on <date> at <time><Ksh amount> received from
        //   <phone> <name>. [New Account balance is Ksh<x>.] [Transaction cost, Ksh<y>.]
        Regex(
            """(?<code>[A-Z0-9]{8,12})\s*Confirmed\.?\s*on\s+""" +
                """(?<date>\d{1,2}/\d{1,2}/\d{2,4})\s*at\s+""" +
                """(?<time>\d{1,2}:\d{2}\s*[AP]\.?M\.?)\s*""" +
                """Ksh\s*(?<amount>[\d,]+(?:\.\d{1,2})?)\s*received\s+from\s+""" +
                """(?<phone>\+?\d{9,15})\s*""" +
                // Lazy, with a lookahead, so a name containing a full stop
                // ("J. Doe", "Mary A. W.") isn't truncated at the first period —
                // it ends only at the sentence that starts the balance/cost
                // clause, or at the end of the message.
                """(?<name>.*?)\s*(?=\.\s*New\b|\.\s*Transaction\b|\.?\s*$)""",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** `New Account balance is Ksh1300.22.` — also matches `New M-PESA balance`. */
    private val BALANCE = Regex(
        """New\s+(?:[\w-]+\s+)?balance\s+is\s+Ksh\s*(?<balance>[\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    /** `Transaction cost, Ksh0.00.` */
    private val COST = Regex(
        """Transaction\s+cost[,:]?\s*Ksh\s*(?<cost>[\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Message types that must never trigger a reply, matched explicitly.
     *
     * Strictly speaking these are redundant — none of them can satisfy the
     * "received from" structure above. They are here because BUILD-PLAN Phase 2
     * asks for the exclusion to be *explicit*, and because they turn a silent
     * non-match into a named reason in the log. When the agent asks "why didn't
     * it reply to that one", [Rejection.WRONG_TRANSACTION_TYPE] answers the
     * question and [Rejection.NOT_A_RECEIVED_MESSAGE] starts an investigation.
     */
    private val EXCLUDED_TYPES = Regex(
        """\b(?:sent\s+to|paid\s+to|withdrawn?|bought|airtime|reversal|reversed|""" +
            """your\s+(?:m-pesa\s+)?(?:account\s+)?balance\s+(?:was|is))\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The sender M-Pesa confirmations legitimately arrive from.
     *
     * This is a security control, not a tidiness one. Without it, anyone who
     * knows the agent's number can text them a fake "Ksh20 received from ..."
     * and make the app send a stranger an SMS at the agent's expense — and,
     * once Phase 8 lands, poison their books with a payment that never
     * happened. The originating address is set by the network, so an ordinary
     * sender cannot forge it.
     *
     * ⚠️ Needs a real-device check: verified from documentation, not from the
     * agent's own handset. If real payments are ever dropped as
     * [Rejection.NOT_FROM_MPESA], this is the first place to look — the reason
     * is logged with the offending address for exactly that reason.
     */
    private val MPESA_SENDER = Regex("""^M-?PESA$""", RegexOption.IGNORE_CASE)

    /**
     * True if [address] is M-Pesa, or one of [extraTrustedSenders].
     *
     * The whitelist exists for an agent who also runs a side service under
     * their own registered sender ID (e.g. `SKYSCOPE_`) that re-sends the
     * same till-confirmation format — a second real source of "money
     * received" texts, not a spoofing hole, because the agent opts each one
     * in by hand in Settings (`SettingsRepository.trustedSenders`). Compared
     * case-insensitively, trimmed, against the *exact* address — no pattern
     * matching beyond that, unlike the official shortcode's `-?` allowance,
     * because a registered sender ID doesn't have M-Pesa's carrier-display
     * quirks.
     *
     * Kept separate from [parse] so the receiver can check the sender before
     * spending anything on the body, and so both rules are testable
     * independently.
     */
    fun isMpesaSender(address: String?, extraTrustedSenders: Set<String> = emptySet()): Boolean {
        if (address == null) return false
        val trimmed = address.trim()
        return MPESA_SENDER.matches(trimmed) ||
            extraTrustedSenders.any { it.trim().equals(trimmed, ignoreCase = true) }
    }

    /**
     * Parses [body]. Never throws — a malformed SMS is a [ParseResult.Rejected],
     * because BUILD-PLAN Phase 9 requires unparseable messages to be logged and
     * skipped, never to crash the receiver.
     */
    fun parse(body: String?): ParseResult {
        if (body.isNullOrBlank()) return ParseResult.Rejected(Rejection.EMPTY_BODY)

        // Collapse the line wrapping that multipart SMS reassembly and OEM
        // concatenation introduce, so patterns never have to anticipate where a
        // newline landed.
        val text = body.replace('\n', ' ').replace('\r', ' ').trim()

        val match = PATTERNS.firstNotNullOfOrNull { it.find(text) }
            ?: return ParseResult.Rejected(
                if (EXCLUDED_TYPES.containsMatchIn(text)) {
                    Rejection.WRONG_TRANSACTION_TYPE
                } else {
                    Rejection.NOT_A_RECEIVED_MESSAGE
                },
            )

        // Belt and braces: if a future pattern is ever loose enough to match a
        // "sent to" message, this stops it reaching a customer. A wrong reply is
        // worse than a missed one (CLAUDE.md constraint 4).
        if (EXCLUDED_TYPES.containsMatchIn(text)) {
            return ParseResult.Rejected(Rejection.WRONG_TRANSACTION_TYPE)
        }

        val amount = KshAmount.parse(match.group("amount"))
            ?: return ParseResult.Rejected(Rejection.UNREADABLE_AMOUNT)

        val phone = normalizeKenyanMsisdn(match.group("phone"))
            ?: return ParseResult.Rejected(Rejection.UNREADABLE_PHONE)

        return ParseResult.Parsed(
            MpesaPayment(
                transactionCode = match.group("code").uppercase(),
                amount = amount,
                senderPhone = phone,
                // Null rather than "" when absent: Phase 4's template engine has
                // to make a "Hi there" vs "Hi {name}" decision, and null says
                // "no name" unambiguously where "" invites a "Hi ," greeting.
                senderName = match.group("name")?.trim()?.takeIf { it.isNotEmpty() },
                date = match.group("date"),
                time = match.group("time").trim(),
                // Optional by design: a message missing these is still a valid
                // payment, and refusing to reply because the balance clause was
                // worded unexpectedly would cost the agent a customer.
                balance = BALANCE.find(text)?.group("balance")?.let(KshAmount::parse),
                transactionCost = COST.find(text)?.group("cost")?.let(KshAmount::parse),
            ),
        )
    }

    /**
     * Normalises a Kenyan number to local `07XXXXXXXX` / `01XXXXXXXX` form,
     * which is what the SCOPE gateway's `/sendsms` documents as its input.
     *
     * Returns null for anything that isn't a plausible Kenyan mobile number —
     * better to reject the message than to have the gateway bill the agent for
     * an SMS to a number that cannot exist.
     */
    fun normalizeKenyanMsisdn(raw: String): String? {
        val digits = raw.filter { it.isDigit() }

        val local = when {
            // 254712345678 → 0712345678
            digits.length == 12 && digits.startsWith(COUNTRY_CODE) -> "0" + digits.substring(3)
            // 0712345678 — already local.
            digits.length == 10 && digits.startsWith("0") -> digits
            // 712345678 → 0712345678. M-Pesa doesn't print this, but a redacted
            // or hand-entered sample might.
            digits.length == 9 -> "0$digits"
            else -> return null
        }

        // Safaricom/Airtel/Telkom mobile prefixes are 07 and 01. Anything else
        // (a landline, a shortcode, a mangled capture) can't receive this reply.
        return local.takeIf { it.startsWith("07") || it.startsWith("01") }
    }

    private const val COUNTRY_CODE = "254"

    /** Named-group access. `groups[name]` returns null for an unmatched optional group. */
    private fun MatchResult.group(name: String): String = groups[name]?.value.orEmpty()
}

/** Outcome of [MpesaParser.parse]. */
sealed interface ParseResult {

    data class Parsed(val payment: MpesaPayment) : ParseResult

    /**
     * Not a message we act on. [reason] exists because "didn't parse" is
     * undiagnosable, and this parser is knowingly under-validated (one real
     * sample). The reason is what tells the agent's support conversation apart
     * from a genuine parser bug.
     */
    data class Rejected(val reason: Rejection) : ParseResult
}

enum class Rejection {
    /** Null or whitespace body. */
    EMPTY_BODY,

    /** Not from the M-Pesa shortcode. Possibly a spoof; possibly our sender rule is wrong. */
    NOT_FROM_MPESA,

    /** A real M-Pesa message, but a type we deliberately ignore (sent, withdrawal, airtime…). */
    WRONG_TRANSACTION_TYPE,

    /**
     * From M-Pesa, not an excluded type, and still didn't match. **This is the
     * interesting one** — it most likely means a till-format variant we haven't
     * seen. Worth surfacing, not burying.
     */
    NOT_A_RECEIVED_MESSAGE,

    /** Matched, but the amount wasn't a number we could trust. */
    UNREADABLE_AMOUNT,

    /** Matched, but the payer's number isn't one we could reply to. */
    UNREADABLE_PHONE,
}
