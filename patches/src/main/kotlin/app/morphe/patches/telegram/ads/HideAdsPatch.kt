/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.handleTargets
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.returnEarlyWhen
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch

private const val PATCH = "Hide ads"

private const val ADS = "$EXTENSION_PACKAGE/ads/Ads;"

/** The request for a channel's sponsored messages. TL classes keep their names in every build. */
internal const val GET_SPONSORED_MESSAGES = "Lorg/telegram/tgnet/TLRPC\$TL_messages_getSponsoredMessages;"

internal const val MESSAGES_CONTROLLER = "Lorg/telegram/messenger/MessagesController;"

/**
 * The messages controller's `getSponsoredMessages(long)`: answers a chat's cached sponsored
 * messages, and asks the server for them when it has none. Found by what it builds and answers,
 * both kept names, rather than by its own name.
 */
internal object GetSponsoredMessagesFingerprint : Fingerprint(
    definingClass = MESSAGES_CONTROLLER,
    returnType = "Lorg/telegram/messenger/MessagesController\$SponsoredMessagesInfo;",
    parameters = listOf("J"),
    filters = listOf(newInstance(GET_SPONSORED_MESSAGES)),
)

/** `VideoAds.load()`: the video player's own sponsored messages request. */
internal object VideoAdsLoadFingerprint : Fingerprint(
    definingClass = "Lorg/telegram/messenger/video/VideoAds;",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(newInstance(GET_SPONSORED_MESSAGES)),
)

/**
 * Keeps Telegram from asking for sponsored messages.
 *
 * Both requests start by asking the extension, and while the switch is on they never go out: a
 * channel answers as one with no sponsored messages, and the video player as one with no ad. With
 * nothing fetched, nothing is drawn, marked as seen or reported as clicked. The two places stand
 * alone, so a build that moved one still has the other covered and the patch log names the one it
 * went without.
 *
 * Found by reading 12.10.6 (2026-09-30): both methods build `TL_messages_getSponsoredMessages`,
 * and nothing else in the app does.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the sponsored messages in channels and the ads in Telegram's video player. " +
        "Telegram never asks for them, so none are counted as seen.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("hideAds")

        handleTargets(PATCH, "sponsored message requests", AdRequest.entries) { request ->
            when (request) {
                AdRequest.CHANNEL -> GetSponsoredMessagesFingerprint.methodOrNull.let { method ->
                    if (method == null) "no method of the messages controller builds $GET_SPONSORED_MESSAGES for a chat"
                    else {
                        method.returnEarlyWhen(PATCH, "$ADS->skipSponsoredMessages()Z", "const/4 v0, 0x0\nreturn-object v0")
                        null
                    }
                }
                AdRequest.VIDEO -> VideoAdsLoadFingerprint.methodOrNull.let { method ->
                    if (method == null) "VideoAds has no load() that builds $GET_SPONSORED_MESSAGES"
                    else {
                        method.returnEarlyWhen(PATCH, "$ADS->skipVideoAds()Z", "return-void")
                        null
                    }
                }
            }
        }

        enableStatus("hideAds")
    }
}

/** The places Telegram asks for sponsored messages. */
private enum class AdRequest { CHANNEL, VIDEO }
