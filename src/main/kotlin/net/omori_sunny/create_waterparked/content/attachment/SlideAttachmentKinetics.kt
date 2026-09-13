package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import kotlin.math.abs

// the shaft driving an attachment: its own network, or any adjacent kinetic block
object SlideAttachmentKinetics {

    private const val MIN_SPEED = 1.0E-3f

    fun drivenSpeed(level: Level, sab: SlideAttachmentBlockEntity): Float {
        val own = sab as? KineticBlockEntity
        if (own != null && abs(own.speed) > MIN_SPEED) return own.speed
        for (dir in Direction.entries) {
            val neighbour = level.getBlockEntity(sab.blockPos.relative(dir))
            if (neighbour is KineticBlockEntity && abs(neighbour.speed) > MIN_SPEED) return neighbour.speed
        }
        return 0f
    }
}
