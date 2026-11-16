/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.translate

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlField
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.names.origType
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val PINNED_BAR_HIDDEN = "$TRANSLATE_BAR->pinnedBarHidden()Z"
private const val CHAT_FULL = "Lorg/telegram/tgnet/TLRPC\$ChatFull;"
private const val USER_FULL = "Lorg/telegram/tgnet/TLRPC\$UserFull;"
private const val SET_TAG = "Landroid/view/View;->setTag(Ljava/lang/Object;)V"
/** Telegram keeps the pinned message a person closed in a chat under this and the chat id. */
private const val CLOSED_PIN = "pin_"
/** How many moves and gotos the hide path may take before it calls the hide. 13.2 takes two. */
private const val HIDE_REACH = 6
private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * Where the chat screen decides whether its pinned message bar shows, or [missing], why this build
 * can't have the switch. [method] is the decision, [head] its first check, which asks whether the
 * chat is a comment thread, [scratch] the register that check loads and nothing reads before, and
 * [hide] the start of Telegram's own path that hides the bar.
 */
internal class PinnedBar(
    val method: MutableMethod?,
    private val head: Int,
    private val scratch: Int,
    private val hide: Int,
    val missing: String?,
) {
    /** A yes from the extension takes Telegram's hide path ahead of every check of the screen's own. */
    fun apply(target: MutableMethod) {
        target.addInstructionsAtControlFlowLabel(head, """
            invoke-static {}, $PINNED_BAR_HIDDEN
            move-result v$scratch
            if-nez v$scratch, :hush_pinned_hidden
        """, ExternalLabel("hush_pinned_hidden", target.getInstruction(hide)))
    }

    /** The hook put into a copy, so a method that can't take it is known before anything changes. */
    fun rehearse(): String? {
        val decision = method ?: return missing
        return try {
            apply(MutableMethod(ImmutableMethod.of(decision)))
            null
        } catch (refused: PatchException) {
            "the pinned message bar's decision can't take the hook (${refused.message})"
        }
    }
}

/**
 * Telegram's chat screen decides on its pinned message bar in one method, and that method is the only
 * place that shows it: it clears the bar's hidden tag and slides it in. The bar starts hidden when
 * it's made. Every reason to keep it hidden (a comment thread's own bar, no chat info yet, nothing
 * pinned, a pin the person closed, search or selection in the top bar) takes one path, which calls the
 * screen's hide and then lays the chat out again, so nothing is left where the bar was. Telegram's
 * checks start by asking whether the chat is a comment thread, then for the chat's or user's info.
 */
internal fun BytecodePatchContext.resolvePinnedBar(screen: String): PinnedBar {
    controlHook(TRANSLATE_BAR, "pinnedBarHidden", emptyList(), "Z")
    requireStatusMethod("hidePinnedBar")
    fun missing(why: String) = PinnedBar(null, -1, -1, -1, why)

    val screenClass = tgMutableClassDefBy(screen)
    val decisions = screenClass.methods.filter { method ->
        val body = method.controlBody()
        body.any { it.controlString() == CLOSED_PIN } && body.indices.any { body.infoChecks(it, screen) }
    }
    val method = decisions.singleOrNull() ?: return missing(if (decisions.isEmpty())
        "the chat screen no longer checks a closed pin and the chat's info in one place" else
        "the chat screen checks a closed pin and the chat's info in ${decisions.size} places")
    val body = method.controlBody()
    val checks = body.indices.filter { body.infoChecks(it, screen) }
    val info = checks.singleOrNull() ?: return missing("the pinned message bar checks the chat's info ${checks.size} times")
    val flow = try {
        ControlFlow.of(method)
    } catch (unreadable: IllegalArgumentException) {
        return missing("the pinned message bar's decision can't be read (${unreadable.message})")
    }
    val hide = flow.normal[info + 3].first()

    // From the hide path's start, past the moves of the animate flag and the gotos, to the screen's
    // hide, which answers whether anything changed and tags the bar hidden.
    var at = hide
    var hides = false
    for (step in 1..HIDE_REACH) {
        val instruction = body.getOrNull(at) ?: break
        if (instruction.opcode in MOVES) { at++; continue }
        if (instruction.opcode in GOTOS) { at = flow.normal[at].single(); continue }
        val call = instruction.call()
        val hider = screenClass.methods.singleOrNull { it.name == call?.name &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "Z" }
        hides = instruction.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) && call != null &&
            call.definingClass == screen && body.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT &&
            hider?.controlBody()?.any { it.controlRef() == SET_TAG } == true
        break
    }
    if (!hides) return missing("the pinned message bar's hide path no longer hides it")

    val head = info - 4
    val thread = body.getOrNull(head)
    val threadField = thread?.controlField()
    if (thread !is TwoRegisterInstruction || thread.opcode != Opcode.IGET_OBJECT || threadField == null || threadField.definingClass != screen ||
        threadField.origType != MESSAGE_OBJECT || body[head + 1].opcode != Opcode.IF_EQZ || flow.normal[head + 1].first() != info ||
        body[head + 2].opcode != Opcode.IGET_BOOLEAN || body[head + 3].opcode != Opcode.IF_EQZ) {
        return missing("the pinned message bar no longer starts by asking whether the chat is a comment thread")
    }
    val scratch = thread.registerA
    if (scratch == thread.registerB || scratch in RegisterLiveness.of(method).liveInto(head)) {
        return missing("the comment thread check's register is still in use where it's loaded")
    }
    return PinnedBar(method, head, scratch, hide, null)
}

/**
 * Whether instruction [at] starts the check for the chat's info: the chat's, and when it has none,
 * the user's, with no info at all hiding the bar.
 */
private fun List<Instruction>.infoChecks(at: Int, screen: String): Boolean {
    if (at + 3 >= size) return false
    fun read(index: Int, type: String) = this[index].opcode == Opcode.IGET_OBJECT &&
        this[index].controlField()?.let { it.definingClass == screen && it.origType == type } == true
    val register = (this[at] as? OneRegisterInstruction)?.registerA ?: return false
    fun tests(index: Int, opcode: Opcode) = this[index].opcode == opcode && (this[index] as OneRegisterInstruction).registerA == register
    return read(at, CHAT_FULL) && tests(at + 1, Opcode.IF_NEZ) && read(at + 2, USER_FULL) &&
        (this[at + 2] as OneRegisterInstruction).registerA == register && tests(at + 3, Opcode.IF_EQZ)
}
