import { grade, type GradeRequest, type Provider } from "./grade";
import { UPLOAD_PAGE } from "./page";
import { validateDeck } from "./validate";

export interface Env {
  DB: D1Database;
  APP_TOKEN: string;
  ANTHROPIC_API_KEY?: string;
  DEEPSEEK_API_KEY?: string;
  GRADER_PROVIDER?: string;
  GRADER_MODEL?: string;
}

const DEFAULT_MODELS: Record<Provider, string> = {
  anthropic: "claude-haiku-5-5",
  deepseek: "deepseek-chat",
};

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

function authorized(req: Request, env: Env): boolean {
  if (!env.APP_TOKEN) return false;
  const header = req.headers.get("authorization") ?? "";
  const given = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (given.length !== env.APP_TOKEN.length) return false;
  let diff = 0;
  for (let i = 0; i < given.length; i++) diff |= given.charCodeAt(i) ^ env.APP_TOKEN.charCodeAt(i);
  return diff === 0;
}

async function readJson(req: Request): Promise<unknown> {
  try {
    return await req.json();
  } catch {
    return undefined;
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const route = `${req.method} ${url.pathname}`;

    if (route === "GET /") {
      return new Response(UPLOAD_PAGE, { headers: { "content-type": "text/html; charset=utf-8" } });
    }
    if (route === "GET /api/health") return json({ ok: true, grader: env.GRADER_PROVIDER === "deepseek" ? "deepseek" : "anthropic" });
    if (!url.pathname.startsWith("/api/")) return json({ error: "not found" }, 404);
    if (!authorized(req, env)) return json({ error: "Неверный ключ доступа" }, 401);

    switch (route) {
      case "POST /api/decks": {
        const data = await readJson(req);
        const check = validateDeck(data);
        if (!check.ok) return json({ error: "Колода не прошла проверку", details: check.errors }, 400);
        const now = Date.now();
        await env.DB.prepare(
          "INSERT INTO decks (id, title, json, updated_at) VALUES (?1, ?2, ?3, ?4) " +
            "ON CONFLICT(id) DO UPDATE SET title = excluded.title, json = excluded.json, updated_at = excluded.updated_at",
        ).bind(check.deck.id, check.deck.title, JSON.stringify(data), now).run();
        return json({ ok: true, deck: check.deck, updated_at: now });
      }

      case "GET /api/decks": {
        const since = Number(url.searchParams.get("since") ?? 0) || 0;
        const now = Date.now();
        const { results } = await env.DB.prepare(
          "SELECT id, title, json, updated_at FROM decks WHERE updated_at > ?1 ORDER BY updated_at",
        ).bind(since).all<{ id: string; title: string; json: string; updated_at: number }>();
        return json({
          now,
          decks: results.map((r) => ({ id: r.id, title: r.title, updated_at: r.updated_at, json: JSON.parse(r.json) })),
        });
      }

      case "DELETE /api/decks": {
        const id = url.searchParams.get("id");
        if (!id) return json({ error: "Укажите id" }, 400);
        await env.DB.prepare("DELETE FROM decks WHERE id = ?1").bind(id).run();
        return json({ ok: true });
      }

      case "POST /api/grade": {
        const body = (await readJson(req)) as GradeRequest | undefined;
        if (!body?.item?.prompt || typeof body.answer !== "string") return json({ error: "Нужны item и answer" }, 400);
        const provider: Provider = env.GRADER_PROVIDER === "deepseek" ? "deepseek" : "anthropic";
        const apiKey = provider === "deepseek" ? env.DEEPSEEK_API_KEY : env.ANTHROPIC_API_KEY;
        if (!apiKey) {
          return json({ error: `На сервере не задан ${provider === "deepseek" ? "DEEPSEEK_API_KEY" : "ANTHROPIC_API_KEY"}` }, 503);
        }
        try {
          const result = await grade(provider, apiKey, env.GRADER_MODEL || DEFAULT_MODELS[provider], {
            item: {
              prompt: body.item.prompt,
              answer: body.item.answer,
              key_points: body.item.key_points ?? [],
              common_errors: body.item.common_errors ?? [],
            },
            answer: body.answer.slice(0, 8000),
          });
          return json(result);
        } catch (e) {
          return json({ error: e instanceof Error ? e.message : String(e) }, 502);
        }
      }

      case "POST /api/reviews": {
        const body = (await readJson(req)) as { reviews?: { id: string; itemId: string; ts: number }[] } | undefined;
        const reviews = body?.reviews ?? [];
        const stmt = env.DB.prepare("INSERT OR IGNORE INTO reviews (id, item_id, ts, payload) VALUES (?1, ?2, ?3, ?4)");
        for (let i = 0; i < reviews.length; i += 50) {
          await env.DB.batch(
            reviews.slice(i, i + 50).map((r) => stmt.bind(r.id, r.itemId, r.ts, JSON.stringify(r))),
          );
        }
        return json({ ok: true, received: reviews.length });
      }

      case "GET /api/export": {
        const decks = await env.DB.prepare("SELECT id, title, updated_at FROM decks ORDER BY title").all();
        const reviews = await env.DB.prepare("SELECT payload FROM reviews ORDER BY ts").all<{ payload: string }>();
        return json({
          exported_at: Date.now(),
          decks: decks.results,
          reviews: reviews.results.map((r) => JSON.parse(r.payload)),
        });
      }
    }
    return json({ error: "not found" }, 404);
  },
} satisfies ExportedHandler<Env>;
