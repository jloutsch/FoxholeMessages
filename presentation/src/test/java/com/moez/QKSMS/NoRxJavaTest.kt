package dev.octoshrimpy.quik

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app moved from RxJava to Kotlin Flow and coroutines (#34) and no longer ships RxJava. A
 * file importing it again would fail to compile anyway once the libraries are gone; this names
 * the file instead of leaving a wall of unresolved references.
 */
class NoRxJavaTest {

    @Test
    fun noFileImportsRxJava() {
        val rx = Regex("""^import (io\.reactivex|com\.uber\.autodispose|com\.uber\.rxdogtag|com\.jakewharton\.rxbinding|com\.jakewharton\.rxrelay|kotlinx\.coroutines\.rx2)""", RegexOption.MULTILINE)
        val offenders = listOf("common", "domain", "data", "presentation", "android-smsmms")
            .map { module -> File("../$module/src") }
            // Every app source set (main, debug, fdroid, ...), not the tests
            .flatMap { root -> root.walk().onEnter { it.name != "test" && it.name != "androidTest" }
                .filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
            .filter { file -> rx.containsMatchIn(file.readText()) }
            .map { file -> file.path.removePrefix("../") }
            .sorted()
        assertEquals("files still importing RxJava", emptyList<String>(), offenders)
    }
}
