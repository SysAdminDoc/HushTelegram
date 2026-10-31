/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.messagemenu

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlHook

/** The extension class that holds the menu items with a choice (Fast forward, Forward to Saved Messages). */
internal const val MESSAGE_MENU_TOOLS = "$EXTENSION_PACKAGE/misc/MessageMenuTools;"

private const val TOOLS_SEND_HELPER = "Lorg/telegram/messenger/SendMessagesHelper;"
private const val TOOLS_LIST = "Ljava/util/ArrayList;"

/** Fails the patch cleanly when the extension's stub is missing. */
internal fun BytecodePatchContext.resolveMessageMenuTools() {
    controlHook(MESSAGE_MENU_TOOLS, "forwardWith", listOf("I", TOOLS_LIST, "J", "Z", "Z"), "I")
}

/**
 * Telegram's own forward with both flags the caller picks: the sender's name hidden (its "forward from
 * my name" argument) and captions dropped. Sound on, now, no stars agreed yet.
 */
internal fun BytecodePatchContext.writeMessageMenuToolsStubs() {
    writeStub(MESSAGE_MENU_TOOLS, "forwardWith", 16, """
        invoke-static {p0}, $TOOLS_SEND_HELPER->getInstance(I)$TOOLS_SEND_HELPER
        move-result-object v0
        move-object v1, p1
        move-wide v2, p2
        move v4, p4
        move v5, p5
        const/4 v6, 0x1
        const/4 v7, 0x0
        const-wide/16 v8, 0x0
        invoke-virtual/range {v0 .. v9}, $TOOLS_SEND_HELPER->sendMessage(${TOOLS_LIST}JZZZIJ)I
        move-result v0
        return v0
    """)
}
