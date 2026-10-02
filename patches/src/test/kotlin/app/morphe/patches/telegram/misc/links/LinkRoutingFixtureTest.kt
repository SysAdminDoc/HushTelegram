/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.links

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real pinned host methods prove sink scope, both patch orders and untouched stock control flow. */
class LinkRoutingFixtureTest {
    private val trackingFlags = listOf("stripLinkTracking", "openedLinkTracking", "sharedLinkTracking")
    private val externalFlags = listOf("openExternalLinks", "externalBrowserRouting")

    @Test fun `both selectable patches work in either order and retain all stock operands branches and handlers`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hostClasses(build)
            for (externalFirst in listOf(true, false)) {
                val context = PatchContexts.of(ExtensionDex.classes() + classes)
                val plan = context.resolveLinkHooks()
                assertEquals("${build.name}: the three verified Share Link chooser sinks", 3, plan.shares.size)
                assertTrue("${build.name}: the kept custom-tab share receiver is covered", plan.shares.any {
                    it.method.definingClass == "Lorg/telegram/messenger/ShareBroadcastReceiver;" && it.method.name == "onReceive"
                })
                val methods = (listOf(plan.browser) + plan.shares.map { it.method }).distinct()
                val original = methods.associate { key(it) to ImmutableMethod.of(it) }
                if (externalFirst) {
                    openExternalLinksPatch.execute(context)
                    stripLinkTrackingPatch.execute(context)
                } else {
                    stripLinkTrackingPatch.execute(context)
                    openExternalLinksPatch.execute(context)
                }
                for (method in methods) assertStock(build.name, original.getValue(key(method)), method)
                for (flag in trackingFlags + externalFlags) assertFlag(context, flag, true)
                val protection = context.mutableClassDefBy(LINKS).methods.single { it.name == "protectedByTelegram" }
                val calls = protection.instructions().mapNotNull { it.ref() }
                assertTrue("the exact discovered native classifier is called", plan.classifier.toString() in calls)
                assertTrue("auth domains keep stock routing", "$MC->authDomains:Ljava/util/Set;" in calls)
                assertTrue("autologin domains keep stock routing", "$MC->autologinDomains:Ljava/util/Set;" in calls)
                assertTrue("the selected account is used", "$UC->selectedAccount:I" in calls)
                assertEquals("one route guard", 1, plan.browser.instructions().count { it.ref()?.startsWith("$LINKS->tryOpenExternal(") == true })
                assertEquals("one opened-URI cleaner", 1, plan.browser.instructions().count { it.ref()?.startsWith("$LINKS->cleanOpenedUri(") == true })
                for (share in plan.shares) assertEquals("only the chooser target is passed to cleaning", 1,
                    share.method.instructions().count { it.ref()?.startsWith("$LINKS->cleanShareIntent(") == true })
            }
        }
    }

    @Test fun `each patch is selectable independently and makes only its own capabilities true`() {
        for (build in Fixtures.declaredBuilds()) for (external in listOf(true, false)) {
            val context = PatchContexts.of(ExtensionDex.classes() + hostClasses(build))
            val plan = context.resolveLinkHooks()
            val beforeShares = plan.shares.associate { key(it.method) to it.method.instructions().map(::operands) }
            if (external) openExternalLinksPatch.execute(context) else stripLinkTrackingPatch.execute(context)
            externalFlags.forEach { assertFlag(context, it, external) }
            trackingFlags.forEach { assertFlag(context, it, !external) }
            if (external) for (share in plan.shares) assertEquals("routing doesn't touch sharing", beforeShares.getValue(key(share.method)), share.method.instructions().map(::operands))
            assertEquals(if (external) 0 else 1, plan.browser.instructions().count { it.ref()?.startsWith("$LINKS->cleanOpenedUri(") == true })
            assertEquals(if (external) 1 else 0, plan.browser.instructions().count { it.ref()?.startsWith("$LINKS->tryOpenExternal(") == true })
        }
    }

    @Test fun `missing status methods refuse before host or native-protection stub edits`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hostClasses(build)
            for (missing in trackingFlags + externalFlags) {
                val context = PatchContexts.of(ExtensionDex.classes() + classes)
                val plan = context.resolveLinkHooks()
                val methods = listOf(plan.browser) + plan.shares.map { it.method }
                val before = methods.map { it.instructions().map(::operands) }
                val stub = context.mutableClassDefBy(LINKS).methods.single { it.name == "protectedByTelegram" }
                val stubBefore = stub.instructions().map(::operands)
                context.mutableClassDefBy(SETTINGS_STATUS).methods.removeAll { it.name == missing }
                assertThrows(PatchException::class.java) {
                    if (missing in externalFlags) openExternalLinksPatch.execute(context) else stripLinkTrackingPatch.execute(context)
                }
                assertEquals(before, methods.map { it.instructions().map(::operands) })
                assertEquals(stubBefore, context.mutableClassDefBy(LINKS).methods.single { it.name == "protectedByTelegram" }.instructions().map(::operands))
            }
        }
    }

    @Test fun `a wrong native flag-array shape or altered Share Link action refuses before any insertion`() {
        for (build in Fixtures.declaredBuilds()) for (badFlags in listOf(true, false)) {
            val context = PatchContexts.of(ExtensionDex.classes() + hostClasses(build))
            val plan = context.resolveLinkHooks()
            if (badFlags) {
                val instructions = plan.browser.instructions()
                val classifier = instructions.indexOfFirst { it.ref() == plan.classifier.toString() }
                val arrayRegister = instructions[classifier].namedRegisters()[2]
                val array = (0 until classifier).last { instructions[it].opcode == Opcode.NEW_ARRAY && instructions[it].namedRegisters()[0] == arrayRegister }
                val countRegister = instructions[array].namedRegisters()[1]
                val count = (0 until array).last { instructions[it].opcode == Opcode.CONST_4 && instructions[it].namedRegisters()[0] == countRegister }
                plan.browser.replaceInstruction(count, "const/4 v$countRegister, 0x2")
            } else {
                val receiver = plan.shares.single { it.method.definingClass == "Lorg/telegram/messenger/ShareBroadcastReceiver;" }.method
                val action = receiver.instructions().indexOfFirst { it.ref() == "android.intent.action.SEND" }
                val register = receiver.instructions()[action].namedRegisters().single()
                receiver.replaceInstruction(action, "const-string v$register, \"android.intent.action.VIEW\"")
                // It no longer matches a share candidate. The required kept receiver must refuse.
            }
            val methods = listOf(plan.browser) + plan.shares.map { it.method }
            val before = methods.map { it.instructions().map(::operands) }
            assertThrows(PatchException::class.java) { stripLinkTrackingPatch.execute(context) }
            assertEquals("no host is half-patched", before, methods.map { it.instructions().map(::operands) })
            (trackingFlags + externalFlags).forEach { assertFlag(context, it, false) }
        }
    }

    @Test fun `inaccessible or nonconcrete link runtime refuses before host scope or status changes`() {
        for (build in Fixtures.declaredBuilds()) for (name in listOf("tryOpenExternal", "cleanOpenedUri", "cleanShareIntent")) {
            for (mutation in 0..4) {
                val context = PatchContexts.of(ExtensionDex.classes() + hostClasses(build))
                val plan = context.resolveLinkHooks()
                val owner = context.mutableClassDefBy(LINKS)
                val method = owner.methods.single { it.name == name }
                when (mutation) {
                    0 -> owner.accessFlags = owner.accessFlags and AccessFlags.PUBLIC.value.inv()
                    1 -> method.accessFlags = method.accessFlags and AccessFlags.PUBLIC.value.inv()
                    2 -> method.accessFlags = method.accessFlags and AccessFlags.STATIC.value.inv()
                    3 -> method.accessFlags = method.accessFlags or AccessFlags.NATIVE.value
                    4 -> method.accessFlags = method.accessFlags or AccessFlags.ABSTRACT.value
                }
                val methods = listOf(plan.browser) + plan.shares.map { it.method } + owner.methods
                val before = methods.map { it.instructions().map(::operands) }
                assertThrows(PatchException::class.java) {
                    if (name == "tryOpenExternal") openExternalLinksPatch.execute(context) else stripLinkTrackingPatch.execute(context)
                }
                assertEquals("all host and runtime bodies remain unchanged", before, methods.map { it.instructions().map(::operands) })
                (trackingFlags + externalFlags).forEach { assertFlag(context, it, false) }
            }
        }
    }

    private fun hostClasses(build: File): List<ClassDef> {
        val sinks = FixtureDex.classesWhere(build, { true }) { method ->
            (method.returnType == "V" && method.parameterTypes.size == 10 &&
                method.parameterTypes.take(2) == listOf("Landroid/content/Context;", "Landroid/net/Uri;") &&
                method.instructions().any { it.ref() == "autologin_token" }) ||
                (method.instructions().any { it.ref() == "Lorg/telegram/messenger/R\$string;->ShareLink:I" } &&
                    method.instructions().any { it.ref() == "android.intent.action.SEND" })
        }
        val kept = FixtureDex.classes(build, setOf(MC, UC))
        assertEquals("account protection definitions", setOf(MC, UC), kept.keys)
        return sinks + kept.values
    }

    private fun assertStock(where: String, original: Method, patched: Method) {
        val before = original.instructions()
        val after = patched.instructions()
        val injected = mutableListOf<IntRange>()
        for (index in after.indices) {
            when {
                after[index].ref()?.startsWith("$LINKS->tryOpenExternal(") == true -> injected += index - 1..index + 4
                after[index].ref()?.startsWith("$LINKS->cleanOpenedUri(") == true -> injected += index..index + 3
                after[index].ref()?.startsWith("$LINKS->cleanShareIntent(") == true -> injected += index..index + 1
            }
        }
        val stock = after.indices.filter { index -> injected.none { index in it } }
        assertEquals("$where: every stock instruction is retained", before.map(::operands), stock.map { operands(after[it]) })
        assertEquals("$where: registers are retained", original.implementation!!.registerCount, patched.implementation!!.registerCount)
        val toOld = stock.withIndex().associate { it.value to it.index }
        fun oldTarget(target: Int): Int {
            val site = injected.singleOrNull { target in it }
            return toOld.getValue(site?.let { it.last + 1 } ?: target)
        }
        val oldFlow = ControlFlow.of(original)
        val newFlow = ControlFlow.of(patched)
        for (index in before.indices) {
            assertEquals("$where: stock normal branch $index", oldFlow.normal[index].toSet(), newFlow.normal[stock[index]].map(::oldTarget).toSet())
            assertEquals("$where: stock exception handler $index", oldFlow.exceptional[index].toSet(), newFlow.exceptional[stock[index]].map(::oldTarget).toSet())
        }
        for (site in injected) {
            if (after[site.first + 1].ref()?.startsWith("$LINKS->tryOpenExternal(") == true) {
                assertEquals(Opcode.IF_EQZ, after[site.first + 3].opcode)
                assertEquals(Opcode.RETURN_VOID, after[site.first + 4].opcode)
                assertEquals("success returns; false resumes stock", setOf(site.first + 4, site.last), newFlow.normal[site.first + 3].toSet())
            }
        }
    }

    private fun operands(instruction: Instruction): List<Any?> = listOf(
        instruction.opcode, instruction.namedRegisters(), instruction.ref(),
        (instruction as? WideLiteralInstruction)?.wideLiteral,
        (instruction as? SwitchPayload)?.switchElements?.map { it.key },
        (instruction as? ArrayPayload)?.arrayElements,
    )

    private fun assertFlag(context: BytecodePatchContext, name: String, enabled: Boolean) {
        val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions()
        assertEquals(Opcode.CONST_4, body[0].opcode)
        assertEquals(name, if (enabled) 1 else 0, (body[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[1].opcode)
    }

    private fun key(method: Method) = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference?.toString()
    private companion object {
        const val MC = "Lorg/telegram/messenger/MessagesController;"
        const val UC = "Lorg/telegram/messenger/UserConfig;"
    }
}
