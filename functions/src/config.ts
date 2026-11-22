import { defineSecret, defineString } from "firebase-functions/params";
import { setGlobalOptions } from "firebase-functions/v2";
import type { ClientCredentials } from "./lib/google.js";
import { devSealer, KmsSealer, type TokenSealer } from "./lib/sealer.js";

/** Region every function deploys to. */
export const REGION = "us-central1";

setGlobalOptions({ region: REGION });

/** A day in milliseconds. */
export const DAY_MS = 24 * 60 * 60 * 1000;

/** Whether a trigger's event is more than a day old: dropped rather than retried, as a sweep covers it. */
export function isStaleEvent(eventTime: string): boolean {
  return Date.now() - Date.parse(eventTime) > DAY_MS;
}

/** OAuth web client id. */
const GOOGLE_CLIENT_ID = defineString("GOOGLE_CLIENT_ID");

/** OAuth web client secret. */
export const GOOGLE_CLIENT_SECRET = defineSecret("GOOGLE_CLIENT_SECRET");

/** Resend API key, used as the SMTP password for mail from `@hafa.cc`. */
export const RESEND_API_KEY = defineSecret("RESEND_API_KEY");

/** Domain whose addresses send through Resend rather than Gmail. */
export const RESEND_DOMAIN = "hafa.cc";

/** Resend's SMTP server: implicit TLS on 465, user `resend`, the API key as password. */
export const RESEND_SMTP = {
  host: "smtp.resend.com",
  port: 465,
  user: "resend",
} as const;

/** Cloudflare API token with Email Routing Addresses Write (account) and Rules Write (zone). */
export const CLOUDFLARE_API_TOKEN = defineSecret("CLOUDFLARE_API_TOKEN");

/** Cloudflare account id that holds destination addresses; empty until the owner sets it. */
export const CLOUDFLARE_ACCOUNT_ID = defineString("CLOUDFLARE_ACCOUNT_ID", {
  default: "",
});

/** Cloudflare zone id of hafa.cc, which holds routing rules; empty until the owner sets it. */
export const CLOUDFLARE_ZONE_ID = defineString("CLOUDFLARE_ZONE_ID", {
  default: "",
});

/** Workers KV namespace the Key Worker serves from (its `KEYS` binding); empty until set up. */
export const CLOUDFLARE_KV_NAMESPACE_ID = defineString(
  "CLOUDFLARE_KV_NAMESPACE_ID",
  { default: "" },
);

/** Cloud KMS key that seals refresh tokens (`projects/…/cryptoKeys/…`). */
const KMS_KEY = defineString("KMS_KEY", {
  default:
    "projects/hafa-inbox/locations/global/keyRings/inbox/cryptoKeys/refresh-tokens",
});

/** Pub/Sub topic (in this project) Gmail publishes mailbox changes to. */
export const GMAIL_TOPIC_NAME = "gmail";

/** Full name of the Gmail topic, `projects/{project}/topics/gmail`; call only inside a handler. */
export function gmailTopic(): string {
  const project =
    process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
  if (project === undefined) {
    throw new Error("no project id in the environment");
  } else {
    return `projects/${project}/topics/${GMAIL_TOPIC_NAME}`;
  }
}

/** Read the OAuth client credentials; call only inside a handler. */
export function clientCredentials(): ClientCredentials {
  return {
    clientId: GOOGLE_CLIENT_ID.value(),
    clientSecret: GOOGLE_CLIENT_SECRET.value(),
  };
}

/** A sealer that loads the Cloud KMS client on first use, so functions that never seal skip it. */
function kmsSealer(): TokenSealer {
  let loaded: Promise<KmsSealer> | undefined;
  const load = () => {
    loaded ??= import("@google-cloud/kms").then(
      ({ KeyManagementServiceClient }) =>
        new KmsSealer(KMS_KEY.value(), new KeyManagementServiceClient()),
    );
    return loaded;
  };
  return {
    seal: async (plain) => (await load()).seal(plain),
    open: async (sealed) => (await load()).open(sealed),
  };
}

let sealer: TokenSealer | undefined;

/** Return the token sealer: a local AES key in the emulator, Cloud KMS otherwise. */
export function tokenSealer(): TokenSealer {
  sealer ??=
    process.env.FUNCTIONS_EMULATOR === "true" ? devSealer() : kmsSealer();
  return sealer;
}
