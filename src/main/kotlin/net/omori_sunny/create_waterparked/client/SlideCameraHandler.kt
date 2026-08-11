package net.omori_sunny.create_waterparked.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ViewportEvent

// Per-frame camera override; Sable applies yaw+180 / -pitch to the camera.
@OnlyIn(Dist.CLIENT)
object SlideCameraHandler {

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
}
