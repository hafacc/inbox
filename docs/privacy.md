# Privacy

Inbox is an email app for Gmail, on the web at inbox.hafa.cc and on Android. Your mail stays in
your Gmail account. The apps read it from Google on your device, and the server keeps only what it
needs to act while every app is closed: sending scheduled mail, notifying you, and forwarding your
@hafa.cc address.

## What Inbox reads from Google

When you sign in, you let Inbox use your Google account for:

- **Gmail** (read, label, archive, send, drafts, filters, send-as addresses): to show and manage
  your mail.
- **Google Contacts** (read only): to suggest addresses and show names and photos.
- **Google Calendar** (events): to answer invitations and show conflicts.

Your devices talk to Gmail, Contacts and Calendar directly. Mail, contacts and events are kept on
the device you use (in the browser's storage on the web, in the app's storage on Android) so they
open quickly and offline. If you use encryption, decrypted mail and messages you are writing are
kept there only encrypted with a key that never leaves the device. On the web, signing out removes
all of it from that browser: cached mail, contacts and photos, drafts being written, settings and
the unlocked encryption key. On Android, signing out removes the same from the phone, and so do
uninstalling the app or clearing its storage.

The server reads your mail only for these jobs, and doesn't keep what it reads:

- Notifications: it reads the sender, subject and opening text of new mail and passes them to your
  devices through Google's push service (Firebase Cloud Messaging). For encrypted mail it sends
  neither subject nor text.
- Scheduled and @hafa.cc sending: it reads the draft at send time and sends it.
- Unsubscribe: when you ask, it reads the message's unsubscribe header and follows it.
- @hafa.cc setup: it reads Cloudflare's confirmation email to confirm the forwarding for you.

## What the server stores

Stored in Google Firebase under your account. Other users can't read it.

- Your Google name, email address and profile photo, and which permissions you granted, as Google
  reports them each time you sign in.
- Your Google refresh token, encrypted with a key in Google Cloud Key Management, so the server can
  act for you while your apps are closed. It also stores the point in your Gmail history it last
  read, when it last read it, and when its Gmail change notifications expire.
- For each snoozed conversation, its Gmail id, the snooze time (as a clock time, without a time
  zone) and the point in your Gmail history it was snoozed at, so newer mail can end the snooze.
- The scheduled-send queue (which draft, which address, when, and how sending went; not the
  message itself), and counts of the times you pick for snoozes and scheduled sends, with the last
  time you typed in by hand, used to suggest times.
- Your todos: their text, times, repeats and order. A deleted todo is kept as a marker so another
  device's copy can't bring it back; deleting one in Inbox erases its text, but todos brought in
  already deleted from elsewhere may keep theirs.
- For each Android phone, which snooze it waits on next, so the server can tell that phone when a
  change moves its reminder.
- Your settings: your signature, the address new mail starts from, how scheduled-send times are
  shifted, when images show and the senders whose images always show, the color theme, whether
  shortcuts are on and whether todos show.
- For each Gmail label you change in Settings, whether it shows in the sidebar, whether it is
  bundled in the inbox, and its icon.
- For each device that gets notifications, a push token, whether it is the web or Android, and when
  it last registered.
- For each notification, the id of the Gmail conversation it is about, so its Done button works
  without signing in. It is deleted after 14 days.
- For a message sent from your @hafa.cc address: until the copy is in your Gmail Sent folder,
  the exact message that went, and for a day, the key that stops it from being sent twice (with
  its date and message id). If the copy can't be filed because Inbox lost access to your Google
  account, it is deleted after 14 days without being filed.
- Your @hafa.cc address, if you claim one, the Gmail address it forwards to, when you claimed it,
  and how far its forwarding setup has got. An address is yours for good; once claimed, its name
  is never given to anyone else.
- If you turn on encryption: your public key, and copies of your private key locked by your
  passkeys and by your recovery phrase. The server can't unlock them; the recovery phrase is never
  stored.

## Other services

- **Images**: remote images are off until you show them, for one message, for a sender or for all
  mail. Shown images load straight from the sender's servers (or whoever hosts them), which then see
  your IP address and when you opened the message.
- **@hafa.cc mail**: Cloudflare Email Routing forwards mail sent to your @hafa.cc address to your
  Gmail. Mail you send from it goes out through Resend (resend.com); a copy is put in your Gmail Sent
  folder. When every recipient has an encryption key, Resend only sees the encrypted message.
- **Public keys**: if you have an @hafa.cc address and turn on encryption, your public key is
  published at openpgpkey.hafa.cc so other people's mail apps can encrypt to you. Public keys are
  meant to be public.
- **Key lookups**: if you turn on encryption, the apps look up the public keys of the people you
  write to and of the senders of encrypted mail you open, so the address is sent to: the address's
  own mail domain (Web Key Directory; on the web through openpgpkey.hafa.cc/lookup, which asks
  that domain for the browser and stores nothing, on Android straight from the phone), and
  keys.openpgp.org. Those services see the address and, except for the domain on the web, your IP
  address. Keys found are kept on your device.

## What Inbox doesn't do

No ads, no analytics, no tracking, and no selling or sharing of data. Google data is never used to
train AI models and is never read by people, except with your permission for support or where the
law requires it.

Inbox's use and transfer to any other app of information received from Google APIs will adhere to
the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Removing your data

- **Delete your account** in Settings (under General on the web). It
  deletes at once everything listed under "What the server stores": your Google profile, your refresh token (after
  asking Google to revoke it, which ends Inbox's access to your Google account), settings, snooze
  times and what each phone waits on, your todos, label settings, the scheduled-send queue, push tokens, notification
  conversation ids and your encryption keys. It stops your @hafa.cc address's forwarding and keeps only the name, so nobody else can
  ever claim it, together with a scrambled form of your account id that can't be turned back into
  it. That scrambled id is kept only so that, if you sign in again with the same Google account,
  you can take the same address back (and no other). It also removes your published public key, cancels scheduled sends, and then deletes your Inbox
  sign-in. It
  also clears the device you delete from. For a day afterwards the server keeps your account id
  on a list of deleted accounts, so a device still signed in can't write anything back; signing in
  again removes it from the list.
- Remove Inbox's access at https://myaccount.google.com/connections. The server can then no longer
  reach your mail, and it deletes the stored refresh token the next time it tries.
- If you can't sign in any more, email support@inbox.hafa.cc from your Gmail address to have everything
  the server stores about you deleted. It is deleted within 30 days.

Your mail itself is in Gmail, and nothing Inbox does removes it. Deleting your account leaves the
drafts of canceled scheduled sends in Drafts.

## Contact

Questions: support@inbox.hafa.cc
