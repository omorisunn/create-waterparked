package net.omori_sunny.create_waterparked.content.attachment.door

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachment
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType

// the first slide attachment: a mechanical door across the tube, driven by a
// shaft like an Aeronautics torsion spring. The openness is a continuous
// [0,1] value: clockwise rotation winds it open, counter-clockwise closes it,
// and it stops at either end. Riders are held while the opening is below the
// pass threshold.
class MechanicalDoorAttachment(
    type: SlideAttachmentType,
    entry: SlideAttachmentEntry
) : SlideAttachment(type, entry) {

    companion object {
        private const val TAG_OPEN = "DoorOpenF"

        // riders pass once the opening exceeds this fraction
        private const val PASS_THRESHOLD = 0.7f

        // full swing per 40 ticks at reference speed 16 RPM
        private const val OPEN_PER_TICK_AT_REF = 1.0f / 40f
        private const val REF_SPEED = 16f
    }

    /** continuous opening, 0 = closed, 1 = fully open */
    private var open = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            data.putFloat(TAG_OPEN, field)
        }

    private var lastRiderSeen = 0L

    init {
        open = data.getFloat(TAG_OPEN).coerceIn(0f, 1f)
    }

    /** the hub is driven when ANY adjacent kinetic block spins */
    private fun drivenSpeed(level: ServerLevel, sab: SlideAttachmentBlockEntity): Float {
        val own = sab as? com.simibubi.create.content.kinetics.base.KineticBlockEntity
        if (own != null && kotlin.math.abs(own.speed) > 1.0E-3f) return own.speed
        for (dir in net.minecraft.core.Direction.entries) {
            val n = level.getBlockEntity(sab.blockPos.relative(dir))
            if (n is com.simibubi.create.content.kinetics.base.KineticBlockEntity &&
                kotlin.math.abs(n.speed) > 1.0E-3f
            ) return n.speed
        }
        return 0f
    }

    override fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {
        val speed = drivenSpeed(level, sab)
        if (speed == 0f) return
        // wind open on clockwise, close on counter-clockwise; stop at the ends
        val before = open
        val delta = (speed / REF_SPEED) * OPEN_PER_TICK_AT_REF
        open = (open + delta).coerceIn(0f, 1f)
        if (open != before) sync(sab)
    }

    override fun onTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity?) {
        lastRiderSeen = level.gameTime
    }

    override fun speedScaleAt(distance: Double): Double? {
        if (open >= PASS_THRESHOLD) return null
        // hard stop zone: inside 1.2 blocks the demand is EXACTLY zero - a
        // positive ramp here would let riders creep through the closed door
        if (distance <= 1.2) return 0.0
        val x = ((distance - 1.2) / (5.0 - 1.2)).coerceIn(0.0, 1.0)
        // OutCubic braking: strong deceleration first, gentle settle at the stop
        return x * x * x
    }
}
