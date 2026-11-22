package dev.octoshrimpy.quik

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Coroutines stop by throwing CancellationException. A `catch (e: Exception)` around a suspending
 * call (withContext) also catches that, and then runs its error path when the screen merely went
 * away: a valid backup was reported as unreadable after a rotation (#34 final review). Such a catch
 * must be preceded by `catch (e: CancellationException) { throw e }`.
 */
class NoSwallowedCancellationTest {

    @Test
    fun catchAroundWithContextRethrowsCancellation() {
        val catchAll = Regex("""catch \(\w+: (Exception|Throwable)\)""")
        val offenders = listOf("presentation", "data", "domain")
            .map { module -> File("../$module/src/main/java") }
            .flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                val text = file.readText()
                catchAll.findAll(text).mapNotNull { match ->
                    val tryStart = text.lastIndexOf("try {", match.range.first)
                    if (tryStart < 0) return@mapNotNull null
                    val guarded = text.substring(tryStart, match.range.first)
                    val suspends = "withContext(" in guarded
                    val rethrows = "CancellationException" in guarded
                    if (suspends && !rethrows) "${file.name}:${text.substring(0, match.range.first).count { it == '\n' } + 1}" else null
                }.toList()
            }
            .sorted()
        assertEquals("rethrow CancellationException before catching Exception", emptyList<String>(), offenders)
    }
}
