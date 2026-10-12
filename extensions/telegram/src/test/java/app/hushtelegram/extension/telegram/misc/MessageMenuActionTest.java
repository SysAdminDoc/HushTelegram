/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ScrollView;
import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowClipboardManager;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

/** Exercises the chosen actions through the same native bridges the patch supplies. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, shadows = {MessageMenuActionTest.NativeMenu.class, MessageMenuActionTest.Clipboard.class},
        instrumentedPackages = "app.hushtelegram.extension.telegram.misc")
public class MessageMenuActionTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String LONG_NAME = "A saved destination with a long name that must wrap completely on a narrow screen";
    private ActivityController<ClipboardActivity> controller;
    private File photo;

    /** Read the registry only after SettingsContextRule has installed this test's context. */
    private static List<BooleanSetting> switches() {
        return Arrays.asList(Settings.MESSAGE_MENU_REPEAT, Settings.MESSAGE_MENU_COPY_PHOTO,
                Settings.MESSAGE_MENU_DETAILS, Settings.MESSAGE_MENU_QUICK_FORWARD);
    }

    public static class ClipboardActivity extends Activity {
        int clipboardMode;
        @Override public Object getSystemService(String name) {
            if (Context.CLIPBOARD_SERVICE.equals(name) && clipboardMode != 0) {
                if (clipboardMode == 2) throw new SecurityException("clipboard service refused");
                return null;
            }
            return super.getSystemService(name);
        }
    }

    @Implements(value = ClipboardManager.class)
    public static class Clipboard extends ShadowClipboardManager {
        static boolean refuse;
        @Implementation public void setPrimaryClip(ClipData clip) {
            if (refuse) throw new SecurityException("clipboard write refused");
            super.setPrimaryClip(clip);
        }
    }

    @Implements(value = MessageMenu.class, isInAndroidSdk = false)
    public static class NativeMenu {
        static ClipboardActivity host;
        static Object message;
        static ArrayList<Object> album;
        static File photo;
        static Uri uri;
        static boolean refuseUri, refuseForward, refuseRepeat;
        static int forwarded, repeated, result;
        static long destination;
        static ArrayList<Object> forwardedBatch;
        static boolean hideSender;

        @Implementation protected static Object selected(Object chat) { return message; }
        @Implementation protected static Activity activity(Object chat) { return host; }
        @Implementation protected static ArrayList<?> grouped(Object chat) { return album; }
        @Implementation protected static int account(Object message) { return 2; }
        @Implementation protected static long dialog(Object message) { return 40; }
        @Implementation protected static long selfId(int account) { return 41; }
        @Implementation protected static boolean reachable(int account, long dialog) { return true; }
        @Implementation protected static String title(int account, long dialog) { return LONG_NAME; }
        @Implementation protected static String attachPath(Object message) { return photo.getAbsolutePath(); }
        @Implementation protected static Uri uri(Context context, File file) {
            if (refuseUri) throw new SecurityException("file provider refused");
            return uri;
        }
        @Implementation protected static int forwardTo(int account, ArrayList<Object> batch, long dialog, boolean hide) {
            if (refuseForward) throw new IllegalStateException("forward refused");
            forwarded++;
            destination = dialog;
            forwardedBatch = batch;
            hideSender = hide;
            return result;
        }
        @Implementation protected static void send(Object chat, ArrayList<Object> messages) {
            if (refuseRepeat) throw new IllegalStateException("repeat refused");
            repeated++;
        }
    }

    @Before public void setUp() throws IOException {
        PauseForTests.resume();
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        controller = Robolectric.buildActivity(ClipboardActivity.class).setup();
        NativeMenu.host = controller.get();
        NativeMenu.message = new Object();
        NativeMenu.album = new ArrayList<>(Arrays.asList(NativeMenu.message, new Object()));
        photo = File.createTempFile("menu-photo-", ".png", NativeMenu.host.getCacheDir());
        NativeMenu.photo = photo;
        NativeMenu.uri = null;
        NativeMenu.refuseUri = NativeMenu.refuseForward = NativeMenu.refuseRepeat = false;
        NativeMenu.forwarded = NativeMenu.repeated = NativeMenu.result = 0;
        NativeMenu.forwardedBatch = null;
        Clipboard.refuse = false;
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        Clipboard.refuse = false;
        RuntimeEnvironment.setFontScale(1f);
        PauseForTests.resume();
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        if (photo != null) assertTrue(photo.delete());
        HookStatus.clear();
        ShadowToast.reset();
    }

    @Test @Config(qualifiers = "w480dp-h240dp-land")
    public void longDestinationNamesWrapOnFocusableButtonsInsideTheScrollArea() {
        RuntimeEnvironment.setFontScale(2f);
        Button row = destination();
        assertEquals(LONG_NAME, row.getText().toString());
        assertEquals("android.widget.Button", row.createAccessibilityNodeInfo().getClassName());
        assertTrue(row.isFocusable());
        assertTrue(row.isClickable());
        assertTrue(row.getBackground() instanceof RippleDrawable);
        assertNull(row.getEllipsize());
        assertNotNull(scrollParent(row));
        row.measure(View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        assertTrue(row.getLayout().getLineCount() > 1);
        assertTrue(row.getMeasuredHeight() >= dp(48));

        assertTrue(row.performClick());
        assertEquals(1, NativeMenu.forwarded);
        assertEquals(41, NativeMenu.destination);
        assertEquals(NativeMenu.album, NativeMenu.forwardedBatch);
        assertFalse(NativeMenu.hideSender);
        assertFalse(ShadowDialog.getLatestDialog().isShowing());
    }

    @Test public void refusedAndThrowingForwardsBothExplainTheFailure() {
        for (boolean throwing : new boolean[]{false, true}) {
            NativeMenu.refuseForward = throwing;
            NativeMenu.result = 1;
            Button row = destination();
            ShadowToast.reset();
            row.performClick();
            assertEquals("Can't forward there", ShadowToast.getTextOfLatestToast());
            assertFalse(HookStatus.report().toString().contains("quick forwarded"));
        }
    }

    @Test public void pausingAfterTheDestinationListOpensPreventsAForward() {
        Button row = destination();
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        ShadowToast.reset();
        row.performClick();
        assertEquals(0, NativeMenu.forwarded);
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void missingAndThrowingPhotoUrisOrClipboardsNeverClaimACopySucceeded() {
        Settings.MESSAGE_MENU_COPY_PHOTO.save(true);
        for (int mode = 0; mode < 5; mode++) {
            NativeMenu.uri = mode == 0 ? null : Uri.parse("content://test/photo");
            NativeMenu.refuseUri = mode == 1;
            NativeMenu.host.clipboardMode = mode == 2 ? 1 : mode == 3 ? 2 : 0;
            Clipboard.refuse = mode == 4;
            ShadowToast.reset();
            MessageMenu.chosen(new Object(), MessageMenu.COPY_PHOTO);
            idle();
            assertEquals("Couldn't copy that. Try again.", ShadowToast.getTextOfLatestToast());
            assertFalse(HookStatus.report().toString().contains("photo copied"));
        }
    }

    @Test public void aPhotoRemovedAfterItsMenuOpenedShowsAFailure() {
        Settings.MESSAGE_MENU_COPY_PHOTO.save(true);
        assertTrue(photo.delete());
        MessageMenu.chosen(new Object(), MessageMenu.COPY_PHOTO);
        idle();
        assertEquals("Couldn't copy that. Try again.", ShadowToast.getTextOfLatestToast());
        photo = null;
    }

    @Test public void aSuccessfulPhotoCopyKeepsTheContentUriAndSuccessFeedback() {
        Settings.MESSAGE_MENU_COPY_PHOTO.save(true);
        NativeMenu.uri = Uri.parse("content://test/photo");
        MessageMenu.chosen(new Object(), MessageMenu.COPY_PHOTO);
        idle();
        assertEquals("Photo copied", ShadowToast.getTextOfLatestToast());
        ClipboardManager clipboard = (ClipboardManager) NativeMenu.host.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(NativeMenu.uri, clipboard.getPrimaryClip().getItemAt(0).getUri());
        assertTrue(HookStatus.report().toString().contains("photo copied"));
    }

    @Test public void asynchronousRepeatFailuresHaveVisibleFeedback() {
        Settings.MESSAGE_MENU_REPEAT.save(true);
        NativeMenu.refuseRepeat = true;
        MessageMenu.chosen(new Object(), MessageMenu.REPEAT);
        idle();
        assertEquals("Couldn't complete that action. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(0, NativeMenu.repeated);
        assertFalse(HookStatus.report().toString().contains("message repeated"));
    }

    @Test public void pausingBeforeTheDeferredRepeatPreventsSending() {
        Settings.MESSAGE_MENU_REPEAT.save(true);
        MessageMenu.chosen(new Object(), MessageMenu.REPEAT);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        idle();
        assertEquals(0, NativeMenu.repeated);
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void detailsClipboardFailuresNeverClaimACopySucceeded() {
        Settings.MESSAGE_MENU_DETAILS.save(true);
        for (boolean refuse : new boolean[]{false, true}) {
            NativeMenu.host.clipboardMode = refuse ? 0 : 1;
            Clipboard.refuse = refuse;
            MessageMenu.chosen(new Object(), MessageMenu.DETAILS);
            idle();
            Button copy = button(ShadowDialog.getLatestDialog().findViewById(android.R.id.content), "Copy");
            assertNotNull(copy);
            ShadowToast.reset();
            copy.performClick();
            assertEquals("Couldn't copy that. Try again.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aMissingSelectedMessageExplainsTheUnavailableAction() {
        Settings.MESSAGE_MENU_DETAILS.save(true);
        NativeMenu.message = null;
        MessageMenu.chosen(new Object(), MessageMenu.DETAILS);
        idle();
        assertEquals("Couldn't complete that action. Try again.", ShadowToast.getTextOfLatestToast());
    }

    private Button destination() {
        Settings.MESSAGE_MENU_QUICK_FORWARD.save(true);
        MessageMenu.chosen(new Object(), MessageMenu.QUICK_FORWARD);
        idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        Button row = button(dialog.findViewById(android.R.id.content), LONG_NAME);
        assertNotNull(row);
        return row;
    }

    private static Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ScrollView scrollParent(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }

    private int dp(int value) { return Math.round(value * NativeMenu.host.getResources().getDisplayMetrics().density); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
