package net.omori_sunny.create_waterparked.content.sketch

import com.simibubi.create.foundation.block.IBE
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities

class SlideDraftingTableBlock(properties: Properties) : Block(properties), IBE<SlideDraftingTableBlockEntity> {

    override fun newBlockEntity(pos: BlockPos, state: BlockState) =
        SlideDraftingTableBlockEntity(ModBlockEntities.SLIDE_DRAFTING_TABLE_BE_TYPE, pos, state)

    override fun getBlockEntityType(): BlockEntityType<SlideDraftingTableBlockEntity> =
        ModBlockEntities.SLIDE_DRAFTING_TABLE_BE_TYPE

    override fun getBlockEntityClass(): Class<SlideDraftingTableBlockEntity> =
        SlideDraftingTableBlockEntity::class.java

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SlideDraftingTableBlockEntity ?: return InteractionResult.FAIL
            player.openMenu(be, { buf -> buf.writeBlockPos(pos) })
        }
        return InteractionResult.SUCCESS
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING)
    }

    companion object {
        fun defaultProperties(): Properties = Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f, 6.0f)
            .sound(SoundType.WOOD)
            .noOcclusion()
    }
}
