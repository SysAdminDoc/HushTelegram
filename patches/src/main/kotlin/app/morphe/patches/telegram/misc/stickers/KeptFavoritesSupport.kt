/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.stickers

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireParameterIntact
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.requireThisIntact
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlString
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.call
import app.morphe.util.namedRegisters
import app.morphe.util.names.addInstructions
import app.morphe.util.names.origName
import app.morphe.util.names.origParameterTypes
import app.morphe.util.names.origReturnType
import app.morphe.util.names.tgMutableClassDefBy
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val KEPT_FAVORITES = "$EXTENSION_PACKAGE/misc/KeptFavorites;"
internal const val FAVE_FAVING = "$KEPT_FAVORITES->faving(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;II)V"
internal const val FAVE_DROPPED = "$KEPT_FAVORITES->dropped(Ljava/lang/Object;)V"
internal const val FAVE_LISTED = "$KEPT_FAVORITES->listed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val FAVE_FAVORITE = "$KEPT_FAVORITES->favorite(Ljava/lang/Object;Ljava/lang/Object;I)Z"
internal const val FAVE_REFERENCE =
    "$KEPT_FAVORITES->referenceParent(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

internal const val FAVE_MEDIA_DATA = "Lorg/telegram/messenger/MediaDataController;"
internal const val FAVE_FILE_REF = "Lorg/telegram/messenger/FileRefController;"
internal const val FAVE_DOCUMENT = "Lorg/telegram/tgnet/TLRPC\$Document;"
internal const val FAVE_RECENT_STICKERS = "$FAVE_MEDIA_DATA->recentStickers:[Ljava/util/ArrayList;"
private const val FAVE_OBJECT = "Ljava/lang/Object;"
private const val FAVE_LIST = "Ljava/util/ArrayList;"
private const val FAVE_BASE_CONTROLLER = "Lorg/telegram/messenger/BaseController;"
private const val FAVE_USER_CONFIG = "Lorg/telegram/messenger/UserConfig;"
private const val FAVE_SUB_LIST = "Ljava/util/ArrayList;->subList(II)Ljava/util/List;"
private const val FAVE_COPY = "Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V"
private const val FAVE_REMOVE = "Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;"
private const val SERIALIZED_DATA = "Lorg/telegram/tgnet/SerializedData;"
private const val TL_OBJECT = "Lorg/telegram/tgnet/TLObject;"

/** The parent Telegram gives a favorite sticker; only FileRefController's reference request compares it. */
private const val FAVORITES_PARENT = "fav"

/** One hook going in before instruction [at] of [method], at its control-flow label when [labelled]. */
internal class FaveEdit(val method: MutableMethod, val at: Int, val smali: String, val labelled: Boolean = false)

/**
 * Keep more favorite stickers' hooks, and the stubs that read Telegram's side for them. Or
 * [missing], why this build's favorites can't be kept past Telegram's limit.
 */
internal class KeptFavoritesPlan(val edits: List<FaveEdit>, val missing: String?) {
    /** The same edits on copies, and the stubs checked without going in, so a problem refuses before the app is touched. */
    fun rehearse(context: BytecodePatchContext) {
        edits.groupBy { it.method }.forEach { (method, own) -> edit(MutableMethod(ImmutableMethod.of(method)), own) }
        context.writeFavoriteStubs(install = false)
    }

    fun apply(context: BytecodePatchContext) {
        edits.groupBy { it.method }.forEach { (method, own) -> edit(method, own) }
        context.writeFavoriteStubs(install = true)
    }

    /** Last first, so the earlier indices still name the instructions they were read from. */
    private fun edit(target: MutableMethod, own: List<FaveEdit>) {
        own.sortedByDescending { it.at }.forEach {
            if (it.labelled) target.addInstructionsAtControlFlowLabel(it.at, it.smali) else target.addInstructions(it.at, it.smali)
        }
    }
}

private class Unkept(why: String) : Exception(why)

private fun unkept(why: String): Nothing = throw Unkept(why)

/**
 * Telegram keeps five favorite stickers, ten with Premium. MediaDataController.addRecentSticker
 * puts a new favorite first, and when the list is then over its limit takes the last one off; the
 * runtime is told at the start which sticker is being added or removed, and keeps the one taken
 * off. getRecentStickers hands the sticker panel, the sticker search and the rest a copy of a
 * list, and the runtime adds the kept stickers to the favorites' copy; isStickerInFavorites asks it
 * before answering no. A kept sticker's expired file reference goes through
 * FileRefController.requestReference, which would ask the favorites list for it again, so the
 * runtime hands it the sticker's pack instead.
 */
internal fun BytecodePatchContext.resolveKeptFavorites(): KeptFavoritesPlan {
    try {
        requireStatusMethod("keepMoreFavorites")
        controlHook(KEPT_FAVORITES, "faving", listOf(FAVE_OBJECT, "I", FAVE_OBJECT, FAVE_OBJECT, "I", "I"), "V")
        controlHook(KEPT_FAVORITES, "dropped", listOf(FAVE_OBJECT), "V")
        controlHook(KEPT_FAVORITES, "listed", listOf(FAVE_OBJECT, FAVE_OBJECT, FAVE_OBJECT), "V")
        controlHook(KEPT_FAVORITES, "favorite", listOf(FAVE_OBJECT, FAVE_OBJECT, "I"), "Z")
        controlHook(KEPT_FAVORITES, "referenceParent", listOf(FAVE_OBJECT, FAVE_OBJECT, FAVE_OBJECT), FAVE_OBJECT)
    } catch (changed: PatchException) {
        return KeptFavoritesPlan(emptyList(), "the favorites' runtime hooks changed (${changed.message})")
    }
    return try {
        KeptFavoritesPlan(favoriteEdits(), null)
    } catch (refused: Unkept) {
        KeptFavoritesPlan(emptyList(), refused.message)
    } catch (refused: PatchException) {
        KeptFavoritesPlan(emptyList(), refused.message)
    }
}

private fun BytecodePatchContext.favoriteEdits(): List<FaveEdit> {
    val media = tgMutableClassDefBy(FAVE_MEDIA_DATA)
    fun named(name: String, parameters: List<String>, result: String): MutableMethod {
        val found = media.methods.filter {
            it.origName == name && it.origParameterTypes == parameters && it.origReturnType == result &&
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
        }
        return found.singleOrNull() ?: unkept("MediaDataController.$name is missing or ambiguous (${found.size})")
    }
    val add = named("addRecentSticker", listOf("I", FAVE_OBJECT, FAVE_DOCUMENT, "I", "Z"), "V")
    val list = named("getRecentStickers", listOf("I", "Z"), FAVE_LIST)
    val check = named("isStickerInFavorites", listOf(FAVE_DOCUMENT), "Z")
    return addEdits(add) + listEdit(list) + checkEdits(check) + refreshEdit(referenceRequest())
}

/**
 * The start of addRecentSticker, which hands the runtime all six of its registers, and the one
 * place it takes a list's last sticker off: `recentStickers[type]` loaded, its last item removed
 * (by ArrayList.remove or a helper R8 outlined it into) and cast to a Document. The runtime sees
 * that sticker before the cast.
 */
private fun addEdits(add: MutableMethod): List<FaveEdit> {
    requireNothingJumpsTo(add, 0, "addRecentSticker's start")
    val body = add.controlBody()
    val drops = body.indices.filter { droppedAt(body, it) }
    if (drops.size != 1) unkept("addRecentSticker no longer takes a full list's last sticker off in one place (${drops.size})")
    val cast = drops.single()
    requireNothingJumpsTo(add, cast, "the sticker addRecentSticker takes off")
    val document = body[cast].namedRegisters().single()
    return listOf(
        FaveEdit(add, 0, "invoke-static/range {p0 .. p5}, $FAVE_FAVING"),
        FaveEdit(add, cast, "invoke-static/range {v$document .. v$document}, $FAVE_DROPPED"),
    )
}

/** Whether [at] is the cast of the sticker taken off the end of one of the recent sticker lists. */
private fun droppedAt(body: List<Instruction>, at: Int): Boolean {
    if (at < 3 || body[at].opcode != Opcode.CHECK_CAST || body[at].controlRef() != FAVE_DOCUMENT) return false
    val document = body[at].namedRegisters().single()
    val result = body[at - 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || result.namedRegisters() != listOf(document)) return false
    val call = body[at - 2]
    val list = when {
        call.opcode == Opcode.INVOKE_VIRTUAL && call.controlRef() == FAVE_REMOVE -> call.namedRegisters().first()
        call.opcode == Opcode.INVOKE_STATIC && call.call()?.let {
            it.parameterTypes.map(CharSequence::toString) == listOf("I", FAVE_LIST) && it.returnType == FAVE_OBJECT
        } == true -> call.namedRegisters()[1]
        else -> return false
    }
    val load = body[at - 3]
    if (load.opcode != Opcode.AGET_OBJECT || load.namedRegisters().first() != list) return false
    val lists = load.namedRegisters()[1]
    val written = (at - 4 downTo 0).firstOrNull { faveWrites(body[it], lists) } ?: return false
    return body[written].opcode == Opcode.IGET_OBJECT && body[written].controlRef() == FAVE_RECENT_STICKERS
}

/**
 * Right after getRecentStickers copies the start of a list into the one it returns: the runtime
 * gets `this`, the list copied from and the copy, and adds the kept stickers when the list is the
 * favorites.
 */
private fun listEdit(list: MutableMethod): FaveEdit {
    val body = list.controlBody()
    val copies = body.indices.filter { at ->
        at >= 2 && body[at].opcode == Opcode.INVOKE_DIRECT && body[at].controlRef() == FAVE_COPY &&
            body[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT && body[at - 2].controlRef() == FAVE_SUB_LIST &&
            body[at].namedRegisters().getOrNull(1) == body[at - 1].namedRegisters().single()
    }
    if (copies.size != 1) unkept("getRecentStickers no longer copies a list in one place (${copies.size})")
    val copy = copies.single()
    val source = body[copy - 2].namedRegisters().first()
    val into = body[copy].namedRegisters().first()
    if (source == body[copy - 1].namedRegisters().single() || source == into) {
        unkept("getRecentStickers no longer keeps the list it copies from")
    }
    for (at in copy - 1..copy + 1) requireNothingJumpsTo(list, at, "getRecentStickers' copy")
    if (copy + 1 !in body.indices) unkept("getRecentStickers ends at its copy")
    list.requireThisIntact("Keep more favorite stickers", listOf(copy + 1))
    val self = list.localRegisterCount()
    if (maxOf(self, source, into) > 15) unkept("getRecentStickers' registers don't fit the hook")
    return FaveEdit(list, copy + 1, "invoke-static {v$self, v$source, v$into}, $FAVE_LISTED")
}

/**
 * Each of isStickerInFavorites' answers that still has `this` and the sticker: the runtime may
 * turn it into a yes. The others have to be a plain yes Telegram reached by finding the sticker.
 */
private fun checkEdits(check: MutableMethod): List<FaveEdit> {
    val body = check.controlBody()
    val self = check.localRegisterCount()
    val document = self + 1
    val answers = body.indices.filter { body[it].opcode == Opcode.RETURN }
    val hooked = answers.filter { at ->
        try {
            check.requireThisIntact("Keep more favorite stickers", listOf(at))
            check.requireParameterIntact("Keep more favorite stickers", 0, listOf(at))
            true
        } catch (spoiled: PatchException) {
            false
        }
    }
    if (hooked.isEmpty()) unkept("isStickerInFavorites no longer has an answer the hook can read the sticker at")
    for (at in answers - hooked.toSet()) {
        val answer = body[at].namedRegisters().single()
        val yes = body.getOrNull(at - 1)
        if (yes?.opcode != Opcode.CONST_4 || (yes as NarrowLiteralInstruction).narrowLiteral != 1 || yes.namedRegisters() != listOf(answer)) {
            unkept("isStickerInFavorites answers somewhere the hook can't read the sticker")
        }
        requireNothingJumpsTo(check, at, "isStickerInFavorites' yes")
    }
    return hooked.map { at ->
        val answer = body[at].namedRegisters().single()
        if (maxOf(self, document, answer) > 15) unkept("isStickerInFavorites' registers don't fit the hook")
        FaveEdit(check, at, "invoke-static {v$self, v$document, v$answer}, $FAVE_FAVORITE\nmove-result v$answer", labelled = true)
    }
}

/**
 * FileRefController.requestReference(parentObject, args...), which R8 takes as (args, parent):
 * the instance method of that shape that compares the parent with "fav". Its name isn't in the
 * build's table, so its shape finds it.
 */
private fun BytecodePatchContext.referenceRequest(): MutableMethod {
    val found = tgMutableClassDefBy(FAVE_FILE_REF).methods.filter { m ->
        !AccessFlags.STATIC.isSet(m.accessFlags) && m.implementation != null && m.returnType == "V" &&
            m.parameterTypes.map(CharSequence::toString) == listOf("[$FAVE_OBJECT", FAVE_OBJECT) &&
            m.controlBody().any { it.controlString() == FAVORITES_PARENT }
    }
    return found.singleOrNull() ?: unkept("FileRefController no longer asks again for a reference in one method (${found.size})")
}

/** At requestReference's start, the runtime may hand back another parent, which takes the parent's register. */
private fun refreshEdit(refresh: MutableMethod): FaveEdit {
    requireNothingJumpsTo(refresh, 0, "requestReference's start")
    if (refresh.localRegisterCount() + 2 > 255) unkept("requestReference's parent is out of a result's reach")
    return FaveEdit(refresh, 0, "invoke-static/range {p0 .. p2}, $FAVE_REFERENCE\nmove-result-object p2")
}

/** Refuses when a branch, a switch or a handler goes to instruction [at] of [method]. */
private fun requireNothingJumpsTo(method: Method, at: Int, what: String) {
    val flow = ControlFlow.of(method)
    val into = flow.normal.indices.filter { at in flow.normal[it] && it != at - 1 } +
        flow.exceptional.indices.filter { at in flow.exceptional[it] }
    if (into.isNotEmpty()) unkept("something jumps to $what")
}

/** Whether [instruction] writes [register], alone or as the second half of a wide pair. */
private fun faveWrites(instruction: Instruction, register: Int): Boolean {
    val first = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return instruction.opcode.setsRegister() && first == register ||
        instruction.opcode.setsWideRegister() && first == register - 1
}

/** Gives KeptFavorites' stubs Telegram's code, or with [install] off only checks it would go in. */
private fun BytecodePatchContext.writeFavoriteStubs(install: Boolean) {
    writeStub(KEPT_FAVORITES, "account", 3, """
        check-cast p0, $FAVE_BASE_CONTROLLER
        invoke-virtual {p0}, $FAVE_BASE_CONTROLLER->getUserConfig()$FAVE_USER_CONFIG
        move-result-object v0
        invoke-virtual {v0}, $FAVE_USER_CONFIG->getClientUserId()J
        move-result-wide v0
        return-wide v0
    """, install)
    writeStub(KEPT_FAVORITES, "favoritesOf", 3, """
        check-cast p0, $FAVE_MEDIA_DATA
        iget-object v0, p0, $FAVE_RECENT_STICKERS
        const/4 v1, 0x2
        aget-object v0, v0, v1
        return-object v0
    """, install)
    writeStub(KEPT_FAVORITES, "documentId", 3, """
        check-cast p0, $FAVE_DOCUMENT
        iget-wide v0, p0, $FAVE_DOCUMENT->id:J
        return-wide v0
    """, install)
    writeStub(KEPT_FAVORITES, "serialized", 3, """
        check-cast p0, $TL_OBJECT
        new-instance v0, $SERIALIZED_DATA
        invoke-direct {v0}, $SERIALIZED_DATA-><init>()V
        invoke-virtual {p0, v0}, $TL_OBJECT->serializeToStream(Lorg/telegram/tgnet/OutputSerializedData;)V
        invoke-virtual {v0}, $SERIALIZED_DATA->toByteArray()[B
        move-result-object v1
        invoke-virtual {v0}, $SERIALIZED_DATA->cleanup()V
        return-object v1
    """, install)
    writeStub(KEPT_FAVORITES, "deserialized", 4, """
        new-instance v0, $SERIALIZED_DATA
        invoke-direct {v0, p0}, $SERIALIZED_DATA-><init>([B)V
        const/4 v1, 0x0
        invoke-virtual {v0, v1}, $SERIALIZED_DATA->readInt32(Z)I
        move-result v2
        invoke-static {v0, v2, v1}, $FAVE_DOCUMENT->TLdeserialize(Lorg/telegram/tgnet/InputSerializedData;IZ)$FAVE_DOCUMENT
        move-result-object v2
        invoke-virtual {v0}, $SERIALIZED_DATA->cleanup()V
        return-object v2
    """, install)
}
