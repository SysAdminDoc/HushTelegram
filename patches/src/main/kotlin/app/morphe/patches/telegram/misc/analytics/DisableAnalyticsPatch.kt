/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.analytics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.ads.MESSAGES_CONTROLLER
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.returnEarlyWhen
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch

private const val PATCH = "Disable analytics"

/**
 * The messages controller's `logDeviceStats()`: when the server's config sets
 * `collectDeviceStats`, it reads the phone's storage directories once and sends them as a
 * `help.saveAppLog` event. Found by that field and that request, both kept names.
 */
internal object LogDeviceStatsFingerprint : Fingerprint(
    definingClass = MESSAGES_CONTROLLER,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = MESSAGES_CONTROLLER, name = "collectDeviceStats", type = "Z"),
        newInstance("Lorg/telegram/tgnet/TLRPC\$TL_help_saveAppLog;"),
    ),
)

/**
 * Keeps Telegram's device statistics report on the phone.
 *
 * Found by reading 12.10.6 (2026-09-30): of the places that build a `help.saveAppLog` event, the
 * device statistics are the one that describes the phone rather than something you did. The
 * method asks the extension first and returns before reading anything while the switch is on.
 */
@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = PATCH,
    description = "Stops Telegram reading your storage folders and sending them to its server as a " +
        "device statistics report. Everything the app needs to work is left alone.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("disableAnalytics")

        val method = LogDeviceStatsFingerprint.methodOrNull
            ?: throw PatchException("$PATCH: no method of the messages controller reads collectDeviceStats and sends a help.saveAppLog event")
        method.returnEarlyWhen(PATCH, "$EXTENSION_PACKAGE/misc/Analytics;->skipDeviceStats()Z", "return-void")

        enableStatus("disableAnalytics")
    }
}
