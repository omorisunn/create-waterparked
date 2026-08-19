package net.omori_sunny.create_waterparked.mixin.client.colorwheel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.djefrey.colorwheel.engine.ClrwlMeshPool;
import dev.djefrey.colorwheel.engine.ClrwlVertexView;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters;

/**
 * Colorwheel integration and mesh-attribute sanitising.
 *
 * 1) WATER stamping: makes Colorwheel stamp the WATER block-state id into the
 *    per-vertex entity attribute (location 5, "_clrwl_aEntity", offset +32 in
 *    ClrwlVertexView) for waterslide water meshes, so shaderpacks classify the
 *    tube water + thrown stream as their own water. Gated by the
 *    shaderWaterCompat toggle: off = no stamping, water keeps the plain look.
 *
 * 2) UV sanitising for EVERY non-water quad mesh (moved here because this is the
 *    exact point where Colorwheel hands raw mesh attributes to the shaderpack):
 *    - the side wall between OPEN and filled sectors is baked with a NEGATIVE
 *      encoded u (-uAtlas - 1). A shaderpack samples the block atlas with the
 *      vertex uv as an ATLAS coordinate; a negative coordinate wraps around the
 *      atlas and lands on an arbitrary region (often grass/leaves) -> the
 *      opaque green face overlapping at the sector boundary. Decode it back to
 *      the positive atlas coordinate here.
 *    - every non-water quad's uv is shrunk toward its own quad range by ~3% per
 *      side, so atlas sampling never sits exactly on a sprite-rect edge where
 *      mipmapping / linear filtering would blend in the neighbouring sprite
 *      (the shimmering green edges of the support beam and bracket).
 *
 * Injected at TAIL: ClrwlMeshPool itself overwrites the entity attribute with
 * -1 while computing extended data, so a HEAD stamp would be clobbered.
 */
@Mixin(ClrwlMeshPool.class)
public abstract class ColorwheelWaterEntityMixin {

    @Inject(method = "computeExtendedQuadData", at = @At("TAIL"), remap = false)
    private void waterparked$stampWaterEntity(QuadMesh mesh, ClrwlVertexView vertexView, CallbackInfo ci) {
        if (vertexView == null) return;
        int count = vertexView.vertexCount();
        if (count <= 0) return;

        // Water band identification: U peaks above 1.0 (arc-length tile count)
        // while V only ever takes 0 or 1 (bed arc vs surface arc). Tube walls
        // and end caps keep U within [0,1] (or negative for side walls), so they
        // are never mistaken for water.
        boolean hasUOver1 = false;
        java.util.HashSet<Float> vSet = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            if (vertexView.u(i) > 1.0f) hasUOver1 = true;
            vSet.add(vertexView.v(i));
        }
        boolean isWaterMesh = hasUOver1 && vSet.size() <= 2;

        if (isWaterMesh && IrisColorwheelCompat.waterShadingActive()) {
            // Pack-specific water id, resolved from the active shaderpack adapter
            // (BslWaterAdapter=20000, Complementary=32000, Photon=10001, generic 32000).
            int stamp = IrisColorwheelCompat.waterStampId();
            net.omori_sunny.create_waterparked.CreateWaterparked.INSTANCE.getLOGGER().info(
                "[WaterStamp] stamping {} verts (uOver1={}, vSet={}) with id {} pack={} adapter={}",
                count, hasUOver1, vSet.size(), stamp,
                IrisColorwheelCompat.shaderpackName(),
                ShaderpackWaterAdapters.activeOrGeneric().getClass().getSimpleName()
            );
            for (int i = 0; i < count; i++) {
                vertexView.entityX(i, (short) stamp);
                vertexView.entityY(i, (short) 0);
            }
            return; // water uv is tile space, never touched
        }

        // UV sanitising for non-water meshes (always on; independent of the
        // shaderWaterCompat toggle so the pack never sees broken UVs).
        int quads = count / 4;
        for (int q = 0; q < quads; q++) {
            int base = q * 4;
            float minU = Float.MAX_VALUE;
            float maxU = -Float.MAX_VALUE;
            float minV = Float.MAX_VALUE;
            float maxV = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                float u = vertexView.u(base + k);
                float v = vertexView.v(base + k);
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            boolean sideWall = minU < 0f;
            if (sideWall) {
                // decode the negative side-wall u back to the positive atlas
                // coordinate (-uAtlas - 1 -> uAtlas); re-derive the range
                for (int k = 0; k < 4; k++) {
                    vertexView.u(base + k, -vertexView.u(base + k) - 1f);
                }
                minU = Float.MAX_VALUE;
                maxU = -Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    float u = vertexView.u(base + k);
                    if (u < minU) minU = u;
                    if (u > maxU) maxU = u;
                }
            }
            float rangeU = maxU - minU;
            float rangeV = maxV - minV;
            boolean changed = sideWall;
            if (rangeU > 1e-5f && rangeV > 1e-5f) {
                // shrink each axis ~3% inward so sampling never sits exactly on
                // a sprite-rect edge (mip/linear bleed into neighbouring atlas
                // sprites was the shimmering green on the beam and bracket)
                float insetU = rangeU * 0.03f;
                float insetV = rangeV * 0.03f;
                for (int k = 0; k < 4; k++) {
                    float u = vertexView.u(base + k);
                    float v = vertexView.v(base + k);
                    float nu = minU + insetU + (u - minU) / rangeU * (rangeU - 2f * insetU);
                    float nv = minV + insetV + (v - minV) / rangeV * (rangeV - 2f * insetV);
                    if (nu != u || nv != v) {
                        vertexView.u(base + k, nu);
                        vertexView.v(base + k, nv);
                        changed = true;
                    }
                }
            }
            if (changed) {
                net.omori_sunny.create_waterparked.CreateWaterparked.INSTANCE.getLOGGER().debug(
                    "[UvSan] quad {} {} uv=({},{})-({},{})",
                    sideWall ? "sidewall" : "mesh", q, minU, maxU, minV, maxV
                );
            }
        }
    }
}