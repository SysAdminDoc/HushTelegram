/*
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushtelegram.extension.telegram.settings;

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.settings.BaseSettings;
import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.Setting;
import app.hushtelegram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushtelegram.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * A view of the complete preference model. Filtering never removes preferences from their
 * screen, so imports, dependencies, persistence and restart notices still reach hidden pages.
 */
@SuppressWarnings("deprecation")
final class SettingsNavigation extends BaseAdapter {
    private static final String STATE = "hushtelegram_navigation";
    private static final String MORE = "more";
    /** The page of switches that aren't the way they started. */
    private static final String CHANGED_PAGE = "changed";
    /** The home page row that opens it. Like the other rows only this view draws, it stores nothing. */
    static final String CHANGED = "action_changed_switches";
    /** Marks the Pause, Resume or Undo button placed under a status row's text, so a rebind replaces it. */
    private static final String STATUS_ACTION = "hushtelegram_status_action";
    /** The restart row's key. Like the other rows only this view draws, it stores nothing. */
    static final String RESTART = "action_restart_to_apply";
    static final String QUICK_SETUPS = "action_quick_setups";
    /** The settings lock's row, under Quick setups. The lock keeps its own state ({@link SettingsLock}). */
    static final String LOCK = "action_settings_lock";
    /** The row on Settings backup that hides or shows the Quick setups row again. Like the other rows only this view draws, it stores nothing. */
    static final String RESTORE_QUICK_SETUPS = "action_restore_quick_setups";
    /**
     * Whether a long press has taken the Quick setups row off the home page. The screen's own
     * choice: it stays out of settings files and a Quick setups preset, and a pause leaves it alone.
     */
    static final BooleanSetting HIDE_QUICK_SETUPS =
            new BooleanSetting("hushtelegram_hide_quick_setups", Boolean.FALSE, false, false);
    /** The row that switches these pages between cards and plain rows. Like the other rows only this view draws, it stores nothing. */
    static final String SETTINGS_LOOK = "action_settings_look";
    /**
     * Whether these pages draw plain rows with a thin line between them instead of cards (#18). The
     * screen's own choice, like {@link #HIDE_QUICK_SETUPS}: settings files and Quick setups leave
     * it out, and a pause leaves it alone.
     */
    static final BooleanSetting PLAIN_ROWS =
            new BooleanSetting("hushtelegram_plain_rows", Boolean.FALSE, false, false);

    static {
        Setting.keepWhenPaused(HIDE_QUICK_SETUPS);
        Setting.keepWhenPaused(PLAIN_ROWS);
    }

    static final String REPORT = "action_report_problem";
    private final HushTelegramPreferenceFragment page;
    private final SettingsDialog host;
    private final ListView list;
    private final int normalBottomPadding;
    private final PreferenceScreen screen;
    private final ListAdapter source;
    private final List<Section> sections = new ArrayList<>();
    private final List<Preference> visible = new ArrayList<>();
    private final Preference browse;
    /** Under the switch pages: every switch that isn't the way it started, on one page. */
    private final Preference changed;
    /** What that page shows when every switch is the way it started. */
    private final Preference noneChanged;
    private final Preference more;
    /** The home page's last row, which opens HushTelegram's Ko-fi page in a browser. */
    private final Preference support;
    /** Just above Support: a new GitHub bug report with this install's versions filled in. */
    private final Preference report;
    /** Under the home page's status card: a set of switches in one tap. */
    private final Preference quickSetups;
    /** Under Quick setups: locks the settings, starts the unlock's wait or cancels it, saying which. */
    private final HushTelegramPreferenceFragment.Row lock;
    /**
     * Shown on every page while a change waits for a restart: a tap restarts Telegram and the
     * screen comes back on the same page ({@link ReopenSettingsAfterRestart}).
     */
    private final HushTelegramPreferenceFragment.Row restart;
    /** Keeps the shared fragment's restart toast off this screen, which shows the restart row instead. */
    private final AbstractPreferenceFragment.RestartFeedbackPresenter restartFeedback;
    /** The page the last rebuild drew, so a row coming or going on that same page keeps the list's place. */
    private String rebuiltPage;
    private final Preference empty;
    /** The line a category or search page starts with while a pause applies to it. */
    private final Preference pageStatus;
    /** Whether that line is about Pause, and so carries Resume or Undo. */
    private boolean pageAction;
    /** Keep a status action from shortening this page and clamping its current scroll offset. */
    private int pageStatusHeight;
    private String route = "";
    private String query = "";
    /**
     * The row Back returns to. With a screen reader on it takes accessibility focus once it's back
     * on screen: on the S22 TalkBack otherwise held the whole screen, and the person's place in
     * the list was lost.
     */
    private Preference refocus;
    private int homePosition;
    private int homeOffset;
    private int morePosition;
    private int moreOffset;
    /** The headings inside a long page, made once so each keeps its place and id between rebuilds. */
    private final Map<PatchFamily.Group, Preference> groupHeadings = new EnumMap<>(PatchFamily.Group.class);
    /** The card each row of a split page is drawn in, and each of its headings. Empty elsewhere. */
    private final Map<Preference, Object> cards = new IdentityHashMap<>();

    private final DataSetObserver changes = new DataSetObserver() {
        @Override public void onChanged() { rebuild(); }
        @Override public void onInvalidated() { rebuild(); }
    };

    SettingsNavigation(HushTelegramPreferenceFragment page, SettingsDialog host, Bundle saved) {
        this.page = page;
        this.host = host;
        screen = page.getPreferenceScreen();
        list = page.getView().findViewById(android.R.id.list);
        normalBottomPadding = list.getPaddingBottom();
        source = screen.getRootAdapter();
        Context context = screen.getContext();
        // Stable English route IDs survive a locale change; the displayed names are localized.
        Set<PatchFamily> build = PatchFamily.inThisBuild();
        // The switch pages are on the home page, in the order they're drawn; a page this build
        // put nothing on has no category, so section() leaves it out.
        for (PatchFamily.Page switches : PatchFamily.Page.values()) {
            section(switches.title, switches.label(), summary(switches, build), icon(switches), true);
        }
        section("Links", L10n.t("Links"), null, SettingsIcons.LINKS, false);
        section("Updates", L10n.t("Updates"), null, SettingsIcons.UPDATES, false);
        section("Pause", L10n.t("Pause"), null, SettingsIcons.PAUSE, false);
        section("Settings backup", L10n.t("Settings backup"), null, SettingsIcons.EXPORT, false);
        section("Diagnostics", L10n.t("Diagnostics"), null, SettingsIcons.BUG, false);
        section("About", L10n.t("About"), null, SettingsIcons.ABOUT, false);
        browse = new HushTelegramPreferenceFragment.Heading(context);
        browse.setTitle(L10n.t("Browse settings"));
        changed = link(context, L10n.t("Changed switches"), null, SettingsIcons.LIST);
        changed.setKey(CHANGED);
        changed.setOnPreferenceClickListener(ignored -> { navigate(CHANGED_PAGE); return true; });
        noneChanged = new HushTelegramPreferenceFragment.Row(context);
        noneChanged.setTitle(L10n.t("No changed switches"));
        noneChanged.setSummary(L10n.t("A switch you turn on or off shows up here until you put it back."));
        noneChanged.setSelectable(false);
        noneChanged.setPersistent(false);
        more = link(context, L10n.t("More settings"), L10n.t("Links, updates, backup and more"), SettingsIcons.SETTINGS);
        more.setOnPreferenceClickListener(ignored -> { navigate(MORE); return true; });
        support = link(context, L10n.t("Support HushTelegram"), null, SettingsIcons.OPENING);
        support.setKey(HushTelegramPreferenceFragment.SUPPORT);
        support.setOnPreferenceClickListener(row -> { HushTelegramPreferenceFragment.openSupport(row.getContext()); return true; });
        report = link(context, L10n.t("Report a problem"),
                L10n.t("Opens a GitHub report with your versions and settings filled in. Add what happened."), SettingsIcons.BUG);
        report.setKey(REPORT);
        report.setOnPreferenceClickListener(row -> { ReportProblemForm.open(row.getContext(), screen); return true; });
        quickSetups = link(context, L10n.t("Quick setups"),
                L10n.t("Turn on a set of switches in one tap, or put every switch back the way it started. Press and hold to hide this row."), SettingsIcons.TOOLS);
        quickSetups.setKey(QUICK_SETUPS);
        quickSetups.setOnPreferenceClickListener(ignored -> { page.showQuickSetups(); return true; });
        lock = (HushTelegramPreferenceFragment.Row) link(context, SettingsLock.title(SettingsLock.State.OFF),
                SettingsLock.summary(SettingsLock.State.OFF), SettingsIcons.PRIVACY);
        lock.setKey(LOCK);
        // A tap does what the title says at once, and the row and a note say what happened.
        lock.actsAtOnce = true;
        lock.setOnPreferenceClickListener(ignored -> {
            Utils.showToastLong(SettingsLock.advance());
            rebuild();
            return true;
        });
        restart =new HushTelegramPreferenceFragment.Row(context);
        restart.setKey(RESTART);
        restart.setPersistent(false);
        // A tap restarts at once, so the row goes without a chevron.
        restart.actsAtOnce = true;
        restart.setSummary(L10n.t("Telegram closes and reopens on this page."));
        restart.setIcon(SettingsIcons.icon(context, SettingsIcons.UPDATES, palette().heading));
        restart.setOnPreferenceClickListener(row -> {
            ReopenSettingsAfterRestart.restartInto(row.getContext(), route);
            return true;
        });
        // The row appears as the switch flips. A screen reader hears the saved message as well,
        // since the row can land above the part of the list in view.
        restartFeedback = (ignored, message) -> list.announceForAccessibility(message);
        AbstractPreferenceFragment.setRestartFeedbackPresenter(restartFeedback);
        empty =new HushTelegramPreferenceFragment.Row(context);
        empty.setTitle(L10n.t("No matching settings"));
        empty.setSummary(L10n.t("Try a different word or clear the search."));
        empty.setSelectable(false);
        empty.setPersistent(false);
        pageStatus = new HushTelegramPreferenceFragment.Row(context);
        pageStatus.setSelectable(false);
        pageStatus.setPersistent(false);
        Bundle state = saved == null ? null : saved.getBundle(STATE);
        if (state != null) {
            route = state.getString("route", "");
            query = state.getString("query", "");
            homePosition = state.getInt("homePosition");
            homeOffset = state.getInt("homeOffset");
            morePosition = state.getInt("morePosition");
            moreOffset = state.getInt("moreOffset");
            if (!known(route)) route = "";
        } else {
            // Nothing saved: the page the screen was asked to open on, such as the one a restart
            // from the restart row left.
            route = host.startPage();
            if (!known(route)) route = "";
        }
        source.registerDataSetObserver(changes);
        list.setAdapter(this);
        // The list ignores Enter on a row holding a focusable view, so a status row's Enter
        // presses the button it shows, whose label says what that does.
        list.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                    && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            int position = list.getSelectedItemPosition();
            if (position < 0 || position >= getCount() || !carriesAction(getItem(position))) return false;
            View row = list.getSelectedView();
            View action = row == null ? null : row.findViewWithTag(STATUS_ACTION);
            if (action == null) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) action.performClick();
            return true;
        });
        // A long press on Quick setups takes it off the home page, and Settings backup brings it back.
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (getItem(position) != quickSetups) return false;
            HIDE_QUICK_SETUPS.save(true);
            Preference restore = page.findPreference(RESTORE_QUICK_SETUPS);
            if (restore != null) restore.setSummary(HushTelegramPreferenceFragment.restoreQuickSetupsSummary());
            Utils.showToastLong(L10n.f("Quick setups hidden. Bring it back from %1$s.", L10n.t("Settings backup")));
            rebuild();
            return true;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            Preference item = getItem(position);
            int original = originalPosition(item);
            if (original >= 0) {
                // PreferenceScreen indexes its OWN adapter, not the adapter on the ListView.
                screen.onItemClick(parent, view, original, source.getItemId(original));
            } else if (item.getOnPreferenceClickListener() != null) {
                item.getOnPreferenceClickListener().onPreferenceClick(item);
            }
        });
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        if (state != null) showAt(state.getInt("position"), state.getInt("offset"));
    }

    private void section(String id, String title, String detail, String icon, boolean primary) {
        for (Preference candidate : page.sections()) {
            if (!title.contentEquals(candidate.getTitle())) continue;
            // Gray on the home page and on More settings alike: a row that opens a page wears gray,
            // and blue is left for the rows that do something where they are.
            Preference link = link(screen.getContext(), title, detail, icon);
            link.setKey("section_" + id);
            link.setOnPreferenceClickListener(ignored -> { navigate(id); return true; });
            sections.add(new Section(id, (PreferenceCategory) candidate, link, primary));
            return;
        }
    }

    /** A switch page's line on the home page. Only Ads and Privacy have one. */
    @Nullable
    static String summary(PatchFamily.Page page, Set<PatchFamily> build) {
        switch (page) {
            case ADS: return adsSummary(build);
            case PRIVACY: return privacySummary(build);
            default: return null;
        }
    }

    private static String icon(PatchFamily.Page page) {
        switch (page) {
            case ADS: return SettingsIcons.BLOCK;
            case CHAT_LIST: return SettingsIcons.LIST;
            case CONVERSATIONS: return SettingsIcons.FORUM;
            case PLAYBACK: return SettingsIcons.PLAY;
            case NOTIFICATIONS: return SettingsIcons.BELL;
            case LOOK: return SettingsIcons.PALETTE;
            default: return SettingsIcons.PRIVACY;
        }
    }

    /**
     * The Ads row's line, from what this build hides: the ads its hooks cover, and "and more"
     * only when another Ads switch is there too. Null when Hide ads covers nothing, since the
     * page then holds only switches the line would leave unnamed.
     */
    @Nullable
    static String adsSummary(Set<PatchFamily> build) {
        if (!build.contains(PatchFamily.HIDE_ADS)) return null;
        Set<PatchFamily.Capability> installed = PatchFamily.HIDE_ADS.installedCapabilities();
        boolean channels = installed.contains(PatchFamily.Capability.CHANNEL_ADS)
                || installed.contains(PatchFamily.Capability.VIDEO_ADS);
        boolean search = installed.contains(PatchFamily.Capability.SEARCH_ADS);
        if (!channels && !search) return null;
        String ads = channels && search ? L10n.t("Ads in channels and search")
                : channels ? L10n.t("Ads in channels") : L10n.t("Ads in search");
        for (PatchFamily family : PatchFamily.Page.ADS.families) {
            if (family != PatchFamily.HIDE_ADS && build.contains(family)) return L10n.f("%1$s, and more", ads);
        }
        return ads;
    }

    /**
     * The Privacy row's line, naming only the switches this build put on that page. The draft
     * preview, gallery camera, phone number and beta log switches are off as shipped, so they add
     * "and more" rather than names of their own. A page holding only the draft or camera switch
     * still names it; one holding only the last two has no line.
     */
    @Nullable
    static String privacySummary(Set<PatchFamily> build) {
        boolean reports = build.contains(PatchFamily.DISABLE_ANALYTICS);
        boolean calls = build.contains(PatchFamily.DISABLE_CALL_DEBUG);
        boolean drafts = build.contains(PatchFamily.DISABLE_DRAFT_PREVIEWS);
        boolean camera = build.contains(PatchFamily.GALLERY_CAMERA_ON_TAP);
        boolean others = build.contains(PatchFamily.HIDE_PHONE_NUMBER) || build.contains(PatchFamily.BETA_LOGS_OFF);
        String named = reports && calls ? L10n.t("Usage reports and call diagnostics")
                : reports ? L10n.t("Usage reports") : calls ? L10n.t("Call diagnostics") : null;
        if (named == null) {
            if (drafts) return camera || others ? L10n.f("%1$s, and more", L10n.t("Draft link previews")) : L10n.t("Draft link previews");
            if (camera) return others ? L10n.f("%1$s, and more", L10n.t("Gallery camera")) : L10n.t("Gallery camera");
            return null;
        }
        return drafts || camera || others ? L10n.f("%1$s, and more", named) : named;
    }

    private static Preference link(Context context, String title, String summary, String icon) {
        Preference row = new HushTelegramPreferenceFragment.Row(context);
        row.setTitle(title);
        row.setSummary(summary);
        row.setIcon(SettingsIcons.icon(context, icon, palette().heading));
        row.setPersistent(false);
        return row;
    }

    private Section selected() {
        for (Section section : sections) if (section.id.equals(route)) return section;
        return null;
    }

    /** Whether a route names a page this screen draws: More settings, Changed switches or a section. */
    private boolean known(String destination) {
        if (MORE.equals(destination) || CHANGED_PAGE.equals(destination)) return true;
        for (Section section : sections) if (section.id.equals(destination)) return true;
        return false;
    }

    private CharSequence title() {
        if (MORE.equals(route)) return more.getTitle();
        if (CHANGED_PAGE.equals(route)) return changed.getTitle();
        Section section = selected();
        return section == null ? "HushTelegram" : section.category.getTitle();
    }

    void navigate(String destination) {
        refocus = null;
        rememberIndex();
        pageStatusHeight = 0;
        query = "";
        route = known(destination) ? destination : "";
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        showAt(0, 0);
        list.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    boolean open(Preference preference) {
        for (Section section : sections) {
            if (preference != section.category && preference.getParent() != section.category) continue;
            navigate(section.id);
            // A link to one setting lands on its row rather than the top of a long page, with the
            // row's heading above it on a split page.
            int row = visible.indexOf(preference);
            if (row > 0 && groupHeadings.containsValue(visible.get(row - 1))) row--;
            if (row > 0) showAt(row, 0);
            return true;
        }
        return false;
    }

    void search(String text) {
        if (query.equals(text)) return;
        refocus = null;
        if (query.isEmpty()) rememberIndex();
        pageStatusHeight = 0;
        query = text;
        route = "";
        host.showSearchHeading(query);
        rebuild();
        showAt(query.isEmpty() ? homePosition : 0, query.isEmpty() ? homeOffset : 0);
    }

    boolean back() {
        if (!query.isEmpty()) {
            search("");
            host.showPage(title(), true, "");
            return true;
        }
        if (route.isEmpty()) return false;
        Section section = selected();
        Preference left = section != null ? section.link : CHANGED_PAGE.equals(route) ? changed : more;
        pageStatusHeight = 0;
        route = section != null && !section.primary ? MORE : "";
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        showAt(MORE.equals(route) ? morePosition : homePosition,
                MORE.equals(route) ? moreOffset : homeOffset);
        refocus = left;
        return true;
    }

    private void showAt(int position, int offset) {
        // A header resize otherwise syncs the old page's visible children back over this
        // selection in touch mode. Rebind when changing pages; normal preference updates
        // still keep their current views and scroll position through notifyDataSetChanged.
        list.setAdapter(this);
        // Outside touch mode, selection skips an informational row. Scroll to it without
        // turning the notice into an action: place the next selectable row below its height.
        int anchor = position;
        int top = offset;
        if (!list.isInTouchMode()) {
            int width = list.getWidth() > 0 ? list.getWidth()
                    : screen.getContext().getResources().getDisplayMetrics().widthPixels;
            width -= list.getPaddingLeft() + list.getPaddingRight();
            while (anchor >= 0 && anchor < getCount() && !isEnabled(anchor)) {
                View preceding = getView(anchor, null, list);
                preceding.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                top += preceding.getMeasuredHeight();
                anchor++;
            }
        }
        list.setSelectionFromTop(anchor < getCount() ? anchor : position, anchor < getCount() ? top : offset);
    }

    private void rememberIndex() {
        View first = list.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        if (route.isEmpty() && query.isEmpty()) {
            homePosition = list.getFirstVisiblePosition();
            homeOffset = offset;
        } else if (MORE.equals(route)) {
            morePosition = list.getFirstVisiblePosition();
            moreOffset = offset;
        }
    }

    void save(Bundle out) {
        Bundle state = new Bundle();
        state.putString("route", route);
        state.putString("query", query);
        state.putInt("homePosition", homePosition);
        state.putInt("homeOffset", homeOffset);
        state.putInt("morePosition", morePosition);
        state.putInt("moreOffset", moreOffset);
        state.putInt("position", list.getFirstVisiblePosition());
        View first = list.getChildAt(0);
        state.putInt("offset", first == null ? 0 : first.getTop());
        out.putBundle(STATE, state);
    }

    void close() {
        source.unregisterDataSetObserver(changes);
        AbstractPreferenceFragment.clearRestartFeedbackPresenter(restartFeedback);
    }

    /** A change that waits for a restart was made or taken back: the restart row comes, goes or counts again. */
    void restartChanged() { rebuild(); }

    private void rebuild() {
        int restartWas = visible.indexOf(restart);
        boolean pageStatusWas = visible.contains(pageStatus);
        visible.clear();
        cards.clear();
        String terms = normalized(query).trim();
        Section selected = selected();
        if (!terms.isEmpty()) {
            for (Section section : sections) {
                boolean headingAdded = false;
                for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                    Preference row = section.category.getPreference(i);
                    String text = normalized(searchable(section.category.getTitle()) + " " + searchable(row.getTitle())
                            + " " + searchable(row.getSummary()));
                    boolean matches = true;
                    for (String term : terms.split("\\s+")) if (!text.contains(term)) matches = false;
                    if (!matches) continue;
                    if (!headingAdded) visible.add(section.category);
                    headingAdded = true;
                    visible.add(row);
                }
            }
            host.showResults(visible.size() - headings());
            if (visible.isEmpty()) visible.add(empty);
            else addPageStatus();
        } else if (selected != null) {
            addPageRows(selected.category);
            addPageStatus();
        } else if (CHANGED_PAGE.equals(route)) {
            addChangedRows();
            if (visible.isEmpty()) visible.add(noneChanged);
            else addPageStatus();
        } else if (MORE.equals(route)) {
            for (Section section : sections) if (!section.primary) visible.add(section.link);
        } else {
            visible.add(screen.getPreference(0));
            if (!HIDE_QUICK_SETUPS.get()) visible.add(quickSetups);
            showLock();
            visible.add(lock);
            visible.add(browse);
            for (Section section : sections) if (section.primary) visible.add(section.link);
            changed.setSummary(changedSummary());
            visible.add(changed);
            visible.add(more);
            visible.add(report);
            visible.add(support);
        }
        addRestart();
        if (terms.isEmpty()) host.showResults(-1);
        // Undo can remove the entire status card. Reserve its former scroll range at the
        // bottom until navigation, without retaining a stale status row or changing choices.
        int bottom = normalBottomPadding + (visible.contains(pageStatus) ? 0 : pageStatusHeight);
        if (list.getPaddingBottom() != bottom) {
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottom);
        }
        notifyDataSetChanged();
        keepPlace(restartWas, pageStatusWas);
    }

    /**
     * Every switch whose saved value isn't the one it starts with, under its page's title the way
     * search shows rows. Each is the page's own row, so flipping it back goes through the same save,
     * refusal and restart as anywhere else, and the page drops it once the change is saved.
     */
    private void addChangedRows() {
        for (Section section : sections) {
            boolean headed = false;
            for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                Preference row = section.category.getPreference(i);
                if (!isChanged(row)) continue;
                if (!headed) visible.add(section.category);
                headed = true;
                visible.add(row);
            }
        }
    }

    /** The Changed switches row's line: how many switches aren't the way they started. */
    private String changedSummary() {
        int count = 0;
        for (Section section : sections) {
            for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                if (isChanged(section.category.getPreference(i))) count++;
            }
        }
        return count == 0 ? L10n.t("Every switch is the way it started.")
                : L10n.quantity(screen.getContext(), count, "%1$d switch differs from how it started. Open to flip it back.",
                        "%1$d switches differ from how they started. Open to flip any of them back.", count);
    }

    /** Whether a row is a switch whose saved value isn't the one it starts with. */
    private static boolean isChanged(Preference row) {
        if (!(row instanceof TwoStatePreference) || !row.hasKey()) return false;
        Setting<?> setting = Setting.getSettingFromPath(row.getKey());
        return setting instanceof BooleanSetting && !setting.isSetToDefault();
    }

    /** The lock row says where the lock stands as the home page is drawn, time left included. */
    private void showLock() {
        SettingsLock.State state = SettingsLock.state();
        lock.setTitle(SettingsLock.title(state));
        lock.setSummary(SettingsLock.summary(state));
    }

    /**
     * The restart row, on whatever page is showing, while a change waits for a restart: under the
     * home page's status card or a page's pause line, and otherwise first. Its title counts the
     * changes a restart applies.
     */
    private void addRestart() {
        Context context = screen.getContext();
        int count = ReopenSettingsAfterRestart.changesWaiting(context);
        if (count <= 0) return;
        restart.setTitle(L10n.quantity(context, count, "Restart Telegram to apply this change",
                "Restart Telegram to apply %1$d changes", count));
        boolean underStatus = !visible.isEmpty()
                && (visible.get(0) == pageStatus || visible.get(0) == screen.getPreference(0));
        visible.add(underStatus ? 1 : 0, restart);
    }

    /**
     * The restart row comes and goes above the rows in view when a switch is flipped. On the same
     * page, with nothing else above moving, the list moves with it, so the row that was tapped
     * stays where it was. A row added at the top of what's in view is left to push the list down,
     * so it's seen.
     */
    private void keepPlace(int restartWas, boolean pageStatusWas) {
        String pageWas = rebuiltPage;
        rebuiltPage = route + '\n' + query;
        int restartNow = visible.indexOf(restart);
        if (!rebuiltPage.equals(pageWas) || (restartWas < 0) == (restartNow < 0)
                || pageStatusWas != visible.contains(pageStatus) || list.getChildCount() == 0) return;
        int first = list.getFirstVisiblePosition();
        int top = list.getChildAt(0).getTop();
        if (restartNow >= 0 && restartNow < first) list.setSelectionFromTop(first + 1, top);
        else if (restartWas >= 0 && restartWas < first) list.setSelectionFromTop(first - 1, top);
    }

    /**
     * A page's rows. A long page is split into its groups, each under its heading in a card of its
     * own, and a row with no switch of its own, like Sticker size or Filters for groups, stays
     * with the switch above it. A row whose family no group lists leads the page rather than
     * going missing.
     */
    private void addPageRows(PreferenceCategory category) {
        int count = category.getPreferenceCount();
        Preference[] rows = new Preference[count];
        PatchFamily[] owners = new PatchFamily[count];
        PatchFamily owner = null;
        boolean split = false;
        for (int i = 0; i < count; i++) {
            rows[i] = category.getPreference(i);
            PatchFamily family = rows[i].hasKey() ? PatchFamily.Group.owner(rows[i].getKey()) : null;
            if (family != null) owner = family;
            owners[i] = owner;
            split |= owner != null && PatchFamily.Group.of(owner) != null;
        }
        if (!split) {
            for (Preference row : rows) {
                visible.add(row);
                if ("local_notification_status".equals(row.getKey())) cards.put(row, row);
            }
            return;
        }
        for (int i = 0; i < count; i++) {
            if (owners[i] == null || PatchFamily.Group.of(owners[i]) == null) visible.add(rows[i]);
        }
        for (PatchFamily.Group group : PatchFamily.Group.values()) {
            boolean headed = false;
            for (PatchFamily family : group.families) {
                for (int i = 0; i < count; i++) {
                    if (owners[i] != family) continue;
                    if (!headed) {
                        Preference heading = heading(group);
                        visible.add(heading);
                        cards.put(heading, heading);
                        headed = true;
                    }
                    visible.add(rows[i]);
                    cards.put(rows[i], group);
                }
            }
        }
    }

    private Preference heading(PatchFamily.Group group) {
        Preference heading = groupHeadings.get(group);
        if (heading == null) {
            heading = new HushTelegramPreferenceFragment.Heading(screen.getContext());
            heading.setTitle(group.label());
            groupHeadings.put(group, heading);
        }
        return heading;
    }

    private int headings() {
        int count = 0;
        for (Preference item : visible) if (item instanceof PreferenceCategory) count++;
        return count;
    }

    /**
     * Starts a category or search page with a short line while HushTelegram is paused or a pause
     * or its end waits for a restart, when the page shows a switch Pause reaches. On a paused start
     * the switches keep showing what was saved, which read as active without it. Nothing here
     * changes a saved choice. A restart owed for anything else is the restart row's to say.
     */
    private void addPageStatus() {
        Context context = screen.getContext();
        boolean paused = HushTelegramPause.isPaused();
        boolean nextPaused = HushTelegramPause.pausesNextStart(context);
        pageAction = (paused || nextPaused) && showsPausable();
        if (!pageAction) return;
        pageStatus.setTitle(paused ? L10n.t("HushTelegram is paused") : L10n.t("HushTelegram is on"));
        // Worded like the Pause switch: Debug logging and what was set when patching stay in.
        pageStatus.setSummary(paused == nextPaused
                ? L10n.t("Until you resume, every switch except Debug logging acts as if it were off. Your "
                        + "choices stay saved. Edits made when you patched stay in.")
                : paused ? L10n.t("HushTelegram turns back on when Telegram restarts.")
                : L10n.t("HushTelegram pauses when Telegram restarts."));
        pageStatus.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette().summary : palette().heading));
        visible.add(0, pageStatus);
    }

    /** Whether the page shows a setting Pause answers for, or the Pause switch itself. */
    private boolean showsPausable() {
        for (Preference row : visible) {
            Setting<?> setting = row.hasKey() ? Setting.getSettingFromPath(row.getKey()) : null;
            if (setting != null && (!setting.isKeptWhenPaused() || setting == BaseSettings.PAUSED)) return true;
        }
        return false;
    }

    /** A title or summary as search text. A row without one adds nothing, not the word "null". */
    private static String searchable(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private int originalPosition(Preference item) {
        for (int i = 0; i < source.getCount(); i++) if (source.getItem(i) == item) return i;
        return -1;
    }

    @Override public int getCount() { return visible.size(); }
    @Override public Preference getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return System.identityHashCode(getItem(position)); }
    @Override public boolean hasStableIds() { return true; }
    @Override public boolean areAllItemsEnabled() { return false; }
    /**
     * A row with a Pause, Resume or Undo button counts too, so a keyboard stops on it and Enter
     * presses the button (the key listener). A tap still isn't an item click: the list skips
     * those on rows holding a focusable view, and TalkBack reads that row as it did.
     */
    @Override public boolean isEnabled(int position) {
        Preference item = getItem(position);
        return (item.isEnabled() && item.isSelectable()) || carriesAction(item);
    }

    private boolean carriesAction(Preference item) {
        return item == screen.getPreference(0) || (item == pageStatus && pageAction);
    }
    // Preference creates several incompatible layouts. Never recycle one kind into another.
    @Override public int getItemViewType(int position) { return IGNORE_ITEM_VIEW_TYPE; }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Preference item = getItem(position);
        View row = item.getView(null, parent);
        if (item == refocus) {
            refocus = null;
            // Posted from the new row, it runs after this layout has put the row in the window.
            row.post(() -> row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
        if (!(item instanceof PreferenceCategory)) {
            Object group = group(item);
            boolean first = position == 0 || group(getItem(position - 1)) != group;
            boolean last = position + 1 == getCount() || group(getItem(position + 1)) != group;
            palette().paintSurface(row, item, first, last, item == screen.getPreference(0) || item == restart,
                    item == support);
            if (isNavigation(item)) {
                // Keep the accurate page summaries in the searchable model, while this index
                // stays compact enough to scan all seven destinations at a glance.
                TextView summary = row.findViewById(android.R.id.summary);
                if (summary != null) summary.setVisibility(View.GONE);
                row.setMinimumHeight(dp(56));
                int side = palette().rowSide(row);
                boolean plain = ScreenColors.plainRows();
                row.setPaddingRelative(side, dp(first && !plain ? 12 : 8), side, dp(last && !plain ? 12 : 8));
                ImageView icon = row.findViewById(android.R.id.icon);
                if (icon != null) {
                    icon.setPadding(dp(7), dp(7), dp(7), dp(7));
                    icon.getLayoutParams().width = dp(40);
                    icon.getLayoutParams().height = dp(40);
                    if (icon.getParent() instanceof View) {
                        ViewGroup.LayoutParams size = ((View) icon.getParent()).getLayoutParams();
                        size.width = dp(56);
                        if (size instanceof LinearLayout.LayoutParams) {
                            ((LinearLayout.LayoutParams) size).gravity = android.view.Gravity.CENTER_VERTICAL;
                        }
                    }
                }
            }
        }
        if (item == screen.getPreference(0)) {
            // The status card stays a card in either look, as a panel apart from the rows.
            row.setPaddingRelative(dp(36), dp(16), dp(36), dp(16));
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        }
        if (item == screen.getPreference(0)) {
            bindStatus(row);
        }
        if (item == support) {
            row.setMinimumHeight(dp(48));
            row.setPaddingRelative(dp(30), dp(12), dp(30), dp(12));
            View icon = row.findViewById(android.R.id.icon);
            if (icon != null && icon.getParent() instanceof View) ((View) icon.getParent()).setVisibility(View.GONE);
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null) summary.setVisibility(View.GONE);
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setTextColor(palette().heading);
        }
        if (item == pageStatus) {
            row.setMinimumHeight(Math.max(row.getMinimumHeight(), pageStatusHeight));
            if (pageAction) bindAction(row, HushTelegramPause.isPaused(), HushTelegramPause.pausesNextStart(screen.getContext()), true);
        }
        return row;
    }

    private boolean isNavigation(Preference item) {
        if (item == more) return true;
        for (Section section : sections) if (item == section.link) return true;
        return false;
    }

    private void bindStatus(View row) {
        boolean nextPaused = HushTelegramPause.pausesNextStart(screen.getContext());
        boolean paused = HushTelegramPause.isPaused();
        TextView summary = row.findViewById(android.R.id.summary);
        if (!paused && !nextPaused && ReleaseCheck.statusLine() == null) {
            summary.setText(AbstractPreferenceFragment.restartPending.isEmpty()
                    ? L10n.t("Your switches are working.")
                    : L10n.t("A change here applies after Telegram restarts."));
        } else if (paused && nextPaused && HushTelegramPause.reason() == HushTelegramPause.Reason.SWITCH
                && !markerLeft()) {
            // A marker Resume couldn't remove keeps the card's own line, which says what to do.
            summary.setText(L10n.t("Your choices are saved. Tap Resume, then restart Telegram."));
        }
        bindAction(row, paused, nextPaused, false);
    }

    /**
     * Pause, Resume or Undo in [row], whichever changes the next start. The list keeps its place,
     * so the page the button is on stays where it was.
     */
    private void bindAction(View row, boolean paused, boolean nextPaused, boolean pageStatusAction) {
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        frame.removeAllViews();
        // Some platform preference templates give this frame MATCH_PARENT height. That measures
        // the button against the shorter text column and clips its 48dp target on Android 13.
        ViewGroup.LayoutParams frameSize = frame.getLayoutParams();
        frameSize.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        frame.setLayoutParams(frameSize);
        Button action = new Button(screen.getContext());
        action.setText(paused != nextPaused ? L10n.t("Undo") : nextPaused ? L10n.t("Resume") : L10n.t("Pause"));
        action.setAllCaps(false);
        action.setTextSize(14);
        action.setTextColor(palette().onAccent);
        action.setBackground(new android.graphics.drawable.InsetDrawable(
                palette().control(action, palette().accent, ScreenColors.BUTTON_RADIUS, false),
                0, dp(6), 0, dp(6)));
        action.setMinWidth(dp(48));
        action.setMinHeight(dp(48));
        action.setMinimumWidth(dp(48));
        action.setMinimumHeight(dp(48));
        action.setPadding(dp(14), 0, dp(14), 0);
        action.setTag(STATUS_ACTION);
        action.setOnClickListener(ignored -> {
            // Resume always goes through. Pause, or Undo on a resume, waits for the settings lock.
            if (!nextPaused && SettingsLock.refuses()) return;
            if (pageStatusAction) pageStatusHeight = Math.max(pageStatusHeight, row.getHeight());
            if (nextPaused) {
                page.resumeFromOverview();
            } else {
                Preference pause = page.findPreference(BaseSettings.PAUSED.key);
                if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(!nextPaused);
            }
            rebuild();
        });
        // From one and a half times the text size, a button beside the text leaves the name too
        // little room and it breaks inside the word, so the button goes under the text there.
        TextView summary = row.findViewById(android.R.id.summary);
        if (screen.getContext().getResources().getConfiguration().fontScale >= 1.5f
                && summary != null && summary.getParent() instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams place = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            place.addRule(android.widget.RelativeLayout.BELOW, android.R.id.summary);
            place.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
            ViewGroup column = (ViewGroup) summary.getParent();
            View earlier = column.findViewWithTag(STATUS_ACTION);
            if (earlier != null) column.removeView(earlier);
            column.addView(action, place);
            frame.setVisibility(View.GONE);
            return;
        }
        frame.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setVisibility(View.VISIBLE);
    }

    private boolean markerLeft() {
        java.io.File marker = HushTelegramPause.markerFile(screen.getContext());
        return marker != null && marker.exists();
    }

    private int dp(int value) { return Math.round(value * screen.getContext().getResources().getDisplayMetrics().density); }

    private Object group(Preference item) {
        Object card = cards.get(item);
        if (card != null) return card;
        if (item == more || item == support || item == browse || item == screen.getPreference(0)
                || item == restart || item == quickSetups || item == lock || item == report) return item;
        if (item.getParent() != null) return item.getParent();
        return sections;
    }

    private static ScreenColors palette() { return ScreenColors.DEFAULT; }

    private static final class Section {
        final String id;
        final PreferenceCategory category;
        final Preference link;
        final boolean primary;
        Section(String id, PreferenceCategory category, Preference link, boolean primary) {
            this.id = id;
            this.category = category;
            this.link = link;
            this.primary = primary;
        }
    }
}
