package com.local.aivoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private Runnable sttTimeout;
    private static final int PERMS = 10;
    private static final int IMPORT_RC = 21;
    private static final int RESTORE_RC = 22;

    private EditText urlEt, keyEt, systemEt, userInfoEt;
    private TextView modelSel, textFbSel, visionSel, visionFbSel, templateSel, ttsSel;
    private android.widget.CheckBox visionCb, wakeCb, keepScreenCb, autoUpdateCb;
    private android.widget.SeekBar voiceVolumeSeek;
    private TextView voskStatus, modelCatalogStatus, updateStatus,
            voiceVolumeLabel, spendView, talkWindowSel, voiceSel, rateSel, pitchSel;
    private boolean voskDownloading = false;

    private final List<ModelCatalog.Info> catalog = new ArrayList<>();

    // values chosen via pickers
    private String modelValue, textFbValue, visionValue, visionFbValue, ttsValue;
    private TextView cloudSel, cloudVoiceSel, cloudHint;
    private android.widget.EditText cloudKeyEt, cloudUrlEt;
    private String cloudValue, cloudVoiceValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashActivity.applyTheme(this);
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        NxBlobs.apply(this);
        L.attach(this);
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        Ui.applyAmoled(this);

        urlEt = findViewById(R.id.urlEt);
        keyEt = findViewById(R.id.keyEt);
        systemEt = findViewById(R.id.systemEt);
        userInfoEt = findViewById(R.id.userInfoEt);
        modelSel = findViewById(R.id.modelSel);
        textFbSel = findViewById(R.id.textFbSel);
        visionSel = findViewById(R.id.visionSel);
        visionFbSel = findViewById(R.id.visionFbSel);
        templateSel = findViewById(R.id.templateSel);
        ttsSel = findViewById(R.id.ttsSel);
        cloudSel = findViewById(R.id.cloudSel);
        cloudVoiceSel = findViewById(R.id.cloudVoiceSel);
        cloudHint = findViewById(R.id.cloudHint);
        cloudKeyEt = findViewById(R.id.cloudKeyEt);
        cloudUrlEt = findViewById(R.id.cloudUrlEt);
        visionCb = findViewById(R.id.visionCb);
        wakeCb = findViewById(R.id.wakeCb);
        keepScreenCb = findViewById(R.id.keepScreenCb);
        autoUpdateCb = findViewById(R.id.autoUpdateCb);
        voiceVolumeSeek = findViewById(R.id.voiceVolumeSeek);
        voskStatus = findViewById(R.id.voskStatus);
        modelCatalogStatus = findViewById(R.id.modelCatalogStatus);
        updateStatus = findViewById(R.id.updateStatus);
        voiceVolumeLabel = findViewById(R.id.voiceVolumeLabel);
        spendView = findViewById(R.id.spendView);
        talkWindowSel = findViewById(R.id.talkWindowSel);
        voiceSel = findViewById(R.id.voiceSel);
        rateSel = findViewById(R.id.rateSel);
        pitchSel = findViewById(R.id.pitchSel);

        load();
        setupSelectors();
        setupVolume();
        setupTheme();
        setupLang();
        restoreScroll();

        findViewById(R.id.saveBtn).setOnClickListener(v -> save());
        findViewById(R.id.testBtn).setOnClickListener(v -> testLlm());
        findViewById(R.id.savePromptBtn).setOnClickListener(v -> savePromptAsMine());

        findViewById(R.id.macrosBtn).setOnClickListener(v -> showMacros());

        talkWindowSel.setOnClickListener(v -> pickTalkWindow());
        voiceSel.setOnClickListener(v -> pickVoice());
        rateSel.setOnClickListener(v -> pickRate());
        pitchSel.setOnClickListener(v -> pickPitch());

        findViewById(R.id.announceNotifCb).setOnClickListener(v -> {
            if (((android.widget.CheckBox) v).isChecked() && !notificationAccessGranted()) {
                Toast.makeText(this, L.s("Нужен доступ к уведомлениям: «Прочее» → «Разрешить чтение уведомлений»"),
                        Toast.LENGTH_LONG).show();
            }
            saveAnnounce();
        });

        findViewById(R.id.callAnswerCb).setOnClickListener(v -> {
            boolean on = ((android.widget.CheckBox) v).isChecked();
            SettingsStore.setCallAnswer(this, on);
            if (on) {
                askPermissions(new String[]{
                        Manifest.permission.ANSWER_PHONE_CALLS,
                        Manifest.permission.READ_PHONE_STATE});
                Toast.makeText(this, L.s("Автоответчик включён: ") + SettingsStore.callAnswerDelay(this)
                        + L.s(" сек, затем приветствие"), Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.callSetupBtn).setOnClickListener(v -> editCallAnswer());

        findViewById(R.id.announceQuietCb).setOnClickListener(v ->
                SettingsStore.setAnnounceQuiet(this,
                        ((android.widget.CheckBox) v).isChecked()));

        findViewById(R.id.amoledCb).setOnClickListener(v -> {
            boolean on = ((android.widget.CheckBox) v).isChecked();
            String key = Ui.themeKey(this);
            SettingsStore.setAmoled(this, on);
            if (on && Ui.THEME_FROST.equals(key)) key = Ui.THEME_MIDNIGHT;
            saveScrollForRecreate();
            Ui.setTheme(this, key);
        });
        findViewById(R.id.announceCallsCb).setOnClickListener(v -> {
            if (((android.widget.CheckBox) v).isChecked()
                    && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_PHONE_STATE}, PERMS + 1);
            }
            saveAnnounce();
        });

        findViewById(R.id.batteryBtn).setOnClickListener(v -> {
            PowerManager pm = getSystemService(PowerManager.class);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, L.s("Уже оптимизация отключена"), Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.capabilitiesBtn).setOnClickListener(v -> showCapabilities());

        findViewById(R.id.restartWakeBtn).setOnClickListener(v -> {
            WakeService.stop(this);
            SettingsStore.p(this).edit().putBoolean("wakeEnabled", true).apply();
            WakeService.start(this);
            wakeCb.setChecked(true);
            Toast.makeText(this, L.s("Прослушка перезапущена"), Toast.LENGTH_SHORT).show();
            findViewById(R.id.restartWakeBtn).postDelayed(this::renderWakeLive, 1500);
        });

        findViewById(R.id.notifBtn).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.testSttBtn).setOnClickListener(v -> testStt());
        findViewById(R.id.assistantSettingsBtn).setOnClickListener(v -> openAssistantSettings());

        findViewById(R.id.chooserHelpBtn).setOnClickListener(v -> showChooserHelp());

        findViewById(R.id.testVoiceBtn).setOnClickListener(v -> testVoice());

        findViewById(R.id.exportBtn).setOnClickListener(v -> exportSettings());
        findViewById(R.id.importBtn).setOnClickListener(v -> {
            // SAF-документ: файл копии может лежать где угодно и пережить удаление
            // приложения (раньше читался только внутренний файл, который стирался).
            try {
                startActivityForResult(Backup.pickFileIntent(), RESTORE_RC);
            } catch (Exception e) {
                Toast.makeText(this, L.s("Не удалось открыть файл копии: ") + e,
                        Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.checkUpdateBtn).setOnClickListener(v -> {
            SettingsStore.p(this).edit()
                    .putString("updateUrl", ((EditText) findViewById(R.id.updateUrlEt)).getText().toString().trim())
                    .putBoolean("autoUpdate", autoUpdateCb.isChecked())
                    .apply();
            android.widget.Button checkBtn = (android.widget.Button) v;
            checkBtn.setEnabled(false);
            updateStatus.setText(L.s("Проверяю…"));
            UpdateChecker.check(this, (msg, newer, apkUrl, name) -> {
                updateStatus.setText(msg);
                checkBtn.setEnabled(true);
                if (newer && apkUrl != null) askDownload(apkUrl, name);
            });
        });

        modelCatalogStatus.setOnClickListener(v -> loadModels());
        findViewById(R.id.providerBtn).setOnClickListener(v -> pickProvider());

        refreshStatuses();
        animateCards();

        if (BuildConfig.SAFE_MODE) {
            try { findViewById(R.id.orbsView).setVisibility(View.GONE); } catch (Throwable ignored) {}
            modelCatalogStatus.setText(L.s("Безопасный режим: загрузка моделей отключена"));
        } else {
            loadModels();
            if (SettingsStore.autoUpdate(this)) checkUpdatesSilently();
        }
    }

    /** stagger fade+slide-in for every card — kills the "wooden" feel */
    private void animateCards() {
        android.view.ViewGroup content = findViewById(R.id.mainContent);
        if (content == null) return;
        int i = 0;
        for (int k = 0; k < content.getChildCount(); k++) {
            View child = content.getChildAt(k);
            boolean card = child.getBackground() != null && k > 0;
            child.setAlpha(0f);
            child.setTranslationY(28f);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(60L * Math.min(i, 6))
                    .setDuration(320)
                    .start();
            if (card) i++;
        }
        View globe = findViewById(R.id.headerGlobe);
        if (globe instanceof GlobeView) {
            ((GlobeView) globe).setBrandDots(true);
            ((GlobeView) globe).setSpinSpeed(0.45f);
        }
    }

    // --------------------------------------------------------------- pickers

    private void setupSelectors() {
        ChoiceDialog.styleSelector(modelSel);
        ChoiceDialog.styleSelector(textFbSel);
        ChoiceDialog.styleSelector(visionSel);
        ChoiceDialog.styleSelector(visionFbSel);
        ChoiceDialog.styleSelector(templateSel);
        ChoiceDialog.styleSelector(ttsSel);
        ChoiceDialog.styleSelector(cloudSel);
        ChoiceDialog.styleSelector(cloudVoiceSel);
        cloudSel.setOnClickListener(v -> pickCloud());
        cloudVoiceSel.setOnClickListener(v -> pickCloudVoice());
        cloudKeyEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                SettingsStore.p(SettingsActivity.this).edit()
                        .putString("ttsCloudKey", e.toString().trim()).apply();
                renderCloud();
            }
        });
        cloudUrlEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                SettingsStore.p(SettingsActivity.this).edit()
                        .putString("ttsCloudUrl", e.toString().trim()).apply();
            }
        });


        // выбор модели сохраняем сразу: раньше он жил только в памяти и терялся,
        // если уйти с экрана, не нажав «Сохранить» — чат потом писал «Модель не выбрана»
        modelSel.setOnClickListener(v -> pickModel(false, false, value -> {
            modelValue = value;
            renderModel(modelSel, value);
            SettingsStore.p(this).edit()
                    .putString("model", value == null ? "" : value).apply();
        }));
        textFbSel.setOnClickListener(v -> pickModel(false, true, value -> {
            textFbValue = value;
            renderModel(textFbSel, value);
            SettingsStore.p(this).edit()
                    .putString("textFallback", value == null ? "" : value).apply();
        }));
        visionSel.setOnClickListener(v -> pickModel(true, false, value -> {
            visionValue = value;
            renderModel(visionSel, value);
            SettingsStore.p(this).edit()
                    .putString("visionModel", value == null ? "" : value).apply();
        }));
        visionFbSel.setOnClickListener(v -> pickModel(true, true, value -> {
            visionFbValue = value;
            renderModel(visionFbSel, value);
            SettingsStore.p(this).edit()
                    .putString("visionFallback", value == null ? "" : value).apply();
        }));
        // адрес и ключ тоже переживают уход с экрана без «Сохранить»
        keyEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                String v = e.toString().trim();
                if (!v.equals(SettingsStore.key(SettingsActivity.this))) {
                    SettingsStore.p(SettingsActivity.this).edit().putString("key", v).apply();
                }
            }
        });
        urlEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                String v = e.toString().trim();
                if (!v.equals(SettingsStore.url(SettingsActivity.this))) {
                    SettingsStore.p(SettingsActivity.this).edit().putString("url", v).apply();
                }
            }
        });
        templateSel.setOnClickListener(v -> showPromptPicker());
        ttsSel.setOnClickListener(v -> pickTts());
    }

    private interface ValueCb { void on(String value); }

    private void pickModel(boolean visionOnly, boolean allowEmpty, ValueCb cb) {
        String rec = visionOnly ? SettingsStore.DEFAULT_VISION_MODEL : SettingsStore.DEFAULT_MODEL;
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        if (rec != null && !rec.trim().isEmpty()) {
            rows.add(ChoiceDialog.Row.header(L.s("Рекомендуемая")));
            rows.add(ChoiceDialog.Row.item(rec, subtitle(rec), rec));
        }
        if (allowEmpty) rows.add(ChoiceDialog.Row.item(L.s("Без фолбэка"), L.s("не использовать резервную"), ""));

        rows.add(ChoiceDialog.Row.header(L.s("Все модели") + (visionOnly ? L.s(" (с картинками)") : "")
                + " — " + catalog.size()));
        if (catalog.isEmpty()) {
            rows.add(ChoiceDialog.Row.item(L.s("Каталог не загружен"),
                    L.s("нажми «Модели и API» → обнови каталог"), ""));
        }
        for (ModelCatalog.Info f : catalog) {
            if (visionOnly && !f.image) continue;
            rows.add(ChoiceDialog.Row.item(f.id, subtitle(f), f.id));
        }
        rows.add(ChoiceDialog.Row.header(L.s("Своё")));
        rows.add(ChoiceDialog.Row.item(L.s("Ввести ID модели вручную…"), null, "@custom"));

        ChoiceDialog.show(this, visionOnly ? L.s("Vision-модель") : L.s("Модель"), L.s("Поиск модели…"),
                rows, L.s("Обновить каталог"), this::loadModels, row -> {
                    if ("@custom".equals(row.value)) {
                        askCustomModel(visionOnly, cb);
                    } else {
                        cb.on(row.value);
                    }
                });
    }

    private void askCustomModel(boolean visionOnly, ValueCb cb) {
        EditText et = new EditText(this);
        et.setHint(L.s("например deepseek/deepseek-v4-flash"));
        et.setText(visionOnly ? visionValue : modelValue);
        et.setSingleLine(true);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("ID модели"))
                .setView(et)
                .setPositiveButton("OK", (d, w) -> cb.on(et.getText().toString().trim()))
                .setNegativeButton(L.s("Отмена"), null)
                .show();
    }

    private String subtitle(ModelCatalog.Info f) {
        StringBuilder sb = new StringBuilder();
        if (f.image) sb.append("🖼  ");
        String p = ModelCatalog.priceStr(f);
        sb.append(p.isEmpty() ? L.s("цена не указана") : p.trim());
        return sb.toString();
    }

    private String subtitle(String id) {
        for (ModelCatalog.Info f : catalog) {
            if (f.id.equals(id)) return subtitle(f);
        }
        return L.s("рекомендуемая");
    }

    private void renderModel(TextView v, String id) {
        if (id == null || id.isEmpty()) {
            v.setText(L.s("— не выбрано —"));
            return;
        }
        String price = "";
        for (ModelCatalog.Info f : catalog) {
            if (f.id.equals(id)) {
                price = ModelCatalog.priceStr(f);
                break;
            }
        }
        SpannableString sp = new SpannableString(id + (price.isEmpty() ? "" : price));
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, id.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!price.isEmpty()) {
            int start = id.length();
            sp.setSpan(new ForegroundColorSpan(Ui.color(this, R.attr.nxTextDim)),
                    start, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new RelativeSizeSpan(0.8f), start, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.setText(sp);
    }

    // ------------------------------------------------------- облачный голос

    private void pickCloud() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Сервис озвучки")));
        String[][] items = {
                {"", L.s("Голос телефона"), L.s("системный TTS, бесплатно")},
                {CloudTts.FREE, L.s("Google-голос · бесплатно"), L.s("живой женский голос, без ключа")},
                {CloudTts.EDGE, L.s("Edge нейроголос · бесплатно"), L.s("Svetlana / Dmitry, без ключа")},
                {CloudTts.OPENAI, "OpenAI · gpt-4o-mini-tts", L.s("живой голос, дешево")},
                {CloudTts.ELEVEN, "ElevenLabs · multilingual v2", L.s("самый естественный русский")},
                {CloudTts.YANDEX, L.s("Яндекс SpeechKit"), L.s("лучший русский, нужен каталог")},
        };
        for (String[] it : items) {
            boolean on = it[0].equals(cloudValue == null ? "" : cloudValue);
            rows.add(on ? ChoiceDialog.Row.checked(it[1], it[2], it[0])
                    : ChoiceDialog.Row.item(it[1], it[2], it[0]));
        }
        ChoiceDialog.show(this, L.s("Чем говорить"), null, rows, null, null, row -> {
            cloudValue = row.value == null ? "" : row.value;
            SettingsStore.p(this).edit().putString("ttsCloud", cloudValue).apply();
            cloudVoiceValue = "";
            SettingsStore.p(this).edit().putString("ttsCloudVoice", "").apply();
            renderCloud();
        });
    }

    private void pickCloudVoice() {
        String provider = cloudValue == null ? "" : cloudValue;
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Голоса")));
        java.util.LinkedHashMap<String, String> voices = new java.util.LinkedHashMap<>();
        if (CloudTts.FREE.equals(provider)) {
            voices.put("ru", L.s("Русский · женский (Google)"));
        } else if (CloudTts.EDGE.equals(provider)) {
            voices.put("ru-RU-SvetlanaNeural", L.s("Svetlana · женский, живой"));
            voices.put("ru-RU-DmitryNeural", L.s("Dmitry · мужской, спокойный"));
        } else if (CloudTts.ELEVEN.equals(provider)) {
            voices.put("EXAVITQu4vr4xnSDxMaL", L.s("Sarah · женский, мягкий"));
            voices.put("21m00Tcm4TlvDq8ikWAM", L.s("Rachel · женский, спокойный"));
            voices.put("pNInz6obpgDQGcFmaJgB", L.s("Adam · мужской, низкий"));
            voices.put("ErXwobaYiN019PkySvjV", L.s("Antoni · мужской, тёплый"));
        } else if (CloudTts.YANDEX.equals(provider)) {
            voices.put("filipp", L.s("Filipp · мужской"));
            voices.put("ermil", L.s("Ermil · мужской"));
            voices.put("jane", L.s("Jane · женский"));
            voices.put("omazh", L.s("Omazh · женский"));
            voices.put("alena", L.s("Alena · женский, живой"));
            voices.put("zahar", L.s("Zahar · мужской"));
        } else {
            voices.put("nova", L.s("Nova · женский, тёплый"));
            voices.put("shimmer", L.s("Shimmer · женский, мягкий"));
            voices.put("coral", L.s("Coral · женский, бодрый"));
            voices.put("alloy", L.s("Alloy · нейтральный"));
            voices.put("onyx", L.s("Onyx · мужской, низкий"));
            voices.put("echo", L.s("Echo · мужской"));
        }
        for (java.util.Map.Entry<String, String> e : voices.entrySet()) {
            boolean on = e.getKey().equals(CloudTts.voice(this));
            rows.add(on ? ChoiceDialog.Row.checked(e.getValue(), e.getKey(), e.getKey())
                    : ChoiceDialog.Row.item(e.getValue(), e.getKey(), e.getKey()));
        }
        rows.add(ChoiceDialog.Row.item(L.s("Свой ID голоса…"), L.s("вставь идентификатор"), "__custom"));
        ChoiceDialog.show(this, L.s("Голос облака"), null, rows, null, null, row -> {
            if ("__custom".equals(row.value)) {
                askCustomVoice();
                return;
            }
            cloudVoiceValue = row.value;
            SettingsStore.p(this).edit().putString("ttsCloudVoice", cloudVoiceValue).apply();
            renderCloud();
        });
    }

    private void askCustomVoice() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("voice id");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Свой голос"))
                .setView(et)
                .setPositiveButton(L.s("Ок"), (d, w) -> {
                    cloudVoiceValue = et.getText().toString().trim();
                    SettingsStore.p(this).edit()
                            .putString("ttsCloudVoice", cloudVoiceValue).apply();
                    renderCloud();
                })
                .setNegativeButton(L.s("Отмена"), null)
                .show();
    }

    private void renderCloud() {
        if (cloudSel == null) return;
        String p = cloudValue == null ? "" : cloudValue;
        String label;
        switch (p) {
            case CloudTts.FREE: label = L.s("Google-голос · бесплатно"); break;
            case CloudTts.EDGE: label = L.s("Edge нейроголос · бесплатно"); break;
            case CloudTts.OPENAI: label = "OpenAI · gpt-4o-mini-tts"; break;
            case CloudTts.ELEVEN: label = "ElevenLabs · multilingual v2"; break;
            case CloudTts.YANDEX: label = L.s("Яндекс SpeechKit"); break;
            default: label = L.s("Голос телефона (бесплатно)"); break;
        }
        cloudSel.setText(label);
        boolean on = !p.isEmpty();
        boolean needKey = on && !CloudTts.EDGE.equals(p) && !CloudTts.FREE.equals(p);
        findViewById(R.id.deviceVoiceBox).setVisibility(on ? View.GONE : View.VISIBLE);
        findViewById(R.id.cloudVoiceLabel).setVisibility(on ? View.VISIBLE : View.GONE);
        cloudVoiceSel.setVisibility(on ? View.VISIBLE : View.GONE);
        findViewById(R.id.cloudKeyLabel).setVisibility(needKey ? View.VISIBLE : View.GONE);
        cloudKeyEt.setVisibility(needKey ? View.VISIBLE : View.GONE);
        findViewById(R.id.cloudUrlLabel).setVisibility(
                on && CloudTts.OPENAI.equals(p) ? View.VISIBLE : View.GONE);
        cloudUrlEt.setVisibility(on && CloudTts.OPENAI.equals(p) ? View.VISIBLE : View.GONE);
        cloudVoiceSel.setText(prettyCloudVoice(p, CloudTts.voice(this)));
        String key = SettingsStore.p(this).getString("ttsCloudKey", "");
        if (!key.equals(cloudKeyEt.getText().toString())) cloudKeyEt.setText(key);
        String url = SettingsStore.p(this).getString("ttsCloudUrl", "");
        if (!url.equals(cloudUrlEt.getText().toString())) cloudUrlEt.setText(url);

        if (!on) {
            cloudHint.setText(L.s("Сейчас говорит системный движок телефона. Облако звучит заметно живее."));
        } else if (CloudTts.FREE.equals(p)) {
            cloudHint.setText(L.s("Бесплатно и без ключа. Нужен интернет."));
        } else if (CloudTts.EDGE.equals(p)) {
            // в России Edge отдаёт 403: человек должен видеть причину, а не искать баг
            cloudHint.setText(CloudTts.edgeBlocked()
                    ? L.s("Edge из России недоступен (403) — говорит голос движка телефона. Выбери его в списке «Голос озвучки».")
                    : L.s("Бесплатно и без ключа: нейроголоса Microsoft. Нужен интернет."));
        } else if (key.trim().isEmpty()) {
            cloudHint.setText(CloudTts.ELEVEN.equals(p)
                    ? L.s("Вставь ключ ElevenLabs (Settings → API Keys)")
                    : CloudTts.YANDEX.equals(p)
                    ? L.s("Вставь Api-Key Яндекс Облака (сервис SpeechKit)")
                    : L.s("Вставь ключ OpenAI (platform.openai.com → API keys)"));
        } else {
            cloudHint.setText(L.s("Облачный голос включён. Проверь кнопкой ниже."));
        }
    }

    /** одна кнопка на всё: показывает, что именно произошло, вместо тишины */
    /** «ru» рядом с названием сервиса выглядит как баг — пишем по-человечески */
    private String prettyCloudVoice(String provider, String voice) {
        if (voice == null || voice.isEmpty()) return L.s("по умолчанию");
        if (CloudTts.FREE.equals(provider)) return L.s("Русский · женский (Google)");
        if (CloudTts.EDGE.equals(provider)) {
            if (voice.contains("Svetlana")) return L.s("Svetlana · женский");
            if (voice.contains("Dmitry")) return L.s("Dmitry · мужской");
            return voice;
        }
        java.util.HashMap<String, String> names = new java.util.HashMap<>();
        names.put("nova", L.s("Nova · женский"));
        names.put("shimmer", L.s("Shimmer · женский"));
        names.put("coral", L.s("Coral · женский"));
        names.put("alloy", L.s("Alloy · нейтральный"));
        names.put("onyx", L.s("Onyx · мужской"));
        names.put("echo", L.s("Echo · мужской"));
        names.put("filipp", L.s("Filipp · мужской"));
        names.put("ermil", L.s("Ermil · мужской"));
        names.put("jane", L.s("Jane · женский"));
        names.put("omazh", L.s("Omazh · женский"));
        names.put("alena", L.s("Alena · женский"));
        names.put("zahar", L.s("Zahar · мужской"));
        String nice = names.get(voice);
        return nice == null ? voice : nice;
    }

    private TtsSpeaker testSpeaker;

    private void testVoice() {
        // прошлый тестовый движок гасим, иначе быстрые нажатия дают хор голосов
        if (testSpeaker != null) {
            try { testSpeaker.shutdown(); } catch (Throwable ignored) {}
            testSpeaker = null;
        }
        final TextView status = findViewById(R.id.voiceTestStatus);
        status.setTextColor(Ui.color(this, R.attr.nxTextDim));
        String p = cloudValue == null ? "" : cloudValue;
        if (!p.isEmpty() && !CloudTts.EDGE.equals(p) && !CloudTts.FREE.equals(p)
                && SettingsStore.p(this).getString("ttsCloudKey", "").trim().isEmpty()) {
            status.setTextColor(Ui.color(this, R.attr.nxWarn));
            status.setText(L.s("Нужен API-ключ этого сервиса"));
            return;
        }
        status.setText(L.s("Синтезирую…"));
        final TtsSpeaker t = new TtsSpeaker(this, ttsValue, SettingsStore.voiceVolume(this),
                SettingsStore.ttsRate(this), SettingsStore.ttsPitch(this),
                SettingsStore.ttsVoice(this), () -> runOnUiThread(() ->
                        status.setText(L.s("Готово. Если не слышно — проверь громкость медиа"))));
        testSpeaker = t;
        t.setStateListener(speaking -> runOnUiThread(() -> {
            if (speaking) status.setText(L.s("Говорю…"));
        }));
        t.speak(L.s("Привет! Я Нэксус, так звучит мой голос."));

        findViewById(R.id.voiceTestStatus).postDelayed(() -> {
            String problem = t.problem();
            if (problem == null) return;
            switch (problem) {
                case "cloud":
                    status.setTextColor(Ui.color(this, R.attr.nxWarn));
                    status.setText(L.s("Облако не ответило — сказал голосом телефона. ")
                            + L.s("Проверь интернет и ключ"));
                    break;
                case "no_ru":
                    status.setTextColor(Ui.color(this, R.attr.nxWarn));
                    status.setText(L.s("В телефоне нет русского голоса: Настройки → Синтез речи → русский"));
                    break;
                case "voice_missing":
                    status.setTextColor(Ui.color(this, R.attr.nxWarn));
                    status.setText(L.s("Выбранный голос не найден в движке — сказал голосом по умолчанию. Выбери голос из списка заново"));
                    break;
                default:
                    status.setTextColor(Ui.color(this, R.attr.nxDanger));
                    status.setText(L.s("Синтез речи не отвечает — поставь «Речевые службы Google»"));
                    break;
            }
        }, 6000);
    }

    private void testCloud() {
        if (cloudValue == null || cloudValue.isEmpty()) {
            Toast.makeText(this, L.s("Сначала выбери облачный сервис"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!CloudTts.EDGE.equals(cloudValue) && !CloudTts.FREE.equals(cloudValue)
                && SettingsStore.p(this).getString("ttsCloudKey", "").trim().isEmpty()) {
            Toast.makeText(this, L.s("Нужен API-ключ озвучки"), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, L.s("Синтезирую…"), Toast.LENGTH_SHORT).show();
        final TtsSpeaker t = new TtsSpeaker(this, ttsValue, SettingsStore.voiceVolume(this),
                () -> { });
        t.speak(L.s("Привет! Так звучит мой новый голос. Нравится?"));
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            if (!t.isCloud() && t.problem() != null) {
                Toast.makeText(this, L.s("Облако не ответило: ") + t.problem()
                        + L.s(" — проверь ключ и интернет"), Toast.LENGTH_LONG).show();
            }
        }, 6000);
    }

    private void pickTts() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Голос")));
        boolean none = ttsValue == null || ttsValue.isEmpty();
        rows.add(none
                ? ChoiceDialog.Row.checked(L.s("Системный по умолчанию"), L.s("как в настройках телефона"), "")
                : ChoiceDialog.Row.item(L.s("Системный по умолчанию"), L.s("как в настройках телефона"), ""));
        for (String id : ttsEngines()) {
            if (id.equals(ttsValue)) {
                rows.add(ChoiceDialog.Row.checked(id, null, id));
            } else {
                rows.add(ChoiceDialog.Row.item(id, null, id));
            }
        }
        ChoiceDialog.show(this, L.s("Голос Нексуса"), L.s("Поиск движка…"), rows,
                L.s("Проверить голос"), () -> new TtsSpeaker(this, ttsValue,
                        SettingsStore.voiceVolume(this), () -> {
                }).speak(L.s("Так звучит мой голос.")),
                row -> {
                    ttsValue = row.value;
                    renderTts();
                });
    }

    private List<String> ttsEngines() {
        List<String> out = new ArrayList<>();
        try {
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
                out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void renderTts() {
        ttsSel.setText(ttsValue == null || ttsValue.isEmpty()
                ? L.s("Системный по умолчанию") : ttsValue + L.s("  ·  выбрано"));
    }

    // --------------------------------------------------------------- prompts

    private void showPromptPicker() {
        String selected = PromptTemplates.selectedName(this);
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Готовые шаблоны")));
        for (String[] t : PromptTemplates.ALL) {
            String preview = t[1] == null || t[1].trim().isEmpty()
                    ? L.s("базовый характер, ничего лишнего")
                    : ellipsize(t[1], 90);
            rows.add(t[0].equals(selected)
                    ? ChoiceDialog.Row.checked(L.s(t[0]), preview, "@builtin:" + t[0])
                    : ChoiceDialog.Row.item(L.s(t[0]), preview, "@builtin:" + t[0]));
        }
        List<PromptTemplates.UserPrompt> mine = PromptTemplates.userList(this);
        rows.add(ChoiceDialog.Row.header(mine.isEmpty()
                ? L.s("Мои промпты — пока пусто") : L.s("Мои промпты (") + mine.size() + ")"));
        for (PromptTemplates.UserPrompt p : mine) {
            rows.add(p.name.equals(selected)
                    ? ChoiceDialog.Row.checked(L.s(p.name), ellipsize(p.text, 90), "@user:" + p.name)
                    : ChoiceDialog.Row.item(L.s(p.name), ellipsize(p.text, 90), "@user:" + p.name));
        }
        rows.add(ChoiceDialog.Row.header(L.s("Действия")));
        rows.add(ChoiceDialog.Row.item(L.s("＋  Сохранить текущий текст как мой промпт"),
                L.s("то, что сейчас в поле ниже"), "@save"));
        if (!mine.isEmpty()) {
            rows.add(ChoiceDialog.Row.item(L.s("🗑  Удалить мой промпт…"),
                    L.s("выбрать и удалить"), "@delete"));
        }
        rows.add(ChoiceDialog.Row.item(L.s("↺  Вернуть стандартный характер"),
                L.s("Нексус (по умолчанию)"), "@reset"));

        ChoiceDialog.show(this, L.s("Характер Нексуса"), L.s("Поиск шаблона…"), rows, null, null, row -> {
            String v = row.value == null ? "" : row.value;
            if (v.startsWith("@builtin:")) {
                String name = v.substring("@builtin:".length());
                PromptTemplates.apply(this, name);
                systemEt.setText(PromptTemplates.builtInText(name));
                renderTemplate();
            } else if (v.startsWith("@user:")) {
                String name = v.substring("@user:".length());
                PromptTemplates.apply(this, name);
                for (PromptTemplates.UserPrompt p : PromptTemplates.userList(this)) {
                    if (p.name.equals(name)) systemEt.setText(p.text);
                }
                renderTemplate();
            } else if ("@save".equals(v)) {
                savePromptAsMine();
            } else if ("@delete".equals(v)) {
                pickDeletePrompt();
            } else if ("@reset".equals(v)) {
                PromptTemplates.apply(this, PromptTemplates.DEFAULT_NAME);
                systemEt.setText("");
                renderTemplate();
            }
        });
    }

    private void pickDeletePrompt() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Удалить мой промпт")));
        for (PromptTemplates.UserPrompt p : PromptTemplates.userList(this)) {
            rows.add(ChoiceDialog.Row.item(p.name, ellipsize(p.text, 70), p.name));
        }
        ChoiceDialog.show(this, L.s("Удаление"), L.s("Поиск…"), rows, null, null, row -> {
            PromptTemplates.deleteUser(this, row.value);
            Toast.makeText(this, L.s("Удалён: ") + row.value, Toast.LENGTH_SHORT).show();
            renderTemplate();
        });
    }

    private void savePromptAsMine() {
        String text = systemEt.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, L.s("Сначала напиши текст промпта"), Toast.LENGTH_SHORT).show();
            return;
        }
        EditText et = new EditText(this);
        et.setHint(L.s("название, например «Мой стиль»"));
        et.setSingleLine(true);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Название промпта"))
                .setView(et)
                .setPositiveButton(L.s("Сохранить"), (d, w) -> {
                    String name = et.getText().toString().trim();
                    PromptTemplates.saveUser(this, name, text);
                    renderTemplate();
                    Toast.makeText(this, L.s("Сохранено: ") + L.s(PromptTemplates.selectedName(this)),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.s("Отмена"), null)
                .show();
    }

    private void renderTemplate() {
        String name = PromptTemplates.selectedName(this);
        boolean mine = false;
        for (PromptTemplates.UserPrompt p : PromptTemplates.userList(this)) {
            if (p.name.equals(name)) mine = true;
        }
        templateSel.setText(L.s(name) + (mine ? L.s("  ·  мой") : L.s("  ·  готовый")));
    }

    private static String ellipsize(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    // ------------------------------------------------------------- settings

    private void setupVolume() {
        voiceVolumeSeek.setProgress(SettingsStore.voiceVolume(this));
        voiceVolumeLabel.setText(L.s("Громкость: ") + SettingsStore.voiceVolume(this) + "%");
        voiceVolumeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean b) {
                voiceVolumeLabel.setText(L.s("Громкость: ") + v + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
    }

    private static int pendingScrollY = 0;

    /** запомнить, где человек читал настройки, чтобы вернуть его туда после recreate() */
    private void saveScrollForRecreate() {
        try {
            android.widget.ScrollView sc = findViewById(R.id.settingsScroll);
            if (sc != null) pendingScrollY = sc.getScrollY();
        } catch (Throwable ignored) {
        }
    }

    private void restoreScroll() {
        if (pendingScrollY <= 0) return;
        final int y = pendingScrollY;
        android.widget.ScrollView sc = findViewById(R.id.settingsScroll);
        if (sc == null) return;
        sc.post(() -> {
            int max = Math.max(0, sc.getChildAt(0).getHeight() - sc.getHeight());
            sc.scrollTo(0, Math.min(y, max));
        });
    }

    private void setupLang() {
        android.widget.RadioGroup g = findViewById(R.id.langGroup);
        if (g == null) return;
        if ("en".equals(L.lang(this))) g.check(R.id.langEn);
        else g.check(R.id.langRu);
        g.setOnCheckedChangeListener((grp, id) -> {
            String code = id == R.id.langEn ? "en" : "ru";
            if (code.equals(L.lang(this))) return;
            L.setLang(this, code);
            saveScrollForRecreate();
            recreate();
        });
    }

    private void setupTheme() {
        android.widget.RadioGroup themeGroup = findViewById(R.id.themeGroup);
        if (themeGroup == null) return;
        String key = Ui.themeKey(this);
        if (Ui.THEME_FROST.equals(key)) themeGroup.check(R.id.themeFrost);
        else if (Ui.THEME_GRAPHITE.equals(key)) themeGroup.check(R.id.themeGraphite);
        else themeGroup.check(R.id.themeMidnight);
        themeGroup.setOnCheckedChangeListener((g, id) -> {
            if (id != R.id.themeMidnight && id != R.id.themeFrost
                    && id != R.id.themeGraphite) return;
            String next = id == R.id.themeFrost ? Ui.THEME_FROST
                    : id == R.id.themeGraphite ? Ui.THEME_GRAPHITE : Ui.THEME_MIDNIGHT;
            if (next.equals(Ui.themeKey(this))) return;
            if (Ui.THEME_FROST.equals(next) && SettingsStore.amoled(this)) {
                SettingsStore.setAmoled(this, false);
                android.widget.CheckBox cb = findViewById(R.id.amoledCb);
                if (cb != null) cb.setChecked(false);
            }
            // смена темы пересоздаёт экран: запоминаем позицию, иначе человека
            // выбрасывало в начало настроек
            saveScrollForRecreate();
            Ui.setTheme(this, next);
        });
    }

    private void save() {
        SettingsStore.save(this,
                urlEt.getText().toString().trim(),
                keyEt.getText().toString().trim(),
                modelValue == null ? SettingsStore.DEFAULT_MODEL : modelValue,
                visionValue == null ? SettingsStore.DEFAULT_VISION_MODEL : visionValue,
                textFbValue == null ? "" : textFbValue,
                visionFbValue == null ? "" : visionFbValue,
                visionCb.isChecked(),
                systemEt.getText().toString(),
                wakeCb.isChecked());
        SettingsStore.setUserInfo(this, userInfoEt.getText().toString());
        SettingsStore.saveAssistantPrefs(this,
                keepScreenCb.isChecked(),
                voiceVolumeSeek.getProgress(),
                ttsValue == null ? "" : ttsValue);
        Toast.makeText(this, L.s("Сохранено"), Toast.LENGTH_SHORT).show();
        if (SettingsStore.wakeEnabled(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            WakeService.start(this);
        } else {
            WakeService.stop(this);
        }
    }

    private void pickProvider() {
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        String cur = SettingsStore.url(this);
        rows.add(ChoiceDialog.Row.header(L.s("Провайдер (OpenAI-совместимый)")));
        for (Providers.P p : Providers.KNOWN) {
            boolean selected = Providers.norm(p.url).equals(Providers.norm(cur));
            String sub = p.url.replace("https://", "").replace("http://", "");
            rows.add(selected
                    ? ChoiceDialog.Row.checked(p.name, sub, p.url)
                    : ChoiceDialog.Row.item(p.name, sub, p.url));
        }
        rows.add(ChoiceDialog.Row.header(L.s("Или свой адрес")));
        rows.add(ChoiceDialog.Row.item(L.s("Ввести адрес вручную"), null, "__custom"));

        ChoiceDialog.show(this, L.s("Провайдер"), L.s("Поиск провайдера…"), rows, null, null, row -> {
            if ("__custom".equals(row.value)) return;
            Providers.P p = null;
            for (Providers.P k : Providers.KNOWN) {
                if (k.url.equals(row.value)) { p = k; break; }
            }
            findViewById(R.id.urlEt).setFocusable(true);
            ((EditText) findViewById(R.id.urlEt)).setText(row.value);
            String prevUrl = Providers.norm(SettingsStore.url(this));
            SettingsStore.p(this).edit().putString("url", row.value).apply();
            if (!prevUrl.equals(Providers.norm(row.value))) {
                // у нового провайдера свои имена моделей — старые не подойдут
                modelValue = null;
                visionValue = null;
                textFbValue = null;
                visionFbValue = null;
                SettingsStore.p(this).edit()
                        .putString("model", "")
                        .putString("visionModel", "")
                        .putString("textFallback", "")
                        .putString("visionFallback", "")
                        .apply();
                renderModel(findViewById(R.id.modelSel), "");
                renderModel(findViewById(R.id.visionSel), "");
                renderModel(findViewById(R.id.textFbSel), "");
                renderModel(findViewById(R.id.visionFbSel), "");
            }
            if (p != null && p.keyUrl != null && SettingsStore.key(this).trim().isEmpty()) {
                Toast.makeText(this, L.s("Ключ для ") + p.name + L.s(" — открою страницу"), Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)));
                } catch (Throwable ignored) {
                }
            }
            Toast.makeText(this, L.s("Проверяю ") + Providers.detectName(row.value) + "…",
                    Toast.LENGTH_SHORT).show();
            loadModels();
        });
    }

    /** подставляет модели, если пользователь ещё ничего не выбрал */
    private void autoPickModels(List<ModelCatalog.Info> models) {
        String text = null, vision = null;
        for (ModelCatalog.Info m : models) {
            if (m.id == null || m.id.isEmpty()) continue;
            if (m.image && vision == null) vision = m.id;
            if (!m.image && text == null) text = m.id;
        }
        android.content.SharedPreferences.Editor e = SettingsStore.p(this).edit();
        boolean changed = false;
        if (SettingsStore.model(this).isEmpty() && text != null) {
            e.putString("model", text); modelValue = text; changed = true;
        }
        if (SettingsStore.visionModel(this).isEmpty() && vision != null) {
            e.putString("visionModel", vision); visionValue = vision; changed = true;
        }
        if (SettingsStore.textFallback(this).isEmpty() && models.size() > 1) {
            for (ModelCatalog.Info m : models) {
                if (!m.image && m.id != null && !m.id.equals(text)) {
                    e.putString("textFallback", m.id);
                    textFbValue = m.id;
                    changed = true;
                    break;
                }
            }
        }
        if (changed) e.apply();
        renderModel(modelSel, SettingsStore.model(this));
        renderModel(textFbSel, SettingsStore.textFallback(this));
        renderModel(visionSel, SettingsStore.visionModel(this));
    }

    private void loadModels() {
        if (SettingsStore.url(this).trim().isEmpty()) {
            modelCatalogStatus.setText(L.s("Провайдер не выбран — нажми «Выбрать провайдера»"));
            return;
        }
        if (SettingsStore.key(this).trim().isEmpty()) {
            modelCatalogStatus.setText(L.s("Нужен API-ключ: выбери провайдера и вставь ключ ниже"));
            return;
        }
        modelCatalogStatus.setText(L.s("Загружаю каталог моделей…"));
        ModelCatalog.fetch(SettingsStore.url(this), SettingsStore.key(this), (models, error) -> {
            if (error != null) {
                modelCatalogStatus.setText(L.s("Ошибка каталога: ") + error + L.s(" (нажми, чтобы повторить)"));
                return;
            }
            catalog.clear();
            catalog.addAll(models);
            CostTracker.cachePrices(this, models);
            int priced = 0;
            int vision = 0;
            for (ModelCatalog.Info f : models) {
                if (f.pin >= 0) priced++;
                if (f.image) vision++;
            }
            modelCatalogStatus.setText(L.s("Моделей: ") + models.size()
                    + "  ·  vision: " + vision + L.s("  ·  с ценами: ") + priced
                    + L.s("\nНажми на любое поле модели, чтобы выбрать"));
            spendView.setText(CostTracker.todaySummary(this));
            renderModel(modelSel, modelValue);
            renderModel(textFbSel, textFbValue);
            renderModel(visionSel, visionValue);
            renderModel(visionFbSel, visionFbValue);
        });
    }

    /**
     * Тихая проверка — только проверка. Раньше каждое открытие настроек при
     * включённом автообновлении начинало качать ~49 МБ без спроса; теперь
     * скачивание происходит лишь по нажатию «Проверить обновления».
     */
    private void checkUpdatesSilently() {
        UpdateChecker.checkOnly(this, (msg, newer, apkUrl, name) -> {
            updateStatus.setText(newer
                    ? msg + L.s(" — нажми «Проверить обновления», чтобы скачать")
                    : msg);
        });
    }

    private void askDownload(String apkUrl, String name) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Обновление ") + name)
                .setMessage(L.s("Скачать новую версию (~49 МБ)?"))
                .setPositiveButton(L.s("Скачать"), (d, w) -> startDownload(apkUrl, name))
                .setNegativeButton(L.s("Позже"), null)
                .show();
    }

    private void startDownload(String apkUrl, String name) {
        updateStatus.setText(L.s("Скачивание… 0%"));
        findViewById(R.id.checkUpdateBtn).setEnabled(false);
        UpdateChecker.download(this, apkUrl, name, new UpdateChecker.DownloadCb() {
            @Override public void onProgress(int percent) {
                updateStatus.setText(L.s("Скачивание… ") + percent + "%");
            }
            @Override public void onDone(java.io.File apk, String n, String error) {
                findViewById(R.id.checkUpdateBtn).setEnabled(true);
                if (error != null) {
                    updateStatus.setText(L.s("Ошибка скачивания: ") + error);
                    return;
                }
                updateStatus.setText(L.s("Скачано"));
                new androidx.appcompat.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(L.s("Обновление ") + n)
                        .setMessage(L.s("Загрузка завершена. Установить сейчас?"))
                        .setPositiveButton(L.s("Установить"), (d, w) ->
                                UpdateChecker.install(SettingsActivity.this, apk))
                        .setNegativeButton(L.s("Позже"), (d, w) ->
                                Toast.makeText(SettingsActivity.this,
                                        L.s("Файл сохранён — установишь позже через «Проверить обновления»"),
                                        Toast.LENGTH_LONG).show())
                        .show();
            }
        });
    }

    // ------------------------------------------------- помощник по умолчанию

    private void renderAssistantState() {
        TextView t = findViewById(R.id.assistantState);
        if (t == null) return;
        boolean me = DefaultCheck.isDefaultAssistant(this);
        t.setText(me
                ? L.s("Помощник по умолчанию: Nexus Speak")
                : L.s("Помощник по умолчанию: не Nexus Speak — поэтому зажатие открывает чужого ассистента"));
        t.setTextColor(me ? Ui.color(this, R.attr.nxOk) : Ui.color(this, R.attr.nxWarn));
    }

    private void openAssistantSettings() {
        Intent[] variants = {
                new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent i : variants) {
            try {
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, L.s("Не нашёл экран настроек помощника"), Toast.LENGTH_LONG).show();
    }

    private void showChooserHelp() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Как назначить Nexus Speak помощником"))
                .setMessage(L.s("1. Нажми «Открыть выбор помощника».\n")
                        + L.s("2. Выбери Nexus Speak (в некоторых прошивках — «Приложения по умолчанию → Помощник»).\n")
                        + L.s("3. Зажми кнопку — откроется Нэксус.\n\n")
                        + L.s("Если продолжает открываться другой ассистент: отключи его у него самого ")
                        + L.s("(в настройках приложения — «запуск по кнопке», «по кнопке наушников»). ")
                        + L.s("Список выбора Android показывает, только когда помощник не назначен."))
                .setPositiveButton(L.s("Понятно"), null)
                .show();
    }

    private void testStt() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, L.s("Системное распознавание речи недоступно. ")
                    + L.s("Установи «Речевые службы Google» (com.google.android.tts)"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, L.s("Сначала разреши микрофон"), Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMS + 5);
            return;
        }
        Toast.makeText(this, L.s("Говори что-нибудь 5 секунд…"), Toast.LENGTH_SHORT).show();
        final android.speech.SpeechRecognizer rec =
                android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        final boolean[] finished = {false};
        rec.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int i, android.os.Bundle b) { }
            @Override public void onError(int error) {
                if (finished[0]) return;
                finished[0] = true;
                Toast.makeText(SettingsActivity.this, L.s("Ошибка распознавания: ") + error
                        + (error == 7 ? L.s(" (нет сети)") : error == 9 ? L.s(" (нет разрешения)")
                        : error == 5 ? L.s(" (ничего не услышал)") : ""), Toast.LENGTH_LONG).show();
                try { rec.destroy(); } catch (Throwable ignored) { }
            }
            @Override public void onPartialResults(android.os.Bundle b) { }
            @Override public void onResults(android.os.Bundle b) {
                if (finished[0]) return;
                finished[0] = true;
                java.util.ArrayList<String> r =
                        b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (r == null || r.isEmpty()) ? L.s("пусто") : r.get(0);
                Toast.makeText(SettingsActivity.this, L.s("Услышал: ") + text, Toast.LENGTH_LONG).show();
                try { rec.destroy(); } catch (Throwable ignored) { }
            }
        });
        android.content.Intent i = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, L.isEn() ? "en-US" : "ru-RU");
        try { rec.startListening(i); } catch (Throwable e) {
            Toast.makeText(this, L.s("Не смог запустить распознавание: ") + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        sttTimeout = () -> {
            if (!finished[0]) {
                finished[0] = true;
                Toast.makeText(this, L.s("Распознавание не ответило за 8 секунд — движок не работает"),
                        Toast.LENGTH_LONG).show();
                try { rec.destroy(); } catch (Throwable ignored) { }
            }
        };
        h.postDelayed(sttTimeout, 8000);
    }

    private boolean notificationAccessGranted() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void askPermissions(String[] perms) {
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    need.toArray(new String[0]), PERMS + 2);
        }
    }

    private void editCallAnswer() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView l1 = new TextView(this);
        l1.setText(L.s("Что сказать звонящему:"));
        box.addView(l1);

        EditText greet = new EditText(this);
        greet.setText(SettingsStore.callGreeting(this));
        greet.setMinLines(3);
        greet.setGravity(android.view.Gravity.TOP);
        greet.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(greet);

        TextView l2 = new TextView(this);
        l2.setText(L.s("Ответить через, секунд:"));
        box.addView(l2);

        EditText delay = new EditText(this);
        delay.setText(String.valueOf(SettingsStore.callAnswerDelay(this)));
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(delay);

        android.widget.CheckBox hangup = new android.widget.CheckBox(this);
        hangup.setText(L.s("Положить трубку после приветствия"));
        hangup.setChecked(SettingsStore.callAutoHangup(this));
        box.addView(hangup);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Автоответчик"))
                .setView(box)
                .setPositiveButton(L.s("Сохранить"), (d, w) -> {
                    int secs = 6;
                    try {
                        secs = Integer.parseInt(delay.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    SettingsStore.setCallPrefs(this, greet.getText().toString(), secs,
                            hangup.isChecked());
                    Toast.makeText(this, L.s("Сохранено"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.s("Отмена"), null)
                .show();
    }

    private void saveAnnounce() {
        boolean notif = ((android.widget.CheckBox) findViewById(R.id.announceNotifCb)).isChecked();
        boolean calls = ((android.widget.CheckBox) findViewById(R.id.announceCallsCb)).isChecked();
        SettingsStore.setAnnounce(this, notif, calls);
        if (notif) {
            Toast.makeText(this, L.s("Разреши доступ к уведомлениям в «Прочее», иначе читать нечего"),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderMacros() {
        TextView tv = findViewById(R.id.macrosStatus);
        java.util.List<Macros.Macro> list = Macros.list(this);
        if (list.isEmpty()) {
            tv.setText(L.s("Макросов нет. Пример: «Домой» → open_app:youtube, volume:60. ")
                    + L.s("Нексус выполнит его по фразе «Нексус, включи Домой»."));
        } else {
            StringBuilder sb = new StringBuilder();
            for (Macros.Macro m : list) {
                sb.append("• ").append(m.name)
                  .append("  (").append(m.actions.size()).append(")").append("   ");
            }
            tv.setText(sb.toString().trim());
        }
    }

    private void showMacros() {
        java.util.List<ChoiceDialog.Row> rows = new java.util.ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Макросы")));
        rows.add(ChoiceDialog.Row.item(L.s("＋  Новый макрос"), L.s("имя + действия"), "@new"));
        for (Macros.Macro m : Macros.list(this)) {
            rows.add(ChoiceDialog.Row.item(m.name,
                    android.text.TextUtils.join(", ", m.actions), m.name));
        }
        rows.add(ChoiceDialog.Row.header(L.s("Действия")));
        rows.add(ChoiceDialog.Row.item(L.s("🗑  Удалить макрос…"), null, "@del"));
        ChoiceDialog.show(this, L.s("Макросы"), L.s("Поиск…"), rows, null, null, row -> {
            if ("@new".equals(row.value)) editMacro(null);
            else if ("@del".equals(row.value)) deleteMacro();
            else editMacro(row.value);
        });
    }

    private void deleteMacro() {
        java.util.List<ChoiceDialog.Row> rows = new java.util.ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Удалить макрос")));
        for (Macros.Macro m : Macros.list(this)) {
            rows.add(ChoiceDialog.Row.item(m.name,
                    android.text.TextUtils.join(", ", m.actions), m.name));
        }
        ChoiceDialog.show(this, L.s("Удаление"), L.s("Поиск…"), rows, null, null, row -> {
            Macros.delete(this, row.value);
            renderMacros();
        });
    }

    private void editMacro(String name) {
        Macros.Macro existing = null;
        for (Macros.Macro m : Macros.list(this)) {
            if (m.name.equals(name)) { existing = m; break; }
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        EditText nameEt = new EditText(this);
        nameEt.setHint(L.s("Название, напр. «Домой»"));
        nameEt.setSingleLine(true);
        if (existing != null) nameEt.setText(existing.name);
        box.addView(nameEt);

        EditText actionsEt = new EditText(this);
        actionsEt.setHint("open_app:youtube\nvolume:60\ntorch:on");
        actionsEt.setMinLines(4);
        actionsEt.setGravity(android.view.Gravity.TOP);
        actionsEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) {
            actionsEt.setText(android.text.TextUtils.join("\n", existing.actions));
        }
        box.addView(actionsEt);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(existing == null ? L.s("Новый макрос") : L.s("Макрос"))
                .setView(box)
                .setPositiveButton(L.s("Сохранить"), (d, w) -> {
                    java.util.List<String> actions = new java.util.ArrayList<>();
                    for (String line : actionsEt.getText().toString().split("\n")) {
                        if (!line.trim().isEmpty()) actions.add(line.trim());
                    }
                    Macros.save(this, nameEt.getText().toString(), actions);
                    renderMacros();
                })
                .setNegativeButton(L.s("Отмена"), null)
                .show();
    }

    /** честный список того, что Нэксус реально может на этом телефоне */
    private void showCapabilities() {
        StringBuilder sb = new StringBuilder();
        sb.append(mark(perm(Manifest.permission.RECORD_AUDIO))).append(L.s(" Микрофон (слушать)\n"));
        // Звонок и SMS приложение не выполняет само: открывает звонилку или
        // редактор с готовым текстом, отправляет человек. Разрешения не нужны.
        sb.append("✓").append(L.s(" Звонки и SMS: только звонилка/редактор, отправляешь ты\n"));
        sb.append(mark(perm(Manifest.permission.READ_CONTACTS))).append(L.s(" Контакты\n"));
        sb.append(mark(perm(Manifest.permission.ACCESS_FINE_LOCATION))).append(L.s(" Геолокация\n"));
        sb.append(mark(perm(Manifest.permission.POST_NOTIFICATIONS))).append(L.s(" Свои уведомления\n"));
        sb.append(mark(notificationAccessGranted())).append(L.s(" Чтение уведомлений\n"));
        sb.append(mark(android.speech.SpeechRecognizer.isRecognitionAvailable(this)))
                .append(L.s(" Системное распознавание речи\n"));
        sb.append(mark(ttsAvailable())).append(L.s(" Озвучка (движок TTS)\n"));
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        sb.append(mark(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())))
                .append(L.s(" Без ограничений батареи\n"));
        sb.append(mark(overlayGranted())).append(L.s(" Поверх других приложений (чтобы панель открывалась по слову из фона)\n"));
        sb.append(L.s("\nГалочка — работает. Крестик — нет: нажми «Разрешить всё» ")
                + L.s("или включи нужное в настройках телефона."));

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.s("Доступ к телефону"))
                .setMessage(sb.toString())
                .setPositiveButton(L.s("Разрешить всё"), (d2, w) -> requestPerms())
                .setNeutralButton(L.s("Поверх окон"), (d2, w) -> askOverlay())
                .setNegativeButton(L.s("Закрыть"), null)
                .show();
    }

    private boolean overlayGranted() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return false;
        }
    }

    private void askOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            catch (Throwable ignored) { }
        }
    }

    private boolean perm(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ttsAvailable() {
        Boolean known = TtsProbe.known();
        if (known != null) return known;
        // пока не знаем — не пугаем крестиком и не вешаем экран
        try { TtsProbe.start(this); } catch (Throwable ignored) {}
        return true;
    }

    private String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private void requestPerms() {
        String[] perms = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) ActivityCompat.requestPermissions(this, perms, PERMS);
    }

    private void testLlm() {
        String key = SettingsStore.key(this).trim();
        if (SettingsStore.url(this).trim().isEmpty()) {
            Toast.makeText(this, L.s("Сначала выбери провайдера"), Toast.LENGTH_LONG).show();
            pickProvider();
            return;
        }
        if (key.isEmpty()) {
            Toast.makeText(this, L.s("Вставь API-ключ ниже и нажми «Сохранить»"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (SettingsStore.model(this).trim().isEmpty()) {
            if (catalog.isEmpty()) {
                Toast.makeText(this, L.s("Загружаю модели, потом проверю…"), Toast.LENGTH_SHORT).show();
                loadModels();
                return;
            }
            autoPickModels(catalog);
            if (SettingsStore.model(this).trim().isEmpty()) {
                Toast.makeText(this, L.s("Выбери модель в поле «Главная модель»"),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        Toast.makeText(this, L.s("Проверяю…"), Toast.LENGTH_SHORT).show();
        LlmClient.chat(SettingsStore.url(this), key, SettingsStore.model(this),
                L.s("Ответь ровно одним словом: работает?"),
                new ArrayList<>(),
                (text, err) -> {
                    if (err == null) {
                        Toast.makeText(this, L.s("Работает. Ответ модели: ") + text,
                                Toast.LENGTH_LONG).show();
                    } else {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(L.s("Провайдер ответил ошибкой"))
                                .setMessage(err)
                                .setPositiveButton(L.s("Понятно"), null)
                                .setNeutralButton(L.s("Скопировать"), (x, y) -> {
                                    android.content.ClipboardManager cm =
                                            (android.content.ClipboardManager)
                                                    getSystemService(CLIPBOARD_SERVICE);
                                    if (cm != null) cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("error", err));
                                    Toast.makeText(this, L.s("Скопировано"), Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    }
                });
    }

    private void refreshStatuses() {
        String version = "";
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            version = "v" + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        boolean stt = android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        voskStatus.setText(version + L.s("  ·  распознавание: ") + (stt ? L.s("системное") : L.s("недоступно")));
        voskStatus.setTextColor(stt ? Ui.color(this, R.attr.nxTextDim) : Ui.color(this, R.attr.nxWarn));
        spendView.setText(CostTracker.todaySummary(this));
        renderTalkWindow();
        renderCloud();
        renderWakeLive();
        renderVoiceShape();
        renderAssistantState();
        showCrash();
    }

    private String talkWindowLabel(int sec) {
        if (sec <= 0) return L.s("Закрывать сразу после ответа");
        if (sec < 60) return L.s("Ждать продолжения ") + sec + L.s(" сек");
        return L.s("Ждать продолжения ") + (sec / 60) + L.s(" мин");
    }

    private boolean liveTick = true;
    private final Runnable liveTickRun = new Runnable() {
        @Override public void run() {
            if (!liveTick || isFinishing() || isDestroyed()) return;
            renderWakeLiveNow();
            findViewById(R.id.wakeLive).postDelayed(liveTickRun, 2000);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        liveTick = false;
        findViewById(R.id.wakeLive).removeCallbacks(liveTickRun);
        // сохраняем копию настроек перед уходом из экрана
        try { Backup.save(this); } catch (Throwable ignored) {}
    }

    /** живое состояние прослушки, чтобы не гадать «включено или нет» */
    private void renderWakeLive() {
        if (SettingsStore.wakeEnabled(this) && !WakeService.isRunningNow(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            WakeService.start(this);
        }
        TextView tv = findViewById(R.id.wakeLive);
        if (tv == null) return;
        String err = SettingsStore.p(this).getString("wakeErr", "");
        boolean running = WakeService.isRunningNow(this);
        if (running && err != null && !err.isEmpty()) {
            tv.setText(L.s("Прослушка: работает, но ") + err);
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (running) {
            tv.setText(L.s("Прослушка: работает · скажи «нэксус»"));
            tv.setTextColor(Ui.color(this, R.attr.nxOk));
        } else {
            tv.setText(L.s("Прослушка: остановлена"));
            tv.setTextColor(Ui.color(this, R.attr.nxTextDim));
        }
        wakeLiveTick();
    }

    private void wakeLiveTick() {
        findViewById(R.id.wakeLive).removeCallbacks(liveTickRun);
        findViewById(R.id.wakeLive).postDelayed(liveTickRun, 2000);
    }

    private void renderWakeLiveNow() {
        TextView tv = findViewById(R.id.wakeLive);
        if (tv == null) return;
        boolean running = WakeService.isRunningNow(this);
        String err = SettingsStore.p(this).getString("wakeErr", "");
        if (SettingsStore.wakeEnabled(this) && !overlayGranted()) {
            tv.setText(L.s("Прослушка работает, но панель не откроется без «Поверх других приложений»"));
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
            return;
        }
        if (running && err != null && !err.isEmpty()) {
            tv.setText(L.s("Прослушка: работает, но ") + err);
            tv.setTextColor(Ui.color(this, R.attr.nxWarn));
        } else if (running) {
            tv.setText(L.s("Прослушка: работает · скажи «нэксус»"));
            tv.setTextColor(Ui.color(this, R.attr.nxOk));
        } else {
            tv.setText(L.s("Прослушка: остановлена"));
            tv.setTextColor(Ui.color(this, R.attr.nxTextDim));
        }
    }

    private void renderVoiceShape() {
        String voice = SettingsStore.ttsVoice(this);
        String cloud = CloudTts.provider(this);
        // v1.9.4: видно не только выбор, но и кто реально говорит
        String label;
        if (voice.isEmpty()) {
            label = cloud.isEmpty() ? L.s("Авто (системный)") : L.s("Облачный: ") + CloudTts.voice(this);
        } else {
            label = voice + (cloud.isEmpty() ? L.s(" · движок телефона") : L.s(" · облако перебивает!"));
        }
        voiceSel.setText(label);
        int rate = SettingsStore.ttsRate(this);
        rateSel.setText(rate == 100 ? L.s("Обычная") : rate < 100 ? L.s("Медленнее (") + rate + "%)"
                : L.s("Быстрее (") + rate + "%)");
        int pitch = SettingsStore.ttsPitch(this);
        pitchSel.setText(pitch == 100 ? L.s("Обычный") : pitch < 100 ? L.s("Ниже (") + pitch + "%)"
                : L.s("Выше (") + pitch + "%)");
    }

    private void pickVoice() {
        Toast.makeText(this, L.s("Смотрю установленные голоса…"), Toast.LENGTH_SHORT).show();
        VoiceCatalog.fetch(this, SettingsStore.ttsEngine(this), (voices, error) -> runOnUiThread(() -> {
            if (voices == null || voices.isEmpty()) {
                Toast.makeText(this, error != null ? error
                        : L.s("Русских голосов не нашлось — установи «Речевые службы Google»"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            List<ChoiceDialog.Row> rows = new ArrayList<>();
            rows.add(ChoiceDialog.Row.header(L.s("Голоса")));
            rows.add(SettingsStore.ttsVoice(this).isEmpty()
                    ? ChoiceDialog.Row.checked(L.s("Авто (системный)"), null, "")
                    : ChoiceDialog.Row.item(L.s("Авто (системный)"), null, ""));
            for (String[] v : voices) {
                boolean cur = v[0].equals(SettingsStore.ttsVoice(this));
                rows.add(cur ? ChoiceDialog.Row.checked(v[1], null, v[0])
                        : ChoiceDialog.Row.item(v[1], null, v[0]));
            }
            ChoiceDialog.show(this, L.s("Голос озвучки"), L.s("Поиск…"), rows, null, null, row -> {
                SettingsStore.setVoiceShape(this, SettingsStore.ttsRate(this),
                        SettingsStore.ttsPitch(this), row.value);
                // v1.9.3: выбранный голос движка не слышен, пока включён облачный
                // синтез — он говорит своим голосом. Выбор местного голоса = выбор
                // движка телефона, поэтому облако выключаем сами.
                if (row.value != null && !row.value.isEmpty() && !CloudTts.provider(this).isEmpty()) {
                    cloudValue = "";
                    SettingsStore.p(this).edit()
                            .putString("ttsCloud", "")
                            .putString("ttsCloudVoice", "")
                            .apply();
                    renderCloud();
                    Toast.makeText(this, L.s("Облачный голос выключен — говорит выбранный голос движка"),
                            Toast.LENGTH_LONG).show();
                }
                renderVoiceShape();
            });
        }));
    }

    private void pickRate() {
        int[] values = {80, 90, 100, 110, 125};
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Скорость речи")));
        for (int v : values) {
            String label = v == 100 ? L.s("Обычная") : v < 100 ? L.s("Медленнее ") + v + "%" : L.s("Быстрее ") + v + "%";
            boolean cur = SettingsStore.ttsRate(this) == v;
            rows.add(cur ? ChoiceDialog.Row.checked(label, null, String.valueOf(v))
                    : ChoiceDialog.Row.item(label, null, String.valueOf(v)));
        }
        ChoiceDialog.show(this, L.s("Скорость речи"), L.s("Поиск…"), rows, null, null, row ->
                SettingsStore.setVoiceShape(this, Integer.parseInt(row.value),
                        SettingsStore.ttsPitch(this), SettingsStore.ttsVoice(this)));
        // отрисовка после выбора
        rateSel.postDelayed(this::renderVoiceShape, 400);
    }

    private void pickPitch() {
        int[] values = {85, 100, 115};
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("Тон голоса")));
        for (int v : values) {
            String label = v == 100 ? L.s("Обычный") : v < 100 ? L.s("Ниже ") + v + "%" : L.s("Выше ") + v + "%";
            boolean cur = SettingsStore.ttsPitch(this) == v;
            rows.add(cur ? ChoiceDialog.Row.checked(label, null, String.valueOf(v))
                    : ChoiceDialog.Row.item(label, null, String.valueOf(v)));
        }
        ChoiceDialog.show(this, L.s("Тон голоса"), L.s("Поиск…"), rows, null, null, row ->
                SettingsStore.setVoiceShape(this, SettingsStore.ttsRate(this),
                        Integer.parseInt(row.value), SettingsStore.ttsVoice(this)));
        pitchSel.postDelayed(this::renderVoiceShape, 400);
    }

    private void renderTalkWindow() {
        if (talkWindowSel != null) talkWindowSel.setText(talkWindowLabel(SettingsStore.talkWindow(this)));
    }

    private void pickTalkWindow() {
        int[] options = {0, 10, 20, 30, 60};
        List<ChoiceDialog.Row> rows = new ArrayList<>();
        rows.add(ChoiceDialog.Row.header(L.s("После ответа")));
        for (int sec : options) {
            boolean cur = SettingsStore.talkWindow(this) == sec;
            rows.add(cur
                    ? ChoiceDialog.Row.checked(talkWindowLabel(sec), null, String.valueOf(sec))
                    : ChoiceDialog.Row.item(talkWindowLabel(sec), null, String.valueOf(sec)));
        }
        ChoiceDialog.show(this, L.s("Окно разговора"), L.s("Поиск…"), rows, null, null, row -> {
            int sec = Integer.parseInt(row.value);
            SettingsStore.setTalkWindow(this, sec);
            renderTalkWindow();
        });
    }

    private void showCrash() {
        java.io.File crash = new java.io.File(getFilesDir(), "crash.log");
        TextView cv = findViewById(R.id.crashView);
        if (crash.exists() && crash.length() > 0) {
            String tail = "";
            try {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(crash, "r");
                long skip = Math.max(0, raf.length() - 600);
                raf.seek(skip);
                byte[] b = new byte[(int) (raf.length() - skip)];
                raf.readFully(b);
                tail = new String(b);
            } catch (Exception ignored) {
            }
            cv.setVisibility(View.VISIBLE);
            cv.setText(L.s("⚠ Последний краш:\n") + tail + L.s("\n(тап — очистить)"));
            cv.setOnClickListener(v2 -> {
                crash.delete();
                cv.setVisibility(View.GONE);
                Toast.makeText(this, L.s("Лог очищен"), Toast.LENGTH_SHORT).show();
            });
        } else {
            cv.setVisibility(View.GONE);
        }
    }

    private void load() {
        urlEt.setText(SettingsStore.url(this));
        keyEt.setText(SettingsStore.key(this));
        systemEt.setText(SettingsStore.system(this));

        modelValue = SettingsStore.model(this);
        textFbValue = SettingsStore.textFallback(this);
        visionValue = SettingsStore.visionModel(this);
        visionFbValue = SettingsStore.visionFallback(this);
        ttsValue = SettingsStore.ttsEngine(this);
        cloudValue = SettingsStore.p(this).getString("ttsCloud", "");
        cloudVoiceValue = SettingsStore.p(this).getString("ttsCloudVoice", "");

        renderModel(modelSel, modelValue);
        renderModel(textFbSel, textFbValue);
        renderModel(visionSel, visionValue);
        renderModel(visionFbSel, visionFbValue);
        renderTts();
        renderTemplate();

        visionCb.setChecked(SettingsStore.visionEnabled(this));
        wakeCb.setChecked(SettingsStore.wakeEnabled(this));
        keepScreenCb.setChecked(SettingsStore.keepScreenOn(this));
        autoUpdateCb.setChecked(SettingsStore.autoUpdate(this));
        userInfoEt.setText(SettingsStore.userInfo(this));

        ((android.widget.CheckBox) findViewById(R.id.announceNotifCb))
                .setChecked(SettingsStore.announceNotifications(this));
        ((android.widget.CheckBox) findViewById(R.id.announceCallsCb))
                .setChecked(SettingsStore.announceCalls(this));
        ((android.widget.CheckBox) findViewById(R.id.callAnswerCb))
                .setChecked(SettingsStore.callAnswer(this));
        ((android.widget.CheckBox) findViewById(R.id.amoledCb))
                .setChecked(SettingsStore.amoled(this));
        ((android.widget.CheckBox) findViewById(R.id.announceQuietCb))
                .setChecked(SettingsStore.announceQuiet(this));
        renderTalkWindow();
        renderMacros();
        ((EditText) findViewById(R.id.updateUrlEt)).setText(SettingsStore.updateUrl(this));
    }

    // -------------------------------------------------------- export/import

    private void exportSettings() {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            // ключи в файл не пишем: раньше экспорт молча выносил их наружу
            j.put("url", SettingsStore.url(this));
            j.put("model", modelValue);
            j.put("visionModel", visionValue);
            j.put("textFallback", textFbValue);
            j.put("visionFallback", visionFbValue);
            j.put("visionEnabled", SettingsStore.visionEnabled(this));
            j.put("system", SettingsStore.system(this));
            j.put("promptName", PromptTemplates.selectedName(this));
            j.put("ttsEngine", SettingsStore.ttsEngine(this));
            j.put("voiceVolume", SettingsStore.voiceVolume(this));
            j.put("keepScreenOn", SettingsStore.keepScreenOn(this));
            j.put("userPrompts", SettingsStore.p(this).getString("userPrompts", "[]"));
            java.io.File f = new java.io.File(getFilesDir(), "export.json");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                o.write(j.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".update", f);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,
                    L.s("Куда сохранить настройки (без API-ключей)")));
        } catch (Exception e) {
            Toast.makeText(this, L.s("Ошибка экспорта: ") + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESTORE_RC) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            String report = Backup.restoreFrom(this, data.getData());
            Toast.makeText(this, report, Toast.LENGTH_LONG).show();
            recreate();
            return;
        }
        if (requestCode == IMPORT_RC && resultCode == RESULT_OK && data != null) {
            try {
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                     java.io.BufferedReader r = new java.io.BufferedReader(
                             new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                SettingsStore.p(this).edit()
                        .putString("url", j.optString("url", SettingsStore.url(this)))
                        .putString("key", j.optString("key", SettingsStore.key(this)))
                        .putString("model", j.optString("model", SettingsStore.model(this)))
                        .putString("visionModel", j.optString("visionModel", SettingsStore.visionModel(this)))
                        .putString("textFallback", j.optString("textFallback", ""))
                        .putString("visionFallback", j.optString("visionFallback", ""))
                        .putBoolean("visionEnabled", j.optBoolean("visionEnabled", true))
                        .putString("system", j.optString("system", ""))
                        .putString("promptName", j.optString("promptName", PromptTemplates.DEFAULT_NAME))
                        .putString("ttsEngine", j.optString("ttsEngine", ""))
                        .putInt("voiceVolume", j.optInt("voiceVolume", 100))
                        .putBoolean("keepScreenOn", j.optBoolean("keepScreenOn", false))
                        .putString("userPrompts", j.optString("userPrompts", "[]"))
                        .apply();
                Toast.makeText(this, L.s("Настройки импортированы"), Toast.LENGTH_SHORT).show();
                recreate();
            } catch (Exception e) {
                Toast.makeText(this, L.s("Ошибка импорта: ") + e, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (sttTimeout != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(sttTimeout);
            sttTimeout = null;
        }
        if (testSpeaker != null) {
            try { testSpeaker.shutdown(); } catch (Throwable ignored) {}
            testSpeaker = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        liveTick = true;
        findViewById(R.id.wakeLive).removeCallbacks(liveTickRun);
        refreshStatuses();
    }
}
