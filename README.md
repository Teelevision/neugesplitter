# Neugesplitter

A cost-splitting app for Android with a sync server — without user accounts, and without
the server ever seeing your data.

## What it is

Neugesplitter keeps track of shared money in small groups: flatmates, trips, families,
couples. You record what was paid, by whom, and for whom; the app tells you who owes whom.

- **Groups** — create as many as you like. Everything is stored on your device.
- **Entries** — expenses, income and transfers, each with a description, an amount, one or
  more payers and one or more beneficiaries.
- **Splitting** — equally, by shares, by adjustments, or by exact amounts. The payer side
  and the beneficiary side are configured independently, so two people can pay unequally
  for four people who split unequally.
- **A calculator built into the amount field** — type `12.5 + 3`, get `15.5`, then `* 2` for
  `31`. Like a pocket calculator, left to right.
- **Recurring entries** — by weekday, by day of month, or every *x* days. Either created
  automatically or offered as a suggestion you confirm, edit or decline.
- **Balances** — see what everyone owes, and get a simplified list of transfers that settles
  the group with as few payments as possible.
- **Full history** — every change is an immutable event you can look back at.

## What makes it different

**No accounts.** Install the app and start. No email, no password, no phone number, no
registration. You invite others to a group by showing them a QR code.

**The server knows nothing.** Group content is end-to-end encrypted on your device. The
server exists only to move encrypted data between the installations that belong to a group,
so everyone sees the same state. It cannot read descriptions, amounts, or names — and it
drops a group's data after 30 days without contact.

**Offline first.** The app works fully without a network connection. Changes sync when
connectivity returns.

**Names belong to the group.** When you create a group, you type in your own name and the
names of the others. There is no profile — you can be "Marius" in one group and "Papa" in
another. Several installations can act as the same member, so a phone and a tablet, or two
people sharing a household, can manage one member together.

**Trust instead of permissions.** Everyone in a group can edit everything. Groups are small
circles of people who trust each other, so there are no roles and no admins — just a
complete history when something needs to be traced.

**No currencies.** Amounts are plain numbers. Whatever you and your group count in, the app
does not need to know.

## Status

Early. Nothing is implemented yet. The current artefact is the product requirements
document.

## Documentation

- [Product requirements](docs/product-requirements.md) — what the product does and why.

## Repository layout

```
docs/     product and design documentation
```

(Android client and server will be added as separate top-level modules.)
