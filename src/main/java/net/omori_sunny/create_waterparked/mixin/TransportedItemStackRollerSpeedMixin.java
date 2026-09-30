package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.roller.RollerMomentum;
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the roller deck stores one landing speed per item, travelling in Create's own item nbt so the client mirrors it
@Mixin(TransportedItemStack.class)
public abstract class TransportedItemStackRollerSpeedMixin implements RollerMomentumAccess {

    @Unique
    private float waterparked$rollerSpeed;

    @Unique
    private int waterparked$deckLane;

    @Inject(method = "<init>(Lnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
    private void waterparked$landingSpeed(ItemStack stack, CallbackInfo ci) {
        waterparked$rollerSpeed = RollerMomentum.release();
    }

    @Inject(method = "getSimilar()Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;", at = @At("RETURN"))
    private void waterparked$copySpeed(CallbackInfoReturnable<TransportedItemStack> cir) {
        ((RollerMomentumAccess) cir.getReturnValue()).waterparked$setRollerSpeed(waterparked$rollerSpeed);
        ((RollerMomentumAccess) cir.getReturnValue()).waterparked$setDeckLane(waterparked$deckLane);
    }

    @Inject(
        method = "serializeNBT(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;",
        at = @At("RETURN")
    )
    private void waterparked$writeSpeed(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        if (waterparked$rollerSpeed != RollerMomentum.NO_LANDING)
            cir.getReturnValue().putFloat("RollerSpeed", waterparked$rollerSpeed);
        if (waterparked$deckLane != 0)
            cir.getReturnValue().putInt("RollerLane", waterparked$deckLane);
    }

    @Inject(
        method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;",
        at = @At("RETURN")
    )
    private static void waterparked$readSpeed(
        CompoundTag nbt,
        HolderLookup.Provider registries,
        CallbackInfoReturnable<TransportedItemStack> cir
    ) {
        if (nbt.contains("RollerSpeed"))
            ((RollerMomentumAccess) cir.getReturnValue()).waterparked$setRollerSpeed(nbt.getFloat("RollerSpeed"));
        if (nbt.contains("RollerLane"))
            ((RollerMomentumAccess) cir.getReturnValue()).waterparked$setDeckLane(nbt.getInt("RollerLane"));
    }

    @Override
    @Unique
    public float waterparked$rollerSpeed() {
        return waterparked$rollerSpeed;
    }

    @Override
    @Unique
    public void waterparked$setRollerSpeed(float speed) {
        waterparked$rollerSpeed = speed;
    }

    @Override
    @Unique
    public int waterparked$deckLane() {
        return waterparked$deckLane;
    }

    @Override
    @Unique
    public void waterparked$setDeckLane(int lane) {
        waterparked$deckLane = lane;
    }
}
