/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Takes the UnifiedPush app's broadcasts: a new address, a message, or the end of a sign-up. The
 * patch declares it in the manifest, exported, since the app is another package. A broadcast
 * without this sign-up's token does nothing. See {@link UnifiedPush}.
 */
public final class UnifiedPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        UnifiedPush.receive(context, intent, () -> {
            if (pending != null) pending.finish();
        });
    }
}
