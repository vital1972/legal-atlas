import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { z } from "zod";

export interface GradeRequest {
  item: {
    prompt: string;
    answer: string;
    key_points: { id: string; text: string; weight?: number; required?: boolean }[];
    common_errors: string[];
  };
  answer: string;
}

const GradeSchema = z.object({
  key_points: z.array(z.object({ id: z.string(), status: z.enum(["covered", "partial", "missing"]) })),
  errors: z.array(z.string()),
  score: z.number(),
  rating: z.number().int(),
  feedback: z.string(),
});

export type GradeResult = z.infer<typeof GradeSchema>;

const SYSTEM = `Ты проверяешь ответ опытного российского юриста на тренировочный вопрос.
Сравни ответ с эталоном и ключевыми тезисами. Оценивай смысл, а не формулировки: синонимы,
другой порядок и сокращения засчитываются. Ссылку на норму засчитывай, если номер статьи верный.

Для каждого ключевого тезиса поставь статус: covered (раскрыт), partial (упомянут неточно или
неполно), missing (отсутствует). В errors перечисли только фактические юридические ошибки в ответе
(неверный срок, не та норма, перепутанные последствия); пропуск тезиса ошибкой не считается.

score: доля набранного веса тезисов от 0 до 1 (covered = полный вес, partial = половина).
rating по шкале интервального повторения:
1 — не раскрыт хотя бы один обязательный тезис или есть фактическая ошибка;
2 — обязательные тезисы есть, но score ниже 0.7;
3 — score от 0.7 до 0.95;
4 — score выше 0.95 и ответ точный.
feedback: 1–3 коротких предложения по-русски, обращение на «вы»: что упущено или неверно и как запомнить.`;

export type Provider = "anthropic" | "deepseek";

function userPrompt(req: GradeRequest): string {
  const points = req.item.key_points
    .map((kp) => `- [${kp.id}] ${kp.text} (вес ${kp.weight ?? 1}${kp.required ? ", обязательный" : ""})`)
    .join("\n");
  return [
    `Вопрос:\n${req.item.prompt}`,
    `Эталонный ответ:\n${req.item.answer}`,
    `Ключевые тезисы:\n${points || "- (не заданы: сравнивай с эталоном целиком)"}`,
    req.item.common_errors.length ? `Типичные ошибки:\n${req.item.common_errors.map((e) => `- ${e}`).join("\n")}` : "",
    `Ответ пользователя:\n<answer>\n${req.answer}\n</answer>`,
  ].filter(Boolean).join("\n\n");
}

function clamp(out: GradeResult): GradeResult {
  return { ...out, score: Math.min(1, Math.max(0, out.score)), rating: Math.min(4, Math.max(1, Math.round(out.rating))) };
}

export async function grade(provider: Provider, apiKey: string, model: string, req: GradeRequest): Promise<GradeResult> {
  return clamp(provider === "deepseek" ? await gradeDeepSeek(apiKey, model, req) : await gradeClaude(apiKey, model, req));
}

async function gradeClaude(apiKey: string, model: string, req: GradeRequest): Promise<GradeResult> {
  const client = new Anthropic({ apiKey });
  const message = await client.messages.parse({
    model,
    max_tokens: 2000,
    system: SYSTEM,
    output_config: { effort: "low", format: zodOutputFormat(GradeSchema) },
    messages: [{ role: "user", content: userPrompt(req) }],
  });
  if (message.stop_reason === "refusal") throw new Error("Модель отказалась оценивать ответ");
  if (!message.parsed_output) throw new Error(`Не удалось разобрать оценку (stop_reason: ${message.stop_reason})`);
  return message.parsed_output;
}

// DeepSeek совместим с форматом OpenAI Chat Completions; JSON-режим требует
// описать ожидаемую структуру в самом промпте.
const JSON_SHAPE = `Ответь только JSON-объектом такого вида:
{"key_points": [{"id": "kp1", "status": "covered"}], "errors": ["..."], "score": 0.8, "rating": 3, "feedback": "..."}`;

async function gradeDeepSeek(apiKey: string, model: string, req: GradeRequest): Promise<GradeResult> {
  const res = await fetch("https://api.deepseek.com/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({
      model,
      max_tokens: 2000,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: `${SYSTEM}\n\n${JSON_SHAPE}` },
        { role: "user", content: userPrompt(req) },
      ],
    }),
  });
  if (!res.ok) throw new Error(`DeepSeek ответил ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const body = (await res.json()) as { choices?: { message?: { content?: string } }[] };
  const content = body.choices?.[0]?.message?.content ?? "";
  let data: unknown;
  try {
    data = JSON.parse(content);
  } catch {
    throw new Error("DeepSeek вернул не JSON");
  }
  const parsed = GradeSchema.safeParse(data);
  if (!parsed.success) throw new Error("DeepSeek вернул JSON не по схеме");
  return parsed.data;
}
