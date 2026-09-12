package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock
import com.simibubi.create.foundation.block.IBE
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

// binding block of a slide attachment (SAB): a kinetic block whose BE carries
// the attachment's slide position and runs its server logic. The pillar axis
// accepts a Create shaft - attachment types decide what rotation means (the
// mechanical door opens while driven and closes when the shaft stops). Right
// click with a block item sets the attachment material, support beam style.
class SlideAttachmentBlock(
    properties: Properties,
    private val typeRef: () -> SlideAttachmentType
) : RotatedPillarKineticBlock(properties), IBE<SlideAttachmentBlockEntity> {

    fun type(): SlideAttachmentType = typeRef()

    override fun getBlockEntityClass(): Class<SlideAttachmentBlockEntity> =
        SlideAttachmentBlockEntity::class.java

    override fun getBlockEntityType(): BlockEntityType<SlideAttachmentBlockEntity> =
        @Suppress("UNCHECKED_CAST")
        type().blockEntityType.get() as BlockEntityType<SlideAttachmentBlockEntity>

    override fun hasShaftTowards(
        world: net.minecraft.world.level.LevelReader,
        pos: BlockPos,
        state: BlockState,
        face: Direction
    ): Boolean = face.axis == state.getValue(AXIS)

    override fun getRotationAxis(state: BlockState): Direction.Axis = state.getValue(AXIS)

    // ---- material selection, support beam/bracket style ----

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: net.minecraft.world.InteractionHand,
        hitResult: BlockHitResult
    ): ItemInteractionResult {
        val be = level.getBlockEntity(pos) as? SlideAttachmentBlockEntity
            ?: return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
        // kinetic items (shafts, cogwheels, ...) must still PLACE onto the
        // hub: only plain block items act as material skins, sneak bypasses
        if (!player.isShiftKeyDown && isKineticItem(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (stack.item is BlockItem) {
            if (level.isClientSide) return ItemInteractionResult.SUCCESS
            val blockItem = stack.item as BlockItem
            val material = blockItem.block.defaultBlockState()
            be.setAttachmentMaterial(material, stack.copyWithCount(1))
            if (!player.isCreative) stack.shrink(1)
            level.playSound(
                null, pos,
                material.soundType.placeSound, net.minecraft.sounds.SoundSource.BLOCKS,
                1.0f, 1.0f
            )
            return ItemInteractionResult.SUCCESS
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
    }

    private fun isKineticItem(stack: ItemStack): Boolean =
        com.simibubi.create.AllBlocks.SHAFT.isIn(stack) ||
            com.simibubi.create.AllBlocks.COGWHEEL.isIn(stack) ||
            com.simibubi.create.AllBlocks.LARGE_COGWHEEL.isIn(stack) ||
            com.simibubi.create.AllBlocks.POWERED_SHAFT.isIn(stack)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        // wrench on the hub: reset the material, refund the consumed item
        val be = level.getBlockEntity(pos) as? SlideAttachmentBlockEntity ?: return InteractionResult.PASS
        if (!com.simibubi.create.AllItems.WRENCH.isIn(player.mainHandItem)) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val returned = be.resetAttachmentMaterial()
        if (!returned.isEmpty) {
            if (!player.inventory.add(returned)) player.drop(returned, false)
        }
        return InteractionResult.SUCCESS
    }

    // the attachment visual lives at the slide; the block renders its own model
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    // the visible model is only the 3..13 hub, so the pick ray and the block
    // outline stop on the model instead of on the full cube. Collisions keep the
    // whole block, so nothing about movement or placement changes
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = HUB_SHAPE

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = Shapes.block()

    companion object {
        // hub cube of the block model, in voxels; the block shape and the mode slot
        // anchor both derive from it so the two can never drift apart
        const val HUB_MIN_VOXEL = 3.0
        const val HUB_MAX_VOXEL = 13.0

        private val HUB_SHAPE: VoxelShape = Block.box(
            HUB_MIN_VOXEL, HUB_MIN_VOXEL, HUB_MIN_VOXEL,
            HUB_MAX_VOXEL, HUB_MAX_VOXEL, HUB_MAX_VOXEL
        )
    }
}
