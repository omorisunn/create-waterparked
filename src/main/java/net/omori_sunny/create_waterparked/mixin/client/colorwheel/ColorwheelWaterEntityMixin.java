package net.omori_sunny.create_waterparked.mixin.client.colorwheel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.djefrey.colorwheel.engine.ClrwlMeshPool;
import dev.djefrey.colorwheel.engine.ClrwlVertexView;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;

/**
 * Makes Colorwheel stamp the WATER block-state id into the per-vertex entity
 * attribute (location 5, "_clrwl_aEntity", offset +32 in ClrwlVertexView) for
 * waterslide water meshes. Shaderpacks read that attribute as mc_Entity and
 * classify water from it, so the mounted tube water + thrown water get the
 * active pack's OWN water shader (gbuffers_water / clrwl water path).
 *
 * Water band identification: the band's U coordinate is an arc-length tile
 * count (peaks > 1.0, e.g. ~2.09 for radius 1) while its V is only ever 0 or 1
 * (bed arc vs surface arc). Tube walls and end caps keep U within [0,1], so
 * they are never mistaken for water.
 *
 * Injected at TAIL: ClrwlMeshPool itself overwrites the entity attribute with
 * -1 while computing extended data, so a HEAD stamp would be clobbered.
 */
@Mixin(ClrwlMeshPool.class)
public abstract class ColorwheelWaterEntityMixin {

    @Inject(method = "computeExtendedQuadData", at = @At("TAIL"), remap = false)
    private void waterparked$stampWaterEntity(QuadMesh mesh, ClrwlVertexView vertexView, CallbackInfo ci) {
        if (!IrisColorwheelCompat.waterShadingActive()) return;
        if (vertexView == null) return;
        int count = vertexView.vertexCount();
        if (count <= 0) return;

        // Water band identification: U peaks above 1.0 (arc-length tile count)
        // while V only ever takes 0 or 1 (bed arc vs surface arc).
        boolean hasUOver1 = false;
        java.util.HashSet<Float> vSet = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            if (vertexView.u(i) > 1.0f) hasUOver1 = true;
            vSet.add(vertexView.v(i));
        }
        if (!hasUOver1 || vSet.size() > 2) return;

        // Pack-specific water id: Complementary-family packs classify water as
        // mc_Entity.x in [32000, 32004); BSL's clrwl translucent program
        // classifies it as mc_Entity.x/100 == 200 (20000-range legacy id).
        int stamp = IrisColorwheelCompat.waterStampId();
        for (int i = 0; i < count; i++) {
            vertexView.entityX(i, (short) stamp);
            vertexView.entityY(i, (short) 0);
        }
    }
}
