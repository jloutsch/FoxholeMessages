package dev.octoshrimpy.quik.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
open class BlockedNumber(
        @PrimaryKey var id: Long = 0,

        var address: String = ""
)