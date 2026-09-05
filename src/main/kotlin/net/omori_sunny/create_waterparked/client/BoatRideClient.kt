package net.omori_sunny.create_waterparked.client

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity
import net.omori_sunny.create_waterparked.network.BoatSyncPayload

// rider-side playback for boats, Create-cushion style:
//  - camera: the boat's frame delta (yaw/pitch) is folded into the player's
//    look each render frame, the bank angle is exposed as a camera roll; on
//    belts the boat's own synced yaw drives the same deltas
//  - position: the local player is placed onto the interpolated seat every
//    frame, so the camera no longer steps with the vehicle packets
@OnlyIn(Dist.CLIENT)
object BoatRideClient {

    private var vehicleId = 0
    private var prevYaw = 0f
    private var prevPitch = 0f
    private var roll = 0f

    /** per render frame; call from a RenderLevelStageEvent that always fires */
    @JvmStatic
    fun frame(partialTick: Float) {
        val player = Minecraft.getInstance().player ?: return
        val vehicle = player.vehicle
        if (vehicle !is InflatableBoat1x2Entity) {
            vehicleId = 0
            roll = 0f
            return
        }
        val pose = EntitySlideClientSessions.poseFor(vehicle, partialTick)
        val yaw: Float
        val pitch: Float
        val origin: Vec3
        if (pose != null) {
            yaw = EntitySlideClientSessions.yawOf(pose.tangent)
            pitch = EntitySlideClientSessions.pitchOf(pose.tangent)
            roll = EntitySlideClientSessions.rollOf(pose.tangent, pose.up)
            origin = pose.position.subtract(
                0.0, EntitySlideClientSessions.feetOffsetY(vehicle), 0.0
            )
        } else {
            // no slide session (water, belt, idle): follow the hull as synced
            yaw = vehicle.yRot
            pitch = vehicle.xRot
            roll = 0f
            origin = vehicle.position()
        }
        if (vehicleId != vehicle.id) {
            vehicleId = vehicle.id
            prevYaw = yaw
            prevPitch = pitch
        }
        val dYaw = Mth.wrapDegrees(yaw - prevYaw)
        val dPitch = Mth.wrapDegrees(pitch - prevPitch)
        prevYaw = yaw
        prevPitch = pitch

        player.yRot += dYaw
        player.xRot = Mth.clamp(player.xRot + dPitch, -90f, 90f)

        // seat the local player on the smooth boat pose: vanilla's rideTick
        // only repositions at packet rate
        val seatOffset = Vec3(0.0, 0.125, 0.0)
            .yRot(-vehicle.yRot * (Math.PI.toFloat() / 180f))
        val target = origin.add(seatOffset)
            .subtract(player.getVehicleAttachmentPoint(vehicle))
        player.setPos(target.x, target.y, target.z)
        player.yRotO += dYaw
        player.xRotO = player.xRot
    }

    /** camera roll while riding a boat, null when not riding */
    @JvmStatic
    fun cameraRoll(): Float? {
        val player = Minecraft.getInstance().player ?: return null
        if (player.vehicle !is InflatableBoat1x2Entity) return null
        return roll
    }

    /** high-rate server pose for a ridden boat; one lerp step kills the lag */
    @JvmStatic
    fun sync(payload: BoatSyncPayload) {
        val level = Minecraft.getInstance().level ?: return
        val boat = level.getEntity(payload.entityId) as? InflatableBoat1x2Entity ?: return
        boat.lerpTo(payload.x, payload.y, payload.z, payload.yaw, payload.pitch, 1)
    }
}
