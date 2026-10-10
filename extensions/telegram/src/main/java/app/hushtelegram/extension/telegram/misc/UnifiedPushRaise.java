/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * The UnifiedPush spec's RAISE_TO_FOREGROUND service. The app binds it for a few seconds while it
 * hands over a message, which lifts Telegram out of the background long enough to connect. It
 * answers only while signed up; otherwise there's nothing to bind.
 */
public final class UnifiedPushRaise extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return UnifiedPush.raise(this);
    }
}
