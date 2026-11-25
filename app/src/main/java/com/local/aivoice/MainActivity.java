package com.local.aivoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Главный экран: только самое нужное — статус, «Голос», «Чат», настройки.
 * Всё остальное живёт в отдельном экране настроек.
 */
public class MainActivity extends AppCompatActivity {

    private TextView status, pill, stt;
    private boolean startTried = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashActivity.applyTheme(this);
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        NxBlobs.apply(this);
        L.attach(this);
        Ui.applyAmoled(this);

        status = findViewById(R.id.defaultStatus);
        pill = findViewById(R.id.wakePill);
        stt = findViewById(R.id.sttStatus);

        findViewById(R.id.voiceModeBtn).setOnClickListener(v -> {
            startActivity(AssistantActivity.internalIntent(this));
            Ui.fadeIn(this);
        });
        findViewById(R.id.chatModeBtn).setOnClickListener(v -> {
            startActivity(new Intent(this, ChatActivity.class));
            Ui.fadeIn(this);
        });
        findViewById(R.id.settingsBtn).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            Ui.fadeIn(this);
        });
        findViewById(R.id.setDefaultBtn).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, L.s("Не нашёл настройки помощника на этом телефоне"),
                        Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.newDialogBtn).setOnClickListener(v -> {
            ConversationStore.clear(this);
            Toast.makeText(this, L.s("Диалог очищен"), Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.settingsBtn).setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });

        pill.setOnClickListener(v -> {
            if (SettingsStore.wakeEnabled(this) && !overlayGranted()) {
                askOverlay();
                return;
            }
            toggleWake();
        });

        animate();
    }

    private void toggleWake() {
        boolean on = !SettingsStore.wakeEnabled(this);
        if (on && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, L.s("Сначала разреши микрофон — иначе я не услышу «нексус»"),
                    Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        SettingsStore.p(this).edit().putBoolean("wakeEnabled", on).apply();
        startTried = on;
        if (on) WakeService.start(this);
        else WakeService.stop(this);
        renderPill();
        if (on && !overlayGranted()) askOverlay();
    }

    private final Runnable pillTick = this::renderPill;

    private boolean overlayGranted() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return false;
        }
    }

    private void askOverlay() {
        Toast.makeText(this, L.s("Без этого разрешения Нэксус не сможет открыть панель ")
                + L.s("по слову «нэксус», когда приложение свёрнуто"), Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            catch (Throwable ignored) { }
        }
    }

    private void renderPill() {
        if (pill == null) return;
        pill.removeCallbacks(pillTick);
        boolean wanted = SettingsStore.wakeEnabled(this);
        boolean running = WakeService.isRunningNow(this);
        int green = Ui.color(this, R.attr.nxOk);
        int dim = Ui.color(this, R.attr.nxTextDim);
        String err = SettingsStore.p(this).getString("wakeErr", "");
        if (wanted && running && err != null && !err.isEmpty()) {
            pill.setText("● " + err + L.s(" — нажми, чтобы выключить"));
            pill.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (wanted && running) {
            pill.setText(L.s("● Слушаю «нэксус»"));
            pill.setTextColor(green);
        } else if (wanted && !overlayGranted()) {
            // без оверлея система не даст показать панель по wake-слову
            pill.setText(L.s("● Разреши «Поверх других приложений» — нажми"));
            pill.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (wanted) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                // без разрешения крутить «Запускаю…» бесконечно бессмысленно
                pill.setText(L.s("○ Нет разрешения на микрофон · нажми, чтобы исправить"));
                pill.setTextColor(Ui.color(this, R.attr.nxWarn));
                startTried = false;
                return;
            }
            if (!startTried) {
                startTried = true;
                WakeService.start(this);
            }
            pill.setText(L.s("● Запускаю…"));
            pill.setTextColor(green);
            pill.postDelayed(pillTick, 1500);
        } else {
            pill.setText(L.s("○ Выключено · нажми, чтобы включить"));
            pill.setTextColor(dim);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // в фоне нечего опрашивать состояние — цикл продолжится в onResume
        if (pill != null) pill.removeCallbacks(pillTick);
    }

    @Override
    protected void onDestroy() {
        if (pill != null) pill.removeCallbacks(pillTick);
        super.onDestroy();
    }

    /** честно показываем, чем именно Нэксус будет слышать */
    private void renderStt() {
        if (stt == null) return;
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean google = android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        if (!mic) {
            stt.setText(L.s("Нет доступа к микрофону — нажми, чтобы дать"));
            stt.setTextColor(Ui.color(this, R.attr.nxWarn));
            stt.setOnClickListener(v ->
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 8));
        } else if (google) {
            stt.setText(L.s("Распознавание: системное (Google)"));
            stt.setTextColor(Ui.color(this, R.attr.nxTextDim));
            stt.setOnClickListener(null);
        } else {
            stt.setText(L.s("Нет распознавания речи. Нажми — открою установку «Речевых служб Google»"));
            stt.setTextColor(Ui.color(this, R.attr.nxWarn));
            stt.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.tts")));
                } catch (Throwable t) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")));
                }
            });
        }
    }

    private boolean configured() {
        return !SettingsStore.url(this).trim().isEmpty()
                && !SettingsStore.key(this).trim().isEmpty()
                && !SettingsStore.model(this).trim().isEmpty();
    }

    private void renderStatus() {
        boolean isDefault = DefaultCheck.isDefaultAssistant(this);
        renderProvider();
        View def = findViewById(R.id.setDefaultBtn);
        if (def != null) def.setVisibility(isDefault ? View.GONE : View.VISIBLE);
        status.setOnClickListener(null);
        status.setText(isDefault
                ? L.s("Помощник по умолчанию — Nexus Speak")
                : L.s("Не назначен помощником: Настройки → Приложения → По умолчанию → Помощник"));
    }

    /** честно показываем, к какому провайдеру и модели подключён Нэксус */
    private void renderProvider() {
        TextView tv = findViewById(R.id.providerLine);
        if (tv == null) return;
        String url = SettingsStore.url(this).trim();
        String model = SettingsStore.model(this).trim();
        if (url.isEmpty()) {
            tv.setText(L.s("Провайдер не выбран — нажми сюда"));
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (SettingsStore.key(this).trim().isEmpty()) {
            tv.setText(L.s("Ключ не вставлен — нажми сюда"));
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (model.isEmpty()) {
            tv.setText(L.s("Модель не выбрана — нажми сюда"));
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else {
            String name = Providers.detectName(url);
            tv.setText(name + " · " + model);
            tv.setTextColor(Ui.color(this, R.attr.nxTextDim));
        }
        tv.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            Ui.fadeIn(this);
        });
    }

    private void animate() {
        View globe = findViewById(R.id.headerGlobe);
        if (globe instanceof GlobeView) {
            ((GlobeView) globe).setBrandDots(true);
            ((GlobeView) globe).setSpinSpeed(0.45f);
        }
        View card = findViewById(R.id.voiceModeBtn);
        if (card != null) {
            card.setAlpha(0f);
            card.setTranslationY(24f);
            card.animate().alpha(1f).translationY(0f).setDuration(260).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        renderPill();
        renderStt();
        if (UpdateChecker.retryPending(this)) {
            Toast.makeText(this, L.s("Продолжаю установку обновления"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] res) {
        super.onRequestPermissionsResult(rc, p, res);
        if ((rc == 7 || rc == 8) && res.length > 0
                && res[0] == PackageManager.PERMISSION_GRANTED) {
            startTried = false;
        }
        if (rc == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            SettingsStore.p(this).edit().putBoolean("wakeEnabled", true).apply();
            WakeService.start(this);
        }
        renderPill();
        renderStt();
    }
}
