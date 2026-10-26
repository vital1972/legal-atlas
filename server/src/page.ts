// Страница для загрузки колод с компьютера. Ключ доступа хранится только в браузере.
export const UPLOAD_PAGE = `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Атлас права: загрузка вопросов</title>
<style>
  :root { --bg:#14161f; --panel:#1e2130; --text:#eceaf4; --muted:#9a98aa; --gold:#ffc857; --coral:#ff7a6b; --mint:#7fd1b9; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:17px/1.5 system-ui, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { color: var(--gold); font-size: 26px; margin: 0 0 4px; }
  p { color: var(--muted); }
  section { background: var(--panel); border-radius: 12px; padding: 16px; margin-top: 16px; }
  label { display:block; margin-bottom:6px; }
  input[type=password], textarea { width:100%; background:#14161f; color:var(--text); border:1px solid #3a3550; border-radius:8px; padding:10px; font:inherit; }
  textarea { min-height: 220px; font-family: ui-monospace, monospace; font-size: 14px; }
  button { background: var(--gold); color: #14161f; border: 0; border-radius: 8px; padding: 10px 18px; font: inherit; font-weight: 600; cursor: pointer; margin-top: 10px; }
  button.secondary { background: #3a3550; color: var(--text); }
  #status { white-space: pre-wrap; margin-top: 12px; }
  .ok { color: var(--mint); } .err { color: var(--coral); }
  li { margin: 4px 0; }
</style>
</head>
<body>
<main>
  <h1>Атлас права</h1>
  <p>Загрузите JSON с вопросами, подготовленный в Claude. Приложение на телефоне заберёт его при следующей синхронизации.</p>

  <section>
    <label for="token">Ключ доступа (APP_TOKEN)</label>
    <input id="token" type="password" autocomplete="off">
  </section>

  <section>
    <label for="file">Файл JSON</label>
    <input id="file" type="file" accept=".json,application/json">
    <label for="text" style="margin-top:12px">или вставьте текст</label>
    <textarea id="text" placeholder='{"schema_version": "1.0", "deck": {...}, "topics": [...], "items": [...]}'></textarea>
    <button id="upload">Загрузить</button>
    <div id="status"></div>
  </section>

  <section>
    <strong>Колоды на сервере</strong>
    <ul id="decks"><li>Введите ключ, чтобы увидеть список.</li></ul>
    <button class="secondary" id="refresh">Обновить</button>
    <button class="secondary" id="export">Скачать отчёт для Claude</button>
  </section>
</main>
<script>
const $ = (id) => document.getElementById(id);
const tokenInput = $("token");
try { tokenInput.value = localStorage.getItem("atlasToken") || ""; } catch (e) {}
tokenInput.addEventListener("change", () => { try { localStorage.setItem("atlasToken", tokenInput.value); } catch (e) {} refresh(); });
const headers = () => ({ "authorization": "Bearer " + tokenInput.value, "content-type": "application/json" });

function status(text, ok) { const s = $("status"); s.textContent = text; s.className = ok ? "ok" : "err"; }

$("file").addEventListener("change", async (e) => {
  const f = e.target.files[0];
  if (f) $("text").value = await f.text();
});

$("upload").addEventListener("click", async () => {
  let data;
  try { data = JSON.parse($("text").value); } catch (e) { return status("Это не JSON: " + e.message, false); }
  const res = await fetch("/api/decks", { method: "POST", headers: headers(), body: JSON.stringify(data) });
  const body = await res.json().catch(() => ({}));
  if (res.ok) { status("Загружено: «" + body.deck.title + "», вопросов: " + body.deck.items, true); refresh(); }
  else status((body.error || res.status) + (body.details ? "\\n" + body.details.join("\\n") : ""), false);
});

async function refresh() {
  if (!tokenInput.value) return;
  const res = await fetch("/api/decks?since=0", { headers: headers() });
  const list = $("decks");
  list.innerHTML = "";
  if (!res.ok) { list.innerHTML = "<li class='err'>Неверный ключ доступа</li>"; return; }
  const body = await res.json();
  if (!body.decks.length) list.innerHTML = "<li>Пока пусто</li>";
  for (const d of body.decks) {
    const li = document.createElement("li");
    li.textContent = d.title + " — " + d.json.items.length + " вопросов, обновлено " + new Date(d.updated_at).toLocaleString("ru-RU");
    list.appendChild(li);
  }
}
$("refresh").addEventListener("click", refresh);

$("export").addEventListener("click", async () => {
  const res = await fetch("/api/export", { headers: headers() });
  if (!res.ok) return status("Не удалось получить отчёт: " + res.status, false);
  const blob = new Blob([await res.text()], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "legal-atlas-report-" + new Date().toISOString().slice(0, 10) + ".json";
  a.click();
});
refresh();
</script>
</body>
</html>`;
