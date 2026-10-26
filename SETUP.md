# Установка и запуск

Приложение работает и без сервера: вопросы загружаются файлом, развёрнутые ответы вы оцениваете сами по эталону. Сервер добавляет загрузку вопросов с компьютера, оценку ответов через Claude и резервную копию ответов.

## 1. Приложение на телефоне

**Вариант А, с телефона.** Откройте на телефоне страницу релиза [apk-latest](https://github.com/vital1972/legal-atlas/releases/tag/apk-latest) и скачайте `legal-atlas.apk`. Android спросит разрешение на установку из браузера: разрешите. Новые версии ставятся поверх, данные сохраняются.

**Вариант Б, через Android Studio.** Откройте папку проекта (File → Open → `legal-atlas`), подключите телефон по USB и нажмите Run. Модуль приложения называется `app`.

После установки нажмите «Загрузить пример»: появится карта мира «Гражданское право» с тремя областями под туманом. Нажмите на область, пройдите разведку, и её вопросы начнут приходить в сессиях.

## 2. Сервер на Cloudflare (бесплатно)

Понадобятся: Node.js 20+ на ноутбуке, бесплатный аккаунт [Cloudflare](https://dash.cloudflare.com/sign-up) и ключ API одного из оценщиков:

- Claude: [console.anthropic.com](https://console.anthropic.com) → API Keys, секрет `ANTHROPIC_API_KEY`;
- DeepSeek: [platform.deepseek.com](https://platform.deepseek.com) → API keys, секрет `DEEPSEEK_API_KEY`, и в `server/wrangler.toml` поставьте `GRADER_PROVIDER = "deepseek"`.

Оценка одного ответа у обоих стоит доли цента.

В терминале, в папке проекта:

```bash
cd server
npm install
npx wrangler login                      # откроется браузер, войдите в Cloudflare
npx wrangler d1 create legal-atlas      # создаст базу; скопируйте database_id из вывода
```

Вставьте `database_id` в `server/wrangler.toml` вместо `REPLACE_WITH_DATABASE_ID`, затем:

```bash
npx wrangler d1 execute legal-atlas --remote --file=schema.sql
npx wrangler secret put APP_TOKEN          # придумайте длинный пароль: им пользуются приложение и страница загрузки
npx wrangler secret put ANTHROPIC_API_KEY  # или DEEPSEEK_API_KEY, смотря кого выбрали
npx wrangler deploy
```

`deploy` выведет адрес вида `https://legal-atlas.<ваше-имя>.workers.dev`. Откройте его в браузере: это страница загрузки вопросов.

## 3. Связать приложение с сервером

В приложении: шестерёнка → «Сервер» → адрес и ключ доступа → «Сохранить и синхронизировать». Дальше приложение синхронизируется само при запуске и после каждой сессии.

## Рабочий цикл

1. Перед спором попросите Claude подготовить колоду по нужной области: промпт в [`docs/generation-prompt.md`](docs/generation-prompt.md).
2. Загрузите JSON на странице сервера (или пришлите файл на телефон и откройте через «Поделиться» → «Атлас права»).
3. Занимайтесь сессиями по 10, 20 или 30 минут. Пропуски не страшны: приложение само расставит приоритеты.
4. Раз в неделю скачайте отчёт и попросите Claude доработать колоду под слабые места (промпт там же).

Оценщик выбирается в `server/wrangler.toml`: `GRADER_PROVIDER` (`anthropic` или `deepseek`) и при желании `GRADER_MODEL` (по умолчанию `claude-haiku-5-5` и `deepseek-chat`). Какой оценщик включён, видно по адресу `/api/health`.
