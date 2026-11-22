# Images and unsubscribe: contract for the clients

What the web and Android readers build against for remote images and one-click unsubscribe.
Server: `functions/src/unsubscribe.ts` (`unsubscribe`). Background: design.md "Images and
trackers" and "Unsubscribe".

## Remote images

Remote images stay blocked until the reader shows them for a message, or the setting below shows
them without asking. Then they load straight from the sender's servers, which see the device's IP
address and when it loaded them (design.md "Images and trackers").

1. Sanitize first. Remote images are `<img src>`, `srcset` entries, `background` attributes and
   CSS `url(...)`, `http:`, `https:` or protocol-relative (`//host`, taken as `https:`). `cid:`
   images (inline attachments, loaded from Gmail's attachment API) and `data:` images are local;
   every other scheme is dropped.
2. While blocked, the frame's CSP is `img-src data: blob:` and remote images are hidden (they
   keep their space, with no broken-image icon).
3. Once shown, the CSP is `img-src https: http: data: blob:` and nothing is rewritten, except
   that Android, whose view has no base URL, makes `//host` sources `https:` first.

### Showing images: the setting

Two fields in `users/{uid}.settings`, read and written by both clients (no server involvement):

| Field | Type | Notes |
|---|---|---|
| `images` | `"ask"` \| `"always"` | absent or anything else means `"ask"`: remote images stay blocked until the reader taps "Show images" |
| `imageSenders` | map of address → `true` | lower-case sender addresses whose images always show ("Always show from this sender"); a missing key or any other value means ask |

A message's images show without asking when `images` is `"always"` or its `From` address is a key
of `imageSenders` with value `true`. "Show images" on one message changes nothing stored. Add a
sender with a merge write of `{ settings: { imageSenders: { "<address>": true } } }`; remove one by
deleting the field `settings.imageSenders.<address>` (a field path, since addresses contain dots).
Web: `web/src/lib/images/image-settings.ts`.

## Unsubscribe

The client reads `List-Unsubscribe` and `List-Unsubscribe-Post` from the message (Gmail
`messages.get`, `format=metadata` with those `metadataHeaders`, or from the full message it
already has) and picks one route:

1. **One-click**: `List-Unsubscribe-Post` is `List-Unsubscribe=One-Click` and `List-Unsubscribe`
   has an `https:` URL. Call `unsubscribe`; browsers can't send this POST themselves (CORS).
2. **Mail**: only a `mailto:` URL. Build the mail from it (address, plus `subject` and `body`
   query parameters) and send it through the normal send path.
3. **Link**: only an `http(s):` URL without one-click. Open it in the browser.

The Unsubscribe control shows the request working and then its result; `{ ok: false }` and any
error both read as a failure the user can retry. A `reauth` error reads "Sign in again to
unsubscribe", no connection "Can't unsubscribe offline", anything else "Couldn't unsubscribe".

### `unsubscribe`

Callable, signed-in users only. The server reads the message from the user's Gmail and checks it
really carries both headers with that URL before sending anything, so it can't be pointed at an
arbitrary URL.

| | |
|---|---|
| Input | `{ messageId, url, post }`: the Gmail message id, the `https:` URL exactly as it appears between `<` and `>` in `List-Unsubscribe`, and the `List-Unsubscribe-Post` value |
| Output | `{ ok: boolean, status?: number }`: `ok` when the sender answered 2xx (or 301/302/303); `status` is the sender's HTTP status when it answered |

Errors:

| Code | Message | Meaning |
|---|---|---|
| `unauthenticated` | | not signed in |
| `invalid-argument` | | malformed input, or the URL isn't `https:` |
| `not-found` | | no such message in the user's Gmail |
| `failed-precondition` | `reauth` | Gmail access is gone; run the sign-in flow again |
| `failed-precondition` | other | the message has no one-click header |
| `permission-denied` | | the URL isn't in the message's `List-Unsubscribe` |

The server POSTs `List-Unsubscribe=One-Click` as `application/x-www-form-urlencoded`, with no
cookies or referrer and a 10-second timeout. It follows 307/308 (which repeat the POST) up to
three times, only to other `https:` hosts, and never to private or loopback addresses.
`ok: false` is final for that message: show "Couldn't unsubscribe" and offer the link route
(Open page) if the header has one.
