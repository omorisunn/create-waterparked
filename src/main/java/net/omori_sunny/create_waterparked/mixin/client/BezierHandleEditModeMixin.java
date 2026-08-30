package net.omori_sunny.create_waterparked.mixin.client;
// Mixin: wrench curve-editor gate for waterslide anchors.

import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSupportEdit;
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BezierHandleEditMode.class)
public abstract class BezierHandleEditModeMixin {

    @Accessor("activeAnchor")
    public static BlockPos getRawActiveAnchor() {
        throw new AssertionError("mixin");
    }

    @Accessor("activeAnchor")
    public static void setRawActiveAnchor(BlockPos pos) {
        throw new AssertionError("mixin");
    }

    private static boolean waterslide$supportOwnsClick(Player player, BlockPos anchorPos) {
        if (player == null) return false;
        try {
            WaterslideTubeVisual.SupportPick pick = WaterslideSupportEdit.hoveredPick();
            if (pick != null && pick.anchorPos.equals(anchorPos)) return true;
            pick = WaterslideSupportEdit.freshPick();
            return pick != null && pick.anchorPos.equals(anchorPos);
        } catch (Throwable t) {
            try {
                net.omori_sunny.create_waterparked.CreateWaterparked.INSTANCE.getLOGGER().warn(
                    "[WaterslideBER] support pick failed in editor gate", t
                );
            } catch (Throwable ignored) {
            }
            WaterslideTubeVisual.SupportPick cached = WaterslideSupportEdit.hoveredPick();
            return cached != null && cached.anchorPos.equals(anchorPos);
        }
    }

    @Inject(
        method = "tryActivate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$noCurveNoEdit(
        Level level,
        Player player,
        BlockPos anchorPos,
        boolean enforceReach,
        CallbackInfo ci
    ) {
        if (!(level.getBlockEntity(anchorPos) instanceof WaterslideAnchorBlockEntity)) return;
        if (waterslide$supportOwnsClick(player, anchorPos)) {
            ci.cancel();
        }
    }
    
    @Inject(
        method = "tryActivateFromInteract(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$supportFirst(
        Level level,
        Player player,
        BlockPos anchorPos,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level.getBlockEntity(anchorPos) instanceof WaterslideAnchorBlockEntity)) return;
        if (waterslide$supportOwnsClick(player, anchorPos)) {
            cir.setReturnValue(false);
        }
    }
}