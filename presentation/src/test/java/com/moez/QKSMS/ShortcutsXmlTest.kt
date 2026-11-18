package dev.octoshrimpy.quik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Compose" launcher shortcut (res/xml/shortcuts.xml) names the installed app id, which differs
 * per build type. It named upstream QUIK's id for years, so the shortcut opened nothing.
 */
class ShortcutsXmlTest {

    private val gradle = File("build.gradle").readText()
    private val applicationId = Regex("""applicationId\s+['"]([^'"]+)['"]""").find(gradle)!!.groupValues[1]

    private fun suffixOf(buildType: String): String {
        val block = Regex("""(?s)\n\s*$buildType\s*\{(.*?)\n\s*}""").findAll(gradle).map { it.groupValues[1] }
            .firstOrNull { it.contains("applicationIdSuffix") } ?: return ""
        return Regex("""applicationIdSuffix\s+['"]([^'"]+)['"]""").find(block)!!.groupValues[1]
    }

    private fun shortcutsFor(sourceSet: String) = File("src/$sourceSet/res/xml/shortcuts.xml").readText()

    @Test
    fun eachBuildTypeNamesItsOwnInstalledApp() {
        for ((sourceSet, buildType) in listOf("main" to "release", "debug" to "debug", "fdroid" to "fdroid")) {
            val target = Regex("""targetPackage="([^"]+)"""").find(shortcutsFor(sourceSet))!!.groupValues[1]
            assertEquals("$sourceSet shortcuts.xml targetPackage", applicationId + suffixOf(buildType), target)
        }
    }

    @Test
    fun targetClassExists() {
        for (sourceSet in listOf("main", "debug", "fdroid")) {
            val cls = Regex("""targetClass="([^"]+)"""").find(shortcutsFor(sourceSet))!!.groupValues[1]
            assertTrue("$sourceSet shortcuts.xml targetClass $cls", runCatching { Class.forName(cls, false, javaClass.classLoader) }.isSuccess)
        }
    }
}
