package com.local.aivoice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Searchable picker dialog with section headers and optional extra action.
 * Used for model selection, prompt templates and any other "choose instead of
 * type" field.
 */
public final class ChoiceDialog {

    public static class Row {
        final String title, subtitle, value;
        final boolean header;
        final boolean checked;
        final int tint;

        private Row(String title, String subtitle, String value,
                    boolean header, boolean checked, int tint) {
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.header = header;
            this.checked = checked;
            this.tint = tint;
        }

        public static Row header(String title) {
            return new Row(title, null, null, true, false, 0);
        }

        public static Row item(String title, String subtitle, String value) {
            return new Row(title, subtitle, value, false, false, 0);
        }

        public static Row checked(String title, String subtitle, String value) {
            return new Row(title, subtitle, value, false, true, 0);
        }

        public static Row itemTinted(String title, String subtitle, String value, int tint) {
            return new Row(title, subtitle, value, false, false, tint);
        }
    }

    public interface Pick { void onPick(Row row); }

    public static void show(Activity a, String title, String hint, List<Row> rows,
                            String extraLabel, Runnable extra, Pick pick) {
        Context ctx = a;
        int textMain = Ui.color(ctx, R.attr.nxTextMain);
        int textDim = Ui.color(ctx, R.attr.nxTextDim);
        int primary = Ui.color(ctx, R.attr.nxPrimary);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 14);
        root.setPadding(pad, pad, pad, dp(ctx, 4));

        final List<Row> all = new ArrayList<>(rows);
        final List<Row> shown = new ArrayList<>(rows);

        final EditText search = new EditText(ctx);
        search.setHint(hint);
        search.setSingleLine(true);
        search.setTextColor(textMain);
        search.setHintTextColor(textDim);
        search.setBackgroundResource(R.drawable.glass_field);
        search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        search.setTextSize(15f);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView list = new ListView(ctx);
        list.setDivider(null);
        list.setDividerHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 400));
        lp.topMargin = dp(ctx, 8);
        root.addView(list, lp);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Row getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return !shown.get(i).header; }

            @Override public View getView(int i, View cv, ViewGroup parent) {
                Row r = shown.get(i);
                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(ctx, 12), dp(ctx, r.header ? 16 : 10),
                        dp(ctx, 12), dp(ctx, 10));

                TextView t = new TextView(ctx);
                t.setText((r.checked ? "✓  " : "") + r.title);
                t.setTextSize(r.header ? 13f : 16f);
                t.setTextColor(r.header ? textDim : (r.tint != 0 ? r.tint : textMain));
                if (r.header) t.setTypeface(Typeface.DEFAULT_BOLD);
                if (r.checked) t.setTextColor(primary);
                box.addView(t);

                if (r.subtitle != null && !r.subtitle.isEmpty()) {
                    TextView s = new TextView(ctx);
                    s.setText(r.subtitle);
                    s.setTextSize(12.5f);
                    s.setTextColor(textDim);
                    s.setPadding(0, dp(ctx, 2), 0, 0);
                    box.addView(s);
                }
                return box;
            }
        };
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                shown.clear();
                if (q.isEmpty()) {
                    shown.addAll(all);
                } else {
                    for (Row r : all) {
                        if (r.header) continue;
                        if (r.title.toLowerCase(java.util.Locale.ROOT).contains(q)
                                || (r.subtitle != null
                                    && r.subtitle.toLowerCase(java.util.Locale.ROOT).contains(q))
                                || (r.value != null
                                    && r.value.toLowerCase(java.util.Locale.ROOT).contains(q))) {
                            shown.add(r);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(L.s("Отмена"), null);
        if (extraLabel != null && extra != null) {
            b.setNeutralButton(extraLabel, (d, w) -> extra.run());
        }
        final AlertDialog dlg = b.create();
        list.setOnItemClickListener((p, v, pos, id) -> {
            Row r = shown.get(pos);
            if (r.header) return;
            dlg.dismiss();
            pick.onPick(r);
        });
        dlg.show();
        // Material делает кнопки КАПСОМ — в русском и английском это выглядит грубо
        try {
            for (int id : new int[]{android.R.id.button1, android.R.id.button2, android.R.id.button3}) {
                android.widget.Button btn = dlg.findViewById(id);
                if (btn != null) btn.setAllCaps(false);
            }
        } catch (Throwable ignored) {
        }
        // без своего фона системный диалог выглядит серым прямоугольником —
        // не в стиле тёмного стекла, из которого сделан весь интерфейс
        try {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(R.drawable.dialog_bg);
                android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
                // фиксированная высота: у ListView «wrap_content» растягивался на
                // весь экран и под списком оставалась пустая плита
                w.setLayout((int) (dm.widthPixels * 0.92f), (int) (dm.heightPixels * 0.7f));
            }
        } catch (Throwable ignored) {
        }
    }

    /** two-line "field" look used for selector rows in settings */
    public static void styleSelector(TextView v) {
        v.setClickable(true);
        v.setFocusable(false);
        v.setGravity(Gravity.CENTER_VERTICAL);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private ChoiceDialog() {}
}
