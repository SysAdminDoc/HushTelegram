/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.updates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.patchLog
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.returnEarlyWhen
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.MAIN_ACTIVITY
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.names.origRef
import app.morphe.util.names.tgClassDefBy
import app.morphe.util.names.tgFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef

private const val PATCH = "Disable update checks"

/**
 * The launcher activity's update check, `checkAppUpdate(boolean force)` in Telegram's source and
 * renamed in the APK. telegram.org's build runs it: it waits out `SharedConfig.lastUpdateCheckTime`
 * and then sends `help.getAppUpdate`. Up to 13.1 it called `ApplicationLoader.isStandaloneBuild()`
 * first; 13.2 inlines that, so the time and the request are what find it.
 */
internal val CheckAppUpdateFingerprint by tgFingerprint {
    Fingerprint(
        definingClass = MAIN_ACTIVITY,
        returnType = "V",
        parameters = listOf("Z"),
        filters = listOf(
            fieldAccess(
                definingClass = type(SHARED_CONFIG),
                name = field(SHARED_CONFIG, "lastUpdateCheckTime", "J").name,
                type = "J",
            ),
            newInstance(GET_APP_UPDATE),
        ),
    )
}

private const val SHARED_CONFIG = "Lorg/telegram/messenger/SharedConfig;"

private const val GET_APP_UPDATE = "Lorg/telegram/tgnet/TLRPC\$TL_help_getAppUpdate;"

/** Whether any method of the class builds `help.getAppUpdate`. */
private fun ClassDef.sendsAppUpdateRequest() = methods.any { method ->
    method.implementation?.instructions?.any {
        it.opcode == Opcode.NEW_INSTANCE && it.origRef() == GET_APP_UPDATE
    } == true
}

/**
 * Stops telegram.org's build checking for its own updates.
 *
 * That build downloads and offers the next version itself, but the APK it gets is signed with
 * Telegram's key and Android won't install it over a patched build. The check asks the extension
 * first and returns before reaching the server while the switch is on.
 *
 * Found by reading 12.10.6 (2026-09-30).
 */
@Suppress("unused")
val disableUpdateChecksPatch = bytecodePatch(
    name = PATCH,
    description = "Stops Telegram from offering its own updates from telegram.org, which can't install over a patched " +
        "app. Patch each new version in Morphe Manager instead. On by default. Turn it off in HushTelegram " +
        "settings > More settings > Updates.",
    default = true,
) {
    category("Updates")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("disableUpdateChecks")

        val method = CheckAppUpdateFingerprint.methodOrNull
        if (method == null) {
            if (!tgClassDefBy(MAIN_ACTIVITY).sendsAppUpdateRequest()) {
                // Google Play's build never updates itself, and from 13.2 R8 drops the check whole.
                patchLog.info("$PATCH: this build has no update check of its own, so there's nothing to stop")
                return@execute
            }
            throw PatchException("$PATCH: the launcher activity sends help.getAppUpdate, but not from a (boolean) method that waits out SharedConfig.lastUpdateCheckTime")
        }
        method.returnEarlyWhen(PATCH, "$EXTENSION_PACKAGE/misc/UpdateChecks;->skipUpdateCheck()Z", "return-void")

        enableStatus("disableUpdateChecks")
    }
}
