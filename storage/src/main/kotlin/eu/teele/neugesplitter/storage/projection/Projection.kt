package eu.teele.neugesplitter.storage.projection

import app.cash.sqldelight.db.SqlDriver
import eu.teele.neugesplitter.domain.entry.Entry as DomainEntry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.storage.Database

/**
 * Writes what the domain's replay made of a group into the derived tables (TR-18a).
 *
 * Nothing here decides what an event means. It is handed the group before and after, and rewrites
 * the rows of whatever the two states disagree on, so that the projection always equals what a
 * rebuild from the log would produce (TR-18).
 */
internal class Projection(private val driver: SqlDriver, private val queries: ProjectionQueries) {

    /**
     * Brings [after]'s rows up to date from [before], which is null for a group not yet projected,
     * and moves its occurrences and balances to [today] (RE-14).
     */
    fun write(before: Group?, after: Group, today: EntryDate) {
        val group = after.id.hex
        val moved = queries.projectedOn(group).executeAsOneOrNull() != today.toString()
        if (!moved && before == after) return

        with(after) {
            queries.putGroup(
                group,
                name,
                description,
                emoji,
                colour?.text,
                scale.digits.toLong(),
                today.toString(),
            )
        }

        for ((id, member) in after.members) {
            val removed = id in after.removed
            if (
                before == null || before.members[id] != member || (id in before.removed) != removed
            ) {
                queries.putMember(group, id.hex, member.name, removed.flag)
            }
        }

        for ((id, entry) in after.entries + after.deleted) {
            val deleted = id in after.deleted
            val was = before?.let { it.entries[id] ?: it.deleted[id] }
            if (was != entry || (before != null && id in before.deleted) != deleted) {
                queries.dropEntry(group, id.hex)
                put(group, entry, deleted)
            }
        }

        for ((id, series) in after.series) {
            if (moved || before?.series?.get(id) != series) {
                queries.dropOccurrences(group, id.hex)
                for (occurrence in series.occurrences(today)) {
                    put(group, occurrence.entry, deleted = false)
                    queries.putOccurrence(
                        group,
                        id.hex,
                        occurrence.due.toString(),
                        occurrence.entry.id.hex,
                        occurrence.pending.flag,
                    )
                }
            }
        }

        queries.dropBalances(group)
        val settled = after.settlement(today)
        for ((member, exact) in after.balances(today)) {
            queries.putBalance(group, member.hex, exact.units, settled.getValue(member).units)
        }
    }

    /** Drops every derived table and creates it again, empty and in its current shape (TR-18b). */
    fun discard() {
        for (table in TABLES) driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
        // Every table is created IF NOT EXISTS, so this touches only the ones just dropped.
        Database.Schema.create(driver)
    }

    private fun put(group: String, entry: DomainEntry, deleted: Boolean) {
        with(entry) {
            queries.putEntry(
                group,
                id.hex,
                kind.name.lowercase(),
                description,
                date.toString(),
                amount.units,
                note,
                deleted.flag,
            )
        }
        put(group, entry, "payers", entry.payers)
        put(group, entry, "beneficiaries", entry.beneficiaries)
    }

    private fun put(group: String, entry: DomainEntry, role: String, side: Side) {
        val parts = side.parts(entry.amount)
        side.members.forEachIndexed { position, member ->
            val input =
                when (side) {
                    is Side.Equal -> null
                    is Side.Shares -> side.input.getValue(member)
                    is Side.Adjustments -> side.input.getValue(member).units
                    is Side.Absolute -> side.input.getValue(member).units
                }
            queries.putSide(
                group,
                entry.id.hex,
                role,
                position.toLong(),
                member.hex,
                side.scheme.name.lowercase(),
                input,
                parts.getValue(member).units,
            )
        }
    }

    companion object {
        /**
         * The version of replay and of the derived tables. Raising it is how a change to either
         * reaches an installed database: the next start discards the projection and rebuilds it
         * before anything is read (TR-18b).
         */
        const val VERSION: Long = 1

        /** The derived tables of architecture.md 5; every other table is a source of truth. */
        val TABLES = listOf("group_row", "member", "entry", "side", "occurrence", "balance")
    }
}

private val Boolean.flag: Long
    get() = if (this) 1 else 0
