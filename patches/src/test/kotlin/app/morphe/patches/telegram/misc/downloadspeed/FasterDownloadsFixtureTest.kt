/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.downloadspeed

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** FileLoadOperation.updateParams on both declared builds, where Telegram picks a download's piece size. */
class FasterDownloadsFixtureTest {
    private fun context(build: File): BytecodePatchContext {
        val host = FixtureDex.classes(build, setOf(LOAD_OPERATION, DOWNLOAD_CONTROLLER)).values.map(ImmutableClassDef::of)
        assertEquals("${build.name}: FileLoadOperation and DownloadController", 2, host.size)
        return PatchContexts.of(ExtensionDex.classes() + host)
    }

    private fun ClassDef.status(name: String) = methods.single { it.name == name }.controlBody()

    private fun BytecodePatchContext.flag(name: String) =
        (mutableClassDefBy(SETTINGS_STATUS).status(name).first() as WideLiteralInstruction).wideLiteral

    private fun literal(after: List<Instruction>, size: Long) =
        after.indices.single { (after[it] as? WideLiteralInstruction)?.wideLiteral == size }

    private fun Instruction.shape() = listOf(opcode, namedRegisters(), (this as? ReferenceInstruction)?.reference?.toString())

    @Test fun `the extension answers for the server's flag and the small-piece fallback still has the last word`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = context(build)
            val plan = context.resolveFasterDownloads()
            val old = ImmutableMethod.of(plan.method)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { fasterDownloadsPatch.execute(context) })

            val before = old.controlBody()
            val after = plan.method.controlBody()
            assertEquals("$name: two instructions", before.size + 2, after.size)
            val read = plan.read
            assertEquals(EXPERIMENTAL_PARAMS, after[read].controlRef())
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ), after.subList(read + 1, read + 4).map { it.opcode })
            assertEquals("$DOWNLOAD_SPEED->fast(Z)Z", after[read + 1].controlRef())
            for (i in read + 1..read + 3) assertEquals("$name: $i", listOf(plan.register), after[i].namedRegisters())

            // A no still goes to the 128 KB tier, and forceSmallChunk still sends a yes there too.
            val flow = ControlFlow.of(plan.method)
            val small = literal(after, SMALL_PIECE)
            val big = literal(after, BIG_PIECE)
            assertEquals("$name: the stock branch", setOf(read + 4, small), flow.normal[read + 3].toSet())
            assertEquals(FORCE_SMALL_CHUNK, after[read + 4].controlRef())
            assertEquals("$name: the fallback", setOf(read + 6, small), flow.normal[read + 5].toSet())
            assertEquals("$name: a yes reaches the 512 KB tier", read + 6, big)
            assertEquals(CHUNK_SIZE, after[big + 1].controlRef())
            assertEquals(CHUNK_SIZE, after[small + 1].controlRef())

            for (i in before.indices) {
                val j = if (i <= read) i else i + 2
                assertEquals("$name: stock $i", before[i].shape(), after[j].shape())
            }
            assertEquals(1L, context.flag("fasterDownloads"))
        }
    }

    @Test fun `a changed piece size choice or Data Saver runtime refuses before anything is edited`() {
        val build = Fixtures.declaredBuilds().first()
        val shapes = listOf<Pair<String, (BytecodePatchContext) -> Unit>>(
            "the server's flag no longer picks the piece size" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.replaceInstruction(plan.read + 1, "nop")
            },
            "the server's big-piece flag is missing or ambiguous" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.addInstruction(0, "iget-boolean v${plan.register}, v${plan.register}, $EXPERIMENTAL_PARAMS")
            },
            "Telegram's small-piece fallback no longer follows the server's flag" to { context ->
                val plan = context.resolveFasterDownloads()
                plan.method.replaceInstruction(plan.read + 3, "nop")
            },
            // The extension's own runtime refuses, since it ships with the patch.
            "holdMask runtime hook is missing or ambiguous" to { context ->
                context.mutableClassDefBy(DATA_SAVER).methods.removeAll { it.name == "holdMask" }
            },
            "checkAgain runtime hook is missing or ambiguous" to { context ->
                context.mutableClassDefBy(DATA_SAVER).methods.removeAll { it.name == "checkAgain" }
            },
        )
        for ((why, change) in shapes) {
            val context = context(build)
            change(context)
            val untouched = snapshot(context)
            try {
                fasterDownloadsPatch.execute(context)
                fail("$why: the patch went ahead")
            } catch (refused: PatchException) {
                assertTrue("$why: ${refused.message}", refused.message!!.contains(why))
            }
            assertEquals(why, untouched, snapshot(context))
            assertEquals(why, 0L, context.flag("fasterDownloads"))
            assertEquals(why, 0L, context.flag("dataSaverDownloads"))
        }
    }

    @Test fun `a changed Data Saver answer leaves the hold out and faster downloads goes on`() {
        val build = Fixtures.declaredBuilds().first()
        val shapes = listOf<Pair<String, (BytecodePatchContext) -> Unit>>(
            "DownloadController.canPreloadStories()Z no longer reads the network's download settings" to { context ->
                val method = context.mutableClassDefBy(DOWNLOAD_CONTROLLER).methods.single { it.name == "canPreloadStories" }
                val body = method.controlBody()
                method.replaceInstruction(body.indices.single { body[it].controlRef() == NETWORK_TYPE_READ },
                    "invoke-static {}, Lorg/telegram/messenger/ApplicationLoader;->isNetworkOnline()Z")
            },
            // An answer the hold doesn't know would let automatic downloads around it.
            "DownloadController has answers the hold doesn't know (canPreloadChannelStories()Z)" to { context ->
                val controller = context.mutableClassDefBy(DOWNLOAD_CONTROLLER)
                val stories = controller.methods.single { it.name == "canPreloadStories" }
                controller.methods.add(ImmutableMethod(stories.definingClass, "canPreloadChannelStories", stories.parameters,
                    stories.returnType, stories.accessFlags, stories.annotations, stories.hiddenApiRestrictions,
                    ImmutableMethod.of(stories).implementation).toMutable())
            },
            "DownloadController.checkAutodownloadSettings()V no longer reads the download mask" to { context ->
                val method = context.mutableClassDefBy(DOWNLOAD_CONTROLLER).methods.single { it.name == "checkAutodownloadSettings" }
                val body = method.controlBody()
                val at = body.indices.single { body[it].controlRef() == CURRENT_MASK }
                method.replaceInstruction(at, "invoke-virtual {v${body[at].namedRegisters().single()}}, $DOWNLOAD_CONTROLLER->getAutodownloadMaskAll()I")
            },
        )
        for ((why, change) in shapes) {
            val context = context(build)
            change(context)
            val controller = controllerSnapshot(context)
            val plan = context.resolveFasterDownloads()
            assertEquals(why, listOf("Faster downloads: $why. The patch goes on without holding automatic downloads on Data Saver."),
                PatchLogCapture.warnings { fasterDownloadsPatch.execute(context) })
            assertEquals(why, controller, controllerSnapshot(context))
            assertEquals(why, "$DOWNLOAD_SPEED->fast(Z)Z", plan.method.controlBody()[plan.read + 1].controlRef())
            assertEquals(why, listOf(Opcode.RETURN_VOID), checkAgain(context).map { it.opcode })
            assertEquals(why, 1L, context.flag("fasterDownloads"))
            assertEquals(why, 0L, context.flag("dataSaverDownloads"))
        }
    }

    /** Every method body the patch may edit, as opcodes and registers. */
    private fun snapshot(context: BytecodePatchContext) = listOf(LOAD_OPERATION, DOWNLOAD_CONTROLLER, DATA_SAVER)
        .flatMap { type -> context.mutableClassDefBy(type).methods.filter { it.implementation != null }
            .map { m -> "$type->${m.name}" to m.controlBody().map { it.opcode to it.namedRegisters() } } }

    /** Each DownloadController method by its signature, as opcodes, registers and references. */
    private fun controllerSnapshot(context: BytecodePatchContext) = context.mutableClassDefBy(DOWNLOAD_CONTROLLER).methods
        .filter { it.implementation != null }
        .associate { m -> "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}" to m.controlBody().map { it.shape() } }

    private fun checkAgain(context: BytecodePatchContext) = context.mutableClassDefBy(DATA_SAVER).methods.single { it.name == "checkAgain" }.controlBody()

    @Test fun `automatic downloads and the download mask ask the Data Saver hold first, and its check is Telegram's own`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = context(build)
            val saver = context.resolveDataSaverDownloads()
            assertEquals("$name: nothing missing", null, saver.missing)
            assertEquals("$name: every answer", AutoDownloadAnswer.entries.toSet(), saver.answers.keys)
            val stock = saver.answers.mapValues { ImmutableMethod.of(it.value).controlBody() }
            val signatures = saver.answers.entries.associate { (answer, _) -> answer.signature to answer }
            val others = controllerSnapshot(context).filterKeys { it !in signatures }
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { fasterDownloadsPatch.execute(context) })

            for ((answer, method) in saver.answers) {
                val where = "$name: ${answer.signature}"
                val before = stock.getValue(answer)
                val after = method.controlBody()
                val void = answer.signature.endsWith("V")
                val gate = if (void) 4 else 5
                assertEquals(where, before.size + gate, after.size)
                val call = if (answer == AutoDownloadAnswer.MASK) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC
                assertEquals(where, listOf(call, Opcode.MOVE_RESULT, Opcode.IF_EQZ), after.subList(0, 3).map { it.opcode })
                if (answer == AutoDownloadAnswer.MASK) {
                    // The mask hands over its controller, so the runtime can run Telegram's check on it later.
                    // A range call, so p0 is reached past v15 too.
                    assertEquals(where, "$DATA_SAVER->holdMask(Ljava/lang/Object;)Z", after[0].controlRef())
                    assertEquals(where, listOf(method.implementation!!.registerCount - 1), after[0].namedRegisters())
                } else {
                    assertEquals(where, "$DATA_SAVER->hold()Z", after[0].controlRef())
                }
                val held = after[1].namedRegisters()
                assertEquals(where, held, after[2].namedRegisters())
                if (void) {
                    assertEquals(where, Opcode.RETURN_VOID, after[3].opcode)
                } else {
                    assertEquals(where, Opcode.CONST_4, after[3].opcode)
                    assertEquals(where, 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals(where, Opcode.RETURN, after[4].opcode)
                    assertEquals(where, held, after[4].namedRegisters())
                }
                // A no carries on into Telegram's own answer, untouched.
                assertEquals(where, setOf(3, gate), ControlFlow.of(method).normal[2].toSet())
                for (i in before.indices) assertEquals("$where: stock $i", before[i].shape(), after[i + gate].shape())
            }
            // The rest of DownloadController, its network change check among it, stays Telegram's.
            assertEquals(name, others, controllerSnapshot(context).filterKeys { it !in signatures })

            val stub = checkAgain(context)
            assertEquals(name, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), stub.map { it.opcode })
            assertEquals(name, DOWNLOAD_CONTROLLER, stub[0].controlRef())
            assertEquals(name, CHECK_SETTINGS, stub[1].controlRef())
            assertEquals(name, stub[0].namedRegisters(), stub[1].namedRegisters())
            assertEquals(1L, context.flag("dataSaverDownloads"))
        }
    }
}

private const val NETWORK_TYPE_READ = "Lorg/telegram/messenger/ApplicationLoader;->getAutodownloadNetworkType()I"
