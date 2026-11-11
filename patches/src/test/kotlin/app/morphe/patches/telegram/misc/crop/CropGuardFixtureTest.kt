/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.crop

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import app.morphe.util.names.origName
import app.morphe.util.names.origRef
import app.morphe.util.names.tgMutableClassDefBy

/** PhotoViewer's applyCurrentEditMode, the crop view's Done button and the photo editor's send. */
class CropGuardFixtureTest {
    @Test fun `a crop that can't finish stops before Done or Send goes on`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val viewer = FixtureDex.classes(build, setOf(CROP_VIEWER)).values
            val callers = FixtureDex.classesWhere(build, { true }) { m -> m.readsCropAreaAndCallsViewer() }
            val hosts = (viewer + callers).distinctBy { it.type }.map(ImmutableClassDef::of)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            val sites = context.resolveCropGuard()
            val apply = ImmutableMethod.of(sites.apply)
            val done = ImmutableMethod.of(sites.done)
            val send = ImmutableMethod.of(sites.send)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { cropGuardPatch.execute(context) })

            val after = sites.apply.controlBody()
            val self = sites.self
            assertEquals("$name: begin first", CROP_GUARD_BEGIN, after[0].controlRef())
            assertEquals("$name: one begin", 1, after.count { it.controlRef() == CROP_GUARD_BEGIN })

            // The photo check, in front of the crop branch's first read and taking its jumps.
            val entry = after.single { it.controlRef() == CROP_GUARD_MISSING }.let(after::indexOf) - 5
            assertEquals("$name: photo check", ENTRY_SHAPE, after.subList(entry, entry + ENTRY_SHAPE.size).map { it.opcode })
            val photoScratch = after[entry].namedRegisters().first()
            assertNotEquals("$name: photo check keeps this", self, photoScratch)
            assertEquals("$name: photo check registers",
                listOf(listOf(photoScratch, self), listOf(photoScratch), listOf(photoScratch, self)) + List(5) { listOf(photoScratch) },
                after.subList(entry, entry + 8).map { it.namedRegisters() })
            assertEquals("$name: skips videos", sites.video, after[entry].controlRef())
            assertEquals("$name: reads the shown photo", sites.photo, after[entry + 2].controlRef())
            assertEquals(GET_BITMAP, after[entry + 3].controlRef())
            assertEquals("$name: stock read after the check", apply.controlBody()[sites.entry].origRef(), after[entry + 10].origRef())

            // The copy check, between the copy's move-result and the paint path read.
            val bake = after.single { it.controlRef() == CROP_GUARD_NOT_MADE }.let(after::indexOf) - 2
            assertEquals("$name: copy check", BAKE_SHAPE, after.subList(bake, bake + BAKE_SHAPE.size).map { it.opcode })
            val bakeScratch = after[bake].namedRegisters().first()
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[bake - 1].opcode)
            assertEquals("$name: copy check reads the copy", listOf(sites.cropped), after[bake - 1].namedRegisters())
            assertNotEquals("$name: copy kept", sites.cropped, bakeScratch)
            assertNotEquals("$name: this kept", self, bakeScratch)
            assertEquals("$name: copy check registers",
                listOf(listOf(bakeScratch, self), listOf(bakeScratch), listOf(sites.cropped), listOf(bakeScratch), listOf(bakeScratch)),
                after.subList(bake, bake + 5).map { it.namedRegisters() })
            assertEquals("$name: skips videos", sites.video, after[bake].controlRef())
            assertEquals(PAINT_PATH, after[bake + BAKE_SHAPE.size].controlRef())

            // A failed copy takes the crop off the photo's edit state and the viewer's, the two the stock code just set.
            val stockBody = apply.controlBody()
            assertEquals("$name: the copy's call", stockBody[sites.bake].origRef(), after[bake - 2].origRef())
            val cropArgument = after[bake - 2].namedRegisters()[1]
            val cropRead = stockBody.indices.last { it < sites.bake && stockBody[it].namedRegisters().firstOrNull() == cropArgument &&
                stockBody[it].opcode == Opcode.IGET_OBJECT }
            assertEquals("$name: crop read off the photo", listOf(CROP_STATE_WRITE, listOf(cropArgument, sites.photoState)),
                listOf(stockBody[cropRead].controlRef(), stockBody[cropRead].namedRegisters()))
            assertEquals("$name: crop handed to the viewer", 1, (cropRead + 1 until sites.bake).count {
                stockBody[it].opcode == Opcode.IPUT_OBJECT && stockBody[it].controlRef() == sites.viewCrop &&
                    stockBody[it].namedRegisters() == listOf(cropArgument, sites.viewState)
            })
            // The cropped drawing and stickers go to the viewer the same way, straight off the photo's state.
            for ((field, view) in listOf(CROPPED_PAINT_PATH to sites.viewPaint, CROPPED_ENTITIES to sites.viewEntities)) {
                assertEquals("$name: $field handed to the viewer", 1, (cropRead + 1 until sites.bake).count {
                    stockBody[it].opcode == Opcode.IPUT_OBJECT && stockBody[it].controlRef() == view &&
                        stockBody[it - 1].controlRef() == field && stockBody[it - 1].namedRegisters()[1] == sites.photoState &&
                        stockBody[it].namedRegisters() == listOf(stockBody[it - 1].namedRegisters()[0], sites.viewState)
                })
            }
            assertNotEquals("$name: viewer kept", sites.viewState, bakeScratch)
            assertNotEquals("$name: photo state kept", sites.photoState, bakeScratch)
            assertEquals("$name: crops cleared",
                listOf(listOf(bakeScratch)) + List(4) { listOf(bakeScratch, sites.photoState) } + List(3) { listOf(bakeScratch, sites.viewState) },
                after.subList(bake + 5, bake + 13).map { it.namedRegisters() })
            assertEquals(0L, (after[bake + 5] as WideLiteralInstruction).wideLiteral)
            assertEquals(listOf(CROP_STATE_WRITE, CROPPED_PAINT_PATH, CROPPED_ENTITIES, IS_CROPPED, sites.viewCrop, sites.viewPaint, sites.viewEntities),
                after.subList(bake + 6, bake + 13).map { it.controlRef() })

            val flow = ControlFlow.of(sites.apply).normal
            for (branch in listOf(entry + 1, entry + 7)) assertEquals("$name: photo check $branch", setOf(branch + 1, entry + 9), flow[branch].toSet())
            for (branch in listOf(bake + 1, bake + 4)) {
                assertEquals("$name: copy check $branch", setOf(branch + 1, bake + BAKE_SHAPE.size - 1), flow[branch].toSet())
            }
            assertKept("$name apply", apply, sites.apply, setOf(0) + (entry until entry + ENTRY_SHAPE.size) + (bake until bake + BAKE_SHAPE.size),
                landsOn = mapOf(sites.entry to entry, sites.bake + 2 to bake))

            // Done and Send hold right after their apply call; a jump past it still lands on the stock code.
            for ((stock, patched, next) in listOf(Triple(done, sites.done, sites.doneAt), Triple(send, sites.send, sites.sendAt))) {
                val body = patched.controlBody()
                val call = next - 1
                assertEquals("$name: ${patched.name} applies", "$CROP_VIEWER->${sites.apply.origName}()V", body[call].controlRef())
                assertEquals("$name: ${patched.name} holds", HELD_SHAPE, body.subList(next, next + HELD_SHAPE.size).map { it.opcode })
                assertEquals(CROP_GUARD_HELD, body[next].controlRef())
                val scratch = body[next + 1].namedRegisters()
                assertEquals("$name: ${patched.name} checks the answer", scratch, body[next + 2].namedRegisters())
                assertEquals(setOf(next + 3, next + 4), ControlFlow.of(patched).normal[next + 2].toSet())
                assertKept("$name ${patched.name}", stock, patched, (next until next + HELD_SHAPE.size).toSet(),
                    fallsInto = mapOf(call to next))
            }
            val holds = hosts.sumOf { host ->
                context.tgMutableClassDefBy(host).methods.sumOf { m -> m.controlBody().count { it.controlRef() == CROP_GUARD_HELD } }
            }
            assertEquals("$name: only Done and Send hold", 2, holds)

            val status = context.tgMutableClassDefBy(SETTINGS_STATUS).methods.single { it.origName == "cropGuard" }.controlBody()
            assertEquals(1L, (status.first() as WideLiteralInstruction).wideLiteral)
        }
    }

    /**
     * Every stock instruction is still there, in order and unchanged, and goes where it went: a jump
     * to a stock target in [landsOn] now lands on the code put in front of it, and a stock
     * instruction in [fallsInto] now falls into the code put after it.
     */
    private fun assertKept(
        name: String,
        stock: Method,
        patched: Method,
        added: Set<Int>,
        landsOn: Map<Int, Int> = emptyMap(),
        fallsInto: Map<Int, Int> = emptyMap(),
    ) {
        val before = stock.controlBody()
        val after = patched.controlBody()
        val old = before.indices.filter { before[it].opcode != Opcode.NOP }
        val new = after.indices.filter { it !in added && after[it].opcode != Opcode.NOP }
        assertEquals("$name: stock count", old.size, new.size)
        val at = old.zip(new).toMap()
        for ((o, n) in at) {
            assertEquals("$name: stock $o", listOf(kind(before[o]), before[o].namedRegisters(), before[o].origRef()),
                listOf(kind(after[n]), after[n].namedRegisters(), after[n].origRef()))
        }
        val was = ControlFlow.of(stock).normal
        val now = ControlFlow.of(patched).normal
        for ((o, n) in at) {
            val expected = was[o].map { t -> fallsInto[o]?.takeIf { t == o + 1 } ?: landsOn[t] ?: at.getValue(t) }.toSet()
            assertEquals("$name: where stock $o goes", expected, now[n].toSet())
        }
    }

    private fun kind(instruction: Instruction) = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun Method.readsCropAreaAndCallsViewer(): Boolean {
        val code = implementation?.instructions ?: return false
        var area = false
        var viewer = false
        for (instruction in code) {
            val reference = (instruction as? ReferenceInstruction)?.reference
            if (reference is FieldReference && reference.definingClass == CROP_AREA_VIEW) area = true
            if (reference is MethodReference && reference.definingClass == CROP_VIEWER) viewer = true
        }
        return area && viewer
    }

    private companion object {
        val ENTRY_SHAPE = listOf(
            Opcode.IGET_BOOLEAN, Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.NOP,
        )
        val BAKE_SHAPE = listOf(
            Opcode.IGET_BOOLEAN, Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
            Opcode.CONST_4, Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN,
            Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID, Opcode.NOP,
        )
        val HELD_SHAPE = listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID)
    }
}
