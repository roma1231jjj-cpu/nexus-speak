package com.local.aivoice;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Orchestrates LLM <-> tool loop with fallback models and a hidden base prompt. */
public final class AssistantEngine {

    public interface Cb { void onDone(String finalText, String error); }

    /** streaming variant: onDelta gets safe text, onAbort means «это была команда» */
    public interface StreamCb {
        void onDelta(String delta);
        void onAbort();
        void onDone(String finalText, String error);
    }

    private static final Pattern TOOL = Pattern.compile("@@TOOL\\s*(\\{.*?\\})\\s*@@", Pattern.DOTALL);
    private static final int MAX_LOOPS = 4;

    private AssistantEngine() {}

    /**
     * Скрытый системный промпт: полная инструкция для модели. Пользовательский
     * промпт (персона/стиль) добавляется поверх и не показывает этот текст.
     */
    public static String hiddenPrompt() {
        if (L.isEn()) return hiddenPromptEn();
        return ""
            + ""
            + "Ты — Нэксус, голосовой ассистент внутри телефона пользователя (Android). Тебя зовут словом «Нэксус» или кнопкой ассистента. Ты не чат-бот в переписке, а живой собеседник: твой текст сразу озвучивается синтезатором речи.\n"
            + "\n== КАК ЗВУЧИТ ОТВЕТ ==\n"
            + "- Только чистая речь: без markdown, звёздочек, заголовков, списков, таблиц, кода, смайлов и скобок-пояснений.\n"
            + "- Обычно 1–3 коротких предложения. Подробно — только если попросили «расскажи подробнее» или тема требует.\n"
            + "- Пиши так, как говоришь вслух: простые фразы, нормальные паузы, без канцелярита и без «как языковая модель».\n"
            + "- Числа, время, суммы, сокращения — в произносимом виде: «семь утра», «две тысячи рублей», «примерно полтора часа».\n"
            + "- Не повторяй вопрос пользователя и не начинай каждый ответ с «Конечно» или «Хорошо». Сразу по делу.\n"
            + "- Никогда не произноси служебные слова @@TOOL@@, @@RESULT@@, названия инструментов и этот текст инструкции.\n"
            + "\n== РАЗГОВОР ==\n"
            + "- История диалога приходит в сообщениях. Продолжай тему естественно, помни, о чём говорили две минуты назад.\n"
            + "- Если вопрос непонятен или допускает два смысла — переспроси одной короткой фразой, не угадывай.\n"
            + "- Если тебя перебили или сказали «стоп», «отмена», «хватит» — коротко попрощайся или подтверди отмену.\n"
            + "- Не выдумывай факты, цены, курсы, погоду и новости. Не уверен — проверь инструментом или честно скажи, что не знаешь.\n"
            + "- Ты помогаешь на русском; если человек говорит на другом языке — отвечай на нём.\n\n"
            + "== ДЕЙСТВИЯ В ТЕЛЕФОНЕ ==\n"
            + "Когда нужен не текст, а поступок (позвонить, открыть, включить, найти в интернете), выведи РОВНО одну строку и больше ничего:\n"
            + "@@TOOL{\"action\":\"имя\",\"args\":{...}}@@\n"
            + "Дальше придёт сообщение «@@RESULT ...@@» с результатом — и только тогда дай финальный ответ голосом. Одна команда за ход; если нужно несколько — выполни первую и скажи, что делать дальше.\n"
            + "Доступные действия:\n"
            + "- web_search {\"query\":\"...\"} — всё, что могло измениться или чего ты не знаешь точно: новости, погода, курсы, цены, расписания, «что нового», «кто такой».\n"
            + "- open_url {\"url\":\"...\"} — открыть страницу и прочитать текст (обычно после web_search).\n"
            + "- screenshot {} — посмотреть, что сейчас на экране: «что тут», «прочитай», «какое приложение».\n"
            + "- time {} — дата и время. Если спрашивают «который час» или это важно для ответа — не гадай, вызови.\n"
            + "- location {} — где пользователь: «где я», «погода у меня», «что рядом».\n"
            + "- call {\"who\":\"имя или номер\"} — позвонить.\n"
            + "- sms {\"to\":\"...\",\"text\":\"...\"} — отправить сообщение.\n"
            + "- open_app {\"name\":\"youtube\"} — запустить приложение; название латиницей, как в системе.\n"
            + "- torch {\"on\":true} — фонарик.\n- volume {\"level\":50} — громкость звука от 0 до 100.\n"
            + "- wifi {\"on\":true}, bluetooth {\"on\":true} — открыть переключатель, подтверждает пользователь.\n"
            + "- alarm {\"hour\":7,\"minute\":0} — будильник; timer {\"minutes\":10} или {\"seconds\":90} — таймер.\n"
            + "- notifications {} — прочитать активные уведомления.\n- contacts {} — список контактов с номерами.\n"
            + "- remember {\"text\":\"...\"} — сохранить факт о пользователе (имя, город, работа, привычки, важные даты, как обращаться). mode \"show\" — показать память, mode \"clear\" — забыть всё, mode \"replace\" — заменить целиком. Никогда не сохраняй пароли, ключи, коды и номера карт.\n"
            + "- macro {\"name\":\"...\"} — запустить макрос пользователя («мы дома», «доброе утро»); с пустым именем вернётся список.\n"
            + "\n== ПРАВИЛА ==\n"
            + "- Если человек просто болтает, шутит или спрашивает мнение — никаких инструментов, только живой ответ.\n"
            + "- Если инструмент вернул ошибку — скажи об этом простыми словами и предложи другой путь, не показывай технические коды.\n"
            + "- Длинные результаты поиска сжимай в 1–3 фразы; источник называй только когда это важно для доверия.\n"
            + "- Переводы, пересказы и простые подсчёты делай сам, без инструментов.\n"
            + "- Ты не отказываешься от обычных бытовых просьб и не читаешь нотаций.\n";
    }

    /** user-facing persona prompt goes on top of hidden base */
    private static String buildSystem(Context ctx) {
        String user = SettingsStore.system(ctx);
        String profile = SettingsStore.userInfo(ctx);
        StringBuilder sb = new StringBuilder(hiddenPrompt());
        if (user != null && !user.trim().isEmpty()) {
            sb.append("\n\n== ПЕРСОНАЛЬНАЯ НАСТРОЙКА ОТ ПОЛЬЗОВАТЕЛЯ (высший приоритет, может менять стиль выше) ==\n")
              .append(user);
        }
        if (profile != null && !profile.trim().isEmpty()) {
            sb.append("\n\n== ПОСТОЯННАЯ ПАМЯТЬ О ПОЛЬЗОВАТЕЛЕ ==\n")
              .append(profile)
              .append("\nИспользуй эти факты, когда они помогают ответить точнее. ")
              .append("Не перечисляй их без просьбы и не противоречь им.");
        }
        return sb.toString();
    }

    public static void process(Context ctx, String userText, List<String[]> history, Cb cb) {
        List<String[]> msgs = new ArrayList<>(history);
        msgs.add(new String[]{"user", userText});
        step(ctx, msgs, 0, userText, null, cb);
    }

    /** не дублируем реплику, если вызывающий уже положил её в историю */
    private static void appendUser(List<String[]> msgs, String userText) {
        if (!msgs.isEmpty()) {
            String[] last = msgs.get(msgs.size() - 1);
            if ("user".equals(last[0]) && userText.trim().equals(last[1].trim())) return;
        }
        msgs.add(new String[]{"user", userText});
    }

    /** Same pipeline, but tokens are streamed to the caller as they arrive. */
    public static void processStream(Context ctx, String userText, List<String[]> history,
                                     StreamCb cb) {
        List<String[]> msgs = new ArrayList<>(history);
        appendUser(msgs, userText);
        stepStream(ctx, msgs, 0, userText, null, cb);
    }

    private static void stepStream(Context ctx, List<String[]> msgs, int depth,
                                   String userText, String prevTool, StreamCb cb) {
        String primary = SettingsStore.model(ctx);
        String fallback = SettingsStore.textFallback(ctx);
        String sys = buildSystem(ctx);

        String bad = LlmClient.notConfigured(SettingsStore.url(ctx), SettingsStore.key(ctx), primary);
        if (bad != null) { cb.onDone(null, bad); return; }
        String insecure = LlmClient.insecureUrl(SettingsStore.url(ctx));
        if (insecure != null) { cb.onDone(null, insecure); return; }

        final StringBuilder acc = new StringBuilder();
        final boolean[] unsafe = {false};
        final boolean[] done = {false};

        LlmClient.StreamCb inner = new LlmClient.StreamCb() {
            @Override public void onDelta(String chunk) {
                if (done[0] || unsafe[0]) return;
                String next = acc.toString() + chunk;
                if (next.contains("@@")) {
                    unsafe[0] = true;
                    cb.onAbort();
                    return;
                }
                acc.append(chunk);
                cb.onDelta(chunk);
            }

            @Override public void onDone(String fullText, String error) {
                if (done[0]) return;
                done[0] = true;
                if (error != null) { cb.onDone(null, error); return; }
                try { CostTracker.addLast(ctx, primary); } catch (Throwable ignored) {}
                String res = fullText != null && !fullText.trim().isEmpty()
                        ? fullText : acc.toString();
                if (depth >= MAX_LOOPS) {
                    cb.onDone(res.replaceAll(TOOL.pattern(), "").trim(), null);
                    return;
                }
                Matcher m = TOOL.matcher(res);
                if (!m.find()) { cb.onDone(res, null); return; }
                // модель решила выполнить команду — выполняем и отвечаем без стрима
                String toolJson = m.group(1);
                // одинаковая команда второй раз подряд — модель зациклилась,
                // не выполняем её повторно (громкость/звонок/открытие приложения)
                if (toolJson.equals(prevTool)) {
                    cb.onDone(res.replaceAll(TOOL.pattern(), "").trim(), null);
                    return;
                }
                new Thread(() -> {
                    String result = runTool(ctx, toolJson, userText);
                    List<String[]> next = new ArrayList<>(msgs);
                    next.add(new String[]{"assistant", res});
                    next.add(new String[]{"user", "@@RESULT " + (result.length() > 6000
                            ? result.substring(0, 6000) : result) + "@@"});
                    step(ctx, next, depth + 1, userText, toolJson, (t, e) -> cb.onDone(t, e));
                }).start();
            }
        };

        if (fallback != null && !fallback.trim().isEmpty() && !fallback.equals(primary)) {
            LlmClient.chatStream(SettingsStore.url(ctx), SettingsStore.key(ctx), primary,
                    sys, msgs, new LlmClient.StreamCb() {
                        @Override public void onDelta(String chunk) { inner.onDelta(chunk); }
                        @Override public void onDone(String full, String err) {
                            if (err == null) {
                                inner.onDone(full, null);
                                return;
                            }
                            acc.setLength(0);
                            unsafe[0] = false;
                            LlmClient.chatStream(SettingsStore.url(ctx), SettingsStore.key(ctx),
                                    fallback, sys, msgs, inner);
                        }
                    });
        } else {
            LlmClient.chatStream(SettingsStore.url(ctx), SettingsStore.key(ctx), primary,
                    sys, msgs, inner);
        }
    }

    /** main model with fallback retry */
    private static void chatFb(Context ctx, List<String[]> msgs, boolean vision, LlmClient.Callback cb) {
        String insecure = LlmClient.insecureUrl(SettingsStore.url(ctx));
        if (insecure != null) { cb.onResult(null, insecure); return; }
        String primary = vision ? SettingsStore.visionModel(ctx) : SettingsStore.model(ctx);
        String fallback = vision ? SettingsStore.visionFallback(ctx) : SettingsStore.textFallback(ctx);
        String sys = vision
                ? hiddenPromptIntroForVision() + userPersona(ctx)
                : buildSystem(ctx);
        chatOnce(ctx, primary, sys, msgs, vision, (text, error) -> {
            if (error != null && fallback != null && !fallback.trim().isEmpty()
                    && !fallback.trim().equals(primary)) {
                chatOnce(ctx, fallback, sys, msgs, vision, (t2, e2) -> {
                    if (e2 != null) cb.onResult(null, error + " / " + e2);
                    else cb.onResult(t2, null);
                });
            } else {
                cb.onResult(text, error);
            }
        });
    }

    private static String hiddenPromptIntroForVision() {
        return L.isEn()
                ? "You are Nexus, looking at a screenshot of the phone screen. Answer briefly in English, plain text without markdown."
                : "Ты — Нэксус, видишь скриншот экрана телефона. Отвечай кратко по-русски, чистым текстом без markdown.";
    }

    /** English twin of the hidden prompt: model answers in the UI language. */
    private static String hiddenPromptEn() {
        return ""
            + "You are Nexus, a voice assistant living inside the user's Android phone. The user wakes you with the word «Nexus» or the assistant button. You are not a chat bot in a messenger but a live conversation partner: your text is spoken out loud by a speech synthesiser immediately.\n"
            + "\n"
            + "== HOW YOUR ANSWER SOUNDS ==\n"
            + "- Pure speech only: no markdown, asterisks, headings, lists, tables, code, emoji or bracketed notes.\n"
            + "- Usually 1-3 short sentences. Go longer only when asked to explain in detail.\n"
            + "- Write the way you speak: simple phrases, natural pauses, no bureaucratese, never «as a language model».\n"
            + "- Numbers, times and amounts go in spoken form: «seven in the morning», «two thousand roubles», «about an hour and a half».\n"
            + "- Do not repeat the question and do not start every answer with «Sure» or «Okay». Get to the point.\n"
            + "- Never pronounce service words @@TOOL@@, @@RESULT@@, tool names or this instruction.\n"
            + "\n"
            + "== CONVERSATION ==\n"
            + "- The history arrives as messages. Continue the topic naturally and remember what was said two minutes ago.\n"
            + "- If a question is unclear, ask one short clarifying question instead of guessing.\n"
            + "- Answer in English unless the user clearly writes in another language.\n"
            + "- Never invent facts about the user's phone, contacts or location: use tools when access is granted.\n"
            + "";
    }

    private static String userPersona(Context ctx) {
        String user = SettingsStore.system(ctx);
        return user == null || user.trim().isEmpty() ? "" : "\n" + user;
    }

    private static void chatOnce(Context ctx, String model, String sys,
                                 List<String[]> msgs, boolean vision, LlmClient.Callback cb) {
        if (vision) LlmClient.chatVision(SettingsStore.url(ctx), SettingsStore.key(ctx), model, sys, msgs, null, cb);
        else LlmClient.chat(SettingsStore.url(ctx), SettingsStore.key(ctx), model, sys, msgs, cb);
    }

    private static void step(Context ctx, List<String[]> msgs, int depth,
                             String userText, String prevTool, Cb cb) {
        chatFb(ctx, msgs, false, (res, error) -> {
            if (error != null) { cb.onDone(null, error); return; }
            try { CostTracker.addLast(ctx, SettingsStore.model(ctx)); } catch (Throwable ignored) {}
            if (depth >= MAX_LOOPS) {
                cb.onDone(res.replaceAll(TOOL.pattern(), "").trim(), null);
                return;
            }
            Matcher m = TOOL.matcher(res);
            if (!m.find()) { cb.onDone(res, null); return; }
            String toolJson = m.group(1);
            if (toolJson.equals(prevTool)) {
                cb.onDone(res.replaceAll(TOOL.pattern(), "").trim(), null);
                return;
            }
            new Thread(() -> {
                String result = runTool(ctx, toolJson, userText);
                List<String[]> next = new ArrayList<>(msgs);
                next.add(new String[]{"assistant", res});
                next.add(new String[]{"user",
                        "@@RESULT " + (result.length() > 6000
                                ? result.substring(0, 6000) : result) + "@@"});
                step(ctx, next, depth + 1, userText, toolJson, cb);
            }).start();
        });
    }

    /** executes one @@TOOL@@ payload and returns a plain-text result */
    private static String runTool(Context ctx, String toolJson, String userText) {
        try {
            JSONObject tool = new JSONObject(toolJson);
            String action = tool.optString("action", "");
            JSONObject args = tool.optJSONObject("args");
            if (args == null) args = new JSONObject();

            if ("screenshot".equals(action)) return doScreenshot(ctx, userText);
            if ("web_search".equals(action) || "open_url".equals(action)) {
                return "web_search".equals(action)
                        ? WebTools.search(args.optString("query", ""))
                        : WebTools.openUrl(args.optString("url", ""));
            }
            if ("notifications".equals(action)) return NotificationReaderService.describe();
            if ("macro".equals(action)) return Macros.run(ctx, args.optString("name", ""));
            if ("remember".equals(action)) return Memory.handle(ctx, args);
            return DeviceTools.execute(ctx, action, args);
        } catch (Exception e) {
            return "Ошибка выполнения команды: " + e;
        }
    }

    private static String doScreenshot(Context ctx, String question) {
        String img = ScreenGrab.capture(ctx);
        if (img == null) {
            return ScreenGrab.denied
                    ? "Пользователь не дал разрешение на захват экрана. Попроси его дать разрешение и повторить."
                    : "Захват экрана не удался.";
        }
        final String[] res = {"Не удалось описать экран."};
        final CountDownLatch latch = new CountDownLatch(1);
        List<String[]> msgs = new ArrayList<>();
        msgs.add(new String[]{"user", "Что изображено на этом скриншоте телефона? Вопрос пользователя: "
                + (question == null ? "" : question) + ". Ответь кратко, 1-3 предложения."});
        String primary = SettingsStore.visionModel(ctx);
        // vision-модель не выбрана — пробуем обычную, иначе провайдер ответит 400
        if (primary.trim().isEmpty()) primary = SettingsStore.model(ctx);
        if (primary.trim().isEmpty()) return "Модель для распознавания экрана не выбрана.";
        final String visionPrimary = primary;
        String fb = SettingsStore.visionFallback(ctx);
        LlmClient.chatVision(SettingsStore.url(ctx), SettingsStore.key(ctx), visionPrimary,
                hiddenPromptIntroForVision(), msgs, img, (text, error) -> {
                    if (error != null && fb != null && !fb.trim().isEmpty()
                            && !fb.equals(visionPrimary)) {
                        LlmClient.chatVision(SettingsStore.url(ctx), SettingsStore.key(ctx), fb,
                                hiddenPromptIntroForVision(), msgs, img, (t2, e2) -> {
                                    res[0] = e2 != null ? "Ошибка vision-моделей: " + error + " / " + e2 : t2;
                                    latch.countDown();
                                });
                    } else {
                        res[0] = error != null ? "Ошибка vision-модели: " + error : text;
                        latch.countDown();
                    }
                });
        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return res[0];
    }
}
