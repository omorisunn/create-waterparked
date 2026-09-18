package net.omori_sunny.create_waterparked.mixin.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.CreateWaterparked;
import net.omori_sunny.create_waterparked.client.attachment.GrabBarHoldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// holding pose on a grab bar: every rendered part is hinged about the grip, arm reach solved in model space
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelGrabBarMixin {

    private static final float HEAD_FOLLOW = 0.35f;
    private static final float LEG_SWING = 1.1f;
    private static final float SWAY_AMPLITUDE = 0.012f;
    private static final float SWAY_SPEED = 0.25f;
    private static final float SWING_AMPLITUDE = 0.42f;
    private static final float SWING_CYCLES = 1.25f;
    private static final float SWING_EASE = 2.0f;
    private static final float LEG_SPLAY = 0.02f;
    private static final float RIDE_LEG_X = -1.4137167f;
    private static final float STAND_LEG_X = 0f;
    private static final float SEATED_LEG_X = RIDE_LEG_X * 0.5f;
    private static final float LEG_TWIST_Y = 0f;
    private static final float LEG_TWIST_Z = 0.005f;
    private static final double MODEL_SCALE = 0.9375;
    private static final double MODEL_Y_OFFSET = 1.501;
    private static final double SHOULDER_PX_Y = 2.0;
    private static final double ARM_PX = 10.0;
    private static final int DIAG_TICKS = 20;

    @Unique
    private int waterparked$diagTick;

    @Unique
    private float waterparked$bodyZBase;

    @Unique
    private float waterparked$headZBase;

    @Unique
    private boolean waterparked$hinged;

    @Unique
    private Vec3 waterparked$modelToWorld(Vec3 playerPos, float bodyYaw, double mx, double my, double mz) {
        Vec3 local = new Vec3(
            -mx / 16.0 * MODEL_SCALE,
            (MODEL_Y_OFFSET - my / 16.0) * MODEL_SCALE,
            mz / 16.0 * MODEL_SCALE
        );
        return playerPos.add(local.yRot((float) Math.toRadians(180f - bodyYaw)));
    }

    @Unique
    private void waterparked$restoreBaseZ(HumanoidModel<?> model) {
        if (!this.waterparked$hinged) return;
        model.body.z = this.waterparked$bodyZBase;
        model.head.z = this.waterparked$headZBase;
        model.hat.z = this.waterparked$headZBase;
        this.waterparked$hinged = false;
    }

    @Unique
    private void waterparked$hinge(ModelPart part, double gy, double gz, double cos, double sin, float hinge) {
        double dy = part.y - gy;
        double dz = part.z - gz;
        part.y = (float) (gy + dy * cos - dz * sin);
        part.z = (float) (gz + dy * sin + dz * cos);
        part.xRot += hinge;
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void waterparked$grabBarPose(
        LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
        float netHeadYaw, float headPitch, CallbackInfo ci
    ) {
        if (!(entity instanceof Player)) return;
        float partialTick = Mth.clamp(ageInTicks - (float) entity.tickCount, 0f, 1f);
        float blend = GrabBarHoldClient.poseBlendFor(entity.getId(), partialTick);
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        waterparked$restoreBaseZ(model);
        if (blend < 0f) return;

        float progress = Math.max(GrabBarHoldClient.smoothedProgressFor(entity.getId(), partialTick), 0f);
        float yaw = GrabBarHoldClient.yawFor(entity.getId());
        float delta = (float) Math.toRadians(Mth.wrapDegrees(yaw - entity.yBodyRot));
        float payloadReach = -(float) Math.PI / 2f
            - GrabBarHoldClient.smoothedArmPitchFor(entity.getId(), partialTick);
        float reach = payloadReach;
        Vec3 grip = GrabBarHoldClient.gripFor(entity.getId());
        double gy = 0.0;
        double gz = 0.0;
        if (grip != null) {
            float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
            Vec3 playerPos = new Vec3(
                Mth.lerp((double) partialTick, entity.xOld, entity.getX()),
                Mth.lerp((double) partialTick, entity.yOld, entity.getY()),
                Mth.lerp((double) partialTick, entity.zOld, entity.getZ())
            );
            Vec3 offset = grip.subtract(playerPos).yRot((float) Math.toRadians(bodyYaw - 180f));
            gy = (MODEL_Y_OFFSET - offset.y / MODEL_SCALE) * 16.0;
            gz = offset.z / MODEL_SCALE * 16.0;
            double armY = gy - SHOULDER_PX_Y;
            double armZ = gz;
            reach = (float) Math.atan2(armZ, armY);
            if (entity.tickCount - this.waterparked$diagTick >= DIAG_TICKS) {
                this.waterparked$diagTick = entity.tickCount;
                double handY = SHOULDER_PX_Y + ARM_PX * Math.cos(reach);
                double handZ = ARM_PX * Math.sin(reach);
                Vec3 hand = waterparked$modelToWorld(playerPos, bodyYaw, 0.0, handY, handZ);
                CreateWaterparked.INSTANCE.getLOGGER().info(
                    "[GrabBarDiag] armLenPx={} anchorDist={}",
                    Math.sqrt(armY * armY + armZ * armZ), hand.distanceTo(grip)
                );
            }
        }

        ModelPart rightArm = model.rightArm;
        ModelPart leftArm = model.leftArm;
        rightArm.xRot = Mth.lerp(blend, rightArm.xRot, reach);
        leftArm.xRot = Mth.lerp(blend, leftArm.xRot, reach);
        rightArm.yRot = Mth.lerp(blend, rightArm.yRot, delta);
        leftArm.yRot = Mth.lerp(blend, leftArm.yRot, delta);
        rightArm.zRot = Mth.lerp(blend, rightArm.zRot, 0.12f);
        leftArm.zRot = Mth.lerp(blend, leftArm.zRot, -0.12f);

        boolean seated = GrabBarHoldClient.seatedFor(entity.getId());
        float splay = seated ? 0f : LEG_SPLAY * progress;
        ModelPart rightLeg = model.rightLeg;
        ModelPart leftLeg = model.leftLeg;
        float legX = seated ? SEATED_LEG_X : STAND_LEG_X;
        rightLeg.xRot = Mth.lerp(blend, rightLeg.xRot, legX);
        leftLeg.xRot = Mth.lerp(blend, leftLeg.xRot, legX);
        rightLeg.yRot = Mth.lerp(blend, rightLeg.yRot, LEG_TWIST_Y);
        leftLeg.yRot = Mth.lerp(blend, leftLeg.yRot, -LEG_TWIST_Y);
        rightLeg.zRot = Mth.lerp(blend, rightLeg.zRot, LEG_TWIST_Z + splay);
        leftLeg.zRot = Mth.lerp(blend, leftLeg.zRot, -LEG_TWIST_Z - splay);

        if (grip != null) {
            this.waterparked$bodyZBase = model.body.z;
            this.waterparked$headZBase = model.head.z;
            this.waterparked$hinged = true;
            float charge = Mth.clamp(
                GrabBarHoldClient.chargeTickFor(entity.getId(), partialTick) / GrabBarHoldClient.chargeTicks(),
                0f, 1f
            );
            float swing = -SWING_AMPLITUDE
                * Mth.sin(SWING_CYCLES * 2f * (float) Math.PI * (float) Math.pow(charge, SWING_EASE));
            float hinge = SWAY_AMPLITUDE * Mth.sin(ageInTicks * SWAY_SPEED) * progress + swing * blend;
            float headHinge = hinge * HEAD_FOLLOW;
            float legHinge = hinge * LEG_SWING;
            double cos = Mth.cos(hinge);
            double sin = Mth.sin(hinge);
            waterparked$hinge(model.body, gy, gz, cos, sin, hinge);
            waterparked$hinge(model.head, gy, gz, cos, sin, headHinge);
            waterparked$hinge(model.hat, gy, gz, cos, sin, headHinge);
            waterparked$hinge(model.rightArm, gy, gz, cos, sin, hinge);
            waterparked$hinge(model.leftArm, gy, gz, cos, sin, hinge);
            waterparked$hinge(model.rightLeg, gy, gz, cos, sin, legHinge);
            waterparked$hinge(model.leftLeg, gy, gz, cos, sin, legHinge);
        }
    }
}
