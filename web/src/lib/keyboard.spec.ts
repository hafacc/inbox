import { beforeAll, expect, test } from "bun:test";
import { JSDOM } from "jsdom";
import { leavesShortcuts, todoChordAllowed } from "./keyboard";

let document: Document;

beforeAll(() => {
  document = new JSDOM(`
    <div id="list">
      <div><button id="row" data-shortcuts>Open</button></div>
      <button id="pin">Pin</button>
      <a id="link" href="/x">Link</a>
    </div>
    <div role="menu"><button id="item" role="menuitem">Label</button></div>
    <dialog open><button id="dialog-button">Close</button></dialog>
    <form><button id="send">Send</button><input id="field"></form>
    <div data-todo-id="t1"><textarea id="t1-text"></textarea><button id="t1-pin">Pin</button></div>
    <div data-todo-id="t2"><textarea id="t2-text"></textarea></div>
    <input id="search" type="search">
  `).window.document;
});

function element(id: string): Element {
  return document.getElementById(id)!;
}

test("a list row takes every shortcut, Enter included", () => {
  expect(leavesShortcuts(element("row"), "Enter")).toBe(false);
  expect(leavesShortcuts(element("row"), "j")).toBe(false);
  expect(leavesShortcuts(element("list"), "e")).toBe(false);
  expect(leavesShortcuts(null, "e")).toBe(false);
});

test("Enter on a button or link clicks it; letter shortcuts still work there", () => {
  expect(leavesShortcuts(element("pin"), "Enter")).toBe(true);
  expect(leavesShortcuts(element("link"), "Enter")).toBe(true);
  expect(leavesShortcuts(element("pin"), "e")).toBe(false);
});

test("menus, dialogs and the composer keep every key", () => {
  for (const id of ["item", "dialog-button", "send", "field"]) {
    expect(leavesShortcuts(element(id), "e")).toBe(true);
    expect(leavesShortcuts(element(id), "Enter")).toBe(true);
  }
});

test("a todo chord acts on its own text, never on another field being typed in", () => {
  expect(todoChordAllowed(element("t1-text"), "t1")).toBe(true);
  expect(todoChordAllowed(element("t1-pin"), "t1")).toBe(true);
  expect(todoChordAllowed(element("list"), "t1")).toBe(true);
  expect(todoChordAllowed(null, "t1")).toBe(true);
  expect(todoChordAllowed(element("t2-text"), "t1")).toBe(false);
  expect(todoChordAllowed(element("search"), "t1")).toBe(false);
  expect(todoChordAllowed(element("field"), "t1")).toBe(false);
});
