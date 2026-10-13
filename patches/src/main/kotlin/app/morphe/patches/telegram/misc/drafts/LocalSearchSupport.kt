/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.drafts

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.ads.GET_SPONSORED_PEERS
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.freeLocalsAt
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.one
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.BitSet

private const val PATCH = "Disable draft link previews"
internal const val LOCAL_SEARCH = "$EXTENSION_PACKAGE/misc/LocalSearch;"
internal const val SEARCH_KEEP = "$LOCAL_SEARCH->keep()Z"
internal const val SEARCH_SENT = "$LOCAL_SEARCH->sent(Ljava/lang/String;)Ljava/lang/String;"
internal const val SEARCH_EMPTY_TEXT = "$LOCAL_SEARCH->emptyText(Ljava/lang/String;)Ljava/lang/String;"
/** Messages matching the text in every chat. */
internal const val GLOBAL_MESSAGES = "Lorg/telegram/tgnet/TLRPC\$TL_messages_searchGlobal;"
/** Messages matching the text in one chat; chat search asks it of a forum opened beside the chat list. */
internal const val CHAT_MESSAGES = "Lorg/telegram/tgnet/TLRPC\$TL_messages_search;"
/** Public accounts whose name or username matches the text. */
internal const val USERNAMES = "Lorg/telegram/tgnet/TLRPC\$TL_contacts_search;"
/** Public channel posts under a hashtag. */
internal const val PUBLIC_POSTS = "Lorg/telegram/tgnet/TLRPC\$TL_channels_searchPosts;"
/** The line under "No results" in search, which names the text that found nothing. */
internal const val NO_RESULT_LINE = "Lorg/telegram/messenger/R\$string;->NoResultFoundFor2:I"
private const val STRING = "Ljava/lang/String;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CONNECTIONS = "Lorg/telegram/tgnet/ConnectionsManager;"
private const val LOCALE = "Lorg/telegram/messenger/LocaleController;"
/** What chat search's own methods take: the search's number and the text. */
private val QUERY = listOf("I", STRING)
/** The username helper's lookup: the text, five switches, a channel, the phone switch, a type, the search's number, a chat to leave out and a callback. */
private val HELPER = listOf(STRING, "Z", "Z", "Z", "Z", "Z", "J", "Z", "I", "I", "J", "Ljava/lang/Runnable;")
private val LOWERS = setOf(Opcode.SUB_INT_2ADDR, Opcode.SUB_INT, Opcode.ADD_INT_LIT8)

/** The places the Chats tab sends its text, and the line it shows when nothing matched. */
internal enum class SearchLookup { SPONSORED, POSTS, MESSAGES, FORUM, USERNAMES, EMPTY_LINE }

/** [code] goes in front of instruction [index] of [method]; [jump], when it has one, is where its `:hush_local` lands. */
internal class SearchEdit(val lookup: SearchLookup, val method: MutableMethod, val index: Int, val code: String, val jump: Int? = null)

internal class LocalSearchSite(val edits: List<SearchEdit>) {
    /** Every edit, on the methods themselves or on what [into] makes of each. */
    fun apply(into: (MutableMethod) -> MutableMethod = { it }) {
        for (method in edits.map { it.method }.distinctBy { System.identityHashCode(it) }) {
            val target = into(method)
            val own = edits.filter { it.method === method }
            // Marked before the first edit and applied from the end, so no edit moves another's place.
            val marks = own.associateWith { edit -> edit.jump?.let { ExternalLabel("hush_local", target.getInstruction(it)) } }
            for (edit in own.sortedByDescending { it.index }) {
                val mark = marks.getValue(edit)
                if (mark == null) target.addInstructionsAtControlFlowLabel(edit.index, edit.code)
                else target.addInstructionsAtControlFlowLabel(edit.index, edit.code, mark)
            }
        }
    }

    /** The same edits on copies, so a smali or register problem refuses before the app is touched. */
    fun rehearse() = apply { MutableMethod(ImmutableMethod.of(it)) }
}

/**
 * Chat search is the adapter behind search's Chats tab, found by the sponsored accounts request only
 * it builds. Typing there starts a search of the chats and contacts on the phone and five lookups on
 * Telegram's server, and each lookup already has a way past its request that Telegram takes for
 * some other reason. A hook sends the lookup that way:
 *
 * - sponsored accounts and public posts each sit behind a check of the text, and skip as they do
 *   for text that's too short or isn't a hashtag;
 * - global messages skip as they do in a list that has no messages to look up, which takes one off
 *   the count of answers the tab waits for;
 * - a forum's messages skip as they do for an empty box, clearing what an earlier search found;
 * - the username helper gets no text, which Telegram passes for an empty box: the helper drops its
 *   pending requests and server results and answers at once, so the count runs down the same.
 *
 * The helper is shared with contact pickers and other screens, so only chat search's call to it is
 * touched. The line under "No results" is the pager's, set as each search starts.
 */
internal fun BytecodePatchContext.resolveLocalSearch(): LocalSearchSite {
    requireSearchRuntime()
    val builders = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/hushtelegram/extension/")) return@classDefForEach
        classDef.methods.filterTo(builders) { it.constructs(GET_SPONSORED_PEERS) }
    }
    val adapter = mutableClassDefBy(builders.one("chat search", ::refuse).definingClass)
    fun own(request: String, what: String): MutableMethod {
        val method = adapter.methods.filter { it.constructs(request) }.one(what, ::refuse)
        shape(method.params() == QUERY && method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags),
            "$what no longer takes the search's number and the text")
        return method
    }
    val search = own(GET_SPONSORED_PEERS, "chat search")
    val messages = own(GLOBAL_MESSAGES, "global message search")
    val forum = own(CHAT_MESSAGES, "forum message search")
    shape(setOf(search.name, messages.name, forum.name).size == 3, "chat search builds two of its requests in one method")
    val edits = listOf(sponsoredEdit(search), postsEdit(search), messagesEdit(search, messages), forumEdit(forum),
        usernamesEdit(adapter.type, search, messages, forum), emptyLineEdit(adapter.type, search))
    shape(edits.none { edit -> edit.jump != null && edits.any { it.method === edit.method && it.index == edit.jump } },
        "a search skip lands on another search hook")
    return LocalSearchSite(edits)
}

/**
 * The request is built behind `if-eqz text`, which skips it for no text, and that skip is where the
 * hook goes too. It sits in front of that check, well before the request Hide ads hooks, so the two
 * patches never meet whichever runs first.
 */
private fun sponsoredEdit(search: MutableMethod): SearchEdit {
    val body = search.instructions()
    val request = body.indices.filter { body[it].opcode == Opcode.NEW_INSTANCE && body[it].reference() == GET_SPONSORED_PEERS }
        .one("sponsored search request", ::refuse)
    val store = (request + 1 until body.size).firstOrNull {
        body[it].opcode == Opcode.IPUT_OBJECT && body[it].reference() == "$GET_SPONSORED_PEERS->q:$STRING" }
    shape(store != null && body[store].namedRegisters()[1] == body[request].namedRegisters().single(),
        "sponsored search no longer carries the text")
    val text = body[store!!].namedRegisters()[0]
    val send = (store until body.size).firstOrNull { body[it].call()?.let { call ->
        call.definingClass == CONNECTIONS && call.name == "sendRequest" } == true }
    val guard = (request - 1 downTo 0).firstOrNull { body[it].opcode == Opcode.IF_EQZ && body[it].namedRegisters().single() == text }
    shape(send != null && guard != null && (guard + 1 until store).none { text in body[it].writes() },
        "sponsored search no longer checks the text it sends")
    val past = ControlFlow.of(search).normal[guard!!].filter { it != guard + 1 }.one("sponsored search skip", ::refuse)
    shape(past > send!! && !reaches(search, request, guard), "sponsored search has a way to its request around the text check")
    val answer = search.freeLocalsAt(PATCH, guard, 1, targets = listOf(past), highest = 255).single()
    return SearchEdit(SearchLookup.SPONSORED, search, guard, keepGate(answer), past)
}

/**
 * Chat search posts a runnable that asks for public posts when the text is a hashtag: `if-eqz tag`
 * skips to the method's return for any other text, and the hook takes the same skip.
 */
private fun BytecodePatchContext.postsEdit(search: MutableMethod): SearchEdit {
    val body = search.instructions()
    val created = body.indices.filter { at -> body[at].opcode == Opcode.NEW_INSTANCE && body[at].reference()?.let { type ->
        classDefByOrNull(type)?.methods?.any { it.name == "run" && it.parameterTypes.isEmpty() && it.constructs(PUBLIC_POSTS) } } == true }
        .one("public posts runnable", ::refuse)
    val type = body[created].reference()
    val built = (created + 1..minOf(created + 3, body.lastIndex)).firstOrNull { body[it].call()?.let { call ->
        call.definingClass == type && call.name == "<init>" } == true }
    val guard = (created - 1 downTo 0).firstOrNull { body[it].opcode == Opcode.IF_EQZ }
    shape(built != null && guard != null, "public posts runnable is built another way")
    val tag = body[guard!!].namedRegisters().single()
    val past = ControlFlow.of(search).normal[guard].filter { it != guard + 1 }.one("public posts skip", ::refuse)
    shape(tag in body[built!!].namedRegisters().drop(1) && (guard + 1..built).none { tag in body[it].writes() } &&
        body[past].opcode == Opcode.RETURN_VOID && !reaches(search, created, guard),
        "public posts no longer start behind the hashtag check")
    val answer = search.freeLocalsAt(PATCH, guard, 1, targets = listOf(past), highest = 255).single()
    return SearchEdit(SearchLookup.POSTS, search, guard, keepGate(answer), past)
}

/**
 * One branch reaches the request, and its other side is Telegram's way past: it lowers the count
 * chat search sets when a search starts, tells the tab whether anything is still waited for, and
 * returns. The hook sits where that branch lands, so it reads the registers the way past reads.
 */
private fun messagesEdit(search: Method, messages: MutableMethod): SearchEdit {
    val body = messages.instructions()
    val request = body.indices.filter { body[it].opcode == Opcode.NEW_INSTANCE && body[it].reference() == GLOBAL_MESSAGES }
        .one("global message search request", ::refuse)
    val flow = ControlFlow.of(messages)
    val branch = flow.into(request).one("way to the global message search request", ::refuse)
    val settle = branch + 1
    shape(settle < request && flow.normal[branch].toSet() == setOf(settle, request) && body[request - 1].opcode == Opcode.RETURN_VOID,
        "global message search lost the way past its request")
    val waiting = body[settle].field()
    val started = search.instructions().filter { it.opcode == Opcode.IPUT }.mapNotNull { it.field() }
    shape(body[settle].opcode == Opcode.IGET && waiting != null && waiting.definingClass == messages.definingClass &&
        waiting.type == "I" && waiting in started && (settle until request).any { body[it].opcode in LOWERS } &&
        (settle until request).any { body[it].opcode == Opcode.IPUT && body[it].field() == waiting } &&
        (settle until request).none { body[it].call()?.name == "sendRequest" },
        "the way past the global message search no longer lowers the waiting count")
    val answer = messages.freeLocalsAt(PATCH, request, 1, targets = listOf(settle), highest = 255).single()
    return SearchEdit(SearchLookup.MESSAGES, messages, request, keepGate(answer), settle)
}

/**
 * For an empty box the method clears what it found and returns; `if-eqz` skips that for any text
 * and goes on to the request. The hook sits where that skip lands and goes back to the clearing.
 */
private fun forumEdit(forum: MutableMethod): SearchEdit {
    val body = forum.instructions()
    val request = body.indices.filter { body[it].opcode == Opcode.NEW_INSTANCE && body[it].reference() == CHAT_MESSAGES }
        .one("forum message search request", ::refuse)
    val flow = ControlFlow.of(forum)
    val branch = (0 until request).filter { body[it].opcode == Opcode.IF_EQZ && clearsAndReturns(body, flow, it, request) }
        .one("forum message search's empty box exit", ::refuse)
    val on = flow.normal[branch].first { it != branch + 1 }
    shape(flow.into(on) == listOf(branch) && !reaches(forum, request, on),
        "forum message search has a way to its request around the empty box check")
    val answer = forum.freeLocalsAt(PATCH, on, 1, targets = listOf(branch + 1), highest = 255).single()
    return SearchEdit(SearchLookup.FORUM, forum, on, keepGate(answer), branch + 1)
}

/** The `if-eqz` at [at] falls into a straight run that clears a list and returns, and branches to the instruction after that return. */
private fun clearsAndReturns(body: List<Instruction>, flow: ControlFlow, at: Int, request: Int): Boolean {
    val on = flow.normal[at].filter { it != at + 1 }.singleOrNull() ?: return false
    val end = (at + 1 until body.size).firstOrNull { body[it].opcode == Opcode.RETURN_VOID } ?: return false
    return on == end + 1 && on <= request && (at + 1 until end).all { flow.normal[it] == listOf(it + 1) } &&
        (at + 1 until end).any { body[it].call()?.let { call -> call.definingClass == ARRAY_LIST && call.name == "clear" } == true }
}

/**
 * Chat search's second step, a runnable, is the one method outside the adapter that starts both
 * message searches, and it asks the helper for usernames first. The hook swaps the text it passes.
 * Chat search itself passes the helper no text for an empty box, so that's a call Telegram makes.
 */
private fun BytecodePatchContext.usernamesEdit(adapter: String, search: Method, messages: Method, forum: Method): SearchEdit {
    val steps = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type == adapter || classDef.type.startsWith("Lapp/hushtelegram/extension/")) return@classDefForEach
        classDef.methods.filterTo(steps) { method -> method.instructions().let { body ->
            body.any { it.runs(adapter, messages) } && body.any { it.runs(adapter, forum) } } }
    }
    val found = steps.one("chat search's second step", ::refuse)
    val step = mutableClassDefBy(found.definingClass).methods.single { it.sameSignature(found) }
    val body = step.instructions()
    val call = body.indices.filter { body[it].call()?.let { lookup -> lookup.params() == HELPER && lookup.returnType == "V" } == true }
        .one("username lookup", ::refuse)
    val helper = body[call].call()!!
    shape(classDefByOrNull(helper.definingClass)?.methods?.any {
        it.name == helper.name && it.params() == HELPER && it.constructs(USERNAMES) } == true,
        "the username helper no longer builds its own request")
    val own = search.instructions()
    shape(own.indices.any { at -> own[at].call() == helper && own[at].namedRegisters().getOrNull(1)?.let { text ->
        (at - 1 downTo 0).firstOrNull { text in own[it].writes() }?.let { write ->
            own[write].opcode == Opcode.CONST_4 && (own[write] as NarrowLiteralInstruction).narrowLiteral == 0 } } == true },
        "chat search no longer clears the username helper by passing it no text")
    val text = body[call].namedRegisters().getOrNull(1)
    val loaded = text?.let { register -> (call - 1 downTo 0).firstOrNull { register in body[it].writes() } }
    val flow = ControlFlow.of(step)
    // Read from the runnable's own String field and passed straight on, and nothing reads the register afterwards.
    shape(text != null && text <= 15 && loaded != null && body[loaded].opcode == Opcode.IGET_OBJECT &&
        body[loaded].field()?.type == STRING && (loaded + 1..call).all { flow.into(it) == listOf(it - 1) } &&
        text !in RegisterLiveness.of(step).liveInto(call + 1), "the username lookup's text can't be swapped where it's passed")
    val passed = text!!
    return SearchEdit(SearchLookup.USERNAMES, step, call, "invoke-static {v$passed}, $SEARCH_SENT\nmove-result-object v$passed")
}

/** The pager starts each chat search and, first, formats the line under "No results" and sets it; the hook rewrites it in between. */
private fun BytecodePatchContext.emptyLineEdit(adapter: String, search: Method): SearchEdit {
    val pagers = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type == adapter || classDef.type.startsWith("Lapp/hushtelegram/extension/")) return@classDefForEach
        classDef.methods.filterTo(pagers) { method -> method.instructions().let { body ->
            body.any { it.runs(adapter, search) } && body.any { it.opcode == Opcode.SGET && it.reference() == NO_RESULT_LINE } } }
    }
    val found = pagers.one("search pager", ::refuse)
    val pager = mutableClassDefBy(found.definingClass).methods.single { it.sameSignature(found) }
    val body = pager.instructions()
    val line = body.indices.filter { body[it].opcode == Opcode.SGET && body[it].reference() == NO_RESULT_LINE }
        .one("no results line", ::refuse)
    val format = (line + 1..minOf(line + 4, body.lastIndex)).firstOrNull { body[it].call()?.let { call ->
        call.definingClass == LOCALE && call.name == "formatString" && call.returnType == STRING } == true }
    shape(format != null && body.getOrNull(format + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT, "the no results line is formatted another way")
    val text = body[format!! + 1].namedRegisters().single()
    val set = format + 2
    shape(body.getOrNull(set)?.call()?.let { it.name == "setText" && it.params() == listOf(CHAR_SEQUENCE) } == true &&
        body[set].namedRegisters().getOrNull(1) == text && text <= 15 && ControlFlow.of(pager).into(set) == listOf(set - 1) &&
        text !in RegisterLiveness.of(pager).liveInto(set + 1), "the no results line is set another way")
    return SearchEdit(SearchLookup.EMPTY_LINE, pager, set, "invoke-static {v$text}, $SEARCH_EMPTY_TEXT\nmove-result-object v$text")
}

private fun keepGate(answer: Int) = "invoke-static {}, $SEARCH_KEEP\nmove-result v$answer\nif-nez v$answer, :hush_local"

private fun BytecodePatchContext.requireSearchRuntime() {
    val owner = classDefByOrNull(LOCAL_SEARCH)
    shape(owner != null && AccessFlags.PUBLIC.isSet(owner.accessFlags), "no public local search runtime")
    for ((name, parameters, result) in listOf(Triple("keep", emptyList<String>(), "Z"), Triple("sent", listOf(STRING), STRING),
        Triple("emptyText", listOf(STRING), STRING))) {
        shape(owner!!.methods.count { it.name == name && it.params() == parameters && it.returnType == result &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.NATIVE.isSet(it.accessFlags) && !AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.implementation != null } == 1,
            "no callable public static runtime $name")
    }
}

/** Whether [target] can be reached from the method's start, thrown or not, without running [avoiding]. */
private fun reaches(method: Method, target: Int, avoiding: Int): Boolean {
    val flow = ControlFlow.of(method)
    val seen = BitSet()
    val pending = ArrayDeque<Int>()
    pending += 0
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (index == avoiding || seen[index]) continue
        seen.set(index)
        pending += flow.normal[index]
        pending += flow.exceptional[index]
    }
    return seen[target]
}

/** Every instruction that can run right before [index], by a fall-through, a branch or a throw. */
private fun ControlFlow.into(index: Int): List<Int> = instructions.indices.filter { index in normal[it] || index in exceptional[it] }

private fun refuse(reason: String): Nothing =
    throw PatchException("$PATCH: $reason; refuses changed search geometry before editing")
private fun shape(valid: Boolean, reason: String) {
    if (!valid) refuse(reason)
}
private fun Method.params(): List<String> = parameterTypes.map { it.toString() }
private fun MethodReference.params(): List<String> = parameterTypes.map { it.toString() }
private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType && params() == other.params()
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.constructs(type: String) = instructions().any { it.opcode == Opcode.NEW_INSTANCE && it.reference() == type }
private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.runs(adapter: String, method: Method) =
    call()?.let { it.definingClass == adapter && it.name == method.name && it.params() == method.params() } == true
/** The registers an instruction sets: the first named one for writes, both halves for a wide one. */
private fun Instruction.writes(): Set<Int> {
    if (!opcode.setsRegister()) return emptySet()
    val first = namedRegisters().firstOrNull() ?: return emptySet()
    return if (opcode.setsWideRegister()) setOf(first, first + 1) else setOf(first)
}
