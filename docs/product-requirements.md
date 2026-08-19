# Neugesplitter — Product Requirements

**Status:** Draft
**Scope:** Product-level requirements (what the product does and why). Software/technical
requirements are derived from this document separately.

---

## 1. Product Vision

Neugesplitter is a cost-splitting app for Android, backed by a sync server. It lets people
track shared expenses, income and money transfers in groups, and shows who owes whom.

Three principles shape the product:

1. **No accounts.** Installing the app is enough to start. There is no registration,
   no email, no password, no phone number.
2. **The server learns nothing.** All group content is end-to-end encrypted. The server
   only relays and stores opaque data so that multiple devices can stay in sync, and it
   does not keep that data indefinitely.
3. **Full trust inside a group.** A group is a small circle of people who trust each
   other. Every member may do everything. Instead of permissions, the app offers a
   complete, immutable history so that mistakes can be understood and corrected.

## 2. Target Users and Use Cases

- Flatmates tracking rent, groceries and utilities over long periods.
- Friends on a trip splitting hotels, meals and tickets.
- Couples and families sharing a household budget.
- Any small, stable group where the same people repeatedly share costs.

## 3. Terminology

| Term | Meaning |
| --- | --- |
| **Group** | A container for a set of members and their entries (e.g. "Trip to Rome", "Flat"). |
| **Member** | A person inside a group. Members exist per group, not globally. |
| **Entry** | A single recorded event: an expense, an income or a transfer. |
| **Payer** | Member(s) who provided the money for an entry. |
| **Beneficiary** | Member(s) on whose behalf the money was spent / who receive it. |
| **Split scheme** | The rule that distributes an amount across several members — used both for the payer side and for the beneficiary side of an entry. |
| **Amount** | A plain number. The app has no notion of currencies. |
| **Balance** | The net amount a member is owed (positive) or owes (negative) within a group. |
| **Installation** | One instance of the app on one device. |
| **Identity** | The cryptographic identity an installation uses to act as a member. |
| **History** | The immutable sequence of events that produced the current state of a group. |

---

## 4. Requirements

Requirement IDs are stable. Each requirement carries a priority:

| Priority | Meaning |
| --- | --- |
| **Core** | Required for the first release. The product is not usable without it. |
| **Later** | Wanted, but deliberately deferred to a release after the first. |
| **Optional** | Nice to have. May never be built. |

Inside a requirement, `MUST`, `SHOULD` and `MAY` describe how strictly the behaviour is
prescribed once the requirement is implemented — they say nothing about *when* it is built.
A `Later` or `Optional` requirement is therefore still written as `MUST`: it may not be in
the first release, but when it is built, it works exactly as stated. `SHOULD` and `MAY` are
reserved for the few places where the implementation genuinely has a choice.

### 4.1 Identity and Names

| ID | Priority | Requirement |
| --- | --- | --- |
| ID-1 | Core | A user MUST be able to use the full app after installing it, without registering an account or providing any personal data. |
| ID-2 | Core | The app MUST create a local identity on first start, without user interaction. |
| ID-3 | Core | Member names MUST be defined per group, not globally: when creating a group, the creating user enters their own name and the names of the other members. There is no app-wide profile or display name. |
| ID-4 | Core | The same person MAY therefore appear under different names in different groups. |
| ID-5 | Core | Several app installations MUST be able to act as the same member in the same group (e.g. phone and tablet, or two partners sharing one "household" member). |

### 4.2 Groups

| ID | Priority | Requirement |
| --- | --- | --- |
| GR-1 | Core | A user MUST be able to create an unlimited number of groups. Data is stored locally, so no product-level limit applies. |
| GR-2 | Core | A group MUST have a name and MAY have a description and an icon/colour. A group has no currency — amounts are plain numbers (see 4.7). |
| GR-3 | Core | A group MUST support an unlimited number of members. |
| GR-4 | Core | A group MUST support an unlimited number of entries. |
| GR-5 | Core | A user MUST be able to add members to a group. |
| GR-6 | Core | A user MUST be able to remove members from a group. |
| GR-7 | Core | Adding or removing a member MUST NOT change any existing entry. Past entries keep their payers, beneficiaries and split results exactly as recorded. |
| GR-8 | Core | Membership changes MUST only affect new entries (e.g. a removed member is no longer offered as a default participant; a new member is). |
| GR-9 | Core | A removed member MUST remain visible in the group's history and balances as long as they have a non-zero balance or appear in any entry. |
| GR-10 | Core | Every member has full access: any member MUST be able to create, edit and delete any entry and to change the group itself. There are no roles or permissions. |
| GR-11 | Core | A user MUST be able to delete a group from their own device, with an explicit confirmation. |
| GR-12 | Later | A user MUST be able to leave a group, and MUST be warned if their balance is not settled. |
| GR-13 | Later | A user MUST be able to archive a group so it is hidden from the main list without being deleted. |

### 4.3 Invitations and Joining

| ID | Priority | Requirement |
| --- | --- | --- |
| IN-1 | Core | A user MUST be able to invite another person to a group by showing a QR code. |
| IN-2 | Core | The invited person MUST be able to join by scanning that QR code with the app. |
| IN-3 | Core | After scanning, the joining user MUST choose who they are in that group: either an existing member from the group's member list, or a newly created member. The inviter does not decide this. |
| IN-4 | Core | Choosing an existing member MUST link the joining installation to that member, so that both installations act as the same member (see ID-5). |
| IN-5 | Core | A member who lost their installation MUST be able to rejoin by being invited again and picking their existing member. This is the recovery path for a lost device, as long as at least one other installation still holds the group. |
| IN-6 | Later | The app MUST offer a shareable link/text as an alternative to scanning, for people who are not physically present. |
| IN-7 | Core | An installation that lost its connection to a group because the server retention period elapsed (see SY-8) MUST be able to return through a normal invitation. |

### 4.4 Entries

| ID | Priority | Requirement |
| --- | --- | --- |
| EN-1 | Core | A user MUST be able to create an entry of one of three types: **expense** (money paid out), **income** (money received) and **transfer** (money moved between members). |
| EN-2 | Core | Every entry MUST have a free-text description and an amount. |
| EN-3 | Core | Every entry MUST have a date, defaulting to today, which the user can change. |
| EN-4 | Core | An entry MUST record one or more payers. If several payers are selected, the amount is distributed across them according to a split scheme (see 4.6). |
| EN-5 | Core | An entry MUST record one or more beneficiaries. If several beneficiaries are selected, the amount is distributed across them according to a split scheme (see 4.6). |
| EN-6 | Core | The payer side and the beneficiary side MUST be independent: a single entry MUST support e.g. two payers splitting by absolute values and four beneficiaries splitting by shares. |
| EN-7 | Core | A transfer MUST be expressible as a payer-to-beneficiary movement without affecting the group's total spending. |
| EN-8 | Core | Income MUST affect balances in the opposite direction of an expense, using the same payer/beneficiary/split model. |
| EN-9 | Core | A user MUST be able to edit and delete any entry (see GR-10). |
| EN-10 | Later | A user MUST be able to assign a category to an entry, and MUST be able to manage the category list per group. |
| EN-11 | Later | A user MUST be able to attach a note and one or more images (e.g. a receipt photo) to an entry. Attachments are not stored on the sync server; only a reference to them is synced. |
| EN-12 | Later | The entry list MUST be searchable and filterable (by member, category, date range, type). |

### 4.5 History

| ID | Priority | Requirement |
| --- | --- | --- |
| HI-1 | Core | Every change to a group — entries, membership, group settings — MUST be recorded as an immutable event. Events are never rewritten; a correction is a new event. |
| HI-2 | Core | Members MUST be able to view the history of a group: what was changed, by which member, and when. |
| HI-3 | Core | Members MUST be able to view the history of a single entry, including its previous versions and its deletion. |
| HI-4 | Core | The current entry list MUST show who created an entry and indicate that it was modified after creation. |
| HI-5 | Later | Changes made by other members that the user has not yet seen MUST be highlighted — in the group list, in the entry list and on the affected entries. This includes new, edited and deleted entries as well as membership changes. |
| HI-6 | Core | Opening an entry or its history MUST clear the highlight for that entry. |
| HI-7 | Core | The user MUST be able to mark all outstanding changes in a group as seen with a single action ("mark all as read"). |
| HI-8 | Core | The seen/unseen state is local to an installation. Marking changes as seen on one installation MUST NOT affect other installations. |
| HI-9 | Optional | A member MUST be able to restore a deleted entry or revert an entry to an earlier version from the history. |

### 4.6 Splitting

| ID | Priority | Requirement |
| --- | --- | --- |
| SP-1 | Core | Whenever several members are involved on a side of an entry — payers or beneficiaries — the user MUST be able to choose a split scheme for that side. Both sides are configured independently. |
| SP-2 | Core | **Equal**: the amount is divided equally among the selected members. This MUST always be the default for both sides. |
| SP-3 | Core | **Shares**: each member is assigned a number of shares; the amount is divided proportionally. Percentages are not a separate scheme — they are expressed as shares. |
| SP-4 | Core | **Adjustments**: the amount is split equally, then per-member plus/minus adjustments are applied. |
| SP-5 | Core | **Absolute values**: the user enters an exact amount per member. |
| SP-6 | Core | The app MUST show, before saving, the resulting amount per member on both sides, and MUST prevent saving if the parts do not add up to the total. |
| SP-7 | Core | Rounding MUST be handled so that the sum of the parts always equals the total exactly; the distribution of the remainder MUST be deterministic and visible to the user. |
| SP-8 | Core | A group MUST be able to define the default set of participants for new entries. The default split scheme is always equal (SP-2) and is not configurable. |

### 4.7 Amount Entry

| ID | Priority | Requirement |
| --- | --- | --- |
| AM-1 | Core | Amounts are plain numbers. The app MUST NOT ask for or display a currency. |
| AM-2 | Core | Amount entry MUST work like a simple pocket calculator, offering `+`, `-`, `*` and `/`. Operations MUST be applied strictly in the order they are entered, from left to right. There is no operator precedence and there are no parentheses. Example: `12.5 + 3` yields `15.5`, then `* 2` yields `31`. |
| AM-3 | Core | The current result MUST be visible after every comitted operation. |
| AM-4 | Core | The user MUST be able to correct the last input before comitting and to clear the whole calculation. |
| AM-5 | Core | Only the resulting number is stored with the entry; the calculation itself is not kept. |
| AM-6 | Core | Meaningless input (e.g. division by zero, an unfinished operation) MUST be prevented or clearly indicated, and MUST NOT be saveable. |

### 4.8 Recurring Entries

| ID | Priority | Requirement |
| --- | --- | --- |
| RE-1 | Core | A user MUST be able to mark an entry as recurring. |
| RE-2 | Core | The recurrence interval MUST support at least: specific weekdays, a specific day of the month, and every *x* days. |
| RE-3 | Core | A recurring entry MUST be configurable as either **automatic** (created without user interaction) or **suggested**. |
| RE-4 | Core | For a suggested recurring entry, the app MUST present the pending occurrence to the user, who can **confirm**, **decline**, or **edit and confirm** it. |
| RE-5 | Core | Declining an occurrence MUST NOT stop the series. |
| RE-6 | Core | Editing a series MUST affect future occurrences only. Already created entries are never changed by editing the series. |
| RE-7 | Core | A user MUST be able to pause, resume and delete a recurring series. |
| RE-8 | Core | Exactly one occurrence MUST be created per due date, even when several installations are online. |
| RE-9 | Core | The app MUST make clear which entries came from a recurring series. |
| RE-10 | Core | Pending suggestions MUST be discoverable in the app. |
| RE-11 | Optional | A recurrence MUST support an end condition: never, on a date, or after *n* occurrences. |
| RE-12 | Optional | The app MUST notify the user about pending suggestions (see SY-10). |
| RE-13 | Optional | When the app recognises a repeating pattern in a group's entries (similar description, amount and interval), it MUST offer to turn those entries into a recurring series. The user can accept, adjust or dismiss the offer, and a dismissed pattern MUST NOT be offered again. |

### 4.9 Balances and Settlement

| ID | Priority | Requirement |
| --- | --- | --- |
| BA-1 | Core | The app MUST display the current balance of every member in a group (who is owed how much, who owes how much). |
| BA-2 | Core | The sum of all balances in a group MUST always be zero. |
| BA-3 | Core | The app MUST show a concrete list of transfers that settle the group ("A pays B 12.30"). |
| BA-4 | Core | The app MUST offer a **simplified settlement** that minimises the number of transfers needed. |
| BA-5 | Core | The user MUST be able to switch between simplified and direct (debt-preserving) settlement views. |
| BA-6 | Core | A suggested transfer MUST be convertible into a recorded transfer entry with one action. |
| BA-7 | Core | The app MUST show a personal summary ("you owe / you are owed") on the group screen. |
| BA-8 | Later | The app MUST show group statistics: total spending, spending per member, and spending per category over a period. |

### 4.10 Sync and Multi-Device

| ID | Priority | Requirement |
| --- | --- | --- |
| SY-1 | Core | All data MUST be stored locally and the app MUST be fully usable offline, including creating and editing entries. |
| SY-2 | Core | The same group on different active installations MUST be kept in sync through the server. An installation is active as long as it contacts the server at least once each 30 days. |
| SY-3 | Core | Changes made offline MUST sync automatically once connectivity returns. |
| SY-4 | Core | Concurrent edits by different installations MUST be merged without data loss and without requiring manual conflict resolution in the common case. |
| SY-5 | Core | The server MUST NOT be required for reading or entering data — only for propagating it between installations. |
| SY-6 | Core | The app MUST show sync state per group (in sync / pending changes / error, and time of last successful sync). |
| SY-7 | Core | A group MAY be used purely locally, without ever contacting the server. |
| SY-8 | Core | An installation that has not contacted the server for longer than the retention period (PR-7) MUST be treated as disconnected from that group: it keeps its local data, but no longer receives or sends updates. |
| SY-9 | Core | The app MUST tell the user when a group is disconnected and MUST explain that a new invitation is needed. Accepting a new invitation (IN-7) MUST restore the installation to the current state of the group without creating duplicate entries or a duplicate member. |
| SY-10 | Optional | The app MUST be able to notify the user about changes received from other installations and about pending recurring suggestions. Notifications MUST be produced locally on the device after decryption; the server MUST NOT be able to influence their content. |

### 4.11 Privacy and Security

| ID | Priority | Requirement |
| --- | --- | --- |
| PR-1 | Core | All group content — descriptions, amounts, member names, categories, attachments — MUST be end-to-end encrypted. The server MUST NOT be able to read it. |
| PR-2 | Core | Encryption keys MUST never leave the user's devices in a form the server can use. |
| PR-3 | Core | Group keys MUST be transported via the invitation channel (QR code), not via the server in plaintext. |
| PR-4 | Core | The server MUST NOT require or store personal identifiers (email, phone number, name). |
| PR-5 | Core | The app MUST NOT contain advertising or third-party tracking. |
| PR-6 | Core | Any diagnostics/telemetry MUST be opt-in and MUST NOT contain group content. |
| PR-7 | Core | The server MUST NOT store entries indefinitely, even encrypted. Entries belonging to a group MUST be deleted after forwarding it to all active installations (SY-2) that participate in that group, or after 30 days if that is sooner. |
| PR-8 | Core | To onboard an installation into an existing group, an app MAY upload data that is older than the retention period. The server MUST keep such an upload only for a short time, long enough for the joining installation to fetch it. |
| PR-9 | Core | The user MUST be able to delete all local data. |
| PR-10 | Later | The user MUST be able to trigger deletion of a group's data from the server. |
| PR-11 | Optional | The app MUST offer an app lock (device biometrics / PIN). |

### 4.12 Data Portability and Loss

A group survives as long as at least one installation still holds it; a user who lost their
device recovers by being invited again (IN-5). If the last installation holding a group is
lost and no backup exists, the group is lost permanently — the server cannot help, because
it does not have the keys.

| ID | Priority | Requirement |
| --- | --- | --- |
| DA-1 | Optional | The app MUST warn the user that, without a backup, losing the only device holding a group means losing that group. |
| DA-2 | Later | The user MUST be able to export a group's data in a text format (e.g. CSV, JSON). |
| DA-3 | Later | The user MUST be able to create a local backup of all groups and restore it on another device. |
| DA-4 | Optional | The user MUST be able to encrypt a local backup. |

### 4.13 Platform and Usability

| ID | Priority | Requirement |
| --- | --- | --- |
| UX-1 | Core | The client MUST be an Android application. |
| UX-2 | Core | Adding a typical expense (description, amount, equal split among all members) MUST take no more than a few taps from opening the app. |
| UX-3 | Later | The app MUST respect the device's light/dark theme. |
| UX-4 | Later | Numbers MUST be formatted according to the user's locale. |
| UX-5 | Core | The app MUST support at least English and German, and MUST be structured for further localisation. |
| UX-6 | Optional | The app MUST meet common accessibility expectations (screen reader labels, scalable text, sufficient contrast). |

---

## 5. Explicit Non-Goals

- No currencies at all — amounts are plain numbers, and there is no conversion between them.
- No roles, permissions or admin concept inside a group.
- No web or iOS client.
- No bank account integration, no payment execution (the app records transfers, it does not perform them).
- No receipt scanning / OCR.
- No attachment hosting on the sync server.
- No social features (comments, reactions, chat).
- No server-side user accounts of any kind.
- No server-side recovery of lost groups.
- No server-generated (push) notifications with content.
- No "close the books" / settled-period concept.

## 6. Decisions

Questions that have been settled, kept here so the reasoning is not lost.

| Question | Decision |
| --- | --- |
| Multiple currencies per group? | No. The product has no currency concept at all; amounts are numbers. |
| What happens when the last installation holding a group is lost? | The group is lost forever, unless a backup exists. This is an accepted consequence of end-to-end encryption without accounts. |
| How long does the server retain data for an untouched group? | 30 days without contact from any installation, then it is deleted (PR-7). |
| Unequal payers *and* unequal beneficiaries in one entry? | Yes. Both sides are configured independently (EN-6). |
| Who may edit what? | Every member may edit everything. Total trust inside the group is assumed; the immutable history (4.5) is the safeguard. |
| Percentages as a split scheme? | No. Percentages are just shares and are covered by SP-3. |
| Configurable default split scheme per group? | No. Equal is always the default; only the default participants are configurable (SP-8). |
| Mark a period as settled, so balances start fresh? | No. A group that has run its course can simply be replaced by a new group. |
| How does a member notice changes made by others? | Unseen changes are highlighted in the app (HI-5 to HI-8) and, later, surfaced as local notifications (SY-10). |
| Are attachments synced through the server? | No. Only a reference is synced; the attachment lives elsewhere (EN-11). |
| Notifications, even though the server sees no content? | Yes, generated locally on the device after decryption (SY-10). |
| What does a user see after being offline for more than 30 days? | The group is shown as disconnected and the user is told to get a new invitation. The inviter then uploads the history again, which the server holds only briefly for onboarding (SY-8, SY-9, IN-7, PR-8). |

## 7. Open Questions

*None currently open.*
