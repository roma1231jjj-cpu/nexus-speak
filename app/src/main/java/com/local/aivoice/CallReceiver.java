package com.local.aivoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

/** Announces incoming calls by contact name (when the user enabled it). */
public class CallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                String who = resolve(context, number);
                Announcer.onCall(context, who);
                AutoAnswer.onRinging(context);
                return;
            }
            AutoAnswer.onIdleOrOffhook(context);
        } catch (Throwable ignored) {
        }
    }

    private String resolve(Context c, String number) {
        if (number == null || number.isEmpty()) return L.s("неизвестный номер");
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            Cursor cur = c.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        String name = cur.getString(0);
                        if (name != null && !name.isEmpty()) return name;
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return number;
    }
}
