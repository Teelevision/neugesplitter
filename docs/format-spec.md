# Format Specification — Version 1

**Status:** Normative draft. Unlike [sync-design.md](sync-design.md) and
[event-model.md](event-model.md), which explain intent, this document defines
what an implementation must actually produce. Where the two disagree, this one
is wrong and should be fixed — the design documents hold the reasoning.

**Everything here is frozen once a real group exists.** There is no server, so
there is nothing that can migrate anyone's data. A mistake in this document is
permanent in a way that a mistake in the app is not.

The key words MUST, MUST NOT, SHOULD and MAY are to be read as in RFC 2119.

---

## 1. Conventions

- Byte order is big-endian everywhere.
- "Hex" means lowercase, two characters per byte, no prefix.
- Text is UTF-8 without a byte-order mark.
- `||` denotes byte concatenation.
- `len(x)` denotes the length of `x` in bytes as a four-byte big-endian unsigned
  integer.

## 2. Object layout

A group occupies a prefix in a bucket:

```
<prefix>/log/0000000        the root object
<prefix>/log/0000001        further batches
<prefix>/attachments/<hash>
```

Log object names are exactly seven lowercase hex digits. Names outside that
shape MUST be ignored by readers and MUST NOT be written. The name is the
object's **number**. Names establish the order in which objects are _fetched_;
the hash chain of section 5.3 establishes the order in which they are
_believed_.

**A stored object is either plain JSON or an age file, and nothing else.** There
is no container of ours, no magic bytes, no header. Standard tools are meant to
be able to read a group's history without any software from this project, which
is a hedge against the project disappearing as much as a convenience (BE-16).

### 2.1 The root object

`log/0000000` identifies the location, and its first byte says which of the two
kinds of group this is. `{` is an unencrypted group; `-` is an encrypted one.

**Unencrypted: a JSON object.** It carries `neugesplitter` — the format version,
`1` in this document — alongside the payload fields of section 2.2, so the root
object **is** the first batch and `log/0000001` is the second.

**Encrypted: an age file, with nothing wrapped around it.** Its plaintext is an
age identity file, and it is encrypted to the group's passphrase with age's
scrypt recipient and ASCII-armoured. `age -d` decrypts it and `age -i` then
consumes the result, with no unwrapping step in between (section 2.3) — which is
the whole reason it is not a key sitting inside JSON. The log begins at
`log/0000001`, and the format version comes from there.

The public key is written down nowhere. Every installation derives it from the
identity once the passphrase has opened it, which is the only moment anything
needs it.

A reader that finds no object, or one that is neither JSON carrying
`neugesplitter` nor an armoured age file, MUST treat the location as empty or
foreign and MUST NOT write to it (BE-9). For an encrypted group the confirmation
that the location is really ours arrives one object later, after the passphrase
has been given.

The root object is written once, by whichever installation creates the group,
and is never rewritten — rotating the key pair would mean re-encrypting every
object, which this design has no mechanism for.

Nothing authenticates the root object itself. It does not need it: an attacker
who substitutes a key pair of their own cannot produce a file that decrypts
under a passphrase they do not have, so every member notices at once, and a
fresh installation joining with the right passphrase notices before it has read
anything else. The passphrase is the root of trust, and the root object is the
first place it is checked.

### 2.2 Log objects

Every log object that carries events is a JSON document — which is all of them
except an encrypted group's root object, whose plaintext is a key rather than a
batch. In an encrypted group it is the plaintext of an age file (section 5.2);
in an unencrypted group it is the stored bytes themselves.

```json
{
  "neugesplitter": 1,
  "batch": "3f9c0a1d5b7e42c8a0d61f4b8e2c7a90",
  "prev": "26860f4388f1ff3f7abfd1af1d63f79995d4f2e8b592c11f43eda79f184a0d06",
  "events": [ { … }, { … } ]
}
```

`neugesplitter` repeats the format version on every object, so that a single
decrypted file is self-describing when it turns up on its own. A reader that
finds a version later than its own MUST stop as it would at an unknown `type`
(section 8). The JSON restrictions and limits below come first, whatever version
an object claims, because they protect the reader rather than describe the
content; everything else in this document is a rule of version 1 and is checked
only once the version says it applies.

`batch` is a fresh 128-bit random identifier, hex. It exists so that a writer
that lost the response to a `PUT` can tell whether the object it finds is its
own (sync-design section 4). It MUST be regenerated whenever the set of events
changes, and MUST be identical across retries of the same write.

`prev` in an encrypted group is the chain link defined in section 5.3.

`events` MUST contain at least one event. Events within an object are applied in
array order.

JSON restrictions: duplicate object keys are invalid, and every number MUST be
an integer within ±2⁵³ — the only numbers this version defines are share
weights, days of the month and day counts. Amounts are strings (section 3.3). No
field defined here holds a boolean, and `null` appears in exactly one place: an
update payload, where it clears an optional field (section 4.2). A reader that
finds either anywhere else MUST treat the event as malformed. Both MAY appear
inside fields it does not recognise, which it ignores (section 8).

Limits, which exist because every installation parses whatever anyone with the
credentials writes (threat model TM-5). A log object MUST NOT exceed **8 MiB**
as stored, MUST NOT nest more than **32** levels deep, MUST NOT contain a string
longer than **64 KiB**, and MUST NOT carry more than **10 000** events. A reader
MUST enforce them as it goes — the size before the body is fetched, the rest
while the document is consumed — and MUST NOT allocate on the strength of a
length the object itself declares. Raising any of these is a format version
change.

Every reader has to draw these lines in the same place, so they are measured as
follows, and each bound is inclusive. The size is the object's length in bytes
as stored. Depth counts the object itself as level 1 and every array or object
inside a value one level deeper, so `events` is level 2 and an event level 3. A
string's length is the length of its value in UTF-8 once escapes are resolved,
keys included, so `"\u20ac"` counts three bytes and not six. A number may be
exactly ±2⁵³.

Beyond what the JSON grammar (RFC 8259) already excludes, these are malformed:
text that is not valid UTF-8 or that begins with a byte-order mark (section 1);
an escape that leaves a surrogate unpaired, because what it encodes is not text;
a number written with a fraction or an exponent, even `1.0`; and a document
whose top-level value is not an object.

An object that breaks any rule in this section is malformed, and a malformed
object is treated exactly as an unrecognised `type` is (section 8): reading
stops there, the group is shown read-only, and nothing after it is applied.
Skipping it would leave one device's balances quietly disagreeing with everybody
else's, which is the failure this format refuses everywhere. Nothing can be
deleted, so the way out is the one in sync-design section 7.

### 2.3 Reading a group without this app

Non-normative, but it is the reason for the layout above. An unencrypted group
is already `cat`-and-`jq` material. An encrypted one takes one extra step:

```sh
age -d log/0000000 > key.txt                       # asks for the passphrase, once
for f in $(ls log/* | tail -n +2); do age -d -i key.txt "$f"; done | jq -c '.events[]'
```

This is why the passphrase does not encrypt the objects directly: `age -p`
derives a key with scrypt every time, so the loop above would pay a full
derivation per file, where a key pair pays it once on the first line.

## 3. Primitive types

### 3.1 Identifiers

An identifier is 128 bits rendered as 32 hex characters. Random identifiers MUST
come from a cryptographically secure source. Derived identifiers are defined in
section 6.

Group, member, entry, series, batch and event identifiers all share this form.
There is no type prefix: position in the document establishes what an identifier
refers to, and a prefix would be one more thing to get wrong when two
implementations disagree.

### 3.2 Dates and instants

A **date** is `YYYY-MM-DD` in the proleptic Gregorian calendar, with no time and
no zone. Entry dates (EN-3) and recurrence due dates are dates.

An **instant** is RFC 3339 in UTC with second precision and a literal `Z`, e.g.
`2026-08-31T18:04:11Z`. Event timestamps are instants. They are a record only
and MUST NOT affect ordering or any derived value (sync-design section 4).

### 3.3 Amounts

**Every amount is an integer count of 10⁻⁸ units**, and is written as one — a
JSON string holding a decimal integer, with no decimal point anywhere in the
format:

```
amount = [ "-" ] ( "0" / digit1-9 *digit )
```

No exponent, no leading `+`, no leading zeros. What a user typed as 1.23 is
written `"123000000"`, and 42.30 is `"4230000000"`. The absolute value MUST be
less than 10¹⁸.

Amounts fit in a signed 64-bit integer (10¹⁸ < 2⁶³) but not in the ±2⁵³ that
section 2.2 allows JSON numbers, which is the first reason they are strings. The
second is that many parsers coerce JSON numbers to binary floating point
regardless of magnitude. Implementations MUST parse an amount to an exact 64-bit
integer and MUST NOT round-trip it through a floating-point type. Intermediate
products in section 7 exceed 64 bits and MUST use a wider type.

An entry's `amount` MUST be greater than zero; direction comes from the entry's
kind (section 4.2). Adjustment inputs MAY be negative.

The unit is not a cent, and the app has no currency (AM-1). What people type and
read is the group's `scale` (section 4.2); 10⁻⁸ is only ever the arithmetic.

## 4. Events

### 4.1 Envelope

Every event is a JSON object with:

| Field  | Type       | Meaning                                        |
| ------ | ---------- | ---------------------------------------------- |
| `id`   | identifier | Identifies the intent (event-model section 2). |
| `type` | string     | See below.                                     |
| `by`   | identifier | The member responsible.                        |
| `at`   | instant    | When, per the writing device's clock.          |

plus the further fields its type requires, listed in section 4.2. A field this
document does not define is ignored (section 8).

`by` is what the writing installation asserts, and nothing authenticates it
(threat model TM-3).

### 4.2 Types

| `type`                 | Payload field               | Notes                       |
| ---------------------- | --------------------------- | --------------------------- |
| `group.created`        | `group`                     | First event of the log.     |
| `group.updated`        | `group`                     | Only what changed.          |
| `member.added`         | `member`                    |                             |
| `member.renamed`       | `target`, `name`            |                             |
| `member.removed`       | `target`                    | A flag; nothing is deleted. |
| `entry.created`        | `entry`                     |                             |
| `entry.updated`        | `target`, `entry`           | Only what changed.          |
| `entry.deleted`        | `target`                    |                             |
| `series.created`       | `series`                    |                             |
| `series.amended`       | `target`, `from`, `segment` |                             |
| `series.ended`         | `target`, `from`            | Stops it; its past stands.  |
| `series.deleted`       | `target`                    | Removes it and its past.    |
| `occurrence.confirmed` | `series`, `due`             |                             |
| `occurrence.deleted`   | `series`, `due`             | Also declining one (RE-5).  |
| `occurrence.amended`   | `series`, `due`, `entry`    |                             |

#### Deletion removes from the state, not from the log

`entry.deleted`, `series.deleted` and `occurrence.deleted` say that something is
no longer part of the group. Nothing leaves the log, the history still shows it
(HI-3), and a later event can put it back.

`series.deleted` takes the series and every occurrence it ever produced,
including past ones, out of the entry list and out of the balances — the same
thing `entry.deleted` does to an entry. An occurrence amended after the deletion
is not taken with it (see "What a due date yields"). `series.ended` is the other
one: it stops a series from a date onward and leaves what already happened
standing. `occurrence.deleted` removes exactly one due date's occurrence,
whether that occurrence was suggested, confirmed, amended or automatic, and the
series carries on.

#### An event is read against the log before it

An event MUST agree with the state the log has produced so far:

- every identifier an event names MUST already be in the group — the `target` of
  `member.renamed`, `member.removed`, `entry.updated`, `entry.deleted`,
  `series.amended`, `series.ended` and `series.deleted`, the `series` of an
  occurrence event, and every member identifier in a side or in
  `defaultParticipants`;
- `member.added`, `entry.created` and `series.created` MUST NOT reuse an
  identifier the group already has, whether or not what it named has since been
  removed or deleted.

An event breaking either is malformed and reading stops (section 8). Requiring
it costs a writer nothing, because writing requires being caught up (sync-design
section 4): an installation cannot name an identifier it has not already seen,
so no valid writer can produce such an event. It is the same ordering that puts
an attachment on the server before the event referencing it (section 9), and it
buys the property that a reference always resolves — the alternative is a
balance belonging to a member nobody can name, which no reader could display and
two readers would handle differently.

Repeating `member.removed`, `entry.deleted`, `series.deleted` or
`occurrence.deleted` is **not** a contradiction and MUST be accepted: two
installations may each decide to remove the same thing, and the second event
asserts nothing the first did not. `entry.updated` MAY name a deleted entry, and
`series.amended` a deleted series, which puts it back (HI-9).

#### Update events carry only what changed

The payload of `group.updated` and `entry.updated` is drawn from the same fixed
set of fields as the corresponding creation event, and applying one is a shallow
merge onto the state the log has produced so far:

- a field that is **absent** is left as it was;
- a field that is **present** replaces its previous value entirely — including
  `payers`, `beneficiaries` and `input`, which are values, not structures to
  merge into;
- a field that is **`null`** is cleared. This is the only place `null` is legal,
  and it MUST NOT be used for a field that is not optional.

`id` MUST NOT appear in either payload — a group cannot restate its identity,
and an entry's is in `target`. Two updates to one object are applied in log
order, so the later value of each field wins independently of the others.

No other event is a diff. `entry.created`, `series.created`, `series.amended`
and `occurrence.amended` carry complete objects, because there is nothing yet to
merge onto.

The reason for all of this is that a later version will define fields this one
has never heard of. A whole-object update written by an older installation would
silently drop them — an edit to a description would take a category with it —
while an update that names only what changed cannot touch what it does not
mention. Where a later field needs something this merge cannot express, such as
appending to an array rather than replacing it, that field gets an event type of
its own; the choice is made per field, as each one is added.

`group`:

| Field                 | Type                                                               |
| --------------------- | ------------------------------------------------------------------ |
| `id`                  | identifier, `group.created` only                                   |
| `scale`               | integer 0–8                                                        |
| `name`                | string                                                             |
| `description`         | string, optional                                                   |
| `emoji`               | string, optional                                                   |
| `colour`              | string, optional, `#rrggbb`                                        |
| `defaultParticipants` | array of member identifiers, optional, `group.updated` only (SP-8) |

`defaultParticipants` is the set of members a new entry starts out with; the
scheme it starts out with is always equal and is not configurable (SP-8). It
MUST NOT appear in `group.created`, which is the first event of the log and so
runs before any `member.added`: there is no member it could name that the rule
above would allow. Every group therefore begins without one, and its absence
means **every member of the group**, which is what a group of people who have
just created one would have chosen anyway. A group sets it with `group.updated`
once there are members to name, and clears it back to that default by sending
`null`.

A member named in `defaultParticipants` who is later removed does not make the
group malformed — removal takes nothing out of the log and the identifier is
still a member's. The default is simply not offered for them when a new entry is
written (GR-8).

`member`: `id`, `name`. Which member an installation acts as is local to that
installation and has no event: an installation simply writes that member into
`by`, and nothing in the format restricts it to one (ID-3).

`scale` is the number of fractional digits in which the group's amounts are
entered and displayed, and is the _d_ of section 7.1. It MAY be changed by
`group.updated`. Nothing stored changes with it — amounts are integers at 10⁻⁸
whatever it says (section 3.3) — so it moves only what is displayed and where
the rounding residue falls. Implementations of this version MUST write `2` when
creating a group, and MUST reject a group whose `scale` they cannot honour.

`entry`:

| Field           | Type                                        |
| --------------- | ------------------------------------------- |
| `id`            | identifier                                  |
| `kind`          | `expense`, `income` or `transfer`           |
| `description`   | string                                      |
| `date`          | date                                        |
| `amount`        | amount                                      |
| `payers`        | side                                        |
| `beneficiaries` | side                                        |
| `note`          | string, optional (EN-11)                    |
| `attachments`   | array of attachment names, optional (EN-11) |

A **side**:

| Field    | Type                                                                                                 |
| -------- | ---------------------------------------------------------------------------------------------------- |
| `scheme` | `equal`, `shares`, `adjustments` or `absolute`                                                       |
| `input`  | array of member identifiers when `scheme` is `equal`; otherwise an object keyed by member identifier |

`input` values are integers for `shares` and amounts for `adjustments` and
`absolute`. A member MUST NOT appear twice, and `input` MUST NOT be empty.

`series`: `id`, `from` (date), `segment`. A `segment` is `interval`, `mode` and
`template`, where `mode` is `automatic` or `suggested` (RE-3) and `template` is
an entry without `id` or `date`. `interval` is one of:

```json
{ "everyDays": 14 }
{ "monthlyOn": [1, 15] }
{ "weeklyOn": ["mo", "th"] }
```

`monthlyOn` and `weeklyOn` are non-empty arrays whose values MUST be unique;
their order carries no meaning. `monthlyOn` values are 1 to 31 and clamp to the
last day of a shorter month, so a series on the 30th and the 31st yields **one**
occurrence in February, not two: clamping happens first and the resulting dates
are then deduplicated. `everyDays` is a positive integer, and `weeklyOn` values
are `mo`, `tu`, `we`, `th`, `fr`, `sa` and `su`.

Everything about a recurrence is a **date**. `from` on `series.created`,
`series.amended` and `series.ended` is a date, and a due date is a date; no
field in a series is an instant. Two series falling due on the same date have no
defined order relative to each other, and implementations MUST NOT depend on one
— balances are a sum, so no result depends on it.

#### Which dates are due

A series is a sequence of segments, each in force from its `from`:
`series.created` begins the first, every `series.amended` begins another, and
`series.ended` begins one that produces nothing. The segment in force on a date
is the one with the latest `from` on or before that date, and of two with the
same `from`, the later in the log.

A date is **due** when the segment in force on it produces it, counting from
that segment's own `from`: `everyDays: n` produces `from`, then every \_n_th day
after it; `weeklyOn` and `monthlyOn` produce every date on or after `from` that
matches them. So `from` is itself due whenever the interval produces it, and an
amendment restarts the count of `everyDays` at its own `from` rather than
carrying on from the segment before it.

Pausing a series is `series.ended` from one date and resuming it is
`series.amended` from a later one (RE-7). An ended series is simply one whose
last segment produces nothing, and an amendment after it puts it back in force.

A reader applies `from` as written, including one in the past: a series MAY be
created or edited retrospectively, and what falls due between that `from` and
the local date is due at once — produced if the segment is automatic, pending if
it is suggested. An edit still reaches only from its own `from` onward (RE-6).
Within that reach a retrospective edit changes automatic and confirmed
occurrences, which are nothing but the calculation, and leaves amended ones as
they are.

An installation shows a due date once its own local date has reached it (RE-14),
and not before, whatever events name that date.

#### What a due date yields

Each due date yields one **occurrence**: the template of the segment in force on
it, dated on the due date. If that segment's `mode` is `automatic` the
occurrence is an entry like any other, in the entry list and in the balances. If
it is `suggested`, the occurrence is **pending** — offered to the user (RE-4,
RE-10) but in neither until it is confirmed.

An occurrence event overrides this for its due date. The three share one
identifier (section 6), so the later in the log supersedes the earlier entirely
and whichever was written last is the occurrence's state:

- `occurrence.confirmed` makes it an entry, as if the segment were automatic;
- `occurrence.amended` makes it the entry the event carries, which has no `id`
  and whose `date` MAY differ from the due date — rent paid on the third is
  still the occurrence due on the first;
- `occurrence.deleted` makes it nothing, and the series carries on (RE-5,
  RE-15).

An amended occurrence is an entry somebody wrote, and only a later event naming
it removes it: another occurrence event for its due date, or `series.deleted`.
So it stands whether or not its due date is still due — an amendment to the
series, written concurrently or from a date in the past, can move the schedule
away from it, and what somebody typed in MUST NOT disappear because of that. It
also stands while its series is deleted, provided it was written after the
`series.deleted`, just as an `entry.updated` after an `entry.deleted` puts the
entry back (HI-9). Like any occurrence it is shown once the local date reaches
its due date. The price is that a moved schedule can yield two occurrences in
one period, both in the entry list where somebody can delete one; the
alternative is an edit lost without anyone seeing it.

`occurrence.confirmed` and `occurrence.deleted` carry nothing of their own and
follow the calculation. On a date that is not due, or on a series that is
deleted, they are **not** malformed and have no effect for as long as that
remains so. Nothing is lost by it: whatever date is due instead is offered or
produced in its place. When `series.amended` puts a deleted series back, every
occurrence event on it applies again, whenever it was written.

An occurrence appears in the entry list with its occurrence identifier as its
`id`. That identifier is not an entry's, so `entry.updated` and `entry.deleted`
cannot name it; an occurrence is edited and deleted only by occurrence events.

## 5. Encryption and integrity

Nothing in this section is invented here. The encryption is
[age](https://age-encryption.org), version 1, used exactly as its own
specification defines it. This project defines only _what_ is encrypted and _to
whom_ — never a primitive, a mode or a key schedule. age has a reference
implementation, a written specification, implementations in several languages,
and a command-line tool that any member can fall back on (section 2.3).

### 5.1 The key pair

A group that is encrypted has one X25519 key pair for its whole life:

- the **identity** — the secret key, `AGE-SECRET-KEY-1…` — is the root object,
  as an age identity file encrypted with the group's passphrase using age's
  scrypt recipient (`age -p`) and ASCII-armoured;
- the **recipient** — the public key, `age1…` — is derived from the identity
  whenever it is unlocked, and is stored nowhere.

Implementations MUST NOT lower age's scrypt work factor below its default. This
derivation happens once when an installation joins and once more whenever the
identity is re-read from storage; it is never on the path of reading an object.

The passphrase is NFC-normalised and encoded as UTF-8 before being given to age.

A failure to decrypt the identity is how a wrong passphrase is detected, and
MUST be reported as a wrong passphrase rather than as a corrupt backend.

**Writing is gated by the same secret as reading.** Because the recipient never
appears in the clear, someone who can read the bucket but does not have the
passphrase cannot produce an object that members will decrypt. The key pair buys
cheap reads (section 2.3); it does not widen who can write something that looks
like ours.

### 5.2 Log objects and attachments

Every log object after the root, and every attachment, is an age file encrypted
to the group's recipient. These are written in age's binary form; only the root
object is armoured, because it is the one file a person is expected to look at
and hand to `age -d` themselves.

There is no associated data, because age has none. Binding an object to its
number — so that it cannot be transplanted or renumbered — is the job of the
chain instead.

### 5.3 The hash chain

Every log object in an encrypted group carries `prev`, 32 bytes as 64 hex
characters:

```
chainKey = HKDF-SHA256( ikm = identity, salt = "", info = "neugesplitter/chain/v1", L = 32 )
prev     = HMAC-SHA256( chainKey, payload of the preceding log object )
```

where `identity` is the 32 bytes the secret key's Bech32 string decodes to, and
the **payload** is the exact bytes that were encrypted — for an unencrypted
group, the exact bytes stored. Not re-serialised, not normalised, not reordered.
An implementation MUST retain those bytes as it received them and MUST NOT
recompute them from a parsed structure.

The first log object has no predecessor and uses the empty byte string as the
payload.

An installation MUST verify `prev` on every object it applies. A mismatch means
the history has been altered, and the group MUST be shown as compromised rather
than repaired, skipped or resynced.

Two things follow. **Insertion, reordering and transplanting are detected**,
because each link names exactly one predecessor by its content, and the key is
that group's alone. And **a leaked plaintext does not extend the attack**,
because the link is a MAC rather than a bare hash — knowing what an object said
is not enough to continue the chain from it.

The chain says nothing about the _end_ of the log. Removing the last _k_ objects
leaves a perfectly valid chain, and a fresh installation has no way to know how
long the history should have been. What answers this is not detection but
repair: every installation keeps the objects it has downloaded exactly as they
were stored, so a shortened log is visible to everyone already synced and is put
back by re-uploading what is missing (threat model TM-1).

Attachments are not in the chain. They do not need to be: an attachment is named
by the hash of its content and referenced from an event, which is.

In an unencrypted group there is no `prev`.

### 5.4 What is not protected

Object names, object count and write times are plaintext, and so is the fact
that the group is encrypted at all — the root object is visibly an age file. The
provider learns that a group exists at a prefix, how many objects it holds and
when each appeared. It does not learn the group id, which lives inside the first
encrypted object.

## 6. Derived identifiers

Occurrence identifiers MUST be produced byte-identically by every installation:

```
occurrenceId = truncate16( SHA-256(
    "neugesplitter/occurrence/v1" || len(seriesId) || seriesId || len(due) || due ) )
```

`seriesId` is the 32 hex characters as ASCII; `due` is the 10 ASCII characters
of the due date. `truncate16` takes the leading 16 bytes, rendered as hex.

This is the `id` of `occurrence.confirmed`, `occurrence.deleted` and
`occurrence.amended`, so two installations acting on the same occurrence produce
one event rather than two (event-model section 2), and the `id` of the
occurrence itself in the entry list. It is the only derived value in the format.

## 7. Splitting

All arithmetic is on integers in units of 10⁻⁸ (section 3.3). Per side, with
`amount` the entry's amount, `n` the number of members on that side, and
`share[i]`, `adjustment[i]` or `value[i]` the member's number of shares,
adjustment or absolute value:

| Scheme        | Member's part                                         |
| ------------- | ----------------------------------------------------- |
| `equal`       | `amount / n`                                          |
| `shares`      | `amount * share[n] / sum(share[..n])`                 |
| `adjustments` | `(amount - sum(adjustment[..n])) / n + adjustment[n]` |
| `absolute`    | `value[n]`                                            |

Division truncates toward zero. Products MUST be computed in a type wider than
64 bits before dividing. `absolute` values MUST sum exactly to, and
`adjustments` to no more than `amount`; an event violating either is malformed.

A `share` is a natural number and their sum MUST be positive; an event violating
either is malformed. A share of zero is allowed and means a part of zero: a
member who is on the side and takes nothing from it. Leaving them off the side
entirely says the same thing about the balances and is what the app writes, but
both are legal and a reader MUST accept either.

**No remainder is distributed.** The parts of a side may therefore fall short of
the entry's amount by up to one unit per member. Only the inputs are stored, so
a later version MAY compute more precisely without any stored data becoming
wrong (event-model section 3).

### 7.1 Balances

A member's balance is the exact integer sum over all entries of what they paid
minus what they owed, negated for `income`. Balances sum to zero at 10⁻⁸ only up
to the shortfall above, so rounding for display or settlement MUST follow:

1.  Round every member's balance to the group's `scale` _d_ (section 4.2), half
    away from zero.
2.  Let _R_ be the sum of the rounded balances. It may be non-zero.
3.  Subtract _R_ from the **creditors** — members whose **exact** balance is
    positive — one unit of 10⁻ᵈ at a time, taking them in order of descending
    exact balance, then by member identifier ascending, and repeating the
    sequence if there are more units than creditors. The unit comes off the
    member's **rounded** balance, and is never taken from one already at zero.

Both halves of step 3 matter. Selecting and ordering by the exact balance keeps
the order from depending on _d_, separates two creditors whose rounded balances
are equal, and admits a creditor whose balance rounds to zero. Subtracting from
the rounded balance is what makes the result sum to zero: rounding half away
from zero does not commute with subtraction across zero, so re-rounding an exact
balance after the subtraction can move it by a whole unit and leave the total
non-zero. A balance of exactly half a unit, for instance, rounds up to one unit,
but one unit less than it rounds to minus one unit rather than to zero.

A creditor is skipped once at zero for the same reason: a member who is owed
something, however little, must never be shown owing money. Skipping cannot
exhaust the creditors, because a positive _R_ is the amount by which the rounded
credits exceed the rounded debts, and so is never more than the creditors hold.

Debtors therefore owe exactly what they are shown, and BA-2 holds after step 3.
A positive _R_ always has a creditor to take from, since it requires a positive
rounded balance. A negative _R_ need not: the shortfall above can outweigh a
member's credit when _d_ is fine enough for it to survive rounding, leaving
every balance zero or negative. **If no balance is positive, step 3 takes every
member in that same order instead**, so that the unit falls first on whoever is
closest to being owed something and no debtor's figure moves while a member at
zero could absorb it.

## 8. Compatibility

- **An unknown field is ignored, and nothing has to carry it forward**, because
  an update names only the fields it changes (section 4.2).
- **An unknown `type` MUST stop writing.** The group is shown read-only, with an
  explanation that a newer version is needed. It MUST NOT be skipped: an ignored
  event is a balance that is wrong on one device and right on every other,
  silently and permanently.
- **An unknown `scheme` or `interval` variant** is treated as an unknown type:
  reading an entry requires computing its split, so a scheme that cannot be
  computed cannot be displayed.
- **A malformed object** — including one over the limits of section 2.2 — stops
  reading in the same way, for the same reason.
- **An event carries no schema version of its own.** The object header's version
  covers the format, and a change to what one event means is a change of `type`,
  which older readers already refuse. If a version per event is ever needed
  anyway, its absence means version 1 and the version that needs it names
  itself.
- Additions that only introduce fields do not change the format version.
  Anything else does.

## 9. Attachments

An attachment is stored at `attachments/<name>` where `name` is the hex SHA-256
of its plaintext bytes. It is referenced by that name from an entry.

An attachment MUST be uploaded before any event referencing it, so that a
reference always resolves.

Nothing deletes an attachment. One uploaded for an entry that was never saved
stays where it is, and no installation ever fetches it, because nothing points
at it (event-model section 7).

## 10. Test vectors

The vectors are in [format-vectors.json](format-vectors.json), and the objects
it names are in [vectors/](vectors/). An object file is exact bytes, to be read
as stored and never reformatted. Every implementation MUST reproduce every
vector (TR-45); where one disagrees with the text above, the defect is in this
document, and it is fixed here rather than left for implementations to choose
between.

They were produced independently of this project's code: the occurrence
identifiers and the chain with a script written from this document, the chain
key and links checked against OpenSSL's HKDF and HMAC, and the identity checked
against the `age` tool, which derives a recipient from it and decrypts what was
encrypted to that recipient. The encrypted files were written by the `age` tool
itself.

- **`occurrenceIds`** — a `series` and a `due` date, and the `id` section 6
  derives from them.
- **`chain`** — an `identity`, the `chainKey` section 5.3 derives from it, and
  the `prev` that follows a predecessor whose payload is the file `payloadFile`,
  or the empty byte string where there is none.
- **`groups`** — the `objects` of an unencrypted group's log, read in order, and
  every member's balance on the date `today`: unrounded, in units of 10⁻⁸
  (section 7.1). `entry-schemes.json` holds `entry.created` with each of the
  four schemes, an `entry.updated` that names only `amount`, and fields this
  version does not define, holding `null` and booleans. `series.json` holds each
  series and occurrence event, a suggested occurrence nobody confirmed and a
  series that was deleted.
- **`accepted`** — objects a reader MUST accept, near the edges of what section
  2.2 allows.
- **`refused`** — objects a reader MUST refuse, whatever was read before them,
  each `as` malformed or unsupported (section 8) and with `why`. Each is `text`,
  meaning its UTF-8 bytes, or `hex` where the bytes are not text.
- **`limits`** — one object at each end of every limit in section 2.2, built as
  follows, and whether it is `accepted`.
- **`encrypted`** — a `root` object, the `passphrase` that opens it and the
  `identity` it holds, and `objects` encrypted to that identity, each with the
  `payloadFile` it decrypts to. The passphrase is given decomposed, so it opens
  the root only once normalised as section 5.1 requires.

Each limit object is the object below, without whitespace, changed in one place:

```
{"neugesplitter":1,"batch":"ba7cba7cba7cba7cba7cba7cba7cba7c","events":[E]}
E = {"id":"00000000000000000000000000000001","type":"member.renamed",
     "by":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","at":"2026-01-01T12:00:00Z",
     "target":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","name":"a"}
```

| `limit`         | The object with _n_                                                                     |
| --------------- | --------------------------------------------------------------------------------------- |
| `size`          | spaces inserted before the final `}` until the object is _n_ bytes                      |
| `depth`         | a last field `"x"` in E whose value is _n_ − 3 nested arrays, the innermost empty       |
| `string`        | a `name` of _n_ letters `a`                                                             |
| `escapedString` | a `name` of ⌊_n_ / 3⌋ escapes `\u20ac` followed by _n_ mod 3 letters `a`                |
| `events`        | _n_ copies of E in `events`, the _i_-th with `id` _i_ in 32 hex digits, counting from 1 |
