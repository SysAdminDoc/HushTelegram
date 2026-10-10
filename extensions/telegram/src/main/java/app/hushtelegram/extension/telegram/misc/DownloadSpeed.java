/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * How big the pieces of a download are.
 *
 * <p>FileLoadOperation picks its piece size each time a download starts, in {@code updateParams}:
 * 512 KB pieces, 8 requests at a time, when the file has a preload prefix or the server set
 * {@code getfile_experimental_params} for the account, and 128 KB pieces, 4 at a time, otherwise.
 * Either way {@code forceSmallChunk}, which Telegram sets after a big piece fails, sends it back to
 * 128 KB. That choice isn't tied to Premium; it's the server's flag.
 *
 * <p>The patch passes the flag Telegram read through {@link #fast} first. With the switch on the
 * answer is yes, so every download takes the big pieces, and {@code forceSmallChunk} still wins
 * afterwards. Telegram's servers accept 512 KB pieces from any account. Any speed cap the servers
 * put on an account stays theirs.
 */
public final class DownloadSpeed {
    private DownloadSpeed() {}

    /**
     * Injected right after updateParams reads the server's flag. Runs when a download is created
     * and each time it starts.
     *
     * @param flagged whether the server already picked big pieces for this account
     * @return whether this download takes the big pieces, before Telegram's small-piece fallback
     */
    public static boolean fast(boolean flagged) {
        HookStatus.invoked(FamilyNames.FASTER_DOWNLOADS);
        if (flagged) return true;
        try {
            if (!Utils.settingsReady() || !Settings.FASTER_DOWNLOADS.get()) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FASTER_DOWNLOADS, "switch", failure);
            return false;
        }
        HookStatus.counted(FamilyNames.FASTER_DOWNLOADS, "download in big pieces");
        return true;
    }
}
