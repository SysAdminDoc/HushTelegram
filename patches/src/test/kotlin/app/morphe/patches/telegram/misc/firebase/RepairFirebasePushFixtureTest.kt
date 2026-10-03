/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.telegram.misc.firebase

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.telegram.misc.extension.SETTINGS_STATUS
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.apksig.ApkVerifier
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Independent signer and request census, with complete pre-mutation refusal snapshots. */
class RepairFirebasePushFixtureTest {
    @Test
    fun `header seed is the actual official fixture signer rather than a guessed fingerprint`() {
        val seed = ExtensionDex.classes().single { it.type == FIREBASE_PUSH }.fields.single {
            it.name == "OFFICIAL_CERTIFICATE_SHA1"
        }.initialValue as StringEncodedValue
        for (build in Fixtures.declaredBuilds()) {
            val signature = ApkVerifier.Builder(build).build().verify()
            assertTrue("${build.name}: fixture signature verifies", signature.isVerified)
            val certificates = signature.signerCertificates
            assertEquals("${build.name}: one official signer", 1, certificates.size)
            val encoded = certificates.single().encoded
            assertEquals("${build.name}: independently pinned SHA-256",
                "49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1", digest("SHA-256", encoded))
            assertEquals("${build.name}: header is the verified SHA-1", digest("SHA-1", encoded).uppercase(), seed.value)
        }
    }

    @Test
    fun `one certificate sink covers every retained create and token request and preserves all other operations`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            val original = hosts.flatMap { it.methods.toList() }
                .single { method -> "X-Android-Cert" in method.strings() }
            val before = original.instructions()
            val at = before.indexOfFirst { it.string() == "X-Android-Cert" } + 1
            val registers = before[at].namedRegisters()
            assertEquals("${build.name}: public shared connection header path", listOf("Ljava/net/URL;", "Ljava/lang/String;"),
                original.parameterTypes.map(CharSequence::toString))
            assertEquals("${build.name}: native lookup stays in the method", 2,
                before.count { it.reference() == "Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;" })
            val census = FixtureDex.methodsWhere(build, { true }) { method -> method.instructions().any {
                it.call()?.signature() == original.signature()
            } }
            assertEquals("${build.name}: every Firebase header caller is retained", 2, census.size)
            assertEquals("${build.name}: no deletion request survives R8", 0, census.count { "DELETE" in it.strings() })
            assertEquals("${build.name}: one rotation request", 1, census.count { "/authTokens:generate" in it.strings() })
            assertEquals("${build.name}: one creation request", 1, census.count {
                "/installations" in it.strings() && "x-goog-fis-android-iid-migration-auth" in it.strings()
            })
            repairFirebasePushPatch.execute(context)
            val patched = context.mutableClassDefBy(original.definingClass).methods.single { it.signature() == original.signature() }
            val after = patched.instructions()
            assertEquals(before.size + 2, after.size)
            assertEquals(Opcode.INVOKE_STATIC, after[at].opcode)
            assertEquals(CERTIFICATE_HOOK, after[at].reference())
            assertEquals(listOf(registers[0], registers[2]), after[at].namedRegisters())
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
            assertEquals(listOf(registers[2]), after[at + 1].namedRegisters())
            assertEquals("${build.name}: only the header value is wrapped", before.map(::operation),
                (after.take(at) + after.drop(at + 2)).map(::operation))
            assertEquals(original.implementation!!.registerCount, patched.implementation!!.registerCount)
            val oldFlow = ControlFlow.of(original)
            val newFlow = ControlFlow.of(patched)
            fun moved(index: Int) = index + if (index >= at) 2 else 0
            fun destination(index: Int) = if (index == at) at else moved(index)
            for (index in before.indices) {
                assertEquals("${build.name}: stock normal successors at $index",
                    oldFlow.normal[index].map(::destination), newFlow.normal[moved(index)])
                assertEquals("${build.name}: stock exceptional successors at $index",
                    oldFlow.exceptional[index].map(::destination), newFlow.exceptional[moved(index)])
            }
            for (owner in hosts) for (method in owner.methods) {
                if (method.signature() == original.signature()) continue
                assertEquals("${build.name}: untouched ${method.signature()}", snapshot(method),
                    snapshot(context.mutableClassDefBy(owner.type).methods.single { it.signature() == method.signature() }))
            }
            assertFlag(context, "repairFirebasePush", true)
            assertFlag(context, "firebaseCertificateHeader", true)
        }
    }

    @Test
    fun `changed header sink URL factory or request anchors refuse before modifying any code or status`() {
        for (build in Fixtures.declaredBuilds()) for (change in listOf("key", "sink", "value", "return", "url", "create", "token", "bypass", "apiKey", "apiValue", "apiConnection", "apiBypass")) {
            val hosts = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + hosts)
            val header = context.resolveFirebaseHeader()
            val method = header.method
            when (change) {
                "key" -> method.replaceInstruction(header.index - 1, "const-string v0, \"X-Unrelated-Cert\"")
                "sink" -> method.replaceInstruction(header.index, "invoke-virtual {v10, v3, v0}, " +
                    "Ljava/net/URLConnection;->addRequestProperty(Ljava/lang/String;Ljava/lang/String;)V")
                "value" -> method.replaceInstruction(header.index, "invoke-virtual {v10, v0, v2}, " +
                    "Ljava/net/URLConnection;->addRequestProperty(Ljava/lang/String;Ljava/lang/String;)V")
                "return" -> method.replaceInstruction(header.index + 3, "return-object v3")
                "url" -> {
                    val factory = context.mutableClassDefBy(method.definingClass).methods.single {
                        "https://firebaseinstallations.googleapis.com/v1/" in it.strings()
                    }
                    factory.replaceInstruction(0, "const-string v0, \"https://unrelated.invalid/\"")
                }
                "create", "token" -> {
                    val request = header.requests.getValue(if (change == "create") FirebaseRequest.CREATE else FirebaseRequest.TOKEN)
                    val mutable = context.mutableClassDefBy(request.definingClass).methods.single { it.signature() == request.signature() }
                    val anchor = if (change == "create") "x-goog-fis-android-iid-migration-auth" else "/authTokens:generate"
                    val index = mutable.instructions().indexOfFirst { it.string() == anchor }
                    mutable.replaceInstruction(index, "const-string v${mutable.instructions()[index].namedRegisters().single()}, \"changed-request\"")
                }
                "bypass" -> {
                    // A direct entry edge past the literal must be refused, even though all strings still match.
                    method.addInstructionsWithLabels(0, "goto/32 :bypass_certificate_key",
                        ExternalLabel("bypass_certificate_key", method.getInstruction(header.index)))
                }
                "apiKey" -> {
                    val register = method.instructions()[header.index + 1].namedRegisters().single()
                    method.replaceInstruction(header.index + 1, "const-string v$register, \"x-unrelated-key\"")
                }
                "apiValue", "apiConnection" -> {
                    val index = header.index + 2
                    val instruction = method.instructions()[index]
                    val registers = instruction.namedRegisters().toMutableList()
                    if (change == "apiValue") registers[2] = registers[1] else registers[0] = registers[1]
                    method.replaceInstruction(index, "invoke-virtual {${registers.joinToString { "v$it" }}}, ${instruction.call()}")
                }
                "apiBypass" -> method.addInstructionsWithLabels(0, "goto/32 :bypass_api_key",
                    ExternalLabel("bypass_api_key", method.getInstruction(header.index + 2)))
            }
            val before = snapshot(context)
            val failure = assertThrows(PatchException::class.java) { repairFirebasePushPatch.execute(context) }
            assertTrue("${build.name}: $change refuses before editing", failure.message.orEmpty().contains("before editing"))
            assertEquals("${build.name}: $change keeps all bytecode and build facts", before, snapshot(context))
            assertFlag(context, "repairFirebasePush", false)
            assertFlag(context, "firebaseCertificateHeader", false)
        }
    }

    @Test
    fun `ambiguous header owners and an inaccessible runtime hook refuse without partial mutation`() {
        for (build in Fixtures.declaredBuilds()) {
            val hosts = hosts(build)
            val client = hosts.single { it.methods.any { method -> "X-Android-Cert" in method.strings() } }
            val duplicate = ImmutableClassDef("Ltest/OtherFirebaseClient;", client.accessFlags, client.superclass,
                client.interfaces, client.sourceFile, client.annotations, client.fields, client.methods.map { method ->
                    ImmutableMethod("Ltest/OtherFirebaseClient;", method.name, method.parameters, method.returnType,
                        method.accessFlags, method.annotations, method.hiddenApiRestrictions, method.implementation)
                })
            val ambiguous = PatchContexts.of(ExtensionDex.classes() + hosts + duplicate)
            val before = snapshot(ambiguous)
            assertThrows(PatchException::class.java) { repairFirebasePushPatch.execute(ambiguous) }
            assertEquals(before, snapshot(ambiguous))
            val inaccessible = PatchContexts.of(ExtensionDex.classes() + hosts)
            val hook = inaccessible.mutableClassDefBy(FIREBASE_PUSH).methods.single { it.name == "certificateHeader" }
            hook.accessFlags = hook.accessFlags and AccessFlags.PUBLIC.value.inv()
            val badShape = snapshot(inaccessible)
            assertThrows(PatchException::class.java) { repairFirebasePushPatch.execute(inaccessible) }
            assertEquals(badShape, snapshot(inaccessible))
        }
    }

    private fun hosts(build: File): List<ClassDef> = FixtureDex.classesWhere(build, { dex ->
        dex.stringSection.any { it in setOf("X-Android-Cert", "/authTokens:generate") }
    }) { method -> method.strings().any { it in setOf("X-Android-Cert", "/authTokens:generate") } }
    private fun snapshot(context: BytecodePatchContext): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        context.classDefForEach { result[it.type] = snapshot(context.mutableClassDefBy(it.type)) }
        return result
    }
    private fun snapshot(owner: ClassDef) = listOf(owner.accessFlags.toString()) + owner.methods.sortedBy { it.signature() }.map(::snapshot)
    private fun snapshot(method: Method): String {
        val flow = method.implementation?.let { ControlFlow.of(method) }
        return listOf(method.signature(), method.accessFlags, method.implementation?.registerCount,
            method.instructions().map(::operation), flow?.normal?.toList(), flow?.exceptional?.toList()).joinToString("|")
    }
    private fun operation(instruction: Instruction) = listOf(instruction.opcode.name, instruction.reference(),
        instruction.namedRegisters(), (instruction as? NarrowLiteralInstruction)?.narrowLiteral).joinToString("|")
    private fun assertFlag(context: BytecodePatchContext, name: String, enabled: Boolean) {
        val body = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions()
        assertEquals(Opcode.CONST_4, body[0].opcode)
        assertEquals(if (enabled) 1 else 0, (body[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[1].opcode)
    }
    private fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.strings() = instructions().mapNotNull { it.string() }.toSet()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
}
