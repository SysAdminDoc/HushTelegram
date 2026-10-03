/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.firebase

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.telegram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.telegram.misc.extension.enableCapability
import app.morphe.patches.telegram.misc.extension.enableStatus
import app.morphe.patches.telegram.misc.extension.requireStatusMethod
import app.morphe.patches.telegram.misc.extension.telegramExtensionPatch
import app.morphe.patches.telegram.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterKind
import app.morphe.util.RegisterKinds
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Repair Firebase push registration"
internal const val FIREBASE_PUSH = "$EXTENSION_PACKAGE/misc/FirebasePush;"
internal const val CERTIFICATE_HOOK = "$FIREBASE_PUSH->certificateHeader(Ljava/net/URLConnection;Ljava/lang/String;)Ljava/lang/String;"
private const val STRING = "Ljava/lang/String;"
private const val HTTP = "Ljava/net/HttpURLConnection;"
private const val CONNECTION = "Ljava/net/URLConnection;"
private const val URL = "Ljava/net/URL;"

/**
 * Fresh implementation from Firebase's documented header path. Telegram 12.10.6 inlines
 * getFingerprintHashForPackage into openHttpURLConnection, so only its header value is wrapped.
 * Reference: firebase/firebase-android-sdk FirebaseInstallationServiceClient.java.
 */
@Suppress("unused")
val repairFirebasePushPatch = bytecodePatch(
    name = PATCH,
    description = "Restores Telegram's official certificate header in Firebase Installations requests " +
        "on re-signed builds. Other signature checks keep their usual behavior.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch, telegramExtensionPatch)
    compatibleWith(*AppCompatibilities.telegram())

    execute {
        requireStatusMethod("repairFirebasePush")
        requireStatusMethod("firebaseCertificateHeader")
        val plan = resolveFirebaseHeader()
        plan.method.addInstructionsAtControlFlowLabel(plan.index, """
            invoke-static {v${plan.connection}, v${plan.value}}, $CERTIFICATE_HOOK
            move-result-object v${plan.value}
        """)
        enableCapability("firebaseCertificateHeader")
        enableStatus("repairFirebasePush")
    }
}

internal data class FirebaseHeaderPlan(
    val method: MutableMethod, val index: Int, val connection: Int, val value: Int,
    val requests: Map<FirebaseRequest, Method>,
)
internal enum class FirebaseRequest { CREATE, TOKEN, DELETE }

/** Every retained request must reach the same scoped sink before any mutation is made. */
internal fun BytecodePatchContext.resolveFirebaseHeader(): FirebaseHeaderPlan {
    val classes = linkedMapOf<String, ClassDef>()
    classDefForEach { if (!it.type.startsWith("Lapp/hushtelegram/extension/")) classes[it.type] = it }
    val methods = classes.values.flatMap { it.methods.toList() }
    val header = methods.filter { it.hasShape(listOf(URL, STRING), HTTP) &&
        listOf("X-Android-Cert", "X-Android-Package", "x-goog-api-key", "SHA1",
            "Could not get fingerprint hash for package: ").all { anchor -> anchor in it.strings() }
    }.unique("Firebase certificate header method")
    shape(!AccessFlags.STATIC.isSet(header.accessFlags), "header method lost its receiver")
    val owner = classes.getValue(header.definingClass)
    val uri = owner.methods.filter { it.hasShape(listOf(STRING), URL) &&
        "https://firebaseinstallations.googleapis.com/v1/" in it.strings()
    }.unique("Firebase request URL factory")
    val callers = methods.filter { method -> method.instructions().any { it.call()?.same(header) == true } }
    val requests = linkedMapOf<FirebaseRequest, Method>()
    for (caller in callers) {
        val strings = caller.strings()
        val role = when {
            "/authTokens:generate" in strings && "POST" in strings -> FirebaseRequest.TOKEN
            "/installations" in strings && "x-goog-fis-android-iid-migration-auth" in strings && "POST" in strings -> FirebaseRequest.CREATE
            "/installations/" in strings && "DELETE" in strings -> FirebaseRequest.DELETE
            else -> throw PatchException("$PATCH: unrecognized Firebase request caller $caller; refuses before editing")
        }
        shape(requests.put(role, caller) == null, "more than one $role request path")
        shape(caller.instructions().any { it.call()?.same(uri) == true }, "$role request bypasses the Firebase URL factory")
    }
    shape(FirebaseRequest.CREATE in requests && FirebaseRequest.TOKEN in requests,
        "installation creation or token generation no longer reaches the shared header")

    val method = mutableClassDefBy(header.definingClass).methods.single { it.same(header) }
    val instructions = method.instructions()
    val literal = instructions.indices.filter { instructions[it].string() == "X-Android-Cert" }.unique("certificate header literal")
    val sink = literal + 1
    val operands = instructions.getOrNull(sink)?.namedRegisters().orEmpty()
    shape(instructions[literal].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        operands.size == 3 && operands.distinct().size == 3 && operands.all { it in 0..15 } &&
        instructions[literal].namedRegisters() == listOf(operands[1]) &&
        instructions.getOrNull(sink)?.isHeaderWrite() == true,
        "certificate literal no longer binds a unique header write")
    val flow = ControlFlow.of(method)
    shape(flow.normal.indices.all { at -> sink !in flow.normal[at] || at == literal } &&
        flow.exceptional.none { sink in it }, "an edge bypasses the certificate key")
    val kinds = RegisterKinds.of(method).at(sink)
    shape(kinds != null && kinds.getOrNull(operands[0]) == RegisterKind.ref(HTTP) &&
        kinds.getOrNull(operands[1]) == RegisterKind.ref(STRING) &&
        kinds.getOrNull(operands[2]) in setOf(RegisterKind.ref(STRING), RegisterKind.ZERO),
        "certificate operands are not a verified HTTP connection and nullable string")
    // R8 reuses a different scratch register in beta. Bind that literal to its own header write,
    // while requiring the original API-key parameter and the same returned connection.
    val apiKey = instructions.getOrNull(sink + 1)
    val apiOperands = instructions.getOrNull(sink + 2)?.namedRegisters().orEmpty()
    val apiParameter = method.implementation!!.registerCount - 1
    shape(apiKey?.string() == "x-goog-api-key" &&
        apiKey.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        apiOperands.size == 3 && apiOperands.distinct().size == 3 &&
        apiKey.namedRegisters() == listOf(apiOperands[1]) &&
        instructions.getOrNull(sink + 2)?.isHeaderWrite() == true &&
        apiOperands[0] == operands[0] && apiOperands[2] == apiParameter &&
        flow.normal.indices.all { at -> sink + 2 !in flow.normal[at] || at == sink + 1 } &&
        flow.exceptional.none { sink + 2 in it } &&
        instructions.getOrNull(sink + 3)?.opcode == Opcode.RETURN_OBJECT &&
        instructions.getOrNull(sink + 3)?.namedRegisters() == listOf(operands[0]),
        "certificate write lost its API-key header and connection return")
    val runtime = mutableClassDefBy(FIREBASE_PUSH)
    shape(AccessFlags.PUBLIC.isSet(runtime.accessFlags) && runtime.methods.count { hook ->
        hook.name == "certificateHeader" && hook.hasShape(listOf(CONNECTION, STRING), STRING) &&
            AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags) &&
            !AccessFlags.ABSTRACT.isSet(hook.accessFlags) && !AccessFlags.NATIVE.isSet(hook.accessFlags) &&
            (hook.implementation?.registerCount ?: 0) >= 2 && hook.instructions().any { !it.opcode.format.isPayloadFormat }
    } == 1, "extension certificate hook is not callable")
    return FirebaseHeaderPlan(method, sink, operands[0], operands[2], requests)
}

private fun Instruction.isHeaderWrite() = opcode == Opcode.INVOKE_VIRTUAL && call()?.let {
    it.definingClass in setOf(CONNECTION, HTTP) && it.name == "addRequestProperty" && it.hasShape(listOf(STRING, STRING), "V")
} == true
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.strings() = instructions().mapNotNull { it.string() }.toSet()
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun MethodReference.hasShape(parameters: List<String>, result: String) =
    parameterTypes.map(CharSequence::toString) == parameters && returnType == result
private fun MethodReference.same(other: MethodReference) = definingClass == other.definingClass && name == other.name &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString) && returnType == other.returnType
private fun shape(valid: Boolean, reason: String) {
    if (!valid) throw PatchException("$PATCH: $reason; refuses changed Firebase geometry before editing")
}
private fun <T> List<T>.unique(what: String): T {
    shape(size == 1, "$what has $size matches")
    return single()
}
