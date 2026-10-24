/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.privacy.contactsLocation

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.LINKS_DESCRIPTOR
import app.crimera.patches.instagram.utils.fieldReference
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Element

private object IgBloksFullScreenOpenFingerprint : Fingerprint(
    returnType = "V",
    parameters =
        listOf(
            "Landroid/content/Context;",
            "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;",
        ),
    strings = listOf("BKDataFetcher.fetch"),
    filters = listOf(
        // App id of the opened screen.
        fieldAccess(definingClass = "this", type = "Ljava/lang/String;", opcode = Opcode.IGET_OBJECT),
    ),
)

/**
 * Manifest patch to strip contacts and location permissions and disable background location/contact services.
 * Note: ACCESS_MEDIA_LOCATION is deliberately preserved so Android 13+ Photo/Video pickers work without errors.
 */
val disableContactsAndLocationManifestPatch =
    resourcePatch(
        name = "Disable contacts and location components",
        description = "Removes contacts and location permissions and disables background location and contact services.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            document("AndroidManifest.xml").use { document ->
                val root = document.documentElement
                val appNode = root.getElementsByTagName("application").item(0) as? Element ?: return@use

                val blockedPermissions =
                    setOf(
                        "android.permission.READ_CONTACTS",
                        "android.permission.WRITE_CONTACTS",
                        "android.permission.GET_ACCOUNTS",
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION",
                        "android.permission.ACCESS_BACKGROUND_LOCATION",
                        "android.permission.FOREGROUND_SERVICE_LOCATION",
                    )

                val usesPermissions = root.getElementsByTagName("uses-permission")
                for (i in usesPermissions.length - 1 downTo 0) {
                    val elem = usesPermissions.item(i) as Element
                    val name = elem.getAttribute("android:name")
                    if (name in blockedPermissions) {
                        root.removeChild(elem)
                    }
                }

                val blockedComponents =
                    listOf(
                        "DirectLiveLocationService",
                        "NearbyVenuesService",
                        "LocationChangeReceiver",
                        "CCUJobService",
                        "ContactImportSettingsExternalUrlHandlerActivity",
                        "LocationUrlHandlerActivity",
                    )

                for (tag in listOf("service", "receiver", "activity")) {
                    val nodes = appNode.getElementsByTagName(tag)
                    for (i in nodes.length - 1 downTo 0) {
                        val elem = nodes.item(i) as Element
                        val name = elem.getAttribute("android:name")
                        if (blockedComponents.any { name.contains(it, ignoreCase = true) }) {
                            elem.setAttribute("android:enabled", "false")
                            elem.setAttribute("android:exported", "false")
                            if (elem.hasAttribute("android:process")) {
                                elem.removeAttribute("android:process")
                            }
                            val intentFilters = elem.getElementsByTagName("intent-filter")
                            for (j in intentFilters.length - 1 downTo 0) {
                                elem.removeChild(intentFilters.item(j))
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * Bytecode patch to suppress Contact Sync (CCU) prompts, NDX Onboarding screens, and Location dialogs/upsells:
 * 1. Suppresses NDX (New Device Experience) Bloks onboarding screens:
 *    - com.bloks.www.bloks.ig.ndx.ci.entry.screen ("Permite el acceso a los contactos...")
 *    - com.bloks.www.bloks.ig.ndx.ls.entry.screen (Location services prompt)
 * 2. Neutralizes NDXLauncher launcher methods (A03 -> returns false, A04 -> returns void).
 * 3. Neutralizes CCU Continuous Contacts Uploader: CCUServiceImpl.onStart -> returns false.
 * 4. Neutralizes CCUPluginImpl.initScheduler -> returns void.
 * 5. Short-circuits isContactsSyncAllowed check: LX/7od;->A00 -> returns false.
 * 6. Short-circuits location permission check: LX/4AJ;->A00 -> returns false.
 * 7. Suppresses device contacts background worker callbacks (DeviceContactsUpdaterAsync).
 */
@Suppress("unused")
val disableContactsAndLocationPromptsPatch =
    bytecodePatch(
        name = "Disable contacts and location prompts",
        description = "Removes contacts and location permissions, stops CCU contacts syncing, and suppresses popups asking for contact book or location.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(disableContactsAndLocationManifestPatch)

        execute {
            // 1. Suppress NDX (New Device Experience) Bloks onboarding prompt screens
            IgBloksFullScreenOpenFingerprint.let {
                val appIdField = it.instructionMatches.first().fieldReference

                it.method.addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $appIdField
                    if-eqz v0, :piko_not_ndx
                    const-string v1, "com.bloks.www.bloks.ig.ndx.ci.entry.screen"
                    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v1
                    if-nez v1, :piko_block_ndx
                    const-string v1, "com.bloks.www.bloks.ig.ndx.ls.entry.screen"
                    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v1
                    if-nez v1, :piko_block_ndx
                    const-string v1, "bloks.ig.ndx."
                    invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v1
                    if-nez v1, :piko_block_ndx
                    goto :piko_not_ndx
                    :piko_block_ndx
                    return-void
                    """.trimIndent(),
                    ExternalLabel("piko_not_ndx", it.method.getInstruction(0)),
                )
            }

            // 2. Neutralize NDXLauncher
            mutableClassDefByOrNull("Lcom/instagram/nux/ndx/util/NDXLauncher;")?.let { cls ->
                cls.methods.filter { it.implementation != null && it.name != "<init>" && it.name != "<clinit>" }.forEach { method ->
                    when (method.returnType) {
                        "V" -> method.addInstructions(0, "return-void")
                        "Z" ->
                            method.addInstructions(
                                0,
                                """
                                const/4 v0, 0x0
                                return v0
                                """.trimIndent(),
                            )
                    }
                }
            }

            // 3. Neutralize NdxStore
            mutableClassDefByOrNull("Lcom/instagram/nux/ndx/util/NdxStore;")?.let { cls ->
                cls.methods.filter { it.implementation != null && it.name != "<init>" && it.name != "<clinit>" }.forEach { method ->
                    when (method.returnType) {
                        "V" -> method.addInstructions(0, "return-void")
                        "Z" ->
                            method.addInstructions(
                                0,
                                """
                                const/4 v0, 0x0
                                return v0
                                """.trimIndent(),
                            )
                    }
                }
            }

            // 4. Suppress CCU start (CCUServiceImpl)
            mutableClassDefByOrNull("Lcom/instagram/contacts/ccu/impl/CCUServiceImpl;")?.let { cls ->
                cls.methods.find { it.implementation != null && it.name == "onStart" }?.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                )
            }

            // 5. Suppress CCU scheduler initialization (CCUPluginImpl)
            mutableClassDefByOrNull("Lcom/instagram/contacts/ccu/impl/CCUPluginImpl;")?.let { cls ->
                cls.methods.filter { it.implementation != null && it.name == "initScheduler" }.forEach { method ->
                    method.addInstructions(0, "return-void")
                }
            }

            // 6. Short-circuit isContactsSyncAllowed check (LX/7od)
            mutableClassDefByOrNull("LX/7od;")?.let { cls ->
                cls.methods.find { it.implementation != null && it.name == "A00" }?.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                )
            }

            // 7. Short-circuit location permission check (LX/4AJ)
            mutableClassDefByOrNull("LX/4AJ;")?.let { cls ->
                cls.methods.find { it.implementation != null && it.name == "A00" }?.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                )
            }

            // 8. Suppress device contacts updater callbacks (DeviceContactsUpdaterAsync)
            mutableClassDefByOrNull("Lcom/facebook/xccu/businesslogic/DeviceContactsUpdaterAsync;")?.let { cls ->
                cls.methods.filter { it.implementation != null && it.name.startsWith("A0") }.forEach { method ->
                    if (method.returnType == "V") {
                        method.addInstructions(0, "return-void")
                    }
                }
            }
        }
    }
