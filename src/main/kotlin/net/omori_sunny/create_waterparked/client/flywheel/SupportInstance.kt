package net.omori_sunny.create_waterparked.client.flywheel

import dev.engine_room.flywheel.api.instance.InstanceHandle
import dev.engine_room.flywheel.api.instance.InstanceType
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

class SupportInstance(
    type: InstanceType<out SupportInstance>,
    handle: InstanceHandle
) : ColoredLitOverlayInstance(type, handle) {

    @JvmField
    val origin = Vector3f()

    @JvmField
    var fullTileMode = 0f

    @JvmField
    var spriteU0 = 0f

    @JvmField
    var spriteU1 = 1f

    @JvmField
    var spriteV0 = 0f

    @JvmField
    var spriteV1 = 1f

    @JvmField
    val boundCenter = Vector3f()

    @JvmField
    var boundRadius = 1f

    fun setOrigin(v: Vec3): SupportInstance {
        origin.set(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
        return this
    }

    fun setZeroOrigin(): SupportInstance {
        origin.zero()
        return this
    }

    fun setBounds(center: Vec3, radius: Float): SupportInstance {
        boundCenter.set(center.x.toFloat(), center.y.toFloat(), center.z.toFloat())
        boundRadius = radius
        return this
    }
}
