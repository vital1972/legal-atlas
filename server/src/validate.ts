// Быстрая проверка колоды при загрузке. Полную проверку делает приложение
// (core/Decks.kt); здесь отсекаем то, что точно сломает импорт на телефоне.

export interface DeckSummary {
  id: string;
  title: string;
  items: number;
}

export type Validation = { ok: true; deck: DeckSummary } | { ok: false; errors: string[] };

const FORMATS = new Set(["free_recall", "self_check", "cloze", "choice", "case_scenario"]);

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (v: unknown) => typeof v === "string" && v.trim().length > 0;

export function validateDeck(data: unknown): Validation {
  const errors: string[] = [];
  if (!isObj(data)) return { ok: false, errors: ["Ожидается JSON-объект с полями deck, topics, items"] };
  const deck = data.deck;
  if (!isObj(deck) || !str(deck.id) || !str(deck.title) || !str(deck.world)) {
    errors.push("deck должен содержать id, title и world");
  }
  const topics = Array.isArray(data.topics) ? data.topics : null;
  const items = Array.isArray(data.items) ? data.items : null;
  if (!topics) errors.push("topics должен быть массивом");
  if (!items || items.length === 0) errors.push("items должен быть непустым массивом");
  if (errors.length) return { ok: false, errors };

  const topicIds = new Set<string>();
  for (const t of topics!) {
    if (!isObj(t) || !str(t.id) || !str(t.title)) errors.push("У каждой темы нужны id и title");
    else if (topicIds.has(t.id as string)) errors.push(`Тема ${t.id} повторяется`);
    else topicIds.add(t.id as string);
  }
  const itemIds = new Set<string>();
  for (const it of items!) {
    if (!isObj(it) || !str(it.id)) {
      errors.push("У каждого вопроса нужен id");
      continue;
    }
    const where = `Вопрос ${it.id}`;
    if (itemIds.has(it.id as string)) errors.push(`${where}: id повторяется`);
    itemIds.add(it.id as string);
    if (!str(it.prompt)) errors.push(`${where}: пустой prompt`);
    if (!str(it.answer)) errors.push(`${where}: пустой answer`);
    if (!topicIds.has(it.topic_id as string)) errors.push(`${where}: тема ${String(it.topic_id)} не описана в topics`);
    if (it.format !== undefined && !FORMATS.has(it.format as string)) errors.push(`${where}: неизвестный формат ${String(it.format)}`);
    if (it.format === "choice") {
      const options = Array.isArray(it.options) ? it.options : [];
      const correct = it.correct_option;
      if (options.length < 2) errors.push(`${where}: для choice нужно минимум два варианта`);
      if (typeof correct !== "number" || correct < 0 || correct >= options.length) {
        errors.push(`${where}: correct_option вне списка вариантов`);
      }
    }
  }
  if (errors.length) return { ok: false, errors: errors.slice(0, 20) };
  const d = deck as Obj;
  return { ok: true, deck: { id: d.id as string, title: d.title as string, items: items!.length } };
}
