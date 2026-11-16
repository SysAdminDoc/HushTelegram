/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.util.SparseIntArray;
import android.view.View;
import android.widget.FrameLayout;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The picture behind the lists (#88): which of a theme's colors go see-through, by how much, and
 * that every way of turning it off gives the theme its own colors back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ListBackgroundTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stand-in ids for windowBackgroundWhite, windowBackgroundGray and graySection, then two keys that stay. */
    private static final int WHITE = 1, GRAY = 2, SECTION = 3, ACTION_BAR = 4, BUBBLE = 5;
    private static final int DAY_WHITE = 0xFFFFFFFF, DAY_GRAY = 0xFFF0F0F0, DAY_SECTION = 0xFFF2F3F5;

    private Context context;
    private SparseIntArray current;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HookStatus.clear();
        ListBackground.ids = new int[]{WHITE, GRAY, SECTION};
        ListBackground.tables = () -> current;
    }

    @After public void tearDown() {
        PauseForTests.resume();
        Settings.LIST_BACKGROUND.resetToDefault();
        Settings.LIST_BACKGROUND_DIM.resetToDefault();
        ListBackgroundForTests.forget(context);
        HookStatus.clear();
    }

    /** Day's colors as Telegram reads them from the built-in asset, which the black theme hook hands on. */
    private SparseIntArray builtInDay() {
        SparseIntArray day = day();
        BlackTheme.loaded("day.attheme", day);
        return day;
    }

    private static SparseIntArray day() {
        SparseIntArray day = new SparseIntArray();
        day.put(WHITE, DAY_WHITE);
        day.put(GRAY, DAY_GRAY);
        day.put(SECTION, DAY_SECTION);
        day.put(ACTION_BAR, 0xFF527DA3);
        day.put(BUBBLE, 0xFFEFFFDE);
        return day;
    }

    private void on() {
        Settings.LIST_BACKGROUND.save(true);
        ListBackgroundForTests.keepPicture(context);
    }

    @Test public void offByDefaultAndWithoutAPictureTheColorsStayTelegrams() {
        assertFalse(Settings.LIST_BACKGROUND.get());
        assertEquals(40, (int) Settings.LIST_BACKGROUND_DIM.get());
        current = builtInDay();
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());

        // The switch on with no picture saved yet changes nothing either.
        Settings.LIST_BACKGROUND.save(true);
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());
        // And a picture with the switch off.
        Settings.LIST_BACKGROUND.save(false);
        ListBackgroundForTests.keepPicture(context);
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());
        assertFalse(String.join("\n", HookStatus.report()).contains("see-through"));
    }

    @Test public void onWithAPictureThePageColorsGoSeeThroughAndNothingElseChanges() {
        on();
        current = builtInDay();
        ListBackground.refreshing();
        // Same color, a share of its opacity: rows keep 0x66, the gray between them 0x26, headers 0x80.
        assertEquals(0x66FFFFFF, current.get(WHITE));
        assertEquals(0x26F0F0F0, current.get(GRAY));
        assertEquals(0x80F2F3F5, current.get(SECTION));
        assertEquals(0xFF527DA3, current.get(ACTION_BAR));
        assertEquals(0xFFEFFFDE, current.get(BUBBLE));
        assertEquals(day().size(), current.size());
        assertTrue(String.join("\n", HookStatus.report()).contains("lists turned see-through over the picture 1"));
        // The backdrop paints the theme's own page color, not the see-through one.
        assertEquals(DAY_WHITE, ListBackground.stockColor(current, 0));
    }

    @Test public void workingTheColorsOutAgainDoesNotThinThemTwice() {
        on();
        current = builtInDay();
        ListBackground.refreshing();
        ListBackground.refreshing();
        ListBackground.refreshing();
        assertEquals(0x66FFFFFF, current.get(WHITE));
        assertEquals(0x26F0F0F0, current.get(GRAY));
        assertEquals(DAY_WHITE, ListBackground.stockColor(current, 0));
    }

    @Test public void turningItOffOrRemovingThePictureGivesTheThemeItsColorsBack() {
        on();
        current = builtInDay();
        ListBackground.refreshing();
        Settings.LIST_BACKGROUND.save(false);
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());

        Settings.LIST_BACKGROUND.save(true);
        ListBackground.refreshing();
        assertEquals(0x66FFFFFF, current.get(WHITE));
        SavedPicture.remove(context, SavedPicture.LIST_BACKGROUND);
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());
    }

    @Test public void pausedTelegramGetsItsOwnColorsBackAndKeepsThem() {
        on();
        current = builtInDay();
        ListBackground.refreshing();
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        ListBackground.refreshing();
        assertEquals(day().toString(), current.toString());

        SparseIntArray fresh = builtInDay();
        current = fresh;
        ListBackground.refreshing();
        assertEquals(day().toString(), fresh.toString());
    }

    @Test public void aThemeInstalledFromAFileKeepsItsColors() {
        on();
        SparseIntArray file = day();
        BlackTheme.loaded(null, file);
        current = file;
        ListBackground.refreshing();
        assertEquals(day().toString(), file.toString());
        assertFalse(String.join("\n", HookStatus.report()).contains("see-through"));
    }

    /**
     * Telegram's Classic theme has no file: its set starts empty and every key takes Telegram's
     * default. The page keys go in see-through, and come out again when it's turned off.
     */
    @Test public void theClassicThemeGetsSeeThroughPagesAndLosesThemAgain() {
        on();
        current = new SparseIntArray();
        ListBackground.refreshing();
        assertEquals(3, current.size());
        assertTrue(current.indexOfKey(WHITE) >= 0);
        // Unpatched, Telegram's default answers 0, which stays 0 with any opacity.
        assertEquals(0, current.get(WHITE, 1));
        Settings.LIST_BACKGROUND.save(false);
        ListBackground.refreshing();
        assertEquals(0, current.size());
    }

    @Test public void aColorTelegramChangedSinceIsLeftAsTelegramSetIt() {
        on();
        current = builtInDay();
        ListBackground.refreshing();
        // The theme editor, say, writes a new row color over the see-through one.
        current.put(WHITE, 0xFF00FF00);
        Settings.LIST_BACKGROUND.save(false);
        ListBackground.refreshing();
        assertEquals(0xFF00FF00, current.get(WHITE));
        assertEquals(DAY_GRAY, current.get(GRAY));
        assertEquals(DAY_SECTION, current.get(SECTION));

        // On again, the color Telegram set is the one kept to put back.
        Settings.LIST_BACKGROUND.save(true);
        ListBackground.refreshing();
        assertEquals(0x6600FF00, current.get(WHITE));
        Settings.LIST_BACKGROUND.save(false);
        ListBackground.refreshing();
        assertEquals(0xFF00FF00, current.get(WHITE));
    }

    @Test public void anUnknownKeyIsSkippedAndNoTableChangesNothing() {
        on();
        ListBackground.ids = new int[]{WHITE, -1, SECTION};
        current = builtInDay();
        ListBackground.refreshing();
        assertEquals(DAY_GRAY, current.get(GRAY));
        assertEquals(0x66FFFFFF, current.get(WHITE));

        current = null;
        ListBackground.refreshing();
        assertFalse(String.join("\n", HookStatus.report()).contains("threw"));
    }

    @Test public void aFailingLookupIsReportedAndTelegramGoesOn() {
        on();
        ListBackground.tables = () -> { throw new IllegalStateException("gone"); };
        ListBackground.refreshing();
        assertTrue(String.join("\n", HookStatus.report()).contains("list background colors"));
    }

    @Test public void theDimRowOffersAFewStepsAndABackupTakesOnlyThose() {
        assertArrayEquals(new int[]{20, 40, 60, 80}, ListBackground.DIM_STEPS);
        assertTrue(ListBackground.dimOffered(40));
        assertTrue(ListBackground.dimOffered(80));
        assertFalse(ListBackground.dimOffered(0));
        assertFalse(ListBackground.dimOffered(50));
        assertFalse(ListBackground.dimOffered(90));
        assertEquals(0x80123456, ListBackground.withAlpha(0xFF123456, 0x80));
        assertEquals(0x40123456, ListBackground.withAlpha(0x80123456, 0x80));
    }

    /** A holder draws the picture only behind the screens it shows, so an empty one shows nothing. */
    @Test public void theBackdropCoversOnlyTheVisibleScreens() {
        FrameLayout holder = new FrameLayout(context);
        holder.layout(0, 0, 1080, 2000);
        Rect area = new Rect();
        assertFalse(ListBackground.Backdrop.covered(holder, area));

        View screen = new View(context);
        holder.addView(screen);
        screen.layout(0, 0, 1080, 2000);
        screen.setTranslationX(300);
        assertTrue(ListBackground.Backdrop.covered(holder, area));
        assertEquals(new Rect(300, 0, 1080, 2000), area);

        screen.setVisibility(View.GONE);
        assertFalse(ListBackground.Backdrop.covered(holder, area));
        assertFalse("a plain view holds no screens", ListBackground.Backdrop.covered(screen, area));
        assertFalse("off any activity's window", ListBackground.Backdrop.onScreen(holder));
    }
}
