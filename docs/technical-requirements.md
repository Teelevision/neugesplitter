# Technical Requirements

**Status:** Draft. Derived from
[product-requirements.md](product-requirements.md), which states what the
product does; this document states what the software must be in order to do it.
Where a product requirement turns out to be unachievable on the platform, that
is recorded as an open question in [decisions.md](decisions.md) rather than
quietly weakened here.

Priorities and the meaning of MUST / SHOULD / MAY follow the product
requirements.

---

## 1. Platform and toolchain

- **TR-1** (Core): The client MUST be a native Android application written in
  Kotlin (UX-1).
- **TR-2** (Core): `minSdk` MUST be 33. `java.time`, a mature Keystore, scoped
  storage and the runtime notification permission are all simply there, with no
  desugaring and no branch for the old way of doing it. Android 13 is several
  years old, this is a first release with no installed base to keep, and every
  version supported is a version the background-execution behaviour of section 3
  has to be verified on.
- **TR-3** (Core): `targetSdk` MUST track the current Android release, and each
  bump MUST be accompanied by a review of the background-execution behaviour in
  section 3.
- **TR-4** (Core): The app MUST NOT depend on Google Play Services, so that it
  can ship through F-Droid as well as Play (TR-31).
- **TR-5** (Should): The UI SHOULD be built with Jetpack Compose. Nothing in the
  design depends on this.

## 2. Offline-first behaviour

- **TR-6** (Core): Every screen MUST render from local storage alone. No UI path
  may await a network call (SY-1, SY-5).
- **TR-7** (Core): Creating and editing entries MUST succeed with no backend
  configured and with the backend unreachable (BE-1, SY-3).
- **TR-8** (Core): Network failure MUST be surfaced as sync state (SY-6), never
  as a failure of the action the user took.

## 3. Background execution — and where SY-13 breaks

This is the area where the platform, not the design, decides what is possible.

Android will not run periodic work more often than every fifteen minutes, and
will not honour even that when the device is dozing or the app has fallen into a
low App Standby bucket, where deferral to a maintenance window hours later is
normal and correct behaviour. A poll interval is therefore **an upper bound on
frequency, not a schedule**.

- **TR-9** (Core): Background polling MUST use the platform's deferrable work
  scheduler and MUST tolerate arbitrary deferral.
- **TR-10** (Core): The configurable interval (SY-13) MUST have a minimum of 15
  minutes, and the UI MUST express it as "at most this often" rather than as a
  promise.
- **TR-11** (Core): The app MUST NOT request exemption from battery
  optimisation, and MUST NOT use a foreground service to keep sync alive.
  Neither is justifiable for an expense tracker.
- **TR-12** (Core): Because delivery is not guaranteed, sync state (SY-6) MUST
  show the time of the last _successful_ sync, not the time of the last attempt.
- **TR-13** (Core): A manual sync (SY-9) MUST run immediately and MUST NOT be
  routed through the deferrable scheduler.
- **TR-14** (Core): The batching delay (SY-14) MUST be implemented as a debounce
  in the app, bounded and independent of the polling interval.
- **TR-15** (Core): Sync MUST be safe to run concurrently with itself and MUST
  be interruptible at any point without corrupting local state. Deferred work
  will sometimes fire while a manual sync is running.

## 4. Local persistence

- **TR-16** (Core): Local storage MUST be SQLite.
- **TR-17** (Core): The **bytes of every log object MUST be retained exactly as
  the backend holds them**, keyed by object number, in addition to any parsed
  projection — encrypted, for an encrypted group. This is what makes SY-11
  possible: restoring a backend means putting the same objects back, and
  anything reconstructed from a projection would be different bytes.
- **TR-17b** (Core): The installation's own history for a group MUST also be
  kept in a canonical local event log in SQLite, so that a backend can be added
  later without reconstructing events from projections or current state. Each
  row MUST retain the event as JSON text in the wire schema plus its local sync
  state. Before sync assigns backend object numbers, local ordering MAY be
  provisional and events MAY still be batched or reordered for upload.
- **TR-17c** (Core): The JSON text retained for a local event MUST be treated as
  durable source material, not as a cache of typed columns. It MUST NOT be
  normalised, trimmed, re-keyed or otherwise rewritten on the way in. SQLite's
  JSON support MAY be used for indexing and queries, but the durable source of
  truth remains the stored JSON text.
- **TR-17a** (Core): Restoring a missing object MUST upload the retained bytes
  unchanged. An implementation MUST NOT re-serialise or re-encrypt on the way,
  because either would break the chain link the next object already carries
  (format-spec 5.3).
- **TR-17d** (Core): Because one backend object can batch several events
  (sync-design section 4), retained object bytes and local event rows MUST be
  stored separately and linked explicitly. For an encrypted group, once a log
  object has been confirmed written or fetched, its exact encrypted bytes MUST
  be retained and associated with the events it carried.
- **TR-18** (Core): Group state MUST be a projection derived from the event log,
  and MUST be reconstructible by replaying from scratch. Any disagreement
  between projection and log is resolved by discarding the projection.
- **TR-18a** (Core): Entries, occurrences, balances and any other user-visible
  history MUST be derived from the local event log rather than interpreted in
  SQL or encoded as an independent source of truth. The domain replay code is
  the only place that decides what an event means.
- **TR-18aa** (Core): To avoid rebuilding every derived row when synced events
  shift relative to provisional local ordering, storage MUST keep references
  from retained events to the derived items they last contributed to. These
  references are an invalidation aid only: they identify what may need to be
  rebuilt, but they do not define what the events mean.
- **TR-18b** (Core): A migration that changes replay logic, projection schema or
  event interpretation MUST discard every derived table and rebuild it from the
  retained events before the data is used again.
- **TR-18c** (Core): When a sync changes the effective order or grouping of
  events, the implementation SHOULD invalidate and rebuild only the affected
  derived items and everything downstream of them, using the event references as
  a starting set. The decision about which items are affected MUST come from the
  domain replay code rather than from SQL rules alone.
- **TR-19** (Core): Replay MUST be deterministic and MUST depend only on object
  number order, never on the order in which objects arrived or the times they
  were fetched.
- **TR-20** (Core): Deleting a group (GR-11) and deleting all local data (PR-9)
  MUST remove the retained object bytes as well as the projection.
- **TR-21** (Later): Whether the local database is additionally encrypted at
  rest is a separate decision; platform file-based encryption is assumed for
  now, and an app lock (PR-13) is not a substitute for it.

## 5. Cryptography

The format names no primitive, so this is entirely a dependency decision: which
implementation of age to depend on, and what to do if the answer is
unsatisfying.

- **TR-22** (Core): Encryption MUST use an existing implementation of age v1. It
  MUST NOT be assembled from primitives, and the format MUST NOT be
  reimplemented from its specification — the point of adopting age is to stop
  writing cryptography, and a from-scratch implementation would put it straight
  back.
- **TR-23** (Core): The Kotlin implementation (kage) MUST be evaluated before it
  is adopted, against: the scrypt recipient, ASCII armouring, streaming
  decryption, licence, and whether anyone is maintaining it. If it falls short,
  the alternatives are the Go reference compiled in through gomobile, or a JNI
  build. This choice MUST be made deliberately and recorded, because every group
  ever created depends on it.
- **TR-24** (Core): The group key pair and all random identifiers MUST come from
  the platform's cryptographically secure generator (threat model TM-11).
- **TR-25** (Core): Passphrases MUST be held in memory only as long as needed
  and MUST NOT be written to disk, logs or backups (PR-12).
- **TR-25a** (Core): A generated passphrase (PR-12a) MUST be at least six words
  drawn with the platform's secure generator from a list of at least 7 776
  words, about 77 bits, with every draw uniform and independent. The list MUST
  ship with the app, MUST contain only unaccented lowercase ASCII, and MUST
  avoid words that sound alike, because this is a string somebody reads aloud or
  copies by hand onto another device.
- **TR-25b** (Core): The strength estimate behind PR-12b's warning MUST run
  entirely on the device. Nothing about a passphrase leaves it, including to a
  breach-checking service.
- **TR-26** (Core): Unlocking the identity MUST run off the main thread and MUST
  show progress: age's scrypt work factor is deliberately expensive and is
  perceptible on a low-end phone. It MUST happen once per session and MUST NOT
  be on the path of reading an object.
- **TR-26a** (Core): The chain link MUST be verified on every object before its
  events are applied, and a mismatch MUST stop the sync and mark the group
  compromised. Skipping verification is not an optimisation; the threat model
  relies on it (TM-1a).
- **TR-26b** (Core): The exact payload bytes of each object MUST be retained as
  received, and the chain link MUST be computed over those bytes rather than
  over a re-serialised structure (format-spec 5.3).
- **TR-26c** (Core): An object rebuilt after losing a number MUST recompute its
  chain link and MUST keep its batch id (sync-design 4).

## 6. Backend access

- **TR-27** (Core): The S3 client SHOULD be a minimal implementation over an
  HTTP client rather than a vendor SDK. The whole operation set is `GET`,
  `HEAD`, `PUT` and `ListObjectsV2`; what the design actually needs is exact
  control over the `If-None-Match` header and over path-style versus
  virtual-hosted addressing, which is precisely what an SDK abstracts away — and
  the compatibility work in section 10 depends on being able to send a specific
  request rather than a portable one.
- **TR-28** (Core): SigV4 request signing MUST be implemented and MUST be
  covered by tests against known vectors.
- **TR-29** (Core): The backend layer MUST sit behind an interface expressed in
  the design's own terms — create-if-absent at a number, get, list from a
  number, put attachment — with no S3 concepts in the signature (BE-14).
- **TR-30** (Core): A `412` response MUST be distinguishable from every other
  failure, including transport failures, because the write path's correctness
  depends on that distinction (sync-design section 4).
- **TR-31** (Core): All dependencies MUST be free and open source, so the build
  can be reproduced and published through F-Droid.

## 7. Numbers

- **TR-32** (Core): Amounts MUST be represented internally as an exact decimal
  or as a scaled integer, and MUST NOT pass through a binary floating-point type
  at any point, including during JSON parsing (format-spec 3.3).
- **TR-33** (Core): All split and balance arithmetic MUST be integer arithmetic
  in units of 10⁻⁸. Division truncates toward zero and no remainder is
  redistributed; products MUST be computed in a type wider than 64 bits before
  dividing (format-spec 7).
- **TR-33a** (Core): Rounding MUST happen only where a figure is displayed, and
  the balance residue MUST be absorbed by creditors as format-spec 7.1
  specifies. A test MUST assert that displayed balances sum to zero.
- **TR-34** (Core): Locale-aware formatting (UX-4) MUST apply to display only.
  Parsing, storage and hashing MUST use the invariant grammar in format-spec
  3.3.
- **TR-35** (Core): The calculator (AM-2) MUST evaluate strictly left to right
  in the same 10⁻⁸ integer units, and the committed result MUST be rounded to
  the group's `scale`.

## 8. Security implementation

- **TR-36** (Core): Backend credentials MUST be encrypted with a key held in the
  platform keystore and never exported from it (PR-6). The Jetpack
  `androidx.security.crypto` library MUST NOT be assumed available or
  maintained; its current status is to be verified before use.
- **TR-37** (Core): Automatic cloud backup MUST exclude credentials and
  passphrase material (PR-6).
- **TR-38** (Core): No group content, credential, passphrase or backend URL may
  appear in logs in any build configuration.
- **TR-39** (Core): Objects fetched from a backend MUST be parsed within the
  limits of format-spec 2.2 — 8 MiB stored size, depth 32, 64 KiB per string, 10
  000 events — enforced during parsing rather than after it: the size from the
  response before the body is read, the rest as the document is consumed. No
  buffer may be sized from a length the object declares. An object that exceeds
  a limit MUST halt the log there and put the group into the read-only state
  format-spec 8 defines, not be skipped (threat model TM-5).
- **TR-40** (Core): Every sync pass MUST list from below the highest object
  number it holds and MUST confirm that object is still there. A backend whose
  highest number is lower than the installation's, or which is missing an object
  the installation already holds, MUST halt syncing for that group and MUST be
  reported (SY-15, threat model TM-1). It MUST NOT be treated as an empty sync,
  and the app MUST NOT write to a backend in that state.
- **TR-40a** (Core): Restoring MUST be an explicit action, MUST upload the
  retained bytes under their original numbers (TR-17a) through the ordinary
  conditional write path, and MUST re-verify the whole log before syncing
  resumes.
- **TR-41** (Later): Attachment decoding MUST be bounded in dimensions and
  memory and MUST NOT happen eagerly on arrival. This is a resource bound: an
  attachment arrives from a member who already has full access, and content
  addressing (format-spec 9) proves it is the one they uploaded.

## 9. Localisation and accessibility

- **TR-42** (Core): All user-facing strings MUST be externalised; English and
  German MUST be complete (UX-5).
- **TR-43** (Core): Plurals and number formatting MUST use platform facilities
  rather than string concatenation.
- **TR-44** (Optional): Screen reader labels, scalable text and contrast MUST
  meet platform accessibility guidance (UX-6).

## 10. Testing and conformance

- **TR-45** (Core): The format specification's test vectors MUST be executed as
  tests. They are the definition of correctness for anything another
  implementation would also have to produce.
- **TR-46** (Core): A property test MUST assert that both sides of every
  generated split obey format-spec section 7: `absolute` values sum exactly to
  the entry amount, `adjustments` sum to no more than it, and every computed
  side's shortfall stays within the truncation bound. It MUST also assert that
  balances across a generated group sum to zero after the TR-33a
  residue-absorption step (BA-2).
- **TR-47** (Core): A test MUST assert that an update event contains only the
  fields that changed, and that applying one leaves every field it does not name
  untouched, including fields the implementation does not recognise (format-spec
  4.2).
- **TR-48** (Core): An integration suite MUST run against a real S3
  implementation in a container, and MUST cover the conditional write, a losing
  conditional write, and an interrupted write resolved by batch id.
- **TR-49** (Core): The backend probe (BE-7) MUST be exercised against a backend
  known to lack conditional writes, and MUST refuse it.
- **TR-50** (Later): A compatibility matrix MUST be maintained for the hosted
  providers named in sync-design section 10, recording what each does with
  conditional writes, addressing and listing.

## 11. Build and distribution

- **TR-51** (Core): The build MUST be reproducible from a clean checkout with no
  credentials.
- **TR-52** (Later): Releases SHOULD be published through F-Droid; a
  reproducible build is a precondition.
- **TR-53** (Core): The app MUST contain no analytics, advertising or crash
  reporting that transmits anything (PR-7, PR-8).
