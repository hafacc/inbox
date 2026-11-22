const CACHE_NAME = "contact-photos";
// A photo is fetched again after this long; until then it comes from the device.
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const STORED_AT_HEADER = "x-stored-at";

/** Pixel sizes photos are requested at, so avatars of nearby sizes share one cached image. */
export const PHOTO_SIZES = [32, 48, 64, 96, 128] as const;

/** A photo URL asking Google for `pixels` square: its `sz` query parameter set (replaced if present). */
export function sizedPhotoUrl(url: string, pixels: number): string {
  try {
    const parsed = new URL(url);
    parsed.searchParams.set("sz", String(pixels));
    return parsed.toString();
  } catch {
    return url;
  }
}

/** The size to request for an avatar drawn `cssPixels` wide on a screen of `pixelRatio`. */
export function photoSize(cssPixels: number, pixelRatio: number): number {
  const needed = Math.ceil(cssPixels * Math.max(1, pixelRatio));
  return PHOTO_SIZES.find((size) => size >= needed) ?? PHOTO_SIZES.at(-1)!;
}

const loading = new Map<string, Promise<string | null>>();
const failed = new Set<string>();

async function openCache(): Promise<Cache | null> {
  if (typeof caches === "undefined") return null;
  return caches.open(CACHE_NAME).catch(() => null);
}

async function load(key: string): Promise<string | null> {
  const cache = await openCache();
  const hit = (await cache?.match(key).catch(() => undefined)) ?? null;
  const storedAt = Number(hit?.headers.get(STORED_AT_HEADER) ?? 0);
  if (hit !== null && Date.now() - storedAt < MAX_AGE_MS) {
    return URL.createObjectURL(await hit.blob());
  }
  try {
    const response = await fetch(key, {
      mode: "cors",
      credentials: "omit",
      referrerPolicy: "no-referrer",
    });
    if (!response.ok) throw new Error(`photo ${response.status}`);
    const blob = await response.blob();
    await cache
      ?.put(
        key,
        new Response(blob, {
          headers: {
            "content-type": blob.type,
            [STORED_AT_HEADER]: String(Date.now()),
          },
        }),
      )
      .catch(() => {});
    return URL.createObjectURL(blob);
  } catch {
    if (hit !== null) return URL.createObjectURL(await hit.blob());
    failed.add(key);
    loading.delete(key);
    return null;
  }
}

/**
 * An object URL for a contact photo at `pixels` square, from the device's cache when it has a
 * fresh copy, else fetched and cached. Null when it can't be loaded; a failed photo isn't tried
 * again until `retryFailedPhotos`.
 */
export function photoObjectUrl(
  url: string,
  pixels: number,
): Promise<string | null> {
  const key = sizedPhotoUrl(url, pixels);
  if (failed.has(key)) return Promise.resolve(null);
  let pending = loading.get(key);
  if (pending === undefined) {
    pending = load(key);
    loading.set(key, pending);
  }
  return pending;
}

/** Let photos that failed to load be tried again, as after a contacts sync. */
export function retryFailedPhotos(): void {
  failed.clear();
}

/** Drop the cached images of photos that changed or went away, at every size, freeing their URLs. */
export async function forgetPhotos(urls: Iterable<string>): Promise<void> {
  const cache = await openCache();
  for (const url of urls) {
    for (const size of PHOTO_SIZES) {
      const key = sizedPhotoUrl(url, size);
      const pending = loading.get(key);
      loading.delete(key);
      failed.delete(key);
      void pending?.then((objectUrl) => {
        if (objectUrl !== null) URL.revokeObjectURL(objectUrl);
      });
      await cache?.delete(key).catch(() => false);
    }
  }
}
