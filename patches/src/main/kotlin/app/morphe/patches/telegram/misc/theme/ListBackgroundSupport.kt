/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.requireThisIntact
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlField
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.patches.telegram.misc.localcontrols.requireReachable
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.names.addInstructions
import app.morphe.util.names.origName
import app.morphe.util.names.tgClassDefByOrNull
import app.morphe.util.names.tgMutableClassDefBy
import app.morphe.util.names.tgMutableClassDefByOrNull
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val LIST_BACKGROUND = "$EXTENSION_PACKAGE/misc/ListBackground;"
internal const val ACTION_BAR_LAYOUT = "Lorg/telegram/ui/ActionBar/ActionBarLayout;"
private const val CONTEXT = "Landroid/content/Context;"
private const val FRAME_LAYOUT = "Landroid/widget/FrameLayout;"
private const val OBJECT = "Ljava/lang/Object;"

/** What Telegram's getDefaultColor answers for a key it has no default for, which it's the one place to write. */
private const val NO_DEFAULT = -65536L

/**
 * The picture behind the lists (#88): where Telegram works out the colors in use from the theme's
 * own, where a page layout makes the holders it slides its screens in, and what the extension's
 * stubs reach.
 */
internal class ListBackgroundSite(
    val refresh: MutableMethod,
    val holders: MutableMethod,
    val holderReturn: Int,
    val table: FieldReference,
    val defaultColor: Method,
    val pages: List<FieldReference>,
    val rebuild: Method,
) {
    /** The two hooks, on a copy of each method first so a refusal leaves the app untouched. */
    fun rehearse() = insert(MutableMethod(ImmutableMethod.of(refresh)), MutableMethod(ImmutableMethod.of(holders)))

    fun insert(refreshTarget: MutableMethod = refresh, holdersTarget: MutableMethod = holders) {
        refreshTarget.addInstructions(0, "invoke-static {}, $LIST_BACKGROUND->refreshing()V")
        holdersTarget.addInstructionsAtControlFlowLabel(holderReturn, "invoke-static {p0}, $LIST_BACKGROUND->laidOut($OBJECT)V")
    }

    /** Writes the extension's stubs, then the two hooks. */
    fun apply(context: BytecodePatchContext) = with(context) {
        writeStub(LIST_BACKGROUND, "table", 1, """
            sget-object v0, $table
            return-object v0
        """)
        // Colors only: the chat wallpaper stays loaded, as Telegram's own call with messages true leaves it.
        writeStub(LIST_BACKGROUND, "recolor", 2, """
            const/4 v0, 0x0
            const/4 v1, 0x1
            invoke-static {v0, v1}, ${refresh.reference()}
            return-void
        """)
        writeStub(LIST_BACKGROUND, "defaultColor", 1, """
            invoke-static {p0}, ${defaultColor.reference()}
            move-result p0
            return p0
        """)
        writeStub(LIST_BACKGROUND, "pages", 4, """
            instance-of v0, p0, $ACTION_BAR_LAYOUT
            if-eqz v0, :none
            check-cast p0, $ACTION_BAR_LAYOUT
            const/4 v0, 0x2
            new-array v0, v0, [Landroid/view/View;
            iget-object v1, p0, ${pages[0]}
            const/4 v2, 0x0
            aput-object v1, v0, v2
            iget-object v1, p0, ${pages[1]}
            const/4 v2, 0x1
            aput-object v1, v0, v2
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """)
        // Every screen in the stack, the one on top too, then the top one shown again.
        writeStub(LIST_BACKGROUND, "rebuild", 2, """
            instance-of v0, p0, $ACTION_BAR_LAYOUT
            if-eqz v0, :skip
            check-cast p0, $ACTION_BAR_LAYOUT
            const/4 v0, 0x1
            invoke-virtual {p0, v0, v0}, ${rebuild.reference()}
            :skip
            return-void
        """)
        insert()
    }
}

private fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Finds every target, or throws naming the first one missing, before anything is changed.
 *
 * Telegram keeps the theme's own colors in one static set, which its theme file reader's result
 * goes into, and copies them into the colors in use in one method that starts by reading that set:
 * refreshThemeColors(background, messages), the only (ZZ)V that does. Keys the theme leaves out come
 * from getDefaultColor, the one (I)I that answers -65536 and asks itself again for a key that falls
 * back to another. A page layout makes its holders in setFragmentStack: two page holders, then the
 * sheet holder, each the same FrameLayout built from the context and the layout. rebuildAllFragmentViews
 * is its one (ZZ)V that clears each screen's views.
 */
internal fun BytecodePatchContext.resolveListBackground(reader: Method): ListBackgroundSite {
    requireStatusMethod("listBackground")
    controlHook(LIST_BACKGROUND, "refreshing", emptyList(), "V")
    controlHook(LIST_BACKGROUND, "laidOut", listOf(OBJECT), "V")
    controlHook(LIST_BACKGROUND, "table", emptyList(), SPARSE_INT_ARRAY)
    controlHook(LIST_BACKGROUND, "recolor", emptyList(), "V")
    controlHook(LIST_BACKGROUND, "defaultColor", listOf("I"), "I")
    controlHook(LIST_BACKGROUND, "pages", listOf(OBJECT), "[Landroid/view/View;")
    controlHook(LIST_BACKGROUND, "rebuild", listOf(OBJECT), "V")

    val theme = tgMutableClassDefBy(reader.definingClass)
    val readerCall = "${reader.definingClass}->${reader.name}("

    // The theme's own set: what the reader's result is stored in.
    val stored = theme.methods.flatMap { method ->
        val body = method.controlBody()
        body.indices.mapNotNull { i ->
            val call = body[i].call()?.takeIf { body[i].opcode == Opcode.INVOKE_STATIC && it.toString().startsWith(readerCall) }
            val result = body.getOrNull(i + 1)?.takeIf { call != null && it.opcode == Opcode.MOVE_RESULT_OBJECT }?.namedRegisters()?.single()
            body.getOrNull(i + 2)?.takeIf { result != null && it.opcode == Opcode.SPUT_OBJECT && it.namedRegisters() == listOf(result) }?.controlField()
        }
    }
    val table = stored.distinctBy { it.toString() }.controlSingle("theme's own color set")
    controlShape(table.type == SPARSE_INT_ARRAY && table.definingClass == theme.type, "the theme's own color set moved")
    requireReachable(table.toString(), static = true)

    // refreshThemeColors starts by copying that set, and nothing jumps back to its start.
    val refresh = theme.methods.filter { method ->
        val body = method.controlBody()
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map { it.toString() } == listOf("Z", "Z") &&
            method.returnType == "V" && body.size > 2 && body[0].opcode == Opcode.SGET_OBJECT &&
            body[0].controlField()?.toString() == table.toString() &&
            body[1].call()?.let { it.name == "clone" && it.definingClass == SPARSE_INT_ARRAY } == true &&
            body[1].namedRegisters() == body[0].namedRegisters()
    }.controlSingle("theme color refresh")
    controlShape(AccessFlags.PUBLIC.isSet(refresh.accessFlags) && AccessFlags.PUBLIC.isSet(theme.accessFlags),
        "the theme color refresh can't be called from outside Telegram's theme code")
    controlShape(ControlFlow.of(refresh).normal.none { 0 in it }, "something jumps back to the start of the theme color refresh")

    // getDefaultColor.
    val defaults = theme.methods.filter { method ->
        val body = method.controlBody()
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map { it.toString() } == listOf("I") && method.returnType == "I" &&
            body.any { (it as? WideLiteralInstruction)?.wideLiteral == NO_DEFAULT } &&
            body.any { it.opcode == Opcode.INVOKE_STATIC && it.call()?.let { c -> c.definingClass == theme.type && c.name == method.name &&
                c.parameterTypes.map { p -> p.toString() } == listOf("I") && c.returnType == "I" } == true }
    }
    val defaultColor = defaults.controlSingle("theme default color")
    controlShape(AccessFlags.PUBLIC.isSet(defaultColor.accessFlags), "the theme default color can't be called from outside Telegram's theme code")

    // The page layout and the holders it makes.
    val layout = tgMutableClassDefByOrNull(ACTION_BAR_LAYOUT)
    controlShape(layout != null && AccessFlags.PUBLIC.isSet(layout.accessFlags), "the page layout is missing")
    val holders = layout!!.methods.filter { it.origName == "setFragmentStack" && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/util/List;") }
        .controlSingle("page layout's holder setup")
    val body = holders.controlBody()
    val made = body.indices.mapNotNull { i ->
        val type = body[i].takeIf { it.opcode == Opcode.NEW_INSTANCE }?.let { (it as ReferenceInstruction).reference.toString() }
            ?: return@mapNotNull null
        val built = body.getOrNull(i + 1)?.call()
        val kept = body.getOrNull(i + 2)?.controlField()
        if (built == null || kept == null || body[i + 1].opcode != Opcode.INVOKE_DIRECT || built.definingClass != type || built.name != "<init>" ||
            built.parameterTypes.map { it.toString() } != listOf(CONTEXT, ACTION_BAR_LAYOUT) ||
            body[i + 2].opcode != Opcode.IPUT_OBJECT || kept.definingClass != ACTION_BAR_LAYOUT || kept.type != type ||
            body[i + 2].namedRegisters().first() != body[i].namedRegisters().single()) null
        else kept
    }
    // The bottom tabs bar is built the same way when the layout has one; the holders are the one type built three times.
    val trios = made.groupBy { it.type }.values.filter { it.size == 3 }
    controlShape(made.size <= 4 && trios.size == 1 && trios[0].map { it.name }.distinct().size == 3,
        "the page layout no longer makes two page holders and a sheet holder")
    val holderFields = trios[0]
    controlShape(tgClassDefByOrNull(holderFields[0].type)?.superclass == FRAME_LAYOUT, "the page holders are no longer FrameLayouts")
    val pages = holderFields.take(2)
    pages.forEach { requireReachable(it.toString(), static = false) }
    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_VOID }
    val holderReturn = returns.controlSingle("page layout's holder setup's return")
    holders.requireThisIntact("AMOLED black", listOf(holderReturn))

    // rebuildAllFragmentViews.
    val rebuild = layout.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map { it.toString() } == listOf("Z", "Z") && method.returnType == "V" &&
            method.controlBody().any { it.opcode == Opcode.INVOKE_VIRTUAL && it.call()?.let { c -> c.name == "clearViews" && c.parameterTypes.isEmpty() } == true }
    }.controlSingle("page layout rebuild")
    controlShape(AccessFlags.PUBLIC.isSet(rebuild.accessFlags) && !AccessFlags.ABSTRACT.isSet(rebuild.accessFlags),
        "the page layout rebuild can't be called from outside it")

    return ListBackgroundSite(refresh, holders, holderReturn, table, defaultColor, pages, rebuild)
}
