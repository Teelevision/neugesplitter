# User Interface

**Status:** Draft. What screens exist, what is on them, and how they behave —
described in words, to be handed to a designer rather than to replace one. The
behaviour each screen has to produce is in
[product-requirements.md](product-requirements.md); this document only decides
where it surfaces. Material 3, Android, light and dark (UX-3).

---

## 1. The shape of the app

There are two levels, and only one of them has a bottom bar.

**Outside a group** the group list is the root screen. There is nothing to
switch between there, so it carries no navigation bar — just a list, a FAB and
an overflow into export, import and app settings.

**Inside a group** a navigation bar carries four destinations with the new-entry
action docked in the middle:

| Position | Destination   | What it is for                                     |
| -------- | ------------- | -------------------------------------------------- |
| 1        | **Overview**  | total spending and the list of entries             |
| 2        | **People**    | members, balances, and how to settle them          |
| centre   | **New entry** | docked FAB, the most prominent thing on the screen |
| 3        | **Activity**  | history, pending suggestions, sync state           |
| 4        | **Settings**  | the group, its backend, its members' defaults      |

Two deliberate notes on that bar. Material 3 says a navigation bar holds
destinations and not actions; the centre FAB breaks that on purpose, because
UX-2 asks for a typical expense in a few taps from opening the app and the add
action is used more than the other three combined. And **Activity** is the one
destination no requirement names directly — it exists because history (HI-2),
unseen changes (HI-5, HI-7), pending recurring suggestions (RE-10) and sync
state (SY-6) all need somewhere to live, and scattering them across the other
three would hide them.

Switching destinations never leaves the group, and back — the arrow or the
system gesture — returns to Overview before it leaves for the group list.
Everything else — editors, details, setup — is pushed over the bar as a full
screen or opened as a bottom sheet, and the bar is hidden while it is up.

## 2. Screen inventory

| Screen              | Kind        | Reached from                             |
| ------------------- | ----------- | ---------------------------------------- |
| Group list          | root        | launcher                                 |
| App settings        | pushed      | group list overflow                      |
| New group           | pushed flow | group list FAB                           |
| Join group          | pushed flow | group list FAB → "join existing"         |
| Export              | pushed flow | group list overflow, group settings      |
| Import              | pushed flow | group list overflow, after a file picker |
| Group overview      | destination | opening a group                          |
| People and balances | destination | navigation bar                           |
| Activity            | destination | navigation bar                           |
| Group settings      | destination | navigation bar                           |
| Entry editor        | pushed      | centre FAB, editing an entry             |
| Amount calculator   | full sheet  | tapping the amount field                 |
| Split editor        | sheet       | tapping a side in the entry editor       |
| Entry detail        | pushed      | tapping an entry                         |
| Entry history       | pushed      | entry detail                             |
| Recurrence editor   | sheet       | the recurring switch in the entry editor |
| Member sheet        | sheet       | tapping a member                         |
| Backend setup       | pushed flow | group settings, new group, join group    |
| Passphrase setup    | pushed      | inside backend setup                     |
| Sync problem        | pushed      | the halted-sync banner                   |

## 3. Outside a group

### Group list

The root. One card or row per group, showing its emoji and colour (GR-2), its
name, the user's own position in it ("you are owed 42.50", BA-7) and, for a
shared group, the same sync icon its own top bars show, at the trailing end
(SY-6), in the error colour once its passes fail or it has halted (SY-15). A
group with unseen changes carries a dot before that icon (HI-5). A group kept
only on this device shows no icon.

- FAB opens a small menu: **new group** or **join an existing group**.
- Tap opens the group at its Overview.
- Long press selects, for the two bulk-ish actions worth having: archive (GR-13,
  later) and delete (GR-11).
- Overflow: **export**, **import** and app settings.
- Empty state: one line explaining that a group can be local or shared, and the
  two FAB choices spelled out as buttons.

### App settings

Theme (UX-3), language (UX-5), app lock (PR-13, optional), and **delete all
local data** (PR-9) as the last setting, in error colour, behind a typed
confirmation. About, licence, and the version. Below them all, the third-party
licences on a screen of their own, each opening its full licence text, the word
lists' attribution among them (NOTICE).

### New group

A short pushed flow, not a wizard with a progress bar: name, optional emoji and
colour, then the member list starting with the user's own name (ID-2). Decimal
places are fixed at two and are not asked about (AM-7, AM-8 is later). The last
step offers a backend, with "keep it on this device" as an equal option and a
one-line warning that a local group dies with the phone (BE-1, DA-1).

### Join group

Backend setup first (section 6), then the app reports what it found at that
location (BE-9): a group, nothing, or something foreign. When it is a group, the
history is fetched and the user picks who they are from the member list, or
creates a new member (BE-6, BE-11). Nothing else — there is no invitation and no
code to type. An encrypted group asks for its passphrase before anything is
fetched, and says so on the spot when it is wrong. An empty location offers to
create a new group there, with the location already filled in; a group already
on the device is offered to open, and nothing is changed.

### Export and import

**Export** turns the list into a selection with every group selected and an
export action in place of delete; the user deselects what should stay out. Group
settings offer the same for one group. A short pushed flow follows: without or
with a passphrase, as two cards; for a passphrase, the same generated one or own
one as backend setup (PR-12a, PR-12b); then the system's save dialog, offering
`neugesplitter-<date>.zip`. Nothing about a backend goes into the file, and the
flow says so (DA-3, DA-4).

**Import** opens the system's file picker first, then a pushed flow: the
passphrase if the file is encrypted, said on the spot when it is wrong; the
groups in the file with a checkbox each, all checked, and those that cannot be
read greyed out with why; then, for each group chosen in turn, who the user is
in it, exactly as when joining. Each becomes a new group that is not shared. One
group imported opens; several return to the list.

A file that is no zip is read as a Splitwise export (DA-5). In place of the
groups, the flow asks for the group's name, since the export has none, and shows
its members, entries and transfers, any rows left out, and a card saying whether
the balances match the export's totals or by how much they differ; a difference
does not stop the import, and an entry is added that corrects it, which the card
says. Who the user is follows, with a new member offered as always. A file in
several currencies is refused.

## 4. Inside a group

All four destinations show the group's emoji and name in their top bar, and the
sync state as a cloud icon at its right, before any menu: done, uploading while
changes wait, crossed out in the error colour on a failure or a halt, and absent
for a local group (SY-6).

### Overview

The default destination and the one the user sees most.

A header shows the user's own position first and large ("you owe 12.30", BA-7),
and under it the group's total spending — one number, not a dashboard;
per-member and per-category statistics are BA-8 and come later. Tapping the
header opens People, where the balances are. Under it, a shared group says in
one line how its sync stands: when a pass last succeeded, or why the last ones
failed ("Not reachable") in the error colour (SY-6, TR-12). The line opens
Activity. If suggestions are pending, a banner sits directly under the header
saying how many, and leads to Activity, where they are decided (RE-10). Until
then they are not listed and count nowhere. A group whose sync has halted shows
an error banner there instead of the line, which opens the sync problem screen
(SY-15).

Below that, entries newest first, grouped under date headers. Each row carries:

- description, and who paid, in the group's names
- the amount, right-aligned, with no currency symbol anywhere (AM-1)
- what the entry does to the user's balance, if anything, as a small signed pill
  under the amount: tinted for a gain, another tint for a loss, neutral for
  zero, and the sign carries it without colour (BA-7, UX-6)
- a small repeat glyph when the entry came from a series (RE-9)
- "edited" when it was changed after creation (HI-4)
- a dot while the change has not been seen (HI-5)

Income and transfer rows are visually distinct from expenses — a leading icon
and a sign, not colour alone (UX-6).

Interactions:

- **Tap** opens entry detail.
- **Long press** opens a context sheet: edit, duplicate, delete, history (but
  not for an occurrence, which has no versions of its own).
- **Swipe left** moves the row aside and shows a delete button, and only tapping
  that deletes, so that a sloppy scroll cannot. Deleting offers an undo
  snackbar. The deletion event is not written until the snackbar goes, so undo
  leaves no trace in the history (HI-1).
- Swipe right is unassigned on purpose. One swipe means one thing.
- Empty state: "No entries yet" and an arrow toward the centre FAB.

### People and balances

One screen for the three things that are really the same thing: who is in the
group, what each of them is owed, and what to do about it.

**Balances.** Every member with their balance, sorted with the largest creditor
first, owed and owing clearly separated (BA-1). The sum is always zero, and the
screen may state it (BA-2). Removed members stay listed while they still have a
balance or appear in an entry, visibly greyed (GR-9).

**Settling.** Under the balances, the transfers that settle the group, phrased
as sentences: "Anna pays Ben 12.30" (BA-3), as few of them as possible (BA-4).
Each suggested transfer has a **Record** button that opens the entry editor
pre-filled as a transfer, so confirming is one further tap (BA-6).

**Managing members.** A member row is tapped to open the member sheet:

- rename (the name is per group, ID-2)
- remove, only from inside the sheet, behind a confirmation that says plainly
  that removing does not take away access to the backend and that their past
  entries are untouched (GR-6, GR-7, BE-13)

Long press starts an inline rename, because renaming a typo is the frequent
case. **Members are not swipeable.** Removing somebody is neither undoable in
the way deleting an entry is, nor the thing it looks like — BE-13 needs a
sentence of explanation, and a gesture cannot give one.

An "add member" row sits at the end of the list (GR-5).

### Activity

How sync stands comes first, because the history can grow long, and the rest is
stacked in collapsible sections below it:

1. **Sync**: a rounded card like the Overview header, coloured by how sync
   stands — primary while it works, error once passes fail or it has halted,
   neutral for a group kept only here. It holds the time of the last
   _successful_ sync (SY-6, TR-12), why the last passes failed if they did, what
   is queued, and a manual sync button (SY-9). When sync has halted, it says so
   instead of offering the button, and leads to the sync problem screen. A group
   kept only on this device says so here and offers to share it (BE-1).
2. **Pending suggestions** (RE-4, RE-10), shown only while there are some,
   oldest first: each with confirm, edit, or decline. Edit opens the entry
   editor, and saving there confirms it, as it was or changed. Declining does
   not stop the series and the section says so once (RE-5).
3. **History**: what changed, by which member, and when (HI-2), as a reverse
   chronological list covering entries, series, membership and group settings,
   under a heading for each day like the entry list. Each line is the size of an
   entry row whatever it says: an icon for its kind, who did what, and below it
   what changed — "Amount changed to 15.00 (previous 12.00)", several joined by
   "·" — cut off with an ellipsis where it does not fit, and the time of day at
   the end. What one action wrote at once is one line: settings changed
   together, or an entry made recurring rather than "deleted" and "changed a
   recurring entry". A line about an entry opens it, or its history once it is
   deleted (HI-3). Unseen items are highlighted; tapping one clears it, and
   opening an entry clears every line about it (HI-5, HI-6). The overflow holds
   **mark all as read** (HI-7).

The navigation bar's Activity icon carries a badge while anything above is
pending.

### Group settings

Plain list, grouped:

- **Group**: name, description, emoji, colour (GR-2). A chosen emoji or colour
  shows at once and is written when the user leaves the screen or the app, so
  trying several writes one change.
- **Entries**: default participants for new entries (SP-8)
- **Backend**: current backend summary, sync interval (SY-13), edit or configure
  (BE-2, BE-15), and the encryption state (PR-11). A local group shows one row
  saying that it is lost with the device, which starts backend setup.
- **Export**: the group to a file (DA-3, section 3)
- **Danger**: leave group (GR-12, later, warning when unsettled), delete group
  from this device (GR-11, confirmation)

The interval control states what it is: the shortest wait between polls, not a
promise, and that polling costs requests the users pay for (SY-13, TR-10).

## 5. Creating and editing an entry

### Entry editor

One screen for all three kinds (EN-1), pushed full-screen with a close button
and a save action. Top to bottom:

- **Kind** as a three-way segmented control: expense, income, transfer.
- **Description** (EN-2), focused on open so the keyboard is already up.
- **Amount**, displayed large. Tapping it opens the calculator sheet rather than
  the system keyboard (AM-2).
- **Date**, defaulting to today, one tap to a date picker (EN-3).
- **Paid by** — a row of member chips, with the group's default participants
  preselected. Selecting more than one reveals the split scheme (EN-4, SP-1).
- **For** — the same control for beneficiaries, independent of the payer side
  (EN-5, EN-6).
- **Repeats** — a switch that opens the recurrence sheet (RE-1).
- Note and attachments, when EN-11 arrives.

When more than one member is on a side, the per-member result is shown under
that side as the user types, both sides at once (SP-6). Save is disabled, with
the reason stated inline, when absolute values do not sum to the total or
adjustments add up to more than the total (SP-6), or when the amount is missing
or meaningless (AM-6).

A transfer relabels the two sides as "from" and "to" and hides the split schemes
unless more than one member is selected (EN-7).

With Repeats on, the entry is the first occurrence of a new series starting on
its date; a suggested one is confirmed at once, since someone just entered it
(RE-1, RE-3). That holds on a date the rule does not fall on, too — rent entered
on the 5th that repeats on the 2nd — and then the editor says when the next one
is, since the rule alone would suggest a different first date. Switching it on
for an existing entry turns that entry into the series' first occurrence.
Editing an occurrence asks on save whether the change is for **only this one**
or **this one and the following** (RE-6); a change to the rule itself always
goes for the following. Its date goes for this one only, so a change to the date
alone does not ask, and where the question comes with a moved date it says so.
Switching Repeats off on an occurrence stops the series after it (RE-7).

Under the date, an occurrence moved off its due date names that date, and one
due on a date its rule does not, or no longer, fall on says so. A rule changed
on an occurrence makes it the first of the new rule, from its date on, and reads
"from this one on" with the next date. Its old due date goes, confirmed or not,
unless the new rule falls on it too; old dates between the two are skipped in a
pause, which it names. It cannot go back past an earlier confirmed or edited
occurrence. Wherever a save changes the series — a changed rule, **this one and
the following**, switching Repeats off — the editor says beforehand how many
entries already in the balances it removes, adds or changes.

Once somebody has confirmed or edited a later occurrence of the series, it can
no longer change from this one, as that would rewrite their decision (RE-6): the
rule under Repeats does not open the sheet, **this one and the following** is
greyed out, and both say why. Repeats can still be switched off, and on again it
repeats as it did.

### Amount calculator

A full-width sheet that replaces the keyboard: digits, decimal separator, `+`,
`-`, `*`, `/`, a backspace and a clear (AM-4). No parentheses and no precedence
— each operator commits the expression so far, and the running result stays
visible above the keypad (AM-2, AM-3). An operator typed first works on the zero
shown, so a minus starts a negative number, as an adjustment may need (SP-4). An
amount already in the entry stands marked like selected text: a digit replaces
it, an operator carries on from it, and backspace edits it (AM-4). Confirming
leaves out an operator still waiting for its number, rounds to the group's two
places and writes the number into the entry; the calculation itself is not kept
(AM-5, AM-6, AM-7).

### Split editor

A sheet for one side, opened by tapping that side's scheme. Four choices as
tabs: **equal** (the default, SP-2), **shares** (SP-3), **adjustments** (SP-4),
**absolute** (SP-5). Below the tabs, one row per selected member with the input
that scheme needs and the resulting amount, plus a running total that shows how
far off the inputs are. Percentages are not offered; they are shares (SP-3).
Under absolute, members left blank share equally what the values typed for the
others leave of the total.

### Recurrence editor

A sheet: start date (for a new series only; an occurrence's due date is fixed),
then the pattern as one of **every _x_ days**, **weekdays** (multi-select),
**days of the month** (multi-select) (RE-2), then **automatic** or **suggested**
(RE-3). A sentence at the bottom reads the rule back in plain language — "every
month on the 1st and 15th" — and the same sentence stands under the Repeats
switch once the sheet is closed. Where a new series' start date is not one the
pattern falls on, a line below it says that this entry is the first and when the
next one is. Days after the 28th note that shorter months use their last day.
Times of day appear nowhere (RE-14).

### Entry detail

What the entry is, who paid, who benefited, and the per-member amounts as
derived, plus who created it and whether it has been edited (HI-4). Actions:
edit, duplicate, delete, and **history**, which lists previous versions and the
deletion if there is one (HI-3), newest first under their days, in lines like
Activity's history; tapping one shows all of it: each changed field on its own
line, or the entry as it was recorded. Opening this screen clears the entry's
unseen highlight (HI-6). An entry from a series says how the series repeats and
links to it (RE-9), and under its date, like the editor, whether it was moved
off its due date or is due off the series' dates; it has no history of its own,
and deleting it deletes only that occurrence (RE-15).

### Recurring series

What the series produces on its next due date, or else as it stands today or on
its first day while that is ahead, so that a change made ahead shows at once:
description, who pays, the amount, the rule read back with automatic or
suggested, and whether it runs, starts later, is paused until a date, pauses or
stops ahead, or stopped (RE-7, RE-9). Below that its actions:

- **Change** opens the entry editor on the series from a date on, by default its
  next due date and at the earliest the day after its last confirmed or edited
  occurrence, without adding an entry of its own: next year's rent, known now
  (RE-6). It says when it is next due and what it does to the entries already in
  the balances.
- **Continue**, while a pause or stop is in force or ahead, goes on from a date
  within it, by default the next due date from today or its first day on, so
  that the date it offers is the one the series comes back with. What fell due
  before that date is not made up; a pause still ends when it would have.
- **Pause** takes a first day and a day it continues, by default today and the
  day after the next occurrence, so that it skips one.
- **Stop** takes a first day, by default today and at the earliest the day after
  its first occurrence; before that, deleting it is what is meant.
- **Delete** asks first, and says that every entry it produced goes with it and
  that stopping keeps them.

Change, pause and stop are hidden once it is stopped for good. Each dialog says
how many entries already in the balances it would remove or add; confirmed and
edited ones stand either way (format-spec 4.2). Pauses and stops that overlap or
meet become one.

Under the actions, every pause and stop, past ones included, each with
**Remove**, which asks first and says how many entries it held back come back;
removing takes it back as a whole. Below them, every due date so far, newest
first under its day: the entry it produced, opening its detail, or else why
there is none — a suggestion still waiting in Activity, or one declined or
deleted (RE-4, RE-5) — and, on the day it was done, what somebody did to the
series or its occurrences, in lines like Activity's history (HI-2). The header
says when it is next due, past its stops, so that a missing entry is never a
mystery.

## 6. Backend setup

A pushed flow, used identically when creating a group, joining one, or changing
a backend later (BE-2, BE-15).

1. **Location**: endpoint, bucket, optional prefix, access key, secret, and the
   addressing options a provider needs. The whole set can be pasted as one blob,
   because seven fields on a phone is the first thing every new member does. A
   bare host means HTTPS, and a missing region means `us-east-1`.
2. **Test**: reachability, credentials, and the conditional-write check, each
   reported as its own line in plain language. A backend that fails the
   conditional-write check is refused, with an explanation of what would go
   wrong, not an error code (BE-7). A read-only backend is accepted with a
   standing note that local changes cannot be uploaded (BE-8); its third line
   says the check was not needed rather than claiming it passed.
3. **What is there**: nothing, this group, a newer state of this group — which
   simply syncs (BE-10) — an older one, shown as a warning that syncing will
   stop until what is missing is put back (SY-15), or foreign data, which is
   refused (BE-9). Nothing is refused too when the group cannot be created
   there: with read access only, or when the group is shared already and this
   would be a move (BE-15). A location that turns out foreign only when
   connecting lands back on this step.
4. **Encryption**: off, or a passphrase (PR-11). Choosing it opens passphrase
   setup. A location that holds this group skips the step, since the group's
   encryption was decided where it was first shared.
5. **Acknowledgement**: one screen, not a checkbox buried in settings, saying
   that everyone who can reach this location can read and change the group, and
   that securing and backing it up is the users' job (BE-12, PR-4). Plain HTTP
   adds a second, stronger warning that describes the consequence — the
   credential is readable by anyone on the network — rather than the protocol
   (PR-5).

The pasted blob is one `key: value` or `key = value` per line, with an optional
`export` and quotes, so that an `.env` file, an rclone section, the AWS
variables or the app's own field names all work. Unknown lines are skipped, the
first mention of a key wins, and a paste that names nothing says so rather than
clearing the fields.

### Passphrase setup

The app generates one and shows it, with a copy button and an explicit "I have
written this down" step (PR-12a). A secondary path allows the user to type their
own, and warns — without refusing — when it is weak (PR-12b). Both paths end on
the same sentence, shown at the moment of choosing and nowhere else: lose this
and the backend copy is worthless to everyone, including us (PR-12).

### Sync problem

Reached from the halted-sync banner under the Overview header, and from the
halted card in Activity (SY-15). It states what happened in one sentence — the
backend is missing something this device already has, or holds something that
cannot be applied — confirms that the group is still fully usable here, and
names the object it stopped at. The repair it names is the storage provider's
own tools, or for an object a newer version wrote, an update. **Check again**
runs a pass; once the backend agrees, the screen says that syncing resumed.
**Restore from this device** (SY-11) joins it when restoring is built. Nothing
on this screen happens automatically.

## 7. Gestures, in one place

| Gesture         | Where              | Does                                            |
| --------------- | ------------------ | ----------------------------------------------- |
| Tap             | entry row          | open detail                                     |
| Long press      | entry row          | context sheet: edit, duplicate, delete, history |
| Swipe left      | entry row          | show delete button; it deletes, with undo       |
| Tap             | member row         | member sheet                                    |
| Long press      | member row         | inline rename                                   |
| Tap             | group row          | open group                                      |
| Long press      | group row          | selection: archive, delete                      |
| Pull to refresh | Overview, Activity | manual sync (SY-9)                              |

Destructive actions are either undoable by snackbar or confirmed by dialogue,
never both and never neither. The three that confirm are: delete group (GR-11),
delete all local data (PR-9) and remove member (GR-6).

## 8. Colour

Six ramps of ten steps each, numbered 50 (lightest) to 900 with the base colour
at 500. Primary, Secondary and Accent are the brand; Neutral carries outlines
and secondary text; Light and Dark are the surface families for the two themes.

| Ramp      | 50        | 100       | 200       | 300       | 400       | **500**   | 600       | 700       | 800       | 900       |
| --------- | --------- | --------- | --------- | --------- | --------- | --------- | --------- | --------- | --------- | --------- |
| Primary   | `#EDF1EA` | `#C7D2BE` | `#ABBD9F` | `#859F73` | `#6D8C58` | `#496F2E` | `#42652A` | `#344F21` | `#283D19` | `#1F2F13` |
| Secondary | `#EDF1F1` | `#C7D3D2` | `#ACBEBD` | `#86A09F` | `#6F8D8C` | `#4B716F` | `#446765` | `#35504F` | `#293E3D` | `#202F2F` |
| Accent    | `#F3F7EA` | `#D9E5BD` | `#C6D99D` | `#ADC770` | `#9DBD54` | `#84AC29` | `#789D25` | `#5E7A1D` | `#495F17` | `#374811` |
| Neutral   | `#F7F9FA` | `#E7ECEF` | `#DBE3E7` | `#CBD6DB` | `#C1CED5` | `#B1C2CA` | `#A1B1B8` | `#7E8A8F` | `#616B6F` | `#4A5155` |
| Light     | `#FBFDFE` | `#F4F8FA` | `#EEF4F8` | `#E7F0F5` | `#E2EDF3` | `#DBE8F0` | `#C7D3DA` | `#9BA5AA` | `#788084` | `#5C6165` |
| Dark      | `#E8E8E7` | `#B6B7B6` | `#939492` | `#626361` | `#434542` | `#141613` | `#121411` | `#0E100D` | `#0B0C0A` | `#080908` |

This is an even ramp rather than a Material tonal palette, so the roles are
mapped by hand rather than generated from a seed. Contrast ratios below are
against the paired `on` colour.

**Light theme**

| Role                      | Ramp step     | Hex       | Contrast |
| ------------------------- | ------------- | --------- | -------- |
| `primary`                 | Primary 500   | `#496F2E` | 5.7      |
| `onPrimary`               | Light 50      | `#FBFDFE` |          |
| `primaryContainer`        | Primary 100   | `#C7D2BE` | 9.1      |
| `onPrimaryContainer`      | Primary 900   | `#1F2F13` |          |
| `secondary`               | Secondary 500 | `#4B716F` | 5.3      |
| `onSecondary`             | Light 50      | `#FBFDFE` |          |
| `secondaryContainer`      | Secondary 100 | `#C7D3D2` | 9.1      |
| `onSecondaryContainer`    | Secondary 900 | `#202F2F` |          |
| `tertiary`                | Accent 500    | `#84AC29` | 6.9      |
| `onTertiary`              | Dark 500      | `#141613` |          |
| `tertiaryContainer`       | Accent 100    | `#D9E5BD` | 7.6      |
| `onTertiaryContainer`     | Accent 900    | `#374811` |          |
| `surface`                 | Light 50      | `#FBFDFE` | 17.8     |
| `onSurface`               | Dark 500      | `#141613` |          |
| `surfaceContainerLow`     | Light 100     | `#F4F8FA` |          |
| `surfaceContainer`        | Light 200     | `#EEF4F8` |          |
| `surfaceContainerHigh`    | Light 300     | `#E7F0F5` |          |
| `surfaceContainerHighest` | Light 500     | `#DBE8F0` |          |
| `onSurfaceVariant`        | Neutral 900   | `#4A5155` | 7.9      |
| `outline`                 | Neutral 700   | `#7E8A8F` | 3.5      |
| `outlineVariant`          | Neutral 300   | `#CBD6DB` |          |

**Dark theme**

| Role                   | Ramp step     | Hex       | Contrast |
| ---------------------- | ------------- | --------- | -------- |
| `primary`              | Primary 200   | `#ABBD9F` | 9.1      |
| `onPrimary`            | Dark 500      | `#141613` |          |
| `primaryContainer`     | Primary 700   | `#344F21` | 5.9      |
| `onPrimaryContainer`   | Primary 100   | `#C7D2BE` |          |
| `secondary`            | Secondary 200 | `#ACBEBD` | 9.4      |
| `secondaryContainer`   | Secondary 700 | `#35504F` | 5.7      |
| `onSecondaryContainer` | Secondary 100 | `#C7D3D2` |          |
| `tertiary`             | Accent 300    | `#ADC770` | 9.7      |
| `tertiaryContainer`    | Accent 800    | `#495F17` | 5.4      |
| `onTertiaryContainer`  | Accent 100    | `#D9E5BD` |          |
| `surface`              | Dark 500      | `#141613` | 14.8     |
| `onSurface`            | Dark 50       | `#E8E8E7` |          |
| `surfaceContainerLow`  | interpolated  | `#1B1D1A` |          |
| `surfaceContainer`     | interpolated  | `#1E201D` |          |
| `surfaceContainerHigh` | interpolated  | `#242623` |          |
| `onSurfaceVariant`     | Neutral 400   | `#C1CED5` | 11.3     |
| `outline`              | Neutral 600   | `#A1B1B8` | 8.2      |

The full role set for both modes, including the ones not worth tabulating here,
is in `design/tokens/` as importable variables.

Three things the palette did not supply, now decided:

- **The dark surface ramp was missing its middle.** Dark 500 to Dark 400 jumps
  from `#141613` to `#434542`, so the elevated surfaces are interpolated between
  them: `#1B1D1A`, `#1E201D`, `#242623`, `#2B2D29`.
- **There is no error colour in the palette**, so Material's default error
  family is used unchanged. Red is the only hue in the app that means something
  is wrong, and it must not be borrowed from the brand.
- **Accent never carries white text.** Accent 500 against white is 2.65:1.
  Wherever accent is a fill, the text on it is Dark 500.

### What each colour means

- **Primary** is the FAB and the selected navigation destination, and nothing
  else competes with it.
- **Accent** means money in credit — a positive balance, a settled group.

- **Secondary** means money owed. Both are always accompanied by a sign and a
  label, so colour is the second signal and never the only one (UX-6).
- **Error** is for faults only: a halted sync (SY-15), a refused backend (BE-7),
  a warning before plain HTTP (PR-5). Owing money is not an error.
- A **group's own colour** (GR-2) is offered from a set of six first, and any
  other colour can be mixed by hue, saturation and brightness. It tints that
  group's avatar and its overview header, and nothing else — otherwise the app
  has as many themes as the user has groups.

**The six group colours are deliberately off-palette.** Drawing them from the
brand ramps produced three greens, a teal and two greys, which is exactly the
one job they cannot do: the point of a group colour is that the user picks their
groups apart at a glance in the list (GR-2), and six variations on green do not
separate at avatar size. They are therefore six distinct hues, evenly spaced and
all at roughly the same lightness, so no group looks more important than
another: `#4C7A3F` Grün, `#2A6E6B` Petrol, `#3A6EA5` Blau, `#6A55A0` Violett,
`#9C4270` Beere, `#9A5F1E` Ocker. Each carries white text at 5:1 or better and
each clears 2.9:1 against both surfaces, which is enough for a filled shape that
never has to be read. Ocker is kept clearly brown-orange rather than red so that
it cannot be mistaken for the error colour, and none of the six is ever used for
anything but a group's own avatar and header — they carry identity, never
meaning, and the emoji and name are always present beside them (UX-6). On a
colour the user mixed, text is white or black, whichever contrasts more; one of
the two always reaches 4.5:1, which no tinted ink does on every colour.

**The navigation bar's selected destination is marked by hue, not by
brightness.** Material 3 would fill the indicator pill with `secondaryContainer`
and darken the icon and label to `onSecondaryContainer`, which works on a tonal
palette because those two steps are far apart. On this even ramp they are not:
`secondaryContainer` sits at 1.5:1 against the surface and the selected label is
only 1.72:1 away from the unselected one, so the whole indicator reads as a
slightly grubby grey and the user cannot tell which destination they are on. The
pill is therefore filled with `primaryContainer` and the selected icon and label
are `primary`, leaving the unselected ones `onSurfaceVariant`. Green against
grey is unmistakable at a glance even though the two are almost equally bright.
Because that difference is one of hue alone it disappears in greyscale, so the
destination labels are always shown and never reduced to icons (UX-6).

## 9. Type and small things

- Amounts use **tabular figures** so columns align, always two decimals (AM-7),
  formatted by locale (UX-4), and never with a currency symbol (AM-1).
- Member names appear at whatever length people typed them; rows truncate names
  before they truncate amounts.
- German strings run roughly a third longer than English, and every label has to
  survive that without reflowing the layout (UX-5).
- From about 120% text (UX-6), rows put the name above the amount instead of
  cutting it, segmented buttons become a wrapping row of chips, the split tabs
  scroll, and the navigation labels shrink before they would clip, never below
  four fifths of their size at 100%. A split member with an input has its name
  on its own line at any size.

## 10. Not in the first version

Search and filtering (EN-12), categories (EN-10), attachments (EN-11), charts
and per-member statistics (BA-8), and export (DA-2) are all later, and none of
them gets a placeholder in the navigation. There is no onboarding carousel, no
avatar or photo anywhere — there are no profiles to put one on (ID-2) — and no
empty dashboard.
