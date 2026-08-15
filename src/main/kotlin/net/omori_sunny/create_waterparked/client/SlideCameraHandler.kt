package net.omori_sunny.create_waterparked.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ViewportEvent

// Per-frame camera override; Sable applies yaw+180 / -pitch to the camera.
@OnlyIn(Dist.CLIENT)
object SlideCameraHandler {

    private var fovBoost = 1.0

    @JvmStatic
    fun onComputeCameraAngles(event: ViewportEvent.ComputeCameraAngles) {
        val state = SlideClientSession.cameraState(event.partialTick.toFloat()) ?: return
        val camera = event.camera
        val delta = state.pos.subtract(camera.position)
        camera.move(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat())
        event.yaw = state.yaw
        event.pitch = state.pitch
        event.roll = state.roll
    }

    @JvmStatic
    fun onComputeFov(event: ViewportEvent.ComputeFov) {
        if (!SlideClientSession.isSliding()) {
            fovBoost = 1.0
            return
        }
        // speed 0 -> base (configured) FOV; up to +50% at 40 blocks/s
        val speed = SlideClientSession.currentSpeedBlocksPerSecond()
        val target = 1.0 + (speed / 40f).coerceIn(0f, 1f) * 0.5
        fovBoost += (target - fovBoost) * 0.25
        event.fov = event.fov * fovBoost
    }
}
