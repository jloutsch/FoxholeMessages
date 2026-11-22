package dev.octoshrimpy.quik

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Live database lists from Room send their updates on a background thread; Realm sent them on the
 * main thread, and screens were written for that. 2.8.0–2.9.1 crashed in group threads because the
 * conversation theme tinted toolbar icons from a background thread.
 *
 * On Flow (#34) a collector runs in its scope's dispatcher, so a database Flow collected in
 * presenterScope, viewModelScope or a view's bindScope (all main-thread scopes) delivers on the
 * main thread. A collector placed in a background scope would run screen code off the main thread.
 */
class MainThreadDatabaseUpdatesTest {

    @Test
    fun screensNeverCollectInABackgroundScope() {
        val background = Regex("""(collectIn|launchIn)\([^)]*Dispatchers\.(IO|Default)|CoroutineScope\([^)]*Dispatchers\.(IO|Default)""")
        val offenders = File("src/main/java").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line -> "${file.name}:${index + 1}".takeIf { background.containsMatchIn(line) } } }
            .sorted()
            .toList()
        assertEquals("collect in a main-thread scope; move only the blocking work to Dispatchers.IO with withContext", emptyList<String>(), offenders)
    }

    @Test
    fun screenBasesRenderOnTheMainThread() {
        listOf("src/main/java/com/moez/QKSMS/common/base/QkViewModel.kt", "src/main/java/com/moez/QKSMS/common/base/QkPresenter.kt")
            .forEach { path ->
                assertEquals("$path must render with launch(Dispatchers.Main)", true,
                    File(path).readText().contains("launch(Dispatchers.Main) { state.collect(view::render) }"))
            }
    }
}
