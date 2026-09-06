# Development

**Status:** Draft. How work on this repository is done — the modelling approach,
the tooling, and the habits that keep the documents and the code from drifting
apart. The shape the code takes is in [architecture.md](architecture.md). This
is a working agreement rather than a specification, so it contains no MUST; the
normative documents are the format spec and the two requirement documents.

---

## 1. Domain-driven, and what that buys here

The domain was modelled before any code existed, which is the fortunate part:
[event-model.md](event-model.md), [format-spec.md](format-spec.md) and section 3
of [product-requirements.md](product-requirements.md) already _are_ the
ubiquitous language. Group, member, entry, payer, beneficiary, side, scheme,
series, occurrence, balance, installation, backend, log object — each of those
words means exactly one thing, and the code uses them unchanged.

Two rules follow, and they are the only ones worth enforcing strictly:

- A word that exists in the documents is not renamed in code. There is no
  `SplitDto` for a side and no `TransactionEntity` for an entry.
- A concept the code needs but the documents do not have gets written down
  first, in whichever document owns it. Naming something in an implementation
  and back-filling the design later is how a ubiquitous language stops being
  ubiquitous.

### Bounded contexts

| Context     | Kind       | Language it speaks                                             |
| ----------- | ---------- | -------------------------------------------------------------- |
| **Group**   | Core       | members, entries, splits, balances, series, occurrences        |
| **Sync**    | Supporting | log objects, numbers, batches, chain links, conditional writes |
| **Backend** | Generic    | buckets, keys, `If-None-Match`, SigV4, `412`                   |

The format specification is the published language between installations, and
the backend context is a bought-in concept that gets an anti-corruption layer:
S3 vocabulary stops at the `LogStore` port (TR-29), so BE-14's "some other kind
of backend later" is an adapter rather than a rewrite.

### The group is the only aggregate

One group is one consistency boundary. Members, entries and series exist only
inside a group, nothing refers across groups, and no requirement asks for
anything that would (GR-1, ID-2). Decisions that need invariants — does this
member still exist, does this side's input sum correctly — are taken on a loaded
group; screens that only display read from the projection, which is why
"unlimited entries" (GR-4) never means loading them all.

### The domain events _are_ the storage format

Usually that coupling would be a warning; here it is the design. Format-spec 4.2
is normative and immutable once real groups exist, so the event types are fixed
from outside the model: a refactor may reshape a Kotlin class, but it cannot
rename `entry.updated`. The domain owns the events as a sealed hierarchy; the
mapping to and from JSON lives in `:format`, so that the constraint is visible
in one place instead of being felt everywhere.

### Purity, because replay has to be deterministic

`:domain` is plain Kotlin with no Android, no JSON and no clock. TR-19 requires
replay to depend only on object number order, which means nothing in the domain
may call `Instant.now()`, read a locale, or draw a random number: ids and
timestamps are handed in by the caller that decided to create the event. `at` is
recorded and never consulted for ordering (event-model 1).

The return on that discipline is that the interesting half of the app — splits,
balances, rounding, recurrence expansion, replay — tests on the JVM in
milliseconds, with no emulator anywhere near it.

### Modelling habits

- Value objects for anything with a validity rule: `Amount`, `MemberId`,
  `Scale`, `EntryDate`. They are parsed at the edge, so an invalid one cannot
  exist further in (TR-32).
- No bare `Long` amount ever crosses a function signature. The unit is 10⁻⁸ and
  the type says so.
- Behaviour on the aggregate — `group.record(entry)` — rather than an
  `EntryService` that reaches into it.
- No use-case class per user action. A screen calling a repository and an
  aggregate is not missing a layer.

## 2. Tooling

| Tool                           | For                                                         |
| ------------------------------ | ----------------------------------------------------------- |
| Gradle with the Kotlin DSL     | build, one version catalog in `gradle/libs.versions.toml`   |
| AGP + Kotlin, JVM toolchain 17 | `minSdk` 33, `targetSdk` current (TR-2, TR-3)               |
| Jetpack Compose + Material 3   | UI (TR-5)                                                   |
| Spotless                       | ktfmt for Kotlin, prettier for Markdown at 80 columns       |
| Android Lint                   | the platform checks, run as part of `check`                 |
| JUnit 4 + kotlin.test          | unit tests; JUnit 4 because instrumentation needs it anyway |
| kotest-property                | the property tests TR-46 asks for                           |
| Testcontainers + MinIO         | the integration suite TR-48 and TR-49 ask for               |
| GitHub Actions                 | one workflow: `./gradlew check assembleDebug`               |

Everything is free and open source, and nothing needs credentials to build
(TR-31, TR-51). Dependencies stay few enough to read: each new one is a thing
that can be abandoned, and this app is meant to still build in five years.

### Testing

| Level               | Where                   | Covers                                                                                              |
| ------------------- | ----------------------- | --------------------------------------------------------------------------------------------------- |
| Domain unit         | `:domain` JVM tests     | splits, balances, rounding, recurrence, replay                                                      |
| Property            | `:domain` JVM tests     | split truncation bounds of format-spec 7 (SP-7), balances sum to zero after residue handling (BA-2) |
| Format conformance  | `:format` JVM tests     | the specification's test vectors (TR-45), update merge semantics (TR-47)                            |
| Backend integration | `:backend-s3` JVM tests | conditional write, losing write, interrupted write, refusing a backend without `If-None-Match`      |
| UI smoke            | `:app` instrumentation  | a handful of screens; not a test strategy, a canary                                                 |

Tests name the requirement they exist for, in the test name:
`balances sum to zero (BA-2)`. It costs nothing and makes a failing test say
what promise broke. Testcontainers needs a plain JVM test source set, which is
one of the reasons the S3 adapter is not a package inside `:app`.

## 3. Working rhythm

Trunk-based, small commits, `type: subject` messages (`docs:`, `feat:`, `fix:`,
`build:`). No release process to speak of until there is something to release
(TR-52).

**Documents first for anything normative.** A change to the wire format, to
behaviour a user can observe, or to a requirement goes into the document before
the code, because the documents are what another implementation would read.
Everything else is code first.

Done means: `./gradlew spotlessApply check` is clean, the new behaviour traces
to a requirement id, and if the work settled an open question,
[decisions.md](decisions.md) says so.

## 4. Where decisions go

`decisions.md` is the decision log; there is no separate `adr/` directory and
there does not need to be. A question that gets answered moves out of "Open
questions" into a section that records the answer _and the reasoning_ — the
reasoning is the part that stops the same question being reopened in a year.
Requirement ids are the currency of every cross-reference: new behaviour gets a
new id rather than a quiet expansion of an old one.

## 5. Deliberately not done

- **No DI framework.** One composition root wiring constructors together. Hilt
  would buy graph management this app does not have, and cost annotation
  processing on every build.
- **No mapper layer between identical models.** A screen that wants exactly the
  domain type uses the domain type.
- **No mocking framework.** The ports are small enough that a fake is a dozen
  lines, and a fake `LogStore` that actually behaves like a bucket is worth more
  than a mock that returns what the test expected.
- **No coverage gates, no CI matrix.** One green check on one runner. This is a
  hobby project, and ceremony is the thing most likely to kill it.
