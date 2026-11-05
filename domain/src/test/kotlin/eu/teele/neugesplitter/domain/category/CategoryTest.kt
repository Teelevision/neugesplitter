package eu.teele.neugesplitter.domain.category

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.CategoryChanges
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryTest {

    private val group = GroupId.parse("01".repeat(16))
    private val anna = MemberId.parse("02".repeat(16))
    private val at = Instant.parse("2026-10-01T12:00:00Z")
    private val categories = defaultCategories()

    private fun eventId(n: Int) = EventId.parse("%032x".format(n))

    private fun groupWithEntry(): Pair<Group, Entry> {
        val category = categories.first()
        val entry =
            Entry(
                EntryId.parse("03".repeat(16)),
                Kind.EXPENSE,
                "Rent",
                EntryDate.parse("2026-10-01"),
                Amount.parse("100000000"),
                Side.Equal(listOf(anna)),
                Side.Equal(listOf(anna)),
                categoryId = category.id,
            )
        val events =
            listOf(
                Event.GroupCreated(
                    eventId(1),
                    anna,
                    at,
                    GroupDetails(group, Scale.DEFAULT, "Home"),
                ),
                Event.MemberAdded(eventId(2), anna, at, Member(anna, "Anna")),
                Event.EntryCreated(eventId(3), anna, at, entry),
            )
        return Group.replay(events) to entry
    }

    @Test
    fun `built in categories have hard coded global identifiers`() {
        assertEquals(18, categories.size)
        var next = 20
        val anotherGroup =
            Group.replay(
                Group.create(
                    GroupId.parse("03".repeat(16)),
                    "Another",
                    null,
                    null,
                    null,
                    listOf(Member(anna, "Anna")),
                    at,
                ) {
                    eventId(next++)
                }
            )
        assertEquals(categories.associateBy { it.id }, anotherGroup.categories)
        assertEquals(18, categories.map { it.id }.distinct().size)
        assertEquals(
            listOf(
                "e27c346bf48a5e7fc3312e086ae54a1a",
                "9d1c8c53bbe11a0f34507d1f2c775398",
                "a5c6e4e231756a27c3170a2e6da6059e",
                "7de2f5f349dac2125cd903cb850d79c4",
                "adc2a031cf5f9237d2051b32f3d95ee7",
                "95e897ffb0ba843759a50ce0342830dd",
                "5025cc4ee7ab924c659c199c53c5f4e1",
                "9a4dc6409cf4fae466a71c6687add748",
                "2d494e77b363c800d71ac3cd3cf8be15",
                "62b5983e7ac63874ae75d53e5dfe9fb9",
                "32d04080c42a3ae1b0187286ceea72f7",
                "bd94347b49ab4fa29e52c756536ba25f",
                "f11d9201b625485754074620b1e706e2",
                "54a51b98ed699999033f8a066deb2062",
                "39bcaa0f20466c9b20356f5045db0261",
                "5b8151f0394ded76ba68fb8bdde37aca",
                "365b2e04c25cd8a1a3af4585a0c1f586",
                "2756a3df43883aaf1c05fd26d12ee5a9",
            ),
            BuiltInCategory.entries.map { it.id.hex },
        )
        assertEquals("gifts_and_donations", BuiltInCategory.GIFTS_AND_DONATIONS.key)
        assertEquals("family", BuiltInCategory.FAMILY.key)
        assertEquals("insurance", BuiltInCategory.INSURANCE.key)
    }

    @Test
    fun `new groups derive built in categories on replay`() {
        var next = 10
        val events =
            Group.create(group, "Home", null, null, null, listOf(Member(anna, "Anna")), at) {
                eventId(next++)
            }

        assertEquals(categories.associateBy { it.id }, Group.replay(events).categories)
    }

    @Test
    fun `built in categories cannot be created by an event`() {
        var next = 10
        val groupState =
            Group.replay(
                Group.create(group, "Home", null, null, null, listOf(Member(anna, "Anna")), at) {
                    eventId(next++)
                }
            )
        val category = categories.first()

        assertFailsWith<Malformed> {
            groupState + Event.CategoryCreated(eventId(11), anna, at, category)
        }
    }

    @Test
    fun `unknown entry category identifiers are treated as uncategorized`() {
        val (groupState, entry) = groupWithEntry()
        val unknown = CategoryId.parse("ff".repeat(16))
        val updated =
            groupState +
                Event.EntryUpdated(
                    eventId(4),
                    anna,
                    at,
                    entry.id,
                    EntryChanges(categoryId = Change.To(unknown)),
                )

        assertNull(updated.entries.getValue(entry.id).categoryId)
    }

    @Test
    fun `unknown recurring category identifiers are treated as uncategorized`() {
        val (groupState, entry) = groupWithEntry()
        val unknown = CategoryId.parse("ff".repeat(16))
        val seriesId = SeriesId.parse("05".repeat(16))
        val event =
            Event.SeriesCreated(
                eventId(4),
                anna,
                at,
                seriesId,
                entry.date,
                Segment(
                    Interval.EveryDays(1),
                    Mode.AUTOMATIC,
                    Template.of(entry.copy(categoryId = unknown)),
                ),
            )

        val created = groupState + event

        assertNull(created.series.getValue(seriesId).segments.single().second.template.categoryId)
    }

    @Test
    fun `updates to unknown category identifiers are ignored`() {
        val (groupState, _) = groupWithEntry()
        val unknown = CategoryId.parse("ff".repeat(16))

        val updated =
            groupState +
                Event.CategoryUpdated(
                    eventId(4),
                    anna,
                    at,
                    unknown,
                    CategoryChanges(emoji = Change.To("❔")),
                )
        val deleted = updated + Event.CategoryDeleted(eventId(5), anna, at, unknown)
        val restored = deleted + Event.CategoryRestored(eventId(6), anna, at, unknown)

        assertEquals(groupState, restored)
    }

    @Test
    fun `soft deleting a category preserves assigned entries and can be undone`() {
        val (groupState, entry) = groupWithEntry()
        val category = categories.first()
        val deleted = groupState + Event.CategoryDeleted(eventId(4), anna, at, category.id)

        assertTrue(deleted.categories.getValue(category.id).deleted)
        assertEquals(category.id, deleted.entries.getValue(entry.id).categoryId)
        assertEquals(entry, deleted.entries.getValue(entry.id))

        val restored = deleted + Event.CategoryRestored(eventId(5), anna, at, category.id)
        assertFalse(restored.categories.getValue(category.id).deleted)
        assertEquals(category.id, restored.entries.getValue(entry.id).categoryId)
    }

    @Test
    fun `built in names remain localized when only the emoji changes`() {
        val (groupState, _) = groupWithEntry()
        val category = categories.first()
        val changed =
            groupState +
                Event.CategoryUpdated(
                    eventId(4),
                    anna,
                    at,
                    category.id,
                    CategoryChanges(emoji = Change.To("🏘️")),
                )

        assertNull(changed.categories.getValue(category.id).name)
        assertEquals("🏘️", changed.categories.getValue(category.id).emoji)
    }

    @Test
    fun `custom categories cannot lose their mandatory name`() {
        val category = Category(CategoryId.parse("04".repeat(16)), name = "Books", emoji = "📚")
        val created = groupWithEntry().first
        val withCustom = created + Event.CategoryCreated(eventId(4), anna, at, category)

        assertFailsWith<Malformed> {
            withCustom +
                Event.CategoryUpdated(
                    eventId(5),
                    anna,
                    at,
                    category.id,
                    CategoryChanges(name = Change.To(null)),
                )
        }
    }
}
