/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.maintabs

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireThisIntact
import app.morphe.patches.telegram.misc.localcontrols.Mention
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlField
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.forEachClassMentioning
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.names.loadsInt
import app.morphe.util.names.origDefiningClass
import app.morphe.util.names.origName
import app.morphe.util.names.origType
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val DOWNLOADS_BUTTON = "$MAIN_TABS->downloadsButton(F)F"
internal const val DOWNLOADS_TABS = "Lorg/telegram/messenger/R\$string;->DownloadsTabs:I"

/**
 * The chat list's one animated visibility call for its Downloads button, or [missing], why this
 * build can't have the switch. [at] is that call in [method], and [factor] the register holding
 * how visible Telegram wants the button.
 */
internal class DownloadsButton(val method: MutableMethod?, val at: Int, val factor: Int, val missing: String?) {
    /** The same edit on a copy, so a smali problem refuses before the app is touched. */
    fun rehearse() {
        if (method != null) insert(MutableMethod(ImmutableMethod.of(method)))
    }

    fun apply() {
        if (method != null) insert(method)
    }

    private fun insert(target: MutableMethod) {
        target.addInstructionsAtControlFlowLabel(at, """
            invoke-static/range {v$factor .. v$factor}, $DOWNLOADS_BUTTON
            move-result v$factor
        """)
    }
}

/**
 * The chat list labels its Downloads button once, as it builds its top bar, and from then on shows
 * or hides it through one call that takes the button and a factor from 0 to 1 (whether a download
 * runs or waits, times the search, side and Done animations). The runtime answers that factor, so
 * a hidden button fades out the way Telegram fades it, and nothing else on the bar is touched.
 */
internal fun BytecodePatchContext.resolveDownloadsButton(): DownloadsButton {
    controlHook(MAIN_TABS, "downloadsButton", listOf("F"), "F")
    fun missing(why: String) = DownloadsButton(null, 0, 0, why)

    val fields = mutableMapOf<String, String>()
    forEachClassMentioning(Mention.reference(DOWNLOADS_TABS)) { cls ->
        if (cls.origType.startsWith("Lapp/hushtelegram/")) return@forEachClassMentioning
        for (m in cls.methods) {
            if (AccessFlags.STATIC.isSet(m.accessFlags) || m.implementation == null) continue
            val body = m.controlBody()
            // Play and Plus copy this out of its parameter register at the top of createView, so the
            // label is tied to the class's own field rather than to the register the button is read through.
            for (index in body.indices) {
                if (!body[index].loadsInt() || body[index].controlRef() != DOWNLOADS_TABS) continue
                val read = body.getOrNull(index - 1) ?: continue
                val field = read.controlField() ?: continue
                val registers = read.namedRegisters()
                if (read.opcode != Opcode.IGET_OBJECT || field.origDefiningClass != m.origDefiningClass) continue
                val labeled = body.subList(index + 1, minOf(body.size, index + 5)).any { next ->
                    val call = next.call()
                    next.opcode == Opcode.INVOKE_VIRTUAL && call?.origName == "setContentDescription" &&
                        call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;") &&
                        next.namedRegisters().first() == registers[0]
                }
                if (labeled) fields[field.toString()] = cls.type
            }
        }
    }
    if (fields.size != 1) return missing("the chat list no longer labels one Downloads button (${fields.size})")
    val (field, owner) = fields.entries.single()

    val sites = mutableListOf<Pair<MutableMethod, Int>>()
    for (m in tgMutableClassDefBy(owner).methods) {
        if (AccessFlags.STATIC.isSet(m.accessFlags) || m.implementation == null) continue
        val body = m.controlBody()
        val self = m.localRegisterCount()
        for (index in 0 until body.size - 1) {
            val read = body[index]
            if (read.opcode != Opcode.IGET_OBJECT || read.controlField()?.toString() != field || read.namedRegisters()[1] != self) continue
            val shown = body[index + 1]
            val call = shown.call() ?: continue
            if (shown.opcode == Opcode.INVOKE_STATIC && call.returnType == "V" &&
                call.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "F") &&
                shown.namedRegisters().first() == read.namedRegisters()[0]) {
                sites += m to index + 1
            }
        }
    }
    if (sites.size != 1) return missing("the Downloads button no longer shows through one animated visibility call (${sites.size})")
    val (method, at) = sites.single()
    val (view, factor) = method.controlBody()[at].namedRegisters()
    if (view == factor) return missing("the Downloads button's visibility factor shares a register with the button")
    if (factor > 255) return missing("the Downloads button's visibility factor sits in a register a call can't name")
    try {
        method.requireThisIntact("Downloads button", listOf(at - 1))
    } catch (changed: PatchException) {
        return missing("the Downloads button is no longer read from the chat list itself")
    }
    return DownloadsButton(method, at, factor, null)
}
