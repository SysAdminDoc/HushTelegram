/*
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at 108c05a2 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushtelegram.extension.telegram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.concurrent.TimeUnit;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * A long press on the "Telegram" title at the top of the chat list opens the HushTelegram screen.
 * The settings patch hands the chat list's top bar to {@link SettingsEntry#watchChatListTitle}
 * right after Telegram makes its logo the bar's title. These build the bar the way Telegram does:
 * a clickable bar (its tap scrolls the list to the top) holding a title that takes no touches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ChatListTitlePressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();
    private ActivityController<Activity> chats;
    /** Taps Telegram's own click listener on the bar saw. */
    private int taps;
    /** Taps the search button on the right of the bar saw. */
    private int searchTaps;

    @Before public void openChats() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
        chats = Robolectric.buildActivity(Activity.class).setup();
    }

    @After public void closeChats() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        chats.pause().stop().destroy();
    }

    /**
     * The chat list's top bar, in the chat list's activity: Telegram's tap, its title on the left
     * and a search button on the right.
     */
    private FrameLayout bar() {
        return bar(chats.get());
    }

    private FrameLayout bar(Context context) {
        FrameLayout bar = new FrameLayout(context);
        bar.setOnClickListener(v -> taps++);
        bar.addView(new View(context), new FrameLayout.LayoutParams(360, 90));
        View search = new View(context);
        search.setOnClickListener(v -> searchTaps++);
        FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(90, 90);
        right.leftMargin = 600;
        bar.addView(search, right);
        FrameLayout screen = new FrameLayout(chats.get());
        screen.addView(bar, new FrameLayout.LayoutParams(720, 90));
        chats.get().setContentView(screen);
        ShadowLooper.idleMainLooper();
        assertTrue("the bar isn't on screen", bar.isAttachedToWindow() && bar.getWidth() == 720);
        assertEquals("the search button isn't on the right", 600, search.getLeft());
        return bar;
    }

    private static MotionEvent event(int action, long downTime, float x, float y) {
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    }

    /** A finger held on the title for [heldMs], then lifted. */
    private static void press(View bar, long heldMs) {
        long down = SystemClock.uptimeMillis();
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 180, 45));
        ShadowLooper.idleMainLooper(heldMs, TimeUnit.MILLISECONDS);
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 180, 45));
        ShadowLooper.idleMainLooper();
    }

    private static long longPress() {
        return ViewConfiguration.getLongPressTimeout() + 100;
    }

    private Object screen() {
        return chats.get().getFragmentManager().findFragmentByTag("hushtelegram_settings");
    }

    private long screens() {
        return chats.get().getFragmentManager().getFragments().stream()
                .filter(fragment -> fragment instanceof SettingsDialog).count();
    }

    @Test public void aLongPressOnTheTitleOpensTheScreenOverTheChatList() {
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);

        press(bar, longPress());

        assertNotNull("a long press on the title didn't open the screen", screen());
        assertEquals("a long press gave no long-press vibration",
                HapticFeedbackConstants.LONG_PRESS, shadowOf(bar).lastHapticFeedbackPerformed());
        assertEquals("letting go after the long press also scrolled the list to the top", 0, taps);
        assertNull("the screen opened on a page instead of the first one", SettingsEntry.requestedPage());
    }

    @Test public void aTapIsStillTelegrams() {
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);

        press(bar, 80);

        assertEquals("the tap didn't reach Telegram's click listener", 1, taps);
        assertNull("a tap opened the screen", screen());
    }

    @Test public void slidingOffTheBarLetsGo() {
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);
        long down = SystemClock.uptimeMillis();

        bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 180, 45));
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, down, 180, 900));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 180, 900));
        ShadowLooper.idleMainLooper();

        assertNull("a finger that slid off the bar still opened the screen", screen());
    }

    /**
     * Telegram can tell the bar to turn touches down. The bar then never takes the press, Android
     * sends it nothing after the finger goes down, and a quick tap mustn't open the screen a moment
     * later.
     */
    @Test public void aPressTheBarTurnedDownIsntTimedOut() {
        View bar = bar();
        bar.setClickable(false);
        SettingsEntry.watchChatListTitle(bar);

        long down = SystemClock.uptimeMillis();
        assertFalse(bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 180, 45)));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);

        assertNull("a press the bar turned down opened the screen", screen());
    }

    /** The bar's buttons, the menu and search among them, keep their own presses. */
    @Test public void aButtonOnTheBarKeepsItsPress() {
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);

        long down = SystemClock.uptimeMillis();
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 640, 45));
        ShadowLooper.idleMainLooper(longPress(), TimeUnit.MILLISECONDS);
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, 640, 45));
        ShadowLooper.idleMainLooper();

        assertNull("a long press on a button of the bar opened the screen", screen());
        assertEquals("the button lost its press", 1, searchTaps);
        assertEquals("the press on the button reached the bar", 0, taps);
    }

    /** Telegram builds the chat list's view again after a theme change and hands over the same bar. */
    @Test public void watchingTheSameBarAgainOpensOneScreen() {
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);
        SettingsEntry.watchChatListTitle(bar);

        press(bar, longPress());
        chats.get().getFragmentManager().executePendingTransactions();

        assertEquals("one long press opened more than one screen", 1, screens());
        assertEquals(0, taps);
    }

    /** A long press Telegram gave the bar itself is left to run. */
    @Test public void aLongPressTelegramGaveTheBarStaysTelegrams() {
        View bar = bar();
        int[] telegramsLongPresses = {0};
        bar.setOnLongClickListener(v -> {
            telegramsLongPresses[0]++;
            return true;
        });
        SettingsEntry.watchChatListTitle(bar);

        press(bar, longPress());

        assertEquals("Telegram's own long press didn't run", 1, telegramsLongPresses[0]);
        assertNull("the long press was taken from Telegram", screen());
    }

    /** No switch reads it, so it's still the way back to the switch while HushTelegram is paused. */
    @Test public void pauseLeavesItWorking() {
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        View bar = bar();
        SettingsEntry.watchChatListTitle(bar);

        press(bar, longPress());

        assertNotNull("Pause turned off the way back to its own switch", screen());
    }

    /**
     * A bar with no activity behind it can't show the screen. The long press then does what it does
     * without HushTelegram: letting go is Telegram's tap. Nothing throws.
     */
    @Test public void aLongPressThatCantOpenTheScreenIsTelegramsTap() {
        View bar = bar(RuntimeEnvironment.getApplication());
        SettingsEntry.watchChatListTitle(bar);

        press(bar, longPress());

        assertNull(screen());
        assertEquals("letting go wasn't Telegram's tap after the screen couldn't open", 1, taps);
    }

    /** A press the listener can't follow is left to the bar, and doesn't throw into Telegram. */
    @Test public void aPressItCantFollowIsTheBars() {
        View bar = bar();

        assertFalse("a touch it failed on wasn't left to the bar", new SettingsEntry.TitlePress().onTouch(bar, null));
    }

    /** No bar, as on a chat list that hasn't made one, is no listener and no crash. */
    @Test public void noBarIsNothing() {
        SettingsEntry.watchChatListTitle(null);
    }
}
