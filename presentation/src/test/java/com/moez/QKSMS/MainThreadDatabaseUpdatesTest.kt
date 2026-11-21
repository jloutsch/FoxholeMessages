package dev.octoshrimpy.quik

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Live database lists from Room send their updates on a background thread; Realm sent them on the
 * main thread, and screens were written for that. 2.8.0–2.9.1 crashed in group threads because the
 * conversation theme tinted toolbar icons from a background thread. Screens must turn a database
 * Flow into Rx with asMainObservable(), which delivers on the main thread, never asObservable().
 */
class MainThreadDatabaseUpdatesTest {

    @Test
    fun screensNeverUsePlainAsObservableOnDatabaseFlows() {
        val helper = "FlowExtensions.kt"
        val offenders = File("src/main/java").walk()
            .filter { it.isFile && it.extension == "kt" && it.name != helper }
            .filter { it.readText().contains("import kotlinx.coroutines.rx2.asObservable") }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals("use asMainObservable() instead of kotlinx asObservable()", emptyList<String>(), offenders)
    }

    @Test
    fun dataStreamsForScreensDeliverOnTheMainThread() {
        listOf(
            "../data/src/main/java/com/moez/QKSMS/repository/ConversationRepositoryImpl.kt",
            "../data/src/main/java/com/moez/QKSMS/repository/ContactRepositoryImpl.kt",
        ).forEach { path ->
            val code = File(path).readText()
            val plain = Regex("""\.asObservable\(\)(?!\s*\.observeOn\(AndroidSchedulers\.mainThread\(\)\))""").findAll(code).count()
            assertEquals("$path: every asObservable() must be followed by observeOn(AndroidSchedulers.mainThread())", 0, plain)
        }
    }
}
