package dev.octoshrimpy.quik.migration

import timber.log.Timber
import java.io.File

/**
 * Removes the old Realm database files (#44) once 2.8.0 imported their data into Room. Kept until
 * then, so a phone that never ran the import still has them.
 */
object RealmFilesCleanup {

    private val names = setOf("default.realm", "default.realm.lock", "default.realm.note", "default.realm.management", ".realm.temp")

    fun deleteIfImported(filesDir: File, imported: Boolean) {
        if (!imported) return
        filesDir.listFiles { file -> file.name in names }?.forEach { file ->
            // delete() for anything but a folder: deleteRecursively() skips files that are neither
            // regular files nor folders, such as Realm's named pipe, while still returning true
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            Timber.i("Old Realm file ${file.name}: deleted=$deleted")
        }
    }
}
