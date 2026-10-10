/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.commerce

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.archive.ARCHIVED_CHATS
import app.morphe.patches.telegram.misc.archive.ARCHIVE_HIDDEN
import app.morphe.patches.telegram.misc.archive.ARCHIVE_ICON
import app.morphe.patches.telegram.misc.archive.ARCHIVE_MENU
import app.morphe.patches.telegram.misc.archive.MESSAGES_CONTROLLER
import app.morphe.patches.telegram.misc.archive.SELECTED_ACCOUNT
import app.morphe.patches.telegram.misc.archive.disableArchivePullPatch
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.insertAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The real fixture proves the presentation sites and the code the patch must retain. */
class HideCommerceFixtureTest {
    @Test
    fun `all sales surfaces are hooked and purchase account and channel controls remain intact`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            val plan = context.resolveCommerceHooks()
            assertEquals("${build.name}: full surface coverage", CommerceTarget.entries.toSet(), plan.hooks.keys)
            val settings = plan.hooks.getValue(CommerceTarget.SETTINGS)
            assertEquals("${build.name}: exactly five Settings sales appends", 5, settings.count { it.replace })
            // 13.0's Wallet row sits at the top while it's new and after Language later.
            val wallet = settings.filter { !it.replace }
            assertEquals("${build.name}: both Wallet placements", 2, wallet.size)
            val builder = settings.first().method.instructions()
            for (gate in wallet) {
                assertEquals("${build.name}: Wallet gate reads walletAvailable", WALLET_AVAILABLE, builder[gate.index - 3].reference())
                assertEquals(Opcode.IF_EQZ, builder[gate.index].opcode)
                val skip = ControlFlow.of(settings.first().method).normal[gate.index].single { it != gate.index + 1 }
                assertEquals("${build.name}: the skipped block holds one Wallet label", 1,
                    (gate.index + 1 until skip).count { builder[it].reference() == WALLET_LABEL })
            }
            val profile = plan.hooks.getValue(CommerceTarget.PROFILE_GIFTS)
            assertEquals("${build.name}: fresh and cached edit candidates", 2, profile.count { it.replace })
            assertEquals("${build.name}: cached presence comparison", 1, profile.count { !it.replace })
            assertTrue("${build.name}: presence guard runs before either candidate append",
                profile.single { !it.replace }.index < profile.filter { it.replace }.minOf { it.index })
            val channel = plan.hooks.getValue(CommerceTarget.CHANNEL_GIFT)
            assertEquals(1, channel.size)
            assertEquals("${build.name}: footer visibility guard is before stock UI work", 0, channel.single().index)

            // 13.0's attach menu gives Wallet's button its number only past the gate. Gallery and
            // File get theirs outside it, so their buttons don't depend on the answer.
            val attach = plan.hooks.getValue(CommerceTarget.ATTACH_WALLET).single()
            assertFalse(attach.replace)
            val numbering = attach.method.instructions()
            assertEquals("${build.name}: attach gate reads walletAvailable", WALLET_AVAILABLE, numbering[attach.index - 3].reference())
            assertEquals(Opcode.IF_EQZ, numbering[attach.index].opcode)
            val skipped = attach.index + 1 until ControlFlow.of(attach.method).normal[attach.index].single { it != attach.index + 1 }
            val bound = binder(context, attach.method.definingClass).instructions()
            fun numberOf(label: String): String {
                val at = bound.indexOfFirst { it.reference() == label }
                return bound[(0 until at).last { bound[it].opcode == Opcode.IGET }].reference()!!
            }
            val walletNumber = numberOf(WALLET_LABEL)
            val walletWrites = numbering.indices.filter { numbering[it].opcode == Opcode.IPUT && numbering[it].reference() == walletNumber }
            assertEquals("${build.name}: Wallet's number is reset, then given only past the gate", listOf(false, true),
                walletWrites.map { it in skipped })
            for (label in listOf("ChatGallery", "ChatDocument").map { "Lorg/telegram/messenger/R\$string;->$it:I" }) {
                val number = numberOf(label)
                assertTrue("${build.name}: $label is numbered outside the Wallet block", numbering.indices.any {
                    numbering[it].opcode == Opcode.IPUT && numbering[it].reference() == number && it !in skipped })
                assertFalse("${build.name}: $label is not numbered inside it", skipped.any { numbering[it].reference() == number })
            }

            // The chat list menu: the hook starts the Wallet entry and jumps to where the menu goes
            // on after it, a path that never reaches the side menu mini apps.
            val menu = plan.hooks.getValue(CommerceTarget.MENU_WALLET).single()
            val entries = menu.method.instructions()
            assertEquals("${build.name}: menu gate reads walletAvailable", WALLET_AVAILABLE, entries[menu.index - 4].reference())
            assertEquals(Opcode.IF_EQZ, entries[menu.index - 1].opcode)
            val menuExit = labelTarget(menu)
            assertEquals("${build.name}: the entry holds one Wallet label", 1, (menu.index until menuExit).count { entries[it].reference() == WALLET_LABEL })
            val menuFlow = ControlFlow.of(menu.method)
            val miniApps = reach(menuFlow, menuFlow.normal[menu.index - 1].single { it != menu.index })
            assertTrue("${build.name}: the side menu mini apps sit behind the gate", miniApps.any { entries[it].reference() == SIDE_MENU })
            assertTrue("${build.name}: the jump never reaches them", reach(menuFlow, menuExit).none { entries[it].reference() == SIDE_MENU })

            val originals = plan.hooks.mapValues { (_, edits) -> ImmutableMethod.of(edits.first().method) }
            val changed = originals.values.map { it.definingClass to it.name }.toSet()
            val untouched = hosts.flatMap { it.methods.toList() }.filter { (it.definingClass to it.name) !in changed }
            assertTrue("${build.name}: includes the ordinary channel footer controls", untouched.any { method ->
                method.instructions().any { it.reference() == JOIN } && method.instructions().any { it.reference() == UNMUTE }
            })
            for (gate in listOf("premiumFeaturesBlocked", "premiumPurchaseBlocked", "starsPurchaseAvailable")) {
                assertTrue("${build.name}: includes stock $gate", untouched.any { it.definingClass == CONTROLLER && it.name == gate })
            }
            assertTrue("${build.name}: includes stock Premium entitlement", untouched.any {
                it.definingClass == USER_CONFIG && it.name == "isPremium"
            })
            val warnings = PatchLogCapture.warnings { hideCommercePatch.execute(context) }
            assertEquals("${build.name}: no missing surface", emptyList<String>(), warnings)

            for ((target, edits) in plan.hooks) {
                if (target == CommerceTarget.MENU_WALLET) assertMenuEdit("${build.name}: $target", originals.getValue(target), edits.single(), menuExit)
                else assertEdits("${build.name}: $target", originals.getValue(target), edits)
            }
            for (method in untouched) {
                val after = context.mutableClassDefBy(method.definingClass).methods.single { it.sameSignature(method) }
                assertEquals("${build.name}: retains $method", method.instructions().map(::operation), after.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 1)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 1) }
            for ((stub, identity) in mapOf("giftTabId" to plan.giftTabId, "giftButtonIndex" to plan.giftButtonIndex)) {
                assertTrue("${build.name}: discovered $stub", identity != null && identity >= 0)
                val body = context.mutableClassDefBy(COMMERCE).methods.single { it.name == stub }.instructions()
                assertEquals("${build.name}: writes the discovered $stub", identity, (body[0] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.RETURN, body[1].opcode)
            }
        }
    }

    @Test
    fun `changed cached Gifts append refuses before any hook or build fact changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val cached = plan.hooks.getValue(CommerceTarget.PROFILE_GIFTS).filter { it.replace }.maxBy { it.index }
            cached.method.replaceInstruction(cached.index, "nop")
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((target, edits) in plan.hooks) {
                assertEquals("${build.name}: no partial $target edit", before.getValue(target), edits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `changed candidate provenance and overlapping wide writes refuse before editing`() {
        for (build in Fixtures.declaredBuilds()) for (change in listOf("allocation", "cached title", "cached ID", "cached source", "cached backedge", "cached label identity", "visibility")) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val edits = plan.hooks.getValue(CommerceTarget.PROFILE_GIFTS)
            val method = edits.first().method
            val body = method.instructions()
            val labels = body.indices.filter { body[it].reference() == PROFILE_GIFTS }
            val cachedPair = edits.filter { it.replace }.maxOf { it.index } - 1
            when (change) {
                "allocation" -> method.replaceInstruction(labels.first() - 3, "nop")
                "cached title" -> {
                    val title = body[labels.last() + 2].namedRegisters().single()
                    method.replaceInstruction(labels.last() + 2, "move-result-object v${if (title == 8) 9 else 8}")
                }
                "cached ID" -> {
                    val branch = body.indices.single { body[it].opcode == Opcode.IF_EQ &&
                        labels.last() in ControlFlow.of(method).normal[it] }
                    val first = body[cachedPair].namedRegisters()[1]
                    method.addInstructionsAtControlFlowLabel(branch, "const-wide/16 v${first - 1}, 0x0")
                }
                "cached source", "cached backedge", "cached label identity" -> {
                    val branch = body.indices.single { body[it].opcode == Opcode.IF_EQ &&
                        labels.last() in ControlFlow.of(method).normal[it] }
                    val first = body[cachedPair].namedRegisters()[1]
                    val box = (0 until branch).last { body[it].reference() == "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" &&
                        body[it + 1].opcode == Opcode.MOVE_RESULT_OBJECT && body[it + 1].namedRegisters() == listOf(first) }
                    val tabIndex = body[box].namedRegisters().single()
                    val identity = body[branch].namedRegisters().single { it != tabIndex }
                    if (change == "cached source") {
                        method.addInstructionsAtControlFlowLabel(branch, "move/from16 v$tabIndex, v$identity")
                    } else {
                        val write = if (change == "cached label identity") {
                            "move/from16 v$identity, v$tabIndex"
                        } else "move/from16 v$tabIndex, v$identity"
                        method.addInstructionsAtControlFlowLabel(branch + 1,
                            "$write\ngoto/32 :hush_changed_comparison",
                            ExternalLabel("hush_changed_comparison", body[branch]))
                    }
                }
                "visibility" -> {
                    val guard = labels.first() - 4
                    val visible = body[guard].namedRegisters().single()
                    method.addInstructionsAtControlFlowLabel(guard, "const-wide/16 v${visible - 1}, 0x0")
                }
            }
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((target, targetEdits) in plan.hooks) {
                assertEquals("${build.name}: $change preserves $target", before.getValue(target),
                    targetEdits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `partial anchor changes are not misreported as missing sales modules`() {
        for (build in Fixtures.declaredBuilds()) for (anchor in SETTINGS_SALES + listOf(WALLET_LABEL, PROFILE_GIFTS, GIFT_BUTTON, GIFT_ICON)) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val target = when (anchor) {
                PROFILE_GIFTS -> CommerceTarget.PROFILE_GIFTS
                GIFT_BUTTON, GIFT_ICON -> CommerceTarget.CHANNEL_GIFT
                else -> CommerceTarget.SETTINGS
            }
            val host = plan.hooks.getValue(target).first().method
            val method = if (anchor == GIFT_ICON) {
                context.mutableClassDefBy(host.definingClass).methods.single { it.name == "<clinit>" }
            } else host
            val at = method.instructions().indices.first { method.instructions()[it].reference() == anchor }
            val register = method.instructions()[at].namedRegisters().single()
            method.replaceInstruction(at, "const/16 v$register, 0x1")
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((surface, edits) in plan.hooks) {
                assertEquals("${build.name}: changed $anchor preserves $surface", before.getValue(surface),
                    edits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `a changed Wallet row refuses before any hook or build fact changes`() {
        for (build in Fixtures.declaredBuilds()) for (change in listOf("row identity", "divider", "availability read", "way in")) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val settings = plan.hooks.getValue(CommerceTarget.SETTINGS)
            val method = settings.first().method
            val body = method.instructions()
            val gate = settings.filter { !it.replace }.minOf { it.index }
            val skip = ControlFlow.of(method).normal[gate].single { it != gate + 1 }
            val row = (gate + 1 until skip).single { body[it].opcode == Opcode.INVOKE_STATIC_RANGE &&
                body.getOrNull(it + 2)?.reference() == "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z" }
            when (change) {
                "row identity" -> {
                    val id = (gate + 1 until row).single { (body[it] as? NarrowLiteralInstruction)?.narrowLiteral == 25 }
                    method.replaceInstruction(id, "const/16 v${body[id].namedRegisters().single()}, 0x18")
                }
                "divider" -> method.replaceInstruction(skip - 1, "nop")
                "availability read" -> method.replaceInstruction(gate - 2, "nop")
                // A jump from before the gate straight to the row skips Telegram's own answer. The
                // jumped-over registers would fail ART's verifier, and the patch must still turn it
                // down, so it's built unchecked.
                "way in" -> {
                    method.insertAtControlFlowLabel(gate - 3, "goto/32 :hush_row", ExternalLabel("hush_row", body[row - 1]))
                    assertTrue("the jump really enters the row", row in ControlFlow.of(method).normal[gate - 3])
                }
            }
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((target, edits) in plan.hooks) {
                assertEquals("${build.name}: $change preserves $target", before.getValue(target),
                    edits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `a changed attach menu Wallet button refuses before any hook or build fact changes`() {
        for (build in Fixtures.declaredBuilds()) for (change in listOf("availability read", "reset", "second reader", "way in", "label")) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val gate = plan.hooks.getValue(CommerceTarget.ATTACH_WALLET).single()
            val method = gate.method
            val body = method.instructions()
            val binder = binder(context, method.definingClass)
            val bound = binder.instructions()
            val label = bound.indexOfFirst { it.reference() == WALLET_LABEL }
            val number = bound[label - 2].reference()!!
            when (change) {
                "availability read" -> method.replaceInstruction(gate.index - 2, "nop")
                "reset" -> method.replaceInstruction(body.indexOfFirst { it.opcode == Opcode.IPUT && it.reference() == number }, "nop")
                // Something else reading Wallet's number could use it for more than the label.
                "second reader" -> method.addInstructions(0, "iget v0, p0, $number")
                "way in" -> {
                    method.insertAtControlFlowLabel(gate.index - 3, "goto/32 :hush_number", ExternalLabel("hush_number", body[gate.index + 1]))
                    assertTrue("the jump really enters the block", gate.index + 2 in ControlFlow.of(method).normal[gate.index - 3])
                }
                // The binder compares the button with something other than Wallet's number.
                "label" -> binder.replaceInstruction(label - 2, "iget v${bound[label - 2].namedRegisters().first()}, " +
                    "v${bound[label - 2].namedRegisters()[1]}, ${body.first { it.opcode == Opcode.IPUT && it.reference() != number }.reference()}")
            }
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((target, edits) in plan.hooks) {
                assertEquals("${build.name}: $change preserves $target", before.getValue(target),
                    edits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `a changed chat list menu Wallet entry refuses before any hook or build fact changes`() {
        for (build in Fixtures.declaredBuilds()) for (change in listOf("availability read", "add", "way in", "title")) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveCommerceHooks()
            val entry = plan.hooks.getValue(CommerceTarget.MENU_WALLET).single()
            val method = entry.method
            val body = method.instructions()
            val exit = labelTarget(entry)
            when (change) {
                "availability read" -> method.replaceInstruction(entry.index - 3, "nop")
                "add" -> method.replaceInstruction(exit - 1, "nop")
                "way in" -> {
                    method.insertAtControlFlowLabel(entry.index - 4, "goto/32 :hush_entry", ExternalLabel("hush_entry", body[exit - 1]))
                    assertTrue("the jump really enters the entry", exit in ControlFlow.of(method).normal[entry.index - 4])
                }
                // Something other than Wallet's own title goes into the entry.
                "title" -> {
                    val title = body.indexOfFirst { it.reference() == WALLET_LABEL } + 2
                    method.addInstructionsAtControlFlowLabel(exit - 1,
                        "const-string v${body[title].namedRegisters().single()}, \"\"")
                }
            }
            val before = plan.hooks.mapValues { it.value.first().method.instructions().map(::operation) }
            assertRefuses { hideCommercePatch.execute(context) }
            for ((target, edits) in plan.hooks) {
                assertEquals("${build.name}: $change preserves $target", before.getValue(target),
                    edits.first().method.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `Disable archive pull and the Wallet entry share the chat list menu in either order`() {
        for (build in Fixtures.declaredBuilds()) for (commerceFirst in listOf(true, false)) {
            val kept = FixtureDex.classes(build, setOf(MESSAGES_CONTROLLER, ARCHIVE_HIDDEN, SELECTED_ACCOUNT, ARCHIVED_CHATS, ARCHIVE_ICON)
                .map { it.substringBefore("->") }.toSet()).values
            val context = PatchContexts.of(ExtensionDex.classes() + (kept + hosts(build)).distinctBy { it.type })
            val menu = context.resolveCommerceHooks().hooks.getValue(CommerceTarget.MENU_WALLET).single().method
            val stock = ImmutableMethod.of(menu).instructions()
            val first = if (commerceFirst) hideCommercePatch else disableArchivePullPatch
            val second = if (commerceFirst) disableArchivePullPatch else hideCommercePatch
            for (patch in listOf(first, second)) {
                assertEquals("${build.name}: ${patch.name}", emptyList<String>(), PatchLogCapture.warnings { patch.execute(context) })
            }
            val after = menu.instructions()
            assertEquals("${build.name}: both hooks and nothing else", stock.size + 6, after.size)
            assertEquals(1, after.count { it.reference() == ARCHIVE_MENU })
            val ask = after.indexOfFirst { it.reference() == "$COMMERCE->showMenuWallet(Z)Z" }
            val jump = ControlFlow.of(menu).normal[ask + 2].single { it != ask + 3 }
            val saved = after.indexOfFirst { it.reference() == "Lorg/telegram/messenger/R\$string;->SavedMessages:I" }
            val add = after.drop(saved).first { it.opcode == Opcode.INVOKE_VIRTUAL && it.reference().orEmpty().endsWith(MENU_ADD) }.reference()
            assertEquals("${build.name}: the jump still lands right after the Wallet add", add, after[jump - 1].reference())
            assertEquals(1, (ask until jump).count { after[it].reference() == WALLET_LABEL })
            assertFact(context, "commerceMenuWallet", 1)
            assertFact(context, "disableArchivePull", 1)
        }
    }

    @Test
    fun `ambiguous Settings builder refuses rather than hiding arbitrary appends`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val initial = PatchContexts.of(ExtensionDex.classes() + hosts)
            val method = initial.resolveCommerceHooks().hooks.getValue(CommerceTarget.SETTINGS).first().method
            val original = hosts.single { it.type == method.definingClass }
            val duplicate = ImmutableMethod(method.definingClass, "salesBuilderAmbiguity", method.parameters,
                method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions, method.implementation)
            val altered = ImmutableClassDef(original.type, original.accessFlags, original.superclass, original.interfaces,
                original.sourceFile, original.annotations, original.fields, original.methods.toList() + duplicate)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts.filter { it.type != original.type } + altered)
            assertRefuses("ambiguous Settings") { hideCommercePatch.execute(context) }
            for (classDef in hosts) for (stock in classDef.methods) {
                val after = context.mutableClassDefBy(classDef.type).methods.single { it.sameSignature(stock) }
                assertEquals("${build.name}: ambiguity retains $stock", stock.instructions().map(::operation), after.instructions().map(::operation))
            }
            assertFact(context, "hideCommerce", 0)
            CommerceTarget.entries.forEach { assertFact(context, it.capability, 0) }
            assertUnwrittenIdentities(context)
        }
    }

    @Test
    fun `missing footer coverage is reported independently without claiming that capability`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val initial = PatchContexts.of(ExtensionDex.classes() + hosts)
            val footer = initial.resolveCommerceHooks().hooks.getValue(CommerceTarget.CHANNEL_GIFT).single().method.definingClass
            val context = PatchContexts.of(ExtensionDex.classes() + hosts.filter { it.type != footer })
            val warnings = PatchLogCapture.warnings { hideCommercePatch.execute(context) }
            assertEquals("${build.name}: one missing footer warning", 1, warnings.size)
            assertTrue(warnings.single(), warnings.single().contains("commerceChannelGift"))
            assertFact(context, "hideCommerce", 1)
            assertFact(context, "commerceSettingsRows", 1)
            assertFact(context, "commerceProfileGifts", 1)
            assertFact(context, "commerceChannelGift", 0)
            assertFact(context, "commerceAttachWallet", 1)
            assertFact(context, "commerceMenuWallet", 1)
            val stub = context.mutableClassDefBy(COMMERCE).methods.single { it.name == "giftButtonIndex" }.instructions()
            assertEquals("${build.name}: no invented footer identity", -1, (stub[0] as NarrowLiteralInstruction).narrowLiteral)
        }
    }

    /** No early return or new stock branch: every original operation and handler survives. */
    private fun assertEdits(where: String, original: Method, edits: List<CommerceEdit>) {
        val before = original.instructions()
        val method = edits.first().method
        val after = method.instructions()
        val inserted = edits.filter { !it.replace }
        assertEquals("$where: only two instructions per decision", before.size + inserted.size * 2, after.size)
        assertEquals("$where: register allocation stays stock", original.implementation!!.registerCount, method.implementation!!.registerCount)
        fun site(index: Int) = index + inserted.count { it.index < index } * 2
        fun stock(index: Int) = site(index) + if (inserted.any { it.index == index }) 2 else 0
        val replaced = edits.filter { it.replace }.associateBy { it.index }
        for (index in before.indices) {
            val next = after[stock(index)]
            if (index in replaced) {
                assertEquals("$where: stock append was scoped", "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z", before[index].reference())
                assertEquals(Opcode.INVOKE_STATIC, next.opcode)
                assertTrue("$where: append belongs to Commerce", next.reference().orEmpty().startsWith(COMMERCE))
                assertEquals("$where: receiver and row are unchanged", before[index].namedRegisters(), next.namedRegisters())
            } else assertEquals("$where: retains stock instruction $index", operation(before[index]), operation(next))
        }
        val oldFlow = ControlFlow.of(original)
        val newFlow = ControlFlow.of(method)
        fun destination(index: Int) = if (inserted.any { it.index == index }) site(index) else stock(index)
        for (index in before.indices) {
            assertEquals("$where: retains normal flow from $index", oldFlow.normal[index].map(::destination), newFlow.normal[stock(index)])
            assertEquals("$where: retains exception flow from $index", oldFlow.exceptional[index].map(::destination), newFlow.exceptional[stock(index)])
        }
        for (edit in inserted) {
            val at = site(edit.index)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
            assertTrue("$where: scoped decision", after[at].reference().orEmpty().startsWith(COMMERCE))
            assertEquals(Opcode.MOVE_RESULT, after[at + 1].opcode)
            assertEquals("$where: result replaces only visibility", after[at].namedRegisters().last(), after[at + 1].namedRegisters().single())
            assertEquals("$where: question reaches its result", listOf(at + 1), newFlow.normal[at])
            assertEquals("$where: result reaches stock code", listOf(at + 2), newFlow.normal[at + 1])
            assertFalse("$where: no stock branch bypasses the visibility decision", newFlow.normal.withIndex().any { (index, targets) ->
                index !in at..at + 1 && at + 2 in targets
            })
        }
        assertEquals("$where: no server, purchase or entitlement call was removed",
            before.filterIsInstance<ReferenceInstruction>().map { it.reference.toString() }.filterNot { it == "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z" },
            after.filterIsInstance<ReferenceInstruction>().map { it.reference.toString() }.filterNot {
                it == "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z" || it.startsWith(COMMERCE)
            })
    }

    /**
     * The chat list menu's one decision: three instructions at the start of the Wallet entry, the
     * last a jump to the entry's stock exit [exit]. Every stock instruction and edge stays.
     */
    private fun assertMenuEdit(where: String, original: Method, edit: CommerceEdit, exit: Int) {
        val before = original.instructions()
        val after = edit.method.instructions()
        val at = edit.index
        assertEquals("$where: three instructions", before.size + 3, after.size)
        assertEquals("$where: register allocation stays stock", original.implementation!!.registerCount, edit.method.implementation!!.registerCount)
        fun stock(index: Int) = if (index < at) index else index + 3
        for (index in before.indices) {
            assertEquals("$where: retains stock instruction $index", operation(before[index]), operation(after[stock(index)]))
        }
        val available = before[at - 1].namedRegisters().single()
        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ), (at..at + 2).map { after[it].opcode })
        assertEquals("$COMMERCE->showMenuWallet(Z)Z", after[at].reference())
        assertTrue("$where: the question and its answer use the gate's register",
            (at..at + 2).all { after[it].namedRegisters() == listOf(available) })
        val oldFlow = ControlFlow.of(original)
        val newFlow = ControlFlow.of(edit.method)
        assertEquals("$where: a hidden entry jumps to its stock exit", setOf(at + 3, stock(exit)), newFlow.normal[at + 2].toSet())
        assertEquals(listOf(at + 1), newFlow.normal[at])
        assertEquals(listOf(at + 2), newFlow.normal[at + 1])
        fun destination(index: Int) = if (index == at) at else stock(index)
        for (index in before.indices) {
            assertEquals("$where: retains normal flow from $index", oldFlow.normal[index].map(::destination), newFlow.normal[stock(index)])
            assertEquals("$where: retains exception flow from $index", oldFlow.exceptional[index].map(::destination), newFlow.exceptional[stock(index)])
        }
        for (step in at + 1..at + 3) {
            assertEquals("$where: only the hook reaches $step", listOf(step - 1), newFlow.normal.indices.filter { step in newFlow.normal[it] })
        }
    }

    /** The binder that labels an attach menu button Wallet, from the context so a test can change it. */
    private fun binder(context: BytecodePatchContext, type: String) = context.mutableClassDefBy(type).methods.single { method ->
        method.parameterTypes.size == 2 && method.instructions().any { it.reference() == WALLET_LABEL }
    }

    /** Where a decision's jump lands, as an index into its unchanged method. */
    private fun labelTarget(edit: CommerceEdit): Int {
        val label = edit.labels.single()
        val body = edit.method.instructions()
        return body.indices.single { label.copy(instruction = body[it]) == label }
    }

    /** Every instruction reachable from [from], handlers included. */
    private fun reach(flow: ControlFlow, from: Int): Set<Int> {
        val seen = mutableSetOf(from)
        val pending = ArrayDeque(listOf(from))
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            for (next in flow.normal[at] + flow.exceptional[at]) if (seen.add(next)) pending += next
        }
        return seen
    }

    private fun hosts(build: File): List<ClassDef> {
        val anchors = FixtureDex.classesWhere(build, { true }) { method ->
            val refs = method.instructions().mapNotNull { it.reference() }.toSet()
            refs.contains(WALLET_LABEL) || refs.contains(PROFILE_GIFTS) || (refs.contains(GIFT_BUTTON) &&
                method.parameterTypes.map { it.toString() } == listOf("I", "Z", "Z")) ||
                (refs.containsAll(SETTINGS_SALES) && AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.map { it.toString() } == listOf(method.definingClass, "Ljava/util/ArrayList;")) ||
                (refs.contains(JOIN) && refs.contains(UNMUTE))
        }
        return (anchors + FixtureDex.classes(build, setOf(TABS, CONTROLLER, USER_CONFIG)).values).distinctBy { it.type }
    }

    private fun assertFact(context: BytecodePatchContext, name: String, value: Int) {
        val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions()
        assertEquals("$name is a build fact", value, (body[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[1].opcode)
    }

    private fun assertUnwrittenIdentities(context: BytecodePatchContext) {
        for (name in listOf("giftTabId", "giftButtonIndex")) {
            val body = context.mutableClassDefBy(COMMERCE).methods.single { it.name == name }.instructions()
            assertEquals("$name was not written before validation", -1, (body[0] as NarrowLiteralInstruction).narrowLiteral)
        }
    }

    private fun assertRefuses(detail: String = "before editing", run: () -> Unit) {
        try {
            run()
            fail("changed fixture shape was accepted")
        } catch (expected: PatchException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(detail))
        }
    }

    private fun operation(instruction: Instruction) = listOf(instruction.opcode, instruction.reference(), instruction.namedRegisters(),
        if (instruction is OffsetInstruction) null else (instruction as? NarrowLiteralInstruction)?.narrowLiteral)
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private companion object {
        const val TABS = "Lorg/telegram/ui/Components/ScrollSlidingTextTabStrip;"
        const val CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
        const val USER_CONFIG = "Lorg/telegram/messenger/UserConfig;"
        const val JOIN = "Lorg/telegram/messenger/R\$string;->ChannelJoinNoCaps:I"
        const val UNMUTE = "Lorg/telegram/messenger/R\$string;->ChannelUnmuteNoCaps:I"
        const val SIDE_MENU = "Lorg/telegram/tgnet/TLRPC\$TL_attachMenuBot;->show_in_side_menu:Z"
        const val MENU_ADD = "(ILjava/lang/CharSequence;Ljava/lang/Runnable;Z)V"
    }
}
