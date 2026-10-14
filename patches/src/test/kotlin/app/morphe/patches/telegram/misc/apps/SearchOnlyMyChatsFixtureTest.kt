/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.apps

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.telegram.misc.drafts.SEARCH_CHANGES
import app.morphe.patches.telegram.misc.drafts.SEARCH_KEEP
import app.morphe.patches.telegram.misc.drafts.SEARCH_SENT
import app.morphe.patches.telegram.misc.drafts.SearchLookup
import app.morphe.patches.telegram.misc.drafts.changeSearch
import app.morphe.patches.telegram.misc.drafts.localSearchHosts
import app.morphe.patches.telegram.misc.drafts.resolveChatSearch
import app.morphe.patches.telegram.misc.drafts.resolveLocalSearch
import app.morphe.patches.telegram.misc.extension.PatchLogCapture
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val CHANNELS_POSTS = "Lorg/telegram/tgnet/TLRPC\$TL_channels_searchPosts;"
private const val TITLES = "Lorg/telegram/messenger/R\$string;"
private const val NOTIFICATIONS = "Lorg/telegram/messenger/NotificationCenter;"
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** Search only my chats on the pinned builds: the tab row, the Downloads shortcut, a hashtag's public posts and the Chats tab's public lookups. */
class SearchOnlyMyChatsFixtureTest {
    @Test fun `each public part of search takes its own way out and the rest of search stays stock`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val context = PatchContexts.of((ExtensionDex.classes() + myChatsSearchHosts(build)).distinctBy { it.type })
            val sites = context.resolveMyChatsSearch()
            assertEquals("$name: the Chats tab's public lookups and none of its message searches",
                listOf(SearchLookup.SPONSORED, SearchLookup.POSTS, SearchLookup.USERNAMES), sites.chat.edits.map { it.lookup })
            // Telegram's own numbers for its tabs, as the titles read them.
            assertEquals("$name: tab numbers", mapOf(SearchTab.CHANNELS to 1, SearchTab.APPS to 4, SearchTab.POSTS to 6,
                SearchTab.PUBLIC_POSTS to 5, SearchTab.DOWNLOADS to 2), sites.tabs.types)
            assertEquals("$name: the public posts kind of a hashtag search", 2, sites.hashtag.publicPosts)

            val methods = (sites.chat.edits.map { it.method } + listOf(sites.tabs.update, sites.downloads.method, sites.hashtag.search))
                .distinctBy { System.identityHashCode(it) }
            val stock = methods.associate { System.identityHashCode(it) to ImmutableMethod.of(it) }
            sites.rehearse()
            for (method in methods) assertEquals("$name: the rehearsal leaves ${method.name} alone",
                stock.getValue(System.identityHashCode(method)).body().map(::operands), method.body().map(::operands))
            context.writeMyChatsSearchStubs(sites)
            sites.apply()

            // The tab row: every way to its end now passes the runtime its list first.
            val update = sites.tabs.update
            val row = stock.getValue(System.identityHashCode(update)).body()
            val rowAfter = update.body()
            val end = sites.tabs.end
            assertEquals("$name: the row keeps every instruction it had", row.map(::operands),
                rowAfter.indices.filter { it !in end until end + 2 }.map { operands(rowAfter[it]) })
            assertEquals("$name: the row's list goes to the runtime", listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
                rowAfter.subList(end, end + 3).map { it.opcode })
            assertEquals("$name: it's the row's own list", sites.tabs.list.toString(), rowAfter[end].ref())
            assertEquals("$name: read from the adapter", update.implementation!!.registerCount - 1, rowAfter[end].namedRegisters()[1])
            assertEquals("$name: and handed over", listOf(rowAfter[end].namedRegisters()[0]), rowAfter[end + 1].namedRegisters())
            assertEquals("$name: to dropPublicTabs", DROP_PUBLIC_TABS, rowAfter[end + 1].ref())
            val stockInto = ControlFlow.of(stock.getValue(System.identityHashCode(update))).let { flow ->
                flow.instructions.indices.filter { end in flow.normal[it] } }
            val into = ControlFlow.of(update).let { flow -> flow.instructions.indices.filter { end in flow.normal[it] } }
            assertEquals("$name: every way to the row's end reaches the hook", stockInto.size, into.size)
            assertTrue("$name: the row ends more than one way, so the jumps were moved too", stockInto.size > 1)

            // The Downloads shortcut asks where Downloads is, between Telegram's count and the call.
            val shortcut = sites.downloads
            val call = shortcut.call
            val opens = shortcut.method.body()
            val flag = opens[call - 2]
            assertEquals("$name: Telegram's count", listOf(Opcode.IGET_BOOLEAN, Opcode.ADD_INT_LIT8),
                listOf(flag.opcode, opens[call - 1].opcode))
            assertEquals("$name: tabs before Downloads", 5, (opens[call - 1] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$name: the count's flag is the pager's public posts flag", sites.tabs.expanded, (flag as ReferenceInstruction).reference)
            assertEquals("$name: the runtime gets the pager and Telegram's place", listOf(shortcut.pager, shortcut.position), opens[call].namedRegisters())
            assertEquals("$name: downloadsAt", DOWNLOADS_AT, opens[call].ref())
            assertEquals("$name: its answer replaces the place", listOf(Opcode.MOVE_RESULT, listOf(shortcut.position), null), operands(opens[call + 1]))
            assertEquals("$name: and the call opens it", listOf("setPosition", listOf(shortcut.pager, shortcut.position)),
                listOf(opens[call + 2].call()?.name, opens[call + 2].namedRegisters()))

            // A hashtag's search asks first only for the public posts kind, and ends there on yes.
            val search = sites.hashtag.search
            val hashtag = search.body()
            val stockHashtag = stock.getValue(System.identityHashCode(search)).body()
            assertEquals("$name: hashtag gate", listOf(Opcode.CONST, Opcode.IF_NE, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.INVOKE_STATIC, Opcode.RETURN_VOID), hashtag.take(7).map { it.opcode })
            assertEquals("$name: the public posts kind", 2, (hashtag[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$name: compared with the kind asked for", setOf(0, sites.hashtag.kind), hashtag[1].namedRegisters().toSet())
            assertEquals("$name: asks skipPublicPosts", SKIP_PUBLIC_POSTS, hashtag[2].ref())
            assertEquals("$name: ends through endPublicPosts", END_PUBLIC_POSTS, hashtag[5].ref())
            assertEquals("$name: with the controller, the screen and the kind",
                listOf(sites.hashtag.self, sites.hashtag.guid, sites.hashtag.kind), hashtag[5].namedRegisters())
            assertEquals("$name: the stock search follows untouched", stockHashtag.map(::operands), hashtag.drop(7).map(::operands))
            val flow = ControlFlow.of(search)
            assertEquals("$name: any other kind goes to the stock search", setOf(2, 7), flow.normal[1].toSet())
            assertEquals("$name: a no goes to the stock search", setOf(5, 7), flow.normal[4].toSet())
            assertTrue("$name: the public posts request is still the stock search's",
                hashtag.drop(7).any { it.opcode == Opcode.NEW_INSTANCE && it.ref() == CHANNELS_POSTS })

            // The stubs read what was found.
            val runtime = context.mutableClassDefBy(MY_CHATS_SEARCH)
            fun stub(name: String) = runtime.methods.single { it.name == name }.body()
            fun literals(body: List<Instruction>) = body.filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral }.toSet()
            assertEquals("$name: public tabs", setOf(1, 4, 6, 5), literals(stub("publicTab")))
            assertEquals("$name: Downloads", setOf(2), literals(stub("downloadsTab")))
            for (predicate in listOf("publicTab", "downloadsTab")) {
                val body = stub(predicate)
                assertTrue("$name: $predicate checks the item's class", body.any { it.opcode == Opcode.INSTANCE_OF && it.ref() == sites.tabs.item })
                assertTrue("$name: $predicate reads its type", body.any { it.opcode == Opcode.IGET && it.ref() == sites.tabs.type.toString() })
            }
            assertEquals("$name: the row from the pager", listOf(sites.tabs.adapter.toString(), sites.tabs.list.toString()),
                stub("tabs").filter { it.opcode == Opcode.IGET_OBJECT }.map { it.ref() })
            val finish = stub("finishPublicPosts").mapNotNull { it.ref() }
            for (part in listOf("$HASHTAG_SEARCH->getSearchResult(I)$HASHTAG_RESULT", "$HASHTAG_RESULT->clear()V",
                "$HASHTAG_RESULT->loading:Z", "$HASHTAG_RESULT->endReached:Z", "$NOTIFICATIONS->hashtagSearchUpdated:I",
                "$NOTIFICATIONS->postNotificationName(I[Ljava/lang/Object;)V")) {
                assertTrue("$name: the finish uses $part", part in finish)
            }
        }
    }

    @Test fun `the whole patch goes in without a warning and says so in its build facts`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = contextFor(build)
            assertEquals(emptyList<String>(), PatchLogCapture.warnings { hidePopularAppsPatch.execute(context) })
            for (flag in listOf("hidePopularApps", "myChatsSearch")) {
                assertEquals("${build.name}: $flag", 1, statusOf(context, flag))
            }
        }
    }

    @Test fun `Keep searches on this phone and Search only my chats hook chat search in either order`() {
        for (build in Fixtures.declaredBuilds()) {
            for (localFirst in listOf(true, false)) {
                val where = "${build.name}, Keep searches on this phone ${if (localFirst) "first" else "second"}"
                val context = PatchContexts.of((ExtensionDex.classes() + localSearchHosts(build)).distinctBy { it.type })
                val search = context.resolveLocalSearch().edits.first { it.lookup == SearchLookup.SPONSORED }.method
                val step = context.resolveLocalSearch().edits.first { it.lookup == SearchLookup.USERNAMES }.method
                fun mine() = context.resolveChatSearch(MY_CHATS_HOOKS, MY_CHATS_LOOKUPS).apply()
                if (!localFirst) mine()
                context.resolveLocalSearch().apply()
                if (localFirst) mine()

                val body = search.body()
                val local = body.indices.filter { body[it].ref() == SEARCH_KEEP }
                val own = body.indices.filter { body[it].ref() == MY_CHATS_HOOKS.keep }
                assertEquals("$where: both ask in front of the sponsored and the posts checks", listOf(2, 2), listOf(local.size, own.size))
                val flow = ControlFlow.of(search)
                for (at in local + own) {
                    assertEquals("$where: each answer is checked", Opcode.IF_NEZ, body[at + 2].opcode)
                    assertEquals("$where: and its no goes on", listOf(at + 3), flow.normal[at + 2].filter { it == at + 3 })
                }

                // The username helper gets the text through both swaps, so either can clear it.
                val code = step.body()
                val helper = code.indices.single { code[it].call()?.parameterTypes?.size == 12 }
                val text = code[helper].namedRegisters()[1]
                val swaps = listOf(code[helper - 4], code[helper - 2]).map { it.ref() }.toSet()
                assertEquals("$where: both swaps sit right before the helper", setOf(SEARCH_SENT, MY_CHATS_HOOKS.sent), swaps)
                for (at in listOf(helper - 4, helper - 2)) {
                    assertEquals("$where: a swap takes the text", listOf(text), code[at].namedRegisters())
                    assertEquals("$where: and hands it back", listOf(Opcode.MOVE_RESULT_OBJECT, listOf(text), null), operands(code[at + 1]))
                }
                val loaded = (helper - 5 downTo 0).first { code[it].opcode.setsRegister() && code[it].namedRegisters().firstOrNull() == text }
                assertEquals("$where: the text is the runnable's own", Opcode.IGET_OBJECT, code[loaded].opcode)
            }
        }
    }

    @Test fun `changed search geometry refuses the whole patch before any partial mutation`() {
        val changes: List<Triple<String, String, (BytecodePatchContext, MyChatsSearchSites) -> Unit>> = listOf(
            // The Apps tab's title reads another string, so no method names all five tabs.
            Triple("a public tab's title gone", "search tab titles has 0 matches") { context, sites ->
                val apps = "$TITLES->AppsTab:I"
                val title = context.mutableClassDefBy(sites.tabs.update.definingClass).methods.single { method ->
                    method.body().any { it.opcode == Opcode.SGET && it.ref() == apps } }
                val body = title.body()
                val at = body.indices.single { body[it].opcode == Opcode.SGET && body[it].ref() == apps }
                title.replaceInstruction(at, "sget v${body[at].namedRegisters()[0]}, $TITLES->SearchAppsPopular:I")
            },
            Triple("Downloads counted from another place", "no longer opens the Downloads tab by its place on the row") { _, sites ->
                val at = sites.downloads.call - 1
                val register = sites.downloads.method.body()[at].namedRegisters()[0]
                sites.downloads.method.replaceInstruction(at, "add-int/lit8 v$register, v$register, 0x4")
            },
            Triple("public posts tab always added", "the public posts tab is no longer added only while it's open") { _, sites ->
                val code = sites.tabs.update.body()
                val posts = code.indices.first { at -> code[at].opcode == Opcode.CONST_4 &&
                    (code[at] as NarrowLiteralInstruction).narrowLiteral == 5 && code.getOrNull(at + 1)?.call()?.name == "<init>" }
                val guard = (posts downTo 0).first { code[it].opcode == Opcode.IF_EQZ }
                sites.tabs.update.replaceInstruction(guard, "nop")
            },
            Triple("hashtag kind picked later", "no longer starts by picking its results by kind") { _, sites ->
                sites.hashtag.search.addInstructions(0, "nop")
            },
            Triple("row ends twice", "the tab row update no longer ends in one place") { _, sites ->
                sites.tabs.update.addInstructions(0, "return-void")
            },
        )
        for (build in Fixtures.declaredBuilds()) {
            for ((case, reason, change) in changes) {
                val context = contextFor(build)
                change(context, context.resolveMyChatsSearch())
                assertRefusedUntouched(build, case, reason, context)
            }
            // The Chats tab's own checks, shared with Keep searches on this phone, refuse this patch too.
            val shared = setOf("sponsored check gone", "posts check gone", "text read again", "second chat search", "helper never cleared")
            for ((case, reason) in SEARCH_CHANGES.filter { it.first in shared }) {
                val context = contextFor(build)
                changeSearch(case, context.resolveLocalSearch())
                assertRefusedUntouched(build, case, reason, context)
            }
            assertRefusedUntouched(build, "no runtime", "no public search only my chats runtime", contextFor(build, runtime = false))
        }
    }

    @Test fun `the census has one tab row, one Downloads shortcut and one hashtag search that asks for public posts`() {
        for (build in Fixtures.declaredBuilds()) {
            val name = build.name
            val titled = FixtureDex.methodsWhere(build, { true }) { method -> method.body().let { body ->
                SearchTab.entries.all { tab -> body.any { it.opcode == Opcode.SGET && it.ref() == "$TITLES->${tab.title}:I" } } } }
            assertEquals("$name: one method names all five tabs", 1, titled.size)
            val shortcuts = FixtureDex.methodsWhere(build, { true }) { method -> method.body().let { body ->
                body.indices.any { at -> at + 2 < body.size && body[at].opcode == Opcode.IGET_BOOLEAN &&
                    body[at + 1].opcode == Opcode.ADD_INT_LIT8 && body[at + 2].call()?.name == "setPosition" } } }
            assertEquals("$name: one place opens search at a counted tab", 1, shortcuts.size)
            val posts = FixtureDex.methodsWhere(build, { true }) { method -> method.body().any {
                it.opcode == Opcode.NEW_INSTANCE && it.ref() == CHANNELS_POSTS } }
            assertTrue("$name: the hashtag search asks for public posts: ${posts.map { it.definingClass }}",
                posts.any { it.definingClass == HASHTAG_SEARCH && it.name == "searchHashtag" })
        }
    }

    private fun assertRefusedUntouched(file: File, case: String, reason: String, context: BytecodePatchContext) {
        val build = file.name
        val hosts = (myChatsSearchHosts(file) + popularAppsOwners(file)).map { it.type }.distinct()
        val before = hosts.associateWith { type -> context.mutableClassDefBy(type).methods.map { it.body().map(::operation) } }
        try {
            hidePopularAppsPatch.execute(context)
            fail("$build: $case was accepted")
        } catch (expected: PatchException) {
            val message = expected.message.orEmpty()
            assertTrue("$build: $case: $message", message.contains("before editing"))
            assertTrue("$build: $case refused for its own reason: $message", message.contains(reason))
        }
        for (type in hosts) assertEquals("$build: $case doesn't partly mutate $type", before.getValue(type),
            context.mutableClassDefBy(type).methods.map { it.body().map(::operation) })
        for (flag in listOf("hidePopularApps", "myChatsSearch")) {
            assertEquals("$build: $case leaves $flag false", 0, statusOf(context, flag))
        }
    }

    private fun statusOf(context: BytecodePatchContext, flag: String) =
        (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }.body()[0] as NarrowLiteralInstruction).narrowLiteral

    private fun contextFor(build: File, runtime: Boolean = true): BytecodePatchContext {
        val extension = ExtensionDex.classes().filter { runtime || it.type != MY_CHATS_SEARCH }
        return PatchContexts.of((extension + popularAppsOwners(build) + myChatsSearchHosts(build)).distinctBy { it.type })
    }
}

private val HOSTS = ConcurrentHashMap<String, List<ClassDef>>()
private val OWNERS = ConcurrentHashMap<String, List<ClassDef>>()

/** Hide popular apps' own loader and Apps tab. */
private fun popularAppsOwners(build: File): List<ClassDef> = OWNERS.getOrPut(build.path) {
    FixtureDex.classesWhere(build, { true }) { method ->
        method.body().any { (it.opcode == Opcode.NEW_INSTANCE && it.ref() == GET_POPULAR_APP_BOTS) ||
            (it.opcode == Opcode.SGET && it.ref() == "$TITLES->SearchAppsPopular:I") }
    }
}

private fun Method.namesEveryTab() = body().let { body ->
    SearchTab.entries.all { tab -> body.any { it.opcode == Opcode.SGET && it.ref() == "$TITLES->${tab.title}:I" } } }

/**
 * Everything Search only my chats reads or edits: chat search and what it builds or calls, the tab
 * row's adapter with its items and pager, the screen that opens Downloads, and Telegram's hashtag
 * search with its results and the notification center it posts to.
 */
internal fun myChatsSearchHosts(build: File): List<ClassDef> = HOSTS.getOrPut(build.path) {
    val found = FixtureDex.classesWhere(build, { true }) { method -> method.namesEveryTab() || method.body().let { body ->
        body.indices.any { at -> at + 2 < body.size && body[at].opcode == Opcode.IGET_BOOLEAN &&
            body[at + 1].opcode == Opcode.ADD_INT_LIT8 && body[at + 2].call()?.name == "setPosition" } } }
    // The adapter's tab items and the pager whose flag it reads.
    val adapter = found.single { classDef -> classDef.methods.any { it.namesEveryTab() } }
    val named = adapter.methods.flatMap { method -> method.body().mapNotNull { instruction ->
        when (instruction.opcode) {
            Opcode.CHECK_CAST -> instruction.ref()
            Opcode.IGET_BOOLEAN -> ((instruction as ReferenceInstruction).reference as FieldReference).definingClass
            else -> null
        } } }
    val kept = FixtureDex.classes(build, named.toSet() + setOf(HASHTAG_SEARCH, HASHTAG_RESULT, NOTIFICATIONS)).values
    (localSearchHosts(build) + found + kept).distinctBy { it.type }.map(ImmutableClassDef::of)
}

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref(): String? = (this as? ReferenceInstruction)?.reference?.toString()
private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun operation(instruction: Instruction) = instruction.opcode to instruction.ref()
/** An instruction's opcode, registers and reference; a goto that grew to reach past a hook still counts as that goto. */
private fun operands(instruction: Instruction): List<Any?> =
    listOf(if (instruction.opcode in GOTOS) Opcode.GOTO else instruction.opcode, instruction.namedRegisters(), instruction.ref())
