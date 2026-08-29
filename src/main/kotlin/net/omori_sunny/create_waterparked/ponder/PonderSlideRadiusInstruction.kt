package net.omori_sunny.create_waterparked.ponder

import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.instruction.TickingInstruction
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import kotlin.math.min

/**
 * Smoothly eases the tube radius of an anchor from its current value to
 * `target` over `ticks` ticks (smoothstep, zero velocity at both ends), then
 * leaves it there. Used by the storyboard for the "set opening to 1.5m" beat.
 */
class PonderSlideRadiusInstruction(
    private val anchors: List<WaterslideAnchorBlockEntity>,
    private val targetRadius: Float,
    ticks: Int, // = 60-ish in the storyboard
    private val startTicks: Int
) : TickingInstruction(false, ticks + startTicks) {

    private val startRadii: MutableList<Float> = mutableListOf()

    override fun firstTick(scene: PonderScene) {
        startRadii.clear()
        for (a in anchors) startRadii.add(a.radius)
    }

    override fun tick(scene: PonderScene) {
        super.tick(scene)
        val elapsed = totalTicks - remainingTicks
        val active = elapsed - startTicks
        if (active <= 0) return
        val t = (active.toDouble() / (totalTicks - startTicks).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val ease = t * t * (3.0 - 2.0 * t) // smoothstep
        for ((i, a) in anchors.withIndex()) {
            val from = if (i < startRadii.size) startRadii[i] else a.radius
            val current = from + (targetRadius - from) * ease.toFloat()
            if (Math.abs(current - a.radius) > 0.0005f) {
                a.setRadius(current)
                // the BER signature holds the radius, so its cache rebuilds automatically
            }
        }
    }
}
