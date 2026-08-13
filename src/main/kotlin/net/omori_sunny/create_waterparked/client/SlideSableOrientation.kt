package net.omori_sunny.create_waterparked.client

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import org.joml.Quaterniond
import org.joml.Quaterniondc
import java.util.UUID

// Sable custom entity orientation hook for slide rides.
@OnlyIn(Dist.CLIENT)
object SlideSableOrientation {

    private var entityId: UUID? = null
    private var roll = 0f
    private var axis: Vec3 = Vec3(0.0, 0.0, 1.0)

    @JvmStatic
    fun update(entity: Entity, rollDegrees: Float, axisWorld: Vec3) {
        entityId = entity.uuid
        roll = rollDegrees
        axis = axisWorld
    }

    @JvmStatic
    fun clear(entity: Entity) {
        if (entityId == entity.uuid) entityId = null
    }

    @JvmStatic
    fun clearAll() {
        entityId = null
    }

    @JvmStatic
    fun get(entity: Entity?, partialTicks: Float): Quaterniondc? {
        if (entity == null || entityId != entity.uuid) return null
        return Quaterniond().rotationAxis(
            Math.toRadians(roll.toDouble()), axis.x, axis.y, axis.z
        )
    }

    @JvmStatic
    fun has(entity: Entity?): Boolean = entity != null && entityId == entity.uuid
}
