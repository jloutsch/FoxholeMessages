package dev.octoshrimpy.quik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds are shrunk and renamed by R8, and unit tests run on un-renamed code, so they can't
 * see the failures that causes. Every one of this app's release-only outages came from a keep rule
 * being removed for something used by reflection. This test fails if one of those rules disappears,
 * and says what breaks without it. (The pull-request CI also builds the shrunk release.)
 */
class ProguardRulesTest {

    private val rules = File("proguard-rules.pro").readText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")

    private val required = mapOf(
        "-keep class * extends androidx.work.Worker {\n<init>(android.content.Context, androidx.work.WorkerParameters);\n}" to
            "WorkManager re-creates queued work by the class name it saved; renamed Workers orphan scheduled work",
        "-keep class kotlin.Metadata { *; }" to
            "without it Moshi silently falls back to field adapters and ignores @Json names (changelog, backups)",
        "-keep class dev.octoshrimpy.quik.manager.ChangelogManagerImpl\$Changeset { *; }" to
            "the what's-new dialog comes up empty",
        "-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl\$Backup { *; }" to
            "backups are written with renamed keys that no other version can restore",
        "-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl\$BackupMetadata { *; }" to
            "the restore screen can't read a backup's message count",
        "-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl\$BackupMessage { *; }" to
            "backups are written with renamed keys that no other version can restore",
        "-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl\$BackupPart { *; }" to
            "MMS attachments in backups are written with renamed keys",
        "-keepclassmembers class com.github.chrisbanes.photoview.PhotoViewAttacher {" to
            "the picture viewer sets PhotoView's zoom limits by reflection"
    )

    @Test
    fun reflectionKeepRulesArePresent() {
        val missing = required.filterKeys { rule -> !rules.contains(rule) }
        assertTrue(
            "Keep rules missing from proguard-rules.pro:\n" +
                missing.entries.joinToString("\n") { (rule, why) -> "  $rule\n    -> without it, $why" },
            missing.isEmpty()
        )
    }

    @Test
    fun obfuscationIsNotTurnedOffWholesale() {
        // These two made release builds essentially unobfuscated (Play's score: 1%).
        assertFalse("-dontobfuscate is back", rules.lines().any { it.startsWith("-dontobfuscate") })
        assertFalse("a blanket keep of the whole app is back", rules.contains("-keep class dev.octoshrimpy.quik.** { *; }"))
    }
}
