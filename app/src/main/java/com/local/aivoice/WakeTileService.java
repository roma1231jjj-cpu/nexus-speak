package com.local.aivoice;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: «Нексус слушает».
 * Tap = start / stop the always-on wake-word service. State is kept in sync
 * with the service itself, so turning it off from the notification flips the
 * tile too.
 */
public class WakeTileService extends TileService {

    public static volatile boolean listening = false;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean nowOn = !WakeService.isRunningNow(this);
        SettingsStore.p(this).edit().putBoolean("wakeEnabled", nowOn).apply();
        if (nowOn) {
            WakeService.start(this);
        } else {
            WakeService.stop(this);
        }
        listening = WakeService.isRunningNow(this);
        try {
            TileService.requestListeningState(this,
                    new android.content.ComponentName(this, WakeTileService.class));
        } catch (Throwable ignored) {
        }
        updateTile();
    }

    private void updateTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            boolean on = WakeService.isRunningNow(this);
            listening = on;
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(on ? L.s("Нексус слушает") : L.s("Нексус выкл"));
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(on ? L.s("скажи «Нексус»") : L.s("нажми, чтобы включить"));
            }
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_nexus));
            }
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }
}
