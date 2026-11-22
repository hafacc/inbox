import { expect, test } from "bun:test";
import { imageMarkdown, withoutImage } from "./markdown-edit";

test("image reference uses the name without extension as alt", () => {
  expect(imageMarkdown("photo [1].jpg", "abc@inbox")).toBe(
    "![photo 1](cid:abc@inbox)",
  );
});

test("removing an image keeps the text around it", () => {
  const image = imageMarkdown("chart.png", "a.b@inbox");
  const other = imageMarkdown("other.png", "c@inbox");
  expect(withoutImage(`Before ${image} after\n${other}`, "a.b@inbox")).toBe(
    `Before  after\n${other}`,
  );
  expect(withoutImage(`One\n${image}\nTwo`, "a.b@inbox")).toBe("One\nTwo");
  // A blank line the user left stays.
  expect(withoutImage(`One\n\n${image}`, "a.b@inbox")).toBe("One\n");
  // The dot in the id is not a wildcard.
  expect(withoutImage(imageMarkdown("x.png", "aXb@inbox"), "a.b@inbox")).toBe(
    imageMarkdown("x.png", "aXb@inbox"),
  );
});
