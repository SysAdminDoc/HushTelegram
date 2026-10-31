/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.paste

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Mention by ID hook on the real pinned web and Play builds. */
class MentionByIdFixtureTest {
    @Test fun `the entities method gets one hook before its return and keeps its stock body`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val site = context.resolveMentionById()
            val before = site.method.controlBody().filter { it.opcode != Opcode.NOP }
                .map { Triple(it.opcode, it.controlRef(), it.namedRegisters()) }
            site.insert()
            val after = site.method.controlBody().filter { it.opcode != Opcode.NOP }
            assertEquals("${build.name}: two instructions added", before.size + 2, after.size)
            val call = after[after.size - 3]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(ADD_MENTIONS, call.controlRef())
            assertEquals("${build.name}: the controller, the text and the list", listOf(1, 2, site.register).size, call.namedRegisters().size)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[after.size - 2].opcode)
            assertEquals(listOf(site.register), after[after.size - 2].namedRegisters())
            assertEquals(Opcode.RETURN_OBJECT, after.last().opcode)
            assertEquals("${build.name}: the stock body is whole", before.dropLast(1),
                after.dropLast(3).map { Triple(it.opcode, it.controlRef(), it.namedRegisters()) })
        }
    }

    private fun hosts(build: File): List<ClassDef> {
        val found = FixtureDex.classesWhere(build, { true }) { method: Method ->
            method.definingClass == "Lorg/telegram/messenger/MediaDataController;" && method.name == "getEntities"
        }.associateByTo(linkedMapOf()) { it.type }
        while (true) {
            val ancestors = found.values.flatMap { listOfNotNull(it.superclass) + it.interfaces }
                .filter { it.startsWith("Lorg/telegram/") && it !in found }.toSet()
            if (ancestors.isEmpty()) break
            val more = FixtureDex.classes(build, ancestors)
            assertEquals("${build.name}: declared Telegram ancestor classes", ancestors, more.keys)
            found.putAll(more)
        }
        assertTrue(found.containsKey("Lorg/telegram/messenger/MediaDataController;"))
        return found.values.map(ImmutableClassDef::of)
    }
}
