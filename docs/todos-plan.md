# Todos in inbox, latr on the same data — plan

Status: phases 1 (backend, shared logic, import script), 2 (web) and 3 (Android) built; phase 4
built in both repos, not yet deployed or released. Written 2026-11-11 against latr android-v1.1.6;
rewritten 2026-11-13 to the goal below.

## Goal

Support everything a latr todo supports, so latr's todos can be copied into inbox and keep working,
repeating todos included, and keep working the same way once latr itself moves to inbox's backend.

So:

- inbox's backend holds everything a latr todo has. It needn't copy latr's storage verbatim; it keeps
  latr's field names and values where that costs nothing, so latr's apps can later use it with
  little change.
- latr's todos (tombstones included) are copied in once, with nothing lost. Learned snooze
  suggestions are not copied.
- latr stays its own app in its own repo; later its apps switch to inbox's backend.

## Phases

1. **Backend (done).** The todo model in `users/{uid}/todos/{todoId}`, its rules and rules tests,
   account deletion covering todos, latr's todo logic in `shared/src/todos` with fixtures and the
   Android `:core` port, and the import script with dry run, run and verify, tested against the
   emulators. Described in design.md, "Todos".
2. **inbox web todos (done)** behind a Show todos setting: list rows, compose, edit, snooze, done, pin,
   undo, a Todos view, search, tags, repeats.
3. **inbox Android todos (done):** the same on Android, with reminders when a todo's snooze ends.
4. **latr on inbox's backend:** latr's web and Android apps switch Firebase project, latr's
   accounts are copied in, and latr's old project is retired.

## What exists today

### latr

- Its own Firebase project. Web at latr.hafa.cc (SvelteKit, GitHub Pages), Android
  `cc.hafa.latr` (Compose, Room). Repo `hafacc/latr`.
- Sign-in: Firebase Auth's own Google provider. The uid is Firebase's random id, not the Google
  account id; the Google account id is in the user's `providerData` (`google.com`).
- Works signed out: todos and snooze counts stay on the device (localStorage / Room). Signing in
  moves them into the account (`planMerge`); signing out leaves nothing behind.
- Firestore: `users/{uid}/todos/{uuid}` (every field below), `users/{uid}` itself holds learned
  snooze suggestions (`snoozeVotes`, the opt-in `snoozePickLog`, and possibly old per-device
  `snoozeStats` blocks), and `snoozePickLog/global` is an admin-read tally of picks.
- No Cloud Functions, no notifications, no server code.

Every field and state a latr todo can have:

| Field | Values | Notes |
|---|---|---|
| `text` | string | `*bold*`, `_italic_` and `#tags` kept as markers in the text |
| `state` | `"ACTIVE"` \| `"DONE"` | old docs may hold `"SNOOZED"`, read as active |
| `createdAt`, `modifiedAt` | epoch ms | `modifiedAt` orders an unmoved row |
| `serverModifiedAt` | Firestore timestamp | stamped by every write |
| `snoozeUntil` | local time, no zone, or null | web saves `YYYY-MM-DDTHH:mm:ss`; Android saves `LocalDateTime.toString()`, which drops zero seconds and keeps fractions. Snoozed iff not done and ahead; once past, the time it woke |
| `pinned` | bool | floats in Active |
| `position` | key or null | written by a move on web; a value that isn't a key is ignored |
| `deleted` | bool | tombstone; lists query `deleted == false`, so a doc without it is hidden. Bare tombstones `{deleted, text, modifiedAt, serverModifiedAt}` exist from deletes of docs that weren't there |
| `repeat` | `{every 1–7, unit day/week/month, weekday 1–7, monthDay 1–31 or -1, at HH:mm, anchor YYYY-MM-DD}` or null | |
| `repeatSpawned` | bool | done has made the next copy |
| `occurrence` | local `YYYY-MM-DDTHH:mm:ss` or null | the scheduled time a repeating copy stands for |

Older docs may lack the fields added later (`pinned`, `position`, `repeat`, `repeatSpawned`,
`occurrence`, even `deleted`); latr reads them as false or null, except that its lists hide a doc
without `deleted`, so the import copies one as deleted.

### inbox

- Firebase project `hafa-inbox`. Sign-in through the `signIn` function: Google code with Gmail
  scopes → Firebase custom token, uid `google:<Google account id>`.
- Thread snoozes are local wall times with no zone, latr's format and rule; each device decides
  when one ends. Its learned snooze menu is latr's, ported, with its own counts in
  `users/{uid}/meta/snoozeStats` (the same format as latr's `snoozeVotes`).
- Todos: `users/{uid}/todos/{todoId}` (design.md, "Todos").

## Data model

As in design.md, "Todos": latr's fields, names and values, held stricter. Every live doc has every
field; local times are always `YYYY-MM-DDTHH:mm:ss`; `state` is only `"ACTIVE"` or `"DONE"`;
`serverModifiedAt` must be the write's own time. Ids are latr's, carried over; new todos use
`crypto.randomUUID()` / `UUID.randomUUID()`.

Choices made for phase 1:

- **Strict local times.** The rules take only `YYYY-MM-DDTHH:mm:ss`; the import gives latr's
  Android times their seconds. latr's Android app must save that form too once it uses this backend
  (phase 4: `localIso` instead of `LocalDateTime.toString()` in its snooze sheet).
- **Limits** latr never had: text up to 100,000 characters, positions up to 1,000 characters, ids
  `[A-Za-z0-9_-]{1,128}`. The import still copies anything past them and reports it.
- **Hard deletes are allowed** (latr deletes an abandoned empty todo outright); every other delete is
  a tombstone.
- **Tombstones are copied**, so latr's signed-out merge can't bring back a deleted todo in phase 4.
- **The shared sort is latr web's:** pinned rows aren't moved by the sort, the Active list groups
  them. latr's Android sorted them first; the result shown is the same.
- **Ported with phase 2:** latr's grouping into date headings, drag planning, the held place of a
  todo being edited, tags, tag completion, styled text and search ranking (`shared/src/todos`
  `list.ts`, `tags.ts`, `styled.ts`, with `todo-list-fixtures.json` generated from latr's code and
  latr's tag fixtures). Android `:core` ports them (phase 3).

## Import

Done on 2026-11-21: a one-time script copied every latr todo into inbox (checked field by field)
and was then removed. design.md lists how each field was copied.

## Phases 2 and 3: todos in inbox

All of this shows only when Show todos is on: `users/{uid}.settings.showTodos`, bool, missing reads
as on.

- **Inbox list:** active todos sit among threads, each its own row, placed in the time sections by
  the time their key starts with; pinned todos join starred threads in Pinned. A todo row: a round
  check, the text with inline styles and tag chips, a repeat line when it repeats. A "Todos" view in
  the sidebar shows todos only, in latr's order.
- **Snoozed** todos with snoozed threads, by wake time; the same snooze menu.
- **Done** todos with done mail. Done on a repeating todo makes the next copy. Undo, 5 seconds, one
  slot, shared with mail actions.
- **Compose a todo:** "New todo" under Compose (`n`); a row opens at the top of the inbox for
  typing, and Enter leaves it open for the next one.
- **Search:** todos searched on the device (text with markers removed, as latr does) above mail
  results; `is:todo` / `-is:todo`; tag completion after `#`.
- **Notifications:** when a todo's snooze ends, inbox's Android app notifies the same way it does for
  threads (`meta/snoozeWaits` is already generic over items).

## Phase 4: latr on inbox's backend

latr stays in its own repo. Its changes:

- Firebase config: `hafa-inbox`.
- Delete account calls inbox's `deleteAccount`, which deletes the whole shared account, behind a
  strong warning naming inbox.
- Android saves local times with seconds (above).
- CSP and privacy text: data now held by inbox's backend.
- Snooze suggestions: latr reads and writes `meta/snoozeStats`, one menu for mail and todos, plus
  its opt-in pick log there (`picks`, `pickLog`) and the `snoozePickLog/global` tally, whose rule
  moved into inbox's rules. latr ignores Show todos.
- Then: export latr's old project, `--dry-run`, `--run`, `--verify`, release latr on `hafa-inbox`, sign
  in again on each device.

## Risks

- **A latr build still on its old project after the copy** keeps writing there; the cutover must be one
  switch, then `--run` again picks up anything newer.
- **Deleting from latr deletes mail state** once both share an account; behind a strong warning.

## Decisions

1. Todos live in `hafa-inbox`, with latr's field names and values, held to a strict shape.
2. latr stays in its own repo; logic is ported, not shared as a package.
3. Learned snooze suggestions are not copied.
4. Tombstones are copied.
5. Each todo is its own row in the inbox, plus a Todos view; a Show todos setting turns them off.
6. Snooze times are local wall times with no zone for todos and threads alike.
7. latr uses inbox's `meta/snoozeStats`, so mail and todos learn one snooze menu.
