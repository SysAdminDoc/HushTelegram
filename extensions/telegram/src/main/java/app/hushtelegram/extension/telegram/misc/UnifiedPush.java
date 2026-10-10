/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Logger;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Notifications through a UnifiedPush app such as ntfy, for phones where Firebase can't sign this
 * patched Telegram up.
 *
 * <p>Telegram's API has a token type for a plain web address its servers call when something
 * arrives, type 4, "Simple push", and its documentation names UnifiedPush as one use of it. The
 * call carries no message, only a wake-up. The UnifiedPush app hands out that address and turns
 * each call into a broadcast here; Telegram then connects and fetches what's new the same way it
 * does after a Firebase push it couldn't read.
 *
 * <p>Sign-up follows the UnifiedPush Android spec: a REGISTER broadcast to the chosen app with a
 * random token, answered by NEW_ENDPOINT with the address. Every broadcast back has to carry that
 * token, which is how a stranger's broadcast is told apart. The address goes to Telegram through
 * its own {@code PushListenerController.sendRegistrationToServer}, and while the switch is on the
 * patch keeps Firebase from taking that place back at the next start.
 *
 * <p>Everything this keeps is in its own prefs file: which app, the token, and the address.
 * None of it is a setting, so it isn't in settings backups.
 */
public final class UnifiedPush {
    static final String PREFS = "hushtelegram_unified_push";
    static final String CHOSEN = "chosen";
    static final String DISTRIBUTOR = "distributor";
    static final String TOKEN = "token";
    static final String ENDPOINT = "endpoint";
    static final String PROBLEM = "problem";
    static final String REFUSED = "refused";
    static final String ENDED = "ended";

    static final String REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    static final String UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER";
    static final String MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK";
    static final String NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT";
    static final String MESSAGE = "org.unifiedpush.android.connector.MESSAGE";
    static final String UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED";
    static final String REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED";
    /** The spec's stand-in package: the PendingIntent only tells the app who's asking. */
    static final String DUMMY_APP = "org.unifiedpush.dummy_app";
    static final String NTFY = "io.heckel.ntfy";
    /** Telegram's token type for a web address its servers call, "Simple push" in its API. */
    static final int SIMPLE_PUSH = 4;
    /** The spec's limit on an address. */
    static final int MAX_ENDPOINT = 1000;

    private UnifiedPush() {}

    /** Telegram's side, swapped out by tests. The real one calls the stubs the patch writes. */
    interface Telegram {
        /** Hands the address to Telegram's own sign-up, unless Telegram already holds it. */
        void register(String endpoint);

        /** Starts Telegram if it isn't yet and has each signed-in account fetch what's new. Runs done after. */
        void wake(Runnable done);
    }

    static Telegram telegram = new Telegram() {
        @Override
        public void register(String endpoint) {
            Utils.runOnMainThread(() -> {
                startTelegram();
                if (endpoint.equals(telegramToken())) return;
                sendToTelegram(SIMPLE_PUSH, endpoint);
                HookStatus.counted(FamilyNames.UNIFIED_PUSH, "addresses handed to Telegram");
            });
        }

        @Override
        public void wake(Runnable done) {
            Utils.runOnMainThread(() -> {
                try {
                    startTelegram();
                    onStageQueue(() -> {
                        try {
                            wakeAccounts();
                            HookStatus.counted(FamilyNames.UNIFIED_PUSH, "wake-ups");
                        } catch (Throwable failure) {
                            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "wake-up", failure);
                        } finally {
                            done.run();
                        }
                    });
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.UNIFIED_PUSH, "wake-up", failure);
                    done.run();
                }
            });
        }
    };

    /** Whether the address replaced Telegram's token in this thread's sign-up, for {@link #type}. */
    private static final ThreadLocal<Boolean> SWAPPED = new ThreadLocal<>();

    /**
     * Injected at the top of {@code sendRegistrationToServer}, before {@link #type}. While the
     * switch is on and an address is saved, any sign-up Telegram starts, Firebase's included,
     * signs up that address instead.
     */
    public static String token(int type, String token) {
        HookStatus.invoked(FamilyNames.UNIFIED_PUSH);
        String endpoint = activeEndpoint();
        SWAPPED.set(endpoint != null);
        if (endpoint == null) return token;
        if (type != SIMPLE_PUSH) HookStatus.counted(FamilyNames.UNIFIED_PUSH, "Firebase sign-ups kept on UnifiedPush");
        return endpoint;
    }

    /** Injected right after {@link #token}: the token type that goes with the answer it gave. */
    public static int type(int type) {
        Boolean swapped = SWAPPED.get();
        SWAPPED.remove();
        return Boolean.TRUE.equals(swapped) ? SIMPLE_PUSH : type;
    }

    /** The saved address while the switch is on and not paused, or null. */
    @Nullable
    static String activeEndpoint() {
        try {
            if (!Utils.settingsReady() || !Settings.UNIFIED_PUSH.get()) return null;
            Context context = Utils.getContext();
            if (context == null) return null;
            String endpoint = prefs(context).getString(ENDPOINT, null);
            return endpoint == null || endpoint.isEmpty() ? null : endpoint;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "switch", failure);
            return null;
        }
    }

    /** From the application's onCreate in the main process: signs up again, as the spec asks at each start. */
    public static void onTelegramStart(Context context) {
        if (context == null || !PatchFamily.UNIFIED_PUSH.inBuild()) return;
        try {
            if (!Utils.settingsReady()) return;
            boolean on = Settings.UNIFIED_PUSH.savedValue();
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Utils.runOnBackgroundThread(() -> sync(app, on));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "sign-up", failure);
        }
    }

    /** From the switch, with the value it's about to save. */
    public static void switched(Context context, boolean on) {
        if (context == null || !PatchFamily.UNIFIED_PUSH.inBuild()) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> sync(app, on));
    }

    /** Picks the app the next sign-up goes to, and signs up with it now if the switch is on. */
    public static void choose(Context context, String distributor, boolean on) {
        if (context == null || !PatchFamily.UNIFIED_PUSH.inBuild()) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        prefs(app).edit().putString(CHOSEN, distributor).commit();
        Utils.runOnBackgroundThread(() -> sync(app, on));
    }

    /**
     * Signs up with the chosen app when on, and ends the sign-up when off. Pause doesn't come
     * through here: callers pass the switch as saved, so a paused Telegram keeps its address. A
     * sign-up the app ended stays ended until the switch is turned off and on again.
     */
    static synchronized void sync(Context context, boolean on) {
        try {
            SharedPreferences prefs = prefs(context);
            String current = prefs.getString(DISTRIBUTOR, null);
            String token = prefs.getString(TOKEN, null);
            if (!on) {
                if (token != null && current != null) send(context, new Intent(UNREGISTER), current, token);
                // The choice of app stays; everything about the sign-up goes.
                prefs.edit().remove(DISTRIBUTOR).remove(TOKEN).remove(ENDPOINT).remove(PROBLEM).commit();
                return;
            }
            if (ENDED.equals(prefs.getString(PROBLEM, null))) return;
            String distributor = pick(distributors(context), prefs.getString(CHOSEN, current));
            if (distributor == null) return;
            if (token == null || !distributor.equals(current)) {
                if (token != null && current != null) send(context, new Intent(UNREGISTER), current, token);
                token = UUID.randomUUID().toString();
                prefs.edit().putString(DISTRIBUTOR, distributor).putString(TOKEN, token)
                        .remove(ENDPOINT).remove(PROBLEM).commit();
            }
            send(context, new Intent(REGISTER)
                    .putExtra("application", context.getPackageName())
                    .putExtra("message", "Telegram"), distributor, token);
            Logger.printInfo(() -> "UnifiedPush: asked " + distributor + " for an address");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "sign-up", failure);
        }
    }

    /** A broadcast to the app, carrying the token and the PendingIntent that names this app to it. */
    private static void send(Context context, Intent intent, String distributor, String token) {
        intent.setPackage(distributor).putExtra("token", token).putExtra("pi", PendingIntent.getBroadcast(context, 0,
                new Intent(DUMMY_APP), PendingIntent.FLAG_IMMUTABLE));
        context.sendBroadcast(intent);
    }

    /** The saved choice while it's still installed, then ntfy, then the first by package name. */
    @Nullable
    static String pick(List<String> installed, @Nullable String chosen) {
        if (installed.isEmpty()) return null;
        if (chosen != null && installed.contains(chosen)) return chosen;
        if (installed.contains(NTFY)) return NTFY;
        return installed.get(0);
    }

    /** Package names of the installed apps that take a UnifiedPush sign-up, sorted, this one left out. */
    public static List<String> distributors(Context context) {
        List<String> found = new ArrayList<>();
        try {
            for (ResolveInfo info : context.getPackageManager().queryBroadcastReceivers(new Intent(REGISTER), 0)) {
                if (info.activityInfo == null) continue;
                String name = info.activityInfo.packageName;
                if (name != null && !name.equals(context.getPackageName()) && !found.contains(name)) found.add(name);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "UnifiedPush: could not list the apps that take a sign-up", failure);
        }
        Collections.sort(found);
        return found;
    }

    /**
     * From {@link UnifiedPushReceiver}. Anything without the saved token is somebody else's and
     * is dropped. Runs done once the broadcast is handled, which for a message is after Telegram
     * has been told to fetch.
     */
    static void receive(Context context, @Nullable Intent intent, Runnable done) {
        Runnable once = once(done);
        try {
            if (intent == null || !PatchFamily.UNIFIED_PUSH.inBuild()) { once.run(); return; }
            SharedPreferences prefs = prefs(context);
            String token = prefs.getString(TOKEN, null);
            if (token == null || !token.equals(intent.getStringExtra("token"))) {
                Logger.printInfo(() -> "UnifiedPush: dropped a broadcast without this sign-up's token");
                once.run();
                return;
            }
            String action = String.valueOf(intent.getAction());
            switch (action) {
                case NEW_ENDPOINT:
                    endpoint(context, prefs, intent.getStringExtra("endpoint"));
                    break;
                case MESSAGE:
                    acknowledge(context, prefs, token, intent.getStringExtra("id"));
                    if (Utils.settingsReady() && Settings.UNIFIED_PUSH.get()) {
                        telegram.wake(once);
                        return;
                    }
                    break;
                case REGISTRATION_FAILED:
                    prefs.edit().remove(ENDPOINT).putString(PROBLEM, REFUSED).commit();
                    Logger.printInfo(() -> "UnifiedPush: the app turned the sign-up down");
                    break;
                case UNREGISTERED:
                    prefs.edit().remove(TOKEN).remove(ENDPOINT).putString(PROBLEM, ENDED).commit();
                    Logger.printInfo(() -> "UnifiedPush: the app ended the sign-up");
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "UnifiedPush broadcast", failure);
        }
        once.run();
    }

    private static void endpoint(Context context, SharedPreferences prefs, @Nullable String endpoint) {
        if (!usable(endpoint)) {
            Logger.printInfo(() -> "UnifiedPush: ignored an address that isn't a web address");
            return;
        }
        prefs.edit().putString(ENDPOINT, endpoint).remove(PROBLEM).commit();
        Logger.printInfo(() -> "UnifiedPush: got an address at " + Uri.parse(endpoint).getHost());
        // Paused, Telegram keeps the address it has; the next start hands this one over.
        if (Utils.settingsReady() && Settings.UNIFIED_PUSH.get()) telegram.register(endpoint);
    }

    /** An http or https address with a host, no longer than the spec allows. */
    static boolean usable(@Nullable String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > MAX_ENDPOINT) return false;
        Uri uri = Uri.parse(endpoint);
        String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty();
    }

    /** The spec asks for an acknowledgement when a message carries an id. */
    private static void acknowledge(Context context, SharedPreferences prefs, String token, @Nullable String id) {
        String distributor = prefs.getString(DISTRIBUTOR, null);
        if (id == null || distributor == null) return;
        context.sendBroadcast(new Intent(MESSAGE_ACK).setPackage(distributor).putExtra("token", token).putExtra("id", id));
    }

    /** From {@link UnifiedPushRaise}: a binding that keeps Telegram in front while a message arrives, signed up only. */
    @Nullable
    static IBinder raise(Context context) {
        try {
            if (!PatchFamily.UNIFIED_PUSH.inBuild() || prefs(context).getString(TOKEN, null) == null) return null;
            return new Binder();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.UNIFIED_PUSH, "raise", failure);
            return null;
        }
    }

    /** The app the next sign-up goes to, or null when none is installed. */
    @Nullable
    public static String chosen(Context context) {
        SharedPreferences prefs = prefs(context);
        return pick(distributors(context), prefs.getString(CHOSEN, prefs.getString(DISTRIBUTOR, null)));
    }

    /** What the Push app row says under its title. */
    public static String status(Context context, boolean on) {
        SharedPreferences prefs = prefs(context);
        String app = chosen(context);
        if (app == null) return L10n.t("Install a UnifiedPush app like ntfy, then come back here.");
        String name = label(context, app);
        if (!on) return L10n.f("%1$s. Turn the switch on to sign up with it.", name);
        String problem = prefs.getString(PROBLEM, null);
        if (ENDED.equals(problem)) return L10n.f("%1$s ended the sign-up. Turn the switch off and on to sign up again.", name);
        if (REFUSED.equals(problem)) return L10n.f("%1$s turned the sign-up down. Check that it allows UnifiedPush.", name);
        if (app.equals(prefs.getString(DISTRIBUTOR, null)) && prefs.getString(ENDPOINT, null) != null) {
            return L10n.f("Telegram's wake-ups come through %1$s.", name);
        }
        return L10n.f("Signing up with %1$s…", name);
    }

    /** The app's name as the launcher shows it, or its package name. */
    public static String label(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // Uninstalled since it was listed: the package name still says which one.
        }
        return packageName;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Runnable once(Runnable done) {
        AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (ran.compareAndSet(false, true)) done.run();
        };
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code PushListenerController.sendRegistrationToServer(type, token)}. */
    public static void sendToTelegram(int type, String token) {}

    /** {@code SharedConfig.pushString}, the token Telegram last signed up. */
    public static String telegramToken() { return null; }

    /** {@code ApplicationLoader.postInitApplication()}, which does nothing once Telegram is up. */
    public static void startTelegram() {}

    /** {@code Utilities.stageQueue.postRunnable(task)}. */
    public static void onStageQueue(Runnable task) { task.run(); }

    /** Each signed-in account reconnects and fetches, as Telegram's {@code onDecryptError} does, without its latch. */
    public static void wakeAccounts() {}
}
