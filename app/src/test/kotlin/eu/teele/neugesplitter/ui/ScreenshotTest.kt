package eu.teele.neugesplitter.ui

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.teele.neugesplitter.AppGraph
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tour of the screens in German on a narrow phone, at normal, large and the largest text, to be
 * looked over for clipped or truncated labels (UX-5, UX-6). It records only under `make
 * screenshots`, into `app/build/outputs/roborazzi`, and is skipped otherwise.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w360dp-h780dp-xhdpi")
class ScreenshotTest(private val scale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font scale {0}")
        fun scales() = listOf(1.0f, 1.3f, 2.0f)
    }

    private val compose = createComposeRule()

    // The activity reads the font scale when it starts, so it is set before the rule starts it.
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(
                object : ExternalResource() {
                    override fun before() = RuntimeEnvironment.setFontScale(scale)
                }
            )
            .around(compose)

    private val context = RuntimeEnvironment.getApplication()
    private val graph = AppGraph(context)
    private val directory = "build/outputs/roborazzi/de-${(scale * 100).toInt()}"
    private var events = 0

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun id(n: Int) = "%032x".format(n)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun euros(cents: Long) = Amount.ofUnits(cents * 1_000_000L)

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitDescription(text: String) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        idle()
        captureScreenRoboImage("$directory/$name.png")
    }

    private fun back() {
        Espresso.pressBack()
        idle()
    }

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Benedikt")
    private val clara = Member(MemberId.parse(id(3)), "Charlotte")
    private val max = Member(MemberId.parse(id(4)), "Maximilian")
    private val everyone = listOf(anna.id, ben.id, clara.id, max.id)
    private val home = GroupId.parse(id(9))
    private val groceries = EntryId.parse(id(10))
    private val name = "WG Kastanienallee"

    /**
     * A shared flat with a bit of everything: plain, shared-out and edited entries, an income and a
     * transfer, monthly rent added automatically, weekly supplies waiting to be confirmed, and an
     * entry another member's device sent that nobody here has seen.
     */
    private fun flat() {
        val today = graph.today().value
        fun ago(days: Long) = EntryDate.of(today.minusDays(days))
        val at = Instant.now().minus(Duration.ofDays(20))
        val created =
            Group.create(
                home,
                name,
                "Haushaltskasse der WG",
                "🏠",
                GROUP_COLOURS[1],
                listOf(anna, ben, clara, max),
                at,
                ::eventId,
            )
        graph.groups.create(created, graph.today())
        fun entry(
            n: Int,
            kind: Kind,
            description: String,
            days: Long,
            cents: Long,
            payers: Side,
            beneficiaries: Side,
        ) =
            Entry(
                EntryId.parse(id(n)),
                kind,
                description,
                ago(days),
                euros(cents),
                payers,
                beneficiaries,
            )
        val equal = Side.Equal(everyone)
        val rent =
            Template(Kind.EXPENSE, "Miete", euros(184000), Side.Equal(listOf(anna.id)), equal)
        val supplies =
            Template(
                Kind.EXPENSE,
                "Putzmittel und Müllbeutel",
                euros(1290),
                Side.Equal(listOf(ben.id)),
                equal,
            )
        graph.groups.append(
            home,
            listOf(
                Event.EntryCreated(
                    eventId(),
                    anna.id,
                    at,
                    entry(
                        10,
                        Kind.EXPENSE,
                        "Wocheneinkauf",
                        1,
                        8437,
                        Side.Equal(listOf(anna.id)),
                        equal,
                    ),
                ),
                Event.EntryCreated(
                    eventId(),
                    ben.id,
                    at,
                    entry(
                        11,
                        Kind.EXPENSE,
                        "Stromabschlag Oktober",
                        3,
                        6200,
                        Side.Equal(listOf(ben.id)),
                        equal,
                    ),
                ),
                Event.EntryCreated(
                    eventId(),
                    clara.id,
                    at,
                    entry(
                        12,
                        Kind.EXPENSE,
                        "Pizza vom Lieferdienst beim gemeinsamen Spieleabend im Wohnzimmer",
                        5,
                        5480,
                        Side.Equal(listOf(clara.id)),
                        Side.Shares(
                            mapOf(anna.id to 2L, ben.id to 1L, clara.id to 1L, max.id to 2L)
                        ),
                    ),
                ),
                Event.EntryCreated(
                    eventId(),
                    clara.id,
                    at,
                    entry(
                        13,
                        Kind.INCOME,
                        "Pfandrückgabe",
                        6,
                        425,
                        Side.Equal(listOf(clara.id)),
                        equal,
                    ),
                ),
                Event.EntryCreated(
                    eventId(),
                    max.id,
                    at,
                    entry(
                        14,
                        Kind.TRANSFER,
                        "Rückzahlung",
                        8,
                        5000,
                        Side.Equal(listOf(max.id)),
                        Side.Equal(listOf(anna.id)),
                    ),
                ),
                Event.EntryUpdated(
                    eventId(),
                    ben.id,
                    at,
                    groceries,
                    EntryChanges(
                        description = Change.To("Wocheneinkauf"),
                        amount = Change.To(euros(8437)),
                    ),
                ),
                Event.SeriesCreated(
                    eventId(),
                    anna.id,
                    at,
                    SeriesId.parse(id(20)),
                    EntryDate.of(today.withDayOfMonth(1).minusMonths(2)),
                    Segment(Interval.MonthlyOn(setOf(1)), Mode.AUTOMATIC, rent),
                ),
                Event.SeriesCreated(
                    eventId(),
                    ben.id,
                    at,
                    SeriesId.parse(id(21)),
                    ago(14),
                    Segment(Interval.WeeklyOn(setOf(today.dayOfWeek)), Mode.SUGGESTED, supplies),
                ),
            ),
            graph.today(),
        )
        graph.groups.receive(
            home,
            graph.today(),
            1,
            Event.EntryCreated(
                eventId(),
                clara.id,
                Instant.now(),
                entry(
                    15,
                    Kind.EXPENSE,
                    "Toilettenpapier",
                    0,
                    799,
                    Side.Equal(listOf(clara.id)),
                    equal,
                ),
            ),
        )
    }

    @Test
    fun tour() {
        flat()
        compose.setContent { NeugesplitterTheme { App(graph) } }

        awaitText(name)
        shot("01-gruppen")

        compose.onNodeWithContentDescription(s(R.string.destination_settings)).performClick()
        awaitText(s(R.string.theme))
        shot("02-app-einstellungen")
        back()

        compose.onNodeWithContentDescription(s(R.string.new_group)).performClick()
        awaitText(s(R.string.join_existing))
        shot("03-hinzufuegen")
        compose.onNodeWithText(s(R.string.new_group)).performClick()
        awaitText(s(R.string.group_name))
        shot("04-neue-gruppe")
        back()

        compose.onNodeWithContentDescription(s(R.string.new_group)).performClick()
        awaitText(s(R.string.join_existing))
        compose.onNodeWithText(s(R.string.join_existing)).performClick()
        awaitText(s(R.string.join_group))
        shot("05-beitreten")
        back()

        compose.onNodeWithText(name).performClick()
        awaitText(s(R.string.total_spending))
        shot("06-uebersicht")

        compose.onNodeWithText(s(R.string.destination_people)).performClick()
        awaitText(s(R.string.balances_header))
        shot("07-personen")

        compose.onNodeWithText(s(R.string.destination_activity)).performClick()
        awaitText(s(R.string.suggestions))
        compose
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(s(R.string.suggestion_confirm)))
        shot("08-aktivitaet")

        compose.onNodeWithText(s(R.string.destination_settings)).performClick()
        awaitText(s(R.string.group_name))
        shot("09-gruppen-einstellungen")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Miete"))
        shot("10-serien")

        compose.onNode(hasText("Miete") and hasClickAction()).performClick()
        awaitText(s(R.string.series_pause))
        shot("11-serie")
        back()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(s(R.string.storage_set_up)))
        compose.onNodeWithText(s(R.string.storage_set_up)).performClick()
        awaitText(s(R.string.setup_paste))
        shot("12-speicher")
        back()

        compose.onNodeWithText(s(R.string.destination_overview)).performClick()
        awaitText(s(R.string.total_spending))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Wocheneinkauf"))
        // At the largest text the row can sit under the navigation bar, where a tap would land.
        compose.onNodeWithText("Wocheneinkauf").performSemanticsAction(SemanticsActions.OnClick)
        awaitDescription(s(R.string.more_options))
        shot("13-eintrag")
        compose.onNodeWithContentDescription(s(R.string.more_options)).performClick()
        compose.onNodeWithText(s(R.string.history)).performClick()
        awaitText(s(R.string.history_edited, "Benedikt"))
        shot("14-verlauf")
        back()
        back()

        compose.onNodeWithContentDescription(s(R.string.new_entry)).performClick()
        awaitDescription(s(R.string.ok))
        shot("15-rechner")
        for (key in "47") compose.onNode(hasText("$key") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(s(R.string.ok)).performClick()
        compose
            .onNodeWithText(s(R.string.entry_description))
            .performTextInput("Getränke für die Einweihungsparty")
        shot("16-neuer-eintrag")

        compose.onNodeWithText(s(R.string.scheme_shares)).performScrollTo().performClick()
        awaitText(s(R.string.ok))
        shot("17-aufteilung")
        compose.onNodeWithText(s(R.string.ok)).performClick()
        idle()

        compose.onNodeWithContentDescription(s(R.string.repeats)).performScrollTo().performClick()
        awaitText(s(R.string.pattern_monthly))
        shot("18-wiederholung")
    }
}
