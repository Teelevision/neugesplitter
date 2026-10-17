# Neugesplitter

A cost-splitting app for Android — without user accounts, and without a server
of ours. Groups sync through storage you provide yourself: a bucket in any
S3-compatible object storage, rented or running on your own hardware.

## What it is

Neugesplitter keeps track of shared money in small groups: flatmates, trips,
families, couples. You record what was paid, by whom, and for whom; the app
tells you who owes whom.

- **No accounts.** Install the app and start.
- **Offline first.** A network connection is only ever needed to sync.
- **Sync via your own backend.** Share a group through any S3-compatible
  storage. It can be sealed with a passphrase that nobody, including us, can
  recover.
- **Entries.** Expenses, income and transfers, split equally, by shares, by
  adjustments or by exact amounts, with multiple payers and beneficiaries.
- **A calculator for entering amounts.**
- **Recurring entries.** Created automatically or offered as a suggestion you
  confirm, edit or decline.
- **Full history.** Every change is an immutable event. Everyone in a group may
  edit everything; the history is the safeguard, not permissions.
- **No currencies.** Amounts are plain numbers.

## Status

The Android client and its supporting Kotlin modules are implemented and under
active development. The app currently supports offline group and entry
management, split schemes and balances, recurring entries, activity and entry
history, and a shared group to-do list. Groups can sync through S3-compatible
storage, optionally encrypted; the app also includes backend setup, joining,
notifications for received changes, and data import and export. Recent work has
added entry search and filters, pasting amounts, and creating an entry from
selected text.

To build the debug app, run `./gradlew assembleDebug`. Run the JVM and app tests
with `./gradlew test`; the S3 integration tests use Docker and SeaweedFS via
`make integration`.

- [Product requirements](docs/product-requirements.md) — what the product does.
- [Technical requirements](docs/technical-requirements.md) — what the software
  must be to do it.
- [Decisions](docs/decisions.md) — settled questions, and the ones still open.
- [Sync design](docs/sync-design.md) — how groups sync through an S3-compatible
  bucket.
- [Event model](docs/event-model.md) — what a group is made of.
- [Format specification](docs/format-spec.md) — what is actually written to a
  backend.
- [Threat model](docs/threat-model.md) — what is protected, and what is not.
- [User interface](docs/ui-design.md) — what screens exist and how they behave.
- [Architecture](docs/architecture.md) — where the code lives.
- [Development](docs/development.md) — how work on this repository is done.
- [Implementation plan](docs/implementation-plan.md) — the order the work
  happens in.
