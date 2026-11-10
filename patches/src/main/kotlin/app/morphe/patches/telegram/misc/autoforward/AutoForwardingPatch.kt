/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.autoforward

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.names.addInstructions
import app.morphe.util.numberOfParameterRegisters
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.patchLog
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.names.origName
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefByOrNull
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Auto-forwarding rules"
private const val NOTIFICATIONS_CONTROLLER = "Lorg/telegram/messenger/NotificationsController;"

private const val AUTO_FORWARDING = "$EXTENSION_PACKAGE/misc/AutoForwarding;"
private const val ON_NEW_MESSAGE_HOOK = "$AUTO_FORWARDING->onNewMessages(Ljava/lang/Object;Ljava/lang/Object;)V"

@Suppress("unused")
val autoForwardingPatch = bytecodePatch(
    name = PATCH,
    description = "Automatically forwards incoming messages to a chosen chat when they match a " +
        "configured rule. Rules name a source chat and a destination; manage them from the " +
        "Hush settings screen. Off by default.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("autoForwarding")

        val owner = tgMutableClassDefByOrNull(NOTIFICATIONS_CONTROLLER)
        if (owner == null) {
            patchLog.warning("$PATCH: NotificationsController is missing. The patch is left out.")
            return@execute
        }

        val processNewMessages = owner.methods.singleOrNull { m ->
            m.origName == "processNewMessages" && m.origReturnType == "V" &&
                !AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.parameterTypes.size == 4
        }
        if (processNewMessages?.implementation == null) {
            patchLog.warning("$PATCH: processNewMessages is missing or ambiguous. The patch is left out.")
            return@execute
        }

        val regCount = processNewMessages.implementation!!.registerCount
        val paramRegs = processNewMessages.numberOfParameterRegisters
        val p0 = regCount - paramRegs
        val p1 = p0 + 1
        processNewMessages.addInstructions(0, """
            move-object/from16 v0, v$p1
            move-object/from16 v1, v$p0
            invoke-static {v0, v1}, $ON_NEW_MESSAGE_HOOK
        """)

        enableStatus("autoForwarding")
    }
}
