/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.apps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.drafts.ChatSearchHooks
import app.morphe.patches.telegram.misc.drafts.LocalSearchSite
import app.morphe.patches.telegram.misc.drafts.PUBLIC_POSTS as PUBLIC_POSTS_REQUEST
import app.morphe.patches.telegram.misc.drafts.SearchLookup
import app.morphe.patches.telegram.misc.drafts.resolveChatSearch
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.freeLocalsAt
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.parameterRegisterNumber
import app.morphe.patches.telegram.misc.extension.requireThisIntact
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.one
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Hide popular apps"
internal const val MY_CHATS_SEARCH = "$EXTENSION_PACKAGE/misc/MyChatsSearch;"
internal const val DROP_PUBLIC_TABS = "$MY_CHATS_SEARCH->dropPublicTabs(Ljava/util/ArrayList;)V"
internal const val DOWNLOADS_AT = "$MY_CHATS_SEARCH->downloadsAt(Ljava/lang/Object;I)I"
internal const val SKIP_PUBLIC_POSTS = "$MY_CHATS_SEARCH->skipPublicPosts()Z"
internal const val END_PUBLIC_POSTS = "$MY_CHATS_SEARCH->endPublicPosts(Ljava/lang/Object;II)V"
/** Telegram's hashtag search, behind the tabs a hashtag opens in a chat. Its names are kept. */
internal const val HASHTAG_SEARCH = "Lorg/telegram/messenger/HashtagSearchController;"
internal const val HASHTAG_RESULT = "Lorg/telegram/messenger/HashtagSearchController\$SearchResult;"
private const val NOTIFICATIONS = "Lorg/telegram/messenger/NotificationCenter;"
private const val TITLES = "Lorg/telegram/messenger/R\$string;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val STRING = "Ljava/lang/String;"
private val CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)
/** Every way out of a method. dexlib2's `Opcode.name` is the smali mnemonic, so these are listed rather than matched by name. */
private val EXITS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)

/** The chat search lookups this patch hooks: the public ones. Messages in your chats are left to Telegram. */
internal val MY_CHATS_LOOKUPS = setOf(SearchLookup.SPONSORED, SearchLookup.POSTS, SearchLookup.USERNAMES)
internal val MY_CHATS_HOOKS = ChatSearchHooks(PATCH, MY_CHATS_SEARCH)

/** Search's tabs that matter here, by the string each one's title reads. */
internal enum class SearchTab(val title: String, val publicTab: Boolean) {
    CHANNELS("ChannelsTab", true),
    APPS("AppsTab", true),
    POSTS("SearchPosts", true),
    PUBLIC_POSTS("PublicPostsTabs", true),
    DOWNLOADS("DownloadsTabs", false),
}

/**
 * Search's tab row. [update] clears [list] and fills it again, one [item] per tab, ending at [end];
 * an item's [type] field holds the number [types] gives its tab. The [pager] holds the row's adapter
 * in [adapter], and says whether the public posts tab is open in [expanded].
 */
internal class TabRow(
    val update: MutableMethod,
    val end: Int,
    val list: FieldReference,
    val item: String,
    val type: FieldReference,
    val types: Map<SearchTab, Int>,
    val pager: String,
    val adapter: FieldReference,
    val expanded: FieldReference,
)

/** The Downloads shortcut: [call] in [method] opens [pager]'s tab number [position], counted on Telegram's own row. */
internal class DownloadsShortcut(val method: MutableMethod, val call: Int, val pager: Int, val position: Int)

/**
 * A hashtag's searches. [search] starts one, with the screen's number in [guid] and the search's
 * kind in [kind], both registers; [publicPosts] is the kind that asks for public posts.
 */
internal class HashtagPosts(val search: MutableMethod, val self: Int, val guid: Int, val kind: Int, val publicPosts: Int)

internal class MyChatsSearchSites(
    val chat: LocalSearchSite,
    val tabs: TabRow,
    val downloads: DownloadsShortcut,
    val hashtag: HashtagPosts,
) {
    /** Every hook, on the methods themselves or on what [into] makes of each. */
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        chat.apply(into)

        val update = into(tabs.update)
        val list = tabs.update.freeLocalsAt(PATCH, tabs.end, 1, highest = 15).single()
        update.addInstructionsAtControlFlowLabel(tabs.end, """
            iget-object v$list, v${tabs.update.localRegisterCount()}, ${tabs.list}
            invoke-static {v$list}, $DROP_PUBLIC_TABS
        """.trimIndent())

        into(downloads.method).addInstructionsAtControlFlowLabel(downloads.call, """
            invoke-static {v${downloads.pager}, v${downloads.position}}, $DOWNLOADS_AT
            move-result v${downloads.position}
        """.trimIndent())

        val search = into(hashtag.search)
        search.addInstructionsWithLabels(0, """
            const v0, ${hashtag.publicPosts}
            if-ne v${hashtag.kind}, v0, :hush_keep
            invoke-static {}, $SKIP_PUBLIC_POSTS
            move-result v0
            if-eqz v0, :hush_keep
            invoke-static {v${hashtag.self}, v${hashtag.guid}, v${hashtag.kind}}, $END_PUBLIC_POSTS
            return-void
        """.trimIndent(), ExternalLabel("hush_keep", search.getInstruction(0)))
    }

    /** The same hooks on copies, so a smali or register problem refuses before the app is touched. */
    fun rehearse() = apply { MutableMethod(ImmutableMethod.of(it)) }
}

/** The runtime's stubs, from what [sites] found. On their own nothing calls them. */
internal fun BytecodePatchContext.writeMyChatsSearchStubs(sites: MyChatsSearchSites) {
    val tabs = sites.tabs
    // Whether an object is a tab item whose type is one of [kinds].
    fun predicate(kinds: Collection<SearchTab>) = (listOf(
        "instance-of v0, p0, ${tabs.item}",
        "if-eqz v0, :hush_no",
        "check-cast p0, ${tabs.item}",
        "iget v0, p0, ${tabs.type}",
    ) + kinds.flatMap { listOf("const v1, ${tabs.types.getValue(it)}", "if-eq v0, v1, :hush_yes") } + listOf(
        ":hush_no",
        "const/4 v0, 0x0",
        "return v0",
        ":hush_yes",
        "const/4 v0, 0x1",
        "return v0",
    )).joinToString("\n")
    writeStub(MY_CHATS_SEARCH, "publicTab", 3, predicate(SearchTab.entries.filter { it.publicTab }))
    writeStub(MY_CHATS_SEARCH, "downloadsTab", 3, predicate(listOf(SearchTab.DOWNLOADS)))
    writeStub(MY_CHATS_SEARCH, "tabs", 2, """
        check-cast p0, ${tabs.pager}
        iget-object v0, p0, ${tabs.adapter}
        iget-object v0, v0, ${tabs.list}
        return-object v0
    """.trimIndent())
    // The hashtag search's results for that kind cleared, done, and the screens told: what Telegram
    // does for a channel name it can't find.
    writeStub(MY_CHATS_SEARCH, "finishPublicPosts", 5, """
        check-cast p0, $HASHTAG_SEARCH
        invoke-virtual {p0, p1}, $HASHTAG_SEARCH->getSearchResult(I)$HASHTAG_RESULT
        move-result-object v0
        invoke-virtual {v0}, $HASHTAG_RESULT->clear()V
        const/4 v1, 0x0
        iput-boolean v1, v0, $HASHTAG_RESULT->loading:Z
        const/4 v1, 0x1
        iput-boolean v1, v0, $HASHTAG_RESULT->endReached:Z
        iget v0, p0, $HASHTAG_SEARCH->currentAccount:I
        invoke-static {v0}, $NOTIFICATIONS->getInstance(I)$NOTIFICATIONS
        move-result-object v0
        sget v1, $NOTIFICATIONS->hashtagSearchUpdated:I
        invoke-virtual {v0, v1, p2}, $NOTIFICATIONS->postNotificationName(I[Ljava/lang/Object;)V
        return-void
    """.trimIndent())
}

/**
 * Search only my chats, found before anything is edited:
 *
 * - the Chats tab's public lookups, the same places Keep searches on this phone hooks: sponsored
 *   accounts and public posts take the skip Telegram has for text that doesn't need them, and the
 *   username helper gets no text, as for an empty box;
 * - the tab row, which drops its public tabs right after it's built, so every count, title and page
 *   Telegram reads from it afterwards agrees;
 * - the Downloads shortcut, which opens a fixed place on Telegram's own row and goes where Downloads
 *   now is instead;
 * - a hashtag's public posts search, which ends with nothing found, the way Telegram ends one for a
 *   channel name it can't find.
 */
internal fun BytecodePatchContext.resolveMyChatsSearch(): MyChatsSearchSites {
    requireRuntime()
    val chat = resolveChatSearch(MY_CHATS_HOOKS, MY_CHATS_LOOKUPS)
    val tabs = resolveTabRow()
    val downloads = resolveDownloadsShortcut(tabs)
    val hashtag = resolveHashtagPosts()
    val own = listOf(tabs.update, downloads.method, hashtag.search)
    shape(own.indices.all { at -> own.drop(at + 1).none { it === own[at] } } &&
        own.none { method -> chat.edits.any { it.method === method } }, "two search hooks share a method")
    return MyChatsSearchSites(chat, tabs, downloads, hashtag)
}

/**
 * The tab row's titles name each tab once, behind a check of the item's type against a number, and
 * the one method that names all five is the row's adapter. Its update builds the row afresh.
 */
private fun BytecodePatchContext.resolveTabRow(): TabRow {
    val titled = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/hushtelegram/extension/")) return@classDefForEach
        classDef.methods.filterTo(titled) { method -> method.instructions().let { body ->
            SearchTab.entries.all { tab -> body.any { it.readsTitle(tab.title) } } } }
    }
    val titles = titled.one("search tab titles", ::refuse)
    val adapter = titles.definingClass
    val body = titles.instructions()
    val found = SearchTab.entries.associateWith { tab ->
        val read = body.indices.filter { body[it].readsTitle(tab.title) }.one("${tab.title} title", ::refuse)
        // check-cast item; iget type; const number; if-ne type, number; then the title.
        val branch = body.getOrNull(read - 1)
        val number = body.getOrNull(read - 2)
        val field = body.getOrNull(read - 3)
        val cast = body.getOrNull(read - 4)
        if (branch == null || number == null || field == null || cast == null || branch.opcode != Opcode.IF_NE ||
            number.opcode !in CONSTS || number !is NarrowLiteralInstruction || field.opcode != Opcode.IGET ||
            cast.opcode != Opcode.CHECK_CAST ||
            branch.namedRegisters().toSet() != setOf(field.namedRegisters()[0], number.namedRegisters()[0]) ||
            field.namedRegisters()[1] != cast.namedRegisters()[0]) refuse("the ${tab.title} tab is no longer told apart by its type")
        Triple(cast.typeRef()!!, field.field()!!, number.narrowLiteral)
    }
    val item = found.values.map { it.first }.distinct().one("tab item class", ::refuse)
    val type = found.values.map { it.second }.distinct().one("tab type field", ::refuse)
    val types = found.mapValues { it.value.third }
    shape(type.definingClass == item && type.type == "I" && types.values.toSet().size == types.size,
        "the tabs no longer each have a type of their own")
    val itemClass = classDefByOrNull(item)
    shape(itemClass != null && itemClass.isPublic() && itemClass.fields.any { it.name == type.name && it.type == "I" &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) },
        "a tab's type can't be read from outside its class")

    // The row itself: the adapter's one ArrayList field, which the titles read.
    val list = body.mapNotNull { instruction -> instruction.field()?.takeIf {
        instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == adapter && it.type == ARRAY_LIST } }.distinct()
        .one("tab list", ::refuse)
    val adapterClass = mutableClassDefBy(adapter)
    shape(adapterClass.isPublic() && adapterClass.fields.any { it.name == list.name && it.type == ARRAY_LIST &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) },
        "the tab list can't be read from outside its adapter")
    val update = adapterClass.methods.filter { method -> !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.isEmpty() && method.returnType == "V" && method.instructions().let { code ->
            code.any { it.opcode == Opcode.IGET_OBJECT && it.field() == list } &&
                code.any { it.call()?.let { call -> call.definingClass == ARRAY_LIST && call.name == "clear" } == true } &&
                code.any { it.opcode == Opcode.NEW_INSTANCE && it.typeRef() == item } } }
        .one("tab row update", ::refuse)
    val code = update.instructions()
    val built = code.indices.mapNotNull { at -> code.builtTab(at, item)?.let { it to at } }
    val at = types.mapValues { (tab, number) -> built.filter { it.first == number }.map { it.second }
        .one("${tab.title} tab in the row", ::refuse) }
    val returns = code.indices.filter { code[it].opcode in EXITS }
    shape(returns.size == 1 && code[returns.single()].opcode == Opcode.RETURN_VOID, "the tab row update no longer ends in one place")
    val end = returns.single()
    shape(at.values.all { it < end }, "a tab is added after the tab row update ends")
    shape(update.localRegisterCount() <= 15, "the tab row update keeps its adapter where the hook can't read it")
    try {
        update.requireThisIntact("the tab row update", listOf(end))
    } catch (changed: PatchException) {
        refuse(changed.message.orEmpty())
    }

    // The public posts tab is added only while it's open, behind the pager's own flag: the branch
    // falls into building and adding it, and its other side lands past that add.
    val posts = at.getValue(SearchTab.PUBLIC_POSTS)
    val made = (posts downTo 0).firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE && code[it].typeRef() == item }
    val guard = made?.let { (it - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IF_EQZ } }
    val flag = guard?.let { code.getOrNull(it - 1) }
    val added = (posts until code.size).firstOrNull { code[it].isAdd() }
    val skip = guard?.let { ControlFlow.of(update).normal[it].filter { next -> next != it + 1 } }
    shape(guard != null && flag?.opcode == Opcode.IGET_BOOLEAN && flag.namedRegisters()[0] == code[guard].namedRegisters().single() &&
        made == guard + 1 && added != null && skip?.singleOrNull()?.let { it > added } == true,
        "the public posts tab is no longer added only while it's open")
    val expanded = flag!!.field()!!
    val pager = expanded.definingClass
    val pagerClass = classDefByOrNull(pager)
    val holder = pagerClass?.fields?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == adapter }.orEmpty()
        .one("pager's tab adapter", ::refuse)
    shape(pagerClass!!.isPublic() && AccessFlags.PUBLIC.isSet(holder.accessFlags) &&
        pagerClass.fields.any { it.name == expanded.name && it.type == "Z" }, "the pager's tab adapter can't be read from outside it")
    return TabRow(update, end, list, item, type, types, pager, ImmutableFieldReference(pager, holder.name, holder.type), expanded)
}

/**
 * The Downloads shortcut opens search on the pager's tab number five, or six with the public posts
 * tab open: Telegram's flag plus a number. That number is how many tabs come before Downloads on the
 * row, which the hook checks before it trusts the place.
 */
private fun BytecodePatchContext.resolveDownloadsShortcut(tabs: TabRow): DownloadsShortcut {
    val code = tabs.update.instructions()
    val downloads = code.indices.single { code.builtTab(it, tabs.item) == tabs.types.getValue(SearchTab.DOWNLOADS) }
    // Every tab added before Downloads is always there, but public posts, which the flag counts.
    val before = code.indices.count { it < downloads && code[it].isAdd() } - 1

    val sites = mutableListOf<Pair<Method, Int>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/hushtelegram/extension/")) return@classDefForEach
        for (method in classDef.methods) {
            val body = method.instructions()
            for (at in 0 until body.size - 2) {
                if (body[at].opcode == Opcode.IGET_BOOLEAN && body[at].field() == tabs.expanded &&
                    body[at + 1].opcode == Opcode.ADD_INT_LIT8 && body[at + 2].call()?.name == "setPosition") sites += method to at
            }
        }
    }
    val (found, read) = sites.one("Downloads shortcut", ::refuse)
    val method = mutableClassDefBy(found.definingClass).methods.single { it.sameSignature(found) }
    val body = method.instructions()
    val (position, pager) = body[read].namedRegisters()
    val add = body[read + 1]
    val call = body[read + 2]
    val flow = ControlFlow.of(method)
    shape(add.namedRegisters() == listOf(position, position) && (add as NarrowLiteralInstruction).narrowLiteral == before &&
        call.opcode == Opcode.INVOKE_VIRTUAL && call.call()?.let { it.definingClass == tabs.pager && it.params() == listOf("I") &&
            it.returnType == "V" } == true && call.namedRegisters() == listOf(pager, position) &&
        flow.into(read + 1) == listOf(read) && flow.into(read + 2) == listOf(read + 1) && pager <= 15 && position <= 15,
        "the Downloads shortcut no longer opens the Downloads tab by its place on the row")
    return DownloadsShortcut(method, read + 2, pager, position)
}

/**
 * Telegram's hashtag search picks a list of results by the search's kind first thing, and the kind
 * whose list is the channel posts one asks for public posts.
 */
private fun BytecodePatchContext.resolveHashtagPosts(): HashtagPosts {
    val controller = classDefByOrNull(HASHTAG_SEARCH) ?: refuse("no hashtag search")
    val search = mutableClassDefBy(HASHTAG_SEARCH).methods.filter { it.name == "searchHashtag" &&
        it.params() == listOf(STRING, "I", "I", "I") && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .one("hashtag search", ::refuse)
    shape(search.instructions().any { it.opcode == Opcode.NEW_INSTANCE && it.typeRef() == PUBLIC_POSTS_REQUEST },
        "the hashtag search no longer asks for public posts")
    val pick = controller.methods.filter { it.name == "getSearchResult" && it.params() == listOf("I") &&
        it.returnType == HASHTAG_RESULT && it.isCallable() }.one("hashtag result picker", ::refuse)
    val picks = pick.instructions()
    val posts = picks.indices.filter { picks[it].opcode == Opcode.IGET_OBJECT && picks[it].field()?.let { field ->
        field.definingClass == HASHTAG_SEARCH && field.name == "channelPostsSearch" } == true }.one("channel posts results", ::refuse)
    // const number; if-ne kind, number; then the channel posts list.
    val branch = picks.getOrNull(posts - 1)
    val number = picks.getOrNull(posts - 2)
    val kind = pick.parameterRegisterNumber(0)
    if (branch == null || number == null || branch.opcode != Opcode.IF_NE || number.opcode !in CONSTS ||
        number !is NarrowLiteralInstruction || branch.namedRegisters().toSet() != setOf(kind, number.namedRegisters()[0])) {
        refuse("the hashtag result picker no longer picks channel posts by kind")
    }
    val publicPosts = number.narrowLiteral

    val self = search.localRegisterCount()
    val guid = search.parameterRegisterNumber(1)
    val asked = search.parameterRegisterNumber(2)
    val first = search.instructions().firstOrNull()
    shape(first?.call()?.let { it.definingClass == HASHTAG_SEARCH && it.name == "getSearchResult" } == true &&
        first!!.namedRegisters() == listOf(self, asked), "the hashtag search no longer starts by picking its results by kind")
    // The hook borrows v0, which holds nothing before the method's first instruction, and passes this, the screen and the kind.
    shape(self >= 1 && asked <= 15, "the hashtag search keeps its registers where the hook can't use them")

    // What the finish reads and writes from the extension, so each must be public.
    val result = classDefByOrNull(HASHTAG_RESULT)
    val notifications = classDefByOrNull(NOTIFICATIONS)
    shape(controller.isPublic() && result != null && result.isPublic() && notifications != null && notifications.isPublic() &&
        controller.hasField("currentAccount", "I", static = false) &&
        result.hasField("loading", "Z", static = false) && result.hasField("endReached", "Z", static = false) &&
        result.methods.any { it.name == "clear" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.isCallable() } &&
        notifications.hasField("hashtagSearchUpdated", "I", static = true) &&
        notifications.methods.any { it.name == "getInstance" && it.params() == listOf("I") && it.returnType == NOTIFICATIONS &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) } &&
        notifications.methods.any { it.name == "postNotificationName" && it.params() == listOf("I", "[Ljava/lang/Object;") &&
            it.returnType == "V" && it.isCallable() },
        "the hashtag search can't be finished from outside it")
    return HashtagPosts(search, self, guid, asked, publicPosts)
}

private fun BytecodePatchContext.requireRuntime() {
    val owner = classDefByOrNull(MY_CHATS_SEARCH)
    shape(owner != null && owner.isPublic(), "no public search only my chats runtime")
    val needed = listOf(
        Triple("dropPublicTabs", listOf(ARRAY_LIST), "V"),
        Triple("downloadsAt", listOf("Ljava/lang/Object;", "I"), "I"),
        Triple("skipPublicPosts", emptyList(), "Z"),
        Triple("endPublicPosts", listOf("Ljava/lang/Object;", "I", "I"), "V"),
        Triple("publicTab", listOf("Ljava/lang/Object;"), "Z"),
        Triple("downloadsTab", listOf("Ljava/lang/Object;"), "Z"),
        Triple("tabs", listOf("Ljava/lang/Object;"), ARRAY_LIST),
        Triple("finishPublicPosts", listOf("Ljava/lang/Object;", "I", "[Ljava/lang/Object;"), "V"),
    )
    for ((name, parameters, result) in needed) {
        shape(owner!!.methods.count { it.name == name && it.params() == parameters && it.returnType == result &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.NATIVE.isSet(it.accessFlags) && !AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.implementation != null } == 1 &&
            owner.methods.count { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) } == 1,
            "no callable public static runtime $name")
    }
}

/**
 * The number a tab item is built with, when instruction [at] is the `<init>(I)` of a new [item] and
 * the last write before it to the number's register is a constant.
 */
private fun List<Instruction>.builtTab(at: Int, item: String): Int? {
    val init = this[at].call() ?: return null
    if (this[at].opcode != Opcode.INVOKE_DIRECT || init.definingClass != item || init.name != "<init>" || init.params() != listOf("I")) {
        return null
    }
    val number = this[at].namedRegisters().getOrNull(1) ?: return null
    val write = (at - 1 downTo 0).firstOrNull { number in this[it].writes() } ?: return null
    return (this[write] as? NarrowLiteralInstruction)?.takeIf { this[write].opcode in CONSTS }?.narrowLiteral
}

private fun Instruction.isAdd() =
    call()?.let { it.definingClass == ARRAY_LIST && it.name == "add" && it.params() == listOf("Ljava/lang/Object;") } == true

/** Every instruction that can run right before [index], by a fall-through, a branch or a throw. */
private fun ControlFlow.into(index: Int): List<Int> = instructions.indices.filter { index in normal[it] || index in exceptional[it] }

private fun refuse(reason: String): Nothing =
    throw PatchException("$PATCH: $reason; refuses changed search geometry before editing")
private fun shape(valid: Boolean, reason: String) {
    if (!valid) refuse(reason)
}
private fun ClassDef.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)
private fun Method.isCallable() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags) &&
    !AccessFlags.ABSTRACT.isSet(accessFlags)
private fun ClassDef.hasField(name: String, type: String, static: Boolean) = fields.any { it.name == name && it.type == type &&
    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == static }
private fun Method.params(): List<String> = parameterTypes.map { it.toString() }
private fun MethodReference.params(): List<String> = parameterTypes.map { it.toString() }
private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType && params() == other.params()
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.typeRef(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
private fun Instruction.readsTitle(name: String) = opcode == Opcode.SGET && field()?.let { it.definingClass == TITLES && it.name == name } == true
/** The registers an instruction sets: the first named one for writes, both halves for a wide one. */
private fun Instruction.writes(): Set<Int> {
    if (!opcode.setsRegister()) return emptySet()
    val first = namedRegisters().firstOrNull() ?: return emptySet()
    return if (opcode.setsWideRegister()) setOf(first, first + 1) else setOf(first)
}
