package net.omori_sunny.create_waterparked.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotRenderer;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the inflatable boat sits on depots exactly like a cardboard package: the
// isPackage redirect routes it through DepotRenderer's package branch (4/16
// lift, 1.5x scale). Depots have no facing, so the angle inject gives the
// boat a fixed deterministic angle instead of the random per-item one
@Mixin(DepotRenderer.class)
public abstract class DepotRendererBoatAngleMixin {

    @Redirect(
        method = "renderItem",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/logistics/box/PackageItem;isPackage(Lnet/minecraft/world/item/ItemStack;)Z"
        )
    )
    private static boolean waterparked$boatIsPackage(ItemStack stack) {
        return stack.getItem() instanceof InflatableBoat1x2Item || PackageItem.isPackage(stack);
    }

    @Inject(method = "renderItemsOf", at = @At("HEAD"))
    private static void waterparked$alignBoatOnDepot(
        com.simibubi.create.foundation.blockEntity.SmartBlockEntity be, float partialTicks,
        PoseStack ms, MultiBufferSource buffer, int light, int overlay,
        DepotBehaviour depotBehaviour, CallbackInfo ci
    ) {
        DepotBehaviourAccessor behaviour = (DepotBehaviourAccessor) depotBehaviour;
        TransportedItemStack held = behaviour.waterparked$getHeldItem();
        if (held != null && held.stack.getItem() instanceof InflatableBoat1x2Item) {
            held.angle = 0;
        }
        for (TransportedItemStack tis : behaviour.waterparked$getIncoming()) {
            if (tis.stack.getItem() instanceof InflatableBoat1x2Item) {
                tis.angle = 0;
            }
        }
    }
}
