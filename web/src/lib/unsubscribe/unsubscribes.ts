import type { Unsubscribe } from "@inbox/shared/unsubscribe";
import {
  failureStep,
  mailtoMessage,
  type UnsubscribeStep,
} from "./unsubscribe-model";

/** A one-click unsubscribe request through the backend; resolves whether the sender took it. */
export type OneClickCall = (request: {
  messageId: string;
  url: string;
  post: string;
}) => Promise<boolean>;

/** What unsubscribing from one message needs from the screen it runs on. */
export type UnsubscribeRun = {
  /** the message's id */
  messageId: string;
  /** its `List-Unsubscribe-Post` header */
  post: string | null;
  /** the ways its headers offer */
  ways: Unsubscribe;
  /** send the request mail; resolves whether it was queued */
  send: (mail: ReturnType<typeof mailtoMessage>) => Promise<boolean>;
  /** open the sender's page */
  openPage: (url: string) => void;
};

/**
 * Where unsubscribing from each message stands, kept for the session so a thread closed and
 * opened again still shows how it went.
 */
export class Unsubscribes {
  #steps: Map<string, UnsubscribeStep>;
  #oneClick: OneClickCall;
  #online: () => boolean;

  /** `steps` holds each message's step (a reactive map, to draw from). */
  constructor(
    steps: Map<string, UnsubscribeStep>,
    oneClick: OneClickCall,
    online: () => boolean = () => navigator.onLine,
  ) {
    this.#steps = steps;
    this.#oneClick = oneClick;
    this.#online = online;
  }

  /** Where unsubscribing from message `messageId` stands. */
  stepOf(messageId: string): UnsubscribeStep {
    return this.#steps.get(messageId) ?? "idle";
  }

  /**
   * Unsubscribe by the message's preferred way: one-click, the request mail, or the sender's page,
   * which leaves the step as it was. Does nothing while a request is under way.
   */
  async run(request: UnsubscribeRun): Promise<void> {
    const { messageId, ways } = request;
    if (this.stepOf(messageId) === "working") return;
    if (ways.preferred === "oneClick" && ways.oneClickUrl) {
      this.#steps.set(messageId, "working");
      try {
        const ok = await this.#oneClick({
          messageId,
          url: ways.oneClickUrl,
          post: request.post ?? "",
        });
        this.#steps.set(messageId, ok ? "unsubscribed" : "failed");
      } catch (error) {
        console.error("unsubscribe failed", error);
        this.#steps.set(messageId, failureStep(error, this.#online()));
      }
    } else if (ways.preferred === "mailto" && ways.mailto) {
      this.#steps.set(messageId, "working");
      try {
        const queued = await request.send(mailtoMessage(ways.mailto));
        this.#steps.set(messageId, queued ? "requested" : "failed");
      } catch (error) {
        console.error("unsubscribe request failed", error);
        this.#steps.set(messageId, failureStep(error, this.#online()));
      }
    } else if (ways.url) {
      request.openPage(ways.url);
    }
  }
}
