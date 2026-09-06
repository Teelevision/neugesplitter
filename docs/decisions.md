# Neugesplitter — Decisions and Open Questions

**Status:** Living document. Settled questions are kept here so the reasoning
behind them is not lost, and so that the requirements can state _what_ without
also arguing _why_. Requirement IDs refer to
[product-requirements.md](product-requirements.md); the mechanisms they rest on
are described in [sync-design.md](sync-design.md).

---

## Decisions

- **Do we run a sync server?** No. An account-free, content-blind server invites
  abuse and puts its operator in legal trouble, and defending it requires device
  attestation that excludes exactly the privacy-minded users this app is for.
  The users bring their own storage instead.
- **What is the first backend?** S3-compatible object storage. It is the only
  widely available storage that offers an atomic conditional write
  (`If-None-Match: *`), which is what makes concurrent appends safe without a
  server; it is self-hostable (MinIO, SeaweedFS, Ceph) as well as rentable, and
  credentials can be scoped to one bucket or prefix without touching the
  server's configuration. Other types can follow (BE-14).
- **Why not WebDAV or FTP?** Neither has a dependable atomic create-if-absent.
  WebDAV's conditional `PUT` is ignored by most implementations, and FTP has no
  conditional write in the protocol at all. Both can only be made to work by
  claiming a name and then filling it, which leaves a window in which an entry
  is lost while its author is told it was saved.
- **Is the backend's authentication an "account"?** Not in the sense this
  product rejects. Users authenticate against their own storage; there is no
  party here that could collect their data.
- **Is group content encrypted on the backend?** Optionally, from the first
  version. There is no untrusted party in the middle, but with rented object
  storage the provider is one, so a group can be sealed with a passphrase
  (PR-11, PR-12). Off by default, chosen when the backend is configured, and
  fixed thereafter — turning it on later would mean rewriting every object,
  which the append-only design has neither a mechanism nor a permission for.
- **What does the encryption?** age, unmodified. Writing our own construction
  would mean unreviewed cryptography protecting other people's money in a
  project that can never issue a correction, and the alternative is specified,
  audited and implemented in several languages. It also means a member can read
  their own history with the `age` command-line tool if this app ever stops
  existing (BE-16).
- **Passphrase or key pair?** A key pair. age can encrypt to a passphrase
  directly, but it derives a key with scrypt for every file, deliberately and
  expensively — which would make decrypting a whole history, in the app or at a
  shell, cost a derivation per object. So the group has one key pair, and the
  root object _is_ the private key encrypted to the passphrase — an age file,
  not a key wrapped in JSON, so `age -d` opens it and `age -i` takes what comes
  out. The public key is stored nowhere and derived from the private one, so a
  key pair costs nothing in exposure: reading and writing stay behind the one
  secret.
- **Are the objects chained?** Yes. Each carries a keyed hash of the previous
  object's payload exactly as it was written, keyed from the private key. That
  makes the log a history rather than a pile — no insertion, no reordering, and
  no transplanting an object from another group or another position, which a
  provider could otherwise do without decrypting anything. It does not detect
  truncation, and nothing yet does.
- **How do people join a group?** Everyone configures the same backend, then
  picks who they are from the member list (BE-5, BE-6). No QR codes, no
  invitations, no key exchange.
- **How do the other members receive the backend settings?** By whatever means
  the group already uses to talk to each other. The app offers no mechanism, and
  a one-time setup effort is acceptable for the audience this product is for
  (product requirements, section 2).
- **How does a passphrase reach the other members?** The same way, and
  deliberately with no help from the app. Every convenient transfer channel
  would carry the passphrase next to the settings it protects, which defeats the
  point of having one. The members work it out among themselves.
- **One shared backend key, or one per member?** Either; the app does not care.
  A provider can issue a key per person, and a single shared key works too. In
  the typical case every member has full access anyway (GR-10). A key scoped to
  one bucket or prefix, without delete permission, is worth recommending because
  it makes SY-4 something the storage enforces rather than something the app
  intends.
- **One bucket per group, or one prefix per group?** Whichever the users already
  have. A bucket per group makes isolation and credential scoping trivial, but
  not every provider or plan lets people create buckets freely; a prefix inside
  a shared bucket works everywhere. The app supports both and recommends
  neither.
- **Must the backend be writable?** No. Read-only access is enough to follow a
  group; the app indicates that local changes stay local (BE-8).
- **How are concurrent changes kept from colliding?** Each change is written as
  its own object, created conditionally so the first writer wins and the loser
  retries at the next number; installations never overwrite one another (SY-4).
- **Are old objects merged together to keep their number down?** Not in the
  first version. A sync pass lists only what is new, so steady-state cost does
  not grow with the history; batching a session's changes into one object
  (SY-14) keeps the count proportional to sync passes rather than entries. The
  numbering scheme is designed so that merging can be added later without a
  format change.
- **Who decides how often the app polls?** The user, per group (SY-13). Every
  poll is a request somebody pays for, so the trade is money as much as
  freshness, and hiding it in a default would be dishonest.
- **What if a backend location already holds something else?** The app inspects
  it and refuses to attach a group to a location holding a different group or
  unrelated data (BE-9). Another state of the same group is not an error — it
  simply syncs (BE-10).
- **Backends inside a home network only?** Supported later, with the "syncs only
  when I am home" behaviour presented as normal (SY-12).
- **Where do attachments live?** On the group's backend, next to its entries
  (EN-11).
- **Are recurring occurrences stored?** No. An occurrence follows by calculation
  from the series definition, which every installation already has, so every
  installation derives the same one and nothing needs to be written or synced
  (RE-8). Events appear only when someone confirms, declines or edits an
  occurrence.
- **Which timezone determines a recurring occurrence's date?** None, because a
  recurrence never involves a time. A series is a start date, a pattern and an
  end date, so every installation derives the same occurrences with the same
  identities. All a timezone changes is when a given device first shows one
  (RE-14).
- **How precise is the arithmetic?** Amounts are integers at 10⁻⁸ and divisions
  simply truncate; no remainder is redistributed. Exactness would cost a
  tie-break rule that every installation and every future version has to
  reproduce forever, to avoid an error thousands of entries cannot push as far
  as a cent (event-model section 3).
- **Who absorbs the rounding when balances are displayed?** The creditors.
  Balances are rounded for display and the discrepancy is taken from the members
  who are owed, largest first, so debtors owe exactly what they are shown. Two
  people owing a third and both rounding down means the third carries the cent,
  which is the right party — the alternative is telling somebody they owe a cent
  no entry accounts for.
- **How many decimal places are shown?** Two, as for euros. The count is a
  property of the group and is written into `group.created` from the first
  release, even though nothing offers a choice yet — a field added later would
  have no answer for the groups that already exist, since there is no server to
  fill one in. A group may change it afterwards: nothing stored is in those
  units, so all that moves is what is displayed and which creditor absorbs the
  leftover.
- **Multiple currencies per group?** No. The product has no currency concept at
  all; amounts are numbers.
- **Unequal payers _and_ unequal beneficiaries in one entry?** Yes. Both sides
  are configured independently (EN-6).
- **Who may edit what?** Every member may edit everything. Total trust inside
  the group is assumed; the immutable history (product requirements, section
  4.5) is the safeguard.
- **Do update events restate an object or name what changed?** They name what
  changed, and are applied as a shallow merge; `null` clears an optional field.
  Restating the whole object would mean an installation on an older release,
  correcting a typo, silently strips every field a newer release added — and
  with no server, old and new installations write into the same log for as long
  as the group exists. It costs nothing in conflict handling, because events
  still apply in log order and that now settles each field separately
  (event-model section 4).
- **Is `by` a claim or a fact?** Technically a claim — nothing authenticates it
  (TM-3) — and displayed as a fact. A group is people who already trust one
  another (principle 3), so hedging every name in the history would advertise a
  doubt the product does not have, and would stop nobody who wanted to write a
  false one. What must not happen is the history being presented as evidence
  outside the group.
- **How is a broken backend repaired?** Not automatically. An installation
  notices that the backend has gone backwards on its next sync, stops syncing
  that group and says so; the group stays usable locally until a person decides
  what happened (SY-15). Repair is then re-uploading: every installation keeps
  each log object exactly as it was stored, still encrypted if the group is
  encrypted, so missing objects go back byte for byte under the same numbers and
  the chain still verifies for everyone else. Objects that are present but wrong
  the app cannot repair, because its credential has no delete permission on
  purpose; somebody empties the location with the provider's own tools, or the
  group moves to a fresh prefix (sync-design section 7). The extra disk is the
  price of being able to recover at all without a server.
- **Are orphaned attachments collected?** No. An attachment is uploaded before
  the event referencing it, so an abandoned entry can leave one behind, and
  nothing deletes it — the credential cannot, and a sweep could never be sure no
  installation is about to reference it. Nothing points at an orphan, so no
  installation downloads one, and a group moving to a new backend sheds them:
  what nobody holds, nobody uploads.
- **Percentages as a split scheme?** No. Percentages are just shares and are
  covered by SP-3.
- **Configurable default split scheme per group?** No. Equal is always the
  default; only the default participants are configurable (SP-8).
- **Mark a period as settled, so balances start fresh?** No. A group that has
  run its course can simply be replaced by a new group.
- **How does a member notice changes made by others?** Unseen changes are
  highlighted in the app (HI-5 to HI-8) and, later, surfaced as local
  notifications (SY-10).
- **Is it recorded which installation acts as which member?** No, and no event
  mentions an installation. The choice made at setup (BE-6) stays on the device,
  and authorship is whatever the app writes into the event (event-model section
  5).
- **How is a passphrase's strength assured?** By generating it — six words or
  more from a fixed list, drawn with the platform's secure generator (PR-12a).
  age's scrypt work factor is fixed and cannot be raised to compensate for a
  weak passphrase, so the passphrase has to carry the strength itself, and a
  generated one is the only kind whose strength is known. A user who wants their
  own gets a warning rather than a refusal (PR-12b): it is their storage.
- **What are the parser limits?** 8 MiB per object, nesting depth 32, 64 KiB per
  string, 10 000 events, enforced while parsing rather than after (format-spec
  2.2). An object over any of them stops the log instead of being skipped,
  because skipping leaves one device disagreeing with the others in silence —
  the failure mode this format avoids everywhere else.
- **How is attachment decoding bounded?** In memory and dimensions, and lazily
  (TR-41) — as a resource matter, not a security one. An attachment arrives from
  somebody who already holds the credentials, and its name is the hash of its
  content, so it is the bytes that member uploaded. That is the same boundary
  everything else in the design sits behind (TM-6).
- **What ends a series without ending its past?** `series.ended` does, and it is
  not what delete means. So there are both: ending stops a series from a date
  onward and leaves everything it already produced standing, while
  `series.deleted` removes the series and all of its occurrences, past ones
  included, exactly as deleting an entry removes an entry (RE-7). A single
  occurrence goes the same way with `occurrence.deleted`, which is also how a
  suggestion is refused — both say that one due date produces nothing (RE-5,
  RE-15). Nothing leaves the log in any of these cases; the history keeps it and
  a later event can restore it.
- **Does an entry need a schema version of its own?** No. The object header
  carries the format version, and a change to what an event _means_ is a change
  of `type`, which older installations already refuse rather than misread. If
  one is ever needed regardless, the absence of the field is version 1 and the
  version that needs it names itself.
- **Does SY-13 promise more than the platform delivers?** It did. The setting is
  now the shortest time the app will wait between polls rather than a schedule,
  because Android runs deferrable work no more often than every fifteen minutes
  and defers even that for hours when the device is dozing (TR-9, TR-10). The
  illustration in sync-design section 8 uses fifteen minutes for the same
  reason: five was never achievable.

## Open Questions

Every unsettled question lives here; the other documents state results and point
back to this list. There are none at the moment.
