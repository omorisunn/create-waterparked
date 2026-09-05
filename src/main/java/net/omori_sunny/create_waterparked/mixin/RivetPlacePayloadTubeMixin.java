package net.omori_sunny.create_waterparked.mixin;
// Server-side redirect of the CCS rivet placement: a payload aimed at a
// waterslide anchor with its hit point on a tube wall becomes a REAL
// RivetBlock bound to the slide anchor (native render/drops/wrench); the CCS
// placement animation still plays. Cancel happens synchronously at HEAD so
// the vanilla CCS placement can never also fire.

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.rivet.RivetBlock;
import dev.silvergold.simulatedcoasters.rivet.RivetHostKey;
import dev.silvergold.simulatedcoasters.rivet.RivetPlacePayload;
import dev.silvergold.simulatedcoasters.rivet.RivetPlacement;
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames;
import kotlin.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RivetPlacePayload.class)
public abstract class RivetPlacePayloadTubeMixin {

    @Inject(method = "handleOnServer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void waterparked$tubeRivet(RivetPlacePayload payload, IPayloadContext ctx, CallbackInfo ci) {
        // payloads arrive on the network thread; cancel SYNCHRONOUSLY so the
        // vanilla CCS placement (real RivetBlock, later auto-broken as
        // hostless) can never be scheduled, then place ours on the main thread
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(payload.host()) instanceof WaterslideAnchorBlockEntity)) return;
        ci.cancel();

        ctx.enqueueWork(() -> {
            if (!(level.getBlockEntity(payload.host()) instanceof WaterslideAnchorBlockEntity anchor)) return;

            // project the hit point onto one of the anchor's slide curves;
            // the hit lands on the OUTER wall at radius + (thickness - 0.1)
            float wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.INSTANCE.wallThickness();
            double gate = wallThickness + 0.35d;
            BezierConnection best = null;
            float bestT = -1f;
            double bestResidual = Double.MAX_VALUE;
            for (Object raw : anchor.getAnchorPeerCurvesView().values()) {
                if (!(raw instanceof BezierConnection conn)) continue;
                BezierConnection curve = conn.isPrimary() ? conn : conn.secondary();
                if (!WaterslideTrackMaterials.isWaterslide(curve)) continue;
                float r0 = SlideCurveGeometry.INSTANCE.radiusAt(level, curve.bePositions.getFirst());
                float r1 = SlideCurveGeometry.INSTANCE.radiusAt(level, curve.bePositions.getSecond());
                int samples = Math.max(64, curve.getSegmentCount() * 4);
                for (int i = 0; i <= samples; i++) {
                    float t = (float) i / samples;
                    double dist = payload.hitLocation().distanceTo(curve.getPosition(t));
                    double radius = Mth.lerp(t, r0, r1);
                    // locality gate keeps the search around the hit; the
                    // ranking below uses the NEAREST SPINE POINT (not the
                    // radial residual, which is near-uniform along the wall
                    // and drifts the projected t sideways)
                    if (dist > radius + gate) continue;
                    if (dist < bestResidual) {
                        bestResidual = dist;
                        best = curve;
                        bestT = t;
                    }
                }
            }
            if (best == null) return;

            // OPEN (none) sectors hold no rivets; config is keyed by the
            // endpoint OPPOSITE the resolving anchor
            net.minecraft.core.BlockPos peer = payload.host().equals(best.bePositions.getFirst())
                ? best.bePositions.getSecond()
                : best.bePositions.getFirst();
            var config = anchor.sectorConfigFor(peer);
            var placed = net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout.INSTANCE
                .place(config);
            double anglePre = Math.toDegrees(Math.atan2(
                payload.hitLocation().subtract(best.getPosition(bestT)).dot(
                    SlideCurveGeometry.INSTANCE.stableFrame(
                        CoasterBezierRailFrames.unitTangentAt(best, bestT).normalize()).getSecond()),
                payload.hitLocation().subtract(best.getPosition(bestT)).dot(
                    SlideCurveGeometry.INSTANCE.stableFrame(
                        CoasterBezierRailFrames.unitTangentAt(best, bestT).normalize()).getFirst())));
            float anglePreN = (float) (((anglePre % 360d) + 360d) % 360d);
            var sectorAt = net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout.INSTANCE
                .sectorAt(placed, anglePreN);
            if (sectorAt == null || sectorAt.getSector().getMaterial() ==
                net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.OPEN) {
                return;
            }

            Vec3 tangent = CoasterBezierRailFrames.unitTangentAt(best, bestT).normalize();
            Pair<Vec3, Vec3> frame = SlideCurveGeometry.INSTANCE.stableFrame(tangent);
            Vec3 rel = payload.hitLocation().subtract(best.getPosition(bestT));
            double angle = Math.toDegrees(Math.atan2(rel.dot(frame.getSecond()), rel.dot(frame.getFirst())));
            float angleNorm = (float) (((angle % 360d) + 360d) % 360d);

            // duplicate cell: a rivet already occupies this wall spot
            if (net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner
                .isRivetOccupied(level, best, bestT, angleNorm)) {
                return;
            }

            Vec3 radial = frame.getFirst().scale(Math.cos(Math.toRadians(angleNorm)))
                .add(frame.getSecond().scale(Math.sin(Math.toRadians(angleNorm))));

            dev.ryanhcode.sable.sublevel.ServerSubLevel sub =
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner
                    .INSTANCE.tryPlaceOnWall(level, best, bestT, angleNorm, payload.yRotationDegrees(), player);
            if (sub == null) return;
            if (!player.getAbilities().instabuild) {
                player.getItemInHand(payload.hand()).shrink(1);
            }
            // CCS-style break burst using the nearest sector's material
            var sectorState = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                sectorAt.getSector().getBlockId())
                .defaultBlockState();
            level.sendParticles(
                new net.minecraft.core.particles.BlockParticleOption(
                    net.minecraft.core.particles.ParticleTypes.BLOCK, sectorState),
                payload.hitLocation().x, payload.hitLocation().y, payload.hitLocation().z,
                24, 0.25, 0.25, 0.25, 0.05);
            // CCS placement animation keyed by the rivet sub-level
            Vec3 wall = payload.hitLocation().add(radial.scale(0.1));
            PacketDistributor.sendToPlayer(player, new dev.silvergold.simulatedcoasters.rivet.RivetPlaceAnimPayload(
                sub.getUniqueId(), wall, radial,
                net.omori_sunny.create_waterparked.content.registry.ModBlocks.INSTANCE.getWATERSLIDE_RIVET()
                    .defaultBlockState().setValue(dev.silvergold.simulatedcoasters.rivet.RivetBlock.FACING, Direction.UP)));
        });
    }
}
