/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.folders

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.Mention
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlField
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.forEachClassMentioning
import app.morphe.patches.telegram.misc.localcontrols.requireReachable
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.names.origType
import app.morphe.util.names.replaceInstruction
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val LOCAL_PIN_DRAG_FROM = "$LOCAL_PINS->dragFrom(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val LOCAL_PIN_DROP_ON = "$LOCAL_PINS->dropOn(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val LOCAL_PIN_MOVED = "$LOCAL_PINS->moved(Ljava/lang/Object;)V"

private const val OBJECT = "Ljava/lang/Object;"
private const val DRAG_RECYCLER = "Landroidx/recyclerview/widget/RecyclerView;"
private const val DRAG_SWAP = "Ljava/util/Collections;->swap(Ljava/util/List;II)V"
private const val DRAG_DIALOG = "Lorg/telegram/tgnet/TLRPC\$Dialog;"

/** What the drag's stubs reach in this build, each a field or method reference. */
internal class LocalPinOrderRefs(
    /** DialogsActivity.isDialogPinned(dialog). */
    val pinned: String,
    /** DialogsActivity.viewPages. */
    val pages: String,
    /** ViewPage.dialogsType. */
    val type: String,
    /** DialogsActivity.movingWas. */
    val moving: String,
)

/**
 * The chat list's drag of its pinned chats: the pin check in [grab] (getMovementFlags) at [grabAt],
 * the pin check in [drop] (onMove) at [dropAt] and that method's note that the pins moved at
 * [movedAt]. Or [missing], why this build's drag can't take chats pinned on this phone.
 */
internal class LocalPinOrder(
    private val grab: MutableMethod?,
    private val grabAt: Int,
    private val drop: MutableMethod?,
    private val dropAt: Int,
    private val movedAt: Int,
    val refs: LocalPinOrderRefs?,
    val missing: String?,
) {
    /** The same edits on copies, so a smali problem refuses before the app is touched. */
    fun rehearse() {
        if (grab != null && drop != null) edit(MutableMethod(ImmutableMethod.of(grab)), MutableMethod(ImmutableMethod.of(drop)))
    }

    fun apply() {
        if (grab != null && drop != null) edit(grab, drop)
    }

    private fun edit(grabbing: MutableMethod, dropping: MutableMethod) {
        grabbing.replaceInstruction(grabAt, asked(grabbing, grabAt, LOCAL_PIN_DRAG_FROM))
        dropping.replaceInstruction(dropAt, asked(dropping, dropAt, LOCAL_PIN_DROP_ON))
        val activity = dropping.getInstruction<TwoRegisterInstruction>(movedAt).registerB
        dropping.replaceInstruction(movedAt, "invoke-static/range {v$activity .. v$activity}, $LOCAL_PIN_MOVED")
    }

    /** The pin check at [index] in [method], asking [hook] instead, with the same two registers. */
    private fun asked(method: MutableMethod, index: Int, hook: String): String =
        when (val check = method.implementation!!.instructions[index]) {
            is RegisterRangeInstruction ->
                "invoke-static/range {v${check.startRegister} .. v${check.startRegister + 1}}, $hook"
            is FiveRegisterInstruction -> "invoke-static {v${check.registerC}, v${check.registerD}}, $hook"
            else -> throw PatchException("Pin on this phone: instruction $index of ${method.name} is no longer the pin check")
        }
}

/**
 * The chat list drags only Telegram's own pins: while chats are selected, getMovementFlags asks
 * isDialogPinned of the chat under the finger, and onMove asks it of each chat the drag passes
 * over, swaps the two and notes that the pins moved, so their new order goes to Telegram when the
 * selection closes. The runtime answers both questions, so a chat pinned on this phone drags among
 * the others pinned here, and onMove's note goes to the runtime too, which saves that order instead
 * of telling Telegram. Telegram's own pins drag as they always did. Its stubs read the pin check,
 * the page shown and the note in Telegram's code.
 */
internal fun BytecodePatchContext.resolveLocalPinOrder(): LocalPinOrder {
    fun missing(why: String) = LocalPinOrder(null, 0, null, 0, 0, null, why)
    try {
        requireStatusMethod("localPinOrder")
        controlHook(LOCAL_PINS, "dragFrom", listOf(OBJECT, OBJECT), "Z")
        controlHook(LOCAL_PINS, "dropOn", listOf(OBJECT, OBJECT), "Z")
        controlHook(LOCAL_PINS, "moved", listOf(OBJECT), "V")
    } catch (changed: PatchException) {
        return missing("the drag's runtime hooks changed (${changed.message})")
    }

    // onMove: an instance method of an item touch callback that swaps two chats of the list and their pinnedNum.
    val moves = mutableListOf<Pair<String, String>>()
    forEachClassMentioning(Mention.allReferences(listOf(DIALOG_PIN_NUM, DRAG_SWAP))) { cls ->
        if (cls.origType.startsWith("Lapp/hushtelegram/")) return@forEachClassMentioning
        cls.methods.filter { it.isDragMove() }.forEach { moves += cls.type to it.toString() }
    }
    if (moves.size != 1) return missing("the chat list no longer drags its pins through one move (${moves.size})")
    val (owner, moveRef) = moves.single()
    val callback = tgMutableClassDefBy(owner)
    val drop = callback.methods.single { it.toString() == moveRef }
    val dropBody = drop.controlBody()
    val swap = dropBody.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && it.controlRef() == DRAG_SWAP }

    // isDialogPinned, the one check of a chat before the swap.
    val checks = (0 until swap).filter { dropBody[it].isPinCheck() }
    if (checks.size != 1) return missing("the chat list's drag no longer asks once whether a chat is pinned (${checks.size})")
    val dropAt = checks.single()
    val pinned = dropBody[dropAt].call()!!
    val activity = pinned.definingClass

    // movingWas, the one flag of the chat list onMove sets after the swap.
    val notes = (swap + 1 until dropBody.size).filter { index ->
        dropBody[index].opcode == Opcode.IPUT_BOOLEAN && dropBody[index].controlField()?.definingClass == activity
    }
    if (notes.size != 1) return missing("the chat list's drag no longer notes once that its pins moved (${notes.size})")
    val movedAt = notes.single()
    val moving = dropBody[movedAt].controlField()!!

    // getMovementFlags, the callback's one other question of the same check.
    val grabs = callback.methods.filter { it.isMovementFlags() }.flatMap { m ->
        val body = m.controlBody()
        body.indices.filter { body[it].isPinCheck() && body[it].call().toString() == pinned.toString() }.map { m to it }
    }
    if (grabs.size != 1) return missing("the chat list no longer asks once whether a held chat is pinned (${grabs.size})")
    val (grab, grabAt) = grabs.single()

    // viewPages[0].dialogsType, read the way isDialogPinned reads it to tell folder tabs apart.
    val check = tgMutableClassDefBy(activity).methods.singleOrNull { it.toString() == pinned.toString() }
        ?: return missing("the chat list's pin check isn't its own")
    val checkBody = check.controlBody()
    val self = check.localRegisterCount()
    val type = checkBody.indices.firstNotNullOfOrNull { index ->
        val pages = checkBody[index]
        val page = checkBody.getOrNull(index + 1)
        val read = checkBody.getOrNull(index + 2)
        val pagesField = pages.controlField()
        val readField = read?.controlField()
        if (pages.opcode == Opcode.IGET_OBJECT && pages.namedRegisters()[1] == self && pagesField?.definingClass == activity &&
            pagesField.type.startsWith("[") && page?.opcode == Opcode.AGET_OBJECT && page.namedRegisters()[1] == pages.namedRegisters()[0] &&
            read?.opcode == Opcode.IGET && read.namedRegisters()[1] == page.namedRegisters()[0] &&
            readField?.definingClass == pagesField.type.removePrefix("[") && readField.type == "I") pagesField to readField else null
    } ?: return missing("the chat list's pin check no longer reads the page shown")
    val literals = checkBody.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    if (7 !in literals || 8 !in literals || checkBody.none { it.opcode == Opcode.IGET_BOOLEAN && it.controlRef() == DIALOG_PINNED }) {
        return missing("the chat list's pin check no longer tells folder tabs from All Chats")
    }

    val refs = LocalPinOrderRefs(pinned.toString(), type.first.toString(), type.second.toString(), moving.toString())
    try {
        requireReachable(refs.pinned, false)
        requireReachable(refs.pages, false)
        requireReachable(refs.type, false)
        requireReachable(refs.moving, false)
    } catch (hidden: PatchException) {
        return missing("the chat list's drag isn't reachable from the extension (${hidden.message})")
    }
    return LocalPinOrder(grab, grabAt, drop, dropAt, movedAt, refs, null)
}

/** Gives the drag's stubs Telegram's pin check, the page shown and the note that its pins moved. */
internal fun BytecodePatchContext.writeLocalPinOrderStubs(refs: LocalPinOrderRefs) {
    val activity = refs.pinned.substringBefore("->")
    writeStub(LOCAL_PINS, "stockPinned", 3, """
        check-cast p0, $activity
        check-cast p1, $DRAG_DIALOG
        invoke-virtual {p0, p1}, ${refs.pinned}
        move-result v0
        return v0
    """)
    writeStub(LOCAL_PINS, "shownType", 3, """
        check-cast p0, $activity
        iget-object v0, p0, ${refs.pages}
        const/4 v1, 0x0
        aget-object v0, v0, v1
        iget v0, v0, ${refs.type}
        return v0
    """)
    writeStub(LOCAL_PINS, "markMoved", 2, """
        check-cast p0, $activity
        const/4 v0, 0x1
        iput-boolean v0, p0, ${refs.moving}
        return-void
    """)
}

/** onMove(RecyclerView, ViewHolder, ViewHolder): swaps two chats of the list and their pinnedNum. */
private fun Method.isDragMove(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || implementation == null || returnType != "Z") return false
    if (parameterTypes.size != 3 || parameterTypes[0].toString() != DRAG_RECYCLER) return false
    val body = controlBody()
    return body.count { it.opcode == Opcode.INVOKE_STATIC && it.controlRef() == DRAG_SWAP } == 1 &&
        body.any { it.opcode == Opcode.IGET && it.controlRef() == DIALOG_PIN_NUM }
}

/** getMovementFlags(RecyclerView, ViewHolder). */
private fun Method.isMovementFlags(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && implementation != null && returnType == "I" &&
        parameterTypes.size == 2 && parameterTypes[0].toString() == DRAG_RECYCLER

/** An instance call that answers yes or no about one chat, the shape of isDialogPinned. */
private fun Instruction.isPinCheck(): Boolean {
    val call = call() ?: return false
    return (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) && call.returnType == "Z" &&
        call.parameterTypes.map(CharSequence::toString) == listOf(DRAG_DIALOG)
}
