package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.AllItems
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities

// the drive a hinged run leaves behind in the world: a powerable shaft you can walk and build through
class RollerWorldShaftBlock(properties: BlockBehaviour.Properties) : ShaftBlock(properties) {

    override fun getBlockEntityType(): BlockEntityType<out KineticBlockEntity> = ModBlockEntities.ROLLER_HINGE_SHAFT_BE

    // the shaft is drawn by its visual or its renderer alone, so the model never doubles it
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    // only collision and occlusion go empty, so the shaft keeps its outline and can still be aimed at
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape = Shapes.empty()

    // a wrench on the shaft flips the latch of the run it drives, exactly like the wrench on the deck does. Only
    // a run that is still assembled has one to flip, but the click is consumed either way: a plain wrench on this
    // shaft has no second meaning, and handing it back let Create flip the shaft's axis instead. Sneaking is not
    // ours, so Create keeps that click and still takes the shaft (and the run with it) away.
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult
    ): ItemInteractionResult {
        if (latchWrench(stack, player, hand)) {
            if (!level.isClientSide) RollerHingeTilt.toggleLockAtShaft(level, pos)
            return ItemInteractionResult.SUCCESS
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
    }

    // the same latch wrench the deck asks for: main hand, not sneaking, and the player is allowed to build there
    private fun latchWrench(stack: ItemStack, player: Player, hand: InteractionHand): Boolean =
        !player.isShiftKeyDown && hand == InteractionHand.MAIN_HAND && player.mayBuild() &&
            AllItems.WRENCH.isIn(stack)

    // a shaft that is broken or pushed away takes its run with it, never leaving a deck behind
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (!level.isClientSide && state.block != newState.block) RollerHingeTilt.dismantleRun(level, pos)
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        fun defaultProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(1.5f, 6.0f)
            .sound(SoundType.METAL)
            .noCollission()
            .noOcclusion()
    }
}
