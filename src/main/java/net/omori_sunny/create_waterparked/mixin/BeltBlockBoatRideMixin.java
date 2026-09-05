package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// boats riding a belt: plain right-click pops the boat back into the world and
// seats the player on it; sneak right-click keeps vanilla Create's pickup.
// Vanilla only picks belt items up without sneaking, so the sneak path needs
// its own handling here.
@Mixin(BeltBlock.class)
public abstract class BeltBlockBoatRideMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void waterparked$boatBeltInteraction(
        ItemStack stack, BlockState state, Level level, BlockPos pos,
        Player player, InteractionHand hand, BlockHitResult hit,
        CallbackInfoReturnable<ItemInteractionResult> cir
    ) {
        // only the empty main-hand path vanilla uses for taking items off belts
        if (!stack.isEmpty() || hand != InteractionHand.MAIN_HAND || !player.mayBuild()) return;
        BeltBlockEntity segment = BeltHelper.getSegmentBE(level, pos);
        if (segment == null) return;
        BeltBlockEntity controller = segment.getControllerBE();
        if (controller == null) return;
        if (!create_waterparked$hasBoatNear(controller.getInventory(), segment.index)) return;

        if (level.isClientSide) {
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
            return;
        }
        if (player.isShiftKeyDown()) {
            controller.getInventory().applyToEachWithin(segment.index + 0.5f, 0.55f, transported -> {
                if (transported.stack.getItem() instanceof InflatableBoat1x2Item) {
                    player.getInventory().placeItemBackInInventory(transported.stack.copy());
                    return TransportedResult.removeItem();
                }
                return TransportedResult.doNothing();
            });
        } else {
            MutableBoolean spawned = new MutableBoolean(false);
            controller.getInventory().applyToEachWithin(segment.index + 0.5f, 0.55f, transported -> {
                if (spawned.isTrue() || !(transported.stack.getItem() instanceof InflatableBoat1x2Item))
                    return TransportedResult.doNothing();
                spawned.setTrue();
                InflatableBoat1x2Entity boat =
                    new InflatableBoat1x2Entity(ModEntityTypes.INSTANCE.getINFLATABLE_BOAT_1X2(), level);
                boat.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                DyedItemColor dye = transported.stack.get(DataComponents.DYED_COLOR);
                boat.setColor(dye != null ? dye.rgb() : 0xFFFFFF);
                level.addFreshEntity(boat);
                player.startRiding(boat);
                return TransportedResult.removeItem();
            });
        }
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
            0.2f, 1.0f + level.random.nextFloat());
        cir.setReturnValue(ItemInteractionResult.SUCCESS);
    }

    @Unique
    private static boolean create_waterparked$hasBoatNear(BeltInventory inventory, int segmentIndex) {
        for (TransportedItemStack transported : inventory.getTransportedItems()) {
            if (transported.stack.getItem() instanceof InflatableBoat1x2Item
                && Math.abs(transported.beltPosition - (segmentIndex + 0.5f)) <= 0.55f) {
                return true;
            }
        }
        return false;
    }
}
