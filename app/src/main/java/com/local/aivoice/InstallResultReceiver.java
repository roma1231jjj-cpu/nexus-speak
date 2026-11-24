package com.local.aivoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Результат установки через PackageInstaller-сессию. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // система просит подтверждение — показываем её диалог
            Intent confirm;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            }
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
            Toast.makeText(c, L.s("Обновление установлено"), Toast.LENGTH_SHORT).show();
        } else {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, L.s("Установка не удалась: ") + (msg == null ? status : msg),
                    Toast.LENGTH_LONG).show();
        }
    }
}
