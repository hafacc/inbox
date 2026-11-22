# Encrypted mail (OpenPGP): contract for the clients

What the web and Android encryption UIs build against. Pure logic: `@inbox/shared/pgp`
(`shared/src/pgp/`, openpgp.js and hash-wasm), pinned by `shared/testdata/pgp-fixtures.json`, which
Android's `:core` runs too. Server: `functions/src/key-publish.ts` (publishes public keys),
`functions/src/lib/notification.ts` (encrypted pushes), the Key Worker `workers/wkd`. Rules:
`firebase/firestore.rules`. Background: design.md "Encrypted mail (OpenPGP)".

| Piece | Import | Fixtures key |
|---|---|---|
| Key pair | `generateKeyPair`, `readPublicKey`, `readUnlockedPrivateKey`, `fingerprintOf` | `keys` (test people; tests make their keys on each run) |
| Sealing | `seal`, `unseal`, `SealError` | `seal`, `unsealFailures` |
| Recovery phrase | `generatePhrase`, `parsePhrase`, `sealWithPhrase`, `unsealWithPhrase`, `recoverySecret`, `wordsFromBytes` | `phraseWords`, `parsePhrase`, `recovery` |
| Key lookup | `wkdUrls`, `wkdHash`, `vksUrl`, `keyFromAnswer`, `encryptionDecision` | `wkd`, `zbase32`, `decision` |
| Autocrypt | `autocryptHeaderValue`, `parseAutocrypt`, `autocryptFor` | `autocrypt` |
| PGP/MIME | `encryptMime`, `protectedParts`, `assemblePgpMime`, `decryptMessage`, `encryptionKind`, `ADDRESS_HEADERS`, `parseMime`, `walk`, `decodedText`, `decodedBody`, `decodeHeaderValue` | `protect`, `decrypt`, `headerDecode` |
| Bcc split | `planEncryptedSends`, `protectedParts(mime, { bccCopy })` | `encrypted-send-fixtures.json`: `plan`, `protect` |

The EFF large wordlist is `shared/wordlists/eff_large_wordlist.txt` (CC BY 3.0 US, see the README
beside it); `EFF_LARGE_WORDLIST` holds the same words in the same order.

Android: Bouncy Castle (`bcpg` for OpenPGP, `bcprov`'s `Argon2BytesGenerator` for Argon2id) and
JCA (`Mac` HmacSHA256 for HKDF, `Cipher` `AES/GCM/NoPadding`). HKDF here is one extract and one
expand block: `prk = HMAC(key = 32 zero bytes, secret)`, `okm = HMAC(prk, info || 0x01)`.

## Keys

- One key pair per account, for its @hafa.cc address: `generateKeyPair({ address, name })`. Ed25519
  (EdDSA) primary key for signing, Curve25519 (ECDH) subkey for encryption, v4 format with the
  older "legacy" algorithm ids that GnuPG, Thunderbird, Proton, Mailvelope and FlowCrypt all read.
  One user id, no expiry. The private key comes back armored and unprotected: never store it
  anywhere but sealed (below) or in the device's secure storage.
- Mail is encrypted only from @hafa.cc, so an account without an active address has no use for a
  key; offer setup once the address is active.

## Firestore: `users/{uid}.pgp`

Written by clients, whole (`set` with merge on the `pgp` field, or `update`); the rules refuse any
other shape. The server reads only `publicKey`.

```
pgp:
  publicKey: string                 // armored public key ("-----BEGIN PGP PUBLIC KEY BLOCK-----")
  sealed: [                         // one per passkey, at most 8
    { credentialId: string,         // the passkey's credential id, base64url
      salt: string,                 // its PRF salt (eval.first), base64url, 32 random bytes
      sealedPrivateKey: string,     // seal(prfOutput, privateKey)
      name: string,                 // label shown in Settings, ≤ 100 characters ("Pixel 9", "Chrome on Mac")
      addedAt: Timestamp }
  ]
  recoverySealed: { salt, sealedPrivateKey, kdf: { name: "argon2id", memoryKiB, iterations, parallelism } }
```

Rules: all three fields present; `publicKey` must start with the public key armor and must not
contain `PRIVATE KEY`; sealed values must be base64; `kdf` must be Argon2id with at least 19 MiB.
Removing the whole `pgp` field is allowed (resetting encryption). Adding a passkey = rewrite
`sealed` with one more entry; removing = rewrite without it.

## Sealing

`seal(secret, armoredPrivateKey)` with a 32-byte secret → base64 of `0x01 || nonce(12) ||
AES-256-GCM ciphertext and tag`, key = HKDF-SHA256(secret, empty salt, info
`inbox.hafa.cc/pgp-seal/v1`). `unseal` throws `SealError` on a wrong secret. The secret is either a
passkey's PRF output or the recovery phrase's Argon2id output.

## Passkeys (WebAuthn PRF)

Relying party id `inbox.hafa.cc` on both platforms, so one passkey (synced by Google Password
Manager) serves web and Android. The PRF extension gives 32 bytes that only exist after the user
unlocks the passkey; each passkey gets its own random 32-byte salt, stored as `salt`.

**Create** (setup, or "Add a passkey" on an unlocked device):

```js
const salt = randomBytes(32);
const credential = await navigator.credentials.create({ publicKey: {
  rp: { id: "inbox.hafa.cc", name: "inbox" },
  user: { id: utf8(uid), name: gmailAddress, displayName: hafaAddress },
  challenge: randomBytes(32),           // nothing verifies it server-side; the passkey only unlocks a seal
  pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }],
  authenticatorSelection: { residentKey: "required", userVerification: "required" },
  extensions: { prf: { eval: { first: salt } } },
}});
const prf = credential.getClientExtensionResults().prf;
```

- `prf.enabled !== true`: this passkey provider can't do PRF (Bitwarden, Proton Pass, some
  security keys). Say so ("This password manager can't unlock mail. Use Google Password Manager or
  iCloud Keychain.") and don't store anything.
- `prf.results.first` present: that is the secret. Some providers enable PRF at creation but don't
  evaluate it then; if `results` is missing, run one **get** (below) with this credential at once.
- Store `{ credentialId: toBase64Url(rawId), salt: toBase64Url(salt), sealedPrivateKey:
  await seal(secret, privateKey), name, addedAt }`.

**Unlock** (sign-in on a new device, after sign-out):

```js
const assertion = await navigator.credentials.get({ publicKey: {
  rpId: "inbox.hafa.cc", challenge: randomBytes(32), userVerification: "required",
  allowCredentials: pgp.sealed.map((s) => ({ type: "public-key", id: fromBase64(s.credentialId) })),
  extensions: { prf: { evalByCredential: Object.fromEntries(
    pgp.sealed.map((s) => [s.credentialId, { first: fromBase64(s.salt) }])) } },
}});
```

Find the entry whose `credentialId` is the assertion's id, take
`getClientExtensionResults().prf.results.first`, `unseal` its `sealedPrivateKey`, check
`fingerprintOf(privateKey) === fingerprintOf(pgp.publicKey)`, then store it on the device (below).
No match or no PRF result: offer the recovery phrase.

**Android** (Credential Manager, `androidx.credentials` 1.5+): the same JSON as WebAuthn's
`PublicKeyCredentialCreationOptionsJSON` / `RequestOptionsJSON`, with binary fields base64url:
`CreatePublicKeyCredentialRequest(requestJson)` with `"extensions": {"prf": {"eval": {"first":
"<salt>"}}}`, and `GetPublicKeyCredentialOption(requestJson)` with `"prf": {"evalByCredential":
{"<credentialId>": {"first": "<salt>"}}}`. The result's `registrationResponseJson` /
`authenticationResponseJson` carries `clientExtensionResults.prf.results.first` (base64url). The
provider applies WebAuthn's salt hashing, so the secret is the same as the browser's for the same
passkey and salt. Needs Digital Asset Links: `https://inbox.hafa.cc/.well-known/assetlinks.json`
listing `cc.hafa.inbox` and its signing certificate SHA-256 with
`delegate_permission/common.handle_all_urls` and `delegate_permission/common.get_login_creds`.

Support (November 2026): Google Password Manager passkeys support PRF on Android (through
Credential Manager) and in Chrome, Edge and Samsung Internet; on desktop Chrome only passkeys saved
to Google Password Manager have it (a passkey kept only in the local Chrome profile does not).
Third-party Android providers can't yet reliably advertise PRF through Credential Manager. iOS 18+
iCloud Keychain supports it on the device itself. Treat "no PRF" as normal and fall back to the
recovery phrase.

## Recovery phrase

- Setup: `generatePhrase()` → 8 words (~103 bits). Show once, numbered, with "Copy" and a
  confirmation step (type two of the words back). `sealWithPhrase(phrase, privateKey)` →
  `recoverySealed`. Never store or log the phrase.
- Unlock: let the user type freely; `parsePhrase(text)` marks `unknown` words as they type (spaces
  separate words; hyphens are part of some words, e.g. `t-shirt`). When `normalized` is non-null,
  `unsealWithPhrase(text, pgp.recoverySealed)` (Argon2id with 64 MiB, ~1 s; show progress). A
  `SealError` means a wrong phrase. Then offer "Add a passkey for this device".
- "New recovery phrase" (unlocked device): generate, show, confirm, replace `recoverySealed`.
- Argon2id: v1.3, password = UTF-8 of the normalized phrase, salt 16 random bytes, output 32 bytes,
  settings from the stored `kdf` (new seals use `RECOVERY_KDF`: 65,536 KiB, 3 passes, 1 lane).

## The unlocked key on the device

- Web: on first unlock make an AES-GCM `CryptoKey` with `extractable: false`, store the CryptoKey
  object itself in IndexedDB (structured clone keeps it non-extractable), and store the private key
  encrypted with it (nonce + ciphertext) next to it. Decrypt into memory when needed; the service
  worker reads the same IndexedDB store to decrypt notifications.
- Android: an AES-256-GCM key in Android Keystore (`setUserAuthenticationRequired(false)`, so
  notifications can decrypt in the background; StrongBox when available), the private key encrypted
  with it in app-private storage.
- Sign-out deletes both. Sign-out also clears everything else the app keeps on
  the device (cached mail, composer state, the key cache). Never write the unlocked key to Firestore, Gmail, logs or
  crash reports.
- Settings: list `sealed` by `name` and date with "Remove"; warn when one is left ("Only one passkey
  unlocks your mail. Keep your recovery phrase safe."). "Reset encryption" (lost everything)
  makes a new key pair and replaces `pgp`; mail encrypted to the old key can't be opened again, so
  say that first.

## Publishing (server)

When `users/{uid}.pgp.publicKey`, the address or its setup changes, `publishKey` writes the key to
the Key Worker's store while the address is `active` (and removes it when it stops); a daily job
catches up. It publishes only a public key with a user id for that exact address, and drops other
user ids. Clients do nothing extra. Published at
`https://openpgpkey.hafa.cc/.well-known/openpgpkey/hafa.cc/hu/<wkdHash(local)>`.

## Looking up recipients' keys

For each recipient chip (To, Cc and Bcc), in order, stopping at the first that gives a key:

1. The device's key cache (below).
2. WKD. Android: fetch `wkdUrls(address).advanced`, then `.direct` (5 s timeout, at most 256 KB).
   Web: browsers can't read most domains' WKD (no CORS), so call the Key Worker:
   `GET https://openpgpkey.hafa.cc/lookup?email=<address>` (allowed from `https://inbox.hafa.cc`
   and `http://localhost:5173`; any other origin, or none, gets 403); 200 = binary key, 404 = none,
   429 = too many lookups of other domains from this IP (30 a minute; a failure, not a miss).
   @hafa.cc addresses come from the Worker's own store either way.
3. keys.openpgp.org: `GET vksUrl(address)` (CORS allowed), 200 = armored key, 404 = none.

Pass every answer through `keyFromAnswer(bytesOrText, address)`: it returns the armored key only
for a public key with a user id for that address that can be encrypted to, else null.

Cache per address (IndexedDB / Room): `{ key | null, source: "autocrypt" | "wkd" | "vks", fetchedAt,
seenAt }`. Reuse a found key for 7 days and a miss for 1 day before looking up again; look up in the
background while composing, never blocking typing. An `Autocrypt` header on incoming mail
(`autocryptFor(headerValues, fromAddress)`, from mail that isn't spam) sets the sender's key when
the message is newer than `seenAt`; turn its `keydata` into a key with `keyFromAnswer`.

## Sending

1. `encryptionDecision(from, [...to, ...cc, ...bcc].map(m => m.email), keysByAddress)`. Show a lock
   on each chip with a key, and the send button as a lock labeled "Encrypted" when `encrypt`. There
   is no switch.
   - **This device not unlocked** (`encrypt`, but no unlocked key here): never send unencrypted on
     its own. Keep the "Encrypted" button, and say under the recipients that sending asks to unlock,
     with "Unlock" and "Send without encryption". Send, Schedule, Send now and saving an edited
     scheduled send first wait for every recipient's lookup, then open the unlock prompt (passkey,
     or recovery phrase). Unlocked: it goes encrypted. Prompt closed without unlocking: nothing is
     sent or rescheduled; the composer stays open with "Not sent. Unlock encrypted mail, or choose
     Send without encryption." "Send without encryption" is the only way such a message goes
     unencrypted: it sends at once (with undo) and the line then reads "Not encrypted — you chose to
     send without encryption" with "Encrypt instead". The choice lasts while that composer is open
     and is not saved with the draft. Building the messages of a send or a scheduled send's edit
     (step 4) without an unlocked key and without that choice must fail ("locked"), not fall back
     to plain text. Autosaved drafts (step 3) on a locked device are unchanged.
2. Mail from @hafa.cc carries `["Autocrypt", await autocryptHeaderValue(address, pgp.publicKey)]`
   in `extraHeaders`, encrypted or not (only when the account has a key). Mail from Gmail addresses
   never does.
3. While composing, the draft is the whole message encrypted to every recipient's key and the
   sender's: `encryptMime(buildMime(input), { encryptionKeys: [...recipientKeys, pgp.publicKey],
   signingKey: unlockedPrivateKey, boundary: randomBoundary() })`. It keeps `Bcc` outside, so it is
   never sent as it is when there is a Bcc. The sender's own key must be in `encryptionKeys`: drafts
   and Sent copies are this ciphertext. The outer Subject is `...`; the real one, From/To/Cc and the
   reply headers travel inside (protected headers, RFC 9788 / Thunderbird's
   `protected-headers="v1"`).
4. On Send or Schedule, split it the way Thunderbird does, because an OpenPGP message lists the key
   ids it is encrypted to and any recipient can read that list:
   `planEncryptedSends({ from, to, cc, bcc })` → the messages, in order. For each, build
   `buildMime({ ...input, to, cc, bcc, boundary: randomBoundary() })` and encrypt it with
   `encryptMime(mime, { encryptionKeys: [...encryptTo.map(keyOf), pgp.publicKey], signingKey,
   boundary: randomBoundary(), bccCopy: kind === "bcc" })`.
   - `main` (when To or Cc is not empty): To and Cc, no Bcc, encrypted to the To and Cc keys.
   - one `bcc` copy per Bcc recipient: encrypted to that recipient's key alone (and the sender's).
     Outside it has `From`, `Bcc: <that recipient>` and no `To` or `Cc`, so Gmail and the server
     deliver it to that recipient alone (both address mail from the headers); inside it has the real
     `To` and `Cc` and `Bcc: <that recipient>` (RFC 5322 §3.6.3's choice for a Bcc recipient's copy:
     a Bcc field naming only them). Delivery strips the outer `Bcc`, so a reader that can't decrypt
     sees no recipients; one that can shows the inner To, Cc and "Bcc: you".
   - A Bcc address also in To or Cc gets no copy; the same Bcc address twice gets one.
   - Without Bcc the plan is the one `main` message, the same as before.
   Each planned message is its own Gmail draft and outbox doc: docs/compose-contract.md, "Encrypted
   sends with Bcc". With no Bcc, saving the draft as in step 3 (no `Bcc`) already is the `main`
   message.
5. Reopening an encrypted draft: decrypt it (it is encrypted to the sender too) with the account's
   own public key among `verificationKeys` (so its signature is `valid` and the inner To/Cc are
   kept; a Bcc copy has none outside), and rebuild the composer from the inner message and
   `protectedHeaders`. A planned send's Bcc list is in its
   copies (outer `Bcc`, or `protectedHeaders.bcc`).
6. The server sends @hafa.cc mail through Resend byte for byte below the headers; the Sent copy is
   the same ciphertext (it only sets `Date`/`Message-ID` and drops `Bcc` outside). Every planned
   message lands in Sent, through Gmail (`drafts.send` always files what it sends) and Resend alike:
   the main message without Bcc, and each copy with its `Bcc`, which keeps the record of who was
   Bcc'd.

## Reading

- Encrypted iff Gmail's `payload.mimeType` is `multipart/encrypted` (PGP/MIME), or one of the
  payload's direct parts is (a mailing list that added a footer wraps it in `multipart/mixed`), or
  the message's text holds `-----BEGIN PGP MESSAGE-----` (inline). `encryptionKind` and
  `decryptMessage` apply the same rule. Fetch it with `format=raw` and call
  `decryptMessage(fromBase64(raw), { decryptionKeys: [privateKey], verificationKeys })`, where
  `verificationKeys` are the keys we had for the sender before this message (cache, WKD,
  keys.openpgp.org).
- Show `protectedHeaders.subject` (and From/To/Cc from there when present) in place of the outer
  `...`. The inner From, Reply-To, To and Cc (`ADDRESS_HEADERS`) can be written by anyone who has
  the user's public key, so `decryptMessage` takes them from inside only when the signature is
  `valid` and the signing key has a user id for the inner From address; otherwise
  `protectedHeaders` carries the outer values for them (or leaves them out when the outer message
  has none), and `mismatchedHeaders` lists those whose inner value differed. When it is not empty,
  show a warning under the sender: "The sender inside this encrypted message doesn't match its
  signature; showing the outside one." Reply-To, To and Cc used for a reply come from the same
  `protectedHeaders`, so a reply never goes to an address the signature doesn't vouch for. A Bcc copy also has `protectedHeaders.bcc` (only the reader's address): show it as Bcc. The thread list shows the decrypted subject once the device has decrypted it; until then
  "Encrypted message".
- Render `entity` like any message (`walk` it for text/plain, text/html and attachments;
  `decodedText` / `decodedBody`). Attachments decrypt with the body; fetch nothing more.
- `signature` is decided by the first signature made by one of `verificationKeys` (a message can
  carry several); `unknown-key` only when none is.
- Badge per message: a lock for encrypted, plus `signature.status`:
  `valid` → check ("Signed by Ana"); `unknown-key` → lock only (and if the message's own Autocrypt
  header gave a key, cache it for next time); `invalid` → warning "The signature doesn't match the
  key we have for Ana"; `unsigned` → lock only.
- `PgpError` `wrong-key` → "Can't open: encrypted to another key"; `malformed` → "Can't open this
  encrypted message"; no key on this device yet → "Unlock encrypted mail" (passkey prompt).
- Replies stay encrypted by the sending rule above; the sender's key comes from the cache, the
  message's Autocrypt header, or WKD.

## Notifications (contract change)

The FCM data payload has a new field `encrypted`: `"true"` or `"false"`. When `"true"`, `subject`
and `snippet` are empty (the server can't read the message). Android fetches the message
(`format=raw`), decrypts it on the phone and shows the real subject and start of the text; the web
service worker does the same when the unlocked key is in IndexedDB, else shows "Encrypted message
from {from}". Done and Reply work as before.

## Offline cache and search

- Decrypted messages that fall in the offline cache are stored decrypted, but only inside the
  device's encrypted storage: web encrypts each cached decrypted body with the same non-extractable
  device key; Android keeps them in Keystore-encrypted storage. Offline search covers them.
- What the composer keeps on the device (its contents, quotes of decrypted mail included, and draft
  saves queued for Gmail) is sealed the same way whenever the account has a key: web seals each
  record with the device key (AES-GCM, as the cache); Android keeps it in the same
  Keystore-encrypted storage. On a device that hasn't unlocked the key there is nothing to seal
  with, so composer state is not written to the device at all (the Gmail draft still saves);
  records sealed under a device key that is gone (after sign-out, or the key changed on another device) are deleted unread. Records
  kept from before the account had a key are sealed the next time they are read on an unlocked
  device.
- Gmail can't search inside encrypted mail. With every online search, also run the user's
  operators (from:, to:, after:, label: … but not the free-text words) plus
  `{filename:encrypted.asc "BEGIN PGP MESSAGE"}` as a second Gmail query, a page (50) at a time,
  newest first. For each message not in the cache, fetch raw, decrypt, and match the free-text words
  (case-insensitive, all words) against the protected subject and the text parts; add matches to
  the results, marked with a lock. Keep nothing from this fetch beyond the results on screen and,
  for the session, which message ids each search checked and which threads matched, so loading
  the search again (a refresh, or coming back to it) lists those matches at once and checks only
  mail not checked yet. Stop after 200 messages a run or when the user leaves the search, and show
  "Searching encrypted mail…" while it runs.
