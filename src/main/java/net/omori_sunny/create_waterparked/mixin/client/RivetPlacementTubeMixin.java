package net.omori_sunny.create_waterparked.mixin.client;
// Mixin into the CCS rivet placement resolver: when the crosshair is on a
// waterslide tube wall the tube becomes a legal placement host, so the CCS
// outline/HUD/rotation pipeline renders natively on the wall; the preview
// geometry is re-anchored to the wall hit point.

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.rivet.RivetPlacement;
import dev.silvergold.simulatedcoasters.rivet.RivetPlacement.Resolved;
import kotlin.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRivetClientCtx;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RivetPlacement.class)
public abstract class RivetPlacementTubeMixin {

    @Inject(method = "findPlacementHit", at = @At("RETURN"), cancellable = true, remap = false)
    private static void waterparked$tubePlacementHit(Level level, Entity entity, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!level.isClientSide) return;
        // the tube occludes everything behind it: when the crosshair is on a
        // wall, ALWAYS take the synthetic tube hit - keeping the vanilla hit
        // on terrain behind the tube let CCS place a real hostless rivet
        // there (then auto-break it with particles)
        WaterslideRivetClientCtx.TubeTarget target = pickTube(level, entity);
        WaterslideRivetClientCtx.setActive(target);
        if (target == null) return;
        Direction nearest = nearestDirection(target.inward.scale(-1.0));
        cir.setReturnValue(new BlockHitResult(target.point, nearest, target.anchor, false));
    }

    @Inject(method = "isValidPlacement", at = @At("HEAD"), cancellable = true, remap = false)
    private static void waterparked$tubeValidPlacement(Level level, Resolved resolved, CallbackInfoReturnable<Boolean> cir) {
        WaterslideRivetClientCtx.TubeTarget target = WaterslideRivetClientCtx.getActive();
        if (target != null && resolved.host().equals(target.anchor)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "previewAabb", at = @At("RETURN"), cancellable = true, remap = false)
    private static void waterparked$tubePreviewAabb(Resolved resolved, CallbackInfoReturnable<net.minecraft.world.phys.AABB> cir) {
        WaterslideRivetClientCtx.TubeTarget target = WaterslideRivetClientCtx.getActive();
        if (target == null || !resolved.host().equals(target.anchor)) return;
        net.minecraft.world.phys.AABB aabb = cir.getReturnValue();
        if (aabb == null) return;
        Vec3 shift = target.point.subtract(aabb.getCenter());
        cir.setReturnValue(aabb.move(shift));
    }

    @Inject(method = "previewOutline", at = @At("RETURN"), cancellable = true, remap = false)
    private static void waterparked$tubePreviewOutline(Resolved resolved, CallbackInfoReturnable<RivetPlacement.PreviewOutline> cir) {
        WaterslideRivetClientCtx.TubeTarget target = WaterslideRivetClientCtx.getActive();
        if (target == null || !resolved.host().equals(target.anchor)) return;
        RivetPlacement.PreviewOutline outline = cir.getReturnValue();
        if (outline == null) return;
        // flat plate frame hugging the wall with the CCS snap/rotate angle:
        // rotate the wall-plane axes about the normal by the effective angle
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer viewer = mc.player;
        Level level = mc.level;
        int base = RivetPlacementOutlineAccessor.waterparked$previewYRotationDegrees();
        int effective = base;
        if (viewer != null && level != null) {
            effective = RivetPlacement.effectiveYRotationDegrees(
                level, target.hit, com.simibubi.create.AllKeys.ctrlDown(), base,
                viewer.getViewVector(1f));
        }
        double rot = Math.toRadians(effective);
        double cos = Math.cos(rot), sin = Math.sin(rot);
        Vec3 right = target.circum.scale(cos).add(target.tangent.scale(sin));
        Vec3 up = target.tangent.scale(cos).subtract(target.circum.scale(sin));
        cir.setReturnValue(new RivetPlacement.PreviewOutline(
            target.point, right, up, target.inward,
            4.875 / 16.0, 4.875 / 16.0, 1.0 / 16.0));
    }

    private static WaterslideRivetClientCtx.TubeTarget pickTube(Level level, Entity entity) {
        Vec3 eye = entity.getEyePosition();
        Vec3 view = entity.getViewVector(1f);
        WaterslideSectorEdit.WallHit best = null;
        double bestD = Double.MAX_VALUE;
        double d = 0.0;
        while (d <= 6.0) {
            WaterslideSectorEdit.WallHit hit = WaterslideSectorEdit.INSTANCE.resolveWallHit(level, eye.add(view.scale(d)), null);
            if (hit != null && d < bestD) {
                bestD = d;
                best = hit;
            }
            d += 0.075;
        }
        if (best == null) return null;
        BezierConnection curve = best.getCurve();
        // snap the wall placement to the uniform 0.25 grid unless CTRL (the
        // CCS free-position key) is held; the outline and the synthetic hit
        // both carry the snapped position
        float snapT = best.getT();
        float snapAngle = best.getAngle();
        if (!com.simibubi.create.AllKeys.ctrlDown()) {
            kotlin.Pair<Float, Float> snapped =
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner
                    .snapWall(level, curve, snapT, snapAngle);
            snapT = snapped.getFirst();
            snapAngle = snapped.getSecond();
        }
        Vec3 point = null;
        if (best.getSurfacePlot() != null) {
            float r0 = SlideCurveGeometry.INSTANCE.radiusAt(level, curve.bePositions.getFirst());
            float r1 = SlideCurveGeometry.INSTANCE.radiusAt(level, curve.bePositions.getSecond());
            float radius = net.minecraft.util.Mth.lerp(snapT, r0, r1)
                + (net.omori_sunny.create_waterparked.config.ModConfig.INSTANCE.wallThickness() - 0.1f);
            kotlin.Pair<Vec3, Vec3> frame0 = SlideCurveGeometry.INSTANCE.stableFrame(
                dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
                    .unitTangentAt(curve, snapT).normalize());
            double rad0 = Math.toRadians(snapAngle);
            point = curve.getPosition(snapT)
                .add(frame0.getFirst().scale(Math.cos(rad0) * radius))
                .add(frame0.getSecond().scale(Math.sin(rad0) * radius));
        }
        if (point == null) return null;
        // snapped cell already holds a rivet: no target, no outline
        if (net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner
            .isRivetOccupied(level, curve, snapT, snapAngle)) {
            return null;
        }
        double rad = Math.toRadians(snapAngle);
        Vec3 tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(curve, snapT).normalize();
        Pair<Vec3, Vec3> frame = SlideCurveGeometry.INSTANCE.stableFrame(tangent);
        Vec3 dir = frame.getFirst().scale(Math.cos(rad)).add(frame.getSecond().scale(Math.sin(rad)));
        // OPEN (none) sectors hold no rivets: no target, no outline; the
        // config lives on the resolving anchor keyed by the OPPOSITE endpoint,
        // and the check uses the SNAPPED angle (the actual placement spot)
        if (best.getSpaceAnchor() != null &&
            level.getBlockEntity(best.getSpaceAnchor()) instanceof
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity anchorBe) {
            net.minecraft.core.BlockPos sectorPeer =
                best.getSpaceAnchor().equals(curve.bePositions.getFirst())
                    ? curve.bePositions.getSecond()
                    : curve.bePositions.getFirst();
            var config = anchorBe.sectorConfigFor(sectorPeer);
            var placedSectors = net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout.INSTANCE.place(config);
            var sector = net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout.INSTANCE
                .sectorAt(placedSectors, snapAngle);
            if (sector == null || sector.getSector().getMaterial() ==
                net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.OPEN) {
                return null;
            }
        }
        Direction nearestDir = nearestDirection(dir.scale(-1.0));
        return new WaterslideRivetClientCtx.TubeTarget(
            curve.bePositions.getFirst(), point, dir.scale(-1.0), tangent,
            new BlockHitResult(point, nearestDir, curve.bePositions.getFirst(), false));
    }

    private static Direction nearestDirection(Vec3 v) {
        Direction best = Direction.NORTH;
        double bestDot = -Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            double dot = v.normalize().dot(Vec3.atLowerCornerOf(dir.getNormal()));
            if (dot > bestDot) {
                bestDot = dot;
                best = dir;
            }
        }
        return best;
    }
}
