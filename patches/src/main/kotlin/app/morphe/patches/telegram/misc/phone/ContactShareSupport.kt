/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.phone

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.freeLocalsAt
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlField
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val CONTACT_SHARE = "$EXTENSION_PACKAGE/misc/ContactShare;"
internal const val STARTS_TICKED = "$CONTACT_SHARE->startsTicked(Ljava/lang/Object;ZZ)Z"
internal const val ADD_CONTACT =
    "Lorg/telegram/messenger/ContactsController;->addContact(Lorg/telegram/tgnet/TLRPC\$User;Lorg/telegram/tgnet/TLRPC\$TL_textWithEntities;Z)V"
/** The setting Telegram's add contact bar leaves behind when it offers to share your number. */
internal const val EXCEPTION_KEY = "dialog_bar_exception"

/**
 * Telegram's add contact screen: [offer] says the share box is shown, [box] is whether it's ticked,
 * and [view], its createView, ticks it at [tick] behind the offer. Saving passes offer && box to addContact.
 */
internal class ContactShareSite(val offer: String, val box: String, val view: MutableMethod, val tick: Int,
                                private val before: String, private val after: String) {
    /** Reads the box before Telegram ticks it, then lets the runtime decide what it holds. */
    fun apply(target: MutableMethod) {
        // Plain, so both skips past the tick keep landing on Telegram's next instruction and never run it.
        target.addInstructions(tick + 1, after)
        target.addInstructionsAtControlFlowLabel(tick, before)
    }

    /** The same edit on a copy, so a smali problem refuses before the app is touched. */
    fun rehearse() = apply(MutableMethod(ImmutableMethod.of(view)))
}

/**
 * The save is the one addContact call whose share boolean is the screen's offer flag and then its
 * box flag. The offer comes from Telegram's dialog_bar_exception setting in onFragmentCreate, the
 * constructors leave the box clear, and the screen ticks it in one place, createView, which only the
 * offer's check reaches.
 */
internal fun BytecodePatchContext.resolveContactShare(): ContactShareSite {
    controlHook(CONTACT_SHARE, "startsTicked", listOf("Ljava/lang/Object;", "Z", "Z"), "Z")
    val saves = mutableListOf<Pair<FieldReference, FieldReference>>()
    classDefForEach { cls ->
        if (cls.type.startsWith("Lapp/hushtelegram/")) return@classDefForEach
        for (method in cls.methods) {
            val body = method.controlBody()
            body.indices.filter { body[it].controlRef() == ADD_CONTACT }.forEach { at -> shareOperand(method, at)?.let(saves::add) }
        }
    }
    val (offer, box) = saves.controlSingle("add contact save that reads the share box")
    controlShape(offer.definingClass == box.definingClass && offer.name != box.name && offer.type == "Z" && box.type == "Z",
        "the share box and its offer aren't one screen's flags")
    val screen = mutableClassDefBy(offer.definingClass)

    val created = screen.methods.filter { it.name == "onFragmentCreate" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        .controlSingle("add contact screen's onFragmentCreate").controlBody()
    controlShape(created.any { it.controlString() == EXCEPTION_KEY } &&
        created.any { it.opcode == Opcode.IPUT_BOOLEAN && it.controlField() == offer },
        "the share box offer no longer comes from Telegram's add contact bar")

    controlShape(screen.methods.filter { it.name == "<init>" }.all { constructor ->
        val body = constructor.controlBody()
        body.indices.filter { body[it].controlField() == box }.all { at -> body[at].opcode == Opcode.IPUT_BOOLEAN && storesZero(body, at) }
    }, "a new add contact screen doesn't start with the share box clear")

    val ticks = screen.methods.filter { it.name != "<init>" }.flatMap { method ->
        val body = method.controlBody()
        body.indices.filter { body[it].opcode == Opcode.IPUT_BOOLEAN && body[it].controlField() == box }.map { method to it }
    }
    val (view, tick) = ticks.controlSingle("share box tick")
    controlShape(view.name == "createView" && view.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;"),
        "Telegram ticks the share box outside createView")
    val body = view.controlBody()
    val (value, owner) = body[tick].namedRegisters()
    val flow = ControlFlow.of(view)
    val into = body.indices.filter { tick in flow.normal[it] || tick in flow.exceptional[it] }
    controlShape(tick >= 2 && into == listOf(tick - 1) && body[tick - 1].opcode == Opcode.IF_EQZ &&
        flow.normal[tick - 1].toSet() == setOf(tick, tick + 1) && flow.normal[tick] == listOf(tick + 1) &&
        body[tick - 2].opcode == Opcode.IGET_BOOLEAN && body[tick - 2].controlField() == offer &&
        body[tick - 2].namedRegisters() == listOf(body[tick - 1].namedRegisters().single(), owner),
        "createView ticks the share box without the offer's check right before it")
    controlShape(value <= 15 && owner <= 15, "the share box tick's registers don't fit the runtime call")
    // Written in front of the tick and read right after it, where the tick names it nowhere.
    val scratch = view.freeLocalsAt("Hide phone number share box", tick, 1).single()
    val before = "iget-boolean v$scratch, v$owner, $box"
    val after = "invoke-static {v$owner, v$value, v$scratch}, $STARTS_TICKED\nmove-result v$scratch\niput-boolean v$scratch, v$owner, $box"
    return ContactShareSite(offer.toString(), box.toString(), view, tick, before, after)
}

/**
 * The flags behind addContact's share boolean at [at]: `offer && box` read off one object, as
 * `iget offer; if-eqz; iget box; if-eqz; move b, yes; goto; move b, no`. Null for any other call.
 */
private fun shareOperand(method: Method, at: Int): Pair<FieldReference, FieldReference>? {
    val body = method.controlBody()
    if (at < 7) return null
    val shared = body[at].namedRegisters().lastOrNull() ?: return null
    val offer = body[at - 7]
    val box = body[at - 5]
    if (offer.opcode != Opcode.IGET_BOOLEAN || box.opcode != Opcode.IGET_BOOLEAN || body[at - 6].opcode != Opcode.IF_EQZ ||
        body[at - 4].opcode != Opcode.IF_EQZ || body[at - 2].opcode !in GOTOS) return null
    if (body[at - 6].namedRegisters() != offer.namedRegisters().take(1) || body[at - 4].namedRegisters() != box.namedRegisters().take(1) ||
        offer.namedRegisters()[1] != box.namedRegisters()[1]) return null
    if (body[at - 3].opcode !in MOVES || body[at - 1].opcode !in MOVES ||
        body[at - 3].namedRegisters().first() != shared || body[at - 1].namedRegisters().first() != shared) return null
    val flow = ControlFlow.of(method)
    // Either flag clear lands on "no"; both set falls through to "yes", which jumps over it.
    if (flow.normal[at - 6].toSet() != setOf(at - 5, at - 1) || flow.normal[at - 4].toSet() != setOf(at - 3, at - 1) ||
        flow.normal[at - 2] != listOf(at)) return null
    return offer.controlField()!! to box.controlField()!!
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/** The value stored at [at] is the const 0 last written to its register, as the constructors do. */
private fun storesZero(body: List<Instruction>, at: Int): Boolean {
    val value = body[at].namedRegisters().first()
    val write = (at - 1 downTo 0).firstOrNull { body[it].opcode.setsRegister() && body[it].namedRegisters().firstOrNull() == value }
        ?: return false
    return body[write].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16) && (body[write] as NarrowLiteralInstruction).narrowLiteral == 0
}
