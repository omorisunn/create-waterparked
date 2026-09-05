package net.omori_sunny.create_waterparked.mixin.client;
// wall rivet placement animation, byte-for-byte the CCS transform: the rivet
// block itself rotates 90 -> 0 degrees about the pivot (0.5, 0.9375, 0.5)
// while dropping 0.5 -> 0 over 250ms with quadratic ease. The CCS mixin only
// recognises their own rivet block, so this twin applies the identical math
// to our extended block; the animation timeline (RivetPlacementAnimationClient)
// is shared - our server already sends their RivetPlaceAnimPayload.

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silvergold.simulatedcoasters.client.rivet.RivetPlacementAnimationClient;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData")
public abstract class VanillaSingleBlockSubLevelRenderDataRivetAnimMixin {

    @Shadow(remap = false)
    private dev.ryanhcode.sable.sublevel.ClientSubLevel subLevel;

    @Shadow(remap = false)
    private BlockState singleBlockState;

    @Inject(
        method = "renderSingleBlock",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/platform/SableSubLevelRenderPlatform;tesselateBlock(Ldev/ryanhcode/sable/sublevel/render/vanilla/SingleBlockSubLevelWrapper;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/util/RandomSource;JILnet/minecraft/client/renderer/RenderType;)V",
            shift = At.Shift.BEFORE
        ),
        remap = false
    )
    private void waterparked$spinWallRivetIn(
        RenderType renderType, VertexConsumer consumer, Matrix4f matrix,
        double x, double y, double z,
        CallbackInfo ci, @Local(name = "stack") PoseStack stack
    ) {
        if (!RivetPlacementAnimationClient.hasActive()) return;
        if (!(singleBlockState.getBlock() instanceof net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetBlock)) return;
        float anim = RivetPlacementAnimationClient.progress(subLevel.getUniqueId());
        if (anim < 0f) return;
        float remaining = 1.0f - RivetPlacementAnimationClient.easeIn(anim);
        float yOffset = 0.5f * remaining;
        float angleRad = (float) Math.toRadians(90.0 * remaining);
        Matrix4f m = new Matrix4f()
            .translate(0.5f, 0.9375f, 0.5f)
            .rotateY(angleRad)
            .translate(-0.5f, -0.9375f, -0.5f)
            .translate(0f, yOffset, 0f);
        stack.last().pose().mul(m);
        stack.last().normal().mul(new Matrix3f(m));
    }
}
