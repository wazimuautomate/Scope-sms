package com.tricreta.scopesms.domain.templates

/**
 * Why a flagged phrase or pattern reads as promotional rather than
 * transactional. The UI (`ui/templates/TemplatesScreen.kt`) maps each of
 * these to its own explanatory string, the same way [TemplateType] maps to a
 * tab label — this enum stays plain so `domain/` has no Android dependency.
 */
enum class ToneIssueCategory {
    URGENCY,
    CALL_TO_ACTION,
    DISCOUNT_FRAMING,
    PRIZE_OR_INCENTIVE,
    SHOUTING,
    EXCESSIVE_PUNCTUATION,
    EMOJI,
}

/** One flagged phrase or pattern, with why it was flagged. */
data class ToneIssue(val matchedText: String, val category: ToneIssueCategory)

/** The result of checking a template body for promotional-sounding language. */
data class ToneCheckResult(val issues: List<ToneIssue>) {
    val soundsPromotional: Boolean get() = issues.isNotEmpty()

    companion object {
        val CLEAN = ToneCheckResult(emptyList())
    }
}

/**
 * Flags wording in a template body that reads as promotional/marketing
 * rather than transactional.
 *
 * ## Why this exists
 * The gateway sender ID is registered with Safaricom as *transactional*, not
 * *promotional* — it exists only to confirm something that already happened
 * (a payment, a purchase). Safaricom's own content filter blocks messages
 * from a transactional sender ID that read like an advert. That block
 * happens at the network level: it is not a `SendFailure` this app's gateway
 * client can see or report (see `network/ScopeSmsGateway`), so by the time
 * anyone notices, a customer simply never got their reply and there is
 * nothing in the activity log to explain why. This has to be caught in the
 * editor, before a template is ever saved — not diagnosed after the fact.
 *
 * ## Why keyword rules, not something smarter
 * This runs live as the agent types (`TemplatesViewModel.editorFor`), so it
 * has to be instant and needs no network round-trip. A deterministic,
 * testable rule set also means "why was this flagged" always has a concrete
 * answer, which is the point — the agent needs to see what to fix, not just
 * a pass/fail badge.
 *
 * ## Why the list looks the way it does
 * This app's own core legitimate vocabulary overlaps with words a generic
 * marketing filter would flag: Safaricom's own product name for bundles is
 * "offers" (`{data_offers}` etc. are literal template variables — see
 * [TemplateVariable]), and every unmatched-reply price list is, by
 * definition, a list of things to buy. A checker that flagged "offer",
 * "free", or a bare price would be wrong about this app's single most common
 * legitimate message, every time. So this deliberately flags only the
 * stronger, far less ambiguous markers of marketing copy — urgency, sales
 * calls-to-action, discount framing, prize language, shouting, and
 * spam-like punctuation — never product or price vocabulary by itself.
 *
 * Advisory only: it never blocks saving ([TemplateEngine.validate] still
 * owns that gate). A heuristic will have false positives, and refusing to
 * save a template the agent has judged fine would be worse than a
 * dismissible warning.
 */
object PromotionalToneChecker {

    fun check(body: String): ToneCheckResult {
        val issues = mutableListOf<ToneIssue>()

        PATTERNS.forEach { (category, pattern) ->
            pattern.findAll(body).forEach { match -> issues += ToneIssue(match.value, category) }
        }

        EXCLAMATION_OR_QUESTION_RUN.findAll(body).forEach { match ->
            issues += ToneIssue(match.value, ToneIssueCategory.EXCESSIVE_PUNCTUATION)
        }

        NUMERIC_PERCENT_OFF.findAll(body).forEach { match ->
            issues += ToneIssue(match.value, ToneIssueCategory.DISCOUNT_FRAMING)
        }

        shoutedWordsIn(body).forEach { word -> issues += ToneIssue(word, ToneIssueCategory.SHOUTING) }

        emojiRunIn(body)?.let { issues += ToneIssue(it, ToneIssueCategory.EMOJI) }

        // De-duped so a repeated "Hurry! Hurry!" produces one card, not four
        // near-identical ones.
        return ToneCheckResult(issues.distinctBy { it.category to it.matchedText.lowercase() })
    }

    /** All-caps runs at least this long are shouting; shorter ones are common abbreviations. */
    private const val MIN_SHOUT_LENGTH = 4

    /** Real abbreviations this app's own messages legitimately use in caps. */
    private val CAPS_ALLOWLIST = setOf("SMS", "PIN", "ATM", "KSH", "KES", "USD", "MPESA", "OK")

    private fun shoutedWordsIn(body: String): List<String> = WORD_PATTERN.findAll(body)
        .map { it.value }
        .filter { word ->
            word.length >= MIN_SHOUT_LENGTH &&
                word == word.uppercase() &&
                word.uppercase() !in CAPS_ALLOWLIST
        }
        .distinct()
        .toList()

    /**
     * Two or more emoji anywhere in the body — a single friendly one is not
     * flagged. Walks actual Unicode code points rather than a regex
     * character-class range: this project has already been bitten once by
     * Android's ICU regex engine disagreeing with the desktop JVM on a
     * lone-brace pattern that 300 CI-run tests never caught (see
     * [TemplateEngine]'s `TOKEN_PATTERN` comment) — surrogate-pair ranges in a
     * regex character class are exactly the kind of thing that class of bug
     * hides in, so this sidesteps regex entirely for emoji detection.
     */
    private fun emojiRunIn(body: String): String? {
        val found = StringBuilder()
        var index = 0
        var count = 0
        while (index < body.length) {
            val codePoint = body.codePointAt(index)
            if (isEmojiCodePoint(codePoint)) {
                found.appendCodePoint(codePoint)
                count++
            }
            index += Character.charCount(codePoint)
        }
        return found.toString().takeIf { count >= MIN_EMOJI_COUNT }
    }

    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F300..0x1FAFF || // pictographs, emoticons, transport, symbols
            codePoint in 0x2600..0x27BF // misc symbols & dingbats

    private const val MIN_EMOJI_COUNT = 2

    private val WORD_PATTERN = Regex("""[A-Za-z]+""")
    private val EXCLAMATION_OR_QUESTION_RUN = Regex("""[!?]{2,}""")
    private val NUMERIC_PERCENT_OFF = Regex("""\d+\s*%\s*off""", RegexOption.IGNORE_CASE)

    private val PATTERNS: List<Pair<ToneIssueCategory, Regex>> = listOf(
        ToneIssueCategory.URGENCY to phrasePattern(
            "hurry", "limited time", "limited offer", "last chance", "don't miss", "do not miss",
            "act now", "today only", "while stocks last", "while supplies last", "offer expires",
            "expires soon", "book now",
        ),
        ToneIssueCategory.CALL_TO_ACTION to phrasePattern(
            "buy now", "shop now", "order now", "click here", "click the link", "visit our",
            "subscribe now", "sign up now", "call now to",
        ),
        // Numeric "10% off"-style discounts are handled separately in check()
        // (NUMERIC_PERCENT_OFF) since "%" isn't a word character and doesn't
        // fit the \b-wrapped phrase matcher below.
        ToneIssueCategory.DISCOUNT_FRAMING to phrasePattern(
            "percent off", "on sale", "save up to", "best price", "lowest price", "bargain", "discount",
        ),
        ToneIssueCategory.PRIZE_OR_INCENTIVE to phrasePattern(
            "win a", "you have won", "you've won", "you win", "winner", "free gift", "giveaway",
            "congratulations you", "claim your prize", "100% free", "absolutely free",
        ),
    )

    /** Case-insensitive, whole-word/phrase match for each of [phrases]. */
    private fun phrasePattern(vararg phrases: String): Regex {
        val alternatives = phrases.joinToString("|") { Regex.escape(it) }
        return Regex("""\b(?:$alternatives)\b""", RegexOption.IGNORE_CASE)
    }
}
