import type { Attachment } from "svelte/attachments";

// How far down, in pixels, a sheet is dragged before letting go closes it.
const CLOSE_DISTANCE = 96;
// A flick down faster than this, in pixels per millisecond, closes it however short.
const CLOSE_SPEED = 0.5;

/**
 * Let a bottom sheet be dragged down by its handle (the element marked `data-sheet-handle`):
 * it follows the finger, and letting go far or fast enough calls `onClose`, else it springs back.
 */
export function sheetDrag(onClose: () => void): Attachment<HTMLElement> {
  return (sheet) => {
    let startY: number | null = null;
    let startedAt = 0;
    let offset = 0;

    function onDown(event: PointerEvent) {
      if (!(event.target as Element | null)?.closest("[data-sheet-handle]")) {
        return;
      }
      startY = event.clientY;
      startedAt = event.timeStamp;
      offset = 0;
      sheet.setPointerCapture(event.pointerId);
    }

    function onMove(event: PointerEvent) {
      if (startY === null) return;
      offset = Math.max(0, event.clientY - startY);
      sheet.style.transform = `translateY(${offset}px)`;
    }

    function onUp(event: PointerEvent) {
      if (startY === null) return;
      const speed = offset / Math.max(1, event.timeStamp - startedAt);
      startY = null;
      if (offset > CLOSE_DISTANCE || speed > CLOSE_SPEED) {
        onClose();
      } else if (offset > 0) {
        sheet
          .animate(
            [{ transform: `translateY(${offset}px)` }, { transform: "none" }],
            { duration: 160, easing: "cubic-bezier(0.2, 0.8, 0.2, 1)" },
          )
          .finished.catch(() => {});
        sheet.style.transform = "";
      }
    }

    sheet.addEventListener("pointerdown", onDown);
    sheet.addEventListener("pointermove", onMove);
    sheet.addEventListener("pointerup", onUp);
    sheet.addEventListener("pointercancel", onUp);
    return () => {
      sheet.removeEventListener("pointerdown", onDown);
      sheet.removeEventListener("pointermove", onMove);
      sheet.removeEventListener("pointerup", onUp);
      sheet.removeEventListener("pointercancel", onUp);
    };
  };
}
