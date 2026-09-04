package net.omori_sunny.create_waterparked.mixin.client;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the inflatable boat rides belts exactly like a cardboard package: the
// isPackage redirect routes it through BeltRenderer's package branch (4/16
// lift, 1.5x scale, no scatter) and the angle inject keeps the hull lying
// along the belt travel direction instead of the random per-item angle
@Mixin(BeltRenderer.class)
public abstract class BeltRendererBoatAngleMixin {

    @Redirect(
        method = "renderItem",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/logistics/box/PackageItem;isPackage(Lnet/minecraft/world/item/ItemStack;)Z"
        )
    )
    private boolean waterparked$boatIsPackage(ItemStack stack) {
        return stack.getItem() instanceof InflatableBoat1x2Item || PackageItem.isPackage(stack);
    }

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void waterparked$alignBoatWithBelt(
        BeltBlockEntity be, float partialTicks, com.mojang.blaze3d.vertex.PoseStack ms,
        net.minecraft.client.renderer.MultiBufferSource buffer, int light, int overlay,
        Direction beltFacing, net.minecraft.core.Vec3i directionVec, BeltSlope slope,
        int verticality, boolean slopeAlongX, boolean onContraption,
        TransportedItemStack transported, net.minecraft.world.phys.Vec3 beltStartOffset,
        CallbackInfo ci
    ) {
        if (transported.stack.getItem() instanceof InflatableBoat1x2Item) {
            // model +z is the boat's long axis; yaw it onto the travel vector
            transported.angle = Math.round(
                (float) Math.toDegrees(Math.atan2(beltFacing.getStepX(), beltFacing.getStepZ()))
            );
        }
    }
}
