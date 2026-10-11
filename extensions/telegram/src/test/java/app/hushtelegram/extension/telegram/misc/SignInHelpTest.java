/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SignInHelpTest {
    /** Stands in for Telegram's screen, which hands out its activity the same way. */
    public static final class Screen {
        private final Activity activity;

        Screen(Activity activity) {
            this.activity = activity;
        }

        public Activity getParentActivity() {
            return activity;
        }
    }

    @Test
    public void onlyTheTwoApiIdRefusalsOnSendCodeAreExplained() throws Exception {
        Object sendCode = new TLRPC.TL_auth_sendCode();
        assertEquals("API_ID_PUBLISHED_FLOOD",
                SignInHelp.refusal(new TLRPC.TL_error(400, "API_ID_PUBLISHED_FLOOD"), sendCode));
        assertEquals("API_ID_INVALID", SignInHelp.refusal(new TLRPC.TL_error(400, "API_ID_INVALID"), sendCode));
        // Telegram's own screen already says these.
        assertNull(SignInHelp.refusal(new TLRPC.TL_error(400, "PHONE_NUMBER_INVALID"), sendCode));
        assertNull(SignInHelp.refusal(new TLRPC.TL_error(420, "FLOOD_WAIT_30"), sendCode));
        // Another request with the same error is left to Telegram.
        assertNull(SignInHelp.refusal(new TLRPC.TL_error(400, "API_ID_PUBLISHED_FLOOD"), new TLRPC.TL_auth_resendCode()));
        assertNull(SignInHelp.refusal(new TLRPC.TL_error(400, null), sendCode));
        assertNull(SignInHelp.refusal(null, sendCode));
        assertNull(SignInHelp.refusal(new TLRPC.TL_error(400, "API_ID_INVALID"), null));
    }

    @Test
    public void aRefusedIdShowsTheMessageAndTelegramsHandlerSkipsItsOwn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(SignInHelp.explain(new TLRPC.TL_error(400, "API_ID_PUBLISHED_FLOOD"), new Screen(activity),
                new TLRPC.TL_auth_sendCode()));
        Dialog shown = ShadowDialog.getLatestDialog();
        assertNotNull(shown);
        assertTrue(shown.isShowing());
    }

    @Test
    public void anythingElseGoesOnToTelegram() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(SignInHelp.explain(new TLRPC.TL_error(400, "PHONE_NUMBER_BANNED"), new Screen(activity),
                new TLRPC.TL_auth_sendCode()));
        assertNull(ShadowDialog.getLatestDialog());
        // A screen that has already let go of its activity can't show anything, so Telegram decides.
        assertFalse(SignInHelp.explain(new TLRPC.TL_error(400, "API_ID_INVALID"), new Screen(null),
                new TLRPC.TL_auth_sendCode()));
        // Something unexpected in place of the screen never throws into Telegram's handler.
        assertFalse(SignInHelp.explain(new TLRPC.TL_error(400, "API_ID_INVALID"), new Object(),
                new TLRPC.TL_auth_sendCode()));
    }

    @Test
    public void theMessageSaysWhichErrorItWas() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String flood = SignInHelp.message(activity, "API_ID_PUBLISHED_FLOOD");
        assertTrue(flood, flood.contains("Use registered Telegram API credentials"));
        assertTrue(flood, flood.endsWith("(API_ID_PUBLISHED_FLOOD)"));
        String invalid = SignInHelp.message(activity, "API_ID_INVALID");
        assertTrue(invalid, invalid.contains("Copy both again"));
        assertTrue(invalid, invalid.endsWith("(API_ID_INVALID)"));
    }

    @Test
    public void theGuideOpensTheReadmeSection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SignInHelp.openGuide(activity);
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://github.com/SysAdminDoc/HushTelegram#your-telegram-account", started.getDataString());
    }
}
