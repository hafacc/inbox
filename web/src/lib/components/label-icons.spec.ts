import { expect, test } from "bun:test";
import { LABEL_ICONS } from "@inbox/shared/label-prefs";
import { LABEL_ICON_COMPONENTS } from "./label-icons";

test("every label icon id has a component, and nothing else does", () => {
  expect(Object.keys(LABEL_ICON_COMPONENTS).sort()).toEqual(
    LABEL_ICONS.map((icon) => icon.id).sort(),
  );
});
