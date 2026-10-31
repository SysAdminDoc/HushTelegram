/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.widget.Toast;
import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * The long-press menu items that forward with a choice (Fast forward, Forward to Saved Messages),
 * each behind its own switch under Add Repeat to the message menu. {@link MessageMenu} hands them
 * the menu's lists as it finishes and the chosen number as it comes back.
 *
 * <p>Both go through Telegram's own forward with its two flags: the sender's name hidden (Telegram's
 * "forward from my name" argument) and captions dropped. They only show next to Telegram's own
 * Forward, so a chat that doesn't allow forwarding never gets them.
 */
public final class MessageMenuTools {
    private MessageMenuTools() {}

    static final int FAST_FORWARD = 0x48544d0a;
    static final int FORWARD_SAVED = 0x48544d0b;

    /** Whether the number is one of this class's. */
    static boolean owns(int option) {
        return option == FAST_FORWARD || option == FORWARD_SAVED;
    }

    static BooleanSetting setting(int option) {
        return option == FAST_FORWARD ? Settings.MESSAGE_MENU_FAST_FORWARD : Settings.MESSAGE_MENU_FORWARD_SAVED;
    }

    /** Whether any of this class's switches is on. */
    static boolean any() {
        return MessageMenu.on(Settings.MESSAGE_MENU_FAST_FORWARD) || MessageMenu.on(Settings.MESSAGE_MENU_FORWARD_SAVED);
    }

    /** One way to forward: a label, and the two flags Telegram's forward takes. */
    static final class Way {
        final String label;
        final boolean hideSender;
        final boolean hideCaption;

        Way(String label, boolean hideSender, boolean hideCaption) {
            this.label = label;
            this.hideSender = hideSender;
            this.hideCaption = hideCaption;
        }
    }

    /**
     * Puts the items whose switches let them in just before Message details, or last. The lists stay in
     * step, so a menu whose lists already differ is left alone, and so is an empty one.
     *
     * @param forwardable whether Telegram offered its own Forward for this message
     */
    static void offer(Object chat, Object message, List<Object> icons, List<Object> items, List<Object> options, boolean forwardable) {
        if (options.isEmpty() || icons.size() != items.size() || items.size() != options.size()) return;
        List<Integer> chosen = new ArrayList<>();
        if (forwardable && MessageMenu.quickable(chat, message)) {
            ArrayList<Object> batch = MessageMenu.batch(chat, message);
            if (MessageMenu.on(Settings.MESSAGE_MENU_FAST_FORWARD) && !fastWays(message, batch).isEmpty()) chosen.add(FAST_FORWARD);
            if (MessageMenu.on(Settings.MESSAGE_MENU_FORWARD_SAVED) && savedAvailable(message)) chosen.add(FORWARD_SAVED);
        }
        int at = options.indexOf(MessageMenu.DETAILS);
        for (int option : chosen) {
            int position = at < 0 ? options.size() : at++;
            icons.add(position, MessageMenu.quickIcon());
            items.add(position, L10n.t(option == FAST_FORWARD ? "Fast forward" : "Forward to Saved Messages"));
            options.add(position, option);
        }
    }

    /** Whether any message in the batch carries a photo or a file, so a caption can be dropped. */
    static boolean captioned(ArrayList<Object> batch) {
        for (Object each : batch) {
            if (MessageMenu.media(each)) return true;
        }
        return false;
    }

    /** Whether Telegram lets this message's sender be hidden: its own Premium rule for articles. */
    static boolean senderHideable(Object message, ArrayList<Object> batch) {
        int[] types = new int[batch.size()];
        for (int i = 0; i < types.length; i++) types[i] = MessageMenu.type(batch.get(i));
        return ForwardSender.hides(MessageMenu.premium(message), MessageMenu.article(), types);
    }

    /** What Fast forward offers: the two things Quick forward can't do on their own. Empty when neither applies. */
    static List<Way> fastWays(Object message, ArrayList<Object> batch) {
        List<Way> ways = new ArrayList<>();
        boolean sender = senderHideable(message, batch);
        boolean caption = captioned(batch);
        if (sender) ways.add(new Way(L10n.t("Without sender name"), true, false));
        if (caption) ways.add(new Way(L10n.t("Without caption"), false, true));
        if (sender && caption) ways.add(new Way(L10n.t("Without sender name or caption"), true, true));
        return ways;
    }

    /** Forward to Saved Messages: your own chat, unless the message is already in it. */
    static boolean savedAvailable(Object message) {
        long self = MessageMenu.selfId(MessageMenu.account(message));
        return self != 0 && self != MessageMenu.dialog(message) && MessageMenu.reachable(MessageMenu.account(message), self);
    }

    /** What Forward to Saved Messages offers: as is, then each way Telegram's forward can drop something. */
    static List<Way> savedWays(Object message, ArrayList<Object> batch) {
        List<Way> ways = new ArrayList<>();
        ways.add(new Way(L10n.t("Forward as is"), MessageMenu.hidesSender(message, batch), false));
        ways.addAll(fastWays(message, batch));
        return ways;
    }

    /** Runs the chosen item. */
    static void choose(Context context, Object chat, Object message, int option) {
        ArrayList<Object> batch = MessageMenu.batch(chat, message);
        int account = MessageMenu.account(message);
        long from = MessageMenu.dialog(message);
        if (option == FAST_FORWARD) {
            List<Way> ways = fastWays(message, batch);
            Utils.runOnMainThread(() -> MessageMenu.chooseRow(context, L10n.t(context, "Fast forward"), labels(ways),
                    Settings.MESSAGE_MENU_FAST_FORWARD, index -> MessageMenu.chooseChat(context, account, from, L10n.t(context, "Forward to"),
                            Settings.MESSAGE_MENU_FAST_FORWARD, destination -> forward(context, account, destination, batch, ways.get(index), Settings.MESSAGE_MENU_FAST_FORWARD))));
            HookStatus.counted(FamilyNames.MESSAGE_MENU_REPEAT, "fast forward opened");
        } else {
            long self = MessageMenu.selfId(account);
            List<Way> ways = savedWays(message, batch);
            MessageMenu.Destination saved = new MessageMenu.Destination(self, MessageMenu.title(account, self));
            Utils.runOnMainThread(() -> MessageMenu.chooseRow(context, L10n.t(context, "Forward to Saved Messages"), labels(ways),
                    Settings.MESSAGE_MENU_FORWARD_SAVED, index -> forward(context, account, saved, batch, ways.get(index), Settings.MESSAGE_MENU_FORWARD_SAVED)));
            HookStatus.counted(FamilyNames.MESSAGE_MENU_REPEAT, "forward to saved messages opened");
        }
    }

    private static List<String> labels(List<Way> ways) {
        List<String> labels = new ArrayList<>();
        for (Way way : ways) labels.add(way.label);
        return labels;
    }

    static void forward(Context context, int account, MessageMenu.Destination destination, ArrayList<Object> batch, Way way, BooleanSetting guard) {
        if (!MessageMenu.on(guard)) return;
        try {
            int result = forwardWith(account, batch, destination.id, way.hideSender, way.hideCaption);
            String said = result == 0
                    ? L10n.f(context, "Forwarding to %1$s", L10n.isolate(destination.title))
                    : L10n.t(context, "Can't forward there");
            Toast.makeText(context, said, Toast.LENGTH_SHORT).show();
            if (result == 0) HookStatus.counted(FamilyNames.MESSAGE_MENU_REPEAT, "forwarded with a choice");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSAGE_MENU_REPEAT, "forward with a choice", failure);
            Toast.makeText(context, L10n.t(context, "Can't forward there"), Toast.LENGTH_SHORT).show();
        }
    }

    /** Telegram's own forward of the messages into the chat, sound on, now, with the sender and captions as asked. 0 for sent. Replaced when patching. */
    public static int forwardWith(int account, ArrayList<Object> messages, long dialog, boolean hideSender, boolean hideCaption) { return 1; }
}
