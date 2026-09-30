package net.omori_sunny.create_waterparked.content.roller

import net.omori_sunny.create_waterparked.config.ModConfig

// carries a landing velocity from the deck's entity hook into the item stack Create builds for it
object RollerMomentum {

    const val NO_LANDING: Float = -1f

    // how fast a landing may hand a load over, in blocks per tick: the one ceiling a whole deck reads
    fun speedLimit(): Float = ModConfig.rollerDeckSpeedLimit()

    // how fast a client walks a load to the position a keyframe gave it: a correction is a slide, never a jump
    fun correctionSpeed(): Float = ModConfig.rollerDeckCorrectionSpeed()

    private val pending: ThreadLocal<Float?> = ThreadLocal()

    @JvmStatic
    fun hold(speed: Float) {
        val limit = speedLimit()
        pending.set(speed.coerceIn(-limit, limit))
    }

    @JvmStatic
    fun clear() {
        pending.set(null)
    }

    @JvmStatic
    fun release(): Float {
        val speed = pending.get()
        pending.set(null)
        return speed ?: NO_LANDING
    }
}
