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

// what rotation means is up to the attachment type
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
        if (!player.isShiftKeyDown && !isPlaceableInsteadOfSkin(stack)) {
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

    private fun isPlaceableInsteadOfSkin(stack: ItemStack): Boolean {
        val blockItem = stack.item as? BlockItem ?: return true
        if (isKineticItem(stack)) return true
        if (com.simibubi.create.AllBlocks.DISPLAY_LINK.isIn(stack)) return true
        val shape = blockItem.block.defaultBlockState()
            .getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        return !net.minecraft.world.level.block.Block.isShapeFullBlock(shape)
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
        val be = level.getBlockEntity(pos) as? SlideAttachmentBlockEntity ?: return InteractionResult.PASS
        if (!com.simibubi.create.AllItems.WRENCH.isIn(player.mainHandItem)) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val returned = be.resetAttachmentMaterial()
        if (!returned.isEmpty) {
            if (!player.inventory.add(returned)) player.drop(returned, false)
        }
        return InteractionResult.SUCCESS
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    // pick and outline use the hub shape while collisions stay a full block
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
        const val HUB_MIN_VOXEL = 3.0
        const val HUB_MAX_VOXEL = 13.0

        private val HUB_SHAPE: VoxelShape = Block.box(
            HUB_MIN_VOXEL, HUB_MIN_VOXEL, HUB_MIN_VOXEL,
            HUB_MAX_VOXEL, HUB_MAX_VOXEL, HUB_MAX_VOXEL
        )
    }
}
