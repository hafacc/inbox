import { afterAll, beforeAll, describe, expect, it } from "bun:test";
import { type App, deleteApp, initializeApp } from "firebase-admin/app";
import { type Firestore, getFirestore } from "firebase-admin/firestore";
import { dropWaits } from "../../functions/src/lib/snooze-waits";

// Needs the Firestore emulator: run through `bun run test` (firebase emulators:exec).

let app: App;
let db: Firestore;

beforeAll(() => {
  app = initializeApp({ projectId: "demo-inbox-waits" }, "waits");
  db = getFirestore(app);
});

afterAll(async () => {
  await deleteApp(app);
});

describe("dropWaits", () => {
  it("drops only the named phones' entries", async () => {
    const waitsRef = db.doc("users/owner/meta/snoozeWaits");
    await waitsRef.set({
      waits: {
        "phone:one": { updatedAt: 1 },
        "phone:two": { updatedAt: 2 },
        "phone:three": { updatedAt: 3 },
      },
    });
    await dropWaits(waitsRef, ["phone:one", "phone:three"]);
    expect((await waitsRef.get()).data()).toEqual({
      waits: { "phone:two": { updatedAt: 2 } },
    });
  });

  it("leaves a deleted doc deleted", async () => {
    const waitsRef = db.doc("users/gone/meta/snoozeWaits");
    await waitsRef.delete();
    await dropWaits(waitsRef, ["phone:one"]);
    expect((await waitsRef.get()).exists).toBe(false);
  });
});
