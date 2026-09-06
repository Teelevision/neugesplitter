# Event Model

**Status:** Draft. Design thinking, not a specification. The transport these
events travel over is in [sync-design.md](sync-design.md); the behaviour they
have to produce is in [product-requirements.md](product-requirements.md).

---

## 1. What an event is

A group is a sequence of events and nothing else (HI-1). Replaying them in log
order produces the member list, the entries, the balances and the history
screen. Nothing is stored that replay cannot reconstruct.

Every event has the same envelope:

| Field   | Meaning                                                                          |
| ------- | -------------------------------------------------------------------------------- |
| `id`    | Identifies the _intent_, not the occasion. See section 2.                        |
| `type`  | `entry.created`, `member.added`, and so on.                                      |
| `by`    | The member who did it (HI-2). Not the installation.                              |
| `at`    | When, as a UTC instant. A record, never a tiebreaker — order comes from the log. |
| payload | Named after the type, e.g. `entry`.                                              |

`at` and the entry's own `date` (EN-3) are different things and are deliberately
different types. `at` is an instant and belongs to the event; `date` is a
calendar date with no time and no zone, belongs to the entry, and is chosen by
the user. Conflating them would make an expense drift across midnight depending
on where it was entered.

## 2. What an event id means

Two objects can carry the same event id, and then the one at the higher number
wins (sync-design section 5). That rule only earns its keep if ids are chosen so
that a collision means _the same intent stated twice_. There are two ways an id
is produced:

- **Random**, for anything a person does deliberately. Two people adding an
  expense at the same moment are two intents, so two ids, and both entries
  exist.
- **Derived**, for anything two installations could compute independently. The
  only case is a recurring occurrence, whose id comes from the series id and the
  due date (section 6). If two installations act on the same occurrence at once,
  they produce the same id, and the two events collapse into one instead of into
  two.

An id is never reused to mean "the corrected version of that". A correction is a
new event with a new id that names its target (section 4).

## 3. Entries

Expense, income and transfer are one shape with a `kind`, not three shapes
(EN-1). All three have an amount, a payer side and a beneficiary side; the kind
only decides the sign and whether the entry counts as spending:

| Kind       | Balances                                      | Counts as spending (BA-8) |
| ---------- | --------------------------------------------- | ------------------------- |
| `expense`  | payer side credited, beneficiary side debited | yes                       |
| `income`   | the same, negated (EN-8)                      | no                        |
| `transfer` | as an expense (EN-7)                          | no                        |

**An amount is an integer count of 10⁻⁸ units**, and it is written as one:
`"123000000"` for what somebody typed as 1.23. A JSON string, because such an
integer outgrows the range a JSON number survives and too many parsers coerce
numbers to binary floating point on the way past; and no decimal point anywhere,
because a point would give every value two spellings for implementations to
disagree about.

One fixed unit everywhere, finer than any precision anybody transacts in, rather
than a scale that follows what the user typed. That is what lets the split rule
below stay as simple as it does, and it keeps the app free of any assumption
about cents, which it has no right to make (AM-1). What people type and read is
the group's own scale (section 5).

### Sides and splits

Each side carries the scheme and the input the user gave it, and nothing else:

```json
{
  "id": "9f2c1ab4",
  "type": "entry.created",
  "by": "m-7a3d",
  "at": "2026-08-31T18:04:11Z",
  "entry": {
    "id": "n-41b8",
    "kind": "expense",
    "description": "Groceries",
    "date": "2026-08-31",
    "amount": "4230000000",
    "payers": {
      "scheme": "equal",
      "input": ["m-7a3d"]
    },
    "beneficiaries": {
      "scheme": "shares",
      "input": { "m-7a3d": 1, "m-3c90": 1, "m-5e22": 2 }
    }
  }
}
```

**The resulting per-member amounts are not stored.** Every installation derives
them from the scheme and the input, in integers, by dividing and truncating:
`amount / n` for `equal`, `amount × share[i] / total` for `shares`, and so on.

**The remainder is not distributed.** A side can therefore fall short of the
entry's amount by up to one unit per member — a hundred-millionth. Ten thousand
three-way entries lose less than a thousandth of a cent between them.

That is the whole rule, and its simplicity is the point. Distributing the
remainder exactly would need a tie-break that every installation and every
future version reproduces forever, to avoid an error four orders of magnitude
below anything the users can act on. Because only the inputs are stored, a later
version is free to compute more precisely: none of the stored data becomes
wrong, and the app has only ever displayed what it derived. What that freedom
does _not_ extend to is moving a debt for a reason no member could point at.
Becoming more precise, and thereby giving a cent to the person who was always
entitled to it, is a correction; reassigning it on a different basis is not.

The shortfall surfaces once, at the balance: balances are exact integer sums, so
they miss zero by whatever the divisions dropped, and the discrepancy left by
rounding them for display is taken from the **creditors** — the people who are
owed — largest first (format-spec section 7.1). Debtors then owe exactly what
they are shown.

**A new split scheme cannot be read by older installations**, since reading an
entry now means computing its split. That is what section 8's rule about unknown
content is for.

`equal` takes a list of members, the other three a map per member. GR-7 needs no
special handling: an entry names the members it named, so a later membership
change cannot reach it.

## 4. Editing and deleting

`entry.updated` carries **only the fields that changed**:

```json
{
  "id": "c4e0",
  "type": "entry.updated",
  "by": "m-3c90",
  "at": "…",
  "target": "n-41b8",
  "entry": { "amount": "4500000000" }
}
```

The fields it may carry are the same fixed set an entry has. A field left out is
left alone, and a field set to `null` is cleared. Applying the event is a
shallow merge onto the entry as the log has it so far (format-spec section 4.2).

The alternative — restating the whole entry — fails on exactly one thing, and it
is the thing this project cannot recover from. A later version will add fields,
and there is no server to migrate anybody, so old and new installations write
into the same log for as long as the group exists. An installation restating an
entry can only restate the fields it knows about, so somebody on last year's
release correcting a typo in a description would quietly strip the category off
that entry for everyone. Carrying unknown fields through a round trip is a rule
that could be written down, but it is a rule that fails silently, on the devices
least likely to be looked at. Naming only what changed removes the failure
instead of forbidding it.

It also costs nothing in conflict handling. There is one rule — events apply in
log order — and it now settles each field on its own: two people editing one
entry at the same time, one the amount and one the date, both get what they
asked for. Where they really do collide, the later object wins that field, and
the earlier value is still in the log for the history screen (HI-3). HI-9,
restoring an earlier version, is an update carrying every field of the version
being restored — the app has the whole log, so it can always compute what that
version was.

What a merge deliberately does not do is reach inside a value. Replacing
`payers` replaces the side entire, scheme and input together, because a half
merged split is not a thing anyone means. And where a later field would need an
operation the merge cannot express — appending to a list rather than replacing
it — that field gets its own event type. That decision is made once per field,
when the field is added, and not by a general mechanism that has to be right in
advance.

`entry.deleted` carries only `target`. It is a tombstone: the entry stays in the
log and in the history, and an `entry.updated` after a deletion resurrects it,
which is how HI-9 is implemented without a second mechanism.

## 5. Members and the group

`group.created` is the first event of every group, and therefore the payload of
object zero, which is also what identifies the location (sync-design section 5).
It carries the group id, its name, the creating member, and **the number of
decimal places the group's amounts are entered and displayed in** — two, in
every group this version creates. Later changes to name, description, emoji,
colour and the default participant set (GR-2, SP-8) are `group.updated`, which
names only the fields it changes, exactly as an entry update does.

The decimal places are the _d_ the balance rounding in section 3 needs. They are
written from the first release even though nothing offers a choice yet, because
a field that arrives later has no answer for the groups already in existence —
there is no server to fill it in. A group may change them afterwards: nothing
stored is in those units, so all that moves is what is displayed and which
creditor absorbs the leftover.

`member.added` carries a member id and a name. `member.renamed` and
`member.removed` follow. Removal is a flag, never a deletion: GR-9 requires
removed members to stay visible while they appear anywhere, and GR-7 requires
their old entries to be untouched, so nothing about a removal may reach back
into the log.

Two installations adding the same person before they have seen each other
produce two members with two ids. There is no way to prevent this without
coordination the design does not have, and merging them afterwards means
rewriting entries, which is not possible either. The realistic answer is to make
it easy to notice and easy to settle between two members by transfer — not to
solve it.

**Which member an installation acts as is not recorded anywhere.** Choosing at
setup (BE-6) is a local decision, and from then on the installation simply
writes that member into `by`. No event mentions an installation at all. This is
what ID-3 needs — a phone and a tablet acting as one person require no linking,
because there is nothing to link — and the log has no notion that would be
violated by an installation writing as several members at once. The app will not
offer that, but the format has no opinion about it, and pretending otherwise
would only be a rule that nothing enforces: `by` is a claim, not an
authenticated fact (threat model TM-3). It is nevertheless shown as a fact,
because a group is people who already trust one another (principle 3) — hedging
every name in the history would advertise a doubt the product does not have, and
would not stop anyone who wanted to write a false one.

## 6. Recurring series

A series is not a template that occurrences are copied from. It is **a sequence
of segments**, each with an effective-from date, an interval (RE-2), and the
entry template in force from that date:

```json
{ "id": "…", "type": "series.amended", "by": "m-7a3d", "at": "…",
  "target": "s-2f11", "from": "2026-09-01",
  "segment": { "interval": { "monthlyOn": [1] }, "template": { … entry without a date … } } }
```

The segment structure exists to make RE-6 true. Occurrences are not stored — a
due date is derived by calculation, which is what keeps an idle series from
growing the log at all (sync-design section 6) — so if a series were a single
mutable definition, raising the rent from 800 to 850 would retroactively raise
every rent payment since the group began. Selecting the segment in force _at the
due date_ means an edit reaches forward and nothing else.

Pausing and resuming (RE-7) are segments that produce no occurrences and then
resume, and `series.ended` is a segment boundary with nothing after it: the
series stops, and everything it already produced stands. That is the right
behaviour and the wrong word for a button marked delete, so deleting is a
separate event. `series.deleted` takes the series and every occurrence of it,
past ones included, out of the group — exactly what `entry.deleted` does to an
entry, and just as recoverable, because the history keeps both (HI-3).

**Only departures from the calculation produce events**, each with an id derived
from the series id and the due date:

- `occurrence.confirmed` — a suggested occurrence accepted (RE-4).
- `occurrence.deleted` — this due date produces nothing. Refusing a suggestion
  (RE-5) and deleting an occurrence that already exists are the same statement
  about the same date, so they are one event; the series continues either way.
- `occurrence.amended` — edited, whether it was suggested or automatic. Carries
  a full entry.

An automatic series that nobody touches produces no events whatsoever, and its
occurrences exist only as the result of replay.

**A derived occurrence is attributed to the author of the series segment in
force at its due date** (HI-4), and an amended one to whoever amended it, so the
entry list never has to invent a member.

**A recurrence is made of dates, never instants**: a start date, a pattern, and
an end date if it has one. Due dates come from calendar arithmetic and are
identical everywhere — which occurrences exist, and what their ids are, does not
depend on any clock. A timezone decides only _when_ an installation starts
showing one, so a member who has passed midnight sees next month's rent a few
hours before a member further west (RE-8). The same latitude applies to ordinary
entries: one dated to a day that is still tomorrow elsewhere is seen at once,
dated tomorrow. Two series falling due on the same date have no defined order
relative to each other, which is harmless because balances are a sum.

## 7. Attachments

An attachment (EN-11) is a blob under `attachments/`, named by the hash of its
bytes, referenced from an entry by that name. Content addressing gives three
things for nothing: the same receipt attached twice is stored once, a truncated
download is detectable in an unencrypted group where no authentication tag
exists, and re-uploading after an interrupted attempt is harmless because the
second write produces the same key.

The blob must be uploaded **before** the event that references it. The reverse
order leaves a window in which every other installation sees an entry pointing
at an attachment that does not exist, and no amount of retrying on their side
would fix it. In that order a reference always resolves, and no installation
needs a notion of an attachment that has not arrived yet.

## 8. Encoding, and what happens to what we do not recognise

Events are JSON. Objects are small, the log is not re-read often, and being able
to look at a downloaded object with `jq` while debugging a sync problem is worth
more than the bytes a binary encoding would save — more than that, it is a
promised property of the storage layout, not a happy accident (sync-design
section 3).

No canonical form is needed, but bytes still matter in one place. Because the
chain hashes each payload exactly as it was written (format-spec section 5.3),
an implementation must keep those bytes rather than re-serialise a parsed
structure and hope it lands on the same JSON. That is a storage rule, not an
encoding rule: two implementations may format their own objects however they
like.

The one place a canonical form _is_ required is the derivation of an occurrence
id from a series id and a due date (section 6), which two installations must
produce byte-identically. That needs its inputs and its hash function pinned
down exactly. The split rule needs nothing of the sort — it reaches the same
answer everywhere by being integer arithmetic.

Two rules for reading a log written by a newer version:

- **An unknown field is ignored, and nobody has to carry it.** An installation
  that edits an entry writes only the fields it changed (section 4), so a member
  on an older release cannot strip a category off an entry they touch — they
  never mention it. The field stays in the log where a newer installation will
  read it.
- **An unknown event _type_ stops writing.** It cannot be skipped: an event type
  that a reader ignores is a balance that is wrong on that device and right
  everywhere else, silently and forever. The group is shown read-only with an
  explanation that a newer version of the app is needed. This is a real cost of
  not having a server to migrate anything, and the way to keep it rare is to add
  fields rather than types wherever there is a choice — which the merge rule
  makes safe.
