/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.keywords

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.blocked.MESSAGE_OBJECT
import app.morphe.patches.telegram.misc.blocked.POST
import app.morphe.patches.telegram.misc.blocked.TL_MESSAGE
import app.morphe.patches.telegram.misc.blocked.insertTypeChecks
import app.morphe.patches.telegram.misc.blocked.resolveOpenChatTypeReads
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val MESSAGE_FILTERS = "$EXTENSION_PACKAGE/misc/MessageFilters;"
internal const val FILTER_TYPE = "$MESSAGE_FILTERS->type(Ljava/lang/Object;I)I"
internal const val TEXT = "$TL_MESSAGE->message:Ljava/lang/String;"

@Suppress("unused")
val hideByKeywordPatch = bytecodePatch(
    name = "Hide messages by keyword",
    description = "Leaves messages that match your own words or regular expressions out of the groups and channels " +
        "you open, with one list for groups and one for channels. Private chats and your own messages stay. Nothing " +
        "is deleted, and the filters stay on the phone. Starts off. Turn it on in HushTelegram settings > Conversations.",
    default = true,
) {
    category("Conversations")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        val sites = resolveHideByKeyword()
        // Assembled on copies first, so a refusal leaves the app untouched.
        sites.forEach { (method, indices) -> insertTypeChecks(MutableMethod(ImmutableMethod.of(method)), indices, FILTER_TYPE) }
        writeStub(MESSAGE_FILTERS, "chat", 3, """
            check-cast p0, $MESSAGE_OBJECT
            invoke-virtual {p0}, $MESSAGE_OBJECT->getDialogId()J
            move-result-wide v0
            return-wide v0
        """)
        writeStub(MESSAGE_FILTERS, "out", 2, """
            check-cast p0, $MESSAGE_OBJECT
            invoke-virtual {p0}, $MESSAGE_OBJECT->isOut()Z
            move-result v0
            return v0
        """)
        writeStub(MESSAGE_FILTERS, "post", 2, """
            check-cast p0, $MESSAGE_OBJECT
            iget-object v0, p0, $MESSAGE_OBJECT->messageOwner:$TL_MESSAGE
            iget-boolean v0, v0, $POST
            return v0
        """)
        writeStub(MESSAGE_FILTERS, "text", 2, """
            check-cast p0, $MESSAGE_OBJECT
            iget-object v0, p0, $MESSAGE_OBJECT->messageOwner:$TL_MESSAGE
            iget-object v0, v0, $TEXT
            return-object v0
        """)
        sites.forEach { (method, indices) -> insertTypeChecks(method, indices, FILTER_TYPE) }
        enableStatus("hideByKeyword")
    }
}

/**
 * The same three type tests Hide blocked users answers: a message the filters match gets
 * Telegram's own mark for one it can't show, after Telegram has done its bookkeeping of IDs and
 * dates. Either patch may go in first; each finds the tests past the other's hook.
 */
internal fun BytecodePatchContext.resolveHideByKeyword(): List<Pair<MutableMethod, List<Int>>> {
    requireStatusMethod("hideByKeyword")
    controlHook(MESSAGE_FILTERS, "type", listOf("Ljava/lang/Object;", "I"), "I")
    controlHook(MESSAGE_FILTERS, "chat", listOf("Ljava/lang/Object;"), "J")
    for (stub in listOf("out", "post")) controlHook(MESSAGE_FILTERS, stub, listOf("Ljava/lang/Object;"), "Z")
    controlHook(MESSAGE_FILTERS, "text", listOf("Ljava/lang/Object;"), "Ljava/lang/String;")
    return resolveOpenChatTypeReads()
}
