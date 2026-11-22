<!-- @component Sanitized message HTML in a sandboxed frame that grows to fit it, scripts off and remote images held back unless allowed. Inside an open thread its touches go to the thread too, so it can be pulled closed. Mail with no background of its own sits straight on the page; designed mail keeps its own box. In dark mode its colors are turned dark (`darkenMessageHtml`). -->
<script lang="ts">
  import {
    isDarkenedWhite,
    isNearWhite,
    type Rgba,
  } from "@inbox/shared/dark-mail";
  import { darkPalette, emailStyle, themeColors } from "@inbox/shared/themes";
  import { darkenMessageHtml } from "../dark-mail";
  import { messageSrcdoc } from "../email-html";
  import { isEditableTarget } from "../keyboard";
  import { openMark, openTimed } from "../open-timing";
  import { getThemeState } from "../theme.svelte";
  import { getThreadTouches } from "../thread-pull";
  import { phoneWidth } from "./phone";

  let {
    html,
    title,
    showImages = false,
    colorSchemes = [],
  }: {
    /** HTML already through `cleanMessageHtml` */
    html: string;
    /** the frame's accessible name */
    title: string;
    /** whether remote images may load */
    showImages?: boolean;
    /** the mail's `color-scheme` meta contents, from cleaning */
    colorSchemes?: readonly string[];
  } = $props();

  // How much of the frame's width a background must cover to count as the mail's own design.
  const WIDE = 0.5;
  // Elements looked at for a background; designed mail shows one near the top.
  const SCAN_LIMIT = 400;
  let frame = $state<HTMLIFrameElement>();
  // The document set up last; a new srcdoc's is the one that replaces it.
  let prepared = $state.raw<Document | null>(null);
  let height = $state(0);
  // Whether the frame has been sized once; it grows smoothly only after, so it opens at its size.
  let measured = $state(false);
  let designed = $state(false);
  const theme = getThemeState();
  const threadTouches = getThreadTouches();
  const palette = $derived(darkPalette(theme.colorTheme));
  const shownHtml = $derived(
    theme.resolved === "dark"
      ? openTimed("message darkened", () =>
          darkenMessageHtml(html, { palette, colorSchemes }),
        )
      : html,
  );
  const srcdoc = $derived(
    messageSrcdoc(
      shownHtml,
      showImages,
      emailStyle(theme.colorTheme, theme.resolved),
      theme.resolved,
      themeColors(theme.colorTheme, theme.resolved)["surface-muted"],
    ),
  );

  /** Whether a computed background is plain: near white, or in dark mode what near white turns into. */
  function plainBackground(color: string): boolean {
    const parts = color.match(/[\d.]+/g)?.map(Number) ?? [];
    const [red = 255, green = 255, blue = 255, alpha = 1] = parts;
    const rgba: Rgba = { red, green, blue, alpha };
    return (
      isNearWhite(rgba) ||
      (theme.resolved === "dark" && isDarkenedWhite(rgba, palette))
    );
  }

  /**
   * Whether the mail draws a colored or pictured background across most of its width, as a
   * designed page does. White boxes in plain mail are cleared so it takes the page's color.
   */
  function hasOwnBackground(document: Document): boolean {
    const view = document.defaultView;
    const body = document.body;
    if (!view || !body) return false;
    const width = document.documentElement.clientWidth || 1;
    const elements = [body, ...body.querySelectorAll("*")].slice(0, SCAN_LIMIT);
    const whiteBoxes: HTMLElement[] = [];
    for (const element of elements) {
      const style = view.getComputedStyle(element);
      const wide = element.getBoundingClientRect().width >= width * WIDE;
      if (!wide) continue;
      if (style.backgroundImage !== "none") return true;
      if (!plainBackground(style.backgroundColor)) return true;
      if (element instanceof view.HTMLElement) whiteBoxes.push(element);
    }
    // Dark mail's turned backgrounds are `!important`, so clearing them must be too.
    const priority = theme.resolved === "dark" ? "important" : "";
    for (const box of whiteBoxes) {
      box.style.setProperty("background-color", "transparent", priority);
    }
    return false;
  }

  /**
   * Narrow fixed-width layouts (a 600px newsletter table on a phone) to the frame's width, so the
   * mail wraps instead of running past the frame's edge. Mail that already fits is left alone.
   */
  function fitWidth(document: Document): void {
    const width = document.documentElement.clientWidth;
    const body = document.body;
    if (!body || width === 0 || body.scrollWidth <= width) return;
    // Every width is read before any is set, so the layout is worked out once, not per element.
    const wide = [
      ...body.querySelectorAll<HTMLElement>("table, td, th, div, center"),
    ].filter((element) => element.getBoundingClientRect().width > width);
    for (const element of wide) {
      const fill = element.tagName === "TABLE" ? "100%" : "auto";
      element.style.setProperty("width", fill, "important");
      element.style.setProperty("min-width", "0", "important");
      element.style.setProperty("max-width", "100%", "important");
    }
  }

  // Each srcdoc's document is set up once, as soon as it is parsed: its load waits for every image,
  // so each frame until then looks for it, and the load event sets it up at the latest.
  $effect(() => {
    const element = frame;
    void srcdoc;
    if (!element) return;
    let observer: ResizeObserver | null = null;
    const measure = () => {
      const document = element.contentDocument;
      const body = document?.body;
      const view = document?.defaultView;
      if (!body || !view) return;
      // The body's own box, not the root's scroll height: that never drops below the frame's
      // height, so the frame could grow but not shrink.
      const box = body.getBoundingClientRect();
      const margin = Number.parseFloat(
        view.getComputedStyle(body).marginBottom,
      );
      height = Math.ceil(
        Math.max(box.bottom + (margin || 0), box.top + body.scrollHeight),
      );
      if (!measured) requestAnimationFrame(() => (measured = true));
    };
    // Keys pressed in the frame go to the app's shortcuts, as if pressed on the frame itself.
    const forwardKey = (event: KeyboardEvent) => {
      if (isEditableTarget(event.target)) return;
      const forwarded = new KeyboardEvent("keydown", {
        key: event.key,
        code: event.code,
        location: event.location,
        repeat: event.repeat,
        shiftKey: event.shiftKey,
        ctrlKey: event.ctrlKey,
        altKey: event.altKey,
        metaKey: event.metaKey,
        bubbles: true,
        cancelable: true,
      });
      if (!element.dispatchEvent(forwarded)) event.preventDefault();
    };
    let polling = 0;
    const prepare = () => {
      const document = element.contentDocument;
      if (!document) return;
      if (document === prepared) {
        measure();
        return;
      }
      prepared = document;
      openMark("frame parsed");
      const preparing = performance.now();
      fitWidth(document);
      designed = hasOwnBackground(document);
      if (designed) delete document.documentElement.dataset.plain;
      else document.documentElement.dataset.plain = "";
      measure();
      openMark("frame fitted and sized", performance.now() - preparing);
      observer?.disconnect();
      document.addEventListener("error", measure, true);
      // Late images and fonts change the height without resizing the body's box first.
      document.addEventListener("load", measure, true);
      document.addEventListener("keydown", forwardKey);
      // Touches in the frame never reach the page, so the open thread hears them from here.
      if (threadTouches) {
        document.addEventListener("touchstart", threadTouches.start, {
          passive: true,
        });
        document.addEventListener("touchmove", threadTouches.move, {
          passive: false,
        });
        document.addEventListener("touchend", threadTouches.end);
        document.addEventListener("touchcancel", threadTouches.end);
      }
      void document.fonts?.ready.then(measure);
      observer = new ResizeObserver(measure);
      observer.observe(document.documentElement);
      if (document.body) observer.observe(document.body);
    };
    const poll = () => {
      const document = element.contentDocument;
      if (
        document &&
        document !== prepared &&
        document.URL === "about:srcdoc" &&
        document.readyState !== "loading" &&
        document.body
      ) {
        prepare();
      } else {
        polling = requestAnimationFrame(poll);
      }
    };
    const loaded = () => {
      cancelAnimationFrame(polling);
      prepare();
    };
    element.addEventListener("load", loaded);
    polling = requestAnimationFrame(poll);
    return () => {
      cancelAnimationFrame(polling);
      element.removeEventListener("load", loaded);
      observer?.disconnect();
    };
  });

  // On a phone designed mail drops its side padding, which needs no new document.
  $effect(() => {
    prepared?.documentElement.toggleAttribute("data-phone", phoneWidth.current);
  });
</script>

<!-- No allow-scripts: same origin only lets this page measure the frame's height. -->
<iframe
  bind:this={frame}
  {title}
  sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
  referrerpolicy="no-referrer"
  scrolling="no"
  {srcdoc}
  style:height={`${Math.max(height, 24)}px`}
  style:color-scheme={theme.resolved}
  class={[
    "block w-full max-w-full",
    measured && "transition-[height] duration-150",
    designed && "rounded-2xl shadow-pill",
  ]}
></iframe>
