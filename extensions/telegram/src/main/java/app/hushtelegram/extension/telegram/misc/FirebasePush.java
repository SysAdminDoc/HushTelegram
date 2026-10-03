/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.net.URL;
import java.net.URLConnection;
import java.util.regex.Pattern;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/** Only the Firebase Installations certificate header, with the stock value retained on failure. */
public final class FirebasePush {
    // Independently verified from both pinned official web and beta APK signing certificates.
    private static final String OFFICIAL_CERTIFICATE_SHA1 = "9723E5838612E9C7C08CA2C6573B6026D7A51F8F";
    private static final Pattern INSTALLATION_PATH = Pattern.compile(
            "/v1/projects/[A-Za-z0-9_-]+/installations(?:/[A-Za-z0-9_-]+(?:/authTokens:generate)?)?");

    private FirebasePush() { }

    /** Injected just before Firebase adds X-Android-Cert. Does not connect or mutate the connection. */
    public static String certificateHeader(URLConnection connection, String original) {
        HookStatus.invoked(FamilyNames.REPAIR_FIREBASE_PUSH);
        try {
            if (!Utils.settingsReady() || !Settings.REPAIR_FIREBASE_PUSH.get() || connection == null) return original;
            URL url = connection.getURL();
            if (url == null || !"https".equalsIgnoreCase(url.getProtocol())
                    || !"firebaseinstallations.googleapis.com".equalsIgnoreCase(url.getHost())
                    || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
                    || url.getQuery() != null || url.getRef() != null
                    || !INSTALLATION_PATH.matcher(url.getPath()).matches()) {
                return original;
            }
            String nativePackage = connection.getRequestProperty("X-Android-Package");
            if (!"org.telegram.messenger.web".equals(nativePackage)
                    && !"org.telegram.messenger.beta".equals(nativePackage)) return original;
            if (OFFICIAL_CERTIFICATE_SHA1.equalsIgnoreCase(original)) return original;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REPAIR_FIREBASE_PUSH, "certificate header", failure);
            return original;
        }
        HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "certificate headers repaired");
        return OFFICIAL_CERTIFICATE_SHA1;
    }
}
