package net.omori_sunny.create_waterparked.client.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class WaterslideTubeInstance extends ColoredLitOverlayInstance {
    public final Vector3f prevSpine = new Vector3f();
    public final Vector3f currSpine = new Vector3f();
    public final Vector3f prevTangent = new Vector3f();
    public final Vector3f currTangent = new Vector3f();
    public final Vector3f prevLateral = new Vector3f();
    public final Vector3f currLateral = new Vector3f();
    public float prevRadius = 1.0f;
    public float currRadius = 1.0f;
    public float wallThickness = 0.1f;
    public float flowStart = 0.0f;
    public float flowEnd = 0.0f;
    public float phaseStart = 0.0f;
    public float phaseEnd = 0.0f;
    public float arcBase = 0.0f;
    public float flowSign = 1.0f;
    public float mirror = 1.0f;
    public float flowUpstream = 0.0f;
    public float phaseUpstream = 0.0f;
    public float downstreamMix = 1.0f;
    public float jitterScale = 0.04f;
    public float jitterFrequency = 4.0f;
    public float jitterTimeScale = 1.0f;
    public float jitterTime = 0.0f;
    public float tailFadeStart = 0.0f;
    public float tailFadeEnd = 0.0f;
    // water texture repeat span in blocks along the flow. 1 = one tile per
    // block (vanilla-ish); under shaderpacks a larger span softens per-block
    // striping in the pack's water material/normal sampling.
    public float waterTileSpan = 1.0f;
    // sprite rect (u0,u1,v0,v1) of this mesh's texture; passed via the
    // instance so the mesh vertex attributes stay clean for Colorwheel/packs
    public float spriteU0 = 0f, spriteU1 = 1f, spriteV0 = 0f, spriteV1 = 1f;
    // 1 = water mesh (drives water shading branch in the vertex shader)
    public float isWater = 0f;
    // 1 = the active shaderpack samples the block atlas directly from the vertex
    // uv (iterationRP's gbuffers_water does textureGrad(tex, v_texCoord)), so the
    // water uv must be exported already folded into the water sprite rect (tile
    // coordinates would sample arbitrary atlas regions = loud garbage water).
    // Only ever 1 while colorwheel is routing our materials (our fragment shader
    // does not run then), so the non-pack look is untouched.
    public float waterAtlasUV = 0f;

    public WaterslideTubeInstance(InstanceType<? extends WaterslideTubeInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public WaterslideTubeInstance setSegment(
        Vec3 prevSpineV,
        Vec3 currSpineV,
        Vec3 prevTangentV,
        Vec3 currTangentV,
        Vec3 prevLateralV,
        Vec3 currLateralV,
        float prevRadiusV,
        float currRadiusV
    ) {
        this.prevSpine.set((float) prevSpineV.x, (float) prevSpineV.y, (float) prevSpineV.z);
        this.currSpine.set((float) currSpineV.x, (float) currSpineV.y, (float) currSpineV.z);
        this.prevTangent.set((float) prevTangentV.x, (float) prevTangentV.y, (float) prevTangentV.z);
        this.currTangent.set((float) currTangentV.x, (float) currTangentV.y, (float) currTangentV.z);
        this.prevLateral.set((float) prevLateralV.x, (float) prevLateralV.y, (float) prevLateralV.z);
        this.currLateral.set((float) currLateralV.x, (float) currLateralV.y, (float) currLateralV.z);
        this.prevRadius = prevRadiusV;
        this.currRadius = currRadiusV;
        return this;
    }

    public WaterslideTubeInstance setZeroTransform() {
        this.prevSpine.zero();
        this.currSpine.zero();
        this.prevTangent.zero();
        this.currTangent.zero();
        this.prevLateral.zero();
        this.currLateral.zero();
        this.prevRadius = 0.0f;
        this.currRadius = 0.0f;
        this.wallThickness = 0.1f;
        this.flowStart = 0.0f;
        this.flowEnd = 0.0f;
        this.phaseStart = 0.0f;
        this.phaseEnd = 0.0f;
        this.arcBase = 0.0f;
        this.flowSign = 1.0f;
        this.mirror = 1.0f;
        this.flowUpstream = 0.0f;
        this.phaseUpstream = 0.0f;
        this.downstreamMix = 1.0f;
        this.jitterScale = 0.04f;
        this.jitterFrequency = 4.0f;
        this.jitterTimeScale = 1.0f;
        this.jitterTime = 0.0f;
        this.tailFadeStart = 0.0f;
        this.tailFadeEnd = 0.0f;
        this.waterTileSpan = 1.0f;
        this.spriteU0 = 0f; this.spriteU1 = 1f; this.spriteV0 = 0f; this.spriteV1 = 1f;
        this.isWater = 0f;
        this.waterAtlasUV = 0f;
        return this;
    }
}
