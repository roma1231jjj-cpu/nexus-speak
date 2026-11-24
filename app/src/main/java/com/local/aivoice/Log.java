package com.local.aivoice;

import android.content.Context;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Складывает не-фатальные ошибки в тот же crash.log, что и фатальные. */
public final class Log {
    public static void crash(Context c, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            File f = new File(c.getFilesDir(), "crash.log");
            // держим журнал в разумных рамках: раньше он рос безлимитно
            if (f.exists() && f.length() > 200_000) {
                try (java.io.FileOutputStream cut = new java.io.FileOutputStream(f)) {
                    cut.write(new byte[0]);
                }
            }
            PrintWriter pw = new PrintWriter(new java.io.FileOutputStream(f, true));
            pw.append("[non-fatal] ").append(sw.toString()).append("\n\n");
            pw.close();
        } catch (Throwable ignored) {
        }
    }

    private Log() {}
}
