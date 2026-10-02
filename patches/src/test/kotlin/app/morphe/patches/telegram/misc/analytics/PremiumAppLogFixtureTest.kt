/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.analytics

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A whole-fixture census, followed by mutation of only the handful of report-owning classes. */
class PremiumAppLogFixtureTest {
    @Test
    fun `every app-log constructor has a classified payload and operational callers remain explicit`() {
        for (build in Fixtures.declaredBuilds()) {
            val builders = hosts(build).flatMap { it.methods }.filter(::buildsAppLog)
            assertEquals("${build.name}: complete constructor census", 7, builders.size)
            assertEquals(7, builders.sumOf { method -> method.instructions().count { it.call()?.let { call ->
                call.definingClass == SAVE_APP_LOG && call.name == "<init>"
            } == true } })
            val classified = builders.filter { method -> method.strings().any { it in CLASSIFIED_TYPES } }
            assertEquals("six literal types and one dynamic push-token batch", 6, classified.size)
            assertEquals(CLASSIFIED_TYPES, classified.flatMap { it.strings().filter { type -> type in CLASSIFIED_TYPES } }.toSet())
            for (event in PremiumPromoEvent.entries) {
                val method = builders.single { event.type in it.strings() }
                val strings = method.strings()
                when (event) {
                    PremiumPromoEvent.SHOW -> assertTrue("view origin", "source" in strings)
                    PremiumPromoEvent.TAP -> assertTrue("chosen feature", "item" in strings)
                    else -> assertTrue("accept/cancellation carry JSON null", method.instructions().any {
                        it.opcode == Opcode.NEW_INSTANCE && it.reference() == JSON_NULL
                    })
                }
            }
            val push = (builders - classified.toSet()).single()
            assertEquals("operational registration owner", "Lorg/telegram/messenger/PushListenerController;", push.definingClass)
            assertTrue(push.strings().containsAll(listOf("fcm", "hcm", "_token_request", "_token_response")))
            assertEquals("both token timing events share one batch", 2, push.instructions().count {
                it.opcode == Opcode.NEW_INSTANCE && it.reference() == APP_EVENT
            })
            assertEquals(2, push.instructions().count { it.opcode == Opcode.NEW_INSTANCE && it.reference() == JSON_NULL })
            assertTrue(push.instructions().any { it.call()?.name == "setRegId" })
            assertTrue(push.instructions().any { it.reference() == "Lorg/telegram/messenger/SharedConfig;->pushStringGetTimeStart:J" })
            assertTrue(push.instructions().any { it.reference() == "Lorg/telegram/messenger/SharedConfig;->pushStringGetTimeEnd:J" })
            val camera = builders.single { "android_dual_camera" in it.strings() }
            assertTrue("camera support payload", "device" in camera.strings())
            for (field in listOf("MANUFACTURER", "MODEL")) assertTrue(camera.instructions().any {
                it.reference() == "Landroid/os/Build;->$field:Ljava/lang/String;"
            })
            assertTrue("camera state logging stays after the request", camera.instructions().any {
                it.call()?.toString() == "Lorg/telegram/messenger/ApplicationLoader;->logDualCamera(ZZ)V"
            })
            val storage = builders.single { "android_sdcard_exists" in it.strings() }
            assertTrue("the existing storage hook reports a boolean, not folder paths", storage.instructions().any {
                it.opcode == Opcode.NEW_INSTANCE && it.reference() == "Lorg/telegram/tgnet/TLRPC\$TL_jsonBool;"
            })
        }
    }

    @Test
    fun `all verified interactions gain send guards while every stock operation branch and payload survives`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val hooks = context.resolvePremiumPromoHooks()
            assertEquals(PremiumPromoEvent.entries.toSet(), hooks.keys)
            val before = hooks.mapValues { ImmutableMethod.of(it.value.method) }
            val stock = classes.flatMap { it.methods }.filter { method -> buildsAppLog(method) &&
                method.strings().none { it in CLASSIFIED_TYPES } }
            assertEquals("push-token reporting is retained", 1, stock.size)
            val camera = classes.flatMap { it.methods }.single { buildsAppLog(it) && "android_dual_camera" in it.strings() }
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { disableAnalyticsPatch.execute(context) })
            for ((event, hook) in hooks) {
                val original = before.getValue(event)
                val old = original.instructions()
                val after = hook.method.instructions()
                assertEquals("$event injects exactly four instructions", old.size + 4, after.size)
                assertTrue(after[hook.index].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO))
                assertEquals(event.type, after[hook.index].string())
                assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
                    after.subList(hook.index + 1, hook.index + 4).map { it.opcode })
                assertEquals("$ANALYTICS->skipPremiumAppLog(Ljava/lang/String;)Z", after[hook.index + 1].reference())
                assertEquals("$event retains every original operand and reference", old.map(::operation),
                    (after.take(hook.index) + after.drop(hook.index + 4)).map(::operation))
                assertEquals(original.implementation!!.registerCount, hook.method.implementation!!.registerCount)
                val oldFlow = ControlFlow.of(original)
                val newFlow = ControlFlow.of(hook.method)
                fun moved(index: Int) = index + if (index >= hook.index) 4 else 0
                fun entry(index: Int) = if (index == hook.index) hook.index else moved(index)
                for (index in old.indices) {
                    assertEquals("$event original branch $index enters the guard when needed",
                        oldFlow.normal[index].map(::entry), newFlow.normal[moved(index)])
                    assertEquals("$event retains handler $index",
                        oldFlow.exceptional[index].map(::entry), newFlow.exceptional[moved(index)])
                }
                assertEquals("false sends; true reaches stock cleanup/return", setOf(hook.index + 4, moved(hook.continuation)),
                    newFlow.normal[hook.index + 3].toSet())
                assertFalse("$event cannot send without its guard", hook.index + 4 in reachable(newFlow, 0, hook.index))
                val oldPayloads = payloadTargets(original)
                val newPayloads = payloadTargets(hook.method)
                for ((index, target) in oldPayloads) assertEquals("$event retains payload target $index", moved(target), newPayloads[moved(index)])
            }
            val fail = hooks.getValue(PremiumPromoEvent.FAIL)
            val cleanup = fail.method.instructions().drop(fail.continuation + 4)
            assertEquals("billing cleanup stays the true destination", "Lorg/telegram/messenger/BillingController;->onCanceled:Ljava/lang/Runnable;",
                cleanup[0].reference())
            assertTrue("cancellation handler is still cleared", cleanup.any {
                it.opcode == Opcode.IPUT_OBJECT && it.reference() == cleanup[0].reference()
            })
            assertTrue("both completion paths keep their callbacks", cleanup.count { it.call()?.toString() == "Ljava/lang/Runnable;->run()V" } >= 2)
            assertTrue("payment assignment is retained", cleanup.any { it.reference() == "Lorg/telegram/tgnet/TLRPC\$TL_payments_assignPlayMarketTransaction;" })
            for (method in stock + camera) assertEquals("${build.name}: operational/support path stays stock", method.instructions().map(::operation),
                context.mutableClassDefBy(method.definingClass).methods.single { it.sameSignature(method) }.instructions().map(::operation))
            assertFlags(context, PremiumPromoEvent.entries.toSet())
        }
    }

    @Test
    fun `changed known payload or billing continuation refuses before any analytics target is edited`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            for (event in PremiumPromoEvent.entries) {
                val context = PatchContexts.of(ExtensionDex.classes() + classes)
                val hook = context.resolvePremiumPromoHooks().getValue(event)
                val at = if (event == PremiumPromoEvent.FAIL) hook.continuation else hook.method.instructions().indexOfFirst {
                    it.opcode == Opcode.IPUT_OBJECT && it.reference() == "$APP_EVENT->data:Lorg/telegram/tgnet/TLRPC\$JSONValue;"
                }
                assertTrue(at >= 0)
                hook.method.replaceInstruction(at, "nop")
                val before = snapshots(context, classes)
                try {
                    disableAnalyticsPatch.execute(context)
                    fail("changed $event geometry was accepted")
                } catch (expected: PatchException) {
                    assertTrue(expected.message.orEmpty().contains("before editing"))
                }
                assertEquals("$event must not partly mutate another target", before, snapshots(context, classes))
                assertFlags(context, emptySet(), oldTargets = false, family = false)
            }
        }
    }

    @Test
    fun `an absent verified type keeps the other five capabilities and reports exactly what is absent`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            for (event in PremiumPromoEvent.entries) {
                val reduced = classes.map { owner -> withMethods(owner, owner.methods.filter { event.type !in it.strings() }) }
                val context = PatchContexts.of(ExtensionDex.classes() + reduced)
                val warnings = PatchLogCapture.warnings { disableAnalyticsPatch.execute(context) }
                assertEquals(1, warnings.size)
                assertTrue(warnings.single(), warnings.single().contains(event.type))
                assertFlags(context, PremiumPromoEvent.entries.toSet() - event)
            }
        }
    }

    @Test
    fun `unknown type in an otherwise familiar builder stays stock and loses only its capability`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val setup = PatchContexts.of(ExtensionDex.classes() + classes)
            val show = setup.resolvePremiumPromoHooks().getValue(PremiumPromoEvent.SHOW)
            val at = show.method.instructions().indexOfFirst { it.string() == PremiumPromoEvent.SHOW.type }
            val register = show.method.instructions()[at].namedRegisters().single()
            show.method.replaceInstruction(at, "const-string v$register, \"support.report\"")
            val replaced = classes.map { if (it.type == show.method.definingClass) ImmutableClassDef.of(setup.mutableClassDefBy(it.type)) else it }
            val context = PatchContexts.of(ExtensionDex.classes() + replaced)
            val unknown = context.mutableClassDefBy(show.method.definingClass).methods.single { it.sameSignature(show.method) }
            val before = unknown.instructions().map(::operation)
            val warnings = PatchLogCapture.warnings { disableAnalyticsPatch.execute(context) }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains(PremiumPromoEvent.SHOW.type))
            assertEquals(before, unknown.instructions().map(::operation))
            assertFlags(context, PremiumPromoEvent.entries.toSet() - PremiumPromoEvent.SHOW)
        }
    }

    @Test
    fun `ambiguous telemetry builders and no surviving target refuse without partial edits`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val owner = classes.single { it.methods.any { method -> PremiumPromoEvent.SHOW.type in method.strings() } }
            val type = "Lorg/telegram/ui/PremiumInteractionReplica;"
            val duplicate = ImmutableClassDef(type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations,
                emptyList(), owner.methods.filter(::buildsAppLog).map { method -> ImmutableMethod(type, method.name, method.parameters,
                    method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions, method.implementation) })
            val context = PatchContexts.of(ExtensionDex.classes() + classes + duplicate)
            val before = snapshots(context, classes)
            try { disableAnalyticsPatch.execute(context); fail("ambiguous builder accepted") }
            catch (expected: PatchException) { assertTrue(expected.message.orEmpty().contains("ambiguous")) }
            assertEquals(before, snapshots(context, classes))
            assertFlags(context, emptySet(), oldTargets = false, family = false)
        }
        val empty = PatchContexts.of(ExtensionDex.classes())
        try { disableAnalyticsPatch.execute(empty); fail("empty coverage accepted") }
        catch (expected: PatchException) { assertTrue(expected.message.orEmpty().contains("none of the 6")) }
        assertFlags(empty, emptySet(), oldTargets = false, family = false)
    }

    private fun hosts(build: File) = FixtureDex.classesWhere(build, { true }) { method -> buildsAppLog(method) || method.instructions().any {
        it.opcode == Opcode.NEW_INSTANCE && it.reference() == REPORT_READ_METRICS
    } }
    private fun buildsAppLog(method: Method) = method.instructions().any { it.opcode == Opcode.NEW_INSTANCE && it.reference() == SAVE_APP_LOG }
    private fun snapshots(context: BytecodePatchContext, classes: List<ClassDef>) = classes.flatMap { owner ->
        context.mutableClassDefBy(owner.type).methods.filter { buildsAppLog(it) || it.instructions().any { instruction ->
            instruction.opcode == Opcode.NEW_INSTANCE && instruction.reference() == REPORT_READ_METRICS
        } }.map { it.toString() to it.instructions().map(::operation) }
    }.toMap()
    private fun withMethods(owner: ClassDef, methods: Iterable<Method>) = ImmutableClassDef(owner.type, owner.accessFlags,
        owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations, owner.fields, methods)
    private fun assertFlags(context: BytecodePatchContext, covered: Set<PremiumPromoEvent>, oldTargets: Boolean = true, family: Boolean = true) {
        val expected = PremiumPromoEvent.entries.associate { it.capability to (it in covered) } +
            mapOf("deviceStats" to oldTargets, "readMetrics" to oldTargets, "disableAnalytics" to family)
        for ((flag, enabled) in expected) {
            val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }.instructions()
            assertEquals(flag, if (enabled) 1 else 0, (body[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, body[1].opcode)
        }
    }
    private fun payloadTargets(method: Method): Map<Int, Int> {
        val body = method.instructions()
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        return body.indices.filter { body[it].opcode in setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA) }.associateWith {
            addresses.indexOf(addresses[it] + (body[it] as OffsetInstruction).codeOffset).also { target -> assertTrue(target >= 0) }
        }
    }
    private fun reachable(flow: ControlFlow, from: Int, blocked: Int): Set<Int> {
        val found = mutableSetOf<Int>()
        val pending = ArrayDeque<Int>()
        pending.add(from)
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            if (index == blocked || !found.add(index)) continue
            pending.addAll(flow.normal[index])
        }
        return found
    }
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.strings() = instructions().mapNotNull { it.string() }
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun operation(instruction: Instruction) = listOf(instruction.opcode, instruction.reference(), instruction.namedRegisters(),
        (instruction as? WideLiteralInstruction)?.wideLiteral)
    private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
    private companion object {
        const val ANALYTICS = "Lapp/hushtelegram/extension/telegram/misc/Analytics;"
        const val APP_EVENT = "Lorg/telegram/tgnet/TLRPC\$TL_inputAppEvent;"
        const val JSON_NULL = "Lorg/telegram/tgnet/TLRPC\$TL_jsonNull;"
        val CLASSIFIED_TYPES = PremiumPromoEvent.entries.map { it.type }.toSet() + setOf("android_sdcard_exists", "android_dual_camera")
    }
}
