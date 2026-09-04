package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.foundation.item.ItemHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// package-style logistics: belts and depots absorb entities whose
// ItemHelper.fromItemEntity yields a stack, so the boat entity reports its
// dyed item stack and rides the Create logistics chain exactly like a
// cardboard package entity
@Mixin(ItemHelper.class)
public abstract class ItemHelperBoatMixin {

    @Inject(method = "fromItemEntity", at = @At("HEAD"), cancellable = true)
    private static void waterparked$boatAsItemStack(Entity entity, CallbackInfoReturnable<ItemStack> cir) {
        if (entity instanceof InflatableBoat1x2Entity boat) {
            // never absorb a boat someone is riding
            cir.setReturnValue(boat.isVehicle() ? ItemStack.EMPTY : boat.createItemStack());
        }
    }
}
