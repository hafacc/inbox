# inbox

Inbox-style email client on top of Gmail: SvelteKit web app (`web/`, served at inbox.hafa.cc),
native Android app (`android/`), Firebase backend (`functions/`, `firebase/`), Cloudflare Workers
(`workers/`), and pure shared logic with fixtures (`shared/`). Design: `docs/design.md`. Build order:
`docs/plan.md`. Owner setup: `docs/private/setup.md` (git-ignored, local only).

## Rules

- Gmail is the source of truth for mail; Firestore holds only what Gmail can't (see design.md).
- Behavior is shared, not code: logic web and Android must agree on lives in `shared/` with JSON
  fixtures under `shared/testdata/`, and Android's `:core` runs the same fixtures.
- Snooze is derived (latr's rule): a thread is snoozed iff `snoozeUntil` is in the future and no newer
  message arrived; nothing writes to Firestore when a snooze lapses.
  `snoozeUntil` is a local wall time with no zone, as in latr; each device decides on its own clock
  and a snoozed thread stays in Gmail's inbox.
- All clock times are 24-hour (`HH:mm`) on every surface.
- bun everywhere; biome for lint/format; warnings are errors. `bun run lint`, `bun run test`.
- Shared tests run in America/New_York (`shared/test-setup.ts`).
- `shared/src/snooze-*.ts` and `shared/testdata/snooze-fixtures.json` started as copies of latr's. Inbox's
  copies are now the source; latr will become a todo front end on inbox, so don't keep them in step with latr.
- `docs/privacy.md` and `docs/support.md` are the only copy of those pages: the web prerenders them
  at `/privacy` and `/support`, and Android's build copies them into its assets and shows them in a
  sheet (`InfoSheet`) with a button to the web page.
