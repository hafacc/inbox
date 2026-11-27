# inbox — backend design

An email client in the spirit of Google Inbox, for web (inbox.hafa.cc) and native Android, backed by
Gmail. Mostly for one person, built to grow to the ~100 Google accounts an unreviewed Google app may
serve. Named "inbox" rather than "mail" because latr's todos may merge in later; until then it is a
separate app with its own Firebase project.

## Principles

- **Gmail is the source of truth for mail.** Messages, threads, labels, spam, drafts and search live in
  Gmail. We never keep a second copy of anyone's mail on our servers; devices keep a local cache.
- **Firestore holds only what Gmail can't:** snooze times, the outbox schedule, @hafa.cc address claims,
  device tokens, settings, public keys, and latr's todos.
- **Clients talk to Gmail directly.** The server mints short-lived access tokens; reading, searching,
  labeling and drafting go device → Gmail with no hop through our code.
- **The server only does what needs to happen while every app is closed:** set up @hafa.cc forwarding,
  send at a set time, push notifications. Snoozes are decided on each device, as in latr.
- **Behavior is shared, not code** (the reader/subtube pattern). Web is SvelteKit, Android is Kotlin +
  Compose with a JVM `:core`; logic that must agree (snooze suggestions, filter search building, MIME building,
  HTML cleaning, todos) is ported by hand and pinned by fixtures in `shared/`.

## Pieces

| Piece | Runs on | Job |
|---|---|---|
| Web app | SvelteKit static build on GitHub Pages at `inbox.hafa.cc` | UI, local cache (IndexedDB), offline queue, service worker |
| Android app | Kotlin + Compose, `:core` + `:app` | UI, local cache (Room), offline queue (WorkManager), FCM |
| Firebase Auth | Firebase (custom tokens from `signIn`, so no auth domain or hosting) | uid |
| Firestore | Firebase (Blaze) | App state listed above, offline-synced to both clients |
| Cloud Functions v2 | Firebase (Blaze) | Token exchange, address claims, Gmail change handler, timers, sending, unsubscribe |
| Cloud Tasks | Google Cloud | One task per scheduled send, and the @hafa.cc setup checks |
| Pub/Sub | Google Cloud | Gmail's change feed (`users.watch`) |
| Cloud KMS | Google Cloud | Encrypts stored Google refresh tokens |
| FCM | Firebase | Notifications to Android and to the web app |
| Email Routing | Cloudflare (hafa.cc already lives there) | Forwards each `name@hafa.cc` to its owner's Gmail |
| Key Worker | Cloudflare, `openpgpkey.hafa.cc` | Publishes users' OpenPGP public keys (WKD) |
| Resend | resend.com, SMTP | Sends mail from `@hafa.cc` addresses |

## Google access

One Google Cloud project, OAuth consent screen **published ("In production") but unverified**:

- Users see Google's "unverified app" screen once and click through.
- Testing mode is not an option: its refresh tokens die after 7 days.
- Hard cap: 100 Google accounts over the life of the project, counting every account that ever
  consents (test accounts too). Past that needs Google's yearly paid security review (CASA).

Scopes:

| Scope | For |
|---|---|
| `gmail.modify` | read, label, archive, spam, drafts, send, insert |
| `gmail.settings.basic` | read send-as addresses, read and write Gmail filters |
| `contacts.readonly`, `contacts.other.readonly` | Google Contacts autocomplete and names/photos |
| `calendar.events` | RSVP inline, show conflicts |

Sign-in flow: the client asks Google for an authorization **code** with offline access (web: Google
Identity Services code client; Android: `AuthorizationClient` with `requestOfflineAccess`). The
`signIn` function swaps it for a refresh token, encrypts it with KMS, and stores it in
`private/{uid}`, creates or finds the Firebase user (uid `google:<sub>`) and returns a Firebase
custom token, so the same Google prompt also signs the user in. Clients then call `gmailToken` for an access token (cached until expiry) and use it
against Gmail, People and Calendar directly. One consent covers both clients and the server.

**Pacing.** Gmail limits each user's calls per second and at once, so each device paces its own
(`shared/src/gmail-pace.ts`, fixtures in `shared/testdata/gmail-pace-fixtures.json`): at most 4
calls run at once, and a waiting call goes in order of urgency (the user's actions, then what is on
screen, then prefetches and refetches, then the saved copy's rebuild), first come among equals; two
reads of the same thread at once share one call. The rebuild's reads (the first fill's thousands of
whole threads) yield to every other call, waiting or running, run at most 2 at once and start at
least 250 ms apart: at most 4 a second, about 40 of Gmail's 250 quota units a second per user
(which all of the user's devices share), so opening a thread never waits behind them or behind a
usage limit they caused. After a usage limit they stay held 30 s past the pause, so what the user
asks goes alone while the quota refills. A usage limit (429, or 403 `rateLimitExceeded` / `userRateLimitExceeded`)
pauses every call on the device: `Retry-After` when Google gives one (at most 60 s), else 1 s doubling to 32 s,
each stretched by up to half at random; a limit that comes back while the pause runs doesn't
lengthen it. A read is tried again up to 4 times; a write is left to the offline queue, which
retries it. An open thread whose load still fails on a usage limit shows the error with Retry and
loads again by itself once the pause ends. A call gives up after 30 s (a write after 120 s) and frees its turn, so one Gmail never
answers can't hold a list. A list or bundle whose load still fails shows Retry, never a
lasting spinner. Development builds log each pause (the error, how many in a row, how long, and
how urgent the call was); the web's open timing lines name each failed try's status and Google's
reason.

## Firestore

Clients can read/write only under `users/{uid}`; `private/*` and `addresses/*` are function-only.

```
users/{uid}
  email, displayName, photoUrl                    // server only: the Google profile, set at each sign-in
  address: "name@hafa.cc" | null                // server only (docs/address-contract.md)
  addressSetup: { status: "pending" | "verifying" | "active" | "releasing",
                  error: { code, message } | null, updatedAt } | null   // server only
  settings: { signature (styled text, as the body), sendAsDefault,
              images ("ask" | "always"), imageSenders: { "<address>": true }   // docs/images-contract.md
              varySendTimes (bool, on unless false), varySendMaxMinutes (1–30, default 10),
              theme ("green" | "blue" | "plum" | "clay" | "slate", default green; shared/themes/themes.json;
                     green and blue are shown as Fern and Harbor),
              shortcuts (bool, on unless false; web only: off stops single-key shortcuts, Escape and
                         Cmd/Ctrl+Z still work),
              showTodos (bool, on unless false: todos show among the mail and in a Todos view; the
                         rules don't check its type, which would push a write with a pgp key past
                         their step limit),
              quietBundles (bool, off unless true: new mail that lands in a bundle sends no
                            notification, nor, on Android, a bundled thread's or todo's snooze
                            ending; see Notifications) }
  pgp:                                            // client-written, shape checked by the rules (docs/pgp-contract.md)
    publicKey                                     // armored; the server reads only this, to publish it
    sealed: [{ credentialId, salt, sealedPrivateKey, name, addedAt }]   // one per passkey, at most 8
    recoverySealed: { salt, sealedPrivateKey, kdf }                     // under the recovery phrase (Argon2id)
  devices/{fcmToken}: { platform, lastSeen }
  labelPrefs/{labelId}: { sidebar?, bundle?, icon? }   // per label; every field optional (below)
  threads/{threadId}:                             // only threads we have state for
    snoozeUntil: "YYYY-MM-DDTHH:mm:ss" | null     // local wall time, no zone, as in latr
    snoozeWatermark: historyId                   // thread's latest history id when snoozed
  todos/{todoId}:                                 // latr's todos, tombstones included ("Todos" below)
    text, state ("ACTIVE" | "DONE"), createdAt, modifiedAt (epoch ms), serverModifiedAt (the write's time),
    snoozeUntil, occurrence ("YYYY-MM-DDTHH:mm:ss" | null), pinned, position (string | null), deleted,
    repeat ({ every, unit, weekday, monthDay, at, anchor } | null), repeatSpawned
  todos/{threadId}:                               // a todo on an email, inbox only ("Todos on an email" below)
    threadId, text, createdAt, modifiedAt, serverModifiedAt, deleted
  outbox/{id}:                                    // scheduled send, undo send (docs/compose-contract.md)
    draftId, from, threadId?                      // client: the Gmail draft and its From address
    status: "waiting" | "sending" | "retrying" | "blocked" | "sent" | "failed"   // clients write "waiting",
                                                  // and a device sending a send now itself its claim and result
    chosenAt: Timestamp | null                    // the suggestion the user picked (null when typed or undo)
    sendAt: Timestamp                             // when it actually goes (shifted when the setting is on)
    via?, claimedAt?, sentAt?, messageId?, messageIdHeader?, filed?, rejected?,
    attempts?, firstAttemptAt?, nextAttemptAt?, lastError?: { code, message, at },
    blockedOn?: "sign-in" | "resend",
    inFlightAt?, uncertainSince?, draftMessageId?, messageDate?, idempotencyKey?,
    error?: { code, message }, failedAt?          // server only, but for a device's own send (below)
    deviceClaim?                                  // the claim of a device sending it; a server claim deletes it
  meta/snoozeStats: { sets, tod, lastCustom,    // learned snooze suggestions: latr's snoozeVotes format,
                     picks?, pickLog? }          // changed only by increment() so devices never overwrite;
                                                 // latr's apps share it and add their opt-in pick log
  meta/sendStats: { sets, tod, lastCustom }      // learned send-time suggestions, same format, kept apart
  meta/snoozeWaits: { waits: { [fcmToken]: { itemId?, snoozeUntil?, updatedAt } } }
                                                 // the snooze each phone waits on (Notifications)

snoozePickLog/global: { "1".."20" | "none": n, key }   // latr's opt-in tally of picked menu rows;
                                                 // admins read, a write moves one field by 1
addresses/{local}: { uid, createdAt,            // "name" → owner; doc id is the claim lock
                     holderHash,                 // keyed hash of uid; all a retired name keeps
                     destination, status,        // owner's Gmail address; setup status as above
                     destinationId?, ruleId?, verificationSentAt?,   // Cloudflare ids
                     releasingAt? }              // when account deletion began releasing it
serverKeys/addressHolder: { sealed }            // server only: KMS-sealed key behind holderHash
deletedAccounts/{uid}: { expiresAt }            // server only: set by deleteAccount, cleared by signIn;
                                                 // the rules refuse client writes while it exists; TTL
private/{uid}: { refreshToken (KMS ciphertext), email, scopes,   // granted scopes at the last sign-in
                 historyId, watchExpiry, lastSyncAt, updatedAt }
  actions/{nonce}: { threadId, expiresAt }      // one per notification, for its Done button; TTL on expiresAt (14 days)
  sentCopies/{outboxId}: { draftId, threadId, messageIdHeader, chunks, savedAt, nextAttemptAt,
                           filingAt? } + chunks/*  // a Resend send's Sent copy until filed
  resendHandovers/{sha256(draftId)}: { idempotencyKey, messageDate, messageIdHeader, startedAt,
                                       expiresAt }   // a draft's Resend key; TTL on expiresAt (1 day)
```

Snooze follows latr's rule: **snoozed-ness is derived** — a thread is snoozed iff `snoozeUntil` is in
the future and no message newer than `snoozeWatermark` has arrived **in the inbox**. Each device
decides on its own clock, offline, with no write when it lapses. `snoozeUntil` is a wall time with no
zone (`YYYY-MM-DDTHH:mm:ss`, latr's format; `shared/src/local-time.ts`, fixtures in
`shared/testdata/local-time-fixtures.json`), read in each device's own zone, so "08:00" means 08:00
wherever the device is. A snoozed thread stays in Gmail's inbox; the apps hide it there until its
time. A newer message is a `messageAdded` in `history.list` after the watermark whose own labels
include `INBOX` (labels are per message; a thread's labels are the union, so they can't tell).
Drafts and the account's own sent mail don't count, unless the latter lands in the inbox (a note to
self). Mail a Gmail filter keeps out of the inbox doesn't end a snooze, not even once it gains
`INBOX` later: `history.list` may report a message's current labels, so a `labelAdded` of `INBOX`
later in the same scan marks it as having arrived without. In the Snoozed view such a thread shows a
quiet "New message · still snoozed" line. Nothing on the server knows about snoozes, so gmail.com and
Gmail's own apps show a snoozed thread in the inbox all along.

## Flows

### Claiming an @hafa.cc address

Client contract: [address-contract.md](address-contract.md).

1. User types a name in the app. `claimAddress` checks it (2–32 of `a-z0-9.-`, letter or digit at
   both ends, no doubled punctuation; reserved: role names such as `support`, `postmaster`,
   `abuse`, `admin`, and every hafa.cc subdomain, also with dots and hyphens removed) and in one
   transaction creates `addresses/{name}` and sets `users/{uid}.address`; it fails if the doc
   exists (held, or left by a deleted account) or the user holds or held another name. One address
   per account, for good: there is no release, and a name never passes to another account. Every
   claim carries `holderHash`, an HMAC-SHA256 of its uid under a random key stored KMS-sealed at
   `serverKeys/addressHolder`, and a deleted account's doc keeps only that; when that
   uid signs in again, `claimAddress` lets it
   take that name back (overwriting the doc as a new claim) and refuses any other (`had-address`).
   Clients can't write `address` or `addressSetup` (rules).
2. It adds the user's Gmail address as an account-level Cloudflare Email Routing destination
   (reusing one that exists), which mails a confirmation link to that Gmail, and queues a chain of
   `addressCheck` tasks (15 s, then sparser for about a day). Each check, and one queued right away
   when `gmailChanged` sees new mail for a mailbox whose claim is verifying, searches the Gmail
   (`in:anywhere from:cloudflare.com newer_than:2d`) for that mail: sent after the destination was
   added, authenticated by Gmail as Cloudflare's (DMARC or DKIM pass for a cloudflare.com domain),
   naming the destination address (in `To` or its text), about verifying, and only while the
   destination is under a day old. It GETs the link only if it is https on `dash.cloudflare.com`
   or `verify.cloudflare.com`, follows redirects only while they stay on cloudflare.com, then asks
   the API whether the destination is confirmed; if so it trashes the mail. Cloudflare doesn't
   document the mail or link, so this matches traits rather than an exact format, and the
   confirmation page may need more than a GET. After 10
   minutes, or once opening the link didn't confirm, `addressSetup.error` asks the user to click
   the link; the checks (and `checkAddress`) notice that through the destination's `verified` time.
3. Once confirmed, it creates the zone-level routing rule `name@hafa.cc → their Gmail`, named
   `inbox: name@hafa.cc` (Cloudflare only accepts a rule to a confirmed destination), and marks the
   claim `active`. Every step looks for what already exists first, so checks can repeat or
   overlap, and a save never moves a claim back (a slow check that read it `verifying` can't undo
   `active`). A rule for the address not named that way was made by hand (the domain's owner has
   some, e.g. `support@`): it makes the name `taken` at claim time, and setup never updates or
   deletes it. Cloudflare's limits: 200 rules
   per domain and 200 destinations per account, comfortably above 100 users.
4. Account deletion marks the claim `releasing` (so a running check that creates a rule deletes it
   again), deletes its own rule (never a hand-made one), deletes the destination unless another
   claim, rule or the catch-all still forwards to it, then empties `addresses/{name}` (no owner,
   no forwarding; the doc id alone keeps the name taken) and clears `users/{uid}.address`. A
   claim left `releasing` over an hour by a deletion that failed partway is settled by
   `sweepStuck`: retired when the Firebase Auth user is gone, otherwise put back to `pending` so
   setup restores its forwarding. When the confirmation mail is over
   a day old, `checkAddress` (and claiming the same name again) deletes the unconfirmed
   destination and adds it again, which is how Cloudflare sends a fresh one.
5. Replies from Gmail's own apps can come from the new address only if the user adds it in Gmail
   settings as send-as through Resend SMTP (optional, documented). Our apps send from it regardless.

### Receiving @hafa.cc mail

Plain forwarding: Cloudflare forwards to the owner's Gmail (it rewrites the envelope sender and adds
ARC headers so Gmail's SPF/DMARC checks still hold). From there it is ordinary Gmail mail: Gmail's spam
checks, categories, calendar invites and **the user's Gmail filters all apply as usual**, and it
reaches us through the same change feed as everything else (next section). A filter on
`{deliveredto:name@hafa.cc to:name@hafa.cc}` can label it; the app offers to create one (with the
user's own token, no server call) once the address is active. No server of ours
sits in the path, so nothing of ours being down can lose mail. Cloudflare's limit is 25 MB per message.

### Receiving ordinary Gmail mail

1. `users.watch` (renewed daily by a scheduled function; it lapses after 7 days) publishes to Pub/Sub.
2. `gmailChanged` runs `history.list` from `private/{uid}.historyId` and sends an FCM notification
   for each new message that landed in the inbox (a filter that archives it, or spam, means no
   notification). A sign-in that finds no stored refresh token (first sign-in, or Google revoked
   the old one) clears `historyId`, so mail from while access was gone sends no notifications.
3. Clients separately keep their own `historyId` and pull changes when opened or notified.

**Thread dates.** Every list shows a thread once, placed in its time section, sorted and dated by its
latest received message (one without `SENT`), so the user's own reply doesn't move it; a thread of only
the user's messages uses its latest message. Sent uses the latest message the user sent instead.
Search matches any message, but its rows follow the same rule. A thread whose snooze ended sorts by the
later of that date and its wake time. Gmail's message order isn't trusted, and drafts don't count
(`shared/src/thread-dates.ts`, fixtures in `shared/testdata/thread-date-fixtures.json`).

### Snooze, pin, done

- **Snooze:** client writes `threads/{id}.snoozeUntil` + watermark (works offline) and leaves the
  thread in Gmail's inbox; no label of our own. A thread snoozed from outside the inbox (search, a
  label) gets `INBOX` (queued like any label change), so it comes back there. Each device hides it
  until its local time passes, then shows it again, sorted by when its snooze ended; re-snoozing or
  un-snoozing just changes the doc. Android schedules a local notification for the end (below);
  the web app gives no reminder while it is closed. A thread archived elsewhere while snoozed (in
  Gmail itself, say) counts as Done: the next device to fetch a copy of it newer than the snooze,
  with none of its own changes to the thread still on the way to Gmail, clears the snooze. Done on a
  snoozed thread clears the snooze too.
- **Filling the inbox:** Gmail's inbox pages include snoozed threads, which the list leaves out, so
  a page can show few rows or none. Both apps keep reading further pages by themselves while the
  list doesn't fill the screen, and show the loading state rather
  than "All done" while they do.
- **Suggestions:** latr's learned snooze menu, ported with latr's fixtures. Counts sit in this app's own
  `users/{uid}/meta/snoozeStats` (same format), which latr's apps read and write too, so mail and
  todos learn one menu.
- **Pin:** Gmail's star (`STARRED`), so pins and stars are the same thing everywhere. Pinned threads
  stay put through "Done all". Pins (threads' and todos') show in the theme's accent color.
- **Important:** Gmail's own `IMPORTANT` label, shown as a small marker in the theme's accent color by the sender and set
  or cleared from the open thread (on a phone: the marker beside the subject in its top bar; on a wide web screen: its
  header button; `+` / `-`) through `threads.modify`, queued like any label change.
- **Delete:** moves the thread to Gmail's trash: adds `TRASH` and takes `INBOX` off through `threads.modify`, queued
  like any label change, and drops any snooze, with the usual undo ("Moved to trash"). A thread in the trash offers
  Move to inbox instead (takes `TRASH` off, puts `INBOX` back). From the open thread, or `#` on it or a focused
  thread row.
- **Focus:** a switch on each device (not per account, kept across restarts and sign-out). While on,
  the inbox shows only pinned threads and pinned todos; bundles are hidden, since pinned threads
  never sit in one (the open thread, alone or in its bundle, stays, and so does the thread the
  keyboard focused, unpinned, until focus leaves it). Other lists are unaffected. It
  lists the inbox with Gmail's `is:starred` added to the view's own query, as its own list paged
  like any other, so every pinned thread is reachable however far back; rows already loaded that
  aren't pinned are counted as hidden (`shared/src/focus.ts`, fixtures in
  `shared/testdata/focus-fixtures.json`).
- **Done:** remove `INBOX` (Gmail's archive). "Done all" sweeps every unpinned thread in view. Undo
  for one action, 5 seconds, as in latr. On a phone (Android, and the web at phone width) the undo
  bar sits at the bottom on the line of the Compose button, narrowed to stop short of it on both
  sides while the button shows, its text wrapping to two lines; the button never moves for it. With
  no Compose button on screen the bar may take the full width, less the margins. While an open thread's or
  the selection's round corner button shows, the bar sits above that button, never under it.
- **Reply on a phone** opens the full composer, as Reply all does (a reply started on the device
  opens again as it was); there is no short reply field under the thread there. Reply, Reply all
  and Forward open the full-screen composer a new message uses, over the thread (the web at phone
  width too, rather than inside the thread card), so they can be parked like it (see "Parking a
  message on a phone"). Opening an email on a phone always shows the email: a reply parked in the
  strip stays there until the strip (or Reply on its thread) is tapped, and a reply draft kept for
  it shows as its draft row in the thread, which a tap opens full-screen; neither opens over the
  email by itself. In Drafts a row opens its draft instead (below). Reply all is offered when it would reach more than one other person
  (`offersReplyAll`, `shared/src/compose/reply.ts`).

### The list

How a list is used — its rows in visual order, keyboard focus, the open thread, the open bundle and
its paging, selection, the scroll anchor, rows an action took out that Gmail hasn't dropped yet, and
undo — is one value with one pure transition (`shared/src/list-state.ts`, Android
`core/mail/ListState.kt`; fixtures in `shared/testdata/list-state-fixtures.json`, and a randomized
run of event sequences checks every rule below after every step). Every input is an event: j/k/↑/↓,
Enter/o, Escape, x and the action keys; clicks, taps and Tab; done, snooze, spam, move, label, read
and category from a row, the open thread, the selection or a notification; undo; and each
refetch — the inbox, a bundle's page, pinned, focus mode, new mail, todos changed on another device —
as a new layout. Neither app keeps focus, open or selection state of its own, and neither scrolls
except where the list says.

- Focus is on a row that exists (a thread, a todo or a bundle) or on nothing.
- A row that goes, by an action or a refetch, hands focus to the next row in visual order, else the
  previous one. Inside an open bundle focus stays in the bundle: Done on its last shown thread moves
  to the one before it, and the bundle stays open (unless Gmail has more, below).
- A refetch never moves focus or the scroll position: the focused row keeps focus wherever it moves,
  and the row at the top of the screen stays where it is (the next row takes its place if it went),
  except that a list scrolled to its very top stays at the top, so new mail shows.
  Only the keyboard scrolls a row into view, and a new todo (`reveal`), which scrolls to itself
  once a layout lists it, also when it was started from another view (a view's own saved scroll
  position gives way to it); the whole row shows, kept in view below the header and above the
  on-screen keyboard while its editor and the keyboard open. Closing a thread scrolled down
  through brings its row back into view where it was (Android: the list comes back at the row it
  showed before, not at its old position, and the closed thread's row is scrolled into view).
  A row shown again inside the open bundle (by a close or the keys) sits below the bundle's held
  header, never under it, on both.
  When the thread's row left the list as it closed (Done or another action from the thread, its
  bundle closing with it), the row focus moved to comes into view instead (the next row, or the
  bundle's header), not the rows the thread had covered. A layout measured while the thread was
  open never scrolls the page back after the close, and once the close has played the row is
  checked again and brought back if it ended up off screen, unless the user scrolled meanwhile.
- Pinning or unpinning the thread or todo the keyboard focused doesn't move it to or from Pinned, or
  into or out of a bundle, under the user: that move waits until focus goes elsewhere
  (`shared/src/held-places.ts`, a todo held by its row key). In focus mode, unpinning it likewise
  leaves it showing in place until focus leaves (`shared/src/focus.ts`). A row focused by a click or
  tap (a todo being edited, a row whose button or hold menu was used) holds nothing: pinning or
  unpinning it moves it at once.
  Only the pinning waits; a reply, a new label or a refetch moves the focused thread at once, as
  any other. Closing a thread lets go of what its being open held, so it moves where it belongs
  once its close has played, though focus stays on its row.
- One row per thread, in exactly one place: one section, one bundle, or the inbox's own rows; a todo
  of its own shows once, as its own row or in a bundle, and an email's todo inside its email's row
  (in a bundle too; in the Todos view, its email's row drawn as the todo; see Lists). A thread is dated by
  its latest received message (a woken snooze by its wake time).
- The open thread holds focus. Acting on it, or a refetch dropping it, returns to the list with
  focus where its row was. j and k from an open thread open the next or previous thread.
- A thread opens already scrolled to its first unread message (else its last) when that isn't its
  first: on the web the thread stops growing out of its row and widens from it at full height,
  so nothing scrolls once it has opened (at phone width it covers the screen and opens there).
- On touch (web: at phone width), pulling the open thread 160 px (dp) past its top or end, starting
  there, closes it as Back does, as in reader but farther, so a scroll that runs on past the edge
  doesn't close it by accident: it follows the finger less and less, an arrow in the gap turns over,
  and a haptic tick marks crossing that point (either way) and letting go there.
- One bundle is open at a time. It shows its first five items (threads and todos, in date order)
  until j goes past them or "Show more" is pressed. When j goes past its last loaded item and Gmail has more, focus sits on the
  bundle's header while the page loads, then moves to its first new row, even one that sorts above
  an older todo it went past (or back, if none came);
  the same happens when Done, another action or a refetch takes away its last shown thread. A
  refresh of a bundle's first page keeps the later pages already loaded (the lists merge), so it
  doesn't shrink an open bundle; a thread archived elsewhere leaves it. An open bundle left with no
  threads closes once Gmail has no more. Its header stays at the top of the list as its threads
  scroll, and they are cut off at the header's bottom edge, going into it rather than on behind
  it (web: not while a thread is open in the bundle, whose own headers stick there instead).
- Focus is always inside the open bundle (its header or a thread it shows): focus ending up outside
  it by any route — j, k, Tab, a click, an action, undo or a refetch — closes it.
- Menus and dropdowns, the open thread and the open bundle are layers, in that order from the top.
  A click or tap outside the top one closes that one only, rather than acting on what was tapped:
  clicking away from a label menu opened inside a bundle closes the menu and leaves the bundle open.
  A tap outside a bundle with a thread open in it closes the thread, a second one the bundle.
  Escape steps back one level: the open menu, then the open thread, then the selection, then the
  open bundle, then focus. Back works as one stack on both platforms (`shared/src/back-stack.ts`,
  Android `core/mail/BackStack.kt`; fixtures in `shared/testdata/back-stack-fixtures.json`): it
  closes whatever opened last (the side menu, a menu, sheet or dialog, a sheet's step, the open
  thread, a todo being edited, the selection, the open bundle, search), and with nothing open
  returns to the view shown before (Snoozed, then Done: Back shows Snoozed). The Outbox and each
  settings page count as views, so settings pages stack (settings, then Filters: Back shows
  settings, and a search started from Filters closes back to Filters; Android's filter editor is
  part of Filters, as on the web); one search replaces another, and picking the view before from
  the menu goes back to it rather than stacking it again. A view picked from an open thread (a
  label chip, a search) closes the thread, so Back returns to the view without it. At the first view with
  nothing open, also one a link or a reload opened on, Back leaves the app on Android and does
  what the browser does on the web. The web keeps it as history entries (an overlay's `pushBack`,
  a layer's `followLayer`, views and the open thread as places); an entry whose layer closed some
  other way (its X, Escape, an action) is taken off, or stepped over when it isn't on top, so
  Back never does nothing. Android keeps the views shown in a list and adds each layer's Back
  when the layer opens (`LayerBackHandler`),
  so a selection made in search results is cleared before search closes, and takes it away as
  the layer starts to close (a thread shrinking into its row, the search bar fading), so a quick
  second Back reaches the layer under it.
- Selection only holds rows that exist. A todo's select dot selects it, as a thread's avatar
  does, and once anything is selected a tap on any row toggles it; a todo is marked done by swipe,
  its row's Done action or the selection bar. The dot is a small dot when not selected (a done
  todo keeps its green check, a snoozed one its clock, a pinned one its pin) and a filled ring
  with a dot when selected; a selected row (a thread, a todo, an email with a todo) is marked by its tint and its
  avatar's check (a todo's ring) alone, with no bar along its edge. While rows are selected, a swipe on any thread or todo acts on that row
  alone, as it does otherwise, never on the selection (`threadSwipeAction` and `todoSwipeAction`
  take no selection; `shared/testdata/swipe-fixtures.json`). On a phone (Android, and the web at
  phone width) the selection bar sits at the bottom of the screen, where Compose and New todo
  were (they hide while rows are selected). The bar is 56 dp (px) tall and the same on both: Clear,
  the count and Select all (or none). Every action that would change something is on a round corner
  button (below) 12 above the bar: on its inner ring Done and Snooze, Move to inbox taking
  Done's place and Wake now Snooze's wherever they apply; on its outer ring Pin (Unpin) first, then a
  Done or Snooze so displaced, Label, Mark read or unread, Report spam or Not spam and Delete (Move to
  inbox when every selected thread is in the trash and no todo is selected), in the open thread's
  order (below), the same icons for the same actions. For todos alone the inner ring holds Done alone,
  and the outer ring Snooze, Pin (Unpin) and Delete. An outer ring of one joins the inner ring, and so
  does every action when the inner ring would be empty; otherwise, with Done or Snooze missing, Pin
  joins the inner ring, as in the open thread, so threads and todos together have Done and Snooze
  inside and Pin and Delete beyond (`selectionDisc` in `shared/src/selection.ts`, same
  fixtures). The undo bar rises above the button. Above phone width the
  web's bar keeps every action in the header: Wake now as a ringed pill, Snooze, Pin, Label, Mark read,
  Spam and Delete as icons, then Move to inbox as a ringed pill and Done as a green pill. A selection of
  threads and todos together offers only
  the actions both have (Done, Snooze, Pin, Delete); labels, move, category, read and spam show only
  when the selection is all threads (`selectionActions` in
  `shared/src/selection.ts`, fixtures in `shared/testdata/selection-fixtures.json`). Delete moves the
  selected threads to the trash and deletes the selected todos, as one undo; with only threads, all
  in the trash, it moves them to the inbox (`todoSelectionPlan`, same fixtures). The selection has no
  Add todo: an email's Add todo opens its own todo to write, saved only once it has text, which
  several emails at once have no way to give.
- Undo puts back the rows the action took out and the focus it had.
- **Round menus.** Every round menu, on both platforms, is one element (web `hold-menu.svelte`, Android
  `HoldMenu.kt`), its geometry and hit-testing shared (`shared/src/hold-menu.ts`, Android
  `core/menu/HoldMenu.kt`; fixtures in `shared/testdata/hold-menu-fixtures.json`): wedges on one or two
  rings, the danger color for destructive ones and Done's green for Done when lit, the 30% scrim, the
  grow-in, the finger's ring, a haptic tick as the finger enters a wedge, and a pill naming the wedge under
  the finger. A finger up to 28 past the outermost wedge at its angle still counts for it.
- **The view's name.** On a phone (Android, and the web at phone width, the only place the top bar
  names the view) the top bar holds the menu button, which opens the side menu, then the view's name.
  A tap on the name glides the list back to its top (at once with reduced motion or animations off).
  Holding it for 450 ms (a right click or the menu key too) opens the round menu down from the name:
  one ring 40 to 124 from the name's middle, Inbox, Snoozed, Done and Todos (Todos only while todos are
  on) sharing 240° to 360° from rightward round (`viewHoldWedges`), the current view's icon in the accent
  color, the pill saying "Slide to a view", the view under the finger, or "Tap a view" once letting go in
  place keeps it open. Picking a view shows it as the side menu does, so Back returns to the view before;
  picking the current one goes to the top. The Outbox's name works the same, with no view marked. While
  search is open the top bar is the search field. Screen readers read the name as a button, "Inbox, go to
  top", with "Switch view" as a second action (Android: its long press; web: a button only screen
  readers reach).
- **Round corner buttons.** On a phone the Compose button, the selection's actions button and the open
  thread's actions button are round 56 dp (px) buttons in a bottom corner, 16 from the side and the
  bottom (the selection's 12 above its bar). The actions buttons show vertical dots on the accent color,
  as Compose shows its pen. A press on an actions button grows its round menu out of it: a quarter disc
  opening up and inward, the inner ring 44 to 130 from the button's middle and the outer 130 to 212, each
  ring's wedges sharing 90° equally from the top, its pill saying "Slide to an action". Sliding onto a
  wedge and letting go picks it; letting go on the button keeps the menu open ("Tap an action") for a
  tap, the arrow keys and Enter; Enter or Space on the focused button opens it so, focus on its first
  wedge. The menu stays as long as the finger rests; Escape, Back or a tap away (the button too) closes
  it. Dragging down from any corner button, more than 24 below where the press began and before reaching
  any wedge (`cornerDragStarts`), lifts the button: its menu fades away over 150 ms, it grows a little
  with a deeper shadow, a haptic marks it, and it follows the finger, sideways too; letting go snaps it
  over 200 ms to the nearer bottom corner (the middle counts as right), and every
  corner button moves there, New todo with Compose, the disc mirrored (`cornerSide`). The side is one
  setting on the device (web localStorage, kept across sign-out; Android preferences), right by
  default, also set under Settings, "Round buttons": Left or Right; TalkBack offers the move as an
  action on each button. Compose keeps its tap. While the on-screen keyboard is up the corner buttons
  stay under it (Android draws them below the keyboard; the web on a touch screen hides them while a
  text field has focus), so they never cover what is being typed.
- **Holding an email row** (Android, and the web on touch) for 450 ms anywhere on it, its avatar too,
  opens its round menu centered where the finger is, rather than selecting it, with the same two
  wedges placed the same way as a todo's (Hold menu, below): Pin (Unpin when pinned) and Delete (Move
  to inbox from the trash) (`rowHoldActions` in `shared/src/view-filter.ts`, fixtures in
  `shared/testdata/thread-action-fixtures.json`), the subject (an email drawn as its todo: the todo's
  text) in the pill. Done, Snooze, Wake now, Label, Mark read and Report spam are on its swipes, the
  open thread's round menu and the selection bar instead. It acts on that email alone, even while
  others are selected. Sliding, letting go, taps and Back work as on a todo's. Selecting stays on the
  avatar (and, while anything is selected, a tap on any row; shift-click on a desktop). TalkBack's
  long press on the row opens the menu on its avatar for taps, and the row's actions for TalkBack
  (Android) and screen readers (the web) are its swipes' and the menu's: Done (Move to inbox), Snooze
  (Wake now), Pin (Unpin) and Delete (Move to inbox).
- **Holding a bundle's row** (Android, and the web on touch) for 450 ms, closed or the open bundle's
  header, opens the same round menu centered where the finger is, placed as an email's and a todo's,
  its name in the pill: Pin all
  and Delete all, acting on every thread and todo Done all takes (those shown and the rest Gmail
  lists, with one undo that covers those listed later): Pin all pins them, so they leave the bundle
  for the inbox, and Delete all moves the threads to the trash and deletes the todos. The bundle's swipes stay Done all and Snooze all. Sliding,
  letting go, taps and Back work as on an email's; TalkBack's long press on the row opens it on the
  label's mark for taps, and the row's actions for TalkBack and screen readers are Done all, Snooze
  all, Pin all and Delete all; on an open bundle's header, which doesn't swipe and shows its own
  Done all, Pin all and Delete all.
- **Hold menu.** Holding a todo for 450 ms, on its select dot or anywhere else on the row, opens a
  third of a disc (120°) centered where the finger is, cut into equal wedges, with a pill above naming the todo: Pin
  (Unpin when pinned; not on a done todo, whose place stays empty) and Delete, with the usual undo,
  acting on that todo only, even while others are selected. A right click on the dot, the menu key
  (on the dot or a focused row) and TalkBack's long press open it on the dot instead. Every row's
  disc (a todo's, an email's, a bundle's) is placed alike (`rowHoldWedges` in
  `shared/src/hold-menu.ts`, `row` in the hold-menu fixtures): straight up, Pin up and to the left
  (90° to 150°, counterclockwise from rightward) and Delete up and to the right (30° to 90°); right
  and down within 190 px (dp) of the top, Pin to the right and Delete below; within 110 of the left
  edge turned 45° right, Pin above and Delete to the right, at the bottom too; and within 110 of the
  right edge the same, mirrored, opening leftward, so it stays on screen wherever the finger is.
  Sliding onto a wedge lights it (the pill
  names it, with a haptic tick) and letting go picks it; letting go without sliding keeps the disc
  open for a tap, and on the web for the arrow keys and Enter; Escape, Back or a tap away closes it.
  A press on the dot never scrolls the list, so sliding to a wedge moves only the disc; a quick tap
  on a wedge right after letting go picks it; and while the disc is open it takes every key, so no
  list shortcut acts under it. It is a menu to assistive tech, each wedge an item. The disc grows in
  from 15% of its size at the finger while fading in, over 200 ms, and the scrim fades in to 30% over
  150 ms; the finger ring and pill show at once. With reduced motion (animations off on Android) it
  shows at once. A press elsewhere on the row that moves before the hold scrolls or swipes, and once
  the disc opens the finger slides from where it is, letting go where it pressed keeping the disc
  open for a tap. A long press never selects: the dot does. Android shows the same disc when a link
  in an email is held: Open (as a tap does), Copy link and Share, with the link's full address in the
  pill, and TalkBack lists Copy link and Share among a link's actions (opening is its own tap); the
  web keeps the browser's own link menu on a phone and its right-click menu on a desktop.
- An action takes a row out of a list only when the list's own filter no longer matches the thread:
  Done leaves the inbox (and a search asking for `in:inbox`, or `-is:done`) but stays, archived, in a
  label, Sent, Done-filtered views and any search that doesn't ask for the inbox; Move to inbox
  leaves a search for `is:done`; taking a label off leaves that
  label's list; spam and trash leave every list but their own. A thread not in the inbox (nor
  snoozed) offers Move to inbox where an inbox thread offers Done — on its row, as its leftward
  swipe (but in spam or trash), in the open thread and in the selection bar — with the usual undo
  (`shared/src/view-filter.ts`, fixtures in `shared/testdata/view-filter-fixtures.json`; swipes in
  `shared/src/swipe.ts`, fixtures in `shared/testdata/swipe-fixtures.json`). What shows under a
  swiped row stays the move it was let go with until the row settles, though the action changes
  the thread at once: let go past the point where it acts, the row slides off its side with that
  move under it, and snaps back 200 ms later only if it is still listed (a row the action removes
  fades out meanwhile); short of it, the row springs back. A row being swiped is lifted (raised, with its shadow, its selection tint
  and bar hidden); under it only the action of the side it moves toward shows, filling the row
  in its soft color, which turns to its strong color (150 ms) with the icon growing once letting go acts.
- A closed bundle's row swipes as a thread's does, on every thread and todo in it: leftward is its
  Done all (below), rightward opens the snooze menu (sheet) for the bundle and the time picked
  applies to every thread and todo in it, each with one undo; the strip under it names the move with
  the count its row shows ("Done · 14", "Snooze · 10+"). An open bundle's header doesn't swipe (a tap closes it). With
  rows selected it acts on that bundle alone. TalkBack (and screen readers on the web) offer the same
  as Done all and Snooze all on the closed row, and Snooze's key (b) on a focused bundle opens its
  menu as Done's (e) acts (`bundleSwipeAction` in `shared/src/swipe.ts`, `bundles` in the swipe
  fixtures; `bundleSweepIds` in `shared/src/bundles.ts`, `sweep` in the bundle fixtures).
- On the web with a pointer that hovers, a closed bundle's row shows Pin all, Snooze all (its menu
  by the button) and Done all in the same floating bar, at the same place, as a thread row's hover
  actions, on hover, keyboard focus or while its snooze menu is open, its date fading under it. An
  open bundle's header shows Pin all and Snooze all as icons before its own Done all.
- The open thread offers every action a thread takes anywhere else (its row, a swipe, the
  selection bar, a key): Done or Move to inbox, Snooze, Wake now when snoozed, Pin or Unpin, Label,
  Add or Edit todo, Mark important or not, Mark read or unread, Report spam or Not spam, Delete
  (Move to inbox in the trash), and Filter mail like this, in that order (`threadActions` in
  `shared/src/view-filter.ts`, fixtures in `shared/testdata/thread-action-fixtures.json`). On a
  wide web screen each is a button in its header, on one line after the subject and label chips.
  Label chips shorten first (their names cut, down to 40 px each); when the buttons still can't
  all fit beside a subject 160 px wide, they move, from the end of the row, into a More button
  (vertical dots) at its end, whose menu names each with its key. Done, Move to inbox and Not
  spam go last. They come back as the window widens (`collapsedActions` in
  `web/src/lib/header-fit.ts`; web only, as Android has no wide header). Snooze and Label from
  that menu, or from `b` and `l`, open their menus under More.
- **The open thread on a phone** (Android, and the web at phone width) fills the screen over the
  list, which stays where it was behind it: there is no app header, and nothing behind it can be
  reached (by Tab or a screen reader) until it closes, when focus goes back to its row. It grows out of its row (fades
  in when the row is off screen) and shrinks back into it on closing. It has no menu. Its top bar,
  which stays while the thread scrolls, on the card color with no divider and casting a soft
  shadow once the messages scroll under it, holds Back, the subject (up to two lines, then cut; a tap
  shows a longer one whole, another cuts it again) and the importance marker, when the thread offers
  Mark important or not (web and Android alike). On a wide web screen
  the subject is cut to one line in the header, and a click on it shows it whole the same way
  rather than closing the thread. Its other actions are on a round corner button (above), where Compose
  sits in the list: Done (Move to inbox once done) and Snooze on the inner ring, Pin (Unpin), Label, Add
  todo (Edit todo), Mark read or unread, Report spam (Not spam in spam) and Delete (Move to inbox in
  the trash) on the outer, the selection's order with Add todo after Label;
  in spam and the trash, with no Done, Pin stays on the inner ring beside Snooze
  (`threadActionPlaces` in `shared/src/view-filter.ts`, same fixtures). The messages scroll
  under the button, their end kept clear of it and of the undo bar above it. Wake now (when snoozed)
  and Filter mail like this are buttons on the line above the messages, with the label chips and
  the message count ("3 messages"), each in a 48 dp (px) tall tap area, the line 16 in from the
  sides. Below it, on the page color, the messages scroll on a card 4 in from the screen's edges
  with 12 corners, the email's todo at its top; a draft is a row of that card like a message, under
  a separator, 16 in and 12 above and below, its "Draft" past a 36 space so it lines up with the
  senders' names. Each message has one header, folded or not: a 36 avatar, 16 in and
  12 above and below, the sender (16 px, semibold while unread), a lock when encrypted, the date,
  then the sender's address when open, then a line of whom it went to (Cc and Bcc too) when open
  or its snippet when folded. A tap on the header folds or opens any message, the last one too
  (everywhere, the wide web as well). An open message's body sits 8 in from the card's edges with 16
  below; its signature note and an encrypted message it can't show sit 16 in. Unsubscribe stays on its message, and Reply, Reply all and Forward at the end of the thread
  (`threadActionPlaces` in `shared/src/view-filter.ts`, same fixtures).
- **Swiping between threads on a phone.** The threads before and after the open one in the list
  stay drawn on either side of it, off screen, so a sideways drag slides the real neighbor in:
  left for the next thread down the list, right for the one above. Neighbors are the open
  thread's peers in visual order: inside the open bundle when it is open there (every thread it
  has loaded, not only the first five, and its next page loads as the open one nears its end,
  as below), else the list's
  own thread rows, passing over todos, closed bundles and headings (`openNeighbors` in
  `shared/src/list-state.ts`, `neighbors` in the list-state fixtures). A neighbor is drawn with
  its subject and label chips, from its saved copy, else its row (its last sender's avatar and
  name, with the snippet under the name, as a light message below); drawing it asks Gmail nothing, loads no
  remote images whatever the image setting (a sender would see an open that never happened),
  leaves out invites and attachments, and marks nothing read. On the web a neighbor is light text
  (each message it would show expanded as its sender and plain text) until a swipe toward it
  starts, when its messages are drawn in full, and stay so while it is the neighbor; so the open
  thread never carries its neighbors' message frames while nothing slides. Let go past 28% of the width, or flung its own way at 600 px (dp) per second once
  past 30, the swipe opens the neighbor as a tap on its row would (focus and the list's place
  follow; it is marked read), with nothing growing or shrinking; short of that, or toward a side
  with no thread (where the drag follows the finger a third as far), it springs back
  (`shared/src/thread-pager.ts`, fixtures in `shared/testdata/thread-pager-fixtures.json`). A
  swipe starting within 24 px (dp) of either screen edge (Android: or its system gesture inset,
  if wider) is left to the system's Back. A sideways drag on content that scrolls sideways (a wide
  table, code, a large image) only scrolls it. Two fingers pinch-zoom an email's body: the web
  zooms the page, as the browser does; Android zooms that body alone (its images and tables with
  it), from the size it opens at, and a double tap on it puts it back. While zoomed, a sideways
  drag on it pans it and never moves to another thread (web: anywhere on the zoomed page), and a
  pinch never scrolls the list, pulls the thread closed or turns a page. There are no next and previous buttons; on a wide
  web screen j and k do it.
- Todos and threads share one list in every view, interleaved by the view's own order (newest
  first, Snoozed soonest first); search has no separate todo section. A todo sorts by
  `todoListKey`: a done one by its own place, any other by its place in Active, a snoozed or woken
  one at its wake time. Among threads it goes by the time that key starts with (`todoListDate`);
  todos with the same time go by the rest of their keys (`newestTodosFirst`, fixtures in
  `shared/testdata/todo-order-fixtures.json`), so one started with Enter stays just above the one
  it was started from.

### Todos

latr's todos live in this backend so that latr's data can be copied in and keep working, from inbox's
apps now and from latr's own apps once they point here (docs/todos-plan.md). A todo is
`users/{uid}/todos/{todoId}`, holding everything a latr todo holds, with latr's field names and
values, so latr's apps keep their data code (its Android app must only start saving local times with
their seconds, which the rules require). The logic both apps must agree on is latr's, ported:
`shared/src/todos` (the stored shape, snoozed or not, list order, edits, repeats and positions) and
Android `:core` `todo/`, both running `shared/testdata/todo-fixtures.json` (generated from latr's own
code), `repeat-fixtures.json` and `position-fixtures.json` (latr's).

- **Snoozed is derived**, as for threads and in latr: a todo is snoozed iff it isn't done and
  `snoozeUntil` is ahead. Nothing is written when a snooze ends; once past, `snoozeUntil` is the time
  it woke, which places the row in Active.
- **Order** is by a text key, newest first: a row's own key is `position` if a move wrote one, else
  `modifiedAt` as 13 digits plus its id; in Active a woken row puts its `snoozeUntil` in front.
  Snoozed sorts by snooze time first. Every key starts with a millisecond time, so a todo can be
  placed among threads by time.
- **Repeats** count from the schedule, never from when the todo was done: marking a repeating todo
  done (with `repeatSpawned` off) sets `repeatSpawned` and, in the same write, makes its next copy,
  snoozed to the next occurrence after the later of its `occurrence` and now. Unsnoozing one by hand
  ends its repeat.
- **Deletes are tombstones**: `deleted: true`, text blanked, `modifiedAt` bumped. Lists query
  `deleted == false`, so every doc carries `deleted`. A tombstone keeps a device's stale copy (latr's
  signed-out todos merging in at sign-in) from bringing the todo back. An abandoned empty todo is
  deleted outright.
- **Every write stamps `serverModifiedAt`** with the server's time. The rules hold every field of the
  resulting doc to its type and `serverModifiedAt` to the write's own time, allow a bare tombstone
  (`{deleted, text, modifiedAt, serverModifiedAt}`) for a delete of a doc that isn't there, and take
  ids of `[A-Za-z0-9_-]{1,128}` (UUIDs).
- **Todos on an email** (as in Google's Inbox): an email has at most one todo, and it holds only its
  text: `users/{uid}/todos/{threadId}`, its doc id the Gmail thread's, with `threadId`, `text`,
  `createdAt`, `modifiedAt`, `serverModifiedAt` and `deleted`, and nothing else. Whether it is done,
  snoozed, pinned or in the inbox is its email's (archived, the thread's snooze, starred, the
  `INBOX` label), read from the email and never stored on the todo, so the two can't disagree and
  nothing keeps them in step. The rules refuse any other field on a doc with `threadId`, and a
  `threadId` other than the doc's id, so a second todo for an email can't be written; adding one
  where there is one edits it. latr's todos never carry `threadId`; latr's apps, once they read this
  backend, leave out every todo with a `threadId` (their lists, Done included), since its state lives on the email. The shape and the row's todo
  (a joined row's: the first of its threads, lead first, with one) are
  `shared/src/todos/linked.ts` and `:core` `todo/Linked.kt`, fixtures
  `shared/testdata/linked-todo-fixtures.json`.
- **Tags and labels.** A todo's tags stay part of its text (`#name`).
  A tag that names one of the user's own Gmail labels (user labels only, never Gmail's own or the
  categories) makes the todo act as if it carried that label, with nothing stored and nothing
  written to Gmail: the tag is drawn in the label's color; the label's list, and a search with
  `label:` for it, show the todo among the threads; and a todo on an email shows there in its
  email's row, though Gmail doesn't give the email the label (the email is read from the cache, or
  from Gmail, when the list doesn't hold it).
  So a todo of its own bundles through its tags: one whose tag names a label that bundles joins
  that label's bundle in the inbox, among its emails by date, and counts toward it as an email
  does; a bundle can hold todos alone. A todo whose tags name several such labels goes by the
  emails' rule (Labels, `bundle`): the bundle with the most items, todos counted. A pinned todo
  stays in Pinned, as a pinned thread does. An email with a todo bundles by its own Gmail labels
  and the labels its todo's tags name, together, by the same rule (an email labeled "M" whose todo
  says `#l` may join L's bundle, or M's, whichever has more), its todo inside its row there; a
  pinned one stays in Pinned. A closed bundle's row lists, after its senders, the first three
  subjects and todo texts in the bundle's own order, newest first, todos among the emails by their
  time, a blank one left out and a repeated one named once (`bundleLine`, the `line` cases of
  `bundle-fixtures.json`). Bundles are only the inbox's: the Todos view,
  Snoozed and Done list a todo as before. Nothing more is stored (`inboxSections` in
  `shared/src/list-model.ts`, the `todos` of `label-group-fixtures.json`). `-label:` leaves such todos out, and `label:` with
  any other mail operator lists no todos, as before. A tag names a label when their keys agree
  (`tagKey`): lower case, each run of anything but letters, digits, marks and `_` as one `-`, `-`
  and `_` trimmed from each part, so `#read-later` names "Read Later" and "Bills & Receipts" is
  `#bills-receipts`. A nested label keeps its `/` path: "Work/Projects" is `#work/projects` (not
  `#work-projects`, which Gmail's own `label:work-projects` would suggest; tags already take `/`). A
  label whose name has no letter (`2024`) has no tag. Labels sharing a key are all named, and the
  color is the first's by name (a label with no color of its own is gray, as its dot is). `#` in a
  todo, and in the search field, offers the user's labels as tags (in that form) after the tags in
  use, each drawn in its label's color. A `#name` that names no label is only a tag: typing it never
  creates a label. Settings › Labels (web and Android) lists, under "Tags without a label", the tags
  of the todos a label could bundle (todos of their own, not done; not email todos) that name none,
  one per key (the spelling most todos use, ignoring case, as first written), each with "Make
  label": it makes the Gmail user label named as the tag is written (`#grocery` makes "grocery",
  the nested `#a/b` the nested "a/b"), but with the leading parts that name a label spelled as that
  label is (`#work/meetings` under "Work" makes "Work/meetings"), which the tag then names, so its
  todos bundle once that label bundles (on by default). A tag whose name a label has already,
  Gmail's own included, isn't listed (`unlabeledTags`, the `unlabeled` cases of
  `label-tag-fixtures.json`). The section shows only once the labels have loaded (from the device
  or Gmail), so no tag is offered for want of them. When Gmail refuses the new label as a
  duplicate, the label it has by that name, ignoring case as Gmail does, is used (`labelNamed`,
  the `named` cases of `label-name-fixtures.json`).
  Renaming or moving a label in the app rewrites the tags naming it, and those naming labels
  nested under it, in every todo (`renameTags`; the todos are read once from Firestore's servers if
  Show todos is off). The new name is trimmed. Each rename waits in a queue on the device until its
  tags are rewritten, so one made offline (or whose read fails) is tried again, in order, when the
  app comes back online or into view.
  The logic is `shared/src/todos/label-tags.ts` and `:core` `todo/LabelTags.kt`, fixtures
  `shared/testdata/label-tag-fixtures.json` and the `label:` cases of
  `search-scope-fixtures.json`.
- **Account deletion** covers todos: `deleteAccount` deletes `users/{uid}` recursively. The server
  reads no todos but the one whose write runs `todoSnoozePing` (Notifications, below).

On the web, with Show todos on:

- **Lists.** Active todos sit among the inbox's threads by the time their key starts with, each its
  own row; pinned ones join Pinned. Snoozed shows snoozed todos by wake time, Done shows done ones.
  A Todos view in the sidebar lists active todos under latr's headings (no tabs: snoozed and done
  ones are in Snoozed and Done). Rows can't be moved by hand for now: the stored order, `planMove`
  and its fixtures stay, with nothing in the UI that drags a row. The Todos view also lists the todo
  of each email in the inbox and not snoozed, as a row of its own: a mail icon in place of the
  select dot and the todo's text, as a todo's row shows it, with no sender, subject or date. It
  is the email's row drawn as its todo: a tap opens the email (growing out of the row), and its
  swipes, selection, hover actions and keys are the email's, so Done, Snooze and Pin act on the
  email and so on its todo. It sits under Pinned when the email is pinned, else under the date
  heading of the email's date (its latest received message, or a woken snooze's wake time),
  newest first among the todos. While the email is open it stays where it sat when it opened,
  though it is pinned, archived or gets new mail, as an open row does in the inbox, until its
  close has played. Emails the device hasn't got are read from the cache, else
  Gmail; an otherwise empty Todos view shows Loading while they are read. The rule is `shared/src/todos/todos-view.ts` and `:core` `todo/TodosView.kt`, fixtures
  `shared/testdata/todos-view-fixtures.json`.
- **Rows.** A select dot, the text with `*bold*`, `_italic_` and tag chips (padded a little each
  side), its first line in Geist at the weight of a new email's subject (semibold; bold above it), edited in place (Shift+Enter for a
  new line, ⌘B/⌘I, known tags offered for the `#tag` at the cursor: a dropdown, or at phone width a
  row on top of the keyboard, as latr has it), a repeat line, and the hover actions; on touch,
  latr's swipes, a long press for the hold menu, and no buttons under the text being edited (the
  swipes and the hold menu cover them). A tap on the text opens it with the cursor where it landed;
  a tap elsewhere on the row (above, below or beside the text) opens it with the cursor at the end.
  Web addresses in the text are links that work as an email's do (see *Web addresses* under
  *Images and trackers*): a tap opens one rather than the editor, and a hold gives a link's menu
  rather than the row's. They are links in a todo row and in the open email's todo, not in the
  one-line todo under an email's snippet or an email listed as a todo (a tap there opens the
  email); while a todo is edited, or rows are selected (a tap selects), its text is plain.
  While a todo is edited on Android the list ends at the keyboard and keeps that todo in view, the
  last of a long list too; for 1.5 s after its edit starts it is brought back even from off screen
  (as when its field taking focus cuts the scroll to it short), unless the user scrolls the list
  meanwhile, by a drag, a mouse wheel or trackpad, the keys, or TalkBack. The open email's todo
  is a button under its text, with the text's links above it, so the keyboard and screen readers
  reach the button and each link. Text saves after a short pause, and as the page is
  hidden; blank text is never saved, and closing a todo left blank deletes it, with undo.
  While a todo is edited it keeps its place, and its saves change only its text. Its time
  (`modifiedAt`) changes once, as editing ends (focus leaves the text, Enter or Escape, or
  something else opened), and only if its text changed. Hiding the page or sending the Android app
  to the background only saves the text, leaving the editor open and its time as it was (so a tab
  closed or an app killed mid-edit keeps its text and its old time). When an edit that changed
  the text ends, the todo drops any place a move gave it, a row woken from a snooze leaves the
  snooze behind, and the row slides to its new place (with reduced motion it moves at once). A new
  todo keeps the time it was made. Every way out of editing goes through one path on each platform
  (`TodoState.setEditing` on the web, `TodoStore.setEditing` on Android); Enter's new todo goes
  above where the edited one lands. An email's todo follows the same rule. The rule is
  `todoEditEnded` (`shared/src/todos/typing.ts`, `:core` `todo/Typing.kt`) and
  `emailTodoEditEnded` (`linked.ts`, `Linked.kt`), fixtures `todo-typing-fixtures.json` and
  `linked-todo-fixtures.json`. Tag
  completion works anywhere in the line, the start included, and adds a space after the tag
  (`shared/src/todos/tags.ts`, fixtures `shared/testdata/tag-complete-fixtures.json`).
- **New todo** (`n`, or the button) puts a real, empty todo at the top of the inbox's list (or the
  Todos view's), open to edit and scrolled into view. It is saved once it has text and gone if left
  empty; until then it is a row like any other. Enter in a todo with text starts the next one just
  above it, the newer; Enter in an empty one, or leaving it, ends the editing. The
  on-screen keyboard's Enter key shows Next where Enter in a todo with text starts another, Done
  otherwise; it doesn't change while typing, since a change restarts the keyboard mid-word.
- **Snooze menu** is the mail one, with a Repeat step (every 1–7 days, weeks or months, at a
  24-hour time), and its picks feed the same suggestions.
- **Keys** as for threads on a focused todo (`e`, `b`, `p`, `o`, `#`), latr's ⌘D, ⌘S, ⌘K on the
  todo being written, focused or under the mouse, ⌘⌫ under the mouse, `g k` for Todos. With a
  thread open the keys are the thread's.
- **Undo** is the one slot mail uses: done (the repeat's next copy goes too), snooze, pin and
  delete. Making a done todo active again and setting a repeat have none, as in latr.
- **Search** lists matching todos (text with markers removed) among the threads, with no heading
  or count of their own;
  `is:todo` shows only todos, `-is:todo` none, `label:` those whose tags name the label (above).
  The terms that describe inbox state hold for todos too: `in:inbox` keeps those neither done nor
  snoozed, `is:done` the done ones, `is:snoozed` the
  snoozed ones, `is:pinned` (or `is:starred`) the pinned ones, each negated the others; any other
  mail operator leaves todos out. Emails follow the same rule: `in:inbox` lists what the Inbox
  shows (in Gmail's inbox and not snoozed), `-in:inbox` the rest, snoozed threads still in Gmail's
  inbox included, so `in:inbox is:snoozed` lists nothing and `-in:inbox is:snoozed` the snoozed. An email whose todo's text matches (words, tags, `label:`) is
  listed among the threads, read from the cache or Gmail when the search didn't bring it
  (`searchEmailTodos`, the `emailTodos` cases of `search-scope-fixtures.json`), and those terms
  are held to the email's own state by the search's filter, as for any thread; `is:todo` lists
  them with the todos of their own.
- **Todos on an email.** The email's row is the normal one with one line more under the snippet,
  above a dashed rule in the border color: the todo's text on one line, with no tag beside it
  (screen readers and TalkBack hear it as "Todo:" and its text). The line has no mark, pin or clock and no time of its own, since the
  row shows its email's state, and no actions: a click on it opens the email, and the row's
  actions, swipes, selection and keys are the email's alone. The open email shows the same line at
  the top of its messages, its text opening to edit on a click. The open thread's action is Add
  todo, or Edit todo once it has one (adding one to an email that has one opens that one); a new
  one is saved once it has text, and removing the text deletes the todo, with undo, the email left
  as it is. Done, Snooze, Pin, undo, a reply ending a snooze, a notification's Done and an archive
  made elsewhere change only the email, and so its todo with it.

On Android, with Show todos on (Settings, Inbox), the same lists, rows, snooze menu, undo and search
as the web, from the same `:core` logic (`todo/` ports `list.ts`, `tags.ts` and `styled.ts` and runs
their fixtures), and:

- **Rows.** A tap opens the text to edit, with no buttons under it (as on the web at phone width:
  snoozing and waking are the rightward swipe, Pin and Delete the hold menu, from a long press on
  the row or its select dot);
  the keyboard's action key (or a typed line break) acts as Enter does on the web, and shows Next
  or Done as on the web. Known tags show
  as a row on top of the keyboard while a `#tag` is typed. A swipe right snoozes (or makes a done or snoozed todo active again), left marks
  it done (or deletes a done one).
- **New todo** is a small button above Compose, as on the web, taking taps on a 48 dp square around
  it. The drawer lists Todos after Done.
- **Reminders** when a todo's snooze ends, as for threads (Notifications, below); a todo on an
  email has none, its email's reminder is the one.
- **Todos on an email** as on the web: Add todo (or Edit todo) on the open thread's corner button, the
  todo's line under the snippet of its email's row and at the top of the open email's messages,
  with no actions of its own, and the Todos view's row of its own for an active email's todo.

How each latr field was copied by the one-time import (done 2026-11-21; the script is removed):

| latr | inbox | Notes |
|---|---|---|
| `users/{latrUid}/todos/{id}` | `users/google:<sub>/todos/{id}` | `<sub>` is the Google account id in latr's Auth `providerData`; ids kept, so a second copy overwrites |
| `text` | `text` | as is, markers and tags kept; tombstones written before latr blanked them keep their text |
| `state` | `state` | `"DONE"` kept; latr's old `"SNOOZED"` (and anything else) is `"ACTIVE"`, as latr reads it |
| `createdAt`, `modifiedAt` | same | epoch ms; a missing `createdAt` takes `modifiedAt` |
| `serverModifiedAt` | same | latr's time kept; a missing one takes `modifiedAt` |
| `snoozeUntil`, `occurrence` | same | latr's Android app saved `YYYY-MM-DDTHH:mm` (no zero seconds) or fractions; written as `YYYY-MM-DDTHH:mm:ss`, the same wall time |
| `pinned`, `repeatSpawned` | same | missing (older docs) is `false` |
| `position` | `position` | kept when it is a key; latr ignores one that isn't, so it is dropped |
| `deleted` | `deleted` | tombstones copied too; a missing or non-boolean one is `true`, since latr's query hid such a doc (reported) |
| `repeat` | `repeat` | `{every, unit, weekday, monthDay, at, anchor}` kept; one latr can't read is dropped |
| `users/{latrUid}.snoozeVotes`, `.snoozePickLog`, `.snoozeStats`, `snoozePickLog/global` | — | learned snooze suggestions, not copied |

The import never wrote to latr.

### Sending, scheduled send, undo send

All sends go one way:

1. The client builds the full MIME message on the device: the typed text → HTML + plain text,
   `multipart/related` for inline images (`cid:`), `multipart/mixed` for attachments, signature,
   `In-Reply-To`/`References` for threading (forwards too, so they stay in the thread;
   `References` is the answered message's `References` with its `Message-ID` added, never cut), an
   `Autocrypt` header, and PGP/MIME if encrypting.
   The body is rich text that markdown turns into as it is typed (latr's `*bold*` and
   `_italic_`, plus strikethrough, code, links, headings, lists, quotes and code blocks; Tiptap on
   web, Compose Rich Editor on Android); a web address (by the same rule as todos and plain-text
   mail, `shared/src/web-links.ts`, so `example.com` links to `https://example.com`) becomes a real
   link once a space or Enter follows it, not inside code or a link; on web the link is a step of
   its own after the space, so ⌘Z/Ctrl+Z or Backspace right after takes it off and keeps the space
   (Android's editor has no undo; Remove in the link's menu does it). The composer writes every link
   out as `[text](url)` in its markdown and reads that markdown back without making bare addresses
   links. The plain-text part of a draft or sent message, though, writes a link whose text is just
   its address (ignoring an `http`, `https` or `mailto` scheme, a trailing slash and case) as the
   bare address, unless read back the address wouldn't be found there (it runs into a word); other
   links stay `[text](url)` (`plainLinks`). A reopened draft (web and Android) takes its links back
   from its HTML part: a bare address in the text becomes a link only where the HTML links it,
   matched in order among that address's appearances, so an address the user took the link off
   stays plain and stored drafts read back as they were (`draftLinks`). Markdown from elsewhere (a
   signature, shared text) still reads a bare address as a link to itself (`finishedWebLink`,
   `markdownToRich`'s `bareLinks`, `rich-fixtures.json`). A link
   leads only to an `http`, `https` or `mailto` address, its scheme put in lower case (`safeHref`):
   any other (`javascript:`, `data:`) is never made a link by typing, the dialog, loaded markdown or
   pasted HTML, never written into the sent HTML, and never opened. `![alt](cid:id)`
   places an inline image. Links: a Link button beside
   Bold and Italic (⌘K/Ctrl+K on web) opens a small dialog: with the cursor in a link it edits that
   link's text and address, with text selected it asks for the address and links that text, and
   with nothing selected it asks for both and puts the link in at the cursor; an address without a
   scheme gets `https://`, empty text takes the address, and an address with another scheme can't
   be saved (the dialog says why). On web the dialog sits outside the composer's form, so its Save,
   Enter and Escape are its own: Enter saves, Escape closes only the dialog. Only the cursor in a
   link or at either of its edges counts as in it. A hold on a link (and a right click on
   web, or the menu key with the cursor in one) opens the round menu, laid out as a row's, with Open,
   Edit (the same dialog) and Remove (back to plain text, the words kept); a tap still places the
   cursor. TalkBack lists Open link, Edit link and Remove link among the field's actions. Web runs
   Tiptap's commands (`rich-doc.ts`), Android the same edits on a line's runs (`:core`
   `compose/RichLinks.kt`); both pass the `links` cases of `rich-fixtures.json`. The HTML part comes from the rich text; the plain-text part is it as markdown,
   markers back in, with the signature after a `-- ` line. A reply
   quotes the message it answers below the signature, the way Gmail does: "On Mon, Nov 2, 2026 at
   11:50, Maya Chen <maya.chen@gmail.example.com> wrote:" (24-hour), then the original in Gmail's
   `gmail_quote` blockquote in HTML (so Gmail folds it) and as `> ` lines in plain text. Clients
   show a quote folded to "…", in the composer and in threads; a thread shows the message above it
   without the blank lines (`<br>`s, empty divs) mail puts before a quote. Pure code
   in `shared/src/compose` (web uses it, Android ports it), pinned by
   `shared/testdata/compose-fixtures.json`. Threading copies only the `<id>`s of the answered
   message's headers (none at all when its `Message-ID` holds no `<id>`), and the builder refuses
   any header value with a control character but tab, so no copied value can add a header.
2. It saves it as a Gmail draft (`drafts.create`, 35 MB limit — queued if offline) and writes
   `outbox/{id}` with `sendAt` (the press of Send + undo window, or the scheduled time). A draft Gmail already
   holds as it stands (opened and not edited, or saved since the last edit) and that isn't
   encrypted isn't uploaded again while online: its doc is written at once. Until the draft is
   saved there is no doc, so a send made offline is held on the device: the Outbox lists it (and
   counts it) with its recipients and subject from the device's copy and "Waiting for a
   connection" in place of its time, sent-now ones with those going out and scheduled ones by
   their time. Its one action is Cancel, which drops the send and keeps the message as a draft
   (saved to Gmail once online), as canceling a send with a doc does. For listing the Outbox in the menu it counts
   as a `waiting` doc due when it was meant to go (`shared/src/outbox-held.ts`, fixtures in
   `shared/testdata/outbox-held-fixtures.json`).
3. A Cloud Task fires at `sendAt` (`outboxChanged` queues it on `outboxSend`'s queue; sends more
   than 29 days out run as a chain of legs) and, if the outbox doc still says
   `waiting` for that time, claims it (`sending`, in a transaction), reads the draft raw and checks
   its `From` is the doc's `from` and an address the user owns:
   - **from the Gmail address or a send-as alias Gmail has verified** (`settings.sendAs.list`,
     primary or `accepted`): `drafts.send`.
   - **from @hafa.cc**, held by this user in `addresses/{name}` (not `users/{uid}.address`, which
     clients can write): sends the raw message through Resend SMTP (so PGP/MIME survives) with
     `Date` set to the send time, a `Message-ID` kept or added, and `Bcc` left off; then
     `messages.insert`s the copy (with `Bcc`) into the thread with `SENT` and deletes the draft.
     It also refuses a `From` whose name or comment holds an email address, and a `Sender` or
     `Reply-To` naming another @hafa.cc address.

   On both routes it refuses (`rejected`, with a plain sentence the Outbox shows) a draft whose
   header holds a control character but tab (a stray line break among them), an `In-Reply-To`
   or `References` with anything but `<id>`s, or more than one `To`, `Cc` or `Bcc` line.

   **From the device.** A send now still on screen (Android: the app in the foreground; web: the
   tab visible) and online when its undo window ends goes from the device itself, which skips the
   task's 1–3 s cold start; the task stays the backup. Only a send of one doc (no Bcc copies), from
   the Gmail address or an alias verified in the device's send-as list (never @hafa.cc), still
   `waiting` at the `sendAt` the device wrote with nothing but the client's fields (a doc that
   carries the server's attempt record goes the usual way), and at most 30 s late
   (`shared/src/direct-send.ts`, fixtures in `shared/testdata/direct-send-fixtures.json`; web
   `web/src/lib/compose/direct-send.ts`, Android `:core` `send/DirectSend.kt`). The device reads
   the draft's headers and checks its one `From` is the doc's, as the server does (anything it
   can't read plainly is left to the server); then claims the doc in a transaction, recording the
   handover in the same write: `sending`, `claimedAt`, `firstAttemptAt` and `inFlightAt` at the
   write's time, `via: "gmail"`, `draftMessageId`, `messageIdHeader` and a random `deviceClaim`.
   The rules allow that only from a `waiting` doc holding only the client's fields, not from
   @hafa.cc, and from 30 s before to 60 s after its unchanged `sendAt` by the server's clock (the
   device's 30 s, widened for clock skew either way). Unless the claim took over 60 s, it calls
   `drafts.send` once, waits at most 30 s for the answer, and records the result as the server
   does: `sent`
   with `sentAt`, `messageId`, `threadId`, `filed: true` and `rejected: []`; or, for a refusal the
   server fails at once (a 4xx other than 401, 404, 408, 429 and a 403 rate limit), `failed` with
   `rejected`. Each result write carries the device's `deviceClaim` as it stands, and the rules
   allow it only while that claim does: a result from an older claim would change `deviceClaim`
   and is refused. Anything else (a limit, a server error, the draft gone, or no answer within
   the 30 s) isn't retried or recorded on the device: the doc stays
   `sending`, the task finds it held and checks back when the lease ends, and its takeover settles
   it by the Gmail rules below (the draft still there means nothing went, so it sends; gone, it is
   found in Sent and recorded). An answer that comes after the 30 s is still recorded as above,
   under the device's `deviceClaim`, so a send Gmail confirms late doesn't show as sending for
   minutes; the device never sends it again. A server claim deletes `deviceClaim`, so a device that
   comes back late can't write over the server's attempt. A Gmail send keeps no Sent copy or Resend key, so
   nothing else follows it.
4. The doc is an outbox: it stays `sending`/`retrying`/`blocked` until the message has actually
   gone, then becomes `sent`. A failure that retrying might fix (network, Gmail 429/5xx or a 403
   rate-limit reason, SMTP 4xx, Resend rate and daily limits, a token refresh that fails) moves it
   to `retrying` with `lastError` and `nextAttemptAt`; the server queues its own Cloud Task for
   the next attempt. It retries only 3 times, after 1, 5 and 30 minutes: a passing glitch clears
   in that time, and anything longer is better shown to the user than retried out of sight. If
   the last retry fails too, the send ends as `failed` (`unavailable`) and the Outbox offers "Send
   again".

   Problems retrying can't fix but that someone can fix don't use up retries: the send moves to
   `blocked` with `blockedOn` and waits, and the server resumes it (back to `waiting`, retries
   reset) once fixed.
   - `sign-in`: Google access was revoked (Google answered `invalid_grant` and the stored refresh
     token was deleted) or none is stored. `signIn`, after storing a fresh refresh token, resumes
     that user's sends blocked on sign-in.
   - `resend`: `RESEND_API_KEY` isn't set, or Resend's SMTP refused the login (535). Fixing either
     means setting the secret and redeploying, so `resumeResend`, a scheduled function every 30
     minutes, resumes them: it runs one collection-group query on `blockedOn == "resend"` (an
     empty read while none are blocked), and only when it finds some does it log in to Resend's
     SMTP once (`verify`, no mail sent) and resume them if that works. Waiting for the next
     Resend send instead would leave them stuck when no one sends.

   Refusals no one can fix end at once as `failed`: the draft deleted, its From not the doc's or
   not the user's, a permanent refusal from Gmail or the SMTP server (bad or rejected recipients,
   message too large). The user can cancel a retrying or blocked send by deleting the doc; a retry
   finding no doc stops. The rules refuse deleting a `sending` doc, since the attempt would go on
   without it. "Send again" rewrites the doc as `waiting`, which drops the server's
   fields; `outboxChanged` puts back the attempt record of the doc it replaced (same draft) before
   queueing the send, so the rules below hold for a manual resend too.

   **Never twice.** Each attempt claims the doc in a transaction (`sending`, a 5-minute lease; a
   second task finding it held queues a check for when the lease ends), and records what it is
   about to hand over (`inFlightAt`) before handing it over. A device sending a send now itself
   claims it the same way, under the same lease. Taking over a dead attempt (lease
   ended) counts as an attempt, so a send whose every run dies ends as `failed` (`unavailable`)
   once the retries are used up. A Gmail send reads only the draft's headers (its From is
   checked); the whole draft is read only for Resend.
   - **Gmail:** `drafts.send` consumes the draft, and the sent message keeps the draft's Gmail
     message id. An attempt whose result is unknown (a lost response, a 5xx, a dead attempt) is
     settled by the next one: if the draft still exists, nothing went, and it sends; if the draft
     is gone, it looks the message up by that id (`SENT` label), then by `rfc822msgid:` in Sent,
     and records it as sent; if neither finds it, the user deleted the draft (`draft-missing`).
   - **Resend:** every SMTP send carries a `Resend-Idempotency-Key` header (Resend's SMTP form of
     its API's `Idempotency-Key`; it keeps keys 24 hours and answers a repeat of the same key and
     content with the first result instead of sending). The key, the `Date` and the `Message-ID`
     are stored before the handover. When SMTP answered with an error, nothing went: the next
     attempt uses a new key and a fresh `Date`. When the outcome is unknown (the connection broke
     after the message started going, or the attempt died), the next attempts resend the identical
     message under the same key, and only within 23 hours of that handover (also after the
     retries run out and the user sends it again, or after it was blocked). The key, `Date` and
     `Message-ID` are also kept by draft (`private/{uid}/resendHandovers`, gone after a day), so a
     doc deleted mid-retry and made again for the same draft reuses them; a clean refusal or
     filing the Sent copy forgets them. Past 23 hours, or if
     Resend says the key was already used for different content, the send ends as `failed`,
     `uncertain`, rather than risk a second copy. Sending again after `uncertain` goes as new: the
     user was told it may already have been delivered.

   The guarantee: a message is handed to Gmail at most once, given Gmail's own behavior above.
   Through Resend it is delivered at most once given Resend honors the key as documented; the one
   case that can't be settled (an unknown outcome not resolved within 23 hours) is reported as
   `uncertain`, never resent. Editing a draft while its send is `sending` or `retrying` is not
   offered (it would change the content under the same key). If filing the Sent copy fails after
   Resend accepted, the server keeps the exact message that went (server-only, until filed), deletes
   the draft, and only the filing is retried: by Cloud Tasks, then by `sweepStuck` every 30 minutes
   and at the next sign-in. The copy is kept only while its outbox doc exists (else it is filed
   at once if Gmail lets us, and nothing is kept); the sweep puts off a copy it can't file (by 6
   hours while its owner has no Google access) so it never holds up the rest, and deletes it
   unfiled once the account is gone or 14 days pass without Google access. The draft's From must be one header naming one mailbox. `sweepStuck`
   also re-queues sends whose task never ran, and claims whose lease ended with no task left to take
   them over (a device that claimed a send now and died before the task fired).

Schedule send has its own learned suggestions, built the same way as snooze's but from its own
history (`meta/sendStats`), so send habits and snooze habits don't mix. Until the user has
history the menu offers next 08:00, next 13:00 and Monday 08:00 ("Tomorrow morning", "This
afternoon", "This Monday morning"); learned times take their places as they come in. A setting, "Vary scheduled send
times" (on by default), shifts each *suggested* time by a random amount when the menu is shown, so
the menu itself reads 08:07 rather than 08:00 and mail doesn't land on the exact hour. A slider sets
the largest shift, 1 to 30 minutes either way (default 10). The shift is a whole number of minutes
and never 0. Stored as `settings.varySendTimes` (off only when `false`) and
`settings.varySendMaxMinutes`. A time typed
into the picker is used as is. `sendAt` is the shown (shifted) time; `chosenAt` is the unshifted
suggestion, and only `chosenAt` feeds `sendStats`.

Undo send is just `sendAt` = the press of Send + 10 s (never varied, and not restarted when the draft reaches
Gmail); undo deletes the outbox doc. Scheduled mail is visible and
editable in the Gmail draft until it goes. In the app it shows only in the Outbox: Drafts, its count and
a thread's drafts leave out every draft an outbox doc not yet sent names (`shared/src/outbox-drafts.ts`,
fixtures in `shared/testdata/outbox-draft-fixtures.json`), and canceling the send brings it back to Drafts.
A message is never in both at once. Gmail's listing of drafts, which says which thread and message
each draft is, lags: so a send the device holds or just wrote a doc for leaves Drafts by what the
device knows of its draft (draft id, message id, thread), and a doc newer than the listing leaves
by the thread it names. The doc names the draft's thread for a new message too (its own thread),
not only for a reply.

On Android, other apps can start a message: a `mailto:` link (opened or sent to, `ACTION_VIEW` and
`ACTION_SENDTO`) or a share (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) opens the composer with its To,
Cc, Bcc, subject and body filled in (`:core` `compose/Prefill.kt`: addresses that aren't deliverable
are dropped, the subject is one line, a share's text follows a link's body) and the shared files
attached; only files another app hands over (`content:` URIs not this app's own) are attached.
Addresses may come as a string array, a string list or one string. Shared text is read as
markdown; when the share carries HTML too (`EXTRA_HTML_TEXT`), that is used instead and keeps
whatever formatting the composer has (bold, italic, links, lists and so on), read as the composer reads pasted
HTML (`htmlToMarkdown` in `RichBody.kt`), falling back to the text if it comes out empty. A file is read off the main
thread and only while it fits in a message (Gmail's 35 MB, encoded); a bigger one is left out with
a notice. The request comes to the app's one window in its own task, never a second copy inside
the other app's, and reopening the app from Recents doesn't start the same message again. A
message already open in the composer is kept as a draft first. Signed out, it opens once signed in.

Holding the Android app's icon offers three shortcuts (`res/xml/shortcuts.xml`, `LauncherShortcut.kt`),
each with its button's icon in the accent on the paper color (one color for themed icons): New email
opens a blank composer, New todo a new todo at the top of the inbox, open to edit (offered only
while Show todos is on; turned off, a copy pinned to the home screen greys out with "Todos are
off", and comes back when todos are turned on), and Search the search bar, empty and taking the keyboard. Each shows its
action on top of whatever was open: a message being written is kept as a draft, and an open thread,
the selection and the menu drawer close. Signed out, sign-in shows first and the action follows once
the account's mail is ready. A shortcut acts once: not again on a rotation or when the app is reopened
from Recents.

The composer's From, To, Cc, Bcc and Subject lines: a tap anywhere on a line, its label too, puts
the cursor in its field, and Backspace in an empty address field takes off its last recipient (on
Android a zero-width character before the cursor gives the soft keyboard's Backspace something to
delete). A recipient's cross takes a 48 dp/px tap. On Android a turn of the phone keeps the
addresses being typed and the field with the cursor (kept on the composer's session), and the
snooze and schedule sheets keep their step; their menus scroll when they don't fit.

**Parking a message on a phone** (Android, and the web at phone width). The full-screen composer (a
new message, a reply or a forward) can be put aside: dragging its top bar down 96 dp (px), or less
but moving down at 800 dp/s or more when let go, or its Minimize button (a down chevron: on
Android beside Close, on the web after the save status), slides it down until its top bar lands
on a strip at the bottom of the screen, 56 tall above the navigation (home) bar, that names its
subject ("Re: Lunch Friday"; with none, "New message", "Reply" or "Forward"); what was under it,
the thread or the list, can be used again. A shorter, slower drag springs back, and only the top
bar drags, so scrolling the message never parks it. Parking closes the keyboard and saves the draft
to Gmail at once, as autosave would (queued offline). A tap on the strip grows the composer back
out of it exactly as it was: what's typed, the recipients and addresses being typed, the files,
and the cursor in the field that had it, with the keyboard. With reduced motion (animations off on
Android) both happen at once. TalkBack and the keyboard reach Minimize and the strip as buttons
("Draft: Re: Lunch Friday"; on the web focus moves to the strip). The strip is no layer: Back passes
it by and closes what is under it as usual, and Back in the reopened composer closes it as before,
keeping the draft. The strip sits at the very bottom, and everything else at the bottom of the
screen (the round corner buttons, the undo and sending bars, the selection bar, the ends of lists
and of the open thread) sits above it while it shows (web `--safe-bottom` in `app.css`); the menu
drawer, sheets and menus cover it. One message is parked at a time: opening another composer
(Compose, Reply, a draft, a shortcut or a share) keeps the parked one as a draft, with the usual
"Saved to Drafts" bar whose Open brings it back, and the strip goes; opening the parked message
itself (its draft in its thread or in Drafts, or Reply on its thread) opens it from the strip.
On a phone, opening an email always shows the email, never a composer over it: a parked reply stays
in the strip, and a reply's saved draft shows as its row in the thread, opening full-screen only
when tapped. In Drafts, a click or tap on a row (or Enter on it) opens its draft in the composer
right away, a reply's too, and leaves its thread closed (`draftsOpening` in
`shared/src/thread-drafts.ts`); a row whose draft is gone opens its thread.
Sending and discarding from the reopened composer work as always. A turn of the phone keeps it
parked; the web wider than a phone has no strip, so a parked message opens again, floating (or
in its thread when that is open). The strip doesn't outlive the app (a kill, a reload): the message
is then a draft in Drafts and its thread. Shared rules in `shared/src/compose-park.ts` (fixtures in
`shared/testdata/compose-park-fixtures.json`, Android `:core` `compose/ComposePark.kt`).

Send-as limits: new Gmail send-as addresses can't be created through the API on personal accounts;
users add them in Gmail settings and we pick them up. Resend free tier: 100/day, 3,000/month across
all users (Pro is $20/month).

### Search

Online: Gmail's `q` syntax from the client. Offline: plain-text search over the local cache.

- The search field parses operators as you type (`from:`, `to:`, `in:`, `label:`, `has:`, `is:`,
  `subject:`, `filename:`, `after:`, `before:`, `older_than:`, `newer_than:`, plus our own
  `is:snoozed`, `is:todo`, `is:pinned`, `is:done` and `in:outbox`) and turns each finished one into a
  chip in the field. `is:pinned` goes to Gmail as `is:starred`, and `is:done` (anything archived:
  mail out of the inbox, and done todos) as `-in:inbox` (`gmailQueryText`); `-in:inbox` itself
  doesn't go to Gmail, which keeps snoozed threads in its inbox, and the list's filter (`viewFilter`)
  holds it, as it does `in:inbox`. With todos shown, a
  finished `#tag` turns into a chip too, drawn like a todo's tag (in its label's color when it names
  one). A `#name` naming a user label (by `tagKey`, any case) searches exactly as that label's
  `label:` term does: its mail and the todos tagged with it, with the rest of the query as before
  (`searchTagTerms`). The search box, the address and recent searches keep `#name` as typed; it
  becomes `label:` only where the search runs (Gmail's query, `searchScope`, `searchTodos`, the
  list's filter, offline and encrypted search), so a reopened recent search or address runs the same,
  and a search shown runs again once labels load if they change what it names. Mailboxes and states
  have no `#name`: `#inbox` is a todo tag like any other. A `#tag` naming no label names only todos'
  tags: it keeps the todos (and emails' todos) with that tag and lists no other mail, as do `#tag`s
  joined by `OR` (`#home OR #work`, either tag), and a negated one (`-#home`) keeps only the todos
  without it (`searchTodos`); the rest of the query still searches as before. The help (Settings,
  Shortcuts & search; Android's search help) lists every operator and our own terms (`OWN_TERMS`).
- Tab completes the operator or value under the cursor: `in:` → mailboxes, `label:` → labels,
  `from:`/`to:` → Google Contacts and recent senders, `has:` → attachment/drive/…, dates → a
  short list (today, yesterday, last week); with todos shown, `#` → the todos' tags. Backspace on a
  chip turns it back into text. Enter on an operator with nothing after its colon completes the
  first suggestion, or with none searches without that operator.
- A tap on a chip puts the cursor at the end of the field, as a tap anywhere else in the box
  does; only a chip's cross (a 48 dp/px tap) removes it. The box's cross empties the field and
  chips and keeps search open, focused, with the keyboard up. Closing search (its Back arrow,
  Back, or Escape on the web) returns to the view search was started from, not always Inbox
  (`searchOriginAfter` on the web, `MailState.closeSearch` on Android). Android keeps what is typed
  across a turn of the phone. The chips scroll sideways when they don't fit.
- The chip parser and completions live in `shared/` fixtures so web and Android agree.

### Spam

Mark spam = add `SPAM`, remove `INBOX`; not spam = the reverse. A Spam view lists Gmail's spam. Filters
can delete or "never send to spam". Whether API spam-marking trains Gmail's filter is undocumented.

### Unsubscribe

Client reads `List-Unsubscribe` / `List-Unsubscribe-Post`:

- One-click (RFC 8058): `unsubscribe` function POSTs it (browsers can't, CORS). The client passes
  the Gmail message id with the URL; the function reads that message's headers from Gmail and
  POSTs only to an `https:` URL the message really lists, never to private addresses (checked at
  connect time and on each 307/308). Contract: [images-contract.md](images-contract.md).
- `mailto:` only: sends the request mail through the normal send path.
- URL only: opens it.

The Unsubscribe control itself shows the request working, then the result: Unsubscribed when the
sender took the one-click POST, Request sent once the request mail is queued, or a failure with
Retry: "Sign in again to unsubscribe" when Gmail access has to be granted again, "Can't unsubscribe
offline" with no connection, else "Couldn't unsubscribe". A failed one-click also offers Open page
when the header lists a sender's page. The control is a banner like the hidden-images one, under the
message header, the spinner and then the result inside it. Each message's progress and result live
in the mail state for the session on both platforms: folding the message, reloading the thread or
closing and reopening it keeps them, and a press while the request is under way sends nothing more.
Opening a link changes nothing, since only the sender's page knows the
result. Nothing else is offered afterwards.

### Notifications

FCM to every device in `users/{uid}/devices` — Android natively, the web app through its service
worker. On the web, notifications carry Done (runs without opening the app) and Reply (opens the
thread). On Android they carry Done, Snooze (to the first time the snooze sheet suggests, named on
the button, e.g. "Snooze until 18:00"; no button while the sheet has no learned suggestion) and an
inline Reply, all working with the app closed: the
reply goes to the thread's last message through the drafts and outbox like any send, with the 10 s
undo shown on the notification; when it can't go, a notification says so and opens a reply with the
text. Android groups new mail and reminders apart (each its own channel and summary). While the app
is on screen it refreshes instead of notifying. Gmail's watch only reports changes that touch
`INBOX`; other changes are read from history at the next push.

An Android notification goes once its thread is opened, done, snoozed or read in the app, or once
the app's mail shows it read, out of the inbox or snoozed. For changes made elsewhere,
`gmailChanged` also reads `labelRemoved` history: threads where a message lost `UNREAD` or `INBOX`
(but for those it notifies about in the same run) go to the account's Android devices (device docs
with `platform: "android"`) as a `{ kind: "clear", account, threadIds }` data message, up to 100
comma-separated ids each, and the phone takes those notifications away. Web devices get none: a web
push must show a notification.
With "Skip notifications for bundled mail" on (`settings.quietBundles`, off by default; web and
Android settings), `gmailChanged` sends nothing for new mail that counts as bundled: its thread
(every message's labels) has a label that bundles, a user label or a category other than Primary
without bundling turned off in `labelPrefs`, and isn't pinned (starred). That holds however many
inbox threads carry the label, even when this is the only one, and whatever the snoozes
(`bundledOnArrival` in `shared/src/bundles.ts`, fixtures in
`shared/testdata/arrival-bundle-fixtures.json`; functions imports the same code).
While Show todos is on, the labels a thread's email todo's tags name count too, as the apps bundle
by them: the todo (`todos/{threadId}`) is read only when the thread's own labels don't bundle it,
it isn't pinned, and the account has a user label that bundles (Gmail's label list, read at most
once a push, says). When the check, the todo or the settings can't be read, the mail notifies.
Each send is logged (`push sent`: its kind, how many devices, how many FCM took and refused, and
the error codes; never the mail or an address), and each run with new mail logs how many messages
it looked at, how many were already read or archived, how many landed in a bundle (with the
setting on), and how many devices it had.
@hafa.cc mail notifies as soon as Cloudflare hands it over; other Gmail mail as soon as Pub/Sub
delivers (usually seconds).

When a snooze ends, the phone posts the notification itself; the server does no time math and
schedules nothing, it only tells a phone when a snooze change concerns it:

- **The phone waits on one snooze.** A check reads the pending snoozes (`threads` whose
  `snoozeUntil` is no earlier than a day before now and, while todos show, todos'): from the open
  app's listeners while each is following without error, else one query each. It notifies those that
  ended since the last check (not while the app is on screen, nor one that newer mail already woke,
  as that mail was notified, nor, with "Skip notifications for bundled mail" on, one that counts as
  bundled: the thread isn't pinned and one of its labels, as the device's cache last saw them, or,
  while todos show, of those its email todo's tags name, bundles, however many inbox threads carry
  it; `bundledOnWake`, the `wake` cases of
  `arrival-bundle-fixtures.json`), sets one inexact alarm for the earliest end still ahead, and writes its
  own entry in `meta/snoozeWaits`, keyed by its push token: `{ itemId, snoozeUntil, updatedAt }` for
  that snooze (`updatedAt` the server's time), or `{ updatedAt }` alone when nothing is pending. Once
  the server has the entry, the phone reads the snoozes again, from Firestore rather than its
  listeners, which may not have another device's write yet: the server skips pinging for writes
  older than `updatedAt`, so one made between the first read and the entry is caught here (the
  phone writes its entry again if it changes what to wait on, up to three times). Each phone writes
  only its own entry, by field path, so phones never overwrite each other. "Ended since the last
  check" goes by instants, but after a time zone change by wall time (the last check's in its old
  zone), so flying east skips no snooze and flying west repeats none. A check runs when the alarm
  goes off, on the server's ping, at boot, after an app update and on clock or time zone changes,
  when the app sees the snoozes change, and once the phone has its push token after sign-in; one
  that can't read the snoozes or write its entry runs again within 15 minutes.
- **The server pings only the phones a change concerns.** `snoozePing` runs on each write to
  `threads/{id}`. When `snoozeUntil` didn't change (a watermark write, the same time written again)
  it exits before reading anything. Otherwise it reads the one `meta/snoozeWaits` doc and sends a
  `{ kind: "snooze" }` data message to each phone whose awaited snooze is this one and no longer
  says the same time, and to each phone whose entry predates the write and waits on a later time or
  on nothing. Wall times compare as strings, so no time zone is involved. That is one read per
  snooze change, none for other writes, plus a read of the device docs and a delete of the entries
  of phones whose device doc is gone or whose push token FCM no longer knows, so phones that left
  don't hold the 10 entries the rules allow. Removing a device doc for a refused token clears its
  entry too. Pings go at normal priority: they only reset an alarm. A phone's own snooze writes may ping it back; that only runs a check, which changes
  nothing.
- **Todos use the same mechanism**, with `todo:<id>` as the `itemId`. While Show todos is on, a
  check also reads the live todos not done (snoozed no earlier than a day before now) and waits on them; `todoSnoozePing` runs on each write
  to `todos/{id}` and pings as `snoozePing` does when the snooze phones wait on (none once the todo
  is done or deleted) changed. A todo's notification shows its text (markers removed) and "Back
  from snooze", with Done (written straight to Firestore); a tap opens the Todos view scrolled to
  the todo, highlighted for a moment. With "Skip notifications for bundled mail" on, none shows for
  a todo that counts as bundled, as for a thread: it isn't pinned and a tag names a label that
  bundles, however many items carry that label (`bundledOnWake`, the `wake` cases of
  `arrival-bundle-fixtures.json`). With the app closed, only the snoozes already over are judged
  (their threads' cached labels, the label prefs, their todos), the rest when their alarm comes.
- Sign-out cancels the alarm, forgets the last check, and removes the phone's entry with its
  device doc. The web app gives no reminder while it is closed; open, it just shows the thread
  again.

### Images and trackers

Message HTML is cleaned on the device (DOMPurify on web, an equivalent allow-list on Android, same
fixtures), shown in a sandboxed frame with a strict CSP, scripts and forms off. Cleaning drops the
top margin of a body's leading paragraph and the bottom margin of its trailing one (looking through
plain `div`-like wrappers), so mail written as paragraphs (Thunderbird's) gets no extra space above or
below (`sanitize-fixtures.json`).

**Web addresses.** A plain-text body and a todo's text show their web addresses as links:
`http://` and `https://` URLs, and bare domains ending in a known top-level domain with an optional
port and path (`example.com/x`; `www.example.com` is just a bare domain, opened as `https://`). The
top-level domains are a fixed list of common ones without those that are also common file
extensions or words (`md`, `py`, `sh`, `rs`, `pl`, `zip`, `mov`, `is`, `to`), so `notes.txt`,
`README.md` and version numbers stay text. None starts inside a word, an email address (left as
text, so `ana.co+news@x.com` links nothing) or a `#tag`; a style marker closing around a bare domain
ends it (`_example.com_`); trailing punctuation and unmatched closing parentheses are left out (`see
example.com.`, `(example.com/x)`), balanced ones kept. Redirect wrappers come off as in HTML mail, unless what they wrap isn't a
web or mail address; a scheme is put in lower case (`Https://` opens), and Android says so when no
app opens a link.
Markers and `#` inside an address don't style it, while a style around one does. They behave as
links in HTML mail: on web a click opens a new tab and a hold or right click gives the browser's own
menu; on Android a tap opens the browser and a hold opens the link disc (Open, Copy link, Share),
with TalkBack offering the same. `findWebLinks` in `shared/src/web-links.ts` and `:core`
`mail/WebLinks.kt`, fixtures `shared/testdata/web-link-fixtures.json`; a todo's links are
`styledTree`'s link nodes (`todo-list-fixtures.json`). The composer's bare URLs follow the same
rule.

- Fixed-width layouts wider than the view (a 600px newsletter table on a phone) are narrowed to fit
  it, so the mail wraps rather than scrolling sideways: each table, cell, `div` or `center` wider than
  the view is set to fill it. The web measures the widths in the frame once it is drawn; Android's
  view runs no script, so it reads the widths the HTML states (`width` attributes, inline `width` and
  `min-width` in px, a table's first row of fixed cells) against the view's width before showing it
  (`fitWideLayouts`). Widths set only in a `<style>` sheet aren't seen on Android.

- Remote images are blocked until shown: per message ("Show images"), per sender ("Always show from
  this sender") or for all mail (a setting; `settings.images`, `settings.imageSenders`). Until then
  the frame's CSP allows only `data:` and `blob:` images. Shown images load straight from the
  sender's servers, which see the device's IP address and when it opened the mail. Contract:
  [images-contract.md](images-contract.md).
- Possible follow-up: a picture-fetching proxy (a Cloudflare Worker that fetches without cookies or
  referrer, refuses private hosts and non-image bytes, swaps known trackers for a blank pixel and
  caches), so senders would see the proxy instead of the reader. Not built now.
- Link tracking: known redirect wrappers are unwrapped where the real URL is in the link.

### Labels and filters

Gmail filters only, read and written through `settings.filters` (list / create / delete; there is no
update, so an edit is delete + create). They run inside Gmail, in Gmail's own apps too, and need none
of our code to be up.

- What a filter can match: from, to, subject, has words, doesn't have, size, has attachment. "Has
  words" takes the full Gmail search language (`OR`, `-`, `{}`, `()`, `list:`, `deliveredto:`,
  `category:`), so all / any / not conditions fit.
- What it can do: add a label (one per filter), archive, mark read, star, never send to spam, mark
  important or not, delete, forward (only to addresses verified in Gmail settings).
- The editor builds the conditions as rows (field, operator, value, grouped all / any / not) and
  writes the Gmail search text; a live preview runs that search. Filters made in Gmail itself load into
  the same editor, falling back to raw text when they don't parse into rows. Leaving the filter
  editor keeps a change, as Save does (Android's Back; the web's Escape, close button or going
  elsewhere), and one that isn't whole (it matches nothing, does nothing, or, on the web, has
  Label or Forward on with nothing picked) is dropped (`filterIsWhole`, the `whole` cases of
  `filter-fixtures.json`); Cancel drops a change. Leaving a label's
  edit (Back in Android's label editor, Escape in a web rename) keeps the change the same way,
  while a new label is made only by its Create button: leaving the form drops it, on both. The
  signature saves as it is typed.
- Labels: create, rename, color, nest (`labels.*`). A rename carries the todos' tags along, and a
  label's list shows the todos (and their emails) whose tags name it ("Todos", Tags and labels).
- Per-label settings live in `users/{uid}/labelPrefs/{labelId}` (`shared/src/label-prefs.ts`, fixtures in
  `shared/testdata/label-prefs-fixtures.json`). `sidebar` (bool): shown in the sidebar; when unset,
  Gmail's `labelListVisibility` decides (hidden only for `labelHide`). `bundle` (bool, default on):
  the inbox folds this label's unpinned threads, and the todos whose tags name it (Todos, Tags and
  labels), into one row, once, in the time section of its newest item; grouping looks only at labels
  with it on. A thread or todo with several such labels joins the one with the most of the inbox's
  items (ties go by label order), and a label left with fewer than two gives its items back to
  their own rows and the choice is made again without it, so a bundle holds those of its label's
  items no bigger bundle took, at least two (`groupByLabel` in `shared/src/label-groups.ts`). Its
  counts add its todos to its threads (only the threads come in pages, so "10+" stays a floor).
  Like a todo being edited in latr, an open bundle keeps the place it opened at (its date
  and section) until it closes and its close has played, then moves to where it belongs: acting on
  its items doesn't move it, new mail of its label shows inside it, and it stays a bundle with
  a single item, or none while Gmail may have more of the label. Once its last item is
  gone and its own list shows Gmail has no more, it closes itself and collapses out of the list
  (undoing that last action brings the threads back, closed). A thread open in place (web) keeps its date and pinning the
  same way, so a reply or a star doesn't move it (`shared/src/held-places.ts`, fixtures in
  `shared/testdata/held-place-fixtures.json` and `label-group-fixtures.json`). Each such label carried by a loaded inbox thread gets its own list (`threads.list` of
  INBOX and the label, cached like any list: 10 up front, 50 more when the bundle is first opened,
  50 per "Show more"), so a bundle doesn't depend on how far the inbox is scrolled. The device
  remembers which of these lists held threads and fetches them at the start of the next session,
  before its first inbox page. Only once a label's list came back does the inbox's own list leave
  that label out (`-label:` / `-category:`); a label whose name the query can't name safely
  (anything but letters, digits, spaces, `/` and `-`, or a query name another label shares) stays
  in. While any label is left out, the inbox's pinned threads come from their own list (INBOX and
  STARRED, 100), so a pinned thread of a bundled label never drops out. When Gmail has a next page
  the bundle's counts read "10+" / "N+ new" and the open bundle ends with "Show more", which only
  that bundle's own next page turns to "Loading…" (a refresh of its first page shows nothing). The
  next page is also fetched by itself, one at a time per bundle and each bundle on its own, when a
  bundle runs out of loaded threads, when an open one is left with fewer than 10, when the end
  of an open one comes into view, or when the thread open in one has fewer than 3 of its loaded
  threads after it (`openBundleTail` in `shared/src/list-state.ts`), so swiping on reaches every
  thread of the bundle; a page that failed says why, with Retry, and waits for a tap on it. Its Done all (and a
  swipe on its row, Done or Snooze) takes its loaded threads but pinned ones, and its todos (marked
  done or snoozed, a repeating one making its next copy as Done does), and, when Gmail has more,
  lists the label's unpinned inbox threads by id (up to 500, no thread read, each snoozed with the
  history id Gmail listed) and archives or snoozes them all, with one undo. The undo shows at once for
  the loaded threads; those listed after join it while it shows, and an undo before the listing returns leaves them be. A list is fetched again (its first page, keeping pages past it) when the
  minute's `history.list` shows a change carrying its label, when its last fetch failed, or on a
  forced refresh (`shared/src/bundles.ts`, fixtures in `shared/testdata/bundle-fixtures.json`).
  The inbox, its bundles, pinned threads, focus mode and counts take only threads carrying INBOX and
  neither SPAM nor TRASH: a thread's labels are its messages' together, so one with a message in the
  inbox and another in spam stays out (`inInbox`). `icon` (user labels only): an id from `shared/themes/label-icons.json` or `"none"` (the colored dot). A user
  label's name and color are Gmail's own and change through `labels.patch` (colors from Gmail's
  palette, one per theme tone). Gmail's four categories can't be renamed, recolored or given an icon:
  their icons and colors are fixed in `label-icons.json` (Promotions tag/orange, Social users/blue,
  Updates info/purple, Forums message-square/teal), and the rules let their doc hold only `sidebar`
  and `bundle`. The rules check the keys, the types and the icon id against that list.
- Not possible with filters, so not offered now: snooze on arrival, notify-off as its own action
  (archiving achieves it), more than one label per filter (use two filters).

### Contacts

Clients pull Google Contacts and "other contacts" (People API) into the local cache and sync with
sync tokens. Autocomplete, names and photos come from there. A suggestion needs every typed word
to start a word of the name or address, accents ignored ("chen maya" finds Maya Chen, "jose" finds
José, "aya" finds nothing); saved contacts rank above other contacts (`shared/src/contact-match.ts`). Read-only for now. Both lists return
photos (`photos` in `personFields` / `readMask`): a URL that takes a `sz` size parameter, and
`default: true` for Google's generated placeholder, which clients replace with their own initials.
Details in docs/compose-contract.md.

### Calendar invites

The client spots `text/calendar` parts and shows a card: time, place, conflicts (Calendar free/busy),
and Yes / Maybe / No.

- Event already in the user's Google Calendar (Google adds most invites): RSVP through
  `events.patch` on our attendee entry; Google mails the organizer.
- Otherwise: send an iCalendar `REPLY` through the normal send path.

### Deleting an account

The `deleteAccount` callable (Play requires deletion in the app) removes everything the server holds
for the caller, in this order; each step treats "already gone" as done, so a failed run can simply
be called again:

1. Write `deletedAccounts/{uid}`, so the rules refuse every write from devices still signed in
   (no outbox doc can appear after the next step reads them). Until a run finishes, or the user
   signs in again, the account stays closed to writes.
2. Read `users/{uid}.address` and the outbox docs.
3. Cancel the send tasks of `waiting` docs, by the names the write trigger uses. Retry and
   lease-check tasks can't be named from the doc; they find no doc and stop.
4. Delete the published WKD key, then stop the @hafa.cc address: delete its Cloudflare rule and
   unused destination, and strip `addresses/{name}` down to `holderHash` so the name stays taken
   and only this account can take it back.
5. With the stored refresh token: stop the Gmail watch (`users.stop`) and revoke the token
   (`oauth2.googleapis.com/revoke`). A token Google already revoked skips both.
6. `recursiveDelete` `users/{uid}` (devices, threads, todos, outbox, meta) and `private/{uid}`.
7. Delete the Firebase Auth user.

Mail stays in Gmail; snoozed threads never left the inbox. Clients then sign out and clear the
device: the web deletes every IndexedDB database (mail cache, device key, Firestore's offline cache),
Cache Storage, localStorage and the service worker. Plain sign-out on the web clears the same except
the theme and the service worker's copy of the app. Android, on sign-out and after deletion, deletes
the account's Room database, draft files, sealed private key and opened attachments, its Keystore
keys, Firestore's offline cache, the image and web caches, its preferences, queued WorkManager work
and shown notifications, and cancels the snooze alarm (sign-out first removes the FCM device doc and its snooze wait entry).

### Offline

- **What's saved.** Each device saves threads with their bodies, most important first: everything in
  the inbox (bundles, categories and pinned threads too) or snoozed; every thread with a draft, at
  any age; every encrypted thread (OpenPGP,
  PGP/MIME or inline) outside spam and trash, at any age, since Gmail can't search inside it and
  search over it works only on what the device saved (with its decrypted copy, once the device is
  unlocked); everything with a message in the last N days outside spam and trash; everything opened
  on the device in the last N days. N is the device's own setting (Settings, "Offline": 0 to 30
  days, 30 by default); the inbox, snoozed mail, drafts and encrypted mail are always saved, which
  the setting says. A pinned thread out of the inbox is saved only as recent or opened, like any other.
  At most 3,000 threads besides encrypted ones: past that the least recently opened go first, then
  the oldest recent ones; the inbox, snoozed threads and threads with a draft are never dropped
  while they qualify (they count toward the 3,000, so they leave less room for the rest). Encrypted
  threads have their own cap of 10,000, newest kept; one past it stays only as recent or opened
  mail
  (`shared/src/offline-set.ts`, fixtures in `shared/testdata/offline-set-fixtures.json`). A thread
  read whole for any other reason (opened, say) is saved by the same rules. Each saved thread keeps
  a record of its messages' labels, its newest message's date, when it was last opened and its
  history id (web: the IndexedDB `offline` store; Android: the Room `offline` table), next to its
  row and bodies, and whether any message is encrypted (by its type, its text or its snippet).
  Android's Room schema 3 added that flag and starts the saved copy over (a fresh first fill).
- Attachments and images aren't saved: offline, an attachment whose fetch fails shows its name and
  size and "Opens when you're back online" (no preview, no error), and an inline (`cid:`) image is
  a small box with its file name, size and the same words (the sanitizer swaps it in; shared
  `sanitize-fixtures.json`). Both load as usual once the app is back online. Remote images are
  never saved.
- **Lists from the saved copy.** Once the first fill is done, the inbox, its bundles, pinned and
  focus mode are built from the saved copy (its labels, then the usual rules for dates, bundles,
  snoozes and the list) instead of `threads.list` pages, so the whole inbox opens offline and at
  once. Drafts is too, online or not (below). Other label views and search fetch from Gmail as before; offline, search covers everything
  saved: words, `-word` and quoted phrases in rows and saved messages, plus the operators the device
  can check (`from:`, `to:`, `subject:`, `label:`, `in:`, `is:`, `has:attachment`); others, like
  dates, don't narrow (`shared/src/offline-search.ts`). A thread that isn't saved says it opens once back online.
- **Opening a saved thread.** A row whose Gmail threads are all saved opens from the device and asks
  Gmail nothing, whatever the history ids say: the sync above keeps its bodies current (labels,
  unread marks, new and deleted messages), and while it is open each change the sync saves shows in
  it at once, new mail in it marked read. The row's read state is laid over the saved messages, so a
  change queued here shows before Gmail has it. Marking it read goes through the usual queue. A
  thread not saved is read from Gmail and saved before it counts as saved, so opening it again reads
  nothing. Each device holds the bodies of the last 24 threads shown in memory; the web also reads a
  row's bodies ahead when the pointer is over it, a press starts on it, or the keyboard focuses it,
  so most opens show in the same frame. Android reads them off the main thread, without a database
  write first. A message's frame (web) or view (Android) shows once its document is parsed or first
  drawn, not after every image has loaded. In dark mode the last 32 messages turned dark are
  kept turned, so opening one again or switching back doesn't redo it, and a message of more than
  20000 elements shows as it is. The web likewise keeps the last 32 messages cleaned, and the inline
  images it fetched this session in memory, so a message drawn again has them in its first frame.
  Counting the open and marking it read wait until the thread has drawn, and on a phone its two
  neighbors' saved copies are read only then too. Android loads its WebView engine once the app first goes idle, so
  the first thread opened doesn't wait on it. Setting localStorage `inbox.debugTiming` to `1` (web)
  logs each open's phases to the console (`open-timing.ts`). In the dev server every open is timed:
  once it has been quiet for 2 s with no Gmail call running (or the next open starts, or the page
  hides) its phases, where its messages came from (memory, saved copy or Gmail) and each Gmail call
  it made (method, path, ms, ms waiting its turn) go to the dev server, which prints one
  `[open-timing]` line per open to its log (`web/open-timing-relay.ts`); `bun scripts/open-bench.ts` in `web/`
  times the open path in headless Chrome against the running dev server. A Gmail access token fetch
  (`gmailToken`) that got no answer from the server (the request failed, timed out, or the server
  was unavailable) counts as offline like any other failed call, so it says the same.
- **Drafts offline.** Gmail's messages don't say which draft they are, and `drafts.update` needs the
  draft id, so the saved copy keeps Gmail's drafts listing (draft id, message, thread) beside it
  (web: IndexedDB `meta`; Android: the Room `meta` table). A sync or rebuild lists the drafts again
  (`drafts.list`) only when a saved draft message isn't in the last listing and wasn't left out of
  it before. Offline, a draft opens in the composer from the device's own copy when it has one, else
  from the saved copy (its From, recipients, Bcc, subject, threading and text), and its edits save
  on the device and go to Gmail as queued `drafts.update`s once online. `drafts.update` replaces the
  whole draft, so one the saved copy holds only in part (an attachment or inline image whose bytes
  weren't saved, an encrypted draft, whose raw is needed to decrypt it, or a copy saved before Bcc
  was kept) isn't opened from it: it says "This draft opens once you're back online." A draft
  opened from the saved copy may be older than Gmail's, so Send uploads what it shows before it
  goes, even when nothing was edited. Online,
  drafts open from Gmail as before. Once the first fill is done, Drafts is built from the saved
  copy's draft messages and the device's own drafts, online too, so it shows at once and follows
  each sync and each change made here (before that, only when Gmail can't be reached; else from
  Gmail's pages): a reply written here puts its thread in; a draft thrown away or sent here, one
  the Outbox holds, and one the device knows went leave it. A new message kept here that isn't in
  Gmail yet (written offline, or not saved there yet) has a row of its own in a card at the top of
  Drafts, "Not in Gmail yet", newest first: "To" its recipients, its subject and first words; a tap opens it in the composer. Once Gmail has it, its thread's row takes
  over. These rows aren't part of the list's keyboard focus, and the list's empty text doesn't
  show while there are any.
  A draft the device knows went is one an outbox doc said was `sent`; the device keeps that record
  (by its draft and message) until the saved copy no longer holds the message as a draft, so the
  draft doesn't come back once the doc is deleted while the device is offline and hasn't synced.
  Drafts listed this way follow the device's own drafts as they change
  (`shared/src/offline-drafts.ts`, fixtures in `shared/testdata/offline-draft-fixtures.json`).
- **Sync.** The device keeps its own history id across restarts. At start, on the minute's check,
  and on coming back online or into view, it reads `history.list` from that id, every page. A label
  added or removed is applied to the saved messages directly, their rows and their saved bodies
  alike (each message's unread mark; a deleted message leaves the body), so a thread losing INBOX
  leaves the inbox (and the saved set, unless recent or opened); new mail on a saved thread is read
  message by message (`messages.get`) and added to its saved body and row, since a Gmail message
  never changes once sent (a message Gmail won't give, or a thread with no saved body, has its
  thread read whole instead); a thread entering the saved set, such as one gaining INBOX or a draft, is
  read whole once (`threads.get`); new mail on a thread kept for no
  other reason (with N at 0, say), or a thread leaving spam or trash that way, is read as metadata
  first and read whole only when encrypted; a thread whose messages were all
  deleted, or that Gmail answers 404 for, goes, its row with it (from the screen and the device);
  other threads history names have their row read
  again. Threads are read the inbox's first (those history put in the inbox, most recently named
  first), then the rest; lists update once the first 25 are saved and again once the rest are, and
  the id moves on only after all were read. A thread Gmail refuses (not the network: a 403, say)
  doesn't hold the id back: it is read again at each sync until it works. A sync runs beside a
  rebuild once the device has a history id, so new mail shows while a rebuild lists. Queued changes
  of the device's own are laid over what Gmail says. The id moves on only after a sync came back
  whole, so a failed one is tried again from the same place
  (`shared/src/history-apply.ts` and `shared/src/offline-sync.ts`, fixtures alongside).
- **Tabs (web).** The tabs of one browser share the saved copy, so one at a time syncs, fills and
  prunes it (a Web Lock per account, `navigator.locks`): a tab whose sync finds another tab at it
  skips its own and, once that tab is done, reads the saved copy again, updating its rows and the
  inbox's lists. A tab that changed the copy leaves a mark in it, so the next tab to sync reads it
  again first; it then reads history from where the other left off.
- **Rebuild.** With no id (the first fill) or one Gmail no longer knows (404), the device rebuilds:
  it reads the new history id from `getProfile` first, lists `in:inbox`, the snoozed threads
  Firestore names, then `in:draft`, then `newer_than:Nd`, then encrypted mail (`{filename:encrypted.asc "BEGIN PGP
  MESSAGE"}`, the query encrypted search uses: PGP/MIME's payload file or an inline block), and
  reads whole only the threads whose history id differs
  from the saved one. Once every list page came back, saved threads no list named lose INBOX and go
  unless opened lately (a draft they still show counts for nothing, since `in:draft` named them all), but for those read after the listing began (history id past the profile's),
  which may have arrived since. The first fill runs in the background at the
  pacing's lowest priority (see "Pacing"), newest first and the inbox before the rest, saving each batch as it
  comes, so one cut short goes on where it stopped; until it completes, lists come from Gmail's
  pages as before. On Android the first fill waits for a network that isn't metered (Wi-Fi); later
  syncs and anything read on demand use any network. Android also syncs with the app closed
  (WorkManager, `SavedMailWorker`): the first fill as a job that waits for an unmetered network and
  runs again until it completes, then a sync every hour on any network.
- **Space.** Settings shows how many threads are saved and the space they take (web: the browser's
  estimate for the site; Android: the account's database). There's no button to clear it, since it
  would only fill again. The web asks the browser to keep its storage (`navigator.storage.persist()`).
- Every change (done, snooze, pin, label, spam, draft, send) goes into a local queue and replays in
  order when online. Firestore writes queue by themselves.
- A Gmail write that gets no answer in time is tried again, so only writes that come out the same
  when repeated are cut off: modify, `drafts.update`, label changes, deletes (2 minutes; reads 30 s).
  Creates (`drafts.create`, `labels.create`, `filters.create`) wait as long as Gmail takes: one cut
  off may already have been made, and its retry would make a second. The server's calls all time
  out (20 s), each safe to repeat: `drafts.send` is settled by whether the draft is still there, the
  Sent copy is looked up by `rfc822msgid:` before each `messages.insert`, and Resend sends carry
  their idempotency key.
- Before the first fill completes, a reload checks `history.list` from the history id of the last
  reload that came back whole (the list, every thread read, the bundles history touched) and skips
  when nothing changed.
- Queued changes and drafts send in order once online. The web sends them only while open: at
  start, when it comes into view or back online, and when restored from the back-forward cache.
  Android's `FlushWorker` sends them in the background. A reply can be written offline to any saved
  thread.
- While Gmail can't be reached, the top bar holds a small mark rather than a row of its own:
  "Offline", or how many changes wait to be sent ("3 waiting"; web at full width says it in full).
  Every Gmail call says whether Gmail was reached: any answer, even an error status, clears the
  mark, and a call that got none (no network, the host not found or not reached) shows it; one
  that ran out of time (the app's limit, or Android's socket timeout) changes nothing, as does an
  error in the app's own code. The device's network returning clears it at once (and starts a
  refresh), and losing its last network shows it (web: the `online` and `offline` events;
  Android: the default network callback, offline only when no other network took its place). A failed call elsewhere (the access token fetch,
  Firestore) also shows it, and the next Gmail answer clears it (`shared/src/gmail-pace.ts`,
  fixtures in `shared/testdata/gmail-pace-fixtures.json`).
- A thread Gmail answers "not found" for (deleted for good) when its row is read is remembered for
  the session, not asked for again, and its row leaves the screen and the device. A snoozed one has
  its snooze doc deleted too, since it can never wake.

### Encrypted mail (OpenPGP)

- **The private key is stored on our server, locked, and unlocked on a device by a passkey.**
  - A passkey's PRF extension gives a device a secret only after fingerprint / screen lock, the same
    secret on every device the passkey syncs to. Google Password Manager syncs passkeys end to end
    encrypted, so neither Google nor we can produce it.
  - Setup, on the first device: make the OpenPGP key pair, create a passkey for inbox.hafa.cc, take
    its PRF output (with a random salt), seal the private key with it (AES-GCM via HKDF) and store the
    sealed copy in `users/{uid}.pgp.sealed`. Also show a **recovery phrase** once: 8 random words from
    the EFF diceware list (~103 bits). It seals a second copy (`recoverySealed`, key from Argon2id) and
    is never stored.
  - Unlocking on any device: Google sign-in for the account, then the passkey prompt; the device
    unlocks the key itself and keeps it in the device's own secure storage (Android Keystore, web
    non-extractable WebCrypto key in IndexedDB) so the prompt comes only after sign-out.
  - Several passkeys: each gets its own sealed copy. A device whose passkey isn't in the list (an
    iPhone on iCloud Keychain, a different password manager) unlocks once with the recovery phrase or
    from an already unlocked device, then adds its own passkey.
  - Platforms: Chrome and Android with Google Password Manager support it; iOS 18+ with iCloud
    Keychain on the device itself (unlocking through a QR code from another phone is unreliable there,
    so the iPhone gets its own passkey). Bitwarden and Proton Pass don't support it yet.
  - Settings list the passkeys that unlock mail. Deleting a passkey in the password manager loses that
    copy only; the recovery phrase still works, and the app warns when only one passkey is left.
  - What it protects against: a leak of our server or database, and someone holding your Google
    password. What it doesn't: someone who changes our web app's code (true of all web encryption).
- Public keys are published for `@hafa.cc` addresses by the Key Worker (WKD), so Proton and other
  OpenPGP clients find them automatically. The `publishKey` function copies `pgp.publicKey` into
  Workers KV through Cloudflare's API whenever it, the address or its setup changes (only while the
  address is active, and only a public key with a user id for that address), and a daily job
  catches up anything missed; the Worker at `openpgpkey.hafa.cc` serves it from KV (WKD's advanced
  method). Chosen over the Worker asking a function on each lookup: lookups never touch Firebase,
  and the Worker needs no credentials. The Worker also answers `/lookup?email=` for the web app,
  which can't read other domains' WKD itself (they rarely allow it from a browser); it answers only
  the app's origins, and limits lookups of other domains to 30 a minute per client IP, so it can't
  serve as a fetcher for anyone else. Every outgoing message carries an `Autocrypt` header, and
  the public key is attachable for people on gmail.com (Gmail doesn't publish keys).
- Only mail **from @hafa.cc** is ever encrypted; mail from a Gmail address always goes as is.
- It is automatic, with no switch to remember. While composing, the client looks up each recipient's
  key: Autocrypt headers seen before, WKD on their domain (Proton publishes its users' keys this way),
  keys.openpgp.org. Each recipient chip shows a lock if that person has a key. When every recipient
  has one, the message is encrypted on the device before it becomes a draft and the send button shows
  a lock labeled "Encrypted"; otherwise it goes as is. Resend only ever carries scrambled text.
- Threads show a lock on each message that arrived or left encrypted.
- Gmail files an encrypted reply in a thread of its own, since its outer Subject is the hidden
  "..." rather than "Re: …". Its `In-Reply-To` and `References` stay readable, so a list joins a
  thread with the hidden subject to the loaded thread holding a message it answers, and shows them
  as one row, led by the thread whose earliest message is oldest; joins chain, and Gmail stays
  split. Every message counts, drafts included, whether the row comes from Gmail's copy or the saved
  one (`shared/src/join-keys.ts`). List reads ask for those headers in the same call
  they already make, and a thread whose original isn't loaded shows alone (no reads go out to find
  it). A notification's Done reads the notified thread and, when its first message has the hidden
  subject, finds every thread holding a message its messages answer with one `rfc822msgid:` search
  (up to 20 ids), and archives and unsnoozes each. Web's notification sends Done to the server;
  Android's Done and Snooze run on the device over the notified thread's loaded row plus the same
  search (`answeredSearch`), so they work offline for what is loaded. Every action and its
  undo applies to each Gmail thread in the row; snoozing snoozes each,
  and any new reply ends it, including one Gmail split off after the snooze. Opening the row shows
  all their messages by date, and a reply goes to the newest message's thread
  (`shared/src/thread-joins.ts`, fixtures in `shared/testdata/thread-join-fixtures.json`).
- Works with Proton, Mailvelope, Thunderbird, FlowCrypt; not Tuta (closed scheme); not Gmail's own
  encryption (paid business accounts only).

#### Receiving encrypted mail

- It arrives in Gmail still encrypted (PGP/MIME, or inline PGP from older clients) and stays that way
  there; Gmail and our server never see it decrypted.
- Devices decrypt when showing it. Encrypted mail that is in the offline cache is kept decrypted in
  the device's own encrypted storage, so it opens offline and offline search covers it. The
  decrypted copy stays exactly as long as its thread stays saved (see "Offline"), and a thread the
  saved copy reads again keeps it.
- Gmail can't search inside it, so each search also fetches the encrypted mail not in the cache
  (Gmail search `filename:encrypted.asc OR "BEGIN PGP MESSAGE"`, a page at a time), decrypts it on the
  device and adds any matches to the results. Nothing extra is kept from that fetch.
- Notifications: the server can't read it, so the push carries only the message id and sender
  (`encrypted: "true"`, empty subject and text). Android fetches
  and decrypts it on the phone and shows the real preview; the web app does the same in its service
  worker when the key is there, else shows "Encrypted message from Ana".
- The sender's signature is checked against their known key: a lock means encrypted, a check that it
  is signed by the key we have for them; a mismatch shows a warning.
- Replying stays encrypted automatically when everyone on the reply has a key (the sender's comes from
  the message itself or WKD).
- Encrypted to a key this account doesn't have (an old key, a different address): shown as "Can't
  open: encrypted to another key".
- Attachments inside are decrypted on open, same as the body.
- Costs: encrypted bodies can't be searched by Gmail, and the subject travels in the clear unless the
  other side supports protected headers.
- Formats: keys are Ed25519/Curve25519 (v4, the algorithm ids every current client reads); mail is
  PGP/MIME (RFC 3156), signed and encrypted in one step, with protected headers (outer Subject
  `...`, the real headers inside, RFC 9788 and Thunderbird's `protected-headers="v1"`). Sealing is
  HKDF-SHA256 → AES-256-GCM; the recovery phrase goes through Argon2id (64 MiB, 3 passes). Pure code
  in `shared/src/pgp` (openpgp.js, hash-wasm), pinned by `shared/testdata/pgp-fixtures.json`;
  Android ports it with Bouncy Castle. Client contract: docs/pgp-contract.md.

## DNS on hafa.cc (Cloudflare)

- `inbox` → GitHub Pages (DNS only).
- MX → Cloudflare Email Routing (already); one forwarding rule per claimed address, no catch-all, so
  unknown names bounce.
- Resend: DKIM on `resend._domainkey`, bounce subdomain `send.hafa.cc` with its SPF/MX.
- SPF on hafa.cc includes Cloudflare + Resend; DMARC `p=quarantine` after a quiet period.
- `openpgpkey` → the Key Worker.

## Cost at one user

Free tiers cover Firestore, Functions, Cloud Tasks, Pub/Sub, FCM, Workers and Resend. KMS key: about
$0.06/month. Growth to 100 users stays near free except Resend's 100/day.

## Repo layout

```
web/            SvelteKit app
android/        :core (JVM, ported logic), :app (Compose)
functions/      Cloud Functions v2 (TypeScript, bun)
workers/        Cloudflare: wkd (OpenPGP key directory)
firebase/       Firestore rules + tests, emulator config
shared/         fixtures every client must pass: snooze, filter search text, mime, sanitize, unsubscribe, pgp, todos
docs/           design notes, contracts and the build plan
```
