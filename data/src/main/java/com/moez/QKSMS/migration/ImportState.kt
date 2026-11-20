package dev.octoshrimpy.quik.migration

/** Progress of the one-time copy of this app's own data from the old Realm file into Room (#44). */
sealed class ImportState {
    /** No old file, or it was already imported. */
    object NotNeeded : ImportState()
    object Running : ImportState()
    /** Each kind of data with how many the old file held and how many Room holds afterwards. */
    data class Done(val counts: Map<String, Pair<Long, Long>>) : ImportState()
    /** Nothing is marked done, so the next launch tries again. */
    data class Failed(val error: Throwable) : ImportState()
}
