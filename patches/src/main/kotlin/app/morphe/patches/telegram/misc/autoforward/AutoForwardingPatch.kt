/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.autoforward

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.names.addInstructions
import app.morphe.util.numberOfParameterRegisters
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlMembers
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.messagemenu.FORWARD_SEND
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.names.origDescriptor
import app.morphe.util.names.origName
import app.morphe.util.names.tgMutableClassDefByOrNull
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Auto-forwarding rules"
private const val NOTIFICATIONS_CONTROLLER = "Lorg/telegram/messenger/NotificationsController;"
private const val PROCESS_NEW_MESSAGES = "(Ljava/util/ArrayList;ZZLjava/util/concurrent/CountDownLatch;)V"

internal const val AUTO_FORWARDING = "$EXTENSION_PACKAGE/misc/AutoForwarding;"
internal const val ON_NEW_MESSAGES = "$AUTO_FORWARDING->onNewMessages(Ljava/lang/Object;I)V"

private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/ArrayList;"
private const val STRING = "Ljava/lang/String;"
private const val MESSAGE = "Lorg/telegram/messenger/MessageObject;"
private const val TL_MESSAGE = "Lorg/telegram/tgnet/TLRPC\$Message;"
private const val ACTION = "Lorg/telegram/tgnet/TLRPC\$MessageAction;"
private const val ACTION_EMPTY = "Lorg/telegram/tgnet/TLRPC\$TL_messageActionEmpty;"
private const val TL_DIALOG = "Lorg/telegram/tgnet/TLRPC\$Dialog;"
private const val USER_CONFIG = "Lorg/telegram/messenger/UserConfig;"
private const val CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
private const val DIALOG_OBJECT = "Lorg/telegram/messenger/DialogObject;"
private const val SEND_HELPER = "Lorg/telegram/messenger/SendMessagesHelper;"

@Suppress("unused")
val autoForwardingPatch = bytecodePatch(
    name = PATCH,
    description = "Forwards new messages from one chat to another by rules you set, every message or only " +
        "messages with media. Your own forwards are never forwarded again, so two rules can't loop, " +
        "and at most 20 go out a minute. Starts off. Turn it on and add rules in HushTelegram settings > Privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        val process = resolveAutoForwarding()
        writeAutoForwardingStubs()
        // p1 is the new messages and p3 isFcm, a notification built from a push.
        val p0 = process.implementation!!.registerCount - process.numberOfParameterRegisters
        process.addInstructions(0, """
            move-object/from16 v0, v${p0 + 1}
            move/from16 v1, v${p0 + 3}
            invoke-static {v0, v1}, $ON_NEW_MESSAGES
        """)
        enableStatus("autoForwarding")
    }
}

/**
 * NotificationsController.processNewMessages(messages, isLast, isFcm, latch) gets each batch of
 * new messages, from a push or from the connection, and the stubs reach the members below.
 * Everything is checked before anything changes.
 */
internal fun BytecodePatchContext.resolveAutoForwarding(): MutableMethod {
    requireStatusMethod("autoForwarding")
    controlHook(AUTO_FORWARDING, "onNewMessages", listOf(OBJECT, "I"), "V")
    for (name in listOf("account", "id")) controlHook(AUTO_FORWARDING, name, listOf(OBJECT), "I")
    for (name in listOf("out", "forwarded", "service", "media")) controlHook(AUTO_FORWARDING, name, listOf(OBJECT), "Z")
    for (name in listOf("dialog", "dialogId")) controlHook(AUTO_FORWARDING, name, listOf(OBJECT), "J")
    controlHook(AUTO_FORWARDING, "selectedAccount", emptyList(), "I")
    controlHook(AUTO_FORWARDING, "userId", listOf("I"), "J")
    controlHook(AUTO_FORWARDING, "dialogs", listOf("I", "I"), LIST)
    controlHook(AUTO_FORWARDING, "secret", listOf("J"), "Z")
    controlHook(AUTO_FORWARDING, "peerName", listOf("I", "J"), STRING)
    controlHook(AUTO_FORWARDING, "forward", listOf("I", LIST, "J"), "I")

    controlMembers(MESSAGE, "currentAccount:I", "messageOwner:$TL_MESSAGE", "getDialogId()J", "getId()I", "isOut()Z",
        "isForwarded()Z", "isMediaEmpty()Z")
    controlMembers(TL_MESSAGE, "action:$ACTION")
    controlMembers(ACTION_EMPTY)
    controlMembers(USER_CONFIG, "static selectedAccount:I", "static getInstance(I)$USER_CONFIG", "getClientUserId()J")
    controlMembers(CONTROLLER, "static getInstance(I)$CONTROLLER", "getDialogs(I)$LIST", "getPeerName(J)$STRING")
    controlMembers(TL_DIALOG, "id:J")
    controlMembers(DIALOG_OBJECT, "static isEncryptedDialog(J)Z")
    controlMembers(SEND_HELPER, "static getInstance(I)$SEND_HELPER", FORWARD_SEND.substringAfter("->"))

    val owner = tgMutableClassDefByOrNull(NOTIFICATIONS_CONTROLLER)
    controlShape(owner != null, "NotificationsController is missing")
    val process = owner!!.methods.filter { it.origName == "processNewMessages" && it.origDescriptor == PROCESS_NEW_MESSAGES &&
        !AccessFlags.STATIC.isSet(it.accessFlags) }.controlSingle("processNewMessages")
    controlShape(process.implementation != null &&
        process.implementation!!.registerCount - process.numberOfParameterRegisters >= 2,
        "processNewMessages has no room for the hook")
    return process
}

/** The stubs' bodies. */
internal fun BytecodePatchContext.writeAutoForwardingStubs() {
    writeStub(AUTO_FORWARDING, "account", 2, "check-cast p0, $MESSAGE\niget v0, p0, $MESSAGE->currentAccount:I\nreturn v0")
    writeStub(AUTO_FORWARDING, "dialog", 3, """
        check-cast p0, $MESSAGE
        invoke-virtual {p0}, $MESSAGE->getDialogId()J
        move-result-wide v0
        return-wide v0
    """)
    for ((name, call) in listOf("id" to "getId()I", "out" to "isOut()Z", "forwarded" to "isForwarded()Z")) {
        writeStub(AUTO_FORWARDING, name, 2, """
            check-cast p0, $MESSAGE
            invoke-virtual {p0}, $MESSAGE->$call
            move-result v0
            return v0
        """)
    }
    writeStub(AUTO_FORWARDING, "service", 2, """
        check-cast p0, $MESSAGE
        iget-object v0, p0, $MESSAGE->messageOwner:$TL_MESSAGE
        if-eqz v0, :hush_no
        iget-object v0, v0, $TL_MESSAGE->action:$ACTION
        if-eqz v0, :hush_no
        instance-of v0, v0, $ACTION_EMPTY
        if-nez v0, :hush_no
        const/4 v0, 0x1
        return v0
        :hush_no
        const/4 v0, 0x0
        return v0
    """)
    writeStub(AUTO_FORWARDING, "media", 2, """
        check-cast p0, $MESSAGE
        invoke-virtual {p0}, $MESSAGE->isMediaEmpty()Z
        move-result v0
        if-nez v0, :hush_empty
        const/4 v0, 0x1
        return v0
        :hush_empty
        const/4 v0, 0x0
        return v0
    """)
    writeStub(AUTO_FORWARDING, "selectedAccount", 1, "sget v0, $USER_CONFIG->selectedAccount:I\nreturn v0")
    writeStub(AUTO_FORWARDING, "userId", 3, """
        invoke-static {p0}, $USER_CONFIG->getInstance(I)$USER_CONFIG
        move-result-object v0
        invoke-virtual {v0}, $USER_CONFIG->getClientUserId()J
        move-result-wide v0
        return-wide v0
    """)
    writeStub(AUTO_FORWARDING, "dialogs", 3, """
        invoke-static {p0}, $CONTROLLER->getInstance(I)$CONTROLLER
        move-result-object v0
        invoke-virtual {v0, p1}, $CONTROLLER->getDialogs(I)$LIST
        move-result-object v0
        return-object v0
    """)
    writeStub(AUTO_FORWARDING, "dialogId", 3, "check-cast p0, $TL_DIALOG\niget-wide v0, p0, $TL_DIALOG->id:J\nreturn-wide v0")
    writeStub(AUTO_FORWARDING, "secret", 3, """
        invoke-static {p0, p1}, $DIALOG_OBJECT->isEncryptedDialog(J)Z
        move-result v0
        return v0
    """)
    writeStub(AUTO_FORWARDING, "peerName", 4, """
        invoke-static {p0}, $CONTROLLER->getInstance(I)$CONTROLLER
        move-result-object v0
        invoke-virtual {v0, p1, p2}, $CONTROLLER->getPeerName(J)$STRING
        move-result-object v0
        return-object v0
    """)
    writeStub(AUTO_FORWARDING, "forward", 20, """
        invoke-static/range {p0 .. p0}, $SEND_HELPER->getInstance(I)$SEND_HELPER
        move-result-object v0
        move-object/from16 v1, p1
        move-wide/from16 v2, p2
        const/4 v4, 0x0
        const/4 v5, 0x0
        const/4 v6, 0x1
        const/4 v7, 0x0
        const/4 v8, 0x0
        const/4 v9, 0x0
        const/4 v10, -0x1
        const-wide/16 v11, 0x0
        const-wide/16 v13, 0x0
        const/4 v15, 0x0
        invoke-virtual/range {v0 .. v15}, $FORWARD_SEND
        move-result v0
        return v0
    """)
}
