import { addressKey } from "@inbox/shared/compose";
import { type ThemeId, themeIdFrom } from "@inbox/shared/themes";
import {
  deleteField,
  doc,
  FieldPath,
  type Firestore,
  setDoc,
  updateDoc,
} from "firebase/firestore";
import {
  type AddressSetup,
  isActive,
  setupFrom,
} from "./address/address-model";
import {
  type SendSettings,
  sendSettingsFrom,
  type UserDoc,
} from "./compose/send-settings";
import { watchFirestore } from "./firestore-watch";
import {
  type ImageMode,
  type ImageSettings,
  imageSettingsFrom,
} from "./images/image-settings";
import { type PgpDoc, pgpDocFrom } from "./pgp/pgp-doc";

/** `users/{uid}`: the claimed @hafa.cc address, and the send, image and theme settings, followed live. */
export class AccountSettings {
  /** The claimed @hafa.cc address, or null. */
  address = $state<string | null>(null);
  /** How far the address's setup has come, or null without one. */
  addressSetup = $state.raw<AddressSetup | null>(null);
  /** Send settings. */
  send = $state.raw<SendSettings>(sendSettingsFrom(undefined));
  /** When remote images load without asking. */
  images = $state.raw<ImageSettings>(imageSettingsFrom(undefined));
  /** Whether single-key shortcuts work on web, `settings.shortcuts` (on unless false). */
  shortcuts = $state(true);
  /** Whether todos show among the mail, `settings.showTodos` (on unless false). */
  showTodos = $state(true);
  /** Whether mail landing in a bundle sends no notification, `settings.quietBundles` (off unless true). */
  quietBundles = $state(false);
  /** The color theme, `settings.theme`. */
  colorTheme = $state<ThemeId>(themeIdFrom(undefined));
  /** The address notice the user closed this session, so it stays closed until the problem changes. */
  dismissedNotice = $state<string | null>(null);
  /** Set by the inbox notice's "Fix it", so Addresses points at its Try again. */
  retryRequested = $state(false);
  /** Whether Firestore has answered once. */
  loaded = $state(false);
  /** The address once mail to it arrives, the only time it is offered to send from. */
  readonly activeAddress: string | null;
  #db: Firestore;
  #uid: string;
  #unsubscribe: () => void;

  constructor(
    database: Firestore,
    uid: string,
    onPgp: (value: PgpDoc | null) => void = () => {},
  ) {
    this.#db = database;
    this.#uid = uid;
    this.activeAddress = $derived(
      isActive(this.addressSetup) ? this.address : null,
    );
    this.#unsubscribe = watchFirestore(
      this.#ref(),
      (snapshot) => {
        const data = snapshot.data() as UserDoc | undefined;
        this.address =
          typeof data?.address === "string" && data.address !== ""
            ? data.address
            : null;
        this.addressSetup =
          this.address === null ? null : setupFrom(data?.addressSetup);
        this.send = sendSettingsFrom(data);
        this.images = imageSettingsFrom(data?.settings);
        this.colorTheme = themeIdFrom(data?.settings?.theme);
        const flags = data?.settings as
          | { shortcuts?: unknown; showTodos?: unknown; quietBundles?: unknown }
          | undefined;
        this.shortcuts = flags?.shortcuts !== false;
        this.showTodos = flags?.showTodos !== false;
        this.quietBundles = flags?.quietBundles === true;
        this.loaded = true;
        onPgp(pgpDocFrom((data as { pgp?: unknown } | undefined)?.pgp));
      },
      "settings",
    ).stop;
  }

  #ref() {
    return doc(this.#db, "users", this.#uid);
  }

  /** Change some send settings; Firestore queues the write while offline. */
  update(patch: Partial<SendSettings>): Promise<void> {
    this.send = { ...this.send, ...patch };
    return setDoc(this.#ref(), { settings: patch }, { merge: true });
  }

  /** Choose the color theme for every device on this account. */
  setColorTheme(id: ThemeId): Promise<void> {
    this.colorTheme = id;
    return setDoc(this.#ref(), { settings: { theme: id } }, { merge: true });
  }

  /** Turn single-key shortcuts on or off for every browser on this account. */
  setShortcuts(on: boolean): Promise<void> {
    this.shortcuts = on;
    return setDoc(
      this.#ref(),
      { settings: { shortcuts: on } },
      { merge: true },
    );
  }

  /** Show or hide todos for every browser on this account. */
  setShowTodos(on: boolean): Promise<void> {
    this.showTodos = on;
    return setDoc(
      this.#ref(),
      { settings: { showTodos: on } },
      { merge: true },
    );
  }

  /** Turn notifications for new mail that lands in a bundle off or back on, for every device. */
  setQuietBundles(on: boolean): Promise<void> {
    this.quietBundles = on;
    return setDoc(
      this.#ref(),
      { settings: { quietBundles: on } },
      { merge: true },
    );
  }

  /** Ask before showing remote images, or always show them. */
  setImageMode(mode: ImageMode): Promise<void> {
    this.images = { ...this.images, mode };
    return setDoc(this.#ref(), { settings: { images: mode } }, { merge: true });
  }

  /** Always (or no longer) show a sender's remote images. */
  setSenderImages(email: string, always: boolean): Promise<void> {
    const key = addressKey(email);
    const senders = new Set(this.images.senders);
    if (always) senders.add(key);
    else senders.delete(key);
    this.images = { ...this.images, senders };
    if (always) {
      return setDoc(
        this.#ref(),
        { settings: { imageSenders: { [key]: true } } },
        { merge: true },
      );
    } else {
      return updateDoc(
        this.#ref(),
        new FieldPath("settings", "imageSenders", key),
        deleteField(),
      );
    }
  }

  /** Stop listening. */
  dispose(): void {
    this.#unsubscribe();
  }
}
