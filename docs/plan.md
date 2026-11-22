# Build plan

Order chosen so each step is usable on its own. Web first (fastest to iterate), Android follows each
step once web settles. Design: [design.md](design.md).

## Repo layout

```
package.json        bun workspaces: shared, web, functions, firebase, workers/*; bun pinned (packageManager)
biome.json          lint + format for all TS
eslint.config.js    doc comments required on exported TS declarations (only that rule)
scripts/            typecheck wrapper that skips the latr-copied snooze files
shared/             @inbox/shared — pure TS used by web and the wkd Worker, with fixtures in shared/testdata
                    (functions tests read the fixtures)
web/                SvelteKit 3 / Svelte 5 static SPA, Tailwind 4, Geist (GitHub Pages → inbox.hafa.cc)
functions/          Firebase Functions v2 (Node 22, TypeScript); scripts/ holds one-off admin scripts
workers/            Cloudflare Workers: wkd (openpgpkey.hafa.cc)
firebase/           firestore.rules + rules tests; firebase.json at the root
android/            Gradle: :core (JVM, ports shared/ logic, runs shared/testdata) + :app (Compose)
docs/
```

Conventions: bun, biome, warnings as errors, public Firebase config committed, no secrets in env
files (`functions/.env.hafa-inbox` holds only public deploy params), package id `cc.hafa.inbox`.

Snooze suggestions are copied from latr (`shared/src/snooze-suggest.ts`, Android
`android/core/.../core/snooze/SnoozeSuggestions.kt`, `shared/testdata/snooze-fixtures.json`) rather
than shared as a package for now; extracting a common package is a later cleanup.

The compose editor's text styles (`*bold*`, `_italic_`) are latr's the same way: the parser in
`shared/src/compose/inline-style.ts` and Android `android/core/.../core/compose/InlineStyle.kt` is
ported from latr's `web/src/lib/utils/inline-style.ts` and `android/.../util/InlineStyle.kt`, and
`shared/testdata/inline-style-fixtures.json` is latr's `testdata/inline-style-fixtures.json` byte
for byte. Keep them in step with latr until they move to a common package.

## Steps

1. **Read and triage (web).** Repo skeleton; `signIn` / `gmailToken` functions (KMS behind an
   interface, a local key in the emulator); Firestore rules + tests; web sign-in, inbox list from
   Gmail (Pinned = starred at top, then time sections: Today, Yesterday, this week, then months through last year, then one per earlier year; `shared/testdata/section-fixtures.json`), thread opens in place, Done, Pin, Snooze
   (learned menu + date picker, Firestore + archive), hideable sidebar, keyboard (j/k/o/e/p/b/esc/?),
   local cache for offline reading.
2. **Android catch-up for step 1.** :core/:app, Google sign-in via AuthorizationClient (offline
   code), same features, Room cache.
   Follow-up: customizable swipes. A setting picks the action for each swipe direction on Android
   (Done, Snooze, Pin, Label, or nothing), defaulting to latr's directions. Stored in the
   `users/{uid}` settings so it follows the account; the list code already reads the action from a
   single direction-to-action mapping.
3. **Notifications.** Gmail watch → Pub/Sub → `gmailChanged` (notify); FCM to web + Android with
   Done/Reply. Snoozes are decided on each device (design.md "Snooze, pin, done"); Android notifies
   locally when one ends, and `snoozePing` pings a phone when a snooze change moves the one it
   waits on.
4. **Compose and send.** Styled text → MIME on device, drafts, outbox + Cloud Tasks, Gmail send and
   Resend SMTP with Sent copy, undo send, schedule send with its own learned times and the
   vary-time setting, signature, contacts autocomplete.
5. **@hafa.cc addresses.** Claim flow, Cloudflare destination + rule via API, auto-confirm.
6. **Search, filters, labels, unsubscribe, images.** Operator chips + Tab completion, Gmail filter
   editor, label menu, unsubscribe (one-click function), images held back until shown, HTML
   sanitizing.
7. **Calendar invites.** Invite card, RSVP via Calendar API or iCalendar reply.
8. **Encryption.** OpenPGP keys, passkey unlock + recovery phrase, WKD Worker, encrypt/decrypt,
   encrypted search. Server and shared half done (`shared/src/pgp`, `publishKey`, `workers/wkd`,
   encrypted notifications); clients build against docs/pgp-contract.md.
9. **Release plumbing.** CI (build, web deploy, Android cut/publish), privacy and support pages,
   Play listing.
10. **Todos.** latr's todos in this backend, then in both apps, then latr's own apps on it
    (docs/todos-plan.md). Backend, shared logic and the latr import script done.

## Later

Ideas kept for some day, not planned:

- A typed command box on wide screens (Ctrl/⌘K outside the todo editor): type an action such as "snooze fri 09:00"
  or "label receipts" and press Enter. Needs a parser for typed times and label names.
