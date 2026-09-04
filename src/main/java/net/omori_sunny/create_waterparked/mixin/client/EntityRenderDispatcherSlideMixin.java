package net.omori_sunny.create_waterparked.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.client.EntitySlideClientSessions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// render-frame smoothing for non-player slide riders: the dispatcher has
// already translated the pose stack to the entity's packet position; shift it
// onto the interpolated trajectory sample and correct the yaw/pitch onto the
// tangent, so any renderer draws the entity gliding per frame
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSlideMixin {

    @Inject(
        method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            shift = At.Shift.BEFORE
        )
    )
    private <E extends Entity> void waterparked$applySlidePose(
        E entity, double x, double y, double z, float entityYaw, float partialTicks,
        PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo ci
    ) {
        EntitySlideClientSessions.Pose pose = EntitySlideClientSessions.INSTANCE.poseFor(entity, partialTicks);
        if (pose == null) return;

        // the dispatcher anchored at the interpolated packet position; move
        // the anchor onto the sample (feet for the renderer); the delta is
        // camera independent
        double feetOffset = EntitySlideClientSessions.INSTANCE.feetOffsetY(entity);
        Vec3 target = pose.getPosition().add(0.0, -feetOffset, 0.0);
        Vec3 current = entity.getPosition(partialTicks);
        poseStack.translate(target.x - current.x, target.y - current.y, target.z - current.z);

        // rotate the residual yaw/pitch onto the tangent, about the new anchor
        float targetYaw = EntitySlideClientSessions.INSTANCE.yawOf(pose.getTangent());
        float targetPitch = EntitySlideClientSessions.INSTANCE.pitchOf(pose.getTangent());
        float lerpedYaw = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float lerpedPitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        float dYaw = Mth.wrapDegrees(targetYaw - lerpedYaw);
        float dPitch = Mth.wrapDegrees(targetPitch - lerpedPitch);
        if (Math.abs(dYaw) > 0.01f) poseStack.mulPose(Axis.YP.rotationDegrees(dYaw));
        if (Math.abs(dPitch) > 0.01f) poseStack.mulPose(Axis.XP.rotationDegrees(-dPitch));
    }
}
