package app.hushtelegram.extension.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A dialog over the always-black settings page is built dark, and the rest keep following the app. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "notnight")
public class UtilsDarkModeTest {

    @Test
    public void buildingDarkAnswersDarkOnlyWhileItBuilds() {
        assertFalse("a light phone with no app theme to ask reads light", Utils.isDarkModeEnabled());
        assertTrue(Utils.buildDark(Utils::isDarkModeEnabled));
        assertFalse("put back once built", Utils.isDarkModeEnabled());
    }

    @Test
    public void whatItAnsweredBeforeComesBackAfterAFailure() {
        try {
            Utils.buildDark(() -> {
                throw new IllegalStateException("the dialog couldn't be built");
            });
        } catch (IllegalStateException expected) {
            // The failure is the caller's to handle.
        }
        assertFalse(Utils.isDarkModeEnabled());
    }
}
