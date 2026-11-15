package eu.teele.neugesplitter.domain.statistics

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.MemberId
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StatisticsTest {

    private val everyone = Side.Equal(listOf(ANNA, BEN, CLARA))
    private val groceries = BuiltInCategory.GROCERIES.id
    private val housing = BuiltInCategory.HOUSING.id

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun people() = Log().create().add(ANNA, "Anna").add(BEN, "Ben").add(CLARA, "Clara")

    private fun statistics(log: Log, today: eu.teele.neugesplitter.domain.value.EntryDate = TODAY) =
        Statistics(log.replay(), today)

    private fun entry(
        n: Int,
        amount: Long,
        date: String,
        kind: Kind = Kind.EXPENSE,
        category: eu.teele.neugesplitter.domain.value.CategoryId? = null,
        payers: Side = Side.Equal(listOf(ANNA)),
        beneficiaries: Side = everyone,
    ) =
        entry(n, cents(amount), payers, beneficiaries, kind)
            .copy(date = date(date), categoryId = category)

    @Test
    fun `period boundaries follow the chosen calendar (BA-15)`() {
        val date = LocalDate.parse("2026-08-31")
        assertEquals(date, Granularity.DAY.start(date, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.parse("2026-08-31"), Granularity.WEEK.start(date, DayOfWeek.MONDAY))
        assertEquals(LocalDate.parse("2026-08-30"), Granularity.WEEK.start(date, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.parse("2026-08-01"), Granularity.MONTH.start(date, DayOfWeek.MONDAY))
    }

    @Test
    fun `next period advances across month and year ends (BA-15)`() {
        assertEquals(
            LocalDate.parse("2026-09-01"),
            Granularity.DAY.next(LocalDate.parse("2026-08-31")),
        )
        assertEquals(
            LocalDate.parse("2027-01-04"),
            Granularity.WEEK.next(LocalDate.parse("2026-12-28")),
        )
        assertEquals(
            LocalDate.parse("2027-01-01"),
            Granularity.MONTH.next(LocalDate.parse("2026-12-01")),
        )
        assertEquals(
            LocalDate.parse("2027-01-01"),
            Granularity.DAY.next(LocalDate.parse("2026-12-31")),
        )
    }

    @Test
    fun `pending suggestions and future or deleted entries are excluded (BA-9)`() {
        val log =
            people()
                .record(entry(1, 10, "2026-08-29"))
                .record(entry(2, 20, "2026-08-30"))
                .delete(eu.teele.neugesplitter.domain.entryId(2))
                .record(entry(3, 30, "2026-09-01"))
                .start(
                    RENT,
                    "2026-08-30",
                    segment(
                        Interval.EveryDays(1),
                        template(cents(5), Side.Equal(listOf(ANNA)), everyone),
                        Mode.SUGGESTED,
                    ),
                )
                .start(
                    eu.teele.neugesplitter.domain.value.SeriesId.parse("%032x".format(0x5e2)),
                    "2026-08-30",
                    segment(
                        Interval.EveryDays(1),
                        template(cents(7), Side.Equal(listOf(ANNA)), everyone),
                    ),
                )

        val stats = statistics(log)
        assertEquals(
            listOf("2026-08-29", "2026-08-30", "2026-08-31"),
            stats.entries.map { it.date.toString() },
        )
        assertEquals(LocalDate.parse("2026-08-29"), stats.first)
    }

    @Test
    fun `first is null when the group has no entries (BA-9)`() {
        assertNull(statistics(people()).first)
    }

    @Test
    fun `balances accumulate through period ends and keep earlier starting entries (BA-9)`() {
        val log =
            people()
                .record(entry(1, 300, "2026-08-29", beneficiaries = Side.Equal(listOf(ANNA, BEN))))
                .record(entry(2, 120, "2026-08-31", beneficiaries = Side.Equal(listOf(ANNA, BEN))))
        val stats = statistics(log)
        val periods =
            stats.balances(
                listOf(ANNA, BEN),
                LocalDate.parse("2026-08-30"),
                LocalDate.parse("2026-09-01"),
                Granularity.DAY,
                DayOfWeek.MONDAY,
            )

        assertEquals(
            listOf(
                Period(
                    LocalDate.parse("2026-08-30"),
                    mapOf(ANNA to cents(150), BEN to cents(-150)),
                ),
                Period(
                    LocalDate.parse("2026-08-31"),
                    mapOf(ANNA to cents(210), BEN to cents(-210)),
                ),
                Period(LocalDate.parse("2026-09-01"), mapOf(ANNA to cents(210), BEN to cents(-210))),
            ),
            periods,
        )
        assertEquals(listOf(ANNA, BEN), periods.last().value.keys.toList())
        assertEquals(
            stats.group.balances(TODAY).filterKeys { it == ANNA || it == BEN },
            periods.last().value,
        )
    }

    @Test
    fun `spending signs shares categories and rest follow the perspective (BA-10)`() {
        val beneficiaries = Side.Shares(mapOf(ANNA to 2L, BEN to 1L))
        val log =
            people()
                .record(
                    entry(1, 300, "2026-08-31", category = groceries, beneficiaries = beneficiaries)
                )
                .record(
                    entry(2, 90, "2026-08-31", kind = Kind.INCOME, beneficiaries = beneficiaries)
                )
                .record(
                    entry(
                        3,
                        60,
                        "2026-08-31",
                        kind = Kind.TRANSFER,
                        category = housing,
                        beneficiaries = beneficiaries,
                    )
                )
        val stats = statistics(log)
        val spending =
            stats
                .spending(
                    Perspective.Share(ANNA),
                    LocalDate.parse("2026-08-31"),
                    LocalDate.parse("2026-08-31"),
                    Granularity.DAY,
                    DayOfWeek.MONDAY,
                    listOf(null, groceries, housing),
                )
                .single()
                .value

        assertEquals(cents(140), spending.total)
        assertEquals(listOf(cents(-60), cents(200), Amount.ZERO), spending.parts)
        assertEquals(Amount.ZERO, spending.rest)
        val categorized =
            stats
                .spending(
                    Perspective.Share(ANNA),
                    LocalDate.parse("2026-08-31"),
                    LocalDate.parse("2026-08-31"),
                    Granularity.DAY,
                    DayOfWeek.MONDAY,
                    listOf(groceries),
                )
                .single()
                .value
        assertEquals(cents(-60), categorized.rest)
    }

    @Test
    fun `weekly spending is aligned and includes empty calendar periods (BA-10, BA-15)`() {
        val log = people().record(entry(1, 10, "2026-08-26")).record(entry(2, 20, "2026-09-02"))
        val periods =
            statistics(log, date("2026-09-10"))
                .spending(
                    Perspective.Everyone,
                    LocalDate.parse("2026-08-26"),
                    LocalDate.parse("2026-09-08"),
                    Granularity.WEEK,
                    DayOfWeek.MONDAY,
                )

        assertEquals(
            listOf(
                Period(LocalDate.parse("2026-08-24"), Spending(cents(10), emptyList())),
                Period(LocalDate.parse("2026-08-31"), Spending(cents(20), emptyList())),
                Period(LocalDate.parse("2026-09-07"), Spending(Amount.ZERO, emptyList())),
            ),
            periods,
        )
    }

    @Test
    fun `categories sum inclusively sort positive totals and omit zero or negative totals (BA-11)`() {
        val log =
            people()
                .record(entry(1, 50, "2026-08-01", category = groceries))
                .record(entry(2, 70, "2026-08-10", category = groceries))
                .record(entry(3, 80, "2026-08-20", category = housing))
                .record(entry(4, 0, "2026-08-15", category = BuiltInCategory.UTILITIES.id))
                .record(entry(5, -10, "2026-08-15", category = BuiltInCategory.TRANSPORTATION.id))
                .record(entry(6, 500, "2026-07-31", category = BuiltInCategory.HEALTH.id))
                .record(entry(7, 600, "2026-09-01", category = BuiltInCategory.HEALTH.id))
                .record(entry(8, 30, "2026-08-15"))

        assertEquals(
            listOf(housing to cents(80), groceries to cents(70), null to cents(30)),
            statistics(log)
                .categories(
                    Kind.EXPENSE,
                    Perspective.Everyone,
                    LocalDate.parse("2026-08-10"),
                    LocalDate.parse("2026-08-20"),
                ),
        )
        assertFailsWith<IllegalArgumentException> {
            statistics(log)
                .categories(
                    Kind.TRANSFER,
                    Perspective.Everyone,
                    LocalDate.parse("2026-08-01"),
                    LocalDate.parse("2026-08-31"),
                )
        }
    }

    @Test
    fun `the member category matrix counts paid and consumed expense parts only (BA-12)`() {
        val payers = Side.Shares(mapOf(ANNA to 2L, BEN to 1L))
        val beneficiaries = Side.Shares(mapOf(BEN to 2L, CLARA to 1L))
        val log =
            people()
                .record(
                    entry(
                        1,
                        300,
                        "2026-08-31",
                        category = groceries,
                        payers = payers,
                        beneficiaries = beneficiaries,
                    )
                )
                .record(entry(2, 90, "2026-08-31", kind = Kind.INCOME, category = groceries))
                .record(entry(3, 60, "2026-08-31", kind = Kind.TRANSFER, category = housing))

        val stats = statistics(log)
        assertEquals(
            mapOf<CategoryId?, Map<MemberId, Amount>>(
                groceries to mapOf(ANNA to cents(200), BEN to cents(100))
            ),
            stats.matrix(Measure.PAID, LocalDate.parse("2026-08-31"), LocalDate.parse("2026-08-31")),
        )
        assertEquals(
            mapOf<CategoryId?, Map<MemberId, Amount>>(
                groceries to mapOf(BEN to cents(200), CLARA to cents(100))
            ),
            stats.matrix(
                Measure.CONSUMED,
                LocalDate.parse("2026-08-31"),
                LocalDate.parse("2026-08-31"),
            ),
        )
    }

    @Test
    fun `a day of the month is sampled only in months that have it (BA-13)`() {
        val log =
            people()
                .record(entry(1, 1, "2025-01-01"))
                .record(entry(2, 10, "2025-10-31"))
                .record(entry(3, 30, "2026-01-31"))
                .record(entry(4, 50, "2026-08-31"))
        val position =
            statistics(log)
                .averages(Cycle.MONTH, 1, Perspective.Everyone, DayOfWeek.MONDAY)
                .single { it.index == 31 }

        assertEquals(7, position.samples)
        assertEquals(Amount.ofUnits(cents(90).units / 7), position.mean)
        assertEquals(Amount.ZERO, position.min)
        assertEquals(cents(50), position.max)
    }

    @Test
    fun `week positions start at firstDay and include only passed days of the current week (BA-13)`() {
        val log =
            people()
                .record(entry(1, 5, "2026-08-30"))
                .record(entry(2, 10, "2026-08-31"))
                .record(entry(3, 20, "2026-09-01"))
        val positions =
            statistics(log).averages(Cycle.WEEK, 1, Perspective.Everyone, DayOfWeek.SUNDAY)

        assertEquals(listOf(0, 1), positions.map { it.index })
        assertEquals(1, positions.single { it.index == 0 }.samples)
        assertEquals(cents(5), positions.single { it.index == 0 }.mean)
        assertEquals(1, positions.single { it.index == 1 }.samples)
        assertEquals(cents(10), positions.single { it.index == 1 }.mean)
    }

    @Test
    fun `year positions sum by month within the rolling window (BA-13)`() {
        val log =
            people()
                .record(entry(1, 1, "2025-01-01"))
                .record(entry(2, 10, "2025-09-01"))
                .record(entry(3, 20, "2025-09-20"))
                .record(entry(4, 7, "2025-10-03"))
                .record(entry(5, 40, "2026-08-31"))
        val positions =
            statistics(log).averages(Cycle.YEAR, 1, Perspective.Everyone, DayOfWeek.MONDAY)

        assertEquals((1..12).toList(), positions.map { it.index })
        val september = positions.single { it.index == 9 }
        assertEquals(cents(30), september.mean)
        assertEquals(cents(30), september.min)
        assertEquals(cents(30), september.max)
        assertEquals(1, september.samples)
        assertEquals(cents(40), positions.single { it.index == 8 }.mean)
    }

    @Test
    fun `the current partial month sums its passed days and excludes later entries (BA-13)`() {
        val log =
            people()
                .record(entry(1, 10, "2026-08-01"))
                .record(entry(2, 20, "2026-08-10"))
                .record(entry(3, 100, "2026-08-11"))
        val positions =
            statistics(log, date("2026-08-10"))
                .averages(Cycle.YEAR, 1, Perspective.Everyone, DayOfWeek.MONDAY)

        assertEquals(cents(30), positions.single { it.index == 8 }.mean)
        assertEquals(1, positions.single { it.index == 8 }.samples)
    }

    @Test
    fun `averages are empty without entries and require a positive window (BA-13)`() {
        val stats = statistics(people())
        assertEquals(
            emptyList(),
            stats.averages(Cycle.MONTH, 1, Perspective.Everyone, DayOfWeek.MONDAY),
        )
        assertFailsWith<IllegalArgumentException> {
            stats.averages(Cycle.MONTH, 0, Perspective.Everyone, DayOfWeek.MONDAY)
        }
    }

    @Test
    fun `flows use the income and expense category totals (BA-14)`() {
        val log =
            people()
                .record(entry(1, 100, "2026-08-31", category = groceries))
                .record(entry(2, 60, "2026-08-31", kind = Kind.INCOME, category = housing))
        val flows =
            statistics(log)
                .flows(
                    Perspective.Everyone,
                    LocalDate.parse("2026-08-31"),
                    LocalDate.parse("2026-08-31"),
                )

        assertEquals(
            listOf(Node.Category(housing, cents(60)), Node.Balance(cents(40))),
            flows.income,
        )
        assertEquals(listOf(Node.Category(groceries, cents(100))), flows.expenses)
        assertEquals(listOf(Link(0, 0, cents(60)), Link(1, 0, cents(40))), flows.links)
    }
}
