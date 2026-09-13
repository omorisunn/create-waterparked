package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.nbt.CompoundTag

// red wrench handles share their ranges across attachment types
object SlideHandleDistance {
    const val MIN_DIST = 0.5f
    const val MAX_DIST = 5.0f
    const val DEFAULT_DIST = 1.2f

    const val MIN_T = 0.02f
    const val MAX_T = 0.98f

    fun read(data: CompoundTag, tag: String): Float =
        data.getFloat(tag).let {
            if (it <= 0f) DEFAULT_DIST else it.coerceIn(MIN_DIST, MAX_DIST)
        }
}
