package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// allow slide handles and sector control point dragging
@Mixin(BezierHandleDragManager.class)
public abstract class BezierHandleDragManagerMixin {

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

    @Inject(method = "clientTick(Lnet/minecraft/client/Minecraft;)V", at = @At("HEAD"))
    private static void waterslide$clientTick(Minecraft mc, CallbackInfo ci) {
        WaterslideSectorEdit.mixinClientTick(mc);
        WaterslideRadiusEdit.mixinClientTick(mc);
    }

    @Inject(method = "isHoveringOrDraggingAnyHandle(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true)
    private static void waterslide$anyHandle(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideSectorEdit.isHoveringOrDraggingControlPoint(mc)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "shouldSuppressVanillaUse(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true)
    private static void waterslide$suppressUse(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideSectorEdit.isHoveringOrDraggingControlPoint(mc)) {
            cir.setReturnValue(true);
        }
    }
}
