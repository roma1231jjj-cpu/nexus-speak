package com.local.aivoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;

/** Результат установки через PackageInstaller-сессию. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // система просит подтверждение — показываем её диалог
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (Throwable t) {
                    Log.crash(c, t);
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // обновление поставлено — скачанный APK больше не нужен
            finishPending(c);
            Toast.makeText(c, L.s("Обновление установлено"), Toast.LENGTH_SHORT).show();
        } else {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            // сессия закончилась отказом — не оставляем «вечное» обновление на повтор
            finishPending(c);
            Toast.makeText(c, L.s("Установка не удалась: ") + (msg == null ? status : msg),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** снимает ожидание установки и убирает скачанный файл, чтобы не занимать память */
    private static void finishPending(Context c) {
        try {
            String path = SettingsStore.p(c).getString(UpdateChecker.PENDING, "");
            SettingsStore.p(c).edit().remove(UpdateChecker.PENDING).commit();
            File f = (path == null || path.isEmpty())
                    ? new File(c.getFilesDir(), "update.apk") : new File(path);
            if (f.exists() && f.getParentFile() != null
                    && (f.getParentFile().equals(c.getFilesDir())
                    || f.getParentFile().equals(c.getCacheDir()))) {
                // удаляем только наш файл обновления, а не что угодно из preferences
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
