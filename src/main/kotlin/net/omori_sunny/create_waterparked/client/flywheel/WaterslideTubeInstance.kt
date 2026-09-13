package net.omori_sunny.create_waterparked.client.flywheel

import dev.engine_room.flywheel.api.instance.InstanceHandle
import dev.engine_room.flywheel.api.instance.InstanceType
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

class WaterslideTubeInstance(
    type: InstanceType<out WaterslideTubeInstance>,
    handle: InstanceHandle
) : ColoredLitOverlayInstance(type, handle) {

    @JvmField
    val prevSpine = Vector3f()

    @JvmField
    val currSpine = Vector3f()

    @JvmField
    val prevTangent = Vector3f()

    @JvmField
    val currTangent = Vector3f()

    @JvmField
    val prevLateral = Vector3f()

    @JvmField
    val currLateral = Vector3f()

    @JvmField
    var prevRadius = 1.0f

    @JvmField
    var currRadius = 1.0f

    @JvmField
    var wallThickness = 0.1f

    @JvmField
    var flowStart = 0.0f

    @JvmField
    var flowEnd = 0.0f

    @JvmField
    var phaseStart = 0.0f

    @JvmField
    var phaseEnd = 0.0f

    @JvmField
    var arcBase = 0.0f

    @JvmField
    var flowSign = 1.0f

    @JvmField
    var mirror = 1.0f

    @JvmField
    var flowUpstream = 0.0f

    @JvmField
    var phaseUpstream = 0.0f

    @JvmField
    var downstreamMix = 1.0f

    @JvmField
    var jitterScale = 0.04f

    @JvmField
    var jitterFrequency = 4.0f

    @JvmField
    var jitterTimeScale = 1.0f

    @JvmField
    var jitterTime = 0.0f

    @JvmField
    var tailFadeStart = 0.0f

    @JvmField
    var tailFadeEnd = 0.0f

    @JvmField
    var waterTileSpan = 1.0f

    @JvmField
    var spriteU0 = 0f

    @JvmField
    var spriteU1 = 1f

    @JvmField
    var spriteV0 = 0f

    @JvmField
    var spriteV1 = 1f

    @JvmField
    var isWater = 0f

    @JvmField
    var waterAtlasUV = 0f

    fun setSegment(
        prevSpineV: Vec3,
        currSpineV: Vec3,
        prevTangentV: Vec3,
        currTangentV: Vec3,
        prevLateralV: Vec3,
        currLateralV: Vec3,
        prevRadiusV: Float,
        currRadiusV: Float
    ): WaterslideTubeInstance {
        prevSpine.set(prevSpineV.x.toFloat(), prevSpineV.y.toFloat(), prevSpineV.z.toFloat())
        currSpine.set(currSpineV.x.toFloat(), currSpineV.y.toFloat(), currSpineV.z.toFloat())
        prevTangent.set(prevTangentV.x.toFloat(), prevTangentV.y.toFloat(), prevTangentV.z.toFloat())
        currTangent.set(currTangentV.x.toFloat(), currTangentV.y.toFloat(), currTangentV.z.toFloat())
        prevLateral.set(prevLateralV.x.toFloat(), prevLateralV.y.toFloat(), prevLateralV.z.toFloat())
        currLateral.set(currLateralV.x.toFloat(), currLateralV.y.toFloat(), currLateralV.z.toFloat())
        prevRadius = prevRadiusV
        currRadius = currRadiusV
        return this
    }

    fun setZeroTransform(): WaterslideTubeInstance {
        prevSpine.zero()
        currSpine.zero()
        prevTangent.zero()
        currTangent.zero()
        prevLateral.zero()
        currLateral.zero()
        prevRadius = 0.0f
        currRadius = 0.0f
        wallThickness = 0.1f
        flowStart = 0.0f
        flowEnd = 0.0f
        phaseStart = 0.0f
        phaseEnd = 0.0f
        arcBase = 0.0f
        flowSign = 1.0f
        mirror = 1.0f
        flowUpstream = 0.0f
        phaseUpstream = 0.0f
        downstreamMix = 1.0f
        jitterScale = 0.04f
        jitterFrequency = 4.0f
        jitterTimeScale = 1.0f
        jitterTime = 0.0f
        tailFadeStart = 0.0f
        tailFadeEnd = 0.0f
        waterTileSpan = 1.0f
        spriteU0 = 0f
        spriteU1 = 1f
        spriteV0 = 0f
        spriteV1 = 1f
        isWater = 0f
        waterAtlasUV = 0f
        return this
    }
}
