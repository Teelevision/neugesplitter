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
decrypted file is self-describing when it turns up on its own.

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
| `group.created`        | `group`                     | Object zero, first event.   |
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
thing `entry.deleted` does to an entry. `series.ended` is the other one: it
stops a series from a date onward and leaves what already happened standing.
`occurrence.deleted` removes exactly one due date's occurrence, whether that
occurrence was suggested, confirmed, amended or automatic, and the series
carries on.

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

| Field                 | Type                                         |
| --------------------- | -------------------------------------------- |
| `id`                  | identifier, `group.created` only             |
| `scale`               | integer 0–8                                  |
| `name`                | string                                       |
| `description`         | string, optional                             |
| `emoji`               | string, optional                             |
| `colour`              | string, optional, `#rrggbb`                  |
| `defaultParticipants` | array of member identifiers, optional (SP-8) |

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

`series`: `id`, `from` (date), `segment`. A `segment` is `interval` plus
`template`, where `template` is an entry without `id` or `date`. `interval` is
one of:

```json
{ "everyDays": 14 }
{ "monthlyOn": [1, 15] }
{ "weeklyOn": ["mo", "th"] }
```

`monthlyOn` and `weeklyOn` are non-empty arrays whose values MUST be unique;
their order carries no meaning. `monthlyOn` values are 1 to 31 and clamp to the
last day of a shorter month, so a series on the 30th and the 31st yields **one**
occurrence in February, not two: clamping happens first and the resulting dates
are then deduplicated. A series also carries `mode`, either `automatic` or
`suggested` (RE-3).

Everything about a recurrence is a **date**. `from` on `series.created`,
`series.amended` and `series.ended` is a date, and a due date is a date; no
field in a series is an instant. Two series falling due on the same date have no
defined order relative to each other, and implementations MUST NOT depend on one
— balances are a sum, so no result depends on it.

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
one event rather than two (event-model section 2). It is the only derived value
in the format.

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
3.  Subtract _R_ from the **creditors** — members whose balance is positive —
    one unit of 10⁻ᵈ at a time, taking them in order of descending balance, then
    by member identifier ascending, and repeating the sequence if there are more
    units than creditors.

Debtors therefore owe exactly what they are shown. BA-2 holds after step 3,
which is well defined whenever _R_ is non-zero, because a non-zero sum of
balances requires at least one positive balance.

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
