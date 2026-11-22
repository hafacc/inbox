import { httpsCallable } from "firebase/functions";
import { functions } from "../firebase";
import type { OneClickCall } from "./unsubscribes";

/** Ask the backend to send a message's one-click unsubscribe; resolves whether the sender took it. */
export const oneClickUnsubscribe: OneClickCall = async (request) => {
  const call = httpsCallable<
    { messageId: string; url: string; post: string },
    { ok: boolean; status?: number }
  >(functions(), "unsubscribe");
  const { data } = await call(request);
  return data.ok;
};
