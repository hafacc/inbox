# Shared logic: contract for the clients

Search, filters, unsubscribe, calendar invites, link unwrapping and HTML cleaning: pure logic web
imports from `@inbox/shared/*` and Android ports to `:core`, both run against the same fixtures in
`shared/testdata/` (Android loads them as test resources, like the compose ones). Each fixture
file's `_doc` states the exact rules; the TypeScript in `shared/src/` is the reference. Compose and
send are in `docs/compose-contract.md`; OpenPGP (`@inbox/shared/pgp`) in `docs/pgp-contract.md`. Background: design.md "Search", "Labels and filters",
"Unsubscribe", "Calendar invites", "Images and trackers".

| Module | Import | Fixtures | Spec |
|---|---|---|---|
| Search chips and completion | `@inbox/shared/search` | `search-fixtures.json` | `shared/src/search.spec.ts` |
| Filter editor | `@inbox/shared/filters` | `filter-fixtures.json` | `shared/src/filters.spec.ts` |
| Unsubscribe headers | `@inbox/shared/unsubscribe` | `unsubscribe-fixtures.json` | `shared/src/unsubscribe.spec.ts` |
| Calendar invites and RSVP | `@inbox/shared/calendar` | `calendar-fixtures.json` | `shared/src/calendar.spec.ts` |
| Link unwrapping | `@inbox/shared/links` | `link-fixtures.json` | `shared/src/links.spec.ts` |
| HTML cleaning | (each client's own) | `sanitize-fixtures.json` | run by web and Android |

## Search: `@inbox/shared/search`

The field holds chips (finished operators) and an input with the free text.

- On every input change: `parseQuery(free, cursor)` → `{ chips, free, cursor, context }`. Append
  the returned `chips` to the field's chips, put `free` back in the input with the returned
  `cursor`. An operator becomes a chip once it has a value, its quote is closed and the cursor no
  longer touches it (typing the space after it, or moving away). Groups (`(…)`, `{…}`), `OR`,
  `-word` and unknown operators stay text, and so does an operator right before or after a
  top-level `OR` (as a chip it would leave the `OR` and change the search). Operators: `OPERATORS` (from, to, cc, bcc, subject, in,
  is, has, label, filename, after, before, older_than, newer_than, larger, smaller, size, list,
  deliveredto, category). Loading a whole query (e.g. from a link): `parseQuery(text, null)`.
- Tab: `completions(context, { now, timeZone, labels, contacts })` → `Candidate[]`
  (`{ label, value, detail }`), then `applyCompletion(free, context, candidate)` → `{ text, cursor }`
  and parse again; a completed value turns into a chip at once. Operators complete by name (with
  `OPERATOR_DETAILS` as the second line); values: `in:` mailboxes (`IN_VALUES`, with our `outbox`),
  `is:` (`IS_VALUES`, with our `pinned`, `snoozed`, `done` and `todo`), `has:`, `category:`, sizes, ages, and for `after:`/
  `before:` today, yesterday and last week as concrete `yyyy/mm/dd` dates in `timeZone`. `label:`
  takes the caller's label names (the inserted value is `labelQueryValue(name)`: lower-case, spaces
  and `/` as `-`); `from:`/`to:`/`cc:`/`bcc:`/`deliveredto:` the caller's contacts and recent
  senders, best first (at most `MAX_HOOK_CANDIDATES`, 8).
- Backspace at the start of the input with a chip before it: `unchip(chips, index, free)` →
  `{ chips, free, cursor }`; the chip's text goes back into the input and stays text while the
  cursor touches it.
- Running the search: `gmailQueryText(chips, free)` is the Gmail `q`. Chips for which `isLocalChip`
  is true (`is:snoozed`, `in:outbox`, `LOCAL_CHIPS`) are ours: leave them out of `q` and filter
  the results by snooze state / show the outbox instead. Our `is:pinned` and `is:done` go to Gmail
  as `is:starred` and `-in:inbox` (`gmailChip`).
- The search field parses with `parseQuery(text, cursor, true)`, so a finished `#tag` becomes a chip
  (`operator: "#"`, `TAG_CHIP`); `formatChip` writes it back as `#tag`, and a query read for
  searching (`parseQuery(text, cursor)`) leaves it in the free text.
- Also exported: `tokenize`, `tokenChip`, `formatChip`, `completionContext`, `dateDaysAgo`.

## Filters: `@inbox/shared/filters`

Gmail filters through `users.settings.filters` (list, create, delete; edit = delete + create).

- Load: `filterToEditor(filter)` → `{ conditions, actions }`. `conditions` is
  `{ kind: "rows", groups }` (groups of `{ match: "all" | "any" | "not", rows }`, all of which must
  hold; a Gmail-made filter is one "all" group) or `{ kind: "raw", query }` when it doesn't parse
  into rows (show a text box with the search text).
- Rows: `{ field, operator, value }`, fields `FILTER_FIELDS` (from, to, subject, hasWords,
  doesNotHave: operator `contains`; size: `larger`/`smaller`, value like `5MB`, `500KB` or bytes;
  hasAttachment: `is`, empty value). `rowError(row)` gives the message to show under an invalid
  row (`Enter a value`, `Enter a size like 5MB`); such rows are left out on save.
- Save: `editorToFilter(editor)` → `{ criteria, action }` for `filters.create`. The first row of
  each field in "all" groups fills Gmail's own fields; the rest becomes search text in
  `criteria.query` (`{a b}` for any, `-a -b` for not).
- Live preview: `conditionsToSearch(conditions)` (or `criteriaToSearch(criteria)`) is the
  search to run.
- Actions: `FilterActions` = `labelId` (one user label, `Label_…`), `archive`, `markRead`,
  `star`, `neverSpam`, `important` (`"always"`, `"never"`, null), `trash` (delete), `forward`
  (must be a verified forwarding address), plus `otherAddLabelIds`/`otherRemoveLabelIds` that
  Gmail had and the editor doesn't show (categories, a second label) — keep them so an edit doesn't
  drop them. `NO_ACTIONS` for a new filter.
- `excludeChats` is ignored and not written.

## Unsubscribe: `@inbox/shared/unsubscribe`

`parseUnsubscribe(listUnsubscribe, listUnsubscribePost)` (raw header values, or null) →
`{ oneClickUrl, mailto, url, preferred }`.

- `preferred === "oneClick"`: call the `unsubscribe` function with `oneClickUrl` (it POSTs
  `ONE_CLICK_BODY`, `List-Unsubscribe=One-Click`, as `application/x-www-form-urlencoded`).
- `"mailto"`: send `mailto` (`{ address, subject, body }`, already decoded; subject may be empty)
  through the normal send path. `address` is always one plain address: the list is decoded before
  it is split, only its first address is used, and a mailto whose first address isn't plain
  (an encoded `,`, line break, `<`…) offers nothing.
- `"url"`: open `url` in the browser.
- null: no unsubscribe offered.

## Calendar invites: `@inbox/shared/calendar`

- `parseCalendar(text, defaultTimeZone)` → `{ method, events }` or null, for a `text/calendar`
  part (`defaultTimeZone` = the device zone, used for floating times). `primaryEvent(invite)` is
  the event the card shows. Each event: `uid`, `sequence`, `recurrenceId`, `status`, `summary`,
  `location`, `description`, `organizer` (`{ email, name }`), `attendees` (`{ email, name,
  partstat, role, kind, rsvp }`; rooms have `kind` `ROOM`/`RESOURCE`), `start`/`end`
  (`{ kind: "date", date }` for all-day, end exclusive; or `{ kind: "dateTime", utc, timeZone }`),
  `recurrence` (English, e.g. `Weekly on Tuesday`, or null). Show times in the device zone,
  24-hour. A person's address is the `EMAIL` parameter when that is a plain address, else the
  `mailto:` value when that is; an organizer with neither is null (so no RSVP by mail) and an
  attendee with neither is left out.
- Method `CANCEL`: show the event as canceled; `REPLY`: someone answered the user's invite (show
  their `partstat`); `REQUEST`: offer Yes / Maybe / No.
- The user's entry: `findAttendee(event, ownAddresses)` (null when invited through a group; reply
  with the user's own address then).
- RSVP by mail (when the event isn't in the user's Google Calendar):
  `input = { event, attendee, partstat: "ACCEPTED" | "TENTATIVE" | "DECLINED", now, comment }`;
  `replyMail(input)` → `{ to, subject, markdown }` and `buildReply(input)` → the iCalendar REPLY.
  Build the message with compose's `buildMime` using `to`, `subject`, `markdown` and
  `calendar: { method: "REPLY", content: buildReply(input) }`, which adds a
  `text/calendar; charset=UTF-8; method=REPLY` part (base64) after the HTML in
  `multipart/alternative`; send it through the outbox like any message. Both throw when the event
  has no organizer, so don't offer RSVP by mail then, and `InvalidAddressError` when the
  organizer's or attendee's address isn't plain. The REPLY's `UID` is TEXT-escaped like `SUMMARY`;
  the mail's subject has line breaks folded to spaces.
- Also exported: `recurrenceText(rrule, timeZone)`, `ianaZone(tzid)`, `WINDOWS_ZONES`,
  `escapeText`, `foldLine`, `PRODID`.
- Library: web uses ical.js (pure JS) for parsing and VTIMEZONE math. Android: use biweekly
  (`net.sf.biweekly:biweekly`, pure Java) to parse and resolve VTIMEZONEs, or java.time when a
  TZID has no VTIMEZONE (with the same `WINDOWS_ZONES` map, and `ZonedDateTime.of` for skipped or
  repeated wall times). Port `recurrenceText`, `buildReply`, `foldLine` and `replyMail` as code
  rather than using a library's writer, so the output matches byte for byte.

## Link unwrapping: `@inbox/shared/links`

`unwrapLink(href)` → the real link for Google (`google.com/url?q=`), Outlook Safe Links,
Proofpoint v2/v3, Facebook, YouTube, Slack and LinkedIn redirects (nested ones too), else the link
unchanged. Wrappers that only hold an id (Mailchimp `list-manage.com`, SendGrid…) stay as they are.
Apply it to each `<a href>` when showing a message, before the click; keep the visible text.

## HTML cleaning: `sanitize-fixtures.json`

Cases both `cleanMessageHtml` implementations (web `web/src/lib/sanitize.ts`, DOMPurify; Android
`android/core/.../mail/EmailHtml.kt`, jsoup) must pass: scripts, handlers, `javascript:`/`data:`
links, forms, frames, `<base>`, `<meta>`, stylesheets and comments removed; `cid:` images swapped
for their data URLs; mail layout kept; `hasRemoteImages` for `<img>`, `srcset`, protocol-relative
sources and CSS `url()`. Outputs differ in serialization, so each case lists strings the
normalized output must contain or must not contain (rules in the file's `_doc`). Shared only checks
the file's shape; each client's spec runs the cases against its own cleaner.
