package dev.octoshrimpy.quik

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.octoshrimpy.quik.manager.ChangelogManagerImpl.Changeset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cutting a release means bumping the version in presentation/build.gradle and updating the
 * changelogs to match (see CLAUDE.md, "Cutting a release"). Each of these has gone wrong at least
 * once, and none of them is caught by building the app:
 *  - a 2-part versionName ("1.0") broke the release-notes workflow, which needs strict X.Y.Z;
 *  - changelog.json is read reflectively, and when that broke, the "what's new" dialog came up empty;
 *  - Play rejects release notes over 500 characters.
 */
class ReleaseConsistencyTest {

    private val gradle = File("build.gradle").readText()
    private val versionCode = Regex("""^\s*versionCode\s+(\d+)""", RegexOption.MULTILINE).find(gradle)!!.groupValues[1].toInt()
    private val versionName = Regex("""^\s*versionName\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE).find(gradle)!!.groupValues[1]

    // Same configuration as AppModule.provideMoshi, which ChangelogManagerImpl uses.
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val changelog: List<Changeset> by lazy {
        val type = Types.newParameterizedType(List::class.java, Changeset::class.java)
        moshi.adapter<List<Changeset>>(type).fromJson(File("../data/src/main/assets/changelog.json").readText())!!
    }

    @Test
    fun versionNameIsStrictSemver() =
        assertTrue("versionName '$versionName' must be X.Y.Z (generate-release-notes.yml parses it with semver)",
            versionName.matches(Regex("""\d+\.\d+\.\d+""")))

    @Test
    fun changelogParsesAndIsNewestFirst() {
        assertTrue("changelog.json has no entries", changelog.isNotEmpty())
        val codes = changelog.map { it.versionCode }
        assertEquals("changelog.json must be newest first with no repeated versionCodes", codes.sortedDescending().distinct(), codes)
    }

    @Test
    fun newestChangelogEntryIsThisVersion() {
        val newest = changelog.first()
        assertEquals("newest changelog.json entry must be this versionName", versionName, newest.versionName)
        // A Play re-upload can bump versionCode alone (CLAUDE.md, "versionCode collisions"), so the
        // entry's code may trail the build's, but never lead it.
        assertTrue("changelog.json's newest versionCode ${newest.versionCode} is ahead of build.gradle's $versionCode",
            newest.versionCode <= versionCode)
        val items = listOfNotNull(newest.added, newest.improved, newest.removed, newest.fixed).flatten()
        assertTrue("newest changelog.json entry lists no changes - the what's-new dialog would be empty",
            items.any { it.isNotBlank() })
    }

    @Test
    fun playReleaseNotesExistAndFit() {
        val notes = File("../metadata/en-US/changelogs/$versionCode.txt")
        assertTrue("missing ${notes.path} (Play release notes for versionCode $versionCode)", notes.exists())
        val length = notes.readText().trim().codePointCount(0, notes.readText().trim().length)
        assertTrue("${notes.path} is $length characters; Play's limit is 500", length <= 500)
    }

    @Test
    fun fdroidMetadataMatches() {
        val meta = File("../fdroid/metadata/com.foxhole.messages.yml").readText()
        assertTrue("F-Droid CurrentVersion should be $versionName", meta.contains("CurrentVersion: \"$versionName\""))
        assertTrue("F-Droid CurrentVersionCode should be $versionCode", meta.contains("CurrentVersionCode: $versionCode"))
    }
}
