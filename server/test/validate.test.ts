import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { validateDeck } from "../src/validate";

const sample = JSON.parse(readFileSync(new URL("../../examples/sample-deck.json", import.meta.url), "utf8"));

describe("validateDeck", () => {
  it("accepts the sample deck", () => {
    const r = validateDeck(sample);
    expect(r).toEqual({ ok: true, deck: { id: "civil-limitation", title: "Исковая давность", items: 10 } });
  });

  it("rejects unknown topics and bad choice questions", () => {
    const bad = {
      deck: { id: "d", title: "t", world: "w" },
      topics: [{ id: "t1", title: "T" }],
      items: [
        { id: "i1", topic_id: "nope", prompt: "p", answer: "a" },
        { id: "i2", topic_id: "t1", format: "choice", prompt: "p", answer: "a", options: ["x"], correct_option: 2 },
      ],
    };
    const r = validateDeck(bad);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors.some((e) => e.includes("nope"))).toBe(true);
      expect(r.errors.some((e) => e.includes("два варианта"))).toBe(true);
    }
  });

  it("rejects non-objects", () => {
    expect(validateDeck([]).ok).toBe(false);
    expect(validateDeck(null).ok).toBe(false);
  });
});
