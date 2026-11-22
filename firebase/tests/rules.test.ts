import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
} from "bun:test";
import { readFileSync } from "node:fs";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  deleteDoc,
  deleteField,
  doc,
  FieldPath,
  type Firestore,
  getDoc,
  increment,
  serverTimestamp,
  setDoc,
  setLogLevel,
  Timestamp,
  updateDoc,
  writeBatch,
} from "firebase/firestore";

// Needs the Firestore emulator: run through `bun run test` (firebase emulators:exec).

const OWNER = "google:owner";
const STRANGER = "google:stranger";

let testEnv: RulesTestEnvironment;

function signedIn(uid: string): Firestore {
  return testEnv.authenticatedContext(uid).firestore() as unknown as Firestore;
}

function signedOut(): Firestore {
  return testEnv.unauthenticatedContext().firestore() as unknown as Firestore;
}

beforeAll(async () => {
  // Denied writes are the expected outcome here; the SDK would log each one as an error.
  setLogLevel("silent");
  testEnv = await initializeTestEnvironment({
    projectId: "demo-inbox-rules",
    firestore: {
      rules: readFileSync(
        new URL("../firestore.rules", import.meta.url),
        "utf8",
      ),
    },
  });
  // The emulator's first clear, write and rules evaluation can take several seconds on a cold
  // start; doing them here, with room to spare, keeps them out of the 5-second per-test limit.
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore() as unknown as Firestore;
    await setDoc(doc(admin, "users", OWNER), {
      email: "owner@gmail.example.com",
    });
  });
  await getDoc(doc(signedIn(OWNER), "users", OWNER)).catch(() => undefined);
}, 60_000);

afterAll(async () => {
  await testEnv.cleanup();
}, 30_000);

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore() as unknown as Firestore;
    await setDoc(doc(admin, "users", OWNER), {
      email: "owner@gmail.example.com",
    });
    await setDoc(doc(admin, "users", OWNER, "devices", "device1"), {
      platform: "android",
      lastSeen: 1,
    });
    await setDoc(doc(admin, "private", OWNER), { refreshToken: "kms:sealed" });
    await setDoc(doc(admin, "addresses", "owner"), { uid: OWNER });
    await setDoc(doc(admin, "serverKeys", "addressHolder"), {
      sealed: "kms:sealed",
    });
    await setDoc(doc(admin, "other", "doc"), { value: 1 });
    await setDoc(doc(admin, "users", OWNER, "outbox", "sent"), {
      ...waitingSend(),
      status: "sent",
      via: "gmail",
    });
    await setDoc(doc(admin, "users", OWNER, "outbox", "retrying"), {
      ...waitingSend(),
      status: "retrying",
      attempts: 2,
      lastError: { code: "server", message: "backend error" },
    });
    for (const [id, on] of [
      ["blocked-sign-in", "sign-in"],
      ["blocked-resend", "resend"],
    ] as const) {
      await setDoc(doc(admin, "users", OWNER, "outbox", id), {
        ...waitingSend(),
        status: "blocked",
        blockedOn: on,
        lastError: { code: on, message: "waiting" },
      });
    }
    await setDoc(doc(admin, "users", OWNER, "outbox", "failed"), {
      ...waitingSend(),
      status: "failed",
      error: { code: "rejected", message: "no" },
    });
  });
});

function waitingSend(): Record<string, unknown> {
  return {
    draftId: "r-123",
    from: "owner@gmail.example.com",
    status: "waiting",
    sendAt: Timestamp.fromMillis(1_800_000_010_000),
    chosenAt: Timestamp.fromMillis(1_800_000_000_000),
  };
}

describe("users/{uid}/outbox", () => {
  function outbox(client: Firestore, id: string) {
    return doc(client, "users", OWNER, "outbox", id);
  }

  it("lets the owner schedule, reschedule and undo a send", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(setDoc(outbox(owner, "new"), waitingSend()));
    await assertSucceeds(
      setDoc(outbox(owner, "minimal"), {
        draftId: "r-1",
        from: "owner@hafa.cc",
        status: "waiting",
        sendAt: Timestamp.fromMillis(1),
        chosenAt: null,
        threadId: "t1",
      }),
    );
    await assertSucceeds(
      setDoc(outbox(owner, "new"), {
        ...waitingSend(),
        sendAt: Timestamp.fromMillis(1_800_000_090_000),
      }),
    );
    await assertSucceeds(getDoc(outbox(owner, "new")));
    await assertSucceeds(deleteDoc(outbox(owner, "new")));
  });

  it("lets the owner retry a failed send and clear finished ones", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(setDoc(outbox(owner, "failed"), waitingSend()));
    await assertSucceeds(deleteDoc(outbox(owner, "sent")));
  });

  it("refuses statuses only the server sets", async () => {
    const owner = signedIn(OWNER);
    for (const status of [
      "sending",
      "retrying",
      "blocked",
      "sent",
      "failed",
      "queued",
    ]) {
      await assertFails(
        setDoc(outbox(owner, "new"), { ...waitingSend(), status }),
      );
    }
    await assertFails(updateDoc(outbox(owner, "failed"), { status: "sent" }));
  });

  it("lets the owner send a blocked send again or cancel it, but not unblock it in place", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(outbox(owner, "blocked-sign-in"), waitingSend()),
    );
    await assertSucceeds(deleteDoc(outbox(owner, "blocked-resend")));
    await assertFails(
      setDoc(outbox(owner, "blocked-resend"), {
        ...waitingSend(),
        status: "blocked",
        blockedOn: "resend",
      }),
    );
    await assertFails(
      setDoc(outbox(owner, "new"), { ...waitingSend(), blockedOn: "sign-in" }),
    );
  });

  it("refuses to bring back a sent message or mark it unsent", async () => {
    const owner = signedIn(OWNER);
    await assertFails(setDoc(outbox(owner, "sent"), waitingSend()));
  });

  it("refuses deleting a send mid-attempt", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const admin = context.firestore() as unknown as Firestore;
      await setDoc(doc(admin, "users", OWNER, "outbox", "sending"), {
        ...waitingSend(),
        status: "sending",
      });
    });
    const owner = signedIn(OWNER);
    await assertFails(deleteDoc(outbox(owner, "sending")));
    await assertFails(setDoc(outbox(owner, "sending"), waitingSend()));
    await assertSucceeds(deleteDoc(outbox(owner, "missing")));
  });

  it("lets the owner cancel a retrying send but not rewrite it", async () => {
    const owner = signedIn(OWNER);
    await assertFails(setDoc(outbox(owner, "retrying"), waitingSend()));
    await assertSucceeds(getDoc(outbox(owner, "retrying")));
    await assertSucceeds(deleteDoc(outbox(owner, "retrying")));
  });

  it("refuses missing, malformed and extra fields", async () => {
    const owner = signedIn(OWNER);
    const { draftId: _draftId, ...noDraft } = waitingSend();
    for (const data of [
      noDraft,
      { ...waitingSend(), draftId: "" },
      { ...waitingSend(), from: "not an address" },
      { ...waitingSend(), sendAt: 1_800_000_010_000 },
      { ...waitingSend(), chosenAt: "tomorrow" },
      { ...waitingSend(), via: "resend" },
      { ...waitingSend(), error: { code: "x" } },
    ]) {
      await assertFails(setDoc(outbox(owner, "new"), data));
    }
  });

  it("takes plain ids, an encrypted send's Bcc copies included, and refuses others", async () => {
    const owner = signedIn(OWNER);
    for (const id of [
      "Zx81_q-Tr0",
      "Zx81_q-Tr0-bcc-1",
      "Zx81_q-Tr0-bcc-12",
      "a".repeat(128),
    ]) {
      await assertSucceeds(setDoc(outbox(owner, id), waitingSend()));
    }
    for (const id of [
      "a".repeat(129),
      "has space",
      "dot.ted",
      "line\nbreak",
      "carriage\rreturn",
      "colon:id",
      "ünï",
    ]) {
      await assertFails(setDoc(outbox(owner, id), waitingSend()));
    }
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const admin = context.firestore() as unknown as Firestore;
      await setDoc(doc(admin, "users", OWNER, "outbox", "odd.id"), {
        ...waitingSend(),
        status: "failed",
      });
    });
    await assertFails(setDoc(outbox(owner, "odd.id"), waitingSend()));
    await assertSucceeds(deleteDoc(outbox(owner, "odd.id")));
  });

  describe("a device sending a send now itself", () => {
    function dueSend(
      sendAt: number,
      extra: Record<string, unknown> = {},
    ): Record<string, unknown> {
      return {
        ...waitingSend(),
        chosenAt: null,
        threadId: "t-1",
        sendAt: Timestamp.fromMillis(sendAt),
        ...extra,
      };
    }

    function claim(
      extra: Record<string, unknown> = {},
    ): Record<string, unknown> {
      return {
        status: "sending",
        claimedAt: serverTimestamp(),
        firstAttemptAt: serverTimestamp(),
        inFlightAt: serverTimestamp(),
        via: "gmail",
        draftMessageId: "m-123",
        messageIdHeader: "<abc@mail.gmail.com>",
        deviceClaim: "c".repeat(24),
        ...extra,
      };
    }

    async function seed(id: string, data: Record<string, unknown>) {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const admin = context.firestore() as unknown as Firestore;
        await setDoc(doc(admin, "users", OWNER, "outbox", id), data);
      });
    }

    // As the device writes a result: under the claim id it claimed with.
    function sent(extra: Record<string, unknown> = {}) {
      return {
        deviceClaim: "c".repeat(24),
        status: "sent",
        sentAt: serverTimestamp(),
        messageId: "m-123",
        threadId: "t-1",
        filed: true,
        rejected: [],
        inFlightAt: deleteField(),
        ...extra,
      };
    }

    function refused(extra: Record<string, unknown> = {}) {
      return {
        deviceClaim: "c".repeat(24),
        status: "failed",
        error: { code: "rejected", message: "Invalid To header" },
        failedAt: serverTimestamp(),
        inFlightAt: deleteField(),
        ...extra,
      };
    }

    it("claims a due waiting doc, then records it sent", async () => {
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "due"), claim()));
      // Once claimed it can't be undone or rewritten, as with the server's claim.
      await assertFails(deleteDoc(outbox(owner, "due")));
      await assertFails(setDoc(outbox(owner, "due"), waitingSend()));
      await assertFails(updateDoc(outbox(owner, "due"), claim()));
      await assertSucceeds(updateDoc(outbox(owner, "due"), sent()));
      const stored = (await getDoc(outbox(owner, "due"))).data();
      expect(stored?.status).toBe("sent");
      expect(stored?.inFlightAt).toBeUndefined();
    });

    it("claims up to 30 seconds early for clock skew, not before", async () => {
      await seed("skew", dueSend(Date.now() + 10_000));
      await seed("early", dueSend(Date.now() + 120_000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "skew"), claim()));
      await assertFails(updateDoc(outbox(owner, "early"), claim()));
    });

    it("claims up to 60 seconds late for clock skew, not after", async () => {
      await seed("skew", dueSend(Date.now() - 45_000));
      await seed("late", dueSend(Date.now() - 90_000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "skew"), claim()));
      await assertFails(updateDoc(outbox(owner, "late"), claim()));
    });

    it("refuses a result under another claim than the one standing", async () => {
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "due"), claim()));
      const other = { deviceClaim: "d".repeat(24) };
      await assertFails(updateDoc(outbox(owner, "due"), sent(other)));
      await assertFails(updateDoc(outbox(owner, "due"), refused(other)));
      await assertSucceeds(updateDoc(outbox(owner, "due"), sent()));
    });

    it("refuses the device's result once the server took its claim over", async () => {
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "due"), claim()));
      // As outboxSend's takeover writes it once the lease ends (functions/src/outbox-deps.ts).
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const admin = context.firestore() as unknown as Firestore;
        await updateDoc(doc(admin, "users", OWNER, "outbox", "due"), {
          status: "sending",
          claimedAt: Timestamp.fromMillis(Date.now()),
          attempts: 1,
          deviceClaim: deleteField(),
        });
      });
      await assertFails(updateDoc(outbox(owner, "due"), sent()));
      await assertFails(updateDoc(outbox(owner, "due"), refused()));
      await assertFails(
        updateDoc(outbox(owner, "due"), sent({ deviceClaim: deleteField() })),
      );
      await assertFails(updateDoc(outbox(owner, "due"), claim()));
      await assertFails(deleteDoc(outbox(owner, "due")));
      const stored = (await getDoc(outbox(owner, "due"))).data();
      expect(stored?.status).toBe("sending");
      expect(stored?.deviceClaim).toBeUndefined();
    });

    it("records a refusal as failed", async () => {
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      await assertSucceeds(updateDoc(outbox(owner, "due"), claim()));
      await assertFails(
        updateDoc(
          outbox(owner, "due"),
          refused({ error: { code: "unavailable", message: "x" } }),
        ),
      );
      await assertFails(
        updateDoc(outbox(owner, "due"), refused({ inFlightAt: 1 })),
      );
      await assertSucceeds(updateDoc(outbox(owner, "due"), refused()));
    });

    it("claims only a waiting doc holding nothing but the client's fields", async () => {
      const due = Date.now() - 1000;
      await seed("record", dueSend(due, { via: "gmail", draftMessageId: "m" }));
      await seed("hafa", dueSend(due, { from: "owner@Hafa.cc" }));
      await seed("sending", dueSend(due, { status: "sending" }));
      const owner = signedIn(OWNER);
      for (const id of ["record", "hafa", "sending", "failed", "missing"]) {
        await assertFails(updateDoc(outbox(owner, id), claim()));
      }
    });

    it("refuses a claim that isn't exactly the claim", async () => {
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      for (const bad of [
        claim({ via: "resend" }),
        claim({ claimedAt: Timestamp.fromMillis(Date.now() - 600_000) }),
        claim({ inFlightAt: deleteField() }),
        claim({ deviceClaim: "short" }),
        claim({ deviceClaim: deleteField() }),
        claim({ draftMessageId: "" }),
        claim({ sendAt: Timestamp.fromMillis(Date.now() - 500) }),
        claim({ draftId: "r-other" }),
        claim({ attempts: 0 }),
        { ...claim(), ...sent() },
      ]) {
        await assertFails(updateDoc(outbox(owner, "due"), bad));
      }
      await assertSucceeds(
        updateDoc(outbox(owner, "due"), claim({ messageIdHeader: null })),
      );
    });

    it("refuses a result once the server took the claim over, or with other fields", async () => {
      await seed(
        "server",
        dueSend(Date.now() - 1000, {
          status: "sending",
          claimedAt: Timestamp.fromMillis(Date.now()),
        }),
      );
      await seed("due", dueSend(Date.now() - 1000));
      const owner = signedIn(OWNER);
      await assertFails(updateDoc(outbox(owner, "server"), sent()));
      await assertFails(updateDoc(outbox(owner, "server"), refused()));
      await assertFails(updateDoc(outbox(owner, "due"), sent()));
      await assertSucceeds(updateDoc(outbox(owner, "due"), claim()));
      for (const bad of [
        sent({ via: "resend" }),
        sent({ rejected: ["a@b.c"] }),
        sent({ filed: false }),
        sent({ messageId: "" }),
        sent({ deviceClaim: deleteField() }),
        sent({ sentAt: Timestamp.fromMillis(0) }),
        sent({ inFlightAt: serverTimestamp() }),
        { ...sent(), status: "retrying" },
      ]) {
        await assertFails(updateDoc(outbox(owner, "due"), bad));
      }
      await assertFails(updateDoc(outbox(signedIn(STRANGER), "due"), sent()));
    });
  });

  it("denies other users", async () => {
    const stranger = signedIn(STRANGER);
    await assertFails(getDoc(outbox(stranger, "sent")));
    await assertFails(setDoc(outbox(stranger, "new"), waitingSend()));
    await assertFails(deleteDoc(outbox(stranger, "failed")));
  });
});

describe("users/{uid}", () => {
  it("lets the owner read and write their doc and their device docs", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(getDoc(doc(owner, "users", OWNER)));
    await assertSucceeds(
      setDoc(
        doc(owner, "users", OWNER),
        { settings: { theme: "plum" } },
        { merge: true },
      ),
    );
    await assertSucceeds(
      getDoc(doc(owner, "users", OWNER, "devices", "device1")),
    );
    await assertSucceeds(
      setDoc(doc(owner, "users", OWNER, "devices", "token"), {
        platform: "android",
        lastSeen: serverTimestamp(),
      }),
    );
    await assertSucceeds(
      deleteDoc(doc(owner, "users", OWNER, "devices", "device1")),
    );
  });

  it("denies other users", async () => {
    const stranger = signedIn(STRANGER);
    await assertFails(getDoc(doc(stranger, "users", OWNER)));
    await assertFails(setDoc(doc(stranger, "users", OWNER), { email: "x" }));
    await assertFails(
      getDoc(doc(stranger, "users", OWNER, "devices", "device1")),
    );
    await assertFails(
      setDoc(doc(stranger, "users", OWNER, "devices", "token2"), {
        platform: "android",
        lastSeen: 1,
      }),
    );
  });

  it("denies signed-out clients", async () => {
    const anonymous = signedOut();
    await assertFails(getDoc(doc(anonymous, "users", OWNER)));
    await assertFails(
      setDoc(doc(anonymous, "users", OWNER, "devices", "token2"), {
        platform: "android",
        lastSeen: 1,
      }),
    );
  });
});

describe("users/{uid} Google profile", () => {
  const user = (client: Firestore) => doc(client, "users", OWNER);

  it("keeps the email, name and photo the server's", async () => {
    const owner = signedIn(OWNER);
    for (const field of ["email", "displayName", "photoUrl"]) {
      await assertFails(updateDoc(user(owner), { [field]: "x" }));
    }
    await assertFails(
      setDoc(user(owner), { displayName: "Owner" }, { merge: true }),
    );
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(
        doc(context.firestore() as unknown as Firestore, "users", OWNER),
      );
    });
    await assertFails(
      setDoc(user(owner), { email: "owner@gmail.example.com" }),
    );
  });
});

describe("deleted accounts", () => {
  beforeEach(async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const admin = context.firestore() as unknown as Firestore;
      await setDoc(doc(admin, "deletedAccounts", OWNER), {
        expiresAt: Timestamp.fromMillis(1_900_000_000_000),
      });
    });
  });

  it("refuses every write a device still signed in makes after deletion", async () => {
    const owner = signedIn(OWNER);
    await assertFails(
      setDoc(doc(owner, "users", OWNER), { settings: {} }, { merge: true }),
    );
    await assertFails(
      setDoc(doc(owner, "users", OWNER, "devices", "token"), { lastSeen: 1 }),
    );
    await assertFails(
      setDoc(doc(owner, "users", OWNER, "outbox", "new"), waitingSend()),
    );
    await assertFails(
      setDoc(doc(owner, "users", OWNER, "threads", "abc123"), {
        snoozeUntil: "2027-01-15T08:00:00",
        snoozeWatermark: "42",
      }),
    );
    await assertFails(
      setDoc(doc(owner, "users", OWNER, "labelPrefs", "Label_1"), {
        sidebar: true,
      }),
    );
    await assertFails(
      setDoc(doc(owner, "users", OWNER, "todos", "todo-1"), liveTodo()),
    );
    await assertFails(
      setDoc(
        doc(owner, "users", OWNER, "meta", "snoozeWaits"),
        { waits: { phone1: { updatedAt: serverTimestamp() } } },
        { mergeFields: [new FieldPath("waits", "phone1")] },
      ),
    );
    await assertFails(
      updateDoc(doc(owner, "users", OWNER), {
        pgp: {
          publicKey:
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nxjMEaVW5ABYJKwYBBAHaRw8BAQdA\n-----END PGP PUBLIC KEY BLOCK-----\n",
          sealed: [],
          recoverySealed: {
            salt: "ABEiM0RVZneImaq7zN3u/w==",
            sealedPrivateKey: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHw==",
            kdf: {
              name: "argon2id",
              memoryKiB: 65536,
              iterations: 3,
              parallelism: 1,
            },
          },
        },
      }),
    );
    await assertFails(getDoc(doc(owner, "deletedAccounts", OWNER)));
  });

  it("leaves other accounts alone", async () => {
    const stranger = signedIn(STRANGER);
    await assertSucceeds(
      setDoc(doc(stranger, "users", STRANGER), { settings: {} }),
    );
  });
});

describe("users/{uid}/threads", () => {
  const thread = (client: Firestore, id: string) =>
    doc(client, "users", OWNER, "threads", id);

  it("takes a snooze and refuses other shapes and ids", async () => {
    const owner = signedIn(OWNER);
    const snooze = {
      snoozeUntil: "2027-01-15T08:00:00",
      snoozeWatermark: "123456",
    };
    await assertSucceeds(setDoc(thread(owner, "18c2f0a1b2c3d4e5"), snooze));
    await assertSucceeds(
      setDoc(thread(owner, "18c2f0a1b2c3d4e6"), {
        snoozeUntil: null,
        snoozeWatermark: null,
      }),
    );
    await assertSucceeds(deleteDoc(thread(owner, "18c2f0a1b2c3d4e5")));
    for (const data of [
      { ...snooze, extra: 1 },
      { ...snooze, snoozeUntil: 5 },
      { ...snooze, snoozeUntil: Timestamp.fromMillis(1_800_000_000_000) },
      { ...snooze, snoozeUntil: "2027-01-15T08:00" },
      { ...snooze, snoozeUntil: "2027-01-15T08:00:00Z" },
      { ...snooze, snoozeWatermark: "x".repeat(40) },
    ]) {
      await assertFails(setDoc(thread(owner, "18c2f0a1b2c3d4e7"), data));
    }
    await assertFails(setDoc(thread(owner, "a".repeat(65)), snooze));
    await assertFails(setDoc(thread(owner, "dot.ted"), snooze));
  });

  it("takes only real dates and times of day", async () => {
    const owner = signedIn(OWNER);
    const at = (snoozeUntil: string) =>
      setDoc(thread(owner, "18c2f0a1b2c3d4e8"), { snoozeUntil });
    for (const valid of [
      "2028-02-29T09:00:00",
      "2000-02-29T09:00:00",
      "2026-12-31T23:59:59",
      "2026-04-30T00:00:00",
      "2026-01-31T09:00:00",
    ]) {
      await assertSucceeds(at(valid));
    }
    for (const invalid of [
      "2026-13-01T09:00:00",
      "2026-00-10T09:00:00",
      "2026-11-00T09:00:00",
      "2026-02-30T09:00:00",
      "2026-02-29T09:00:00",
      "1900-02-29T09:00:00",
      "2026-04-31T09:00:00",
      "2026-11-12T24:00:00",
      "2026-11-12T09:60:00",
      "2026-11-12T09:00:60",
    ]) {
      await assertFails(at(invalid));
    }
  });
});

function liveTodo(): Record<string, unknown> {
  return {
    text: "Buy *milk* #home",
    state: "ACTIVE",
    createdAt: 1_794_400_000_000,
    modifiedAt: 1_794_400_000_000,
    serverModifiedAt: serverTimestamp(),
    snoozeUntil: "2026-11-13T08:00:00",
    pinned: false,
    position: null,
    deleted: false,
    repeat: {
      every: 1,
      unit: "week",
      weekday: 1,
      monthDay: 1,
      at: "07:00",
      anchor: "2026-11-09",
    },
    repeatSpawned: false,
    occurrence: "2026-11-16T07:00:00",
  };
}

describe("users/{uid}/todos", () => {
  const todo = (
    client: Firestore,
    id = "0f8fad5b-d9cb-469f-a165-70867728950e",
  ) => doc(client, "users", OWNER, "todos", id);

  it("takes a whole todo and edits to it from its owner only", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(setDoc(todo(owner), liveTodo()));
    await assertSucceeds(getDoc(todo(owner)));
    await assertSucceeds(
      setDoc(
        todo(owner),
        {
          state: "DONE",
          snoozeUntil: null,
          position: "1794450000000i0f8fad5bz",
          repeat: null,
          modifiedAt: 1_794_500_000_000,
          serverModifiedAt: serverTimestamp(),
        },
        { merge: true },
      ),
    );
    const stranger = signedIn(STRANGER);
    await assertFails(getDoc(todo(stranger)));
    await assertFails(setDoc(todo(stranger), liveTodo()));
    await assertFails(deleteDoc(todo(stranger)));
    await assertFails(getDoc(todo(signedOut())));
  });

  it("refuses other shapes and ids", async () => {
    const owner = signedIn(OWNER);
    const { repeat } = liveTodo();
    const { pinned: _pinned, ...withoutPinned } = liveTodo();
    for (const data of [
      withoutPinned,
      { ...liveTodo(), extra: 1 },
      { ...liveTodo(), state: "SNOOZED" },
      { ...liveTodo(), text: 5 },
      { ...liveTodo(), text: "x".repeat(100_001) },
      { ...liveTodo(), createdAt: "1794400000000" },
      { ...liveTodo(), modifiedAt: -1 },
      { ...liveTodo(), modifiedAt: 1.5 },
      {
        ...liveTodo(),
        serverModifiedAt: Timestamp.fromMillis(1_794_400_000_000),
      },
      { ...liveTodo(), snoozeUntil: "2026-11-13T08:00" },
      { ...liveTodo(), snoozeUntil: "2026-11-13T08:00:00.000" },
      { ...liveTodo(), snoozeUntil: 1_794_400_000_000 },
      { ...liveTodo(), occurrence: "2026-11-16T07:00:00Z" },
      { ...liveTodo(), pinned: "yes" },
      { ...liveTodo(), deleted: null },
      { ...liveTodo(), position: "abc" },
      { ...liveTodo(), position: `1794450000000${"z".repeat(1000)}` },
      { ...liveTodo(), repeat: { ...(repeat as object), every: 8 } },
      { ...liveTodo(), repeat: { ...(repeat as object), unit: "year" } },
      { ...liveTodo(), repeat: { ...(repeat as object), monthDay: 0 } },
      { ...liveTodo(), repeat: { ...(repeat as object), at: "24:00" } },
      { ...liveTodo(), repeat: { ...(repeat as object), anchor: "soon" } },
      { ...liveTodo(), repeat: { ...(repeat as object), extra: 1 } },
      { ...liveTodo(), repeatSpawned: 1 },
      { ...liveTodo(), threadId: null },
      { ...liveTodo(), threadId: 42 },
    ]) {
      await assertFails(setDoc(todo(owner), data));
    }
    await assertFails(setDoc(todo(owner, "has space"), liveTodo()));
    await assertFails(setDoc(todo(owner, "a".repeat(129)), liveTodo()));
  });

  it("takes a todo on an email: its text alone, under its thread's id", async () => {
    const owner = signedIn(OWNER);
    const threadId = "18c2f0a9b1d3e4f5";
    const emailTodo = (fields: Record<string, unknown> = {}) => ({
      threadId,
      text: "Call Maya",
      createdAt: 1_794_400_000_000,
      modifiedAt: 1_794_400_000_000,
      serverModifiedAt: serverTimestamp(),
      deleted: false,
      ...fields,
    });
    await assertSucceeds(setDoc(todo(owner, threadId), emailTodo()));
    await assertSucceeds(
      setDoc(
        todo(owner, "a".repeat(64)),
        emailTodo({ threadId: "a".repeat(64) }),
      ),
    );
    await assertSucceeds(
      setDoc(todo(owner, threadId), emailTodo({ deleted: true, text: "" })),
    );
    // Done, snoozed, pinned and the rest are the email's: none is stored on its todo.
    for (const field of [
      { state: "ACTIVE" },
      { snoozeUntil: null },
      { pinned: false },
      { position: null },
      { repeat: null },
      { repeatSpawned: false },
      { occurrence: null },
    ]) {
      await assertFails(setDoc(todo(owner, threadId), emailTodo(field)));
      await assertFails(
        setDoc(
          todo(owner, threadId),
          { ...field, serverModifiedAt: serverTimestamp() },
          { merge: true },
        ),
      );
    }
    const { createdAt: _createdAt, ...withoutCreated } = emailTodo();
    for (const [id, data] of [
      // One todo per email: its id is its thread's.
      ["0f8fad5b-d9cb-469f-a165-70867728950e", emailTodo()],
      ["a".repeat(65), emailTodo({ threadId: "a".repeat(65) })],
      ["has space", emailTodo({ threadId: "has space" })],
      [threadId, withoutCreated],
      [threadId, emailTodo({ text: 5 })],
      [threadId, emailTodo({ deleted: null })],
      [threadId, emailTodo({ serverModifiedAt: Timestamp.fromMillis(1) })],
    ] as const) {
      await assertFails(setDoc(todo(owner, id), data));
    }
    // A todo of its own doesn't become one on an email.
    await assertSucceeds(setDoc(todo(owner, threadId), liveTodo()));
    await assertFails(
      setDoc(
        todo(owner, threadId),
        { threadId, serverModifiedAt: serverTimestamp() },
        { merge: true },
      ),
    );
  });

  it("takes every repeat rule latr makes", async () => {
    const owner = signedIn(OWNER);
    for (const rule of [
      { every: 3, unit: "day", weekday: 1, monthDay: 1, at: "00:00" },
      { every: 7, unit: "week", weekday: 7, monthDay: 1, at: "23:59" },
      { every: 2, unit: "month", weekday: 1, monthDay: -1, at: "18:30" },
      { every: 1, unit: "month", weekday: 1, monthDay: 31, at: "09:00" },
    ]) {
      await assertSucceeds(
        setDoc(todo(owner), {
          ...liveTodo(),
          repeat: { ...rule, anchor: "2026-10-31" },
        }),
      );
    }
  });

  it("deletes by tombstone, even of a todo that isn't there, or outright", async () => {
    const owner = signedIn(OWNER);
    const tombstone = {
      deleted: true,
      text: "",
      modifiedAt: 1_794_500_000_000,
      serverModifiedAt: serverTimestamp(),
    };
    await assertSucceeds(setDoc(todo(owner), liveTodo()));
    await assertSucceeds(setDoc(todo(owner), tombstone, { merge: true }));
    await assertSucceeds(
      setDoc(todo(owner, "never-synced"), tombstone, { merge: true }),
    );
    await assertSucceeds(
      setDoc(todo(owner), { ...liveTodo(), deleted: false }),
    );
    await assertSucceeds(deleteDoc(todo(owner)));
    await assertFails(
      setDoc(todo(owner, "half"), { ...tombstone, deleted: false }),
    );
    await assertFails(
      setDoc(todo(owner, "half"), {
        ...tombstone,
        serverModifiedAt: Timestamp.fromMillis(1),
      }),
    );
  });
});

describe("users/{uid}/meta/snoozeWaits", () => {
  const waits = (client: Firestore, uid = OWNER) =>
    doc(client, "users", uid, "meta", "snoozeWaits");
  const wait = (snooze: Record<string, unknown> = {}) => ({
    itemId: "18c2f0a1b2c3d4e5",
    snoozeUntil: "2027-01-15T08:00:00",
    updatedAt: serverTimestamp(),
    ...snooze,
  });

  it("takes each phone's own entry, written at the server's time", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(waits(owner), { waits: { "phone:1": wait() } }, { merge: true }),
    );
    await assertSucceeds(
      updateDoc(waits(owner), "waits.phone2", { updatedAt: serverTimestamp() }),
    );
    await assertSucceeds(
      updateDoc(waits(owner), "waits.phone2", wait({ itemId: "other" })),
    );
    await assertSucceeds(
      updateDoc(waits(owner), "waits.phone2", deleteField()),
    );
    await assertSucceeds(getDoc(waits(owner)));
  });

  it("takes a todo's item id, `todo:` and the longest todo id", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(waits(owner), {
        waits: { phone1: wait({ itemId: `todo:${"a".repeat(128)}` }) },
      }),
    );
    await assertFails(
      setDoc(waits(owner), {
        waits: { phone1: wait({ itemId: `todo:${"a".repeat(129)}` }) },
      }),
    );
  });

  it("refuses other shapes, old times and too many phones", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(setDoc(waits(owner), { waits: { phone1: wait() } }));
    for (const entry of [
      wait({ updatedAt: Timestamp.fromMillis(1_800_000_000_000) }),
      wait({ snoozeUntil: "2027-01-15T08:00:00Z" }),
      wait({ snoozeUntil: 5 }),
      wait({ itemId: "" }),
      wait({ extra: 1 }),
      { itemId: "18c2f0a1b2c3d4e5", updatedAt: serverTimestamp() },
      { updatedAt: 5 },
      "phone",
    ]) {
      await assertFails(updateDoc(waits(owner), "waits.phone2", entry));
    }
    await assertFails(setDoc(waits(owner), { waits: {}, extra: 1 }));
    const many = Object.fromEntries(
      Array.from({ length: 11 }, (_, index) => [`phone${index}`, wait()]),
    );
    await assertFails(setDoc(waits(owner), { waits: many }));
  });

  // Android's writes (notify/Devices.kt): its own entry by field path, its push token (which holds
  // a colon) as the key; on sign-out, clearing it, even on a doc that isn't there.
  const tokenPath = (token: string) => new FieldPath("waits", token);
  const TOKEN = "fcm:APA91bH-token_1";

  it("takes Android's own-entry writes by field path", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(
        waits(owner),
        { waits: { [TOKEN]: { updatedAt: serverTimestamp() } } },
        { mergeFields: [tokenPath(TOKEN)] },
      ),
    );
    await assertSucceeds(
      setDoc(
        waits(owner),
        { waits: { [TOKEN]: wait({ itemId: "todo:abc" }) } },
        { mergeFields: [tokenPath(TOKEN)] },
      ),
    );
    await assertSucceeds(
      setDoc(
        waits(owner),
        { waits: { [TOKEN]: deleteField() } },
        { mergeFields: [tokenPath(TOKEN)] },
      ),
    );
  });

  it("lets a sign-out clear its entry when the doc isn't there", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(
        waits(owner),
        { waits: { [TOKEN]: deleteField() } },
        { mergeFields: [tokenPath(TOKEN)] },
      ),
    );
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(waits(context.firestore() as unknown as Firestore));
    });
    const batch = writeBatch(owner);
    batch.delete(doc(owner, "users", OWNER, "devices", "device1"));
    batch.set(
      waits(owner),
      { waits: { device1: deleteField() } },
      { mergeFields: [tokenPath("device1")] },
    );
    await assertSucceeds(batch.commit());
    await assertSucceeds(setDoc(waits(owner), {}));
  });

  it("takes a phone's write beside nine others, and lets a write drop other phones' entries", async () => {
    const owner = signedIn(OWNER);
    const many = Object.fromEntries(
      Array.from({ length: 10 }, (_, index) => [
        `phone${index}`,
        wait({ updatedAt: Timestamp.fromMillis(1_800_000_000_000) }),
      ]),
    );
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(waits(context.firestore() as unknown as Firestore), {
        waits: many,
      });
    });
    await assertSucceeds(
      updateDoc(waits(owner), "waits.phone9", wait({ itemId: "other" })),
    );
    await assertFails(
      updateDoc(waits(owner), "waits.phone10", {
        updatedAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(
      updateDoc(waits(owner), "waits.phone0", deleteField(), "waits.phone10", {
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it("denies other users", async () => {
    const stranger = signedIn(STRANGER);
    await assertFails(getDoc(waits(stranger)));
    await assertFails(setDoc(waits(stranger), { waits: { phone1: wait() } }));
  });
});

describe("users/{uid} address fields", () => {
  const user = (client: Firestore) => doc(client, "users", OWNER);

  it("keeps the address and its setup the server's", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const admin = context.firestore() as unknown as Firestore;
      await setDoc(doc(admin, "users", OWNER), {
        email: "owner@gmail.example.com",
        address: "owner@hafa.cc",
        addressSetup: { status: "active", error: null },
      });
    });
    const owner = signedIn(OWNER);
    await assertSucceeds(getDoc(user(owner)));
    await assertSucceeds(
      setDoc(user(owner), { settings: { showTodos: true } }, { merge: true }),
    );
    await assertSucceeds(
      updateDoc(user(owner), { settings: { theme: "clay" } }),
    );
    await assertFails(updateDoc(user(owner), { address: "staff@hafa.cc" }));
    await assertFails(updateDoc(user(owner), { address: null }));
    await assertFails(
      updateDoc(user(owner), { addressSetup: { status: "pending" } }),
    );
    await assertFails(
      setDoc(user(owner), { email: "owner@gmail.example.com" }),
    );
    await assertFails(deleteDoc(user(owner)));
  });

  it("refuses an address on a new doc, and allows deleting one without", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(
        doc(context.firestore() as unknown as Firestore, "users", OWNER),
      );
    });
    const owner = signedIn(OWNER);
    await assertFails(setDoc(user(owner), { address: "owner@hafa.cc" }));
    await assertFails(
      setDoc(user(owner), { addressSetup: { status: "active" } }),
    );
    await assertSucceeds(setDoc(user(owner), { settings: {} }));
    await assertSucceeds(deleteDoc(user(owner)));
  });
});

describe("users/{uid}.pgp", () => {
  const user = (client: Firestore) => doc(client, "users", OWNER);
  const sealedCopy = (index: number) => ({
    credentialId: `cred-${index}`,
    salt: "c2FsdHNhbHRzYWx0c2FsdA==",
    sealedPrivateKey: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHw==",
    name: "Pixel 9",
    addedAt: Timestamp.fromMillis(1_800_000_000_000),
  });
  const pgp = (overrides: Record<string, unknown> = {}) => ({
    publicKey:
      "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nxjMEaVW5ABYJKwYBBAHaRw8BAQdA\n-----END PGP PUBLIC KEY BLOCK-----\n",
    sealed: [sealedCopy(0)],
    recoverySealed: {
      salt: "ABEiM0RVZneImaq7zN3u/w==",
      sealedPrivateKey: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHw==",
      kdf: {
        name: "argon2id",
        memoryKiB: 65536,
        iterations: 3,
        parallelism: 1,
      },
    },
    ...overrides,
  });

  it("accepts a well-formed key, more passkeys, and removing it", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(updateDoc(user(owner), { pgp: pgp() }));
    await assertSucceeds(
      updateDoc(user(owner), {
        pgp: pgp({
          sealed: Array.from({ length: 8 }, (_, index) => sealedCopy(index)),
        }),
      }),
    );
    await assertSucceeds(
      updateDoc(user(owner), { settings: { theme: "blue" } }),
    );
    await assertSucceeds(updateDoc(user(owner), { pgp: deleteField() }));
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(
        doc(context.firestore() as unknown as Firestore, "users", OWNER),
      );
    });
    await assertSucceeds(setDoc(user(owner), { pgp: pgp() }));
    await assertSucceeds(
      setDoc(user(owner), {
        pgp: pgp(),
        settings: { theme: "slate", shortcuts: true },
      }),
    );
    // The apps write pgp alone: with eight passkeys it takes nearly all the steps rules allow.
    await assertSucceeds(
      updateDoc(user(owner), {
        pgp: pgp({
          sealed: Array.from({ length: 8 }, (_, index) => sealedCopy(index)),
        }),
      }),
    );
  });

  it("refuses a private key, or a sealed value that isn't base64", async () => {
    const owner = signedIn(OWNER);
    for (const bad of [
      pgp({
        publicKey:
          "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nxVgE\n-----END PGP PRIVATE KEY BLOCK-----\n",
      }),
      pgp({
        publicKey:
          "-----BEGIN PGP PUBLIC KEY BLOCK-----\n-----BEGIN PGP PRIVATE KEY BLOCK-----\n",
      }),
      pgp({
        sealed: [
          {
            ...sealedCopy(0),
            sealedPrivateKey: "-----BEGIN PGP PRIVATE KEY BLOCK-----",
          },
        ],
      }),
      pgp({
        recoverySealed: {
          ...pgp().recoverySealed,
          sealedPrivateKey: "-----BEGIN PGP PRIVATE KEY BLOCK-----",
        },
      }),
    ]) {
      await assertFails(updateDoc(user(owner), { pgp: bad }));
    }
    await assertFails(setDoc(doc(owner, "users", STRANGER), { pgp: pgp() }));
  });

  it("refuses the wrong shape", async () => {
    const owner = signedIn(OWNER);
    const { recoverySealed } = pgp();
    for (const bad of [
      { publicKey: pgp().publicKey },
      pgp({ privateKey: "x" }),
      pgp({
        sealed: Array.from({ length: 9 }, (_, index) => sealedCopy(index)),
      }),
      pgp({ sealed: [sealedCopy(0), { ...sealedCopy(1), addedAt: 5 }] }),
      pgp({ sealed: [{ ...sealedCopy(0), extra: true }] }),
      pgp({ sealed: [{ ...sealedCopy(0), credentialId: "" }] }),
      pgp({ sealed: "nope" }),
      pgp({
        recoverySealed: {
          ...recoverySealed,
          kdf: { ...recoverySealed.kdf, name: "pbkdf2" },
        },
      }),
      pgp({
        recoverySealed: {
          ...recoverySealed,
          kdf: { ...recoverySealed.kdf, memoryKiB: 1024 },
        },
      }),
      pgp({ recoverySealed: { salt: "x", sealedPrivateKey: "AQID" } }),
    ]) {
      await assertFails(updateDoc(user(owner), { pgp: bad }));
    }
  });
});

describe("function-only collections", () => {
  for (const path of [
    ["private", OWNER],
    ["private", OWNER, "nested", "doc"],
    ["private", OWNER, "actions", "nonce"],
    ["addresses", "owner"],
    ["serverKeys", "addressHolder"],
    ["deletedAccounts", OWNER],
  ] as const) {
    it(`denies ${path.join("/")} even to its owner`, async () => {
      for (const client of [signedIn(OWNER), signedIn(STRANGER), signedOut()]) {
        const [collectionName, ...segments] = path;
        const reference = doc(client, collectionName, ...segments);
        await assertFails(getDoc(reference));
        await assertFails(setDoc(reference, { uid: OWNER }));
        await assertFails(deleteDoc(reference));
      }
    });
  }
});

describe("everything else", () => {
  it("is denied", async () => {
    const owner = signedIn(OWNER);
    await assertFails(getDoc(doc(owner, "other", "doc")));
    await assertFails(setDoc(doc(owner, "other", OWNER), { value: 1 }));
    await assertFails(getDoc(doc(owner, "usersX", OWNER)));
  });
});

describe("snoozePickLog/global", () => {
  const tally = (client: Firestore) => doc(client, "snoozePickLog", "global");
  const admin = () =>
    testEnv
      .authenticatedContext(OWNER, { admin: true })
      .firestore() as unknown as Firestore;

  it("lets any signed-in user move one pick by one, naming it", async () => {
    await assertSucceeds(setDoc(tally(signedIn(OWNER)), { 3: 1, key: "3" }));
    await assertSucceeds(
      setDoc(tally(signedIn(STRANGER)), { 3: 2, key: "3" }, { merge: true }),
    );
    await assertSucceeds(
      setDoc(tally(signedIn(OWNER)), { none: 1, key: "none" }, { merge: true }),
    );
    await assertSucceeds(
      setDoc(tally(signedIn(OWNER)), { 3: 1, key: "3" }, { merge: true }),
    );
  });

  it("refuses other steps, keys, fields and signed-out writes", async () => {
    await assertFails(setDoc(tally(signedIn(OWNER)), { 3: 2, key: "3" }));
    await assertFails(setDoc(tally(signedIn(OWNER)), { 21: 1, key: "21" }));
    await assertFails(setDoc(tally(signedIn(OWNER)), { 3: 1, key: "4" }));
    await assertFails(setDoc(tally(signedIn(OWNER)), { 3: 1, 4: 1, key: "3" }));
    await assertFails(setDoc(tally(signedOut()), { 3: 1, key: "3" }));
    await assertFails(
      setDoc(doc(signedIn(OWNER), "snoozePickLog", "other"), {
        3: 1,
        key: "3",
      }),
    );
  });

  it("is read only by admins and deleted by no one", async () => {
    await assertSucceeds(setDoc(tally(signedIn(OWNER)), { 3: 1, key: "3" }));
    await assertFails(getDoc(tally(signedIn(OWNER))));
    await assertSucceeds(getDoc(tally(admin())));
    await assertFails(deleteDoc(tally(admin())));
  });
});

describe("users/{uid}/meta/snoozeStats", () => {
  const stats = (client: Firestore) =>
    doc(client, "users", OWNER, "meta", "snoozeStats");

  it("takes latr's counts, picks and pick-log switch from the owner", async () => {
    await assertSucceeds(
      setDoc(
        stats(signedIn(OWNER)),
        {
          sets: { "D1@0900": increment(1.5) },
          tod: { "0900": increment(1.5) },
          lastCustom: { target: 1, at: 0 },
          picks: { 1: increment(1) },
          pickLog: true,
        },
        { merge: true },
      ),
    );
    await assertSucceeds(
      setDoc(
        stats(signedIn(OWNER)),
        { pickLog: false, picks: deleteField() },
        { merge: true },
      ),
    );
    await assertFails(
      setDoc(stats(signedIn(STRANGER)), { pickLog: true }, { merge: true }),
    );
  });
});

describe("users/{uid} fields the apps write", () => {
  const user = (client: Firestore) => doc(client, "users", OWNER);

  it("takes every settings write web and Android make", async () => {
    const owner = signedIn(OWNER);
    for (const settings of [
      { signature: "Ana" },
      { signature: "" },
      { signature: null },
      { sendAsDefault: "test@hafa.cc" },
      { sendAsDefault: null },
      { varySendTimes: true },
      { varySendMaxMinutes: 1 },
      { varySendMaxMinutes: 30 },
      { theme: "clay" },
      { shortcuts: true },
      { showTodos: false },
      { quietBundles: true },
      { quietBundles: false },
      { images: "ask" },
      { images: "always" },
      { imageSenders: { "maya@example.org": true } },
    ]) {
      await assertSucceeds(setDoc(user(owner), { settings }, { merge: true }));
    }
    await assertSucceeds(
      updateDoc(
        user(owner),
        new FieldPath("settings", "imageSenders", "maya@example.org"),
        deleteField(),
      ),
    );
  });

  it("refuses other fields and types", async () => {
    const owner = signedIn(OWNER);
    for (const settings of [
      { signature: 5 },
      { sendAsDefault: true },
      { varySendTimes: "yes" },
      { varySendMaxMinutes: 0 },
      { varySendMaxMinutes: 31 },
      { varySendMaxMinutes: 2.5 },
      { showTodos: "on" },
      { quietBundles: "on" },
      { quietBundles: null },
      { images: "never" },
      { imageSenders: ["maya@example.org"] },
      { snoozeTimes: {} },
    ]) {
      await assertFails(setDoc(user(owner), { settings }, { merge: true }));
    }
    await assertFails(setDoc(user(owner), { labels: [] }, { merge: true }));
    await assertFails(updateDoc(user(owner), { lastSeen: 1 }));
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(
        doc(context.firestore() as unknown as Firestore, "users", OWNER),
      );
    });
    await assertFails(setDoc(user(owner), { settings: {}, extra: 1 }));
    await assertSucceeds(setDoc(user(owner), { settings: { theme: "blue" } }));
  });
});

describe("users/{uid} settings.theme", () => {
  const user = (client: Firestore) => doc(client, "users", OWNER);
  const themes = JSON.parse(
    readFileSync(
      new URL("../../shared/themes/themes.json", import.meta.url),
      "utf8",
    ),
  ) as { themes: { id: string }[] };
  const ids = themes.themes.map((theme) => theme.id);

  it("lists the same themes as shared/themes/themes.json", () => {
    const rules = readFileSync(
      new URL("../firestore.rules", import.meta.url),
      "utf8",
    );
    const listed = /settings\.theme in \[([^\]]*)\]/.exec(rules)?.[1];
    expect(
      listed?.split(",").map((id) => id.trim().replaceAll("'", "")),
    ).toEqual(ids);
  });

  it("takes a known theme and refuses anything else", async () => {
    const owner = signedIn(OWNER);
    for (const id of ids) {
      await assertSucceeds(
        setDoc(user(owner), { settings: { theme: id } }, { merge: true }),
      );
    }
    await assertFails(
      setDoc(user(owner), { settings: { theme: "neon" } }, { merge: true }),
    );
    await assertFails(
      setDoc(user(owner), { settings: { theme: 3 } }, { merge: true }),
    );
    await assertFails(
      setDoc(user(owner), { settings: { notify: false } }, { merge: true }),
    );
    await assertFails(setDoc(user(owner), { settings: "plum" }));
    await assertFails(updateDoc(user(owner), { settings: 3 }));
  });

  it("takes shortcuts on or off and nothing else", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(user(owner), { settings: { shortcuts: false } }, { merge: true }),
    );
    await assertFails(
      setDoc(user(owner), { settings: { shortcuts: "off" } }, { merge: true }),
    );
  });

  it("takes showing todos on or off", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(user(owner), { settings: { showTodos: true } }, { merge: true }),
    );
    await assertSucceeds(
      setDoc(user(owner), { settings: { showTodos: false } }, { merge: true }),
    );
  });
});

describe("users/{uid}/labelPrefs/{labelId}", () => {
  const prefs = (client: Firestore, id = "Label_1") =>
    doc(client, "users", OWNER, "labelPrefs", id);
  const icons = JSON.parse(
    readFileSync(
      new URL("../../shared/themes/label-icons.json", import.meta.url),
      "utf8",
    ),
  ) as { icons: { id: string }[] };
  const ids = icons.icons.map((icon) => icon.id);

  it("lists the same icons as shared/themes/label-icons.json", () => {
    const rules = readFileSync(
      new URL("../firestore.rules", import.meta.url),
      "utf8",
    );
    const listed = /data\.icon in \[([^\]]*)\]/.exec(rules)?.[1];
    expect(
      listed?.split(",").map((id) => id.trim().replaceAll("'", "")),
    ).toEqual(["none", ...ids]);
  });

  it("takes known fields and icons", async () => {
    const owner = signedIn(OWNER);
    await assertSucceeds(
      setDoc(prefs(owner), { sidebar: false, bundle: true, icon: "none" }),
    );
    for (const id of ids) {
      await assertSucceeds(setDoc(prefs(owner), { icon: id }));
    }
    await assertSucceeds(setDoc(prefs(owner, "CATEGORY_SOCIAL"), {}));
  });

  it("refuses other fields, types and icons, and other users", async () => {
    const owner = signedIn(OWNER);
    await assertFails(setDoc(prefs(owner), { icon: "rocket" }));
    await assertFails(setDoc(prefs(owner), { sidebar: "yes" }));
    await assertFails(setDoc(prefs(owner), { color: "red" }));
    await assertFails(setDoc(prefs(signedIn(STRANGER)), { bundle: false }));
    await assertFails(getDoc(prefs(signedIn(STRANGER))));
  });

  it("gives Gmail's categories only the sidebar and bundle switches", async () => {
    const owner = signedIn(OWNER);
    for (const category of [
      "CATEGORY_PROMOTIONS",
      "CATEGORY_SOCIAL",
      "CATEGORY_UPDATES",
      "CATEGORY_FORUMS",
    ]) {
      await assertSucceeds(
        setDoc(prefs(owner, category), { sidebar: false, bundle: false }),
      );
      await assertFails(setDoc(prefs(owner, category), { icon: "tag" }));
      await assertFails(setDoc(prefs(owner, category), { name: "Deals" }));
      await assertFails(setDoc(prefs(owner, category), { color: "red" }));
    }
  });
});
