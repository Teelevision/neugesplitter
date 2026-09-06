# Sync Design — S3-Compatible Backends

**Status:** Draft. Design thinking, not a specification. Terminology follows
[product-requirements.md](product-requirements.md), section 4.3 (Backends and
Sharing) and 4.10 (Sync and Multi-Device).

---

## 1. Idea

The app has no server. A group that wants to be shared is given a **backend**: a
prefix in an S3-compatible bucket the users provide. Every installation of that
group reads and writes there.

This works because the group history is already an **append-only log of
immutable events** (HI-1). A log needs no logic on the storage side: writing
adds an object, reading lists the prefix and fetches what is new. Dumb storage
is enough — provided it can do one thing atomically, which is the subject of
section 4.

What it buys, compared to running a server: no accounts, no abuse handling, no
legal exposure, no hosting bill, no uptime obligation. What it costs: the users
have to bring storage, everything is polled rather than pushed, and every poll
is a request somebody pays for (section 8).

## 2. Everything is an event

The group has no state on the backend other than its events. Creating the group,
renaming it, adding a member, recording an entry, editing it, deleting it, an
installation declaring which member it acts as — each is one event, appended
once and never changed. The first event of any group is its creation, which is
why object zero doubles as the location's identity (section 5).

Nothing on the backend is mutable: there is no settings document and no
per-member file. Such a document would have to be read, edited and written back,
which is exactly what two installations cannot do safely at once. Keeping the
group's name in an event instead means renaming needs no more coordination than
adding an expense.

State — name, members, entries, balances — is what replaying the log locally
produces. The backend computes nothing.

## 3. Layout on the backend

```
<bucket>/<prefix>/
  log/
    0000000            the root object; also identifies the location
    0000134            one object, one batch of events, written once
  attachments/
    <attachment-id>    opaque blobs, referenced by events
```

Object numbers are seven lowercase hex digits, so a lexicographic listing is
chronological — which matters, because lexicographic is the order
`ListObjectsV2` returns keys in. Nothing is ever edited and nothing is ever
deleted, so the group's credential needs no delete permission at all (section
10).

**An object is a JSON document, or an age file containing one.** There is no
wrapper of ours — no magic bytes, no header, no nonce, no length prefix. A group
is a directory of files that `jq` or `age` can read on their own, which is a
hedge against this app disappearing as much as a convenience. `log/0000000` says
which of the two a location holds: an encrypted group's is itself an age file,
holding the key the rest is encrypted to (section 9). That is all the structure
there is.

## 4. Writing

Objects are numbered consecutively, and the number _is_ the order. An
installation holding everything up to _n_ writes _n+1_ with `If-None-Match: *`.
Success means the number is his; a `412` means somebody else got there first, so
he fetches that object, applies it, and tries again at _n+2_. Writing therefore
requires being caught up, which costs nothing, because a write is preceded by a
sync pass anyway.

The conditional write is the whole design. Amazon guarantees that of several
conditional writes to the same key, _"the first write operation to finish
succeeds"_ and the rest get a `412`; MinIO holds a namespace lock across the
check and the write; SeaweedFS documents `If-None-Match: *` as a
compare-and-create primitive intended for exactly this. It is one request, and
it either happened or it did not. It is also the _only_ thing this design needs
from a backend — an atomic create-if-absent over a numbered key space — which is
what makes further backend types (BE-14) a matter of writing an adapter rather
than rethinking anything.

**Not every S3-compatible server implements it.** Garage does not document
conditional writes at all. The backend test (BE-7) must therefore write a probe
key twice and require a `412`, and refuse the backend if it does not get one. A
server that fails that test is not supported in a degraded mode: silently losing
an entry is not a degraded mode.

Two things follow from numbering. **Order is fixed at write time**, identically
for everyone, so the timestamps recorded inside events stay a record and never
become a tiebreaker. And **a gap is a real fault**: missing numbers are
detectable and unambiguous, where a missing key among unrelated names would not
be.

An object carries a **batch** of events rather than one: an installation
returning from offline appends its whole queue as a single object. That keeps
the number of objects proportional to sync passes rather than to entries, which
matters for both cost and cold start (section 8).

**An interrupted write resolves itself.** If the response to a write is lost,
the installation does not know whether it landed. It retries the same number
with the same body; a `412` is ambiguous, so it fetches the object and reads the
**batch id** inside. Its own batch id means the write did land and there is
nothing to do. A different one means somebody else took the number, so it
applies that object and retries at the next. Either way the events are written
exactly once — without a deduplication window, without a clock, and without ever
having to establish what happened.

Losing a number means rebuilding the object, because its chain link (section 9)
names a predecessor that has just changed. The batch id does not change with it:
the events are the same intent, and it is the batch id that has to stay
recognisable across exactly this retry.

## 5. Reading

A sync pass is:

1.  `ListObjectsV2` under `log/`, with `StartAfter` set to the number _below_
    the highest one already held;
2.  `GET` the keys it returns;
3.  apply their events;
4.  write anything local the backend does not have yet (section 4).

Starting one number low costs nothing and is what makes truncation visible: an
up-to-date installation expects the listing to return exactly the object it
already has, and a listing that comes back short means the backend has gone
backwards. That stops the sync there (SY-15) rather than looking like a quiet
morning — the group carries on locally, and section 7 is how it is put right.

The listing is bounded by what is new, not by the size of the history: an
installation that is up to date lists once and gets one key back, whether the
group holds a hundred objects or a hundred thousand. Only a fresh installation
reads everything (BE-11, SY-7) — thousands of objects at most for a household
group, fetched in parallel, once ever.

Names are matched strictly — seven hex digits under `log/` — and anything else
under the prefix is ignored. The app reads what it recognises and does not touch
the rest.

Events carry ids, and when the same id appears in more than one object, **the
occurrence at the highest number wins**; earlier ones are superseded, not
merged. That is the only conflict rule in the design, and it is deliberate that
there is exactly one. An id identifies an _intent_, and the last statement of an
intent is the group's answer. Retries never reach this rule, because a retry is
settled by the batch id first (section 4).

Identifying what a location holds (BE-9, BE-10) is one `GET` of `log/0000000`.
No object at all means an empty location. Anything that is neither our JSON nor
an age file means somebody else's data, and the app refuses to write. Otherwise
it is either the first batch, carrying the group-created event, or the group's
encrypted private key — in which case one passphrase attempt against it answers
the second question too, and the group id follows from the object after it.

Each object also carries the chain link of the one before it (section 9),
verified as it is applied. Order therefore does not rest on the object names,
which nothing authenticates: the names say what to fetch, and the chain says
whether it is the same history everyone else has.

## 6. Concurrent intent

A total order does not remove concurrent intent: two members can edit the same
entry before either sees the other. The later number wins, the history keeps
both (HI-3), and every installation reaches the same result. Entries are
independent facts, each carries its own entry date chosen by the user (EN-3),
and balances are a sum — so ordering only ever decides same-entry conflicts.

Recurring entries stay out of the log almost entirely, because **occurrences are
not stored**: the series definition is there, a due date follows from it by
calculation, and a series nobody touches produces no events at all. Events
appear only where a person departs from the calculation — confirming a
suggestion (RE-4), declining one (RE-5), editing an occurrence. Those have a
natural single author, and where two people act at once the event id is
**derived from the series id and the due date**, so the two collapse into one
and the higher-numbered object wins (section 5). RE-8 then holds by construction
rather than by agreement.

One consequence to accept: because a due date is evaluated locally, an
installation that has passed midnight shows this month's rent while another has
not. A recurrence is made of calendar dates and never of instants, so nothing
diverges except when each member first sees it.

## 7. Joining, rejoining and recovery

There is no invitation. Sharing a group means passing on the backend settings,
and for an encrypted group the passphrase as well (section 9). The app then
replays the history it finds and asks _who are you?_ (BE-6) — an existing
member, or a new one. That answer stays on the device and nothing is written
about it (event-model section 5).

The same path recovers a lost device and adds a second one (BE-11): no window,
no re-invitation, no special onboarding protocol.

Recovering the _backend_ works the same way round. Every installation keeps each
log object exactly as it was stored — the bytes it downloaded, still encrypted
if the group is encrypted (TR-17) — alongside the projection it actually shows
people. That is more disk than a projection alone needs, and it buys the only
repair mechanism a design without a server can have: whatever is missing from
the backend is put back by uploading the same bytes again, under the same
numbers. Nothing is re-encrypted and nothing is re-serialised, so the chain
still verifies and every other installation sees the history it already had,
unchanged.

Missing objects need no special permission — a conditional `PUT` at each absent
number is an ordinary write. An object that is present but _wrong_ is the harder
case, and the honest answer is that the app cannot fix it: the credential has no
delete permission on purpose (section 10). Somebody empties the location with
the storage provider's own tools, or points the group at a fresh prefix, and the
first installation to sync refills it. Neither is elegant, and both are better
than a repair path that requires the app to be able to destroy history.

None of it happens by itself. A backend that has gone backwards means something
went wrong that the app cannot see the cause of — a bucket restored from an old
snapshot, a lifecycle rule nobody meant to apply, somebody tidying up — and
uploading over it on a hunch could just as easily be the second mistake. So
syncing stops and stays stopped until a person decides what happened (SY-15).

## 8. Polling, batching and what it costs

S3 has no notifications, so the app polls: on open, after local changes, and
periodically in the background. **Every poll is a billable request**, so the
interval trades money as well as battery against freshness. It is therefore
**configurable per group** (SY-13), and the app should say why rather than hide
a default: polling every fifteen minutes costs about twenty times what every
five hours does, and for an app about groceries and rent the slower setting is
usually right. Fifteen minutes is also the floor — Android schedules deferrable
work no more often than that, and honours even that only when it feels like it
(TR-9), so the interval is a lower bound on the wait and never a schedule. A
poll that finds nothing is one `ListObjectsV2` returning the single key the
installation already holds — the cheapest request the API has, and the case the
whole read path is shaped around. Changes by other members (SY-10) are therefore
noticed a polling interval late at best: a real regression compared to a server
with a long-lived connection, and an acceptable one here.

Uploads are **delayed and batched** (SY-14). Somebody adding an expense usually
adds two or three, so the app waits a short while after the last change before
writing, and the queue goes up as one object instead of three. One request
instead of three, and a shorter log for everyone else's cold start. The delay is
not a sync interval and must stay short — long enough to catch a burst of
typing, not long enough that a user watching the sync state thinks nothing
happened. A manual sync bypasses it entirely (SY-9).

## 9. Encryption

A bucket is usually rented, which makes the provider a party the group never
chose to trust with its contents. So passphrase encryption is part of the first
version (PR-11, PR-12): off by default (PR-4), offered when the backend is
configured.

It fits here for one specific reason: **no object is ever re-derived by a second
installation.** Each is written once, by one writer, and never rebuilt, merged
or rewritten. So nothing anywhere depends on two installations producing
identical bytes.

The encryption itself is [age](https://age-encryption.org), used as it comes.

The shape:

- **a group has one key pair.** The root object _is_ the private key, encrypted
  to the group's passphrase — an age file and nothing else, so `age -d` opens it
  and `age -i` takes what comes out. The public key is written down nowhere;
  each installation derives it from the private key once it has unlocked it. An
  installation joins by fetching that one object;
- **every later object is encrypted to the public key.** Reading costs no key
  derivation at all, which is what makes a whole history practical to decrypt —
  whether by the app on a cold start, or by somebody at a shell with the `age`
  tool;
- **every object carries a keyed hash of the one before it**, with the key
  derived from the private key. That is what makes the log a history rather than
  a pile: an object cannot be inserted, reordered, or transplanted from another
  group or another position in this one — which a provider could otherwise do to
  existing objects without ever decrypting them;
- **whether a group is encrypted is decided when its backend is configured** and
  does not change afterwards. Turning it on later would mean rewriting every
  object, which this design has neither a mechanism nor a permission for.

A key pair usually costs something: a public key is public, and anyone holding
it can write something the holders of the private key will decrypt. Here it
costs nothing, because the public key is never written down.

What is not hidden: object **names**, and therefore how many objects exist and
roughly when each appeared, plus the fact that the group is encrypted at all.
Ordering depends on the names being readable, so this is accepted rather than
solved (threat model TM-7).

PR-12 is a product problem rather than a cryptographic one: a passphrase
recoverable by no one has to be presented as such when it is chosen. Losing it
costs the backend copy, not the group — every installation still holds the whole
history (SY-11).

## 10. Things that will hurt

- **Shared credentials are shared power.** Everyone holding the group's key can
  read and write everything under its prefix. That fits the group's trust model
  (GR-10), but it also means removing a member (GR-6) removes nothing: access
  follows the key, and taking it back means rotating it and having everyone
  re-enter the settings (BE-13). A key scoped to one prefix and denied delete
  permission at least turns SY-4 into something the storage enforces — but
  somebody has to create it, per group, in the provider's console.
- **Not everything called S3-compatible is.** Conditional writes are the sharp
  edge (section 4), but path-style versus virtual-hosted addressing, region
  handling, listing behaviour and multipart support all vary across AWS, MinIO,
  SeaweedFS, Ceph, Garage, Backblaze B2 and Cloudflare R2. Expect a
  compatibility matrix, and expect BE-7 to exercise the operations actually used
  rather than check reachability.
- **The settings are long.** Seven fields entered on a phone, as the first thing
  every new member does. They should at least be pasteable as one blob.
- **Quota, and bills.** Full storage must degrade into "changes are queued",
  never into lost entries (SY-8). Unlike a NAS, a rented bucket can also stop
  working because an invoice went unpaid, which the app cannot tell apart from a
  wrong key.
- **Local-network backends.** A MinIO on a NAS in the hallway is a perfectly
  reasonable backend that syncs only when the user is home; the sync state
  display (SY-6) has to make that legible rather than look broken (SY-12). It is
  also where an endpoint on plain HTTP is plausible, which puts the access key
  on the wire in the clear (PR-5).
