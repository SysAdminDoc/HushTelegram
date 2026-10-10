/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.commerce

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.parameterRegisterNumber
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PATCH = "Hide Premium, gifts and Stars"
internal const val STICKER_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
internal const val PREMIUM_BLOCKED = "$STICKER_CONTROLLER->premiumFeaturesBlocked()Z"
internal const val PREMIUM_DOCUMENT = "Lorg/telegram/messenger/MessageObject;->isPremiumSticker(Lorg/telegram/tgnet/TLRPC\$Document;)Z"
internal const val PREMIUM_MESSAGE = "Lorg/telegram/messenger/MessageObject;->isPremiumSticker()Z"
internal const val STICKER_TOOLTIP = "Lorg/telegram/messenger/R\$string;->PremiumStickerTooltip:I"
internal const val TAP_HINT = "Lorg/telegram/messenger/R\$string;->EmojiInteractionTapHint:I"
private const val FILTER = "filterPremiumStickers"
private const val GET_MESSAGE = "->getMessageObject()Lorg/telegram/messenger/MessageObject;"
private const val USER_CONFIG = "Lorg/telegram/messenger/UserConfig;"
private const val ACCOUNT_CONFIG = "Lorg/telegram/messenger/BaseController;->getUserConfig()$USER_CONFIG"
private const val IS_PREMIUM = "$USER_CONFIG->isPremium()Z"
private const val ASK_BLOCKED = "$COMMERCE->premiumStickersBlocked(Ljava/lang/Object;)Z"

/**
 * Premium stickers for an account without Premium, under Hide Premium, gifts and Stars. Telegram
 * already knows how to leave them out: where Premium can't be bought, premiumFeaturesBlocked
 * answers yes and its sticker filters drop them. [STICKERS] asks Commerce instead at just those
 * places, and [EFFECTS] keeps a Premium sticker's full-screen effect and its upsell tooltip from
 * playing in a chat.
 */
internal enum class PremiumStickerTarget(val capability: String) {
    STICKERS("commercePremiumStickers"), EFFECTS("commercePremiumEffects"),
}

/** The stubs Commerce's two questions read, written only for the targets that apply. */
internal val PREMIUM_STUBS = mapOf(
    "premiumBlocked" to """
        check-cast p0, $STICKER_CONTROLLER
        invoke-virtual {p0}, $PREMIUM_BLOCKED
        move-result v0
        return v0
    """,
    "premiumAccount" to """
        check-cast p0, $STICKER_CONTROLLER
        invoke-virtual {p0}, $ACCOUNT_CONFIG
        move-result-object v0
        invoke-virtual {v0}, $IS_PREMIUM
        move-result v0
        return v0
    """,
    "premiumSticker" to """
        check-cast p0, Lorg/telegram/messenger/MessageObject;
        invoke-virtual {p0}, $PREMIUM_MESSAGE
        move-result v0
        return v0
    """,
    "messageAccountPremium" to """
        check-cast p0, Lorg/telegram/messenger/MessageObject;
        iget v0, p0, Lorg/telegram/messenger/MessageObject;->currentAccount:I
        invoke-static {v0}, $USER_CONFIG->getInstance(I)$USER_CONFIG
        move-result-object v0
        invoke-virtual {v0}, $IS_PREMIUM
        move-result v0
        return v0
    """,
)

/**
 * [PremiumStickerTarget.STICKERS]: MessagesController's two sticker filters (one pack, and a list
 * of packs) and the keyboard pass that takes Premium stickers out of favorites and recents, each
 * gated on one premiumFeaturesBlocked. [PremiumStickerTarget.EFFECTS]: the effect player's class,
 * found by the tooltip it shows a person without Premium, and its handler that plays a sticker's
 * effect, which a chat calls when the sticker scrolls into view and when it's tapped. A target is
 * left out whole when any of its places is missing; two candidates for one place refuse.
 */
internal fun BytecodePatchContext.resolvePremiumStickerHooks(): Map<PremiumStickerTarget, List<CommerceEdit>> {
    val methods = mutableListOf<Method>()
    classDefForEach { if (!it.type.startsWith("Lapp/hushtelegram/extension/")) methods += it.methods }
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.single { it.sameSignature(method) }
    val hooks = mutableMapOf<PremiumStickerTarget, List<CommerceEdit>>()

    val blocked = methods.singleOrNull { it.definingClass == STICKER_CONTROLLER && it.name == "premiumFeaturesBlocked" }
    // The stubs read the account the same way Telegram's own answer does.
    val answer = blocked?.instructions()?.mapNotNull { it.reference() }.orEmpty()
    if (blocked == null || ACCOUNT_CONFIG !in answer || IS_PREMIUM !in answer) return hooks

    val filters = methods.filter { it.definingClass == STICKER_CONTROLLER && it.name == FILTER }
    shape(filters.size <= 2, "more than two Premium sticker filters (${filters.size})")
    val keyboard = methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.hasShape(listOf("Z"), "V") &&
            method.instructions().let { body ->
                body.count { it.reference() == PREMIUM_BLOCKED } == 1 && body.count { it.reference() == PREMIUM_DOCUMENT } >= 2
            }
    }.unique("keyboard's Premium sticker pass")
    if (filters.size == 2 && keyboard != null) {
        hooks[PremiumStickerTarget.STICKERS] = (filters + keyboard).map { blockedEdit(mutable(it)) }
    }

    val tooltip = methods.filter { method ->
        method.instructions().let { body -> body.any { it.reference() == STICKER_TOOLTIP } && body.any { it.reference() == PREMIUM_BLOCKED } }
    }.unique("Premium sticker tooltip")
    if (tooltip != null) {
        val player = methods.filter { method ->
            method.definingClass == tooltip.definingClass && method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.size == 3 && method.parameterTypes[2].toString() == "Z" &&
                method.instructions().let { body -> body.any { it.reference() == PREMIUM_MESSAGE } && body.any { it.reference() == TAP_HINT } }
        }.unique("sticker effect player")
        if (player != null) hooks[PremiumStickerTarget.EFFECTS] = listOf(blockedEdit(mutable(tooltip)), effectEdit(mutable(player)))
    }
    return hooks
}

/**
 * The one premiumFeaturesBlocked call in [method], asked of Commerce instead with the same
 * controller register. The result is read by the same move-result as before.
 */
private fun blockedEdit(method: MutableMethod): CommerceEdit {
    val body = method.instructions()
    val at = body.indices.filter { body[it].reference() == PREMIUM_BLOCKED }.singleOrNull()
        ?: throw PatchException("$PATCH: ${method.definingClass}->${method.name} no longer asks premiumFeaturesBlocked once (before editing)")
    shape(body[at].opcode == Opcode.INVOKE_VIRTUAL && body.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT,
        "${method.name}'s premiumFeaturesBlocked answer isn't read right after")
    val controller = body[at].namedRegisters().single()
    return CommerceEdit(method, at, "invoke-static {v$controller}, $ASK_BLOCKED", replace = true)
}

/**
 * Before the effect player does anything: a Premium sticker on an account without Premium returns
 * straight away, the way the player returns for a message it doesn't animate. The cell's message
 * is read with the same call the player makes, into a local, which holds nothing yet at entry.
 */
private fun effectEdit(method: MutableMethod): CommerceEdit {
    val body = method.instructions()
    val cell = method.parameterRegisterNumber(0)
    val read = body.firstOrNull {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.reference() == "${method.parameterTypes[0]}$GET_MESSAGE" && it.namedRegisters() == listOf(cell)
    }?.reference() ?: throw PatchException("$PATCH: the sticker effect player no longer reads its cell's message (before editing)")
    shape(cell - 1 >= 1 && cell <= 15, "the sticker effect player has no room to ask about its cell")
    // The keep label is held by the inserted code: a label on the stock first instruction would move
    // onto the guard along with the method start, and the guard would jump to itself.
    return CommerceEdit(method, 0, """
        if-eqz v$cell, :hush_keep
        invoke-virtual {v$cell}, $read
        move-result-object v0
        invoke-static {v0}, $COMMERCE->skipPremiumEffect(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :hush_keep
        return-void
        :hush_keep
        nop
    """)
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.hasShape(parameters: List<String>, result: String) =
    parameterTypes.map { it.toString() } == parameters && returnType == result
private fun Method.sameSignature(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
private fun <T> List<T>.unique(what: String): T? {
    shape(size <= 1, "ambiguous $what ($size candidates)")
    return singleOrNull()
}
private fun shape(ok: Boolean, what: String) {
    if (!ok) throw PatchException("$PATCH: $what (before editing)")
}
