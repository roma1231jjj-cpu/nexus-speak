package com.local.aivoice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Assistant overlay: listens -> asks the LLM -> speaks -> listens again.
 *
 * Everything is non-blocking, listening can be paused with the mic button,
 * and the loop pauses by itself after two empty recognition attempts so the
 * mic is never captured forever.
 */
public class AssistantActivity extends AppCompatActivity {

    /** true while the assistant is answering — used to mute announcements */
    private static volatile boolean busyNow = false;

    public static boolean isBusy() { return busyNow; }

    private TextView status, hint;
    private LinearLayout transcriptBox;
    private Button micBtn;

    private GlobeView globe;
    private View sheet;

    private SpeechRecognizer recognizer;

    private TtsSpeaker tts;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean loopActive = false;
    private boolean paused = false;
    private boolean busy = false;      // LLM request / TTS in progress
    private int googleFailStreak = 0;
    private int emptyTurns = 0;
    private long startedListeningAt = 0;
    private int noSpeechRetries = 0;

    private long windowCloseAt = 0;
    private volatile boolean spokeSinceAnswer = false;

    private ObjectAnimator dotsAnim;
    private int thinkingPhase = 0;
    /** ждём колбэк окончания озвучки ответа */
    private boolean answerSpeaking = false;
    private Runnable ttsGuard;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this, true);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (SettingsStore.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_assistant);
        NxBlobs.apply(this);
        L.attach(this);
        Ui.applyAmoled(this);

        status = findViewById(R.id.status);
        transcriptBox = findViewById(R.id.transcriptBox);
        hint = findViewById(R.id.hint);
        micBtn = findViewById(R.id.micBtn);
        globe = findViewById(R.id.globe);
        sheet = findViewById(R.id.sheet);

        sheet.setAlpha(0f);
        sheet.setScaleX(0.95f);
        sheet.setScaleY(0.95f);
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new OvershootInterpolator(0.8f))
                .start();

        main.postDelayed(this::initTts, 120);
        micBtn.setOnClickListener(v -> {
            toggleListening();
        });
        try {
            micBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0);
            micBtn.setCompoundDrawablePadding(dp(8));
        } catch (Throwable ignored) {
        }
        findViewById(R.id.stopBtn).setOnClickListener(v -> finishAssistant());
        findViewById(R.id.chatBtn).setOnClickListener(v -> {
            // из голосовой панели (в т.ч. по долгому нажатию кнопки питания)
            // можно уйти в обычный текстовый чат того же диалога
            openChat();
        });
        findViewById(R.id.newDialogBtn).setOnClickListener(v -> newDialog());
        findViewById(R.id.settingsBtn).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            Ui.fadeIn(this);
        });

        try {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } catch (Throwable ignored) {
        }
        fitSheet();

        String coldPre = getIntent() != null
                ? getIntent().getStringExtra(WakeService.EXTRA_TEXT) : null;
        if (coldPre != null && !coldPre.trim().isEmpty()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            // wake word already carried the request — answer it, no mic needed
            loopActive = true;
            pauseWakeService();
            setStatus(L.s("Думаю…"), true);
            main.postDelayed(() -> handleRecognized(coldPre.trim()), 250);
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            boolean asked = getSharedPreferences("board", MODE_PRIVATE)
                    .getBoolean("micAsked", false);
            getSharedPreferences("board", MODE_PRIVATE).edit().putBoolean("micAsked", true).apply();
            if (!asked) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                setStatus(L.s("Нужно разрешение на микрофон"), false);
            } else {
                showMicDenied();
            }
        } else {
            startListeningLoop();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String pre = intent != null ? intent.getStringExtra(WakeService.EXTRA_TEXT) : null;
        if (pre != null && !pre.trim().isEmpty()) {
            stopRecognizer();
            paused = false;
            loopActive = true;
            updateMicButton();
            handleRecognized(pre.trim());
            return;
        }
        if (!loopActive
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListeningLoop();
        }
    }

    /**
     * Озвучку поднимаем уже после старта прослушивания: раньше биндинг
     * синтезатора на главном потоке задерживал старт микрофона на 2–3 секунды.
     */
    private void initTts() {
        tts = new TtsSpeaker(this, SettingsStore.ttsEngine(this),
                SettingsStore.voiceVolume(this), SettingsStore.ttsRate(this),
                SettingsStore.ttsPitch(this), SettingsStore.ttsVoice(this), this::onTtsDone);
        tts.setStateListener(speaking -> {
            // не перебиваем статус, когда ответ уже закончился (движок без голоса
            // присылает событие с задержкой и вешал «Говорю…» навсегда)
            if (!speaking || !busy) return;
            setStatus(L.s("Говорю…"), false);
            globe.setPulseActive(false);
        });
        // если голоса в системе нет — честно говорим об этом, а не молчим
        main.postDelayed(() -> {
            String problem = tts.problem();
            if (problem == null) return;
            hint.setVisibility(View.VISIBLE);
            hint.setText("no_ru".equals(problem)
                    ? L.s("В телефоне нет русского голоса. Нажми сюда — открою настройки синтеза речи")
                    : L.s("В телефоне нет озвучки. Нажми — предложу установить «Речевые службы Google»"));
            hint.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS"));
                } catch (Throwable t) {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_SETTINGS));
                }
            });
        }, 4000);

    }

    private void say(String text) {
        if (tts != null) tts.speak(text);
    }

    private void sayAppend(String text) {
        if (tts != null) tts.speakAppend(text);
    }

    private void stopSay() {
        if (tts != null) tts.stop();
    }

    // ---------------------------------------------------------------- status

    private void setStatus(String s, boolean animate) {
        runOnUiThread(() -> {
            // без withEndAction: колбэк анимации приходил позже и затирал
            // свежий текст распознавания обратно на «Слушаю…»
            status.animate().cancel();
            status.setText(s);
            status.setAlpha(1f);
            if (!animate) return;
            status.setAlpha(0.35f);
            status.animate().alpha(1f).setDuration(150).start();
        });
    }

    private void setHint(String s) {
        runOnUiThread(() -> {
            if (s == null || s.isEmpty()) {
                if (hint.getVisibility() != View.GONE) {
                    hint.animate().alpha(0f).setDuration(120)
                            .withEndAction(() -> hint.setVisibility(View.GONE)).start();
                }
                return;
            }
            hint.animate().cancel();
            hint.setVisibility(View.VISIBLE);
            hint.animate().alpha(1f).setDuration(160).start();
            hint.setText(s);
        });
    }

    /** каждое сообщение — отдельным пузырём: мои справа, Нексус слева */
    /** панель снизу как у Алисы: компактная, но с прокруткой истории */
    private void fitSheet() {
        try {
            int h = getResources().getDisplayMetrics().heightPixels;
            View sheet = findViewById(R.id.sheet);
            ScrollView scroll = findViewById(R.id.scroll);
            if (scroll != null) {
                scroll.setVisibility(View.VISIBLE);
                growTranscript(scroll);
            }
            if (sheet != null) {
                sheet.setAlpha(0f);
                sheet.setTranslationY(80f);
                sheet.animate().alpha(1f).translationY(0f).setDuration(220).start();
            }
        } catch (Throwable ignored) {
        }
    }

    /** история растёт вверх, но не выше трети экрана — панель остаётся «чатиком» */
    private void growTranscript(ScrollView sc) {
        try {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
            int want = transcriptBox.getHeight() + dp(4);
            ViewGroup.LayoutParams lp = sc.getLayoutParams();
            lp.height = Math.min(max, Math.max(dp(40), want));
            sc.setLayoutParams(lp);
            if (sc.getVisibility() != View.VISIBLE) sc.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private void appendTranscript(String who, String text) {
        runOnUiThread(() -> {
            if (transcriptBox == null) return;
            String w = who == null ? "" : who.trim();
            boolean mine = w.startsWith(L.s("Вы"));
            boolean hint = w.startsWith("—") || w.isEmpty();
            boolean error = w.startsWith(L.s("Ошибка"));

            TextView tv = new TextView(this);
            tv.setText(text);
            L.keep(tv);
            tv.setTextSize(15.5f);
            tv.setLineSpacing(dp(3), 1f);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);

            if (hint) {
                tv.setTextSize(12f);
                tv.setTextColor(Ui.color(this, R.attr.nxTextDim));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                tv.setGravity(Gravity.CENTER);
            } else {
                tv.setTextColor(Ui.color(this,
                        mine ? R.attr.nxOnPrimary : R.attr.nxTextMain));
                tv.setBackgroundResource(mine ? R.drawable.bg_user_bubble : R.drawable.bg_ai_bubble);
                int hp = dp(14), vp = dp(10);
                tv.setPadding(hp, vp, hp, vp);
                if (mine) {
                    lp.gravity = Gravity.END;
                    lp.leftMargin = dp(48);
                } else {
                    lp.gravity = Gravity.START;
                    lp.rightMargin = dp(36);
                }
                if (error) tv.setTextColor(Ui.color(this, R.attr.nxDanger));
                tv.setOnLongClickListener(v -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(L.s("Нексус"), tv.getText()));
                        Toast.makeText(this, L.s("Скопировано"), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }
            transcriptBox.addView(tv, lp);

            while (transcriptBox.getChildCount() > 40) transcriptBox.removeViewAt(0);

            tv.setAlpha(0f);
            tv.setTranslationY(dp(8));
            tv.animate().alpha(1f).translationY(0f).setDuration(180).start();

            ScrollView sc = findViewById(R.id.scroll);
            if (sc != null) {
                // панель растёт вместе с историей (до 34% экрана) и держит низ в поле зрения
                sc.post(() -> {
                    growTranscript(sc);
                    sc.fullScroll(View.FOCUS_DOWN);
                });
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void startThinkingDots() {
        stopThinkingDots();
        thinkingPhase = 0;
        dotsAnim = ObjectAnimator.ofFloat(globe, "alpha", 1f, 0.55f);
        dotsAnim.setDuration(600);
        dotsAnim.setRepeatMode(ObjectAnimator.REVERSE);
        dotsAnim.setRepeatCount(ObjectAnimator.INFINITE);
        dotsAnim.start();
    }

    private void stopThinkingDots() {
        if (dotsAnim != null) {
            dotsAnim.cancel();
            dotsAnim = null;
            globe.setAlpha(1f);
        }
    }

    private void updateMicButton() {
        runOnUiThread(() -> {
            boolean listening = loopActive && !paused;
            micBtn.setText(listening ? L.s("Пауза") : L.s("Слушать"));
            globe.setPulseActive(listening);
            globe.setSpinSpeed(listening ? 0.85f : 0.35f);
        });
    }

    // ------------------------------------------------------------------ loop

    private void startListeningLoop() {
        loopActive = true;
        paused = false;
        busy = false;
        emptyTurns = 0;
        starting = false;
        Feedback.blip(this);
        updateMicButton();
        // микрофон забираем только когда окно реально на экране:
        // иначе невидимая панель глушит wake-службу, и «нексус» перестаёт работать
        if (hasWindowFocus()) releaseMicForAssistant();
    }

    /**
     * Забираем микрофон у прослушки по-настоящему: останавливаем сервис,
     * ждём, пока он умрёт, и только потом стартуем своё распознавание.
     */
    private void releaseMicForAssistant() {
        if (WakeService.isRunningNow(this)) {
            WakeService.halt(this);
            waitMicFree(0);
        } else {
            if (loopActive && !paused) listenOnce();
        }
    }

    private void waitMicFree(int attempt) {
        if (!loopActive || paused) return;
        if (!WakeService.isRunningNow(this) || attempt >= 10) {
            // Google-распознаватель отпускает микрофон не мгновенно,
            // но 60 мс вместо 120 экономит на каждом запуске
            main.postDelayed(() -> { if (loopActive && !paused && hasWindowFocus()) listenOnce(); },
                    60);
            return;
        }
        main.postDelayed(() -> waitMicFree(attempt + 1), 60);
    }

    /** Не создаём сервис ради паузы: иначе он «живёт» без слушателя. */
    private void pauseWakeService() {
        if (!WakeService.isRunningNow(this)) return;
        try {
            startService(new Intent(this, WakeService.class).setAction(WakeService.ACTION_PAUSE));
        } catch (Throwable t) {
            sendBroadcast(new Intent(WakeService.ACTION_PAUSE).setPackage(getPackageName()));
        }
    }

    /** Возвращаем прослушку: если сервис умер — поднимаем заново. */
    private void resumeWakeService() {
        if (!SettingsStore.wakeEnabled(this)) return;
        if (!WakeService.isRunningNow(this)) {
            WakeService.start(this);
            return;
        }
        try {
            startService(new Intent(this, WakeService.class).setAction(WakeService.ACTION_RESUME));
        } catch (Throwable t) {
            sendBroadcast(new Intent(WakeService.ACTION_RESUME).setPackage(getPackageName()));
        }
    }

    private void toggleListening() {
        if (busy) {
            // barge-in: interrupt speech and listen right away
            stopSay();
            busy = false;
            stopThinkingDots();
            paused = false;
            listenOnce();
            return;
        }
        paused = !paused;
        if (paused) {
            stopRecognizer();
    
            setStatus(L.s("Пауза"), true);
            setHint(L.s("Нажми «Слушать», скажи «Нексус» или закрой окно"));
            Feedback.tick(this);
        } else {
            emptyTurns = 0;
            starting = false;
            setHint(null);
            listenOnce();
            Feedback.blip(this);
        }
        updateMicButton();
    }

    /** ответ уже прозвучал: следующая тишина закрывает панель */
    private volatile boolean afterAnswer = false;
    private volatile boolean starting = false;
    private int busyRetries = 0;
    private Runnable restartSoon;
    private volatile long lastSignalAt = 0;
    private volatile long stoppedAt = 0;
    private volatile int lastError = 0;
    private Runnable stallWatch;

    private void listenOnce() {
        if (!loopActive || paused || busy || isFinishing() || isDestroyed()) return;
        if (starting) {
            // «старт» без ответа движка (нет службы распознавания, занятый микрофон)
            // раньше запирал панель навсегда — теперь через 6 секунд пробуем снова
            long waited = android.os.SystemClock.uptimeMillis() - startedListeningAt;
            if (waited < 6000) return;
            starting = false;
        }
        // Google отпускает микрофон не мгновенно: сразу после destroy
        // новый startListening получает ERROR_AUDIO и «не слышит» человека
        long wait = 320 - (android.os.SystemClock.uptimeMillis() - stoppedAt);
        if (wait > 0) {
            main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); }, wait);
            return;
        }
        // служба могла успеть подняться между переходами — забираем микрофон у неё,
        // иначе получаем ERROR_RECOGNIZER_BUSY и «микрофон занят»
        if (WakeService.isRunningNow(this)) {
            releaseMicForAssistant();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            starting = false;
            paused = true;
            setStatus(L.s("На телефоне нет распознавания речи"), true);
            setHint(L.s("Установи «Речевые службы Google» — без них Нэксус не слышит"));
            updateMicButton();
            return;
        }
        if (recognizer == null) {
            try {
                // каждый раз новый распознаватель: переиспользованный после
                // ERROR_CLIENT/ERROR_AUDIO остаётся «глухим» до пересоздания
                recognizer = Warm.take(this);
                recognizer.setRecognitionListener(listener);
            } catch (Throwable t) {
                recognizer = null;
                setStatus(L.s("Ошибка распознавателя"), true);
                main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); }, 1500);
                return;
            }
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // язык задаём явно: если системный язык английский, Google слушал английский
        // и русская речь «не фиксировалась» вообще
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, L.isEn() ? "en-US" : "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // никаких EXTRA_SPEECH_INPUT_* — именно они заставляли распознаватель
        // обрывать сессию через полсекунды тишины и «не слышать» речь
        try {
            starting = true;
            startedListeningAt = android.os.SystemClock.uptimeMillis();
            lastSignalAt = startedListeningAt;
            // если другое приложение оставило режим «разговор», микрофон уходит
            // в трубку/гарнитуру и распознаватель не слышит человека
            BeepMuter.ensureNormalMode(this);
            setStatus(L.s("Слушаю…"), true);
            setHint(L.s("Говори — я слушаю. «Стоп» / «Пауза» — остановить"));
            // «дзынь» старта: глушим только на пару секунд, иначе вместе с системным
            // потоком глохнет звонок (они в одной группе громкости)
            BeepMuter.muteFor(this, 2500);
            recognizer.startListening(intent);
            // сторож реагирует только на полное молчание движка, а не на длинную речь
            restartSoon = () -> {
                if (!loopActive || paused || busy || starting || recognizer == null) return;
                long idle = android.os.SystemClock.uptimeMillis() - lastSignalAt;
                if (idle < 12000) {
                    main.postDelayed(restartSoon, 4000);
                    return;
                }
                stopRecognizer();
                listenOnce();
            };
            main.removeCallbacks(stallWatch);
            stallWatch = restartSoon;
            main.postDelayed(restartSoon, 12000);
        } catch (Throwable t) {
            starting = false;
            stopRecognizer();
            main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); }, 1200);
        }
        updateMicButton();
    }

    private void emptyTurn() {
        emptyTurns++;
        // после озвученного ответа не держим окно: одно пустое распознавание — и
        // закрываемся, иначе панель висела ещё десяток секунд «просто так»
        if (afterAnswer && emptyTurns >= 2 && !busy) {
            setStatus(L.s("Готово"), true);
            paused = true;
            stopRecognizer();
            updateMicButton();
            main.postDelayed(this::finishAssistant, 250);
            return;
        }
        if (emptyTurns >= 3) {
            paused = true;
            stopRecognizer();
            setStatus(L.s("Пауза — тишина"), true);
            setHint(L.s("Микрофон свободен. Нажми «Слушать», чтобы продолжить"));
            updateMicButton();
            return;
        }
        stopRecognizer();
        main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); }, 500);
    }



    // ------------------------------------------------------------ google cb

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            starting = false;
            busyRetries = 0;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            setStatus(L.s("Слушаю…"), false);
        }
        @Override public void onBeginningOfSpeech() {
            starting = false;
            spokeSinceAnswer = true;
            googleFailStreak = 0;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            setStatus(L.s("Говори…"), false);
        }
        @Override public void onRmsChanged(float rmsdB) {
            lastSignalAt = android.os.SystemClock.uptimeMillis();
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            BeepMuter.muteFor(AssistantActivity.this, 1500, false);
            starting = false;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            setStatus(L.s("Обрабатываю…"), false);
        }
        @Override public void onEvent(int eventType, Bundle params) { }
        @Override public void onPartialResults(Bundle partialResults) {
            starting = false;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            ArrayList<String> list = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) setStatus("… " + list.get(0), false);
        }
        @Override public void onError(int error) {
            BeepMuter.unmute(AssistantActivity.this);
            lastError = error;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            if (!loopActive || paused) return;
            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_NETWORK
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                googleFailStreak++;
            }
            starting = false;
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                long since = android.os.SystemClock.uptimeMillis() - startedListeningAt;
                noSpeechRetries++;
                if (noSpeechRetries <= 3) {
                    // тишина — просто слушаем снова, без текста на экране
                    stopRecognizer();
                    setStatus(L.s("Слушаю…"), false);
                    main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); },
                            250L + 150L * noSpeechRetries);
                    return;
                }
                noSpeechRetries = 0;
                emptyTurn();
            } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_CLIENT) {
                stopRecognizer();
                busyRetries++;
                if (busyRetries <= 4) {
                    // микрофон ещё не отдан — ждём молча, без надписей про «освобождение»
                    if (busyRetries >= 3) setStatus(L.s("Слушаю…"), false);
                    main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); },
                            500L * busyRetries);
                    return;
                }
                busyRetries = 0;
                paused = true;
                if (!SpeechRecognizer.isRecognitionAvailable(AssistantActivity.this)) {
                    setStatus(L.s("Распознавание речи недоступно"), true);
                    setHint(L.s("Поставь «Речевые службы Google» — без них Нэксус не слышит"));
                } else {
                    setStatus(L.s("Микрофон занят другим приложением"), true);
                    setHint(L.s("Закрой приложение, которое слушает микрофон, затем нажми «Слушать»"));
                }
                updateMicButton();
            } else {
                noSpeechRetries = 0;
                stopRecognizer();
                if (googleFailStreak >= 4) {
                    // движок не отдаёт микрофон — не крутим перезапуски до бесконечности
                    googleFailStreak = 0;
                    paused = true;
                    setStatus(L.s("Микрофон недоступен (код ") + error + ")", true);
                    setHint(L.s("Закрой приложение, которое держит микрофон, и нажми «Слушать»"));
                    updateMicButton();
                    return;
                }
                main.postDelayed(() -> { if (loopActive && !paused) listenOnce(); }, 700);
            }
        }
        @Override public void onResults(Bundle results) {
            BeepMuter.unmute(AssistantActivity.this);
            starting = false;
            googleFailStreak = 0;
            noSpeechRetries = 0;
            lastSignalAt = android.os.SystemClock.uptimeMillis();
            // сессия закончилась — движок уничтожаем, следующая фраза пойдёт
            // через свежий экземпляр, а не через «уставший»
            stopRecognizer();
            if (!loopActive || paused) return;
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
            if (text.trim().isEmpty()) { emptyTurn(); return; }
            emptyTurns = 0;
            handleRecognized(text.trim());
        }
    };

    // ------------------------------------------------------------- handling

    private void handleRecognized(String text) {
        String low = text.toLowerCase(new java.util.Locale("ru", "RU"));
        // «нексус ...» пришло из wake-службы — убираем обращение
        final String wake = "(?i)^(нэксус|нексус|нэкси|некси|нэкс|некс|nexus)[,.! ]*";
        low = low.replaceFirst(wake, "");
        text = text.replaceFirst(wake, "");
        if (text.trim().isEmpty()) { listenOnce(); return; }

        if (low.contains("заново") || low.contains("новый диалог") || low.contains("очисти историю")
                || low.contains("start over") || low.contains("new chat") || low.contains("clear history")) {
            appendTranscript(L.s("Вы: "), text);
            ConversationStore.clear(this);
            appendTranscript(L.s("Нексус: "), L.s("Начинаю с чистого листа."));
            say(L.s("Начинаю с чистого листа"));
            return;
        }
        if (low.equals("пауза") || low.equals("подожди") || low.equals("помолчи")
                || low.equals("pause") || low.equals("wait") || low.equals("be quiet")) {
            appendTranscript(L.s("Вы: "), text);
            paused = true;
            stopRecognizer();
            setStatus(L.s("Пауза"), true);
            setHint(L.s("Нажми «Слушать», чтобы продолжить"));
            updateMicButton();
            return;
        }
        if (low.equals("стоп") || low.equals("отмена") || low.equals("закройся")
                || low.equals("хватит") || low.equals("до свидания")) {
            appendTranscript(L.s("Вы: "), text);
            say(L.s("Закрываюсь"));
            loopActive = false;
            stopRecognizer();
            main.postDelayed(this::finishAssistant, 1400);
            return;
        }

        afterAnswer = false;
        final String userText = text.trim();
        appendTranscript(L.s("Вы: "), userText);
        busy = true;
        busyNow = true;
        setStatus(L.s("Думаю…"), true);
        setHint(null);
        globe.setPulseActive(false);
        startThinkingDots();
        updateMicButton();

        final StringBuilder streamed = new StringBuilder();
        final int[] spokenUpTo = {0};
        final boolean[] spokeAnything = {false};
        final boolean[] aborted = {false};

        AssistantEngine.processStream(this, userText, ConversationStore.get(this),
                new AssistantEngine.StreamCb() {
            @Override public void onDelta(String delta) {
                if (!loopActive || aborted[0]) return;
                streamed.append(delta);
                // начинаем говорить, как только набралась первая фраза
                int cut = lastSentenceEnd(streamed, spokenUpTo[0]);
                // первую фразу отдаём в озвучку сразу, следующие — куском покрупнее:
                // на каждый кусок уходит отдельный запрос, и короткие фразы звучат
                // с паузами «долгая озвучка»
                if (spokeAnything[0] && cut - spokenUpTo[0] < 60) cut = 0;
                if (cut > spokenUpTo[0]) {
                    String piece = cleanForSpeech(streamed.substring(spokenUpTo[0], cut));
                    spokenUpTo[0] = cut;
                    if (!piece.isEmpty()) {
                        if (!spokeAnything[0]) {
                            stopThinkingDots();
                            setStatus(L.s("Говорю…"), true);
                            answerSpeaking = true;
                            armTtsGuard(4000 + piece.length() * 90);
                            say(piece);
                            spokeAnything[0] = true;
                        } else {
                            sayAppend(piece);
                        }
                    }
                }
            }

            @Override public void onAbort() {
                aborted[0] = true;
                streamed.setLength(0);
                spokenUpTo[0] = 0;
                if (spokeAnything[0]) stopSay();
                spokeAnything[0] = false;
                setStatus(L.s("Выполняю команду…"), true);
            }

            @Override public void onDone(String finalText, String error) {
                busy = false;
                busyNow = false;
                stopThinkingDots();
                if (!loopActive) return;
                if (error != null || finalText == null || finalText.trim().isEmpty()) {
                    String msg = error == null ? L.s("Пустой ответ модели") : error;
                    appendTranscript(L.s("Ошибка: "), msg);
                    setStatus(L.s("Не получилось"), true);
                    setHint(L.s("Проверь API-ключ и интернет в настройках"));
                    answerSpeaking = true;
                    armTtsGuard(6000);
                    say(L.s("Не получилось получить ответ. Проверь настройки."));
                    updateMicButton();
                    return;
                }
                ConversationStore.add(AssistantActivity.this, "user", userText);
                ConversationStore.add(AssistantActivity.this, "assistant", finalText);
                appendTranscript(L.s("Нексус: "), finalText);
                setStatus(L.s("Говорю…"), true);
                setHint(null);
                if (!spokeAnything[0]) {
                    answerSpeaking = true;
                    armTtsGuard(4000 + finalText.length() * 90);
                    say(finalText);
                } else {
                    // договариваем хвост, который не успел озвучиться
                    String tail = cleanForSpeech(
                            streamed.substring(Math.min(spokenUpTo[0], streamed.length())));
                    String cleanTail = tail.replaceAll("\\s+", " ").trim();
                    String cleanFull = cleanForSpeech(finalText).replaceAll("\\s+", " ").trim();
                    if (!cleanFull.isEmpty() && cleanFull.startsWith(streamed.toString()
                            .replaceAll("\\s+", " ").trim())) {
                        if (!cleanTail.isEmpty() && cleanFull.endsWith(cleanTail)) {
                            String rest = cleanFull.substring(0,
                                    cleanFull.length() - cleanTail.length()).trim();
                            String spoken = cleanForSpeech(streamed.toString())
                                    .replaceAll("\\s+", " ").trim();
                            if (rest.length() > spoken.length()) {
                                sayAppend(rest.substring(spoken.length()).trim());
                            }
                        }
                    }
                }
                updateMicButton();
                if (spokeAnything[0] && tts != null && !tts.isSpeaking()) {
                    main.postDelayed(AssistantActivity.this::onTtsDone, 450);
                }
            }
        });
    }

    /** index right after the last sentence terminator, or -1 */
    private int lastSentenceEnd(CharSequence text, int from) {
        int cut = -1;
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == ';'
                    || ch == '\n') {
                if (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1))) {
                    cut = i + 1;
                }
            }
        }
        return cut;
    }

    private String cleanForSpeech(String s) {
        if (s == null) return "";
        String out = s.replace("@@", " ").replace("*", "").replace("#", "")
                .replace("`", "").replace("_", " ");
        return out.trim();
    }

    private void newDialog() {
        // раньше здесь стиралась история текущего диалога, а в чате создавался
        // новый — теперь оба режима начинают новый диалог одинаково
        SessionStore.newSession(this);
        if (tts != null) tts.stop();
        if (transcriptBox != null) transcriptBox.removeAllViews();
        appendTranscript("— ", L.s("Новый диалог"));
        setStatus(L.s("Новый диалог"), true);
    }

    /** страховка: если движок озвучки не позвал onDone, панель не должна висеть вечно */
    private void armTtsGuard(int approxMs) {
        final int[] checks = {0};
        main.removeCallbacks(ttsGuard == null ? () -> { } : ttsGuard);
        ttsGuard = () -> {
            if (!loopActive || !answerSpeaking) return;
            if (tts != null && tts.isSpeaking() && checks[0] < 4) {
                checks[0]++;
                main.postDelayed(ttsGuard, 2000);
                return;
            }
            answerSpeaking = false;
            onTtsDone();
        };
        main.postDelayed(ttsGuard, Math.max(4000, approxMs));
    }

    private void onTtsDone() {
        if (!loopActive) return;
        main.removeCallbacks(ttsGuard == null ? () -> { } : ttsGuard);
        answerSpeaking = false;
        busy = false;
        busyNow = false;
        if (paused) {
            // ответ пришёл, когда микрофон уже не наш: раньше так и висело «Говорю…»
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                // слушать всё равно нечем — не держим окно, закрываемся
                setStatus(L.s("Готово"), true);
                main.postDelayed(this::finishAssistant, 1200);
                return;
            }
            setStatus(L.s("Готово — нажми «Слушать»"), true);
            setHint(L.s("Нажми «Слушать», чтобы продолжить"));
            updateMicButton();
            return;
        }
        afterAnswer = true;
        int window = SettingsStore.talkWindow(this);
        if (window <= 0) {
            // «закрывать сразу» — как только ответ озвучен
            setStatus(L.s("Готово"), true);
            main.postDelayed(this::finishAssistant, 400);
            return;
        }
        main.postDelayed(() -> { if (loopActive && !paused && !busy) listenOnce(); }, 350);
        // окно разговора: если за N секунд никто не заговорил — закрываемся
        windowCloseAt = 0;
        main.postDelayed(windowCheck, Math.max(5, window) * 1000L);
    }

    private final Runnable windowCheck = new Runnable() {
        @Override public void run() {
            if (!loopActive || busy) return;
            if (spokeSinceAnswer) {
                spokeSinceAnswer = false;
                // человек снова говорит — окно начинается заново
                main.postDelayed(windowCheck,
                        Math.max(5, SettingsStore.talkWindow(AssistantActivity.this)) * 1000L);
                return;
            }
            setStatus(L.s("Окно закрывается"), true);
            finishAssistant();
        }
    };

    private void openChat() {
        startActivity(new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        finishAssistant();
    }

    private void finishAssistant() {
        MicOwner.setPanelActive(false);
        loopActive = false;
        paused = false;
        busy = false;
        busyNow = false;
        stopThinkingDots();

        stopRecognizer();
        stopSay();
        ConversationStore.trim(this);
        resumeWakeService();
        finish();
    }

    private void stopRecognizer() {
        main.removeCallbacks(stallWatch);
        stallWatch = null;
        stoppedAt = android.os.SystemClock.uptimeMillis();
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        starting = false;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishAssistant();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @android.annotation.SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        // жестовая навигация не доходит до onKeyDown — закрываемся тем же путём
        finishAssistant();
    }

    private boolean pausedBySystem = false;

    @Override
    protected void onPause() {
        super.onPause();
        // окно ушло в фон — освобождаем микрофон, но не закрываемся
        if (isFinishing()) return;
        pausedBySystem = !paused;
        paused = true;
        stopRecognizer();

        updateMicButton();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // окно действительно скрылось: возвращаем микрофон wake-службе.
        // В onPause этого делать нельзя — на Honor панель уходит в паузу
        // от системных диалогов, и служба отбирала микрофон у живой панели.
        main.removeCallbacks(startWhenFocused);
        main.removeCallbacks(resumeWakeLater);
        MicOwner.setPanelActive(false);
        if (!isFinishing()) main.postDelayed(resumeWakeLater, 250);
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(resumeWakeLater);
        // микрофон считаем своим только после реального показа окна
        // (в onWindowFocusChanged): иначе невидимая панель глушит wake-слово
    }

    private final Runnable resumeWakeLater = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed() || !paused) return;
            resumeWakeService();
        }
    };

    /** запускаем слушание только когда окно реально получило фокус (то есть видно) */
    private final Runnable startWhenFocused = new Runnable() {
        @Override public void run() {
            if (!loopActive || busy || isFinishing() || isDestroyed()) return;
            if (!hasWindowFocus()) {
                main.postDelayed(this, 300);
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) return;
            pausedBySystem = false;
            paused = false;
            emptyTurns = 0;
            starting = false;
            MicOwner.setPanelActive(true);
            releaseMicForAssistant();
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // окно ушло с экрана — wake-слово должно снова работать сразу
            MicOwner.setPanelActive(false);
            return;
        }
        MicOwner.setPanelActive(true);
        // окно действительно на экране — можно забирать микрофон у службы
        main.removeCallbacks(resumeWakeLater);
        main.removeCallbacks(startWhenFocused);
        startWhenFocused.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(resumeWakeLater);
        if (!loopActive || busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        if (hasWindowFocus()) {
            main.removeCallbacks(startWhenFocused);
            startWhenFocused.run();
        } else {
            // активность могла быть создана в фоне: микрофон не трогаем, пока не покажется
            main.removeCallbacks(startWhenFocused);
            main.postDelayed(startWhenFocused, 60);
        }
    }

    @Override
    protected void onDestroy() {
        loopActive = false;
        busyNow = false;
        MicOwner.setPanelActive(false);
        BeepMuter.unmute(this);
        stopThinkingDots();

        main.removeCallbacksAndMessages(null);
        stopRecognizer();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListeningLoop();
        } else {
            showMicDenied();
        }
    }

    /** Отказ в доступе — не тупик: объясняем и даём кнопку в настройки приложения. */
    private void showMicDenied() {
        paused = true;
        loopActive = false;
        updateMicButton();
        setStatus(L.s("Нужно разрешение на микрофон"), false);
        hint.setVisibility(View.VISIBLE);
        hint.setText(L.s("Разреши микрофон в настройках приложения — иначе слушать не смогу"));
        hint.setOnClickListener(v -> openAppSettings());
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
