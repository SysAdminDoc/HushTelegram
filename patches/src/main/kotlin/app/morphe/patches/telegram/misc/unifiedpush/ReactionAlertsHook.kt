/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.unifiedpush

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.deleted.CHANNEL_DELETE_MESSAGES
import app.morphe.patches.telegram.misc.deleted.USER_DELETE_MESSAGES
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.liveAcrossInjection
import app.morphe.patches.telegram.misc.extension.localRegisterCount
import app.morphe.patches.telegram.misc.extension.requireThisIntact
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val REACTION_ALERTS = "$EXTENSION_PACKAGE/misc/ReactionAlerts;"
internal const val REACTIONS_UPDATE = "Lorg/telegram/tgnet/tl/TL_update\$TL_updateMessageReactions;"

private const val ANY = "Ljava/lang/Object;"
private const val TEXT = "Ljava/lang/String;"
private const val TG = "Lorg/telegram/messenger/"
private const val TL = "Lorg/telegram/tgnet/TLRPC\$"
private const val PEER = "${TL}Peer;"
private const val MESSAGE = "${TL}Message;"
private const val TG_CHAT = "${TL}Chat;"
private const val TG_USER = "${TL}User;"
private const val MESSAGE_OBJECT = "${TG}MessageObject;"
private const val CONTROLLER = "${TG}MessagesController;"
private const val BASE = "${TG}BaseController;"
private const val STORAGE = "${TG}MessagesStorage;"
private const val QUEUE = "${TG}DispatchQueue;"
private const val DATABASE = "Lorg/telegram/SQLite/SQLiteDatabase;"
private const val CURSOR = "Lorg/telegram/SQLite/SQLiteCursor;"
private const val BUFFER = "Lorg/telegram/tgnet/NativeByteBuffer;"
private const val ACCOUNT = "${TG}AccountInstance;"
private const val CONFIG = "${TG}UserConfig;"
private const val CONNECTION = "Lorg/telegram/tgnet/ConnectionsManager;"
private const val NOTIFIER = "${TG}NotificationsController;"
private const val STRINGS = "${TG}R\$string;"
private const val PATCH = "UnifiedPush notifications"

/** A method the extension calls by name, on the class that declares it. */
private class Call(val owner: String, val name: String, val parameters: String, val result: String, val static: Boolean = false)

private val CALLS = listOf(
    Call(BASE, "getMessagesStorage", "", STORAGE),
    Call(BASE, "getAccountInstance", "", ACCOUNT),
    Call(BASE, "getUserConfig", "", CONFIG),
    Call(BASE, "getConnectionsManager", "", CONNECTION),
    Call(BASE, "getNotificationsController", "", NOTIFIER),
    Call(ACCOUNT, "getCurrentAccount", "", "I"),
    Call(CONFIG, "getClientUserId", "", "J"),
    Call(CONNECTION, "getCurrentTime", "", "I"),
    Call(STORAGE, "getStorageQueue", "", QUEUE),
    Call(STORAGE, "getDatabase", "", DATABASE),
    Call(QUEUE, "postRunnable", "Ljava/lang/Runnable;", "Z"),
    Call(DATABASE, "queryFinalized", "$TEXT[$ANY", CURSOR),
    Call(CURSOR, "next", "", "Z"),
    Call(CURSOR, "intValue", "I", "I"),
    Call(CURSOR, "byteBufferValue", "I", BUFFER),
    Call(CURSOR, "dispose", "", "V"),
    Call(BUFFER, "readInt32", "Z", "I"),
    Call(BUFFER, "reuse", "", "V"),
    Call(MESSAGE, "TLdeserialize", "Lorg/telegram/tgnet/InputSerializedData;IZ", MESSAGE, static = true),
    Call(MESSAGE_OBJECT, "getPeerId", PEER, "J", static = true),
    Call(CONTROLLER, "getUser", "Ljava/lang/Long;", TG_USER),
    Call(CONTROLLER, "getChat", "Ljava/lang/Long;", TG_CHAT),
    Call("${TG}UserObject;", "getUserName", TG_USER, TEXT, static = true),
    Call("${TG}ChatObject;", "isChannel", TG_CHAT, "Z", static = true),
    Call("${TG}LocaleController;", "formatString", "I[$ANY", TEXT, static = true),
    Call(NOTIFIER, "processNewMessages", "Ljava/util/ArrayList;ZZLjava/util/concurrent/CountDownLatch;", "V"),
    Call(MESSAGE_OBJECT, "<init>", "I${MESSAGE}${TEXT}${TEXT}${TEXT}ZZZZ", "V"),
    Call("${TL}TL_message;", "<init>", "", "V"),
    Call("${TL}TL_peerUser;", "<init>", "", "V"),
    Call("${TL}TL_peerChat;", "<init>", "", "V"),
    Call("${TL}TL_peerChannel;", "<init>", "", "V"),
    Call("${TL}TL_messageReplyHeader;", "<init>", "", "V"),
)

/** The fields the extension reads and writes by name, on the class that declares each. */
private val FIELDS = listOf(
    Triple(REACTIONS_UPDATE, "peer", PEER),
    Triple(REACTIONS_UPDATE, "msg_id", "I"),
    Triple(REACTIONS_UPDATE, "top_msg_id", "I"),
    Triple(REACTIONS_UPDATE, "reactions", "${TL}TL_messageReactions;"),
    Triple("${TL}MessageReactions;", "recent_reactions", "Ljava/util/ArrayList;"),
    Triple("${TL}MessagePeerReaction;", "peer_id", PEER),
    Triple("${TL}MessagePeerReaction;", "reaction", "${TL}Reaction;"),
    Triple("${TL}MessagePeerReaction;", "date", "I"),
    Triple("${TL}MessagePeerReaction;", "unread", "Z"),
    Triple("${TL}MessagePeerReaction;", "flags", "I"),
    Triple("${TL}TL_reactionEmoji;", "emoticon", TEXT),
    Triple(PEER, "user_id", "J"),
    Triple(PEER, "chat_id", "J"),
    Triple(PEER, "channel_id", "J"),
    Triple(MESSAGE, "id", "I"),
    Triple(MESSAGE, "message", TEXT),
    Triple(MESSAGE, "date", "I"),
    Triple(MESSAGE, "dialog_id", "J"),
    Triple(MESSAGE, "peer_id", PEER),
    Triple(MESSAGE, "from_id", PEER),
    Triple(MESSAGE, "flags", "I"),
    Triple(MESSAGE, "out", "Z"),
    Triple(MESSAGE, "reply_to", "${TL}MessageReplyHeader;"),
    Triple("${TL}MessageReplyHeader;", "forum_topic", "Z"),
    Triple("${TL}MessageReplyHeader;", "reply_to_top_id", "I"),
    Triple(TG_CHAT, "title", TEXT),
    Triple(MESSAGE_OBJECT, "isReactionPush", "Z"),
)

/** Telegram's own reaction lines, the ones its push shows. */
private val LINES = listOf("PushReactText", "PushReactNoText", "PushChatReactText", "PushChatReactNotext")

/** Every type the extension reaches by name, for a test to load. */
internal val REACTION_ALERT_TYPES: Set<String> =
    (CALLS.map { it.owner } + FIELDS.map { it.first } + listOf(STRINGS, CONTROLLER)).toSet()

/** Where the update loop hands a reactions update over: right after it's cast, with two free registers in a row. */
internal class ReactionHook(val updates: MutableMethod, val insertAt: Int, val update: Int, val scratch: Int) {
    fun applyTo(method: MutableMethod) {
        method.addInstructionsAtControlFlowLabel(
            insertAt,
            """
                move-object/from16 v$scratch, v$update
                move-object/from16 v${scratch + 1}, p0
                invoke-static/range {v$scratch .. v${scratch + 1}}, $REACTION_ALERTS->changed($ANY$ANY)V
            """.trimIndent(),
        )
    }
}

private class NoReactions(why: String) : Exception(why)

private fun needed(ok: Boolean, why: String) {
    if (!ok) throw NoReactions(why)
}

/**
 * Telegram shows a reaction to your message only when a push says so; the same reaction coming in
 * on the open connection is only stored. The update loop casts each reactions update to its own
 * type, and the hook goes right after that, so the extension sees it before Telegram stores it. A
 * build where that or a name the extension reads can't be found keeps the rest of the patch.
 *
 * @return the hook, and null; or null and why there's none
 */
internal fun BytecodePatchContext.resolveReactionAlerts(): Pair<ReactionHook?, String?> = try {
    Pair(reactionHook(), null)
} catch (missing: NoReactions) {
    Pair(null, missing.message)
} catch (missing: PatchException) {
    Pair(null, missing.message)
}

private fun BytecodePatchContext.reactionHook(): ReactionHook {
    for (call in CALLS) {
        val owner = classDefByOrNull(call.owner)
        needed(owner != null && AccessFlags.PUBLIC.isSet(owner.accessFlags) && owner.methods.any {
            it.name == call.name && it.parameterTypes.joinToString("") == call.parameters && it.returnType == call.result &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == call.static
        }, "${call.owner}->${call.name} is no longer a public method reaction notifications can call")
    }
    for ((owner, name, type) in FIELDS) {
        val cls = classDefByOrNull(owner)
        needed(cls != null && AccessFlags.PUBLIC.isSet(cls.accessFlags) && cls.fields.any {
            it.name == name && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        }, "$owner->$name is no longer a public field reaction notifications can read")
    }
    val strings = classDefByOrNull(STRINGS)
    needed(strings != null && LINES.all { line ->
        strings.fields.any { it.name == line && it.type == "I" && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
    }, "Telegram's reaction notification lines are gone")

    // The update loop: the one method that also collects both kinds of deleted IDs.
    val loops = mutableClassDefByOrNull(CONTROLLER)?.methods?.filter { m ->
        val refs = m.controlBody().mapNotNull { it.controlRef() }.toSet()
        USER_DELETE_MESSAGES in refs && CHANNEL_DELETE_MESSAGES in refs && REACTIONS_UPDATE in refs
    } ?: emptyList()
    needed(loops.size == 1, "the update loop that reads reactions is missing or ambiguous")
    val updates = loops.single()
    needed(!AccessFlags.STATIC.isSet(updates.accessFlags), "the update loop is static")
    val body = updates.controlBody()
    val casts = body.indices.filter { body[it].opcode == Opcode.CHECK_CAST && body[it].controlRef() == REACTIONS_UPDATE }
    needed(casts.size == 1, "the update loop no longer casts a reactions update once")
    val cast = casts.single()
    val insertAt = cast + 1
    val flow = ControlFlow.of(updates)
    needed(insertAt < body.size && flow.normal.indices.none { it != cast && insertAt in flow.normal[it] },
        "something jumps past the reactions update's cast")
    val live = updates.liveAcrossInjection(insertAt)
    val free = (0 until updates.localRegisterCount()).filter { it !in live }
    val scratch = free.firstOrNull { it + 1 in free && it + 1 <= 255 }
    needed(scratch != null, "the update loop has no two registers in a row free after the reactions update's cast")
    updates.requireThisIntact(PATCH, listOf(insertAt))
    return ReactionHook(updates, insertAt, (body[cast] as OneRegisterInstruction).registerA, scratch!!)
}
