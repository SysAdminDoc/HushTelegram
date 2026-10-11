/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.chattypefolders

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.extension.writeStub
import app.morphe.patches.telegram.misc.localcontrols.controlBody
import app.morphe.patches.telegram.misc.localcontrols.controlCall
import app.morphe.patches.telegram.misc.localcontrols.controlHook
import app.morphe.patches.telegram.misc.localcontrols.controlRef
import app.morphe.patches.telegram.misc.localcontrols.controlShape
import app.morphe.patches.telegram.misc.localcontrols.requireReachable
import app.morphe.patches.telegram.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue

internal const val CHAT_TYPE_FOLDERS = "$EXTENSION_PACKAGE/misc/ChatTypeFolders;"
private const val STRING = "Ljava/lang/String;"
internal const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"
internal const val LOCAL_FILTER = "Lorg/telegram/messenger/MessagesController\$DialogFilter;"
internal const val USER_CONFIG = "Lorg/telegram/messenger/UserConfig;"
internal const val CONNECTIONS = "Lorg/telegram/tgnet/ConnectionsManager;"
internal const val SERVER_FILTER = "Lorg/telegram/tgnet/TLRPC\$DialogFilter;"
internal const val NEW_FILTER = "Lorg/telegram/tgnet/TLRPC\$TL_dialogFilter;"
internal const val TITLE = "Lorg/telegram/tgnet/TLRPC\$TL_textWithEntities;"
internal const val UPDATE_FILTER = "Lorg/telegram/tgnet/TLRPC\$TL_messages_updateDialogFilter;"
internal const val STORAGE = "Lorg/telegram/messenger/MessagesStorage;"
internal const val NOTIFICATIONS = "Lorg/telegram/messenger/NotificationCenter;"
private const val PINS = "Lorg/telegram/messenger/support/LongSparseIntArray;"
private const val LIST = "Ljava/util/ArrayList;"

internal const val SELECTED_ACCOUNT = "$USER_CONFIG->selectedAccount:I"
internal const val USER_CONFIG_INSTANCE = "$USER_CONFIG->getInstance(I)$USER_CONFIG"
internal const val CLIENT_ACTIVATED = "$USER_CONFIG->isClientActivated()Z"
internal const val CLIENT_USER_ID = "$USER_CONFIG->getClientUserId()J"
internal const val IS_PREMIUM = "$USER_CONFIG->isPremium()Z"
internal const val CONTROLLER_INSTANCE = "$MESSAGES_CONTROLLER->getInstance(I)$MESSAGES_CONTROLLER"
internal const val FILTERS = "$MESSAGES_CONTROLLER->dialogFilters:Ljava/util/ArrayList;"
internal const val FILTERS_LOADED = "$MESSAGES_CONTROLLER->dialogFiltersLoaded:Z"
internal const val LIMIT_DEFAULT = "$MESSAGES_CONTROLLER->dialogFiltersLimitDefault:I"
internal const val LIMIT_PREMIUM = "$MESSAGES_CONTROLLER->dialogFiltersLimitPremium:I"
internal const val FILTERS_BY_ID = "$MESSAGES_CONTROLLER->dialogFiltersById:Landroid/util/SparseArray;"
internal const val ADD_FILTER = "$MESSAGES_CONTROLLER->addFilter(${LOCAL_FILTER}Z)V"
internal const val REMOVE_FILTER = "$MESSAGES_CONTROLLER->removeFilter($LOCAL_FILTER)V"
internal const val STORAGE_INSTANCE = "$STORAGE->getInstance(I)$STORAGE"
internal const val SAVE_FILTER = "$STORAGE->saveDialogFilter(${LOCAL_FILTER}ZZ)V"
internal const val DELETE_FILTER = "$STORAGE->deleteDialogFilter($LOCAL_FILTER)V"
internal const val NOTIFICATIONS_INSTANCE = "$NOTIFICATIONS->getInstance(I)$NOTIFICATIONS"
internal const val FILTERS_UPDATED = "$NOTIFICATIONS->dialogFiltersUpdated:I"
internal const val LOCAL_INIT = "$LOCAL_FILTER-><init>()V"
internal const val LOCAL_ID = "$LOCAL_FILTER->id:I"
internal const val LOCAL_FLAGS = "$LOCAL_FILTER->flags:I"
internal const val LOCAL_NAME = "$LOCAL_FILTER->name:$STRING"
internal const val LOCAL_COLOR = "$LOCAL_FILTER->color:I"
internal const val LOCAL_UNREAD = "$LOCAL_FILTER->unreadCount:I"
internal const val LOCAL_PENDING_UNREAD = "$LOCAL_FILTER->pendingUnreadCount:I"
internal const val LOCAL_ALWAYS = "$LOCAL_FILTER->alwaysShow:$LIST"
internal const val LOCAL_NEVER = "$LOCAL_FILTER->neverShow:$LIST"
internal const val LOCAL_ENTITIES = "$LOCAL_FILTER->entities:$LIST"
internal const val LOCAL_PINNED = "$LOCAL_FILTER->pinnedDialogs:$PINS"
internal const val PINS_SIZE = "$PINS->size()I"
internal const val CONNECTIONS_INSTANCE = "$CONNECTIONS->getInstance(I)$CONNECTIONS"
internal const val SEND_REQUEST = "$CONNECTIONS->sendRequest(Lorg/telegram/tgnet/TLObject;Lorg/telegram/tgnet/RequestDelegate;)I"
internal const val NEW_FILTER_INIT = "$NEW_FILTER-><init>()V"
internal const val UPDATE_INIT = "$UPDATE_FILTER-><init>()V"
internal const val FILTER_ID = "$SERVER_FILTER->id:I"
internal const val FILTER_TITLE = "$SERVER_FILTER->title:$TITLE"
internal const val TITLE_TEXT = "$TITLE->text:$STRING"
internal const val FILTER_CONTACTS = "$SERVER_FILTER->contacts:Z"
internal const val FILTER_NON_CONTACTS = "$SERVER_FILTER->non_contacts:Z"
internal const val FILTER_GROUPS = "$SERVER_FILTER->groups:Z"
internal const val FILTER_BROADCASTS = "$SERVER_FILTER->broadcasts:Z"
internal const val FILTER_BOTS = "$SERVER_FILTER->bots:Z"
internal const val UPDATE_FLAGS = "$UPDATE_FILTER->flags:I"
internal const val UPDATE_ID = "$UPDATE_FILTER->id:I"
internal const val UPDATE_FILTER_FIELD = "$UPDATE_FILTER->filter:$NEW_FILTER"

/** The chat type bits of a local folder's flags, as ChatTypeFolders compares them. */
internal val TYPE_FLAGS = listOf(
    "$MESSAGES_CONTROLLER->DIALOG_FILTER_FLAG_CONTACTS:I" to 1,
    "$MESSAGES_CONTROLLER->DIALOG_FILTER_FLAG_NON_CONTACTS:I" to 2,
    "$MESSAGES_CONTROLLER->DIALOG_FILTER_FLAG_GROUPS:I" to 4,
    "$MESSAGES_CONTROLLER->DIALOG_FILTER_FLAG_CHANNELS:I" to 8,
    "$MESSAGES_CONTROLLER->DIALOG_FILTER_FLAG_BOTS:I" to 16,
)

@Suppress("unused")
val chatTypeFoldersPatch = bytecodePatch(
    name = "Folders by chat type",
    description = "Makes Private, Groups, Channels and Bots folders in one tap, with Telegram's own folders, so they " +
        "show as tabs above the chat list and on your other devices. Turning it off removes the ones it made, unless " +
        "you changed them. Starts off. Turn it on in HushTelegram settings > Chat list.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        val post = resolveChatTypeFolders()
        writeChatTypeFolderStubs(post)
        enableStatus("chatTypeFolders")
    }
}

/** Reads `dialogFilters.get(index)` of the account into v0, as a local folder. */
private const val FOLDER_AT = """
    invoke-static {p0}, $CONTROLLER_INSTANCE
    move-result-object v0
    iget-object v0, v0, $FILTERS
    invoke-virtual {v0, p1}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, $LOCAL_FILTER
"""

private fun BytecodePatchContext.writeChatTypeFolderStubs(post: String) {
    writeStub(CHAT_TYPE_FOLDERS, "selectedAccount", 1, """
        sget v0, $SELECTED_ACCOUNT
        return v0
    """)
    writeStub(CHAT_TYPE_FOLDERS, "signedIn", 1, """
        invoke-static {p0}, $USER_CONFIG_INSTANCE
        move-result-object p0
        invoke-virtual {p0}, $CLIENT_ACTIVATED
        move-result p0
        return p0
    """)
    writeStub(CHAT_TYPE_FOLDERS, "userId", 3, """
        invoke-static {p0}, $USER_CONFIG_INSTANCE
        move-result-object v0
        invoke-virtual {v0}, $CLIENT_USER_ID
        move-result-wide v0
        return-wide v0
    """)
    writeStub(CHAT_TYPE_FOLDERS, "foldersLoaded", 1, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object p0
        iget-boolean p0, p0, $FILTERS_LOADED
        return p0
    """)
    writeStub(CHAT_TYPE_FOLDERS, "folderCount", 1, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object p0
        iget-object p0, p0, $FILTERS
        invoke-virtual {p0}, Ljava/util/ArrayList;->size()I
        move-result p0
        return p0
    """)
    for ((stub, read) in listOf("folderId" to "iget v0, v0, $LOCAL_ID\n return v0",
        "folderFlags" to "iget v0, v0, $LOCAL_FLAGS\n return v0",
        "folderName" to "iget-object v0, v0, $LOCAL_NAME\n return-object v0")) {
        writeStub(CHAT_TYPE_FOLDERS, stub, 3, FOLDER_AT + read)
    }
    // Anything you can set on a folder besides its name and chat types makes it yours.
    writeStub(CHAT_TYPE_FOLDERS, "folderPlain", 4, FOLDER_AT + """
        iget v1, v0, $LOCAL_COLOR
        if-gez v1, :hush_changed
        iget-object v1, v0, $LOCAL_ENTITIES
        invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z
        move-result v1
        if-eqz v1, :hush_changed
        iget-object v1, v0, $LOCAL_ALWAYS
        invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z
        move-result v1
        if-eqz v1, :hush_changed
        iget-object v1, v0, $LOCAL_NEVER
        invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z
        move-result v1
        if-eqz v1, :hush_changed
        iget-object v1, v0, $LOCAL_PINNED
        invoke-virtual {v1}, $PINS_SIZE
        move-result v1
        if-nez v1, :hush_changed
        const/4 v0, 0x1
        return v0
        :hush_changed
        const/4 v0, 0x0
        return v0
    """)
    // What Telegram's own folder screen allows you to make: All chats takes one of Premium's places.
    writeStub(CHAT_TYPE_FOLDERS, "folderLimit", 2, """
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v0
        invoke-static {p0}, $USER_CONFIG_INSTANCE
        move-result-object p0
        invoke-virtual {p0}, $IS_PREMIUM
        move-result p0
        if-eqz p0, :hush_free
        iget p0, v0, $LIMIT_PREMIUM
        add-int/lit8 p0, p0, -0x1
        return p0
        :hush_free
        iget p0, v0, $LIMIT_DEFAULT
        return p0
    """)
    // The request Telegram's own folder screen sends for a new folder, with only chat types in it.
    writeStub(CHAT_TYPE_FOLDERS, "createFolder", 11, """
        new-instance v0, $NEW_FILTER
        invoke-direct {v0}, $NEW_FILTER_INIT
        iput p1, v0, $FILTER_ID
        new-instance v1, $TITLE
        invoke-direct {v1}, $TITLE-><init>()V
        iput-object p2, v1, $TITLE_TEXT
        iput-object v1, v0, $FILTER_TITLE
        iput-boolean p3, v0, $FILTER_CONTACTS
        iput-boolean p4, v0, $FILTER_NON_CONTACTS
        iput-boolean p5, v0, $FILTER_GROUPS
        iput-boolean p6, v0, $FILTER_BROADCASTS
        iput-boolean p7, v0, $FILTER_BOTS
        new-instance v1, $UPDATE_FILTER
        invoke-direct {v1}, $UPDATE_INIT
        iput p1, v1, $UPDATE_ID
        iget v2, v1, $UPDATE_FLAGS
        or-int/lit8 v2, v2, 0x1
        iput v2, v1, $UPDATE_FLAGS
        iput-object v0, v1, $UPDATE_FILTER_FIELD
        invoke-static {p0}, $CONNECTIONS_INSTANCE
        move-result-object v0
        const/4 v2, 0x0
        invoke-virtual {v0, v1, v2}, $SEND_REQUEST
        return-void
    """)
    // What Telegram's folder screen does with a new folder once it's sent: into the list at the end
    // and the database, with its unread count to be worked out, then the tabs redrawn.
    writeStub(CHAT_TYPE_FOLDERS, "addFolder", 8, """
        new-instance v0, $LOCAL_FILTER
        invoke-direct {v0}, $LOCAL_INIT
        iput p1, v0, $LOCAL_ID
        iput-object p2, v0, $LOCAL_NAME
        iput p3, v0, $LOCAL_FLAGS
        const/4 v1, -0x1
        iput v1, v0, $LOCAL_COLOR
        iput v1, v0, $LOCAL_UNREAD
        iput v1, v0, $LOCAL_PENDING_UNREAD
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v1
        const/4 v2, 0x0
        invoke-virtual {v1, v0, v2}, $ADD_FILTER
        invoke-static {p0}, $STORAGE_INSTANCE
        move-result-object v1
        const/4 v3, 0x1
        invoke-virtual {v1, v0, v2, v3}, $SAVE_FILTER
        invoke-static {p0}, $NOTIFICATIONS_INSTANCE
        move-result-object v1
        sget v2, $FILTERS_UPDATED
        const/4 v3, 0x0
        new-array v3, v3, [Ljava/lang/Object;
        invoke-virtual {v1, v2, v3}, $post
        return-void
    """)
    // Telegram's own Delete folder: an update with no filter in it, then out of the list and database.
    writeStub(CHAT_TYPE_FOLDERS, "deleteFolder", 5, """
        new-instance v0, $UPDATE_FILTER
        invoke-direct {v0}, $UPDATE_INIT
        iput p1, v0, $UPDATE_ID
        invoke-static {p0}, $CONNECTIONS_INSTANCE
        move-result-object v1
        const/4 v2, 0x0
        invoke-virtual {v1, v0, v2}, $SEND_REQUEST
        invoke-static {p0}, $CONTROLLER_INSTANCE
        move-result-object v0
        iget-object v1, v0, $FILTERS_BY_ID
        invoke-virtual {v1, p1}, Landroid/util/SparseArray;->get(I)Ljava/lang/Object;
        move-result-object v1
        if-eqz v1, :hush_gone
        check-cast v1, $LOCAL_FILTER
        invoke-virtual {v0, v1}, $REMOVE_FILTER
        invoke-static {p0}, $STORAGE_INSTANCE
        move-result-object v0
        invoke-virtual {v0, v1}, $DELETE_FILTER
        :hush_gone
        return-void
    """)
}

/**
 * Nothing in Telegram is hooked: the extension asks for folders through stubs, from the switch.
 * The stubs call Telegram's account, folder and request members from the extension's package, so
 * each one has to be public, and a local folder's chat type bits have to be the ones the extension
 * compares, which MessagesController's static initializer sets. NotificationCenter's post method
 * is renamed by R8, so it's taken from the call removeFilter makes to say the folders changed.
 *
 * @return the post method's reference
 */
internal fun BytecodePatchContext.resolveChatTypeFolders(): String {
    requireStatusMethod("chatTypeFolders")
    controlHook(CHAT_TYPE_FOLDERS, "selectedAccount", emptyList(), "I")
    controlHook(CHAT_TYPE_FOLDERS, "signedIn", listOf("I"), "Z")
    controlHook(CHAT_TYPE_FOLDERS, "userId", listOf("I"), "J")
    controlHook(CHAT_TYPE_FOLDERS, "foldersLoaded", listOf("I"), "Z")
    controlHook(CHAT_TYPE_FOLDERS, "folderCount", listOf("I"), "I")
    controlHook(CHAT_TYPE_FOLDERS, "folderId", listOf("I", "I"), "I")
    controlHook(CHAT_TYPE_FOLDERS, "folderFlags", listOf("I", "I"), "I")
    controlHook(CHAT_TYPE_FOLDERS, "folderName", listOf("I", "I"), STRING)
    controlHook(CHAT_TYPE_FOLDERS, "folderPlain", listOf("I", "I"), "Z")
    controlHook(CHAT_TYPE_FOLDERS, "folderLimit", listOf("I"), "I")
    controlHook(CHAT_TYPE_FOLDERS, "createFolder", listOf("I", "I", STRING, "Z", "Z", "Z", "Z", "Z"), "V")
    controlHook(CHAT_TYPE_FOLDERS, "addFolder", listOf("I", "I", STRING, "I"), "V")
    controlHook(CHAT_TYPE_FOLDERS, "deleteFolder", listOf("I", "I"), "V")

    val controller = mutableClassDefByOrNull(MESSAGES_CONTROLLER)
    controlShape(controller != null, "MessagesController is missing")
    // A starting value the compiler moved into the class's static values, or failing that, the last
    // constant the register held when the static initializer puts it, straight through.
    val stored = mutableMapOf<String, Int?>()
    for (field in controller!!.staticFields) {
        (field.initialValue as? IntEncodedValue)?.let { stored["$MESSAGES_CONTROLLER->${field.name}:${field.type}"] = it.value }
    }
    val init = controller.methods.singleOrNull { it.name == "<clinit>" }?.controlBody().orEmpty()
    val constants = mutableMapOf<Int, Int>()
    for (instruction in init) {
        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        when {
            instruction.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) ->
                constants[register] = (instruction as NarrowLiteralInstruction).narrowLiteral
            instruction.opcode == Opcode.SPUT -> instruction.controlRef()?.let { stored[it] = constants[register] }
            else -> constants.remove(register)
        }
    }
    controlShape(TYPE_FLAGS.all { (field, bit) -> stored[field] == bit }, "Telegram's folder chat type flags changed")

    // removeFilter reads dialogFiltersUpdated and hands it straight to NotificationCenter's post.
    val remove = controller.methods.singleOrNull { it.toString() == REMOVE_FILTER }?.controlBody().orEmpty()
    val read = remove.indexOfFirst { it.opcode == Opcode.SGET && it.controlRef() == FILTERS_UPDATED }
    val post = if (read < 0) null else remove.drop(read + 1).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }?.controlCall()
    controlShape(post != null && post.definingClass == NOTIFICATIONS &&
        post.controlShape(listOf("I", "[Ljava/lang/Object;"), "V"), "Telegram's folder list update changed")

    for ((reference, static) in listOf(SELECTED_ACCOUNT to true, USER_CONFIG_INSTANCE to true, CLIENT_ACTIVATED to false,
        CLIENT_USER_ID to false, IS_PREMIUM to false, CONTROLLER_INSTANCE to true, FILTERS to false, FILTERS_LOADED to false,
        LIMIT_DEFAULT to false, LIMIT_PREMIUM to false, FILTERS_BY_ID to false, ADD_FILTER to false, REMOVE_FILTER to false,
        STORAGE_INSTANCE to true, SAVE_FILTER to false, DELETE_FILTER to false, NOTIFICATIONS_INSTANCE to true,
        FILTERS_UPDATED to true, post.toString() to false, LOCAL_INIT to false, LOCAL_ID to false, LOCAL_FLAGS to false,
        LOCAL_NAME to false, LOCAL_COLOR to false, LOCAL_UNREAD to false, LOCAL_PENDING_UNREAD to false,
        LOCAL_ALWAYS to false, LOCAL_NEVER to false, LOCAL_ENTITIES to false, LOCAL_PINNED to false, PINS_SIZE to false,
        CONNECTIONS_INSTANCE to true, SEND_REQUEST to false, NEW_FILTER_INIT to false, UPDATE_INIT to false,
        "$TITLE-><init>()V" to false, FILTER_ID to false, FILTER_TITLE to false, TITLE_TEXT to false,
        FILTER_CONTACTS to false, FILTER_NON_CONTACTS to false, FILTER_GROUPS to false, FILTER_BROADCASTS to false,
        FILTER_BOTS to false, UPDATE_FLAGS to false, UPDATE_ID to false, UPDATE_FILTER_FIELD to false)) {
        requireReachable(reference, static)
    }
    return post.toString()
}
