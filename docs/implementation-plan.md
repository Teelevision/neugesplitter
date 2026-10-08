# Implementation plan

**Status:** Draft. The order the work happens in, and why that order. What the
software must do is in [technical-requirements.md](technical-requirements.md);
where the code lives is in [architecture.md](architecture.md).

---

## 1. What decides the order

Most of this app is replaceable. The UI can be rewritten, the S3 client can be
swapped, the projection tables can be dropped and rebuilt from the event log at
any time (TR-18). One thing cannot: the bytes written to a backend. Every group
ever created carries the format forward, and a group created by version 1 has to
stay readable by version 9 (format-spec 8). So the first question is not "what
is the smallest useful app" but "what am I about to make permanent", and the
work is sequenced so the permanent decisions are made while they are still
cheap.

After that the ordering is ordinary: pure modules before impure ones, because
`:domain` and `:format` need no emulator and give feedback in seconds; one
vertical slice before any breadth, because a local-only app is already a
shippable product and exercises the entire domain; and the second device early,
because sync bugs do not appear on one.

Three principles worth stating outright, because each of them costs something up
front and repays later:

- **Test vectors before the code they describe.** If the implementation comes
  first, the implementation becomes the definition of the format and the vectors
  become a transcript of whatever it happened to do.
- **The integration suite arrives with the first line of backend code**, not
  after it. TR-48 needs a real S3 in a container; adding it later means
  retrofitting tests to code that was written without them in mind.
- **Modules get created when there is code to put in them** (architecture.md 1).
  Phases 1 and 2 are two modules; nothing forces all six to exist on day one.

## 2. Phase 0 — Decide what cannot be changed later

Nothing here is a feature, and none of it can be deferred, because each item
constrains code that comes after.

**Choose the age implementation (TR-23).** Evaluate kage against the scrypt
recipient, ASCII armouring, streaming decryption, licence and maintenance, and
record the outcome in [decisions.md](decisions.md) as a decision, not as a
dependency line in a build file. This is first because it can change the module
graph: if the answer is a gomobile AAR rather than a Kotlin library, `:format`
becomes an Android library and everything downstream of it inherits that
(architecture.md 1).

**Write the format-spec test vectors (TR-45).** The specification has no vectors
section yet, and it is the one gap that gets more expensive every week. Cover at
minimum: a canonical envelope with its exact serialisation, a chain link
computed over known payload bytes, an `entry.created` with each of the four
schemes, an update event that names one field, and one object at each end of the
parser limits (TR-39).

**Resolve the two contradictions in the specs.** The object numbering is
asymmetric — unencrypted groups start at `0000000`, encrypted ones at `0000001`
— and it should either be justified in format-spec or removed. And event-model 5
says `group.created` carries the creating member while format-spec's `group`
payload table names no creator field; one of the two is wrong.

**Stand up the repository.** Gradle with the version catalog, spotless, the
`:domain` module, and CI that runs a clean build with no credentials (TR-51).
Nothing else — the remaining modules arrive with their code.

## 3. Phase 1 — `:domain`

Pure Kotlin, no Android, no persistence, no I/O. This is the cheapest place in
the project to be wrong, so it is where the hard arithmetic goes.

In order: the value objects (`Amount`, `MemberId`, `EntryId`, `Scale`,
`EntryDate`), then the group aggregate and its members, then entries with their
kinds, sides and schemes, then the split arithmetic of format-spec 7, then
balances including the TR-33a residue absorption, then the sealed event
hierarchy of format-spec 4.2, then replay.

The property tests of TR-46 belong here and should be written alongside, not
after: `absolute` values summing exactly to the entry amount, `adjustments`
summing to no more than it, every computed side's shortfall inside the
truncation bound, and balances across a generated group summing to zero. These
are the tests that justify the aggregate returning an event rather than mutating
itself (development.md) — a function from state and input to an event is
trivially property-testable, and a mutating object is not.

**Recurrence goes last in this phase and is not needed by anything else.**
Occurrence expansion (event-model 6) is the most intricate piece of pure logic
in the app and the only one no other module blocks on.

Exit condition: a group can be built, entries recorded, and balances derived, in
unit tests alone, with no database and no screen.

## 4. Phase 2 — `:format`

The wire format, still pure, still fast to test. The envelope and payload codec,
the JSON restrictions and parser limits (TR-39), the derived occurrence ids, the
chain link computed over the exact retained bytes rather than a re-serialised
structure (TR-26b), and the age binding behind the `AgeCodec` port.

The vectors written in Phase 0 run here, and TR-47's test — that an update event
contains only changed fields and that applying one leaves unnamed fields
untouched, including fields this version does not recognise — is what keeps
format-spec 8's forward compatibility honest.

Keeping the age binding behind a port matters beyond tidiness: if TR-23 landed
on gomobile, the port is the seam that stops an AAR dependency spreading past
`:format`.

Exit condition: an object can be produced, encrypted, decrypted, chain-verified
and re-read byte for byte.

## 5. Phase 3 — A local-only app

The first vertical slice, and the first thing a person can use. `:storage` and
`:app` arrive together; `:sync` and `:backend-s3` do not exist yet.

Storage first: the canonical event log (`event_row`), the retained-object table,
and the projection, with the event and its projection written in one transaction
so the screen is correct before any network exists (TR-6, TR-7, TR-17b). Prove
TR-18 immediately by dropping every derived table and rebuilding from the log —
if that works on day one it keeps working, and if it is deferred it quietly
stops being true.

Then the screens, in the order a first-time user meets them:

1. Group list, empty and populated (GR-1, GR-2)
2. New group, but only the "Nur auf diesem Gerät" path
3. Group overview
4. Entry editor, entry detail
5. Amount calculator — evaluation in `:domain` in 10⁻⁸ units, keypad in
   `:app/ui` (TR-34, TR-35)
6. Split editor, all four schemes
7. People and balances
8. Entry history
9. Group settings, app settings

This order is not arbitrary: each screen is reachable from the one before it, so
there is a working app after every step rather than a set of screens waiting to
be connected. The backend setup, join, activity and sync screens are absent
because nothing behind them exists yet — building them now would mean building
them against imagined behaviour.

Exit condition: a group can be created, used and deleted entirely offline, and
the derived tables can be dropped and rebuilt without the user noticing.

## 6. Phase 4 — `:backend-s3` and the write path

`:backend-s3` first, and inside it the probe before anything else. BE-7 refuses
a backend that cannot do conditional writes, and TR-49 requires that refusal to
be exercised against a backend that genuinely lacks them — so the probe is both
the first feature and the first integration test. The minimal S3 client over an
HTTP client (TR-27), SigV4, addressing style and the `412` mapping (TR-28,
TR-30) follow.

TR-48's suite against a real S3 in a container starts here, covering the
conditional write, a losing conditional write, and an interrupted write resolved
by batch id. Then `:sync`'s writer: batching, the bounded debounce (TR-14),
picking the next number, and the `412` recovery that fetches the winner, applies
it, rebuilds the object so its chain link points at the new predecessor, keeps
the batch id and retries (TR-26c).

The backend setup screens come with the code they describe, in wizard order:
Speicherort, Test, Inhalt, Verschlüsselung, Passphrase, Bestätigung — including
the plain-HTTP warning (PR-5) and the read-only and refused outcomes, because
those are the states the probe actually produces and the wizard is mostly a
report on them.

Passphrase handling lands here too: generation from the shipped word list
(TR-25a), the on-device strength estimate (TR-25b), and unlocking off the main
thread once per session and never on the path of reading an object (TR-26).
Credentials and the passphrase are kept sealed under a keystore key (TR-25,
TR-36) from the start, since connecting a group is what first stores them.

## 7. Phase 5 — The read path and a second device

Listing from one number below the highest object held, truncation detection and
the halt that follows it (TR-40), verify-then-apply so the chain link is checked
before any event is applied (TR-26a), and the partial projection invalidation
that starts from `event_item_ref` and lets the domain decide how far a rebuild
propagates (TR-18aa, TR-18c).

One mutex per group (TR-15) is required from the first line of this phase, not
added when the first race appears — a deferred background poll firing during a
manual sync is the normal case, not an edge one. WorkManager polling and its
schedule (TR-9, TR-14) follow.

The screens: Join group and its three outcomes, the sync problem states
repairable and unrepairable, the sync-halted banner, and Activity with its
unread marking.

A second device becomes the test rig here and stays one. Everything in this
phase is either invisible or wrong on a single installation.

## 8. Phase 6 — Recurrence

Series and segments, occurrence expansion, automatic creation and the suggestion
cards that are confirmed, edited or declined, and the recurrence editor.

This is deliberately last among the features. It is the only part of the app
that produces data without a user, so running it after sync means occurrences
are exercised across two devices from the day they exist rather than being
retrofitted into a sync path that never saw them.

## 9. Phase 7 — Hardening

The accessibility pass — every colour-carried meaning also carried by a sign or
a label, checked in greyscale (UX-6) — German strings at roughly a third longer
than English without reflow (UX-5), a confirmation that nothing transmits
(TR-53), the reproducible build proven from a clean checkout (TR-51), and then
F-Droid (TR-52). The hosted provider compatibility matrix (TR-50) is Later and
belongs here or after.

## 10. The design work

The Figma file is ahead of the code and should stay just ahead of it rather than
being handed over in one piece. Two things are worth doing now, before Phase 3:

- **Export the tokens to code.** The three token files become `Color.kt` and
  `Theme.kt` with both schemes wired up. Doing this by hand once, early, is what
  stops hexadecimal literals appearing in composables later. The six group
  colours are the deliberate exception and stay literal (ui-design.md 8).
- **Build the two components first** — the docked FAB and the segmented control
  — because every screen in Phase 3 uses at least one of them.

After that, hand over screens one phase ahead of the code that needs them. The
frames for Phases 4 and 5 already exist, so the remaining design work is not
drawing but keeping the file in step as implementation turns up things the
drawings did not anticipate — which it will, and which is the normal outcome of
building something, not a failure of the design.

## 11. What to do first

Concretely, the first few commits:

1. `chore: gradle skeleton, version catalog, spotless, CI`
2. `docs: format test vectors` — TR-45, the section format-spec is missing
3. `docs: record the age implementation decision` — TR-23, in decisions.md
4. `docs: resolve object numbering and group.created creator`
5. `feat: domain value objects` — `Amount`, `Scale`, `EntryDate`
6. `feat: split arithmetic and its property tests` — format-spec 7, TR-46

Nothing in that list needs an emulator, a backend or a screen, and by the end of
it the hardest arithmetic in the app is settled and provable.
