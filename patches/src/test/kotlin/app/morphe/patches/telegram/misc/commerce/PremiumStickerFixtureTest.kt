/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.commerce

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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Premium stickers under Hide Premium, gifts and Stars, against both real builds. */
class PremiumStickerFixtureTest {
    @Test
    fun `the sticker filters and the keyboard ask Commerce and the effect player asks first`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolvePremiumStickerHooks()
            assertEquals("${build.name}: both targets", PremiumStickerTarget.entries.toSet(), plan.keys)

            val stickers = plan.getValue(PremiumStickerTarget.STICKERS)
            assertEquals("${build.name}: two filters and the keyboard pass", 3, stickers.size)
            val filters = stickers.filter { it.method.definingClass == CONTROLLER }
            assertEquals("${build.name}: one pack and a list of packs", setOf(STICKER_SET, "Ljava/util/ArrayList;"),
                filters.map { it.method.parameterTypes.single().toString() }.toSet())
            assertTrue("${build.name}: both filters are filterPremiumStickers", filters.all { it.method.name == "filterPremiumStickers" })
            val keyboard = stickers.single { it.method.definingClass != CONTROLLER }
            assertEquals("${build.name}: the keyboard pass takes the refresh flag", listOf("Z"),
                keyboard.method.parameterTypes.map { it.toString() })
            val effects = plan.getValue(PremiumStickerTarget.EFFECTS)
            val tooltip = effects.single { it.replace }
            val player = effects.single { !it.replace }
            assertTrue("${build.name}: the tooltip names Premium stickers",
                tooltip.method.instructions().any { it.reference() == STICKER_TOOLTIP })
            assertEquals("${build.name}: the guard comes first", 0, player.index)
            assertEquals("${build.name}: one class plays effects and shows the tooltip", tooltip.method.definingClass, player.method.definingClass)

            val swaps = stickers + tooltip
            val originals = swaps.associate { it.method.key() to ImmutableMethod.of(it.method) }
            val playerBefore = ImmutableMethod.of(player.method)
            val warnings = PatchLogCapture.warnings { hideCommercePatch.execute(context) }
            // Every warning names the patch, so look for the sticker capabilities themselves.
            assertTrue("${build.name}: no Premium sticker warning $warnings",
                warnings.none { warning -> PremiumStickerTarget.entries.any { it.capability in warning } })

            for (swap in swaps) {
                val before = originals.getValue(swap.method.key()).instructions()
                val after = swap.method.instructions()
                assertEquals("${build.name}: ${swap.method.name} keeps its length", before.size, after.size)
                for (index in before.indices) {
                    if (index != swap.index) {
                        assertEquals("${build.name}: ${swap.method.name} keeps $index", operation(before[index]), operation(after[index]))
                        continue
                    }
                    assertEquals("$CONTROLLER->premiumFeaturesBlocked()Z", before[index].reference())
                    assertEquals(Opcode.INVOKE_STATIC, after[index].opcode)
                    assertEquals("$COMMERCE->premiumStickersBlocked(Ljava/lang/Object;)Z", after[index].reference())
                    assertEquals("${build.name}: the same controller is asked", before[index].namedRegisters(), after[index].namedRegisters())
                    assertEquals(Opcode.MOVE_RESULT, after[index + 1].opcode)
                }
            }

            val before = playerBefore.instructions()
            val after = player.method.instructions()
            val guard = after.size - before.size
            assertEquals("${build.name}: an eight-instruction guard", 8, guard)
            for (index in before.indices) {
                assertEquals("${build.name}: the player keeps $index", operation(before[index]), operation(after[index + guard]))
            }
            val cell = player.method.implementation!!.registerCount - 3
            assertEquals(listOf(Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.NOP), after.take(guard).map { it.opcode })
            assertEquals("${build.name}: the cell is checked and read", listOf(cell), after[0].namedRegisters())
            assertEquals("${player.method.parameterTypes[0]}->getMessageObject()Lorg/telegram/messenger/MessageObject;", after[1].reference())
            assertEquals("$COMMERCE->skipPremiumEffect(Ljava/lang/Object;)Z", after[3].reference())
            assertTrue("${build.name}: the guard uses a local, not a parameter", after.take(guard).drop(2).dropLast(1)
                .all { it.namedRegisters().all { register -> register < cell - 1 } })
            val flow = ControlFlow.of(player.method)
            // The keep label is the guard's own nop, which falls into the stock start; a label that
            // moved onto the guard would make it jump to itself.
            val keep = guard - 1
            assertEquals("${build.name}: a null cell skips the question", setOf(1, keep), flow.normal[0].toSet())
            assertEquals("${build.name}: a kept effect skips the return", setOf(6, keep), flow.normal[5].toSet())
            assertEquals("${build.name}: the keep path reaches the stock start", listOf(guard), flow.normal[keep])
            assertTrue("${build.name}: nothing jumps back into the guard",
                (guard until after.size).none { from -> flow.normal[from].any { it < guard } })

            for (target in PremiumStickerTarget.entries) assertFact(context, target.capability, 1)
            val stubs = context.mutableClassDefBy(COMMERCE).methods.associateBy { it.name }
            assertTrue(stubs.getValue("premiumBlocked").instructions().any { it.reference() == "$CONTROLLER->premiumFeaturesBlocked()Z" })
            assertTrue(stubs.getValue("premiumAccount").instructions().any { it.reference() == "Lorg/telegram/messenger/UserConfig;->isPremium()Z" })
            assertTrue(stubs.getValue("premiumSticker").instructions().any { it.reference() == "Lorg/telegram/messenger/MessageObject;->isPremiumSticker()Z" })
            assertTrue(stubs.getValue("messageAccountPremium").instructions().any {
                it.reference() == "Lorg/telegram/messenger/MessageObject;->currentAccount:I" })
        }
    }

    @Test
    fun `a missing keyboard pass leaves the stickers out whole and the effects still apply`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val keyboard = context.resolvePremiumStickerHooks().getValue(PremiumStickerTarget.STICKERS)
                .single { it.method.definingClass != CONTROLLER }
            val filters = context.resolvePremiumStickerHooks().getValue(PremiumStickerTarget.STICKERS)
                .filter { it.method.definingClass == CONTROLLER }.map { it.method to it.method.instructions().map(::operation) }
            val register = keyboard.method.instructions()[keyboard.index].namedRegisters().single()
            keyboard.method.replaceInstruction(keyboard.index, "invoke-virtual {v$register}, $CONTROLLER->isClientActivated()Z")
            val warnings = PatchLogCapture.warnings { hideCommercePatch.execute(context) }
            assertTrue("${build.name}: $warnings", warnings.any { "commercePremiumStickers" in it })
            assertFact(context, "commercePremiumStickers", 0)
            assertFact(context, "commercePremiumEffects", 1)
            for ((method, before) in filters) {
                assertEquals("${build.name}: ${method.name} is left as it was", before, method.instructions().map(::operation))
            }
        }
    }

    @Test
    fun `a second tooltip refuses before anything changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val initial = PatchContexts.of(ExtensionDex.classes() + hosts)
            val tooltip = initial.resolvePremiumStickerHooks().getValue(PremiumStickerTarget.EFFECTS).single { it.replace }.method
            val original = hosts.single { it.type == tooltip.definingClass }
            val duplicate = ImmutableMethod(tooltip.definingClass, "tooltipAmbiguity", tooltip.parameters, tooltip.returnType,
                tooltip.accessFlags, tooltip.annotations, tooltip.hiddenApiRestrictions, tooltip.implementation)
            val altered = ImmutableClassDef(original.type, original.accessFlags, original.superclass, original.interfaces,
                original.sourceFile, original.annotations, original.fields, original.methods.toList() + duplicate)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts.filter { it.type != original.type } + altered)
            try {
                hideCommercePatch.execute(context)
                fail("${build.name}: a second tooltip was accepted")
            } catch (expected: PatchException) {
                assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("ambiguous Premium sticker tooltip"))
            }
            for (classDef in hosts) for (stock in classDef.methods) {
                val after = context.mutableClassDefBy(classDef.type).methods.single { it.key() == stock.key() }
                assertEquals("${build.name}: ambiguity retains $stock", stock.instructions().map(::operation), after.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            for (target in PremiumStickerTarget.entries) assertFact(context, target.capability, 0)
        }
    }

    /** MessagesController and the two classes that hold the places, found by what they reference. */
    private fun hosts(build: File): List<ClassDef> {
        val anchors = FixtureDex.classesWhere(build, { true }) { method ->
            val refs = method.instructions().mapNotNull { it.reference() }.toSet()
            refs.contains(STICKER_TOOLTIP) || (refs.contains(PREMIUM_BLOCKED) && refs.contains(PREMIUM_DOCUMENT))
        }
        return (anchors + FixtureDex.classes(build, setOf(CONTROLLER)).values).distinctBy { it.type }
    }

    private fun assertFact(context: BytecodePatchContext, name: String, value: Int) {
        val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions()
        assertEquals("$name is a build fact", value, (body[0] as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})"
    private fun operation(instruction: Instruction) = listOf(instruction.opcode, instruction.reference(), instruction.namedRegisters(),
        if (instruction is OffsetInstruction) null else (instruction as? NarrowLiteralInstruction)?.narrowLiteral)
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
        const val STICKER_SET = "Lorg/telegram/tgnet/TLRPC\$TL_messages_stickerSet;"
    }
}
