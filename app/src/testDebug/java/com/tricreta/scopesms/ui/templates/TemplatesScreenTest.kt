package com.tricreta.scopesms.ui.templates

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseWindow
import com.tricreta.scopesms.domain.templates.SmsSegments
import com.tricreta.scopesms.domain.templates.TemplateEngine
import com.tricreta.scopesms.domain.templates.TemplateType
import com.tricreta.scopesms.ui.theme.ScopeSmsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Messages (Templates) tab, composed and laid out off-device.
 *
 * ## Why this test exists
 * The Messages tab has force-closed on the agent's handset since the first
 * build, and two layout-only fixes did not stop it. Every earlier automated
 * test stopped below the UI: 276 pure-JVM tests prove the parser, the rules, the
 * template engine and the queue, and [com.tricreta.scopesms.LaunchTest] only
 * reaches the onboarding screen on a fresh install — **none of them compose the
 * Templates screen, and none of them do it with real data.**
 *
 * A Robolectric Compose test runs Compose's *real* measure and layout pass on
 * the JVM, so a genuine "infinity maximum height" measurement crash — or any
 * plain exception thrown while composing the screen — reproduces here exactly as
 * it would on the device. Feeding realistic data (a long multi-line
 * `{bundle_list}`, agent-customised bodies, an invalid token, a message that
 * spills to a second SMS segment) is the point: the empty default state the
 * older tests would have hit never exercises those paths.
 *
 * The screen is driven through the stateless [TemplatesContent], fed a
 * fabricated [TemplatesUiState] built the same way [TemplatesViewModel] builds
 * it — through the real [TemplateEngine] and [SmsSegments] — so the test sees
 * what the ViewModel would actually hand the composables.
 *
 * `qualifiers` pins a finite phone-sized display; a real handset's screen is
 * finite, and it is exactly that finite bound a mis-structured vertical scroll
 * fails to receive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class TemplatesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Several bundles, so `{bundle_list}` renders as a long, multi-line block —
     * the case that pushes the unmatched reply onto a second segment and that a
     * two-line stub would never surface.
     */
    private val rules = listOf(
        PricingRule(id = 1, amount = KshAmount.ofShillings(20), bundleDescription = "1GB Daily"),
        PricingRule(id = 2, amount = KshAmount.ofShillings(50), bundleDescription = "2GB Weekly"),
        PricingRule(id = 3, amount = KshAmount.ofShillings(100), bundleDescription = "5GB Weekly"),
        PricingRule(id = 4, amount = KshAmount.ofShillings(250), bundleDescription = "10GB Monthly"),
        PricingRule(id = 5, amount = KshAmount.ofShillings(1000), bundleDescription = "40GB Monthly"),
    )

    private fun unmatchedEditor(body: String): TemplateEditorState {
        val preview = TemplateEngine.render(
            body,
            TemplateEngine.unmatchedValues(
                name = "John Kamau",
                amount = KshAmount.ofShillings(35),
                phone = "0712345678",
                activeRules = rules,
            ),
        )
        return TemplateEditorState(
            type = TemplateType.UNMATCHED,
            body = body,
            savedBody = body,
            preview = preview,
            length = SmsSegments.measure(preview),
            validation = TemplateEngine.validate(body, TemplateType.UNMATCHED),
            isDefault = false,
        )
    }

    private fun matchedEditor(body: String): TemplateEditorState {
        val preview = TemplateEngine.render(
            body,
            TemplateEngine.matchedValues(
                name = "John Kamau",
                amount = KshAmount.ofShillings(50),
                phone = "0712345678",
                matchedRule = rules[1],
            ),
        )
        return TemplateEditorState(
            type = TemplateType.MATCHED,
            body = body,
            savedBody = body,
            preview = preview,
            length = SmsSegments.measure(preview),
            validation = TemplateEngine.validate(body, TemplateType.MATCHED),
            isDefault = false,
        )
    }

    /** A bundle with a restricted window, matching the off-window flow's real use. */
    private val restrictedRule = PricingRule(
        id = 6,
        amount = KshAmount.ofShillings(19),
        bundleDescription = "1GB 1Hr",
        purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59), // 4:00 PM to 10:59 PM
    )

    private fun offWindowEditor(body: String): TemplateEditorState {
        val preview = TemplateEngine.render(
            body,
            TemplateEngine.offWindowValues(
                name = "John Kamau",
                amount = KshAmount.ofShillings(19),
                phone = "0712345678",
                matchedRule = restrictedRule,
            ),
        )
        return TemplateEditorState(
            type = TemplateType.OFF_WINDOW,
            body = body,
            savedBody = body,
            preview = preview,
            length = SmsSegments.measure(preview),
            validation = TemplateEngine.validate(body, TemplateType.OFF_WINDOW),
            isDefault = false,
        )
    }

    private fun stateWith(
        unmatchedBody: String =
            "Hi {name}, we received Ksh {amount} on {phone} but it does not match a bundle. " +
                "Today's offers:\n{bundle_list}\nReply with the exact price to buy.",
        matchedBody: String =
            "Hi {name}, thank you for purchasing {package} for Ksh {amount}. " +
                "It is being processed now.",
        offWindowBody: String =
            "Hi {name}, {package} can only be purchased between {purchase_window}. " +
                "Your order is noted - no need to resend.",
    ) = TemplatesUiState(
        unmatched = unmatchedEditor(unmatchedBody),
        matched = matchedEditor(matchedBody),
        offWindow = offWindowEditor(offWindowBody),
    )

    @Test
    fun rendersAllThreeTabsWithRealisticData() {
        compose.setContent {
            ScopeSmsTheme { TemplatesContent(state = stateWith()) }
        }
        compose.waitForIdle()

        // Reaching here at all means the screen composed, measured and laid out.
        // The tabs live in the pinned topBar, so they are always on screen; the
        // Preview sits below the fold in the scroll body — it is composed (a
        // verticalScroll Column measures every child, unlike a LazyColumn) but not
        // necessarily displayed, so assert existence, not visibility.
        compose.onNodeWithText("Price list reply").assertIsDisplayed()
        compose.onNodeWithText("Purchase confirmation").assertIsDisplayed()
        compose.onNodeWithText("Outside buying hours").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertExists()
    }

    /**
     * The third tab, added for the bundle purchase-window feature. This exact
     * kind of change — a new tab appended to [TemplateType.entries] — is what
     * regressed the saved-tab-index crash this file exists to catch (see the
     * class doc), so it gets its own explicit switch-and-render exercise
     * rather than relying on the two-tab tests above to catch a three-tab bug.
     */
    @Test
    fun switchingToOffWindowTabRenders() {
        compose.setContent {
            ScopeSmsTheme { TemplatesContent(state = stateWith()) }
        }
        compose.onNodeWithText("Outside buying hours").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Preview").assertExists()
    }

    /**
     * The faithful arrangement: [TemplatesContent] hosted in the content slot of
     * an outer Scaffold with a bottomBar, fed `Modifier.padding(padding)` — the
     * exact way [com.tricreta.scopesms.ui.ScopeSmsApp]'s MainScaffold places it.
     *
     * This nests the screen's own Scaffold inside another Scaffold, the structural
     * outlier that neither Home nor Settings has and that the root-level renders
     * above do not exercise. If a nested vertical scroll were going to be measured
     * with an unbounded height, this is the shape that would surface it.
     */
    @Test
    fun rendersNestedInsideAppScaffoldLikeTheRealApp() {
        compose.setContent {
            ScopeSmsTheme {
                Scaffold(bottomBar = { Text("nav bar") }) { padding ->
                    TemplatesContent(
                        state = stateWith(),
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Price list reply").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertExists()
    }

    @Test
    fun switchingToConfirmationTabRenders() {
        compose.setContent {
            ScopeSmsTheme { TemplatesContent(state = stateWith()) }
        }
        compose.onNodeWithText("Purchase confirmation").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Preview").assertExists()
    }

    /**
     * The agent saved a body with a typo (`{nmae}`) and a variable that has no
     * value in this flow (`{package}` in the unmatched reply). That makes
     * `validation` invalid and renders the error card — a composable the default,
     * valid templates never bring on screen.
     */
    @Test
    fun rendersInvalidTemplateWithErrorCard() {
        val badBody = "Hi {nmae}, you paid Ksh {amount} for {package}.\n{bundle_list}"
        compose.setContent {
            ScopeSmsTheme {
                TemplatesContent(state = stateWith(unmatchedBody = badBody))
            }
        }
        compose.waitForIdle()

        // The unknown-token error copy is unique to the error card; {nmae} itself
        // also appears in the editor and the verbatim preview, so match the card.
        compose.onAllNodesWithText("Not a real variable", substring = true)
            .onFirst()
            .assertExists()
    }

    /**
     * A long price list spills the unmatched reply past one segment, driving the
     * `willSplit` branch in the segment counter. Purely that this lays out.
     */
    @Test
    fun rendersMultiSegmentUnmatchedReply() {
        val longBody = buildString {
            append("Habari {name}, tumepokea Ksh {amount} kutoka {phone}. ")
            append("Kiasi hiki hakilingani na bando lolote. Bei za leo:\n")
            append("{bundle_list}\n")
            append("Tafadhali tuma kiasi kamili cha bando unalotaka. Asante kwa biashara.")
        }
        compose.setContent {
            ScopeSmsTheme {
                TemplatesContent(state = stateWith(unmatchedBody = longBody))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Price list reply").assertIsDisplayed()
    }

    /**
     * Typing into the editor: onEdit updates hoisted state and the screen
     * recomposes against the new body, the same loop the ViewModel drives.
     */
    @Test
    fun typingInTheEditorRerendersWithoutCrashing() {
        compose.setContent {
            ScopeSmsTheme {
                var state by remember { mutableStateOf(stateWith(unmatchedBody = "Hi {name}")) }
                TemplatesContent(
                    state = state,
                    onEdit = { type, body ->
                        state = if (type == TemplateType.UNMATCHED) {
                            state.copy(unmatched = unmatchedEditor(body))
                        } else {
                            state.copy(matched = matchedEditor(body))
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("Hi {name}").performTextInput(", thanks {bundle_list}")
        compose.waitForIdle()

        compose.onNodeWithText("Preview").assertExists()
    }
}
