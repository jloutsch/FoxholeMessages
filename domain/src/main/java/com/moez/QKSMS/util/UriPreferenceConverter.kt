package dev.octoshrimpy.quik.util

import android.net.Uri

class UriPreferenceConverter : Preference.Converter<Uri> {

    override fun deserialize(serialized: String): Uri {
        return Uri.parse(serialized)
    }

    override fun serialize(value: Uri): String {
        return value.toString()
    }

}
