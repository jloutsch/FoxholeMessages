package dev.octoshrimpy.quik.db

import androidx.room.TypeConverter
import org.json.JSONArray

/** Lists of strings (scheduled-message recipients and attachments) stored as a JSON array. */
object StringListConverter {

    @TypeConverter
    @JvmStatic
    fun fromList(list: List<String>?): String? = list?.let { JSONArray(it).toString() }

    @TypeConverter
    @JvmStatic
    fun toList(json: String?): List<String>? = json?.let {
        val array = JSONArray(it)
        List(array.length()) { index -> array.getString(index) }
    }
}
