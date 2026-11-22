# @hafa.cc addresses: contract for the clients

What the web and Android address UIs build against. Server: `functions/src/addresses.ts`
(callables and the `addressCheck` task), `functions/src/lib/address.ts` (the flow),
`shared/src/address-name.ts` (name rules and refusal wording, which web and Android run too). Rules: `firebase/firestore.rules`. Background:
design.md "Claiming an @hafa.cc address" and "Receiving @hafa.cc mail".

## Flow

1. The user types a name. Check it on the device with the rules below so mistakes show while
   typing; the server checks again.
2. Call `claimAddress({ name })`. On success the name is the user's at once, and
   `users/{uid}.address` reads `name@hafa.cc`. The address is permanent: one per account, never
   given up, and a name once claimed never passes to another account, even after its account is
   deleted. Say so before the user confirms the name. Before showing the name field, call
   `formerAddress()`: when it answers an address, this account held it before it was deleted, it is
   the only name the account can claim, and the screen should offer "Take back name@hafa.cc"
   (claiming that name) in place of the field.
3. Watch `users/{uid}.addressSetup` (below) for progress. Usually it goes `pending` → `verifying` →
   `active` within a minute without the user doing anything: Cloudflare mails a confirmation link
   to the user's Gmail, and the server finds that mail, opens the link and trashes the mail.
4. If `addressSetup.error.code` becomes `confirm-by-hand`, ask the user to open the link in the
   email from Cloudflare (check Spam too), then offer a "Check again" button that calls
   `checkAddress()`. The server also keeps checking by itself for about a day, so the button is
   mostly for impatience. Once the last confirmation mail is over a day old (Cloudflare's links
   last about that long), `checkAddress()` and claiming the same name again send a fresh one.
5. Once `active`, offer the address as a From choice. Neither first-run setup nor Settings offers a
   label filter; users who want one make it in the filter editor.

There is no way to give an address up. Deleting the account (`deleteAccount`) stops its forwarding,
so mail to it bounces, but keeps the name taken: `addresses/{name}` stays behind holding only
`holderHash` (which every claim carries), an HMAC-SHA256 of the account id under a server key
(sealed with Cloud KMS at `serverKeys/addressHolder`). If the same Google account signs in again, it may claim that name
again, which restores forwarding through the usual setup; it is refused any other name
(`had-address`), and every other account is refused that name (`taken`).

## Name rules

One copy, `shared/src/address-name.ts`, run by the server and web; Android's `core` address/AddressName.kt runs
the same fixtures (`shared/testdata/address-name-fixtures.json`) and reads the same reserved list
(`shared/address/reserved-names.json`).

- Trim, lowercase, and drop a trailing `@hafa.cc` before checking (so `Ana` claims `ana`).
- 2–32 characters of `a-z`, `0-9`, `.` and `-`.
- Starts and ends with a letter or digit; no two of `.`/`-` in a row.
- Not reserved: role names (`support`, `postmaster`, `abuse`, `admin`, `hostmaster`, `webmaster`,
  `noreply`, `root`, `security`, `info`, `help`, `billing`, `mailer-daemon`, …) and hafa.cc
  subdomains (`inbox`, `img`, `openpgpkey`, `www`, `mail`, `send`, `auth`, every app's name, …).
  A name is also reserved when it equals one of these with its dots and hyphens removed
  (`no-reply`, `post.master`). Both apps check all of this as the name is typed, from the same list
  the server uses.

## Callables

All need a signed-in user. Errors are `HttpsError`s whose `details.reason` says why:

| Callable | Input | Result |
|---|---|---|
| `claimAddress` | `{ name: string }` | `{ address, setup }` |
| `checkAddress` | none | `{ address, setup }` |
| `formerAddress` | none | `{ address: string \| null }`: the address this account held before it was deleted |

`setup` has the shape of `addressSetup` below, without `updatedAt`.

| `details.reason` | Code | Meaning | Suggested copy |
|---|---|---|---|
| `length` | invalid-argument | not 2–32 characters | "Use 2 to 32 characters." |
| `characters` | invalid-argument | other characters, or no `name` | "Use letters, digits, dots and hyphens." |
| `edges` | invalid-argument | starts or ends with `.`/`-` | "Start and end with a letter or digit." |
| `punctuation` | invalid-argument | `..`, `.-`, `--`… | "Don't put dots or hyphens next to each other." |
| `reserved` | invalid-argument | reserved name | "That name is reserved." |
| `taken` | already-exists | someone else holds it, held it and deleted their account, or the domain's owner forwards it by hand | "That name is taken." |
| `has-address` | already-exists | the user holds another name (one each, for good) | "You already have an address." |
| `had-address` | already-exists | the account held another name before it was deleted; `details.address` is that address | "Your account already had name@hafa.cc — you can only take that one back." |
| `no-gmail` | failed-precondition | no stored Google sign-in | sign in again |
| `releasing` | failed-precondition | claimed again while the account is being deleted | "Try again in a moment." |
| `not-configured` | failed-precondition | account deletion while Cloudflare isn't set up (from `deleteAccount`) | "@hafa.cc addresses aren't available yet." |
| `no-address` | not-found | check with no address | refresh the screen |

Claiming the name the user already holds is not an error: it resumes setup, like `checkAddress`.

## Firestore

Both fields are on `users/{uid}`, written only by the server (the rules refuse any client write
that changes them, and refuse deleting the doc while `address` is set). They are `null` or absent
when the user has no address.

| Field | Type | Notes |
|---|---|---|
| `address` | string \| null | `name@hafa.cc` |
| `addressSetup.status` | `"pending"` \| `"verifying"` \| `"active"` \| `"releasing"` | see below |
| `addressSetup.error` | `{ code, message }` \| null | what setup is waiting out |
| `addressSetup.updatedAt` | Timestamp | last server write |

| Status | Meaning | Suggested copy |
|---|---|---|
| `pending` | claimed; Cloudflare doesn't have the Gmail address yet | "Setting up…" |
| `verifying` | waiting for the Gmail address to be confirmed | "Confirming your Gmail address…" |
| `active` | mail to the address reaches the inbox | "Mail to name@hafa.cc arrives here." |
| `releasing` | the account is being deleted and its forwarding removed; if deletion fails and isn't retried, the server puts it back to `pending` within the hour | "Releasing…" |

| `error.code` | Meaning | Suggested copy |
|---|---|---|
| `confirm-by-hand` | automatic confirmation hasn't worked (10 minutes passed, or opening the link didn't confirm) | "Open the link in the email from Cloudflare to finish." |
| `cloudflare` | a Cloudflare call failed (the server retries), or a forwarding rule the domain's owner made by hand already uses the address (the server never touches it) | "Still setting up; this can take a few minutes." |
| `not-configured` | the owner hasn't finished Cloudflare setup | "@hafa.cc addresses aren't available yet." |

Sending: the outbox accepts `From: name@hafa.cc` as soon as the name is claimed (it checks
`addresses/{name}`), but replies only arrive once `active`, so offer it as a From choice from
`active` on.
