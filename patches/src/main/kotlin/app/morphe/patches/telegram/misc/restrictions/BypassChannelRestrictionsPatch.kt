/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.restrictions

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.patchLog
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.names.addInstructionsWithLabels
import app.morphe.util.names.origName
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefByOrNull
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Bypass channel restrictions"
private const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"

private const val CHANNEL_RESTRICTIONS = "$EXTENSION_PACKAGE/misc/ChannelRestrictions;"
private const val IS_CHAT_NO_FORWARDS_HOOK = "$CHANNEL_RESTRICTIONS->isChatNoForwards(I)I"
private const val NO_FORWARDS_HOOK = "$CHANNEL_RESTRICTIONS->noForwards(I)I"

@Suppress("unused")
val bypassChannelRestrictionsPatch = bytecodePatch(
    name = PATCH,
    description = "Bypasses content restrictions (no forwards, no saving, no copying) on channels " +
        "and chats. Forwarding, copying and saving work as if the restriction was never set. " +
        "The existing Allow screenshots patch handles screen capture separately. Off by default.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("bypassChannelRestrictions")

        val owner = tgMutableClassDefByOrNull(MESSAGES_CONTROLLER)
        if (owner == null) {
            patchLog.warning("$PATCH: MessagesController is missing. The patch is left out.")
            return@execute
        }

        var hooked = 0

        val isChatNoForwards = owner.methods.singleOrNull { m ->
            m.origName == "isChatNoForwards" && m.origReturnType == "Z"
        }
        if (isChatNoForwards?.implementation != null) {
            isChatNoForwards.addInstructionsWithLabels(0, """
                const/4 v0, 0x1
                invoke-static {v0}, $IS_CHAT_NO_FORWARDS_HOOK
                move-result v0
                if-nez v0, :stock
                return v0
            """, ExternalLabel("stock", isChatNoForwards.getInstruction(0)))
            hooked++
        } else {
            patchLog.warning("$PATCH: isChatNoForwards is missing. That part is left out.")
        }

        val isPeerNoForwards = owner.methods.singleOrNull { m ->
            m.origName == "isPeerNoForwards" && m.origReturnType == "Z" &&
                !AccessFlags.STATIC.isSet(m.accessFlags)
        }
        if (isPeerNoForwards?.implementation != null) {
            isPeerNoForwards.addInstructionsWithLabels(0, """
                const/4 v0, 0x1
                invoke-static {v0}, $NO_FORWARDS_HOOK
                move-result v0
                if-nez v0, :stock
                return v0
            """, ExternalLabel("stock", isPeerNoForwards.getInstruction(0)))
            hooked++
        } else {
            patchLog.warning("$PATCH: isPeerNoForwards is missing. That part is left out.")
        }

        if (hooked > 0) {
            enableStatus("bypassChannelRestrictions")
        } else {
            patchLog.warning("$PATCH: no hook sites found. The patch is left out of this build.")
        }
    }
}
