package com.local.aivoice;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Text chat mode: normal messenger-like dialog with streaming answers. */
public class ChatActivity extends AppCompatActivity {

    private LinearLayout chatList;
    private ScrollView scroll;
    private EditText input;
    private TextView title, state;
    private boolean sending = false;
    private TextView liveBubble;
    private View sheet, scrim;
    private androidx.appcompat.widget.SwitchCompat wakeSwitch;
    private TextView wakeState, wakeHint;
    private boolean sheetOpen = false;
    /** true, пока переключатель обновляем мы сами — иначе его слушатель гасит настройку */
    private boolean syncingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        NxBlobs.apply(this);
        L.attach(this);
        Ui.applyAmoled(this);

        chatList = findViewById(R.id.chatList);
        GlobeView backdropGlobe = findViewById(R.id.chatGlobe);
        if (backdropGlobe != null) {
            backdropGlobe.setBrandDots(true);
            backdropGlobe.setSpinSpeed(0.28f);
        }
        scroll = findViewById(R.id.chatScroll);
        input = findViewById(R.id.chatInput);
        title = findViewById(R.id.chatTitle);
        state = findViewById(R.id.chatState);

        sheet = findViewById(R.id.chatSheet);
        scrim = findViewById(R.id.chatSheetScrim);
        wakeSwitch = findViewById(R.id.sheetWakeSwitch);
        tintSwitch();
        wakeState = findViewById(R.id.sheetWakeState);
        wakeHint = findViewById(R.id.sheetWakeHint);
        sheet.setVisibility(View.GONE);

        findViewById(R.id.chatBackBtn).setOnClickListener(v -> openSheet());
        scrim.setOnClickListener(v -> closeSheet());
        findViewById(R.id.sheetTalkBtn).setOnClickListener(v -> {
            closeSheet();
            startActivity(AssistantActivity.internalIntent(this));
            Ui.fadeIn(this);
        });
        findViewById(R.id.chatNewBtn).setOnClickListener(v -> newDialog());
        findViewById(R.id.sheetSettingsBtn).setOnClickListener(v -> {
            closeSheet();
            startActivity(new Intent(this, SettingsActivity.class));
            Ui.fadeIn(this);
        });
        wakeSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingSwitch) return;
            setWake(checked);
        });
        renderWake();
        findViewById(R.id.chatSessionsBtn).setOnClickListener(v -> showSessions());
        findViewById(R.id.chatSettingsBtn).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.chatMicBtn).setOnClickListener(v -> {
            startActivity(AssistantActivity.internalIntent(this));
            Ui.fadeIn(this);
        });
        findViewById(R.id.chatSendBtn).setOnClickListener(v -> send());

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });

        renderHistory();
        restoreChatScroll(savedInstanceState);
    }

    private static final String KEY_SCROLL = "chatScrollY";

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        if (scroll != null) out.putInt(KEY_SCROLL, scroll.getScrollY());
    }

    /** после смены темы экран создаётся заново: возвращаем прежнюю прокрутку */
    private void restoreChatScroll(android.os.Bundle state) {
        if (state == null) return;
        final int y = state.getInt(KEY_SCROLL, 0);
        if (y <= 0) return;
        scroll.post(() -> {
            int max = Math.max(0, chatList.getHeight() - scroll.getHeight());
            scroll.scrollTo(0, Math.min(y, max));
        });
    }

    // ------------------------------------------------------------- шторка

    /** Белая дорожка на белой карточке не видна — красим в цвета темы. */
    private void tintSwitch() {
        if (wakeSwitch == null) return;
        try {
            int accent = Ui.color(this, R.attr.nxPrimary);
            int dim = Ui.color(this, R.attr.nxTextDim);
            int track = (dim & 0x00FFFFFF) | 0x33000000;
            wakeSwitch.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{accent, 0xFFF2F5FF}));
            wakeSwitch.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{(accent & 0x00FFFFFF) | 0x55000000, track}));
        } catch (Throwable ignored) {
        }
    }

    private void openSheet() {
        if (sheetOpen) return;
        sheetOpen = true;
        renderWake();
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        sheet.setVisibility(View.VISIBLE);
        sheet.setTranslationY(dp(70));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0f).alpha(1f).setDuration(240).start();
        scrim.animate().alpha(1f).setDuration(200).start();
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!sheetOpen) scrim.setVisibility(View.GONE);
        }).start();
        sheet.animate().translationY(dp(70)).alpha(0f).setDuration(200)
                .withEndAction(() -> { if (!sheetOpen) sheet.setVisibility(View.GONE); }).start();
    }

    private void setWake(boolean on) {
        SettingsStore.p(this).edit().putBoolean("wakeEnabled", on).apply();
        if (on) {
            WakeService.start(this);
        } else {
            WakeService.stop(this);
        }
        renderWake();
    }

    private void renderWake() {
        boolean stt = android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        boolean overlay = true;
        try { overlay = android.provider.Settings.canDrawOverlays(this); } catch (Throwable ignored) {}
        boolean pref = SettingsStore.wakeEnabled(this);
        boolean running = WakeService.isRunningNow(this);
        // службу мог прибить телефон: раньше переключатель в этом случае показывал
        // «выключено» и своим же слушателем стирал настройку — «слушать постоянно»
        // выключалось само. Теперь поднимаем службу, а настройку не трогаем.
        if (pref && !running && stt) {
            WakeService.start(this);
            running = WakeService.isRunningNow(this);
        }
        boolean on = pref;
        if (wakeSwitch != null && wakeSwitch.isChecked() != on) {
            syncingSwitch = true;
            wakeSwitch.setChecked(on);
            syncingSwitch = false;
        }
        if (wakeState == null) return;
        if (wakeSwitch != null) {
            wakeSwitch.setEnabled(stt);
            wakeSwitch.setAlpha(stt ? 1f : 0.5f);
        }
        if (!stt) {
            wakeState.setText(L.s("Нет системного распознавания речи"));
            wakeHint.setText(L.s("Поставь «Речевые службы Google» — без них слово не услышать"));
        } else if (on && running) {
            wakeState.setText(L.s("Слушаю «Нэксус»"));
            wakeHint.setText(overlay
                    ? L.s("работает и со свёрнутым приложением")
                    : L.s("разреши «Поверх других приложений», иначе шторка не всплывёт"));
        } else if (on) {
            wakeState.setText(L.s("Запускаю прослушку…"));
            wakeHint.setText(L.s("если не поднимется — открой настройки и нажми «Перезапустить»"));
        } else {
            wakeState.setText(L.s("Прослушка выключена"));
            wakeHint.setText(L.s("включи, чтобы слово работало в фоне"));
        }
    }

    private void newDialog() {
        // сбрасываем состояние отправки: иначе после «Нового диалога» поле ввода
        // остаётся заблокированным, пока не придёт ответ на прошлый вопрос
        sending = false;
        liveBubble = null;
        state.setText(L.s("онлайн"));
        SessionStore.newSession(this);
        renderHistory();
        input.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderWake();
        // голосовой режим пишет в тот же диалог — без перерисовки чат показывал
        // историю до разговора
        if (!sending) renderHistory();
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen) { closeSheet(); return; }
        super.onBackPressed();
    }

    private void renderHistory() {
        chatList.removeAllViews();
        SessionStore.Session s = SessionStore.current(this);
        String ttl = s == null ? "" : s.title;
        title.setText(isDefaultTitle(ttl) ? L.s("Чат") : ttl);
        if (s == null || s.messages.isEmpty()) {
            addHint(L.s("Напиши сообщение — Нексус ответит. Работают голосовые команды, ")
                    + L.s("поиск, будильники и макросы."));
            addChips();
            return;
        }
        for (String[] m : s.messages) {
            addBubble("user".equals(m[0]), m[1]);
        }
        scrollToBottom();
    }

    private void addHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(Ui.color(this, R.attr.nxTextDim));
        tv.setGravity(Gravity.CENTER);
        int pad = dp(18);
        tv.setPadding(pad, pad, pad, pad);
        chatList.addView(tv);
    }

    private void addChips() {
        String[] chips = {L.s("Какая погода?"), L.s("Курс доллара"), L.s("Поставь таймер на 5 минут"),
                L.s("Что нового в мире?")};
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        int top = dp(18);
        wrap.setPadding(0, top, 0, 0);

        LinearLayout row = null;
        for (int i = 0; i < chips.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(8);
                wrap.addView(row, rlp);
            }
            final String text = chips[i];
            TextView chip = new TextView(this);
            chip.setText(text);
            chip.setTextSize(13f);
            chip.setTextColor(Ui.color(this, R.attr.nxTextMain));
            chip.setBackgroundResource(R.drawable.btn_ghost);
            int hp = dp(14), vp = dp(9);
            chip.setPadding(hp, vp, hp, vp);
            chip.setOnClickListener(v -> {
                input.setText(text);
                send();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip, lp);
        }
        chatList.addView(wrap);
    }

    /** «Новый диалог»/«Диалог» — служебные заголовки, остальное пишет человек. */
    private static boolean isDefaultTitle(String t) {
        return t == null || t.isEmpty() || "Новый диалог".equals(t) || "Диалог".equals(t)
                || "New chat".equals(t) || "Chat".equals(t);
    }

    private TextView addBubble(boolean mine, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        L.keep(tv);
        tv.setTextSize(15.5f);
        tv.setTextColor(Ui.color(this, mine ? R.attr.nxOnPrimary : R.attr.nxTextMain));
        tv.setBackgroundResource(mine ? R.drawable.bg_user_bubble : R.drawable.bg_ai_bubble);
        int pad = dp(14);
        tv.setPadding(pad, dp(10), pad, dp(10));
        tv.setTextIsSelectable(true);
        tv.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(L.s("Нексус"), tv.getText()));
                Toast.makeText(this, L.s("Скопировано"), Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        // без внутреннего отступа строка шире экрана и правый угол пузыря срезался
        row.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        tv.setMaxWidth(max);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatList.addView(row, lp);
        scrollToBottom();
        return tv;
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void send() {
        if (sending) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");

        addBubble(true, text);
        SessionStore.add(this, "user", text);
        // запоминаем диалог: пользователь может переключить его, пока идёт ответ
        final SessionStore.Session startedIn = SessionStore.current(this);
        final String startedId = startedIn == null ? null : startedIn.id;
        if (isDefaultTitle(title.getText().toString())) {
            title.setText(text.length() > 42 ? text.substring(0, 42) + "…" : text);
        }

        sending = true;
        state.setText(L.s("печатает…"));
        final TextView aiBubble = addBubble(false, "…");
        L.keep(aiBubble);
        liveBubble = aiBubble;
        final StringBuilder acc = new StringBuilder();

        AssistantEngine.processStream(this, text, SessionStore.messages(this),
                new AssistantEngine.StreamCb() {
            @Override public void onDelta(String delta) {
                acc.append(delta);
                if (isFinishing() || isDestroyed()) return;
                aiBubble.setText(acc.toString());
                scrollToBottom();
            }

            @Override public void onAbort() {
                acc.setLength(0);
                if (isFinishing() || isDestroyed()) return;
                aiBubble.setText(L.s("Выполняю…"));
            }

            @Override public void onDone(String finalText, String error) {
                sending = false;
                state.setText(L.s("онлайн"));
                SessionStore.Session now = SessionStore.current(ChatActivity.this);
                boolean same = now != null && (startedId == null || startedId.equals(now.id));
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    // иначе текст ошибки падал в пузырь уже другого диалога
                    if (same) aiBubble.setText(L.s("Ошибка: ") + error);
                    return;
                }
                String out = finalText == null ? "" : finalText;
                // ответ всегда ложится в тот диалог, где был задан вопрос
                SessionStore.appendTo(ChatActivity.this, startedId, "assistant", out);
                if (same) {
                    aiBubble.setText(out);
                    scrollToBottom();
                }
            }
        });
    }

    // ------------------------------------------------------------- sessions

    private void showSessions() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Диалоги")));
        rows.add(ChoiceDialog.Row.item(L.s("＋  Новый диалог"), L.s("начать с чистого листа"), "@new"));
        String cur = SessionStore.current(this) == null ? "" : SessionStore.current(this).id;
        for (SessionStore.Session s : SessionStore.list(this)) {
            String sub = SessionStore.prettyTime(s.updatedAt)
                    + "  ·  " + s.messages.size() + L.s(" сообщ.");
            rows.add(s.id.equals(cur)
                    ? ChoiceDialog.Row.checked(s.title, sub, s.id)
                    : ChoiceDialog.Row.item(s.title, sub, s.id));
        }
        rows.add(ChoiceDialog.Row.header(L.s("Действия")));
        rows.add(ChoiceDialog.Row.item(L.s("🗑  Удалить диалог…"), null, "@del"));

        ChoiceDialog.show(this, L.s("История"), L.s("Поиск по диалогам…"), rows, null, null, row -> {
            if ("@new".equals(row.value)) {
                SessionStore.newSession(this);
                renderHistory();
            } else if ("@del".equals(row.value)) {
                pickDelete();
            } else {
                SessionStore.select(this, row.value);
                renderHistory();
            }
        });
    }

    private void pickDelete() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Удалить диалог")));
        for (SessionStore.Session s : SessionStore.list(this)) {
            rows.add(ChoiceDialog.Row.item(s.title, SessionStore.prettyTime(s.updatedAt), s.id));
        }
        ChoiceDialog.show(this, L.s("Удаление"), L.s("Поиск…"), rows, null, null, row -> {
            SessionStore.delete(this, row.value);
            Toast.makeText(this, L.s("Удалено"), Toast.LENGTH_SHORT).show();
            renderHistory();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
