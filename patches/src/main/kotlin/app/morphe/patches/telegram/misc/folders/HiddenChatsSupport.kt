/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.folders

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.chattypefolders.CLIENT_USER_ID
import app.morphe.patches.telegram.misc.chattypefolders.CONTROLLER_INSTANCE
import app.morphe.patches.telegram.misc.chattypefolders.LOCAL_FILTER
import app.morphe.patches.telegram.misc.chattypefolders.MESSAGES_CONTROLLER
import app.morphe.patches.telegram.misc.chattypefolders.SELECTED_ACCOUNT
import app.morphe.patches.telegram.misc.chattypefolders.USER_CONFIG
import app.morphe.patches.telegram.misc.chattypefolders.USER_CONFIG_INSTANCE
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.requireReachable
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val HIDDEN_CHATS = "$EXTENSION_PACKAGE/misc/HiddenChats;"
private const val HIDDEN_OBJECT = "Ljava/lang/Object;"
private const val HIDDEN_LIST = "Ljava/util/ArrayList;"
private const val SPARSE = "Landroid/util/SparseArray;"
internal const val BY_FOLDER = "$MESSAGES_CONTROLLER->dialogsByFolder:$SPARSE"
internal const val SELECTED_FILTERS = "$MESSAGES_CONTROLLER->selectedDialogFilter:[$LOCAL_FILTER"
internal const val FILTER_DIALOGS = "$LOCAL_FILTER->dialogs:$HIDDEN_LIST"
internal const val DIALOG_ID = "Lorg/telegram/tgnet/TLRPC\$Dialog;->id:J"
internal const val GET_DIALOGS = "$MESSAGES_CONTROLLER->getDialogs(I)$HIDDEN_LIST"
internal const val PEER_NAME = "$MESSAGES_CONTROLLER->getPeerName(J)Ljava/lang/String;"
internal const val GET_USER_CONFIG = "Lorg/telegram/messenger/BaseController;->getUserConfig()$USER_CONFIG"
private const val NOTIFICATIONS = "Lorg/telegram/messenger/NotificationCenter;"
internal const val NOTIFICATIONS_INSTANCE = "$NOTIFICATIONS->getInstance(I)$NOTIFICATIONS"
internal const val DIALOGS_NEED_RELOAD = "$NOTIFICATIONS->dialogsNeedReload:I"
internal const val POST_NOTIFICATION = "$NOTIFICATIONS->postNotificationName(I[Ljava/lang/Object;)V"
private const val SPARSE_SIZE = "$SPARSE->size()I"
private const val SPARSE_REMOVE = "$SPARSE->remove(I)V"
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private const val REORDER_PINS = "Lorg/telegram/tgnet/TLRPC\$TL_messages_reorderPinnedDialogs;"
internal const val DIALOG_PINNED = "Lorg/telegram/tgnet/TLRPC\$Dialog;->pinned:Z"
internal const val DIALOG_PIN_NUM = "Lorg/telegram/tgnet/TLRPC\$Dialog;->pinnedNum:I"
internal const val PIN_ORDER = "$HIDDEN_CHATS->pinOrder(${HIDDEN_OBJECT}I$HIDDEN_OBJECT)$HIDDEN_OBJECT"

/** The call that hands the sorted lists to the extension: one instruction. */
internal const val HIDDEN_CHATS_SIZE = 1

/** The pinned order's swap: the call, its result and the cast back to Telegram's list. */
internal const val PIN_ORDER_SIZE = 3

/**
 * Where reorderPinnedDialogs has just read one folder's chats into [list]: [at] is the instruction
 * after the read, [controller] and [folder] the registers the read was handed.
 */
internal class PinOrderPlan(val method: MutableMethod, val at: Int, val controller: Int, val folder: Int, val list: Int)

/**
 * Where sortDialogs starts dropping the folders left empty, and where the pinned order reads its
 * folder's chats, found and checked before anything is edited, or [missing], why this build can't
 * hide chats.
 */
internal class HiddenChatsPlan(val sort: MutableMethod?, val at: Int, val controller: Int, val missing: String?, val pins: PinOrderPlan? = null) {
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        val method = sort?.let(into) ?: return
        val before = method.implementation!!.instructions.size
        // At a label: the paths that skip the forward list's own entry come in here too.
        method.addInstructionsAtControlFlowLabel(at,
            "invoke-static/range {v$controller .. v$controller}, $HIDDEN_CHATS->prune($HIDDEN_OBJECT)V")
        // smali drops an instruction it can't build and only logs it.
        hiddenFits(method.implementation!!.instructions.size - before == HIDDEN_CHATS_SIZE, "the hidden chats call didn't compile whole")
        val pin = pins ?: return
        val reorder = into(pin.method)
        val stock = reorder.implementation!!.instructions.count { it.opcode != Opcode.NOP }
        // Nothing jumps onto [at], so a plain insert runs on the read's only way on.
        reorder.addInstructions(pin.at, """
            invoke-static {v${pin.controller}, v${pin.folder}, v${pin.list}}, $PIN_ORDER
            move-result-object v${pin.list}
            check-cast v${pin.list}, $HIDDEN_LIST
        """)
        hiddenFits(reorder.implementation!!.instructions.count { it.opcode != Opcode.NOP } - stock == PIN_ORDER_SIZE,
            "the pinned order's swap didn't compile whole")
    }

    /** The same edit on a copy, so a smali problem shows before the app is touched. */
    fun rehearse() = try {
        apply { MutableMethod(ImmutableMethod.of(it)) }
    } catch (failed: RuntimeException) {
        throw Unhidden("the hidden chats call doesn't compile (${failed.javaClass.simpleName}: ${failed.message})")
    }
}

/** The runtime the hook and the settings page call. It's the extension's own, so a change there refuses the patch. */
internal fun BytecodePatchContext.requireHiddenChatsRuntime() {
    requireStatusMethod("hiddenChats")
    controlHook(HIDDEN_CHATS, "prune", listOf(HIDDEN_OBJECT), "V")
    controlHook(HIDDEN_CHATS, "controllerUser", listOf(HIDDEN_OBJECT), "J")
    controlHook(HIDDEN_CHATS, "folders", listOf(HIDDEN_OBJECT), SPARSE)
    controlHook(HIDDEN_CHATS, "selectedFilters", listOf(HIDDEN_OBJECT), "[$HIDDEN_OBJECT")
    controlHook(HIDDEN_CHATS, "filterDialogs", listOf(HIDDEN_OBJECT), HIDDEN_LIST)
    controlHook(HIDDEN_CHATS, "dialogId", listOf(HIDDEN_OBJECT), "J")
    controlHook(HIDDEN_CHATS, "pinOrder", listOf(HIDDEN_OBJECT, "I", HIDDEN_OBJECT), HIDDEN_OBJECT)
    controlHook(HIDDEN_CHATS, "pinned", listOf(HIDDEN_OBJECT), "Z")
    controlHook(HIDDEN_CHATS, "pinnedNum", listOf(HIDDEN_OBJECT), "I")
    controlHook(HIDDEN_CHATS, "selectedAccount", emptyList(), "I")
    controlHook(HIDDEN_CHATS, "userId", listOf("I"), "J")
    controlHook(HIDDEN_CHATS, "dialogs", listOf("I", "I"), HIDDEN_LIST)
    controlHook(HIDDEN_CHATS, "peerName", listOf("I", "J"), "Ljava/lang/String;")
    controlHook(HIDDEN_CHATS, "resort", listOf("I"), "V")
}

/** Why this build's chat list can't hide chats. */
private class Unhidden(why: String) : Exception(why)

private fun hiddenFits(ok: Boolean, why: String) {
    if (!ok) throw Unhidden(why)
}

/**
 * MessagesController.sortDialogs fills every folder's chat list, the archive's included, and the
 * folder tabs' lists, then walks the folders and drops the ones left empty. The hook goes right
 * before that walk starts, so the extension takes the hidden chats out of the full lists and a
 * folder holding only hidden chats goes with the empty ones. Anything else and the switch is left out.
 */
internal fun BytecodePatchContext.resolveHiddenChats(): HiddenChatsPlan = try {
    // Rehearsed here, so an edit that doesn't fit this build leaves the switch out rather than the patch.
    emptyFolderWalk().also { it.rehearse() }
} catch (unhidden: Unhidden) {
    HiddenChatsPlan(null, 0, 0, unhidden.message)
} catch (failed: RuntimeException) {
    // Code this build reads in a way nothing here expected, such as a branch the control flow can't follow.
    HiddenChatsPlan(null, 0, 0, "the chat sort can't be read (${failed.javaClass.simpleName}: ${failed.message})")
}

private fun BytecodePatchContext.emptyFolderWalk(): HiddenChatsPlan {
    val controller = mutableClassDefByOrNull(MESSAGES_CONTROLLER)
    val sorts = controller?.methods?.filter {
        it.name == "sortDialogs" && it.returnType == "V" && it.parameterTypes.size == 1 && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.orEmpty()
    hiddenFits(sorts.size == 1, "the chat sort is missing or ambiguous")
    val method = sorts.single()
    val body = method.controlBody()

    // The one remove the walk makes: dialogsByFolder.remove(key), on the field it just read.
    val removes = body.indices.filter { index ->
        val read = body.getOrNull(index - 1)
        body[index].opcode == Opcode.INVOKE_VIRTUAL && body[index].controlRef() == SPARSE_REMOVE &&
            read?.opcode == Opcode.IGET_OBJECT && read.controlRef() == BY_FOLDER &&
            body[index].namedRegisters().firstOrNull() == (read as TwoRegisterInstruction).registerA
    }
    hiddenFits(removes.size == 1, "the chat sort no longer drops empty folders the same way")
    val flow = try {
        ControlFlow.of(method)
    } catch (failed: IllegalArgumentException) {
        throw Unhidden("the chat sort can't be read (${failed.message})")
    }
    // Back to the walk's head, which reads dialogsByFolder and asks its size.
    val back = (removes.single() + 1 until body.size).firstOrNull { body[it].opcode in GOTOS }
    hiddenFits(back != null && flow.normal[back].size == 1, "the empty folder walk changed")
    val head = flow.normal[back!!].single()
    val readFolders = body[head] as? TwoRegisterInstruction
    hiddenFits(head in 1 until removes.single() && body[head].opcode == Opcode.IGET_OBJECT && body[head].controlRef() == BY_FOLDER &&
        body.getOrNull(head + 1)?.controlRef() == SPARSE_SIZE && body[head + 1].namedRegisters() == listOf(readFolders!!.registerA),
        "the empty folder walk changed")
    val self = readFolders!!.registerB
    // The walk's counter starts right before its head, and nothing but that start changes the controller's register on the way.
    val at = head - 1
    val start = body[at]
    hiddenFits(start.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.MOVE, Opcode.MOVE_FROM16) &&
        (start as OneRegisterInstruction).registerA != self && flow.normal[at] == listOf(head),
        "the empty folder walk's start changed")
    // Only the start leads into the head from before it, so the call runs once for each sort.
    hiddenFits(flow.normal.withIndex().none { (index, next) -> head in next && index != at && index < head } &&
        flow.exceptional.none { head in it || at in it }, "something jumps into the empty folder walk")

    // Without the pinned order a hidden pinned chat would come unpinned everywhere, so it's both or neither.
    val pins = pinOrderRead()

    for ((reference, static) in listOf(BY_FOLDER to false, SELECTED_FILTERS to false, FILTER_DIALOGS to false, DIALOG_ID to false,
        DIALOG_PINNED to false, DIALOG_PIN_NUM to false,
        GET_DIALOGS to false, PEER_NAME to false, GET_USER_CONFIG to false, CLIENT_USER_ID to false, SELECTED_ACCOUNT to true,
        USER_CONFIG_INSTANCE to true, CONTROLLER_INSTANCE to true, NOTIFICATIONS_INSTANCE to true, DIALOGS_NEED_RELOAD to true,
        POST_NOTIFICATION to false, method.toString() to false)) {
        try {
            requireReachable(reference, static)
        } catch (failed: PatchException) {
            throw Unhidden("$reference isn't reachable from the extension")
        }
    }
    return HiddenChatsPlan(method, at, self, null, pins)
}

/**
 * MessagesController.reorderPinnedDialogs sends the server, as the whole pinned order, the pinned
 * chats in the list getDialogs(folder) hands it, which the sort left the hidden ones out of. The
 * swap goes right after that read, the method's only one.
 */
private fun BytecodePatchContext.pinOrderRead(): PinOrderPlan {
    val methods = mutableClassDefBy(MESSAGES_CONTROLLER).methods.filter { m ->
        !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" &&
            m.controlBody().any { it.opcode == Opcode.NEW_INSTANCE && it.controlRef() == REORDER_PINS }
    }
    hiddenFits(methods.size == 1, "the pinned order is missing or ambiguous")
    val method = methods.single()
    val body = method.controlBody()
    val reads = body.indices.filter { body[it].opcode == Opcode.INVOKE_VIRTUAL && body[it].controlRef() == GET_DIALOGS }
    hiddenFits(reads.size == 1, "the pinned order no longer reads one folder's chats")
    val read = reads.single()
    val handed = body[read].namedRegisters()
    val result = body.getOrNull(read + 1)
    hiddenFits(handed.size == 2 && result?.opcode == Opcode.MOVE_RESULT_OBJECT, "the pinned order's chat read changed")
    val list = (result as OneRegisterInstruction).registerA
    // The read's registers still hold the controller and the folder after it, and fit the call's 4-bit form.
    hiddenFits(list !in handed && (handed + list).all { it in 0..15 }, "the pinned order's registers don't fit the swap")
    val at = read + 2
    val flow = try {
        ControlFlow.of(method)
    } catch (failed: IllegalArgumentException) {
        throw Unhidden("the pinned order can't be read (${failed.message})")
    }
    hiddenFits(at < body.size && flow.normal.withIndex().none { (index, next) -> at in next && index != read + 1 } &&
        flow.exceptional.none { at in it }, "something jumps into the pinned order's chat read")
    return PinOrderPlan(method, at, handed[0], handed[1], list)
}

/** Gives HiddenChats' stubs this build's chat lists, account and refresh. [sort] is sortDialogs itself. */
internal fun BytecodePatchContext.writeHiddenChatsStubs(sort: String) {
    writeStub(HIDDEN_CHATS, "controllerUser", 3, """
        check-cast p0, $MESSAGES_CONTROLLER
        invoke-virtual {p0}, $GET_USER_CONFIG
        move-result-object v0
        invoke-virtual {v0}, $CLIENT_USER_ID
        move-result-wide v0
        return-wide v0
    """)
    writeStub(HIDDEN_CHATS, "folders", 2, """
        check-cast p0, $MESSAGES_CONTROLLER
        iget-object v0, p0, $BY_FOLDER
        return-object v0
    """)
    writeStub(HIDDEN_CHATS, "selectedFilters", 2, """
        check-cast p0, $MESSAGES_CONTROLLER
        iget-object v0, p0, $SELECTED_FILTERS
        return-object v0
    """)
    writeStub(HIDDEN_CHATS, "filterDialogs", 2, """
        check-cast p0, $LOCAL_FILTER
        iget-object v0, p0, $FILTER_DIALOGS
        return-object v0
    """)
    writeStub(HIDDEN_CHATS, "dialogId", 3, """
        check-cast p0, ${DIALOG_ID.substringBefore("->")}
        iget-wide v0, p0, $DIALOG_ID
        return-wide v0
    """)
    writeStub(HIDDEN_CHATS, "pinned", 2, """
        check-cast p0, ${DIALOG_PINNED.substringBefore("->")}
        iget-boolean v0, p0, $DIALOG_PINNED
        return v0
    """)
    writeStub(HIDDEN_CHATS, "pinnedNum", 2, """
        check-cast p0, ${DIALOG_PIN_NUM.substringBefore("->")}
        iget v0, p0, $DIALOG_PIN_NUM
        return v0
    """)
    writeStub(HIDDEN_CHATS, "selectedAccount", 1, """
        sget v0, $SELECTED_ACCOUNT
        return v0
    """)
    writeStub(HIDDEN_CHATS, "userId", 3, """
        invoke-static {p0}, $USER_CONFIG_INSTANCE
        move-result-object v0
        invoke-virtual {v0}, $CLIENT_USER_ID
        move-result-wide v0
        return-wide v0
    """)
    writeStub(HIDDEN_CHATS, "dialogs", 3, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v0
        invoke-virtual {v0, p1}, $GET_DIALOGS
        move-result-object v0
        return-object v0
    """)
    writeStub(HIDDEN_CHATS, "peerName", 4, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v0
        invoke-virtual {v0, p1, p2}, $PEER_NAME
        move-result-object v0
        return-object v0
    """)
    writeStub(HIDDEN_CHATS, "resort", 4, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v0
        const/4 v1, 0x0
        invoke-virtual {v0, v1}, $sort
        invoke-static {p0}, $NOTIFICATIONS_INSTANCE
        move-result-object v0
        sget v1, $DIALOGS_NEED_RELOAD
        const/4 v2, 0x0
        new-array v2, v2, [Ljava/lang/Object;
        invoke-virtual {v0, v1, v2}, $POST_NOTIFICATION
        return-void
    """)
}
