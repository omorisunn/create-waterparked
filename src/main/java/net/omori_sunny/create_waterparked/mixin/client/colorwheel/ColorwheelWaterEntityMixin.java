package net.omori_sunny.create_waterparked.mixin.client.colorwheel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.djefrey.colorwheel.engine.ClrwlMeshPool;
import dev.djefrey.colorwheel.engine.ClrwlVertexView;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import net.omori_sunny.create_waterparked.CreateWaterparked;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters;
import java.util.HashSet;

// stamp water ids and sanitize non water mesh uv for the shaderpack
@Mixin(ClrwlMeshPool.class)
public abstract class ColorwheelWaterEntityMixin {

    private static final float WATER_U_THRESHOLD = 1.0f;
    private static final float MIN_UV_RANGE = 1e-5f;
    private static final float UV_INSET_RATIO = 0.03f;

    @Inject(method = "computeExtendedQuadData", at = @At("TAIL"), remap = false)
    private void waterparked$stampWaterEntity(QuadMesh mesh, ClrwlVertexView vertexView, CallbackInfo ci) {
        if (vertexView == null) return;
        int count = vertexView.vertexCount();
        if (count <= 0) return;

        // u peaks above 1.0 marks a water band, v stays in 0 or 1
        boolean hasUOver1 = false;
        HashSet<Float> vSet = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (vertexView.u(i) > WATER_U_THRESHOLD) hasUOver1 = true;
            vSet.add(vertexView.v(i));
        }
        boolean isWaterMesh = hasUOver1 && vSet.size() <= 2;

        if (isWaterMesh && IrisColorwheelCompat.waterShadingActive()) {
            int stamp = IrisColorwheelCompat.waterStampId();
            CreateWaterparked.INSTANCE.getLOGGER().info(
                "[WaterStamp] stamping {} verts (uOver1={}, vSet={}) with id {} pack={} adapter={}",
                count, hasUOver1, vSet.size(), stamp,
                IrisColorwheelCompat.shaderpackName(),
                ShaderpackWaterAdapters.activeOrGeneric().getClass().getSimpleName()
            );
            for (int i = 0; i < count; i++) {
                vertexView.entityX(i, (short) stamp);
                vertexView.entityY(i, (short) 0);
            }
            return; // water uv stays in tile space
        }

        // sanitize uv for non water meshes
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
                // decode the negative side wall u back to the atlas coordinate
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
            if (rangeU > MIN_UV_RANGE && rangeV > MIN_UV_RANGE) {
                // shrink the uv inset so sampling avoids sprite edges
                float insetU = rangeU * UV_INSET_RATIO;
                float insetV = rangeV * UV_INSET_RATIO;
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
                CreateWaterparked.INSTANCE.getLOGGER().debug(
                    "[UvSan] quad {} {} uv=({},{})-({},{})",
                    sideWall ? "sidewall" : "mesh", q, minU, maxU, minV, maxV
                );
            }
        }
    }
}