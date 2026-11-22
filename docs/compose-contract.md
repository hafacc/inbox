# Compose and send: contract for the clients

What the web and Android compose UIs build against. Server: `functions/src/outbox-send.ts`
(`outboxChanged`, `outboxSend`) and `functions/src/lib/outbox.ts`. Rules:
`firebase/firestore.rules`. Background: design.md "Sending, scheduled send, undo send".

## Flow

1. Build the message on the device with `buildMime` (below). For a reply, take `In-Reply-To` and
   `References` from `replyHeaders`, recipients from `replyRecipients`, and the From address from
   `replyFromAddress`.
2. Save it as a Gmail draft: `drafts.create` (or `drafts.update` while editing) with
   `message.raw = gmailRaw(mime)` and, for a reply, `message.threadId`. Queue it offline like any
   other Gmail change. Keep the draft id.
3. Write `users/{uid}/outbox/{id}` (any new id of 1 to 128 letters, digits, `_` or `-`; a random
   one is fine, and the rules refuse others) with `set`, status `waiting`. The server queues a task
   for `sendAt`.
4. Watch the doc: it goes `waiting` → `sending` → `sent`. When an attempt fails in a way
   retrying might fix, it goes `sending` → `retrying` → `sending` → … for at most 3 automatic
   retries, 1, 5 and 30 minutes apart; if the last one fails too it ends as `failed`
   (`unavailable`) and waits for the user to send it again. When it hits a problem only signing in
   again or fixing the server can cure, it goes `sending` → `blocked` and waits there; the server
   turns it back into `waiting` by itself once that is fixed (see `blockedOn`). Refusals retrying
   can't fix go straight to `failed`. Treat `waiting` past its `sendAt`, `sending`, `retrying` and
   `blocked` as "in the outbox": the message has not gone yet. The server never deletes the doc;
   delete it yourself once the user has seen the result (or keep it as a record and delete it
   later).

The draft must exist before the outbox doc's `sendAt`. If the draft is still queued offline, hold
the outbox write until `drafts.create` has returned an id.

### Sending a send now from the device

When a send now's undo window ends while it is on screen and online, the device sends it itself
if `directSendDecision` (`@inbox/shared/direct-send`) says `send`, through `sendDirectly`
(web `web/src/lib/compose/direct-send.ts`, Android `:core` `send/DirectSend.kt`): read the draft's
headers (`drafts.get`, metadata) and check `draftSender` is the doc's `from`; claim the doc in a
transaction, deciding again on the doc as read there; call `drafts.send` once; record the result.
Never retry on the device: `directSendFailure` says which failures to record (`rejected`); leave
the rest `sending` for the server. The writes, all `update`s (the rules refuse anything else):

| Write | From | Fields |
|---|---|---|
| claim | `waiting` with only the client's fields, not @hafa.cc, at most 30 s before its `sendAt` | `status: "sending"`, `claimedAt`, `firstAttemptAt`, `inFlightAt` (server timestamps), `via: "gmail"`, `draftMessageId` (the draft's Gmail message id), `messageIdHeader` (its `Message-ID`, or null), `deviceClaim` (16–64 of `A-Za-z0-9_-`) |
| sent | `sending` with `deviceClaim` | `status: "sent"`, `sentAt` (server timestamp), `messageId`, `threadId`, `filed: true`, `rejected: []`, `inFlightAt` deleted |
| refused | `sending` with `deviceClaim` | `status: "failed"`, `error: { code: "rejected", message }` (at most 2,000 characters), `failedAt` (server timestamp), `inFlightAt` deleted |

### Encrypted sends with Bcc

An encrypted send with Bcc recipients is several messages (docs/pgp-contract.md, Sending, step 4:
`planEncryptedSends`): the main one for To and Cc, then one copy per Bcc recipient. Each has its
own draft and its own outbox doc; the server handles every doc on its own and needs nothing more.

- **Drafts**: the main message replaces the composer's draft in place (`drafts.update`, same id);
  each copy is a new draft (`drafts.create`), with the reply's `threadId` when it is a reply. When
  there is no main message (only Bcc), the first copy replaces the composer's draft. A new message's
  copies get no `threadId`: Gmail threads them on their own.
- **Outbox docs**: the first planned message gets the doc id the send would have had (`{id}`), the
  others `{id}-bcc-{n}` with `n` from 1 in plan order, all with the same `sendAt`, `chosenAt`,
  `from` and `threadId`. That id is how a client finds a send's docs again: the docs whose id is
  `{id}` or starts with `{id}-bcc-`. Write the docs only once every draft has an id.
- **Status**: the send has gone when every doc is `sent`. Show it as one send: in the outbox while
  any doc is; a doc that ends `failed` or `blocked` is shown with its recipient ("Not sent to
  Ana (Bcc)") and its own "Send again".
- **Undo, cancel, reschedule, send again**: do it to every doc of the send. Undo and cancel also
  delete the copies' drafts and save the composer's draft with its Bcc list again (docs/pgp-contract.md,
  Sending, step 3).
- **Edit a scheduled send**: rebuild and save the whole plan again. Keep the first doc and its
  draft; update the copies whose recipient is still in Bcc in place (`drafts.update` with the newly
  encrypted copy, same draft id, and leave its outbox doc untouched); create drafts and docs for new
  Bcc recipients (the next unused `n`); delete the drafts and docs of removed ones. A copy's
  recipient is its draft's outer `Bcc` header. Updating in place is sound for encrypted copies: a
  copy is always encrypted to one recipient's key and the sender's, so the same recipient's new copy
  replaces the old one exactly, and the doc never names anything but the draft. Rewrite a kept doc
  only when something in it changes (its draft was gone and made again, or a new `sendAt`). This
  needs an unlocked key (docs/pgp-contract.md, Sending, step 1).
- **Sent**: every message lands in Sent: the main one without Bcc, each copy with its own `Bcc`.

## Outbox doc

Clients write exactly these fields (anything else is refused by the rules):

| Field | Type | Notes |
|---|---|---|
| `draftId` | string | Gmail draft id, non-empty |
| `from` | string | the address in the draft's `From` header; must match it (case-insensitive) |
| `status` | `"waiting"` | the only status a client may write (but for "Sending a send now from the device") |
| `sendAt` | Timestamp | when it goes |
| `chosenAt` | Timestamp or null | the unshifted suggestion the user picked; null for undo send and typed times |
| `threadId` | string or null, optional | the draft's thread, for showing "Scheduled" in the thread |

The server adds:

| Field | Set when | Notes |
|---|---|---|
| `status` | always | `sending` during an attempt, `retrying` between attempts, `blocked` while waiting for a fix, then `sent` or `failed` |
| `claimedAt` | `sending` | |
| `attempts` | `retrying` | attempts that didn't go so far |
| `firstAttemptAt` | from the first attempt | Timestamp |
| `nextAttemptAt` | `retrying` | Timestamp of the next attempt |
| `lastError` | `retrying`, `blocked` (kept after) | `{ code, message, at }`: why the last attempt didn't go; `message` is readable English |
| `blockedOn` | `blocked` | what it waits for: `"sign-in"` or `"resend"` (below) |
| `inFlightAt`, `uncertainSince`, `draftMessageId`, `messageDate`, `idempotencyKey` | during attempts | the server's bookkeeping for never sending twice; ignore |
| `via` | `sent`, `failed` | `"gmail"` or `"resend"`; null when it failed before choosing |
| `sentAt` | `sent` | |
| `messageId`, `threadId` | `sent` | Gmail ids of the sent message (for Resend, of the copy filed in Sent; may be null briefly until filed) |
| `messageIdHeader` | `sent` via Resend | the `Message-ID` header that went out |
| `filed` | `sent` | Resend only: false until the Sent copy is in Gmail and the draft deleted. The copy filed is the message as it went, whatever happens to the draft after; the server may delete the draft before filing |
| `rejected` | `sent` | addresses the SMTP server refused (others got it); usually empty |
| `error` | `failed` | `{ code, message }`; `message` is readable English |
| `failedAt` | `failed` | |

`error.code` values:

| Code | Meaning | Suggested UI |
|---|---|---|
| `draft-missing` | the draft was deleted or never synced | offer to rebuild from the composer if still open |
| `from-mismatch` | the draft's `From` differs from `from`, or the draft has more than one `From` header or a `From` naming more than one mailbox | fix the doc or draft and retry |
| `not-owned` | `from` is neither the Gmail address, a verified Gmail send-as alias, nor the user's claimed @hafa.cc address with its setup active | |
| `rejected` | Gmail or the SMTP server refused it for good (bad or rejected recipients, message too large) | show `message` |
| `uncertain` | a Resend attempt's outcome stayed unknown past the 23 hours its key protects, or Resend said the key was used for other content; it may have gone | tell the user it may have been delivered; sending again sends it again |
| `unavailable` | still failing after the 3 automatic retries | show `message`; offer "Send again" |

`lastError.code` values (status `retrying`; the server carries on by itself):

| Code | Meaning | Suggested UI |
|---|---|---|
| `network` | the connection failed, or the outcome of handing it over is unknown (the next attempt settles it without sending twice) | "Couldn't send yet · retrying at HH:mm" |
| `rate-limited` | Gmail or Resend limits, including Resend's daily limit | "Sending limit reached · retrying at HH:mm" |
| `server` | Gmail or Resend errors (5xx, SMTP 4xx), a token refresh that failed | as `network` |

In threads and the outbox list, show `retrying` with `nextAttemptAt` (24-hour) and
`lastError.message`, and the message as not sent yet.

`blockedOn` values (status `blocked`; nothing is tried until it is fixed, then the server resumes
it by itself: it turns the doc back into `waiting` with its retries reset, and it goes at once):

| Value | Meaning | Resumes when | Suggested UI |
|---|---|---|---|
| `sign-in` | Google access was revoked, or no refresh token is stored | the user signs in again (the `signIn` function resumes that user's blocked sends) | "Waiting for you to sign in again" |
| `resend` | Resend isn't set up on the server, or refused its credentials | a check every 30 minutes, run only while some send is blocked on Resend, logs in to Resend's SMTP successfully | "Sending from @hafa.cc isn't set up yet" |

Show a blocked send as not sent yet, with that line, "Send again" and Cancel. Its draft can be
edited.

### Lifecycle and rules

- **Send now with undo**: `sendAt = now + UNDO_SEND_DELAY_MS` (10 s, never varied),
  `chosenAt = null`. Undo = delete the doc before `sendAt`; the draft stays, reopen it.
- **Schedule**: `sendAt` from the menu (see below). The draft stays in Gmail's Drafts, editable,
  until it goes.
- **Reschedule**: `set` the whole doc again with the new `sendAt` (allowed while `waiting`).
- **Edit a scheduled send**: keep the doc and update the same draft in place (`drafts.update`);
  the draft id doesn't change, so the doc stands as is. The server reads the draft at `sendAt`,
  so an edit saved before then goes out; save edits early enough as `sendAt` nears (web saves at
  least 15 s ahead, however steadily the user types). If the draft had to be made again under a
  new id, `set` the doc again with that `draftId` and the same `sendAt`. Sending now or picking a
  new time rewrites the same doc; undoing that send now puts the old `sendAt` back.
- **Cancel a scheduled send**: delete the doc (the draft stays).
- **Send again after `failed` or while `blocked`**: `set` the whole doc again as `waiting` (new
  `sendAt`, usually now + undo delay). Allowed only from `waiting`, `failed` or `blocked`; a
  `sent`, `sending` or `retrying` doc can't be rewritten (the rules refuse it). The rewrite drops
  the server's bookkeeping; the server puts back what it needs before the send goes (for the same
  `draftId`), so sending again after an attempt whose outcome was unknown still can't deliver it
  twice. The exception is `uncertain`: that send goes as new.
- **Cancel a retrying or blocked send**: delete the doc; the next retry finds nothing and stops.
  Say that a retry whose `lastError.code` is `network` may already have delivered it. The server
  keeps what it needs to avoid sending twice by draft for a day, so a later send of the same,
  unedited draft still can't deliver it twice.
- **Don't edit the draft while `sending` or `retrying`**: lock the composer on it (show it read-only
  with "Sending…"/"Retrying at HH:mm"). To change it, cancel first. An edit under way would change
  the content the Resend idempotency key covers.
- Deleting is allowed except while a doc is `sending` (the rules refuse it, since the attempt
  would go on without the doc): say it is being sent right now and can't be canceled.
- Times come from the device clock.

## Schedule-send suggestions: `@inbox/shared/send-suggest`

Same engine and stored format as snooze, separate counts at `users/{uid}/meta/sendStats`
(`sendStatsPath(uid)`). Store it exactly like `web/src/lib/snooze-stats-store.ts` /
Android `SnoozeStatsStore.kt` do for `meta/snoozeStats`: read with `sharedFromWire`, write only
`increment()`s of `voteDeltas` (re-exported from `send-suggest`).

- `sendRows(partitions, now)` → up to `SEND_SHOWN_ROWS` (4) rows in time order: learned rows,
  then the defaults "next 08:00", "next 13:00", "Monday 08:00" where there's room and nothing within
  30 minutes. Each row: `time`, `text` (label without time), `icon` (snooze `RowIcon`), `keyId`,
  `learned`.
- `lastRow(partitions, now, rows)` → the last custom time row, as in snooze.
- `quickTimes(partitions, now)` → clock times for the picker, as in snooze.
- Then the "Pick date & time…" item (`PICK_DATE_TIME_LABEL`). Times are 24-hour (`formatClock`).
- **Vary scheduled send times**: two fields in `users/{uid}.settings`: `varySendTimes` (boolean, **on
  unless it is `false`**; absent means on) and `varySendMaxMinutes` (whole minutes, 1–30, default
  10). Read both with `varySetting(settings)` → `{ enabled, maxMinutes }` (clamps and defaults).
  Settings UI: a switch, and under it a slider "Up to N minutes" from `MIN_MAX_SHIFT_MINUTES` (1) to
  `MAX_MAX_SHIFT_MINUTES` (30), step 1, shown only while on; write `varySendMaxMinutes` as an
  integer. When the menu opens and the setting is on,
  `varySendTimes(rows.map(r => r.time), now, seededRandom(seed), maxMinutes)` with one fresh seed
  per opening (e.g. from `crypto.getRandomValues`), so the shown times hold still while the menu is
  open. Show each row's `sendAt`. Off: `unvariedSendTimes`. Shifts are whole minutes in
  ±`maxMinutes`, never 0 (`shiftMinutes(unit, maxMinutes)`). Never vary the last custom row, picker
  times or undo send.
- On pick: outbox `sendAt` = shown time, `chosenAt` = the row's unshifted `time`
  (`null` for undo send). Record the pick with
  `commit(partition, now, chosenAt, "suggestion", row.keyId)` (or `"last"` / `"custom"` with
  the typed time and no key), the same as snooze. Only `chosenAt` ever feeds the stats. Undo send
  doesn't commit.

## Building the message: `@inbox/shared/compose`

Pure TypeScript, no DOM; web imports it directly, Android ports it to `:core`.

- `buildMime(input: ComposeInput): string` → the whole message, CRLF line breaks.
  `ComposeInput`: `from`, `to`, `cc`, `bcc` (`Mailbox = { name, email }`), `subject` (plain text),
  `markdown` (body: plain text with latr's styles, see below), `signature` (the same, from
  `users/{uid}.settings.signature`, or null),
  `inReplyTo` and `references` (from `replyHeaders`, or null and `[]`), `inlineImages`
  (`{ filename, mimeType, data: Uint8Array, contentId }`, referenced in the markdown as
  `![alt](cid:<contentId>)`), `attachments` (`{ filename, mimeType, data }`), `extraHeaders`
  (`[name, value][]`, already encoded, e.g. `Autocrypt` later), `boundary` (`randomBoundary()`),
  and optionally `calendar` (`{ method, content }`: an iCalendar part for an RSVP, see
  docs/shared-contract.md).
- Addresses are trimmed; one that isn't a plain `local@domain` (exactly one `@`, no whitespace,
  control characters, `,`, `;`, `<` or `>`; `isPlainAddress`) makes `buildMime` (and
  `formatMailbox`) throw `InvalidAddressError`, so nothing typed or received can add a recipient
  or a header. Check chips with `isPlainAddress` before Send and show the error rather than
  sending.
- Structure: `multipart/mixed` (attachments) → `multipart/related` (inline images) →
  `multipart/alternative` (text, then HTML); each level only when needed. Text parts are UTF-8
  quoted-printable; files base64 in 76-character lines.
- HTML is the body's rich text rendered by `richToHtml` in `<div dir="auto">`: the editor's
  (`ComposeInput.html`), else `markdownToHtml(markdown)`. Lines join with `<br>`; headings,
  lists (`<ol start>` past 1), `<blockquote>`, `<pre><code>`, `<b>`, `<i>`, `<s>`, `<code>` and
  `<a>` come from the rich text, bare `http(s)://` and `www.` URLs → links and `![alt](cid:id)` →
  `<img>`; blank lines at the start and end are left out, as in the plain text. The signature follows in `<div class="signature">`, rendered from its markdown the same
  way. Plain text is the body as this app's markdown (`richToMarkdown`), then a blank line, `-- `,
  and the signature.
- Leave out `Date` and `Message-ID`: Gmail adds them to the draft, and the server sets `Date` at
  send time for Resend. Keep `Bcc` in the draft; Gmail and the server keep it off what recipients
  get.
- `gmailRaw(mime)` → base64url for `message.raw`.
- Replies and forwards: `answerHeaders({ messageId, references, inReplyTo, subject }, forward)` →
  `{ subject, inReplyTo, references }` (`replySubject` or `forwardSubject`, and `replyHeaders`'
  threading; none when the message has no `Message-ID`); `replyRecipients(original, ownAddresses,
  replyAll)` → `{ to, cc }`; `replyFromAddress(original, ownAddresses, fallback)`. `messageId` is
  the RFC 5322 `Message-ID` header, not Gmail's message id. A forward stays in the thread, as
  Gmail keeps its own. Pass the thread's id to `drafts.create` and `drafts.update`: Gmail files the
  message there only when the id, `In-Reply-To`/`References` and the subject all agree, and
  otherwise starts a new thread without saying so. Pinned by `answerHeaders` in
  `shared/testdata/compose-fixtures.json`.
- **Quoting a reply**: set `ComposeInput.quote` to the message replied to,
  `QuotedMessage = { from: Mailbox, date (epoch ms), text, html | null }` (`from`: its first `From`;
  `date`: its `Date` header, else Gmail's `internalDate`; `text`: its text/plain part, else text
  from its HTML; `html`: its text/html part as received, or null).
  `buildMime` puts the quote after the signature:
  - HTML: `<br>` then Gmail's markup, `<div class="gmail_quote gmail_quote_container"><div dir="ltr"
    class="gmail_attr">On Mon, Nov 2, 2026 at 11:50, Maya Chen &lt;maya.chen@gmail.example.com&gt;
    wrote:<br></div><blockquote class="gmail_quote" style="…">original</blockquote></div>`, so
    Gmail and other clients fold it. The original is `quotableHtml(html)` (the inside of `<body>`,
    no head, scripts or comments), or the text escaped with `<br>`s. `cid:` images in the original
    must go along: `quotedContentIds(html)` lists the Content-IDs the quote refers to; fetch
    those inline parts of the original from Gmail and add them to `inlineImages` with the same
    `contentId` (a part that can't be fetched is left out and shows broken).
  - Text: a blank line, the attribution, a blank line, then the original's lines as `> line`
    (`>` for a blank line).
  - `quoteAttribution(from, date)` formats the line in the device's zone, 24-hour, English;
    `quoteText` and `quoteHtml` give each part alone.
  - Forward stays as it is (the web's `forwardMarkdown` puts the original into the markdown).
- **Showing quotes folded**: `splitQuotedHtml(html)` and `splitQuotedText(text)` →
  `{ body, quote }` (`quote` null when there is none) find the quote trailing a message: Gmail's,
  Apple Mail's, Outlook's, Thunderbird's and Yahoo's markup, or `> ` lines under a "…:" attribution
  line. In threads, show `body` and a "…" button that reveals `quote`. In the composer, keep the
  quote out of the editable markdown: show "…" under the editor that expands it read-only (and lets
  the user remove it, which sets `quote` to null). Reopening a reply draft: split the draft's
  text/plain with `splitQuotedText` first (the quote is the part after the signature), take the body
  as usual from `body`, and rebuild `QuotedMessage` from the thread's replied-to message
  (`In-Reply-To`); if that message isn't found, keep the quote text as plain text
  (`{ from, date, text: quote lines without "> ", html: null }` is fine) or drop it.
- Smaller pieces (exported for tests and previews): `bodyParts(body, signature, quote?, bodyHtml?)`,
  `markdownToHtml`,
  `markdownToText`, `formatMailbox`, `formatAddressList`, `encodeHeaderText`, `foldHeader`,
  `quotedPrintable`, `base64Lines`.
- Size: Gmail drafts take up to 35 MB of message (base64 grows files by a third).
- **Text styles**: latr's, Slack/WhatsApp style: `*bold*` (one star) and `_italic_`. A marker
  opens only before non-whitespace and not after a letter or digit, and closes only after
  non-whitespace and not before one, so `snake_case`, `2*3*4` and `a * b` stay text; `**x**` and
  `__x__` stay text; styles never cross a line break and may nest (`*_x_*`). The parser
  (`shared/src/compose/inline-style.ts`, Android `InlineStyle.kt`) is ported from latr and pinned by
  latr's own `shared/testdata/inline-style-fixtures.json`; keep both in step with latr.
- **Rich text**: the composer edits rich text, and markdown typed into it turns into styles as it
  is typed, its markers gone: latr's `*bold*` and `_italic_`, `~~strike~~`, `` `code` ``,
  `[text](url)`, `# ` to `###### ` headings, `- `, `* ` and `+ ` bullets, `1. ` numbered lists,
  `> ` quotes and ```` ``` ```` plus a space for a code block. Bold and italic follow the text
  style rules above, so a letter or digit typed right after the closing marker puts the markers
  back as text. Enter carries a list, quote or code block on; Enter on an empty item or quote line
  ends it, as does a third Enter on two empty code lines. Web uses Tiptap (`web/src/lib/compose/rich-doc.ts`), Android Compose Rich Editor
  (`android/app/.../mail/RichBody.kt`). Both hold `RichBlock`s (`shared/src/compose/rich.ts`,
  Android `Rich.kt`), a block per line, and write them out as this app's markdown: `- ` bullets,
  `*bold*`, `_italic_`, `~~strike~~`, backticks, `[text](url)` and fences. A link's address keeps
  parentheses balanced one deep; spaces in it, and any other parentheses, are percent-encoded. A
  saved draft's markdown loads back into the same blocks. `shared/testdata/rich-fixtures.json` pins both: what
  each markdown loads as, and what typing each input ends up as.
- **Android's editor** has no quotes or code blocks of its own: their lines are paragraphs with
  an indent of the app's own, the quote bar and code background drawn behind them, and code lines
  set in monospace. Inline code is the library's: boxed and colored, not monospace. Bold and Italic
  on the toolbar (and ⌘B/⌘I on web) toggle the style on the selection.

## Contact photos (People API)

Both `people.connections.list` (`personFields`) and `otherContacts.list` (`readMask`) accept
`photos`; add it beside `names,emailAddresses` (`metadata` too, for sync). Each person has
`photos[]` of `{ metadata: { primary, source }, url, default }`.

- Pick the photo with `metadata.primary`, else the first.
- `default: true` means Google's generated placeholder (a colored letter): ignore it and draw the
  app's own initials avatar, the same as when there is no photo.
- The URL (on `lh3.googleusercontent.com`) needs no token. Request the size you draw at, in device
  pixels, by adding `sz=<px>` as a query parameter (e.g. `…?sz=96` for 48 dp at 2×); replace an
  `sz` already present rather than adding a second one.
- Cache on the device: keep the URL in the contact cache, and the image bytes in an image cache
  keyed by URL and size (web: the HTTP cache / Cache Storage; Android: the image loader's disk
  cache), for at least a week. A changed URL in a sync means a new photo; drop the old entry. On a
  failed load, fall back to initials and don't retry until the next sync.
- Other contacts often have no photo or only a default one; most real photos come from saved
  contacts and Google profiles.

## Fixtures

Both clients run these; Android's `:core` loads them from `shared/testdata/` as the snooze ones.
Each file's `_doc` states the exact rules.

- `shared/testdata/compose-fixtures.json`: styled text → HTML (compared after `normalizeHtml`) and
  text, signature placement, reply subject/headers/recipients/From, address and RFC 2047 header encoding, header
  folding, quoted-printable, base64 lines, whole messages, and reply quoting (`attribution`,
  `quoteText`, `quotableHtml`, `body` entries with `quote`, `splitText`, `splitHtml`, and a `mime`
  entry with `quote`; quote dates are given as local `dateLocal` in America/New_York). Run by
  `shared/src/compose/compose.spec.ts`.
- `shared/testdata/send-fixtures.json`: menu rows, the shift mapping (each entry now has
  `maxMinutes`), the seeded random source (mulberry32), varied times (each entry has
  `maxMinutes`), and reading the setting (`setting`). Run by `shared/src/send-suggest.spec.ts`. America/New_York, like
  the snooze fixtures.
