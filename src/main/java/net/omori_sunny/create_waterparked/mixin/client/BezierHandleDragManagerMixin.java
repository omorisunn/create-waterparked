package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// allow slide handles and sector control point dragging
@Mixin(BezierHandleDragManager.class)
public abstract class BezierHandleDragManagerMixin {

    @Shadow
    private static BlockPos dragLiftAnchorPos;

// hud lift readout without the radius offset
    @WrapOperation(
        method = "renderBezierEditAnchorStatusHud(Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/gui/GuiGraphics;)V",
        at = @At(
            value = "INVOKE",
            target = "Ldev/silvergold/simulatedcoasters/track/anchor/CoasterAnchorpointBlockEntity;"
                + "getLiftBlocks()F"
        )
    )
    private static float waterslide$hudLift(CoasterAnchorpointBlockEntity be, Operation<Float> original) {
        float value = original.call(be);
        return be instanceof WaterslideAnchorBlockEntity slide ? value - slide.getRadius() : value;
    }

// status hud radius segment
    @WrapOperation(
        method = "renderBezierEditAnchorStatusHud(Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/gui/GuiGraphics;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;"
                + "[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"
        )
    )
    private static MutableComponent waterslide$statusLift(
        String key, Object[] args, Operation<MutableComponent> original) {
        MutableComponent component = original.call(key, args);
        if (!"simulatedcoasters.track.bezier_edit_anchor_status_lift".equals(key)) return component;
        Minecraft mc = Minecraft.getInstance();
        BlockPos anchor = BezierHandleEditMode.getActiveAnchor();
        if (anchor == null || mc.level == null) return component;
        if (!(mc.level.getBlockEntity(anchor) instanceof WaterslideAnchorBlockEntity be)) return component;
        float radius = WaterslideRadiusEdit.INSTANCE.radiusAt(mc.level, anchor, be.getRadius());
        return component
            .append(Component.translatable("simulatedcoasters.track.bezier_edit_anchor_status_sep"))
            .append(Component.translatable(
                "create_waterparked.track.bezier_edit_radius_meters",
                CoasterBezierHandleEdit.formatLiftMetersReadout(radius)));
    }

// slide lift snaps to the independent max
    @WrapOperation(
        method = "liftDragVirtualTarget(Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;)"
            + "Lnet/minecraft/world/phys/Vec3;",
        at = @At(
            value = "INVOKE",
            target = "Ldev/silvergold/simulatedcoasters/track/CoasterBezierHandleEdit;"
                + "snapLiftBlocksToNearestHalf(F)F"
        )
    )
    private static float waterslide$liftSnap(float value, Operation<Float> original) {
        Level level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(dragLiftAnchorPos) instanceof WaterslideAnchorBlockEntity be) {
            float max = Math.max(ModConfig.INSTANCE.maxSlideLift() - be.getRadius(), 0.25f);
            float clamped = Mth.clamp(value, 0.25f, max);
            if (clamped < 0.5f) return 0.25f;
            return Mth.clamp(Math.round(clamped * 2f) / 2f, 0.25f, max);
        }
        return original.call(value);
    }

    @WrapOperation(
        method = "rayPickClosestTangentHandle(Lnet/minecraft/client/Minecraft;)"
            + "Ldev/silvergold/simulatedcoasters/client/track/BezierHandleDragManager$HandlePick;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$rayPick(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

// allow the secondary copy so drag works from either endpoint
    @Inject(
        method = "loadPrimary(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$loadPrimary(
        Level level,
        BlockPos host,
        BlockPos remote,
        CallbackInfoReturnable<BezierConnection> cir
    ) {
        if (!(level.getBlockEntity(host) instanceof CoasterAnchorpointBlockEntity ape)) return;
        BezierConnection c = ape.getAnchorPeerCurvesView().get(remote);
        if (c != null && !c.isPrimary() && WaterslideTrackMaterials.isWaterslide(c)) {
            cir.setReturnValue(c.secondary());
        }
    }

    @Inject(method = "clientTick(Lnet/minecraft/client/Minecraft;)V", at = @At("HEAD"), cancellable = true)
    private static void waterslide$clientTick(Minecraft mc, CallbackInfo ci) {
        WaterslideRadiusEdit.mixinClientTick(mc);
        WaterslideSectorEdit.mixinClientTick(mc);
        if (WaterslideRadiusEdit.isDragging() || WaterslideSectorEdit.isDraggingControlPoint()) {
            ci.cancel();
        }
    }

    @Inject(method = "isHoveringOrDraggingAnyHandle(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true)
    private static void waterslide$anyHandle(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideRadiusEdit.isHoveringOrDragging(mc) ||
            WaterslideSectorEdit.isHoveringOrDraggingControlPoint(mc)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "shouldSuppressVanillaUse(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true)
    private static void waterslide$suppressUse(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideRadiusEdit.isHoveringOrDragging(mc) ||
            WaterslideSectorEdit.isHoveringOrDraggingControlPoint(mc)) {
            cir.setReturnValue(true);
        }
    }
}
