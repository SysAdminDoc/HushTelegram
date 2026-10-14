/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb933 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtelegram.extension.telegram.settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.shared.settings.preference.QuickSetups;

/**
 * Which switches each Quick setups choice changes, all in one place.
 *
 * <p>Quiet and Privacy only turn switches on, and only switches whose effect is plain from their
 * name and takes nothing away that someone would miss. Left out on purpose: Hide folder counters
 * and Hide the translate bar, which hide things people read; Reaction effects off, which some
 * like; Open links in your browser, which changes how every link opens; Keep drafts on this phone,
 * which stops drafts following you to your other devices; Search only my chats, which takes the
 * public tabs out of search; No automatic sign-in on Telegram's sites,
 * which has you sign in there yourself; and Beta logs off, which needs a restart
 * and only matters on the beta. Back to defaults puts every switch a settings file
 * carries back the way it started.
 */
final class QuickSetupKeys {
    /** Fewer ads, suggestions, stories, promotions and nags. */
    static final List<BooleanSetting> QUIET = Collections.unmodifiableList(Arrays.asList(
            Settings.HIDE_ADS,
            Settings.HIDE_COMMERCE,
            Settings.HIDE_PROMOTIONAL_BANNERS,
            Settings.HIDE_SPONSORED_PROXY,
            Settings.HIDE_POPULAR_APPS,
            Settings.HIDE_RECOMMENDATIONS,
            Settings.HIDE_STORIES,
            Settings.HIDE_CONTACTS_BLOCK,
            Settings.HIDE_GREETING_STICKERS,
            Settings.HIDE_FEATURES_AND_INVITE,
            Settings.QUIET_CONTACTS_NAG,
            Settings.DISABLE_UPDATE_CHECKS));

    /**
     * Less sent or shown about you: usage reports, call diagnostics, previews of links not sent
     * yet, tracking in links, the camera before you tap it, your own phone number on screen and the
     * box that shares it with a new contact, which you can still tick.
     */
    static final List<BooleanSetting> PRIVACY = Collections.unmodifiableList(Arrays.asList(
            Settings.DISABLE_ANALYTICS,
            Settings.DISABLE_CALL_DEBUG,
            Settings.DISABLE_DRAFT_PREVIEWS,
            Settings.GALLERY_CAMERA_ON_TAP,
            Settings.STRIP_LINK_TRACKING,
            Settings.HIDE_PHONE_NUMBER,
            Settings.SHARE_NUMBER_UNTICKED));

    /**
     * Every feature switch, the same list a settings file carries: Pause, debug, the release check,
     * UnifiedPush and chat type folders stay as they are.
     */
    static final List<BooleanSetting> DEFAULTS = SettingsBackup.ALLOWLIST;

    private QuickSetupKeys() {
    }

    static Map<BooleanSetting, Boolean> quiet() {
        return QuickSetups.allOn(QUIET);
    }

    static Map<BooleanSetting, Boolean> privacy() {
        return QuickSetups.allOn(PRIVACY);
    }

    static Map<BooleanSetting, Boolean> defaults() {
        return QuickSetups.defaults(DEFAULTS);
    }
}
