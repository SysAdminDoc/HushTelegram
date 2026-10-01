# Changelog

Every HushTelegram release, newest first.

## Unreleased

The start of the project, with 4 patches for telegram.org's Telegram 12.10.6. Nothing has been released yet.

* **Telegram:** Hide ads stops the two requests Telegram makes for sponsored messages, a channel's and the video player's, before they go out. A channel answers as one with no sponsored messages and the player as one with no ad, so nothing is drawn, marked as seen or reported as clicked.
* **Telegram:** Disable analytics returns from the device statistics report before it reads anything. That's the `help.saveAppLog` event Telegram sends with your storage folders when its server asks for one.
* **Telegram:** Disable update checks returns from telegram.org's own update check before it reaches the server, because the APK it offers is signed with Telegram's key and can't install over a patched build.
* **Telegram:** HushTelegram settings opens from a launcher shortcut or from Additional settings in the app on Telegram's App info page, with pause, settings backups, diagnostics and the licenses.
* **Telegram:** Check now says "No HushTelegram release is out yet." when GitHub has none to show, instead of asking you to try again later.
* **Telegram:** The card at the top of HushTelegram settings says whether your controls are active or pause at the next start. The version line that crowded it out lives on the About page.
* **Telegram:** The update switches read more plainly, and the Chats summary on the settings home fits on one line.
* **Telegram:** Every row on the Chats, Updates and Links pages has an icon now, so the text starts at the same edge on every page, and Chats has a chat bubble.
* **Telegram:** With large text on a Samsung phone, switch rows show their icon at full size and line up with the rows around them.
* **Telegram:** The Licenses page shows the notice's headings in bold instead of under rows of = and - signs.
* **Tooling:** The build, extension library, settings screen and diagnostics start from HushThreads at b141524, renamed to `app.hushtelegram.extension` so they can't collide with another Morphe source's classes.
* **Tooling:** The verification and release scripts know Telegram. `verify-all-patches.ps1` takes telegram.org's single APK, and the manifest allowlist approves only the settings alias, the one manifest change the patches make.
* **Tooling:** `sources/telegram-sources.json` records every Telegram patch source, Xposed module and fork found, with the commit each was read at, its license and what HushTelegram may take from it. `docs/sources.md` is the readable version.
