# Neugesplitter — Product Requirements

**Status:** Draft **Scope:** Product-level requirements (what the product does).
The reasoning behind them is in [decisions.md](decisions.md); software and
technical requirements are derived from this document in
[technical-requirements.md](technical-requirements.md).

---

## 1. Product Vision

Neugesplitter is a cost-splitting app for Android. It lets people track shared
expenses, income and money transfers in groups, and shows who owes whom. Groups
can be shared across devices by syncing through storage the users provide
themselves — initially S3-compatible object storage, whether rented or
self-hosted.

Three principles shape the product:

1. **No accounts, and no server of ours.** Installing the app is enough to
   start. This project operates no infrastructure at all: there is nothing to
   register with, and no place where the app's authors could collect anyone's
   data.
2. **Your data lives where you put it.** Sharing a group means pointing every
   installation at the same storage location, which the users own, rent or
   self-host. The app is a client, nothing else.
3. **Full trust inside a group.** A group is a small circle of people who trust
   each other. Every member may do everything. Instead of permissions, the app
   offers a complete, immutable history so that mistakes can be understood and
   corrected.

## 2. Target Users and Use Cases

- Flatmates tracking rent, groceries and utilities over long periods.
- Friends on a trip splitting hotels, meals and tickets.
- Couples and families sharing a household budget.
- Any small, stable group where the same people repeatedly share costs.

Sharing a group requires storage the users bring themselves, so at least one
person in a group is expected to be technically confident: someone who already
runs a MinIO or a NAS, or is willing to rent a bucket from an object storage
provider and type its settings into a phone. Everyone else in the group only has
to enter the settings they are given. Using the app on a single device requires
nothing of the sort.

## 3. Terminology

| Term             | Meaning                                                                                                                             |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **Group**        | A container for a set of members and their entries (e.g. "Trip to Rome", "Flat").                                                   |
| **Member**       | A person inside a group. Members exist per group, not globally.                                                                     |
| **Entry**        | A single recorded event: an expense, an income or a transfer.                                                                       |
| **Payer**        | Member(s) who provided the money for an entry.                                                                                      |
| **Beneficiary**  | Member(s) on whose behalf the money was spent / who receive it.                                                                     |
| **Split scheme** | The rule that distributes an amount across several members — used both for the payer side and for the beneficiary side of an entry. |
| **Amount**       | A plain number. The app has no notion of currencies.                                                                                |
| **Balance**      | The net amount a member is owed (positive) or owes (negative) within a group.                                                       |
| **Installation** | One instance of the app on one device.                                                                                              |
| **Backend**      | The storage location a group syncs through, provided by the users. Initially a prefix in an S3-compatible bucket.                   |
| **History**      | The immutable sequence of events that produced the current state of a group.                                                        |

---

## 4. Requirements

Requirement IDs are stable. Each requirement carries a priority:

| Priority     | Meaning                                                               |
| ------------ | --------------------------------------------------------------------- |
| **Core**     | Required for the first release. The product is not usable without it. |
| **Later**    | Wanted, but deliberately deferred to a release after the first.       |
| **Optional** | Nice to have. May never be built.                                     |

Inside a requirement, `MUST`, `SHOULD` and `MAY` describe how strictly the
behaviour is prescribed once the requirement is implemented — they say nothing
about _when_ it is built. A `Later` or `Optional` requirement is therefore still
written as `MUST`: it may not be in the first release, but when it is built, it
works exactly as stated. `SHOULD` and `MAY` are reserved for the few places
where the implementation genuinely has a choice.

### 4.1 Identity and Names

- **ID-1** (Core): A user MUST be able to use the full app after installing it,
  without registering an account or providing any personal data.
- **ID-2** (Core): Member names MUST be defined per group, not globally: when
  creating a group, the creating user enters their own name and the names of the
  other members. There is no app-wide profile or display name, so the same
  person can appear under different names in different groups.
- **ID-3** (Core): Several app installations MUST be able to act as the same
  member in the same group (e.g. phone and tablet, or two partners sharing one
  "household" member). Nothing about which installation acts as which member is
  recorded in the group.

### 4.2 Groups

- **GR-1** (Core): A user MUST be able to create an unlimited number of groups.
  Data is stored locally, so no product-level limit applies.
- **GR-2** (Core): A group MUST have a name and MAY have a description, an emoji
  and a colour. A group has no currency — amounts are plain numbers (see 4.7).
- **GR-3** (Core): A group MUST support an unlimited number of members.
- **GR-4** (Core): A group MUST support an unlimited number of entries.
- **GR-5** (Core): A user MUST be able to add members to a group.
- **GR-6** (Core): A user MUST be able to remove members from a group.
- **GR-7** (Core): Adding or removing a member MUST NOT change any existing
  entry. Past entries keep their payers, beneficiaries and split results exactly
  as recorded.
- **GR-8** (Core): Membership changes MUST only affect new entries (e.g. a
  removed member is no longer offered as a default participant; a new member
  is).
- **GR-9** (Core): A removed member MUST remain visible in the group's history
  and balances as long as they have a non-zero balance or appear in any entry.
- **GR-10** (Core): Every member has full access: any member MUST be able to
  create, edit and delete any entry and to change the group itself. There are no
  roles or permissions.
- **GR-11** (Core): A user MUST be able to delete a group from their own device,
  with an explicit confirmation.
- **GR-12** (Later): A user MUST be able to leave a group, and MUST be warned if
  their balance is not settled.
- **GR-13** (Later): A user MUST be able to archive a group so it is hidden from
  the main list without being deleted.

### 4.3 Backends and Sharing

A group lives on the device that created it. To share it — with other people or
with another of your own devices — every installation is pointed at the same
**backend**: a storage location the users provide. There is no invitation, no
key exchange and no server operated by this project; sharing a group is a matter
of everyone entering the same backend settings.

- **BE-1** (Core): A group MUST be fully usable without any backend. Purely
  local groups are a first-class case, not a degraded one.
- **BE-2** (Core): To share a group, the user MUST be able to configure a
  backend for it: initially an S3-compatible object storage location (endpoint,
  bucket, optional prefix, and access credentials, plus whatever addressing
  options that provider requires).
- **BE-3** (Core): The backend MUST be chosen and operated by the users —
  self-hosted, rented, or part of an existing service. This project MUST NOT
  operate one.
- **BE-4** (Core): Every group MUST be able to use its own backend,
  independently of the other groups on the same installation.
- **BE-5** (Core): Joining a shared group MUST require nothing more than
  configuring the same backend and location as the other installations.
- **BE-6** (Core): After connecting to a group, the user MUST choose who they
  are in it: an existing member from the group's member list, or a newly created
  member.
- **BE-7** (Core): When a backend is configured, the app MUST verify that it is
  reachable, that the credentials work, and that the storage honours the atomic
  conditional write the sync design depends on, and MUST report failures in
  plain language. A backend that fails the conditional-write check MUST be
  refused for writing rather than accepted in a degraded mode.
- **BE-8** (Core): Write access MUST NOT be required. A backend the installation
  can only read MUST work for following a group, and the app MUST indicate that
  local changes cannot be uploaded.
- **BE-9** (Core): When a backend location is configured, the app MUST determine
  what it contains — nothing, another state of the same group, or something else
  — and MUST report it. Pointing a group at a location that holds a different
  group or unrelated data MUST be prevented.
- **BE-10** (Core): Connecting to a location that holds an older or newer state
  of the same group MUST simply sync; it is not an error.
- **BE-11** (Core): Configuring a group's backend on a fresh installation MUST
  restore that group in full, without duplicating entries or members. This is
  also how a lost device is recovered and how an additional device is added.
- **BE-12** (Core): The app MUST make clear that everyone who can access the
  backend location can read and change the group, and that protecting the
  backend is the users' responsibility.
- **BE-13** (Core): Removing a member (GR-6) MUST NOT be presented as removing
  their access. Access follows the backend's credentials, and the app MUST say
  so where it matters.
- **BE-14** (Later): The app MUST be built so that further backend types can be
  added later without changing how groups, entries or sync behave.
- **BE-15** (Later): The user MUST be able to change a group's backend,
  including moving an existing group to a different one.
- **BE-16** (Core): What the app writes to a backend SHOULD be readable with
  ordinary tools and no software from this project: plain JSON files for an
  unencrypted group, and standard [age](https://age-encryption.org) files for an
  encrypted one. No container format of the app's own SHOULD be wrapped around
  them, and decrypting a whole history SHOULD NOT require a key derivation per
  file.

### 4.4 Entries

- **EN-1** (Core): A user MUST be able to create an entry of one of three types:
  **expense** (money paid out), **income** (money received) and **transfer**
  (money moved between members).
- **EN-2** (Core): Every entry MUST have a free-text description and an amount.
- **EN-3** (Core): Every entry MUST have a date, defaulting to today, which the
  user can change.
- **EN-4** (Core): An entry MUST record one or more payers. If several payers
  are selected, the amount is distributed across them according to a split
  scheme (see 4.6).
- **EN-5** (Core): An entry MUST record one or more beneficiaries. If several
  beneficiaries are selected, the amount is distributed across them according to
  a split scheme (see 4.6).
- **EN-6** (Core): The payer side and the beneficiary side MUST be independent:
  a single entry MUST support e.g. two payers splitting by absolute values and
  four beneficiaries splitting by shares.
- **EN-7** (Core): A transfer MUST be expressible as a payer-to-beneficiary
  movement without affecting the group's total spending.
- **EN-8** (Core): Income MUST affect balances in the opposite direction of an
  expense, using the same payer/beneficiary/split model.
- **EN-9** (Core): A user MUST be able to edit and delete any entry (see GR-10).
- **EN-10** (Later): A user MUST be able to assign a category to an entry, and
  MUST be able to manage the category list per group.
- **EN-11** (Later): A user MUST be able to attach a note and one or more images
  (e.g. a receipt photo) to an entry. Attachments are stored on the group's
  backend alongside its entries.
- **EN-12** (Later): The entry list MUST be searchable and filterable (by
  member, category, date range, type).

### 4.5 History

- **HI-1** (Core): Every change to a group — entries, membership, group settings
  — MUST be recorded as an immutable event. Events are never rewritten; a
  correction is a new event.
- **HI-2** (Core): Members MUST be able to view the history of a group: what was
  changed, by which member, and when.
- **HI-3** (Core): Members MUST be able to view the history of a single entry,
  including its previous versions and its deletion.
- **HI-4** (Core): The current entry list MUST show who created an entry and
  indicate that it was modified after creation.
- **HI-5** (Later): Changes made by other members that the user has not yet seen
  MUST be highlighted — in the group list, in the entry list and on the affected
  entries. This includes new, edited and deleted entries as well as membership
  changes.
- **HI-6** (Core): Opening an entry or its history MUST clear the highlight for
  that entry.
- **HI-7** (Core): The user MUST be able to mark all outstanding changes in a
  group as seen with a single action ("mark all as read").
- **HI-8** (Core): The seen/unseen state is local to an installation. Marking
  changes as seen on one installation MUST NOT affect other installations.
- **HI-9** (Optional): A member MUST be able to restore a deleted entry or
  revert an entry to an earlier version from the history.

### 4.6 Splitting

- **SP-1** (Core): Whenever several members are involved on a side of an entry —
  payers or beneficiaries — the user MUST be able to choose a split scheme for
  that side. Both sides are configured independently.
- **SP-2** (Core): **Equal**: the amount is divided equally among the selected
  members. This MUST always be the default for both sides.
- **SP-3** (Core): **Shares**: each member is assigned a number of shares; the
  amount is divided proportionally. Percentages are not a separate scheme — they
  are expressed as shares.
- **SP-4** (Core): **Adjustments**: the amount is split equally, then per-member
  plus/minus adjustments are applied.
- **SP-5** (Core): **Absolute values**: the user enters an exact amount per
  member.
- **SP-6** (Core): The app MUST show, before saving, the resulting amount per
  member on both sides. Where the user enters the parts directly, saving MUST be
  prevented unless they add up: absolute values to the total, adjustments to
  zero.
- **SP-7** (Core): Amounts MUST be held internally at a precision far finer than
  anything a user enters, so that dividing them loses nothing anybody can act
  on. Rounding MUST occur only where a figure is displayed, MUST be
  deterministic, and MUST leave the sum of all balances exactly zero (BA-2).
  Where a rounded unit cannot be divided evenly, the member who is owed MUST
  absorb it rather than the members who owe.
- **SP-8** (Core): A group MUST be able to define the default set of
  participants for new entries. The default split scheme is always equal (SP-2)
  and is not configurable.

### 4.7 Amount Entry

- **AM-1** (Core): Amounts are plain numbers. The app MUST NOT ask for or
  display a currency.
- **AM-2** (Core): Amount entry MUST work like a simple pocket calculator,
  offering `+`, `-`, `*` and `/`. Operations MUST be applied strictly in the
  order they are entered, from left to right. There is no operator precedence
  and there are no parentheses. Example: `12.5 + 3` yields `15.5`, then `* 2`
  yields `31`.
- **AM-3** (Core): The current result MUST be visible after every comitted
  operation.
- **AM-4** (Core): The user MUST be able to correct the last input before
  comitting and to clear the whole calculation.
- **AM-5** (Core): Only the resulting number is stored with the entry; the
  calculation itself is not kept.
- **AM-6** (Core): Meaningless input (e.g. division by zero, an unfinished
  operation) MUST be prevented or clearly indicated, and MUST NOT be saveable.
- **AM-7** (Core): Amounts MUST be entered and displayed with two decimal
  places. The number of places is a property of the group, fixed when the group
  is created; a calculator result with more places MUST be rounded to it when
  the result is committed.
- **AM-8** (Later): The user MAY choose the number of decimal places when
  creating a group. It MUST NOT be changeable afterwards.

### 4.8 Recurring Entries

- **RE-1** (Core): A user MUST be able to mark an entry as recurring.
- **RE-2** (Core): The recurrence interval MUST support at least: specific
  weekdays, specific days of the month, and every _x_ days. A weekday or
  day-of-month rule MUST accept several days at once.
- **RE-3** (Core): A recurring entry MUST be configurable as either
  **automatic** (created without user interaction) or **suggested**.
- **RE-4** (Core): For a suggested recurring entry, the app MUST present the
  pending occurrence to the user, who can **confirm**, **decline**, or **edit
  and confirm** it.
- **RE-5** (Core): Declining an occurrence MUST NOT stop the series.
- **RE-6** (Core): Editing a series MUST affect future occurrences only. Already
  created entries are never changed by editing the series.
- **RE-7** (Core): A user MUST be able to pause and resume a recurring series,
  to stop it from a date onward with its past occurrences left standing, and to
  delete it. Deleting a series MUST remove it and every occurrence it ever
  produced, exactly as deleting an entry removes an entry.
- **RE-8** (Core): Every due date MUST yield exactly one occurrence, identically
  in every installation, regardless of how many installations are online or
  which of them is running.
- **RE-9** (Core): The app MUST make clear which entries came from a recurring
  series.
- **RE-10** (Core): Pending suggestions MUST be discoverable in the app.
- **RE-11** (Optional): A recurrence MUST support an end condition: never, on a
  date, or after _n_ occurrences.
- **RE-12** (Optional): The app MUST notify the user about pending suggestions
  (see SY-10).
- **RE-13** (Optional): When the app recognises a repeating pattern in a group's
  entries (similar description, amount and interval), it MUST offer to turn
  those entries into a recurring series. The user can accept, adjust or dismiss
  the offer, and a dismissed pattern MUST NOT be offered again.
- **RE-14** (Core): A recurrence MUST be expressed entirely in calendar dates —
  a start date, a pattern, and an end date where one applies — and never in
  times of day. An installation MUST show an occurrence once its own local date
  reaches the due date, so members in different timezones MAY see a new
  occurrence hours apart. That divergence is accepted and MUST NOT be presented
  as an error.
- **RE-15** (Core): A user MUST be able to delete a single occurrence —
  suggested, confirmed, edited or automatic — in the same way they delete any
  other entry. The series MUST continue.

### 4.9 Balances and Settlement

- **BA-1** (Core): The app MUST display the current balance of every member in a
  group (who is owed how much, who owes how much).
- **BA-2** (Core): The sum of all balances in a group MUST always be zero.
- **BA-3** (Core): The app MUST show a concrete list of transfers that settle
  the group ("A pays B 12.30").
- **BA-4** (Core): The app MUST offer a **simplified settlement** that minimises
  the number of transfers needed.
- **BA-5** (Core): The user MUST be able to switch between simplified and direct
  (debt-preserving) settlement views.
- **BA-6** (Core): A suggested transfer MUST be convertible into a recorded
  transfer entry with one action.
- **BA-7** (Core): The app MUST show a personal summary ("you owe / you are
  owed") on the group screen.
- **BA-8** (Later): The app MUST show group statistics: total spending, spending
  per member, and spending per category over a period.

### 4.10 Sync and Multi-Device

- **SY-1** (Core): All data MUST be stored locally and the app MUST be fully
  usable offline, including creating and editing entries.
- **SY-2** (Core): Installations that share a group's backend MUST be kept in
  sync through it. The backend is passive storage; no logic runs there.
- **SY-3** (Core): Changes made while the backend is unreachable MUST be
  uploaded automatically once it can be reached again.
- **SY-4** (Core): Concurrent changes from several installations MUST be merged
  without data loss, and SHOULD be merged without asking the user to resolve a
  conflict. No installation may overwrite another's changes.
- **SY-5** (Core): The backend MUST NOT be required for reading or entering data
  — only for propagating it between installations.
- **SY-6** (Core): The app MUST show sync state per group (in sync / pending
  changes / error, and time of last successful sync).
- **SY-7** (Core): An installation joining a group MUST obtain its complete
  history from the backend; balances are computed locally from all entries.
- **SY-8** (Core): A backend that is unreachable, slow, read-only, out of space
  or in a partially written state MUST NOT corrupt or block local use. The app
  MUST recover on its own once the backend is healthy again.
- **SY-9** (Core): The app MUST sync automatically in the background and MUST
  offer a manual sync.
- **SY-10** (Optional): The app MUST be able to notify the user locally about
  changes received from other installations and about pending recurring
  suggestions.
- **SY-11** (Core): Every installation holds the complete history, so if a
  backend loses data, an installation MUST be able to restore it — by putting
  the objects back exactly as they were, so that the other installations see the
  history they already had.
- **SY-12** (Later): Backends that are only reachable from certain networks
  (e.g. a NAS at home) MUST be supported. The app MUST present the resulting
  "syncs only when I am home" behaviour as normal, not as a failure.
- **SY-13** (Core): How often a group polls its backend MUST be configurable.
  The setting is the shortest time the app will wait between polls, not a
  promise about the longest: the app asks the system for background work and the
  system decides when it runs, which can be hours later (TR-9, TR-10). The app
  MUST present it that way, MUST explain that polling costs requests the users
  pay for, and MUST default to something economical rather than eager.
- **SY-14** (Core): Local changes MUST NOT be uploaded one at a time as they are
  made. The app MUST wait a short, bounded time after the last change so that
  several entries made in one sitting are uploaded together. A manual sync
  (SY-9) MUST bypass the wait.
- **SY-15** (Core): If a backend has lost something an installation already
  holds, the app MUST stop syncing that group and MUST say so plainly. The group
  MUST stay fully usable locally. Getting it back is then the user's decision:
  the app MUST offer to restore what is missing from its own copy (SY-11), and
  MUST say when a repair can only be made with the storage provider's own tools.
  Syncing resumes once the backend agrees with the installation again.

### 4.11 Privacy and Security

- **PR-1** (Core): This project MUST NOT operate any server, and the app MUST
  NOT contact any infrastructure of its authors.
- **PR-2** (Core): The app MUST send a group's data only to the backend
  configured for that group, and nowhere else.
- **PR-3** (Core): The app MUST NOT require an account with anyone. Credentials
  for a backend belong to the users' own storage, not to this product.
- **PR-4** (Core): Group content is stored on the backend unencrypted unless the
  user chooses a passphrase (PR-11). Securing and backing up the backend is the
  users' responsibility, and the app MUST say so clearly when a backend is
  configured (BE-12).
- **PR-5** (Core): The app MUST support encrypted transport (HTTPS) and MUST
  warn the user before storing data on a backend reached over plain HTTP.
- **PR-6** (Core): Backend credentials MUST be stored in the device's secure
  storage and MUST NOT appear in logs, exports or backups in plain text.
- **PR-7** (Core): The app MUST NOT contain advertising or third-party tracking.
- **PR-8** (Core): Any diagnostics/telemetry MUST be opt-in and MUST NOT contain
  group content.
- **PR-9** (Core): The user MUST be able to delete all local data.
- **PR-10** (Later): The user MUST be able to delete a group's data from its
  backend from within the app.
- **PR-11** (Core): A group MUST be able to be encrypted with a passphrase, for
  backends the users do not fully trust, such as rented storage. Everything the
  app writes to that backend is then unreadable without the passphrase, apart
  from the object names needed to order the log.
- **PR-12** (Core): A passphrase MUST be entered on every installation of that
  group, MUST NOT be stored on the backend, and MUST be recoverable from no one.
  The app MUST state that losing it makes the backend copy worthless, at the
  moment the passphrase is chosen.
- **PR-12a** (Core): The app MUST generate the passphrase itself — several words
  drawn at random from a fixed list — and present it to be written down or put
  in a password manager. It is the only thing standing between rented storage
  and the group's contents, and a generated one is the only kind whose strength
  is known.
- **PR-12b** (Core): A user who would rather choose their own MUST be allowed
  to, and MUST be told before it is accepted if it is short or otherwise easy to
  guess. This is a warning, not a refusal.
- **PR-13** (Optional): The app MUST offer an app lock (device biometrics /
  PIN).

### 4.12 Data Portability and Loss

A group survives as long as one installation or its backend still holds it.
Recovering a lost device means configuring the same backend again (BE-11). A
purely local group, however, exists only on that one device: if it is lost and
there is no backup, the group is gone.

- **DA-1** (Optional): The app MUST warn the user that a group without a backend
  and without a backup is lost together with the device.
- **DA-2** (Later): The user MUST be able to export a group's data in a text
  format (e.g. CSV, JSON).
- **DA-3** (Later): The user MUST be able to create a local backup of all groups
  and restore it on another device.
- **DA-4** (Optional): The user MUST be able to encrypt a local backup.

### 4.13 Platform and Usability

- **UX-1** (Core): The client MUST be an Android application.
- **UX-2** (Core): Adding a typical expense (description, amount, equal split
  among all members) MUST take no more than a few taps from opening the app.
- **UX-3** (Later): The app MUST respect the device's light/dark theme.
- **UX-4** (Later): Numbers MUST be formatted according to the user's locale.
- **UX-5** (Core): The app MUST support at least English and German, and MUST be
  structured for further localisation.
- **UX-6** (Optional): The app MUST meet common accessibility expectations
  (screen reader labels, scalable text, sufficient contrast).

---

## 5. Explicit Non-Goals

- **No server operated by this project**, and therefore no hosting, no
  moderation, no abuse handling and no accounts.
- No end-to-end encryption of group content by default. The backend belongs to
  the users; protecting it is their job. A passphrase is available for storage
  they trust less (PR-11).
- No backend types other than S3-compatible object storage in the first version.
- No WebDAV and no FTP, ever, as writable backends. Neither offers a dependable
  atomic create-if-absent, so neither can guarantee that an entry the user was
  told was saved actually was.
- No currencies at all — amounts are plain numbers, and there is no conversion
  between them.
- No roles, permissions or admin concept inside a group.
- No web or iOS client.
- No bank account integration, no payment execution (the app records transfers,
  it does not perform them).
- No receipt scanning / OCR.
- No social features (comments, reactions, chat).
- No push notifications; anything the user is told is produced locally.
- No "close the books" / settled-period concept.
