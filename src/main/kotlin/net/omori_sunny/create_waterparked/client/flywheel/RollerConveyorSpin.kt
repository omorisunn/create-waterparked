package net.omori_sunny.create_waterparked.client.flywheel

import com.simibubi.create.content.kinetics.base.RotatingInstance
import com.simibubi.create.foundation.utility.ServerSpeedProvider
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerDeckFace
import net.omori_sunny.create_waterparked.content.roller.RollerDeckGravity
import net.omori_sunny.create_waterparked.content.roller.RollerMomentum
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp

// one easing state per roller, since each roller rolls at the speed of the load resting on it
internal object RollerConveyorSpin {

    private const val EASE_TICKS = 4f
    private const val TICKS_PER_SECOND = 20f
    private const val EASE_SECONDS = EASE_TICKS / TICKS_PER_SECOND
    private const val MAX_STEP_SECONDS = 0.25f
    private const val SPEED_EPSILON = 0.05f
    private const val PHASE_EPSILON = 0.05
    private const val IDLE_SECONDS = 10f
    private const val SWEEP_SECONDS = 5f
    private const val REACH = 1f
    private const val FALLOFF = 0.75f
    private val decks = ConcurrentHashMap<Long, Deck>()
    @Volatile
    private var nextSweep = Float.NaN

    // shared between the flywheel visual and the plain renderer fallback, so both draw one layout and one speed
    internal const val ROLLER_COUNT = 6
    internal const val MIN_ROLLER_OFFSET = 4f / 3f
    internal const val ROLLER_PITCH = 8f / 3f
    internal const val BLOCK_LENGTH = 16.0
    internal const val ROLLER_CENTRE_Y = 2f / 16f
    internal const val ROLL_SPEED_PER_BLOCK =
        20f * (16f / 1.4f) * (180f / Math.PI.toFloat()) / RotatingInstance.SPEED_MULTIPLIER

    internal fun rollerSlice(slot: Int): Float =
        (1.0 - (MIN_ROLLER_OFFSET + slot * ROLLER_PITCH) / BLOCK_LENGTH).toFloat()

    // the load part of a roller's target speed, before riders and before the render multiplier: the two faces of
    // one roller move opposite ways, so a load under the deck turns it the other way round
    internal fun loadSpeed(be: RollerConveyorBlockEntity, slot: Int): Float {
        val inventory = be.inventory ?: return 0f
        val slice = rollerSlice(slot)
        val gravity = RollerDeckGravity.localGravity(be.plotPose())
        val sliding = RollerDeckGravity.accelerationAlong(gravity, be.deckFacing) != 0f
        val laneSpeeds = FloatArray(RollerDeckFace.entries.size)
        for (transported in inventory.transportedItems) {
            val gap = abs(transported.beltPosition - be.index - slice)
            if (gap >= REACH) continue
            val face = RollerDeckFace.ofLane((transported as RollerMomentumAccess).`waterparked$deckLane`())
            val landing = (transported as RollerMomentumAccess).`waterparked$rollerSpeed`()
            val carried = if (landing == RollerMomentum.NO_LANDING) 0f
            else landing.coerceIn(-RollerMomentum.speedLimit(), RollerMomentum.speedLimit())
            val item = be.loadTravelSpeed(carried, sliding) * face.lift.toFloat() *
                ServerSpeedProvider.get() * (1f - FALLOFF * gap)
            if (abs(item) > abs(laneSpeeds[face.ordinal])) laneSpeeds[face.ordinal] = item
        }
        return laneSpeeds.sum()
    }

    class Spin(val speed: Float, val offset: Float)

    private class State {
        @Volatile
        var spin = Spin(0f, 0f)
        var angle = 0.0
        var time = Float.NaN
        var raw = 0.0
    }

    private class Deck {
        val states = Array(ROLLER_COUNT) { State() }
        @Volatile
        var lastUsed = 0f
    }

    // the shader draws a roller at "offset + renderSeconds * speed", so the offset is rewritten to keep that angle equal to the integral of the eased speed
    fun advance(key: Long, slot: Int, target: Float, renderSeconds: Float): Spin {
        val deck = decks.computeIfAbsent(key) { Deck() }
        deck.lastUsed = renderSeconds
        sweep(renderSeconds)
        val state = deck.states[slot]
        synchronized(state) {
            if (state.time.isNaN()) {
                state.time = renderSeconds
                state.raw = -renderSeconds.toDouble() * state.spin.speed.toDouble()
                state.spin = Spin(state.spin.speed, wrap(state.raw))
                return state.spin
            }
            val dt = renderSeconds - state.time
            if (dt < 0f) {
                state.time = renderSeconds
                return state.spin
            }
            if (dt == 0f) return state.spin
            state.time = renderSeconds
            val previous = state.spin.speed
            val step = dt.coerceAtMost(MAX_STEP_SECONDS)
            var speed = previous + (target - previous) * (1f - exp(-step / EASE_SECONDS))
            if (abs(target - speed) < SPEED_EPSILON) speed = target
            state.angle += (previous + speed).toDouble() * 0.5 * step.toDouble()
            val raw = state.angle - renderSeconds.toDouble() * speed.toDouble()
            if (speed != previous || abs(raw - state.raw) > PHASE_EPSILON) {
                state.raw = raw
                state.spin = Spin(speed, wrap(raw))
            }
            return state.spin
        }
    }

    // a deck that has not been drawn for a while drops its easing states, so removed decks leave nothing behind
    private fun sweep(renderSeconds: Float) {
        if (!nextSweep.isNaN() && renderSeconds < nextSweep && nextSweep - renderSeconds < SWEEP_SECONDS * 2) return
        nextSweep = renderSeconds + SWEEP_SECONDS
        for ((key, deck) in decks) {
            if (renderSeconds - deck.lastUsed <= IDLE_SECONDS) continue
            decks.remove(key, deck)
        }
    }

    private fun wrap(degrees: Double): Float {
        val wrapped = degrees % 360.0
        return (if (wrapped < 0.0) wrapped + 360.0 else wrapped).toFloat()
    }
}
