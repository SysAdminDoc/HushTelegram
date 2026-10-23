/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.maintabs

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.localcontrols.Mention
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.forEachClassMentioning
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val NEW_MESSAGE_BUTTON = "$MAIN_TABS->newMessageButton(Ljava/lang/Object;)V"
internal const val COMPOSE_ICON = "Lorg/telegram/messenger/R\$drawable;->filled_fab_compose_32:I"
internal const val NEW_MESSAGE_TITLE = "Lorg/telegram/messenger/R\$string;->NewMessageTitle:I"

/**
 * The chat list's round pencil button, or [missing], why this build can't have the switch.
 * [at] is the instruction in [method] that starts the pencil's look, and [register] holds the
 * button there.
 */
internal class NewMessageButton(val method: MutableMethod?, val at: Int, val register: Int, val missing: String?) {
    /** The same edit on a copy, so a smali problem refuses before the app is touched. */
    fun rehearse() {
        if (method != null) insert(MutableMethod(ImmutableMethod.of(method)))
    }

    fun apply() {
        if (method != null) insert(method)
    }

    private fun insert(target: MutableMethod) {
        target.addInstructionsAtControlFlowLabel(at, "invoke-static/range {v$register .. v$register}, $NEW_MESSAGE_BUTTON")
    }
}

/**
 * The chat list sets its round button's picture from one method: the check mark while choosing
 * chats, the pencil the rest of the time. The pencil's branch is the only place that loads the
 * compose icon, so the hook goes right where that picture is set and the chat list's other
 * round buttons, the story camera among them, are left alone.
 */
internal fun BytecodePatchContext.resolveNewMessageButton(): NewMessageButton {
    controlHook(MAIN_TABS, "newMessageButton", listOf("Ljava/lang/Object;"), "V")
    fun missing(why: String) = NewMessageButton(null, 0, 0, why)

    val found = mutableListOf<Pair<MutableMethod, Int>>()
    forEachClassMentioning(Mention.allReferences(listOf(COMPOSE_ICON, NEW_MESSAGE_TITLE))) { cls ->
        if (cls.type.startsWith("Lapp/hushtelegram/")) return@forEachClassMentioning
        mutableClassDefBy(cls.type).methods.forEach { m ->
            if (AccessFlags.STATIC.isSet(m.accessFlags) || m.returnType != "V" || m.parameterTypes.isNotEmpty()) return@forEach
            val body = m.controlBody()
            val icons = body.indices.filter { body[it].opcode == Opcode.SGET && body[it].controlRef() == COMPOSE_ICON }
            if (icons.size == 1 && body.any { it.controlRef() == NEW_MESSAGE_TITLE }) found += m to icons.single()
        }
    }
    if (found.size != 1) return missing("the chat list no longer sets its new message button in one place (${found.size})")
    val (method, icon) = found.single()
    val body = method.controlBody()
    val set = body.getOrNull(icon + 1)
    val call = set?.call()
    if (set?.opcode != Opcode.INVOKE_VIRTUAL || call?.name != "setImageResource" || call.parameterTypes.map(CharSequence::toString) != listOf("I")) {
        return missing("the new message button's picture is no longer set right after the icon loads")
    }
    val button = set.namedRegisters().first()
    if (button == body[icon].namedRegisters().first()) return missing("the new message button shares a register with its icon")
    if (button > 255) return missing("the new message button sits in a register a call can't name")
    return NewMessageButton(method, icon, button, null)
}
