/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.paste

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlCallable
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.controlSingle
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val MENTION_BY_ID = "$EXTENSION_PACKAGE/misc/MentionById;"
private const val OBJECT = "Ljava/lang/Object;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val MEDIA_DATA = "Lorg/telegram/messenger/MediaDataController;"
private const val ENTITIES = "$MEDIA_DATA->getEntities([Ljava/lang/CharSequence;ZZ)$ARRAY_LIST"

internal const val ADD_MENTIONS = "$MENTION_BY_ID->add($OBJECT$OBJECT$OBJECT)$ARRAY_LIST"

/** The one place a typed message's entities leave `MediaDataController`'s two-argument `getEntities`. */
internal class MentionSite(val method: MutableMethod, val index: Int, val register: Int) {
    private fun code() = "invoke-static {p0, p1, v$register}, $ADD_MENTIONS\nmove-result-object v$register"

    fun insert() = method.addInstructionsAtControlFlowLabel(index, code())

    /** The same edit on a copy, so a register or smali problem shows before the app is touched. */
    fun rehearse() = MutableMethod(ImmutableMethod.of(method)).addInstructionsAtControlFlowLabel(index, code())
}

/**
 * `MediaDataController.getEntities(CharSequence[], boolean)` keeps its name in every build and is
 * what the message box, captions and drafts call. It only passes on to the three-argument method
 * and returns what that gives back, so the hook goes in front of its one `return-object`, where the
 * list is in a register. The text array is its first argument and `this` the controller whose
 * account the hook reads.
 */
internal fun BytecodePatchContext.resolveMentionById(): MentionSite {
    controlHook(MENTION_BY_ID, "add", listOf(OBJECT, OBJECT, OBJECT), ARRAY_LIST)
    val owner = mutableClassDefByOrNull(MEDIA_DATA)
    controlShape(owner != null, "the media controller is missing")
    val method = owner!!.methods.filter { candidate ->
        candidate.name == "getEntities" && candidate.parameterTypes.map { it.toString() } == listOf("[Ljava/lang/CharSequence;", "Z") &&
            candidate.returnType == ARRAY_LIST
    }.controlSingle("the message entities method")
    controlShape(method.controlCallable(false), "the message entities method is not public")
    val body = method.controlBody()
    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }.controlSingle("entities return")
    val result = body.getOrNull(returns)?.namedRegisters()?.singleOrNull()
    controlShape(returns == body.size - 1 && returns >= 1 && body[returns - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        body[returns - 1].namedRegisters() == listOf(result) && body[returns - 2].controlRef() == ENTITIES &&
        result != null && result <= 15, "the message entities method changed")
    return MentionSite(method, returns, result!!).also { it.rehearse() }
}
