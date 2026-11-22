/** Rows joined in one card of a list, keyed by its place among its heading's cards. */
export type Card<Item> = { kind: "card"; key: string; items: Item[] };

/**
 * A heading's rows, split into cards where something lifts out of them (an open thread, an open
 * bundle): {@link add} each row in order, {@link lift} each lifted one, then {@link done}. Cards are
 * keyed by their place under the heading, so a card keeps its key when its top row goes.
 */
export class CardSplit<Item, Lifted extends { key: string }> {
  /** The cards and lifted pieces so far, in order; {@link done} adds the last card. */
  readonly chunks: (Card<Item> | Lifted)[] = [];
  #heading: string;
  #card: Item[] = [];
  #cards = 0;

  /** `heading` keys the cards (a section's or a bucket's key). */
  constructor(heading: string) {
    this.#heading = heading;
  }

  /** Add a row to the card being filled. */
  add(item: Item): void {
    this.#card.push(item);
  }

  /** End the card being filled, and put `lifted` after it. */
  lift(lifted: Lifted): void {
    this.#endCard();
    this.chunks.push(lifted);
  }

  /** End the last card; returns {@link chunks}. */
  done(): (Card<Item> | Lifted)[] {
    this.#endCard();
    return this.chunks;
  }

  #endCard(): void {
    if (this.#card.length === 0) return;
    this.chunks.push({
      kind: "card",
      key: `card:${this.#heading}:${this.#cards++}`,
      items: this.#card,
    });
    this.#card = [];
  }
}
