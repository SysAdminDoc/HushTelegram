/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.links

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.enableCapability
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel

@Suppress("unused")
val stripLinkTrackingPatch = bytecodePatch(
    name = "Strip link tracking",
    description = "Removes utm_source, utm_medium, utm_campaign, utm_term, utm_content, gclid and fbclid " +
        "from links you open or share with Share Link. A link with any other query key is left whole. Its " +
        "switch is on the Links page, under More settings in HushTelegram settings, and starts off.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())
    execute {
        listOf("stripLinkTracking", "openedLinkTracking", "sharedLinkTracking").forEach { requireStatusMethod(it) }
        val plan = resolveLinkHooks()
        requireRuntime("cleanOpenedUri", listOf(URI, "Z", "[Z"), URI)
        requireRuntime("cleanShareIntent", listOf(INTENT), INTENT)
        checkShape(plan.shares.isNotEmpty(), "no verified Share Link chooser")
        writeProtection(plan)
        plan.browser.addInstructionsAtControlFlowLabel(plan.cleanIndex, plan.cleanCode)
        plan.shares.groupBy { it.method }.forEach { (method, sites) ->
            sites.sortedByDescending { it.index }.forEach { site ->
                method.addInstructionsAtControlFlowLabel(site.index,
                    "invoke-static {v${site.register}}, $LINKS->cleanShareIntent($INTENT)$INTENT\nmove-result-object v${site.register}")
            }
        }
        enableCapability("openedLinkTracking")
        enableCapability("sharedLinkTracking")
        enableStatus("stripLinkTracking")
    }
}
