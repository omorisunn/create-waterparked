package net.omori_sunny.create_waterparked.mixin.client;

import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSupportEdit;
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BezierHandleEditMode.class)
public abstract class BezierHandleEditModeMixin {

    @Shadow
    private static BlockPos activeAnchor;

    @Inject(method = "clientTick", at = @At("HEAD"), cancellable = true)
    private static void pliers$keepSessionAlive(Minecraft mc, CallbackInfo ci) {
        if (activeAnchor == null) return;
        if (!net.omori_sunny.create_waterparked.client.editor.pliers.BrassPliersEditor.holdsPlier(mc)) return;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.level.getBlockEntity(activeAnchor) instanceof
            dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "tryActivate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/tterrag/registrate/util/entry/ItemEntry;isIn(Lnet/minecraft/world/item/ItemStack;)Z"
        )
    )
    private static boolean pliers$activateGate(
        com.tterrag.registrate.util.entry.ItemEntry<?> entry,
        net.minecraft.world.item.ItemStack stack,
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original
    ) {
        return original.call(entry, stack) ||
            net.omori_sunny.create_waterparked.client.editor.pliers.BrassPliersEditor.isPlierStack(stack);
    }

    private static boolean waterslide$ghostComboOwnsClick(Player player) {
        return player != null && WaterslideGhostPlacement.INSTANCE
            .ghostPlacementStack(player) != null;
    }

    private static boolean waterslide$attachmentEditOwnsClick() {
        return net.omori_sunny.create_waterparked.client.editor.SlideAttachmentEdit
            .pickHitsAttachment();
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
        if (waterslide$attachmentEditOwnsClick()) {
            ci.cancel();
            return;
        }
        if (!(level.getBlockEntity(anchorPos) instanceof WaterslideAnchorBlockEntity)) return;
        if (waterslide$ghostComboOwnsClick(player) || waterslide$supportOwnsClick(player, anchorPos)) {
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
        if (waterslide$attachmentEditOwnsClick()) {
            cir.setReturnValue(false);
            return;
        }
        if (!(level.getBlockEntity(anchorPos) instanceof WaterslideAnchorBlockEntity)) return;
        if (waterslide$ghostComboOwnsClick(player) || waterslide$supportOwnsClick(player, anchorPos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "tryActivateFromCurveInteract(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$attachmentEditFirst(
        Level level,
        Player player,
        BlockPos anchorPos,
        CallbackInfo ci
    ) {
        if (net.omori_sunny.create_waterparked.client.editor.SlideAttachmentEdit
            .pickHitsAttachment() ||
            waterslide$attachmentEditOwnsClick()) {
            ci.cancel();
        }
    }
}