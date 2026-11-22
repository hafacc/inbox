// Click-tracking redirect wrappers unwrapped to the link they lead to, where that link is in the
// wrapper's own URL. Pure; web uses it directly, Android ports it to :core (link-fixtures.json).

/** Most wrappers unwrapped from one link (wrappers can be nested). */
export const MAX_UNWRAP_DEPTH = 5;

type Wrapper = {
  /** whether a parsed URL is this wrapper */
  matches: (url: URL) => boolean;
  /** the wrapped link, or null when it can't be read */
  target: (url: URL, text: string) => string | null;
};

function param(...names: string[]): Wrapper["target"] {
  return (url) => {
    for (const name of names) {
      const value = url.searchParams.get(name);
      if (value) return value;
    }
    return null;
  };
}

/** Proofpoint URL Defense v2: `u=` with `-` for `%` and `_` for `/`. */
function proofpointV2(url: URL): string | null {
  const value = url.searchParams.get("u");
  if (!value) return null;
  try {
    return decodeURIComponent(value.replace(/-/g, "%").replace(/_/g, "/"));
  } catch {
    return null;
  }
}

/** Proofpoint URL Defense v3: `/v3/__<url>__;…`; `*` marks characters kept elsewhere, so skip those. */
function proofpointV3(_url: URL, text: string): string | null {
  const match = /^https:\/\/urldefense\.com\/v3\/__(.+?)__;/i.exec(text);
  if (match === null || match[1]!.includes("*")) return null;
  return match[1]!;
}

const WRAPPERS: Wrapper[] = [
  {
    matches: (url) =>
      /^(www\.)?google\.com$/.test(url.hostname) && url.pathname === "/url",
    target: param("q", "url"),
  },
  {
    matches: (url) =>
      url.hostname.endsWith(".safelinks.protection.outlook.com"),
    target: param("url"),
  },
  {
    matches: (url) =>
      url.hostname === "urldefense.proofpoint.com" &&
      url.pathname === "/v2/url",
    target: proofpointV2,
  },
  {
    matches: (url) =>
      url.hostname === "urldefense.com" && url.pathname.startsWith("/v3/__"),
    target: proofpointV3,
  },
  {
    matches: (url) =>
      /^(l|lm)\.facebook\.com$/.test(url.hostname) && url.pathname === "/l.php",
    target: param("u"),
  },
  {
    matches: (url) =>
      /^(www\.|m\.)?youtube\.com$/.test(url.hostname) &&
      url.pathname === "/redirect",
    target: param("q"),
  },
  {
    matches: (url) =>
      url.hostname === "slack-redir.net" && url.pathname === "/link",
    target: param("url"),
  },
  {
    matches: (url) =>
      /^(www\.)?linkedin\.com$/.test(url.hostname) &&
      url.pathname === "/redir/redirect",
    target: param("url"),
  },
];

/**
 * The link a click-tracking wrapper leads to: Google (`google.com/url?q=`), Outlook Safe Links,
 * Proofpoint URL Defense v2 and v3, Facebook, YouTube, Slack and LinkedIn redirects, nested up
 * to `MAX_UNWRAP_DEPTH`. Wrappers that only hold an id (Mailchimp, SendGrid, …) can't be
 * unwrapped and are returned as they are, as is anything whose target isn't an http(s) or
 * `mailto:` URL.
 */
export function unwrapLink(link: string): string {
  let current = link.trim();
  let url = URL.parse(current);
  for (let depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
    if (url === null || !/^https?:$/.test(url.protocol)) break;
    const wrapping = url;
    const wrapper = WRAPPERS.find((candidate) => candidate.matches(wrapping));
    const target = wrapper?.target(wrapping, current)?.trim() ?? null;
    if (target === null) break;
    const next = URL.parse(target);
    if (next === null || !/^(https?|mailto):$/.test(next.protocol)) break;
    current = target;
    url = next;
  }
  return current;
}
