package app.morphe.util.injection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generated methods with a hook injected the way the patches inject theirs, run against oracles
 * that never look at the injected code: the statement tree evaluated directly, and the original
 * method run with the hook's effect played out at its instruction. The borrowed register is
 * poisoned once the hook is done with it, so a later read fails even where the value it held
 * happens to match.
 *
 * A failing seed is printed, shrunk, and written to build/injection-regressions. Copy the file
 * into src/test/resources/injection-regressions to keep it. HUSHTELEGRAM_INJECTION_SEED replays
 * one seed, and HUSHTELEGRAM_INJECTION_SEEDS runs more than the default batch.
 */
class SeededInjectionTest {
    private val scratch = File("build/tmp/seeded-injection").apply { mkdirs() }.resolve("check.dex")

    private fun seeds(): List<Long> {
        System.getenv("HUSHTELEGRAM_INJECTION_SEED")?.let { return listOf(it.trim().toLong()) }
        val count = System.getenv("HUSHTELEGRAM_INJECTION_SEEDS")?.trim()?.toInt() ?: 1000
        return (0 until count).map { BASE + it }
    }

    @Test
    fun `generated hooks keep every path the method had`() {
        val seeds = seeds()
        val failures = mutableListOf<String>()
        val injected = mutableListOf<Generated>()
        var comparedOn = 0
        for (seed in seeds) {
            val generated = Generated.of(seed)
            val ran = runCase(generated.case(), Chooser.REAL, scratch)
            ran.failure?.let { failures += report(generated, it, Chooser.REAL) }
            if (failures.size == 3) break
            if (!ran.refused) injected += generated
            if (ran.comparedOn) comparedOn++
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
        if (seeds.size < 400) return

        // The batch has to keep reaching every shape it exists for.
        fun count(what: (Stmt) -> Boolean) = injected.count { generated -> generated.program.body.any { it.has(what) } }
        val reached = mapOf(
            "loops" to count { it is Loop },
            "switches" to count { it is Switch },
            "try blocks" to count { it is Guarded },
            "handlers reading the exception" to count { it is Guarded && it.exception != null },
            "wide registers" to count { it is ConstWide || it is Widen },
            "two-register branches" to count { it is Branch && it.b != null },
            "jumps" to injected.count { it.mode == Mode.SKIP },
            "observed answers" to injected.count { it.mode == Mode.OBSERVE },
            "early returns" to injected.count { it.mode == Mode.RETURN },
            "guard answers compared" to comparedOn,
        )
        println("${injected.size} of ${seeds.size} seeded cases injected: $reached")
        val thin = reached.filterValues { it < 20 }
        assertTrue("too few injected cases with $thin of ${injected.size}: $reached", thin.isEmpty())
    }

    @Test
    fun `a register choice that forgets a read is caught`() {
        val retain = System.getenv("HUSHTELEGRAM_INJECTION_RETAIN")?.let(::File)
        for ((chooser, budget) in listOf(Chooser.RECKLESS to 200, Chooser.IGNORES_TARGETS to 600, Chooser.WIDE_BLIND to 1500)) {
            val caught = (0 until budget).asSequence().map { CONTROL_BASE + it }.firstNotNullOfOrNull { seed ->
                val generated = Generated.of(seed)
                runCase(generated.case(), chooser, scratch).failure?.let { generated to it }
            }
            assertNotNull("$chooser got through $budget seeded cases", caught)
            if (retain != null) {
                val (generated, failure) = caught!!
                val small = minimize(generated) { runCase(it.case(), chooser, scratch).failure }
                val name = "${chooser.name.lowercase().replace('_', '-')}-seed-${generated.seed}"
                retain.mkdirs()
                File(retain, "$name.case").writeText(
                    small.case().retained("Shrunk from seed ${generated.seed}, which $chooser got wrong:\n$failure", chooser),
                )
            }
        }
    }

    @Test
    fun `retained cases pass and still catch the choice they were kept for`() {
        val retained = retainedCases()
        assertTrue("no retained cases under $REGRESSIONS", retained.size >= 3)
        for ((case, control) in retained) {
            val ran = runCase(case, Chooser.REAL, scratch)
            assertNull(case.name, ran.failure)
            assertFalse("${case.name}: freeLocalsAt found no register", ran.refused)
            control?.let { assertNotNull("${case.name} no longer catches $it", runCase(case, it, scratch).failure) }
        }
    }

    @Test
    fun `the device corpus runs the same here after a round trip through dex`() {
        val cases = retainedCases().map { it.first } + seeds().asSequence()
            .map { Generated.of(it).case() }
            .filter { !runCase(it, Chooser.REAL, scratch).refused }
            .take(CORPUS_GENERATED)
            .toList()
        val corpus = cases.map { case -> case to inject(case, assemble(hostClass(HOST, case.method)).methods.single(), Chooser.REAL).method }
        val expected = InjectionCorpus.write(corpus, File("build/injection-corpus"))
        assertEquals(expected.size, expected.map { it.substringBefore('=') }.toSet().size)
        assertTrue("the corpus has only ${expected.size} runs", expected.size >= 100)
    }

    /** Shrinks a failure, writes it where it can be kept, and says how to replay it. */
    private fun report(generated: Generated, failure: String, chooser: Chooser): String {
        val small = minimize(generated) { runCase(it.case(), chooser, scratch).failure }
        val file = File("build/injection-regressions/seed-${generated.seed}.case").absoluteFile
        file.parentFile.mkdirs()
        val case = small.case()
        file.writeText(case.retained("Shrunk from seed ${generated.seed}:\n$failure", null))
        return "seed ${generated.seed} (${generated.mode.name.lowercase()}): $failure\n" +
            "Replay it with HUSHTELEGRAM_INJECTION_SEED=${generated.seed}. Shrunk to $file:\n${case.method}"
    }

    private fun retainedCases(): List<Pair<Case, Chooser?>> {
        val directory = javaClass.getResource("/$REGRESSIONS")?.toURI()?.let(::File) ?: return emptyList()
        return directory.listFiles { file -> file.name.endsWith(".case") }!!.sortedBy { it.name }
            .map { readRetained(it.nameWithoutExtension, it.readText()) }
    }

    private fun Stmt.has(what: (Stmt) -> Boolean): Boolean = what(this) || children().flatten().any { it.has(what) }

    private companion object {
        const val BASE = 20_261_002_000L
        const val CONTROL_BASE = 20_261_002_900_000L
        const val CORPUS_GENERATED = 24
        const val REGRESSIONS = "injection-regressions"
    }
}
