package net.omori_sunny.create_waterparked.content.entrance

import com.mojang.serialization.MapCodec
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.material.MapColor

// Slide entrance; water in front makes it active.
class WaterslideEntranceBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(WATER_ACTIVE, false)
        )
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = simpleCodec(::WaterslideEntranceBlock)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WaterslideEntranceBlockEntity(pos, state)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.clickedFace)

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        refreshWater(level, pos, state)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        refreshWater(level, pos, state)
    }

    fun refreshWater(level: Level, pos: BlockPos, state: BlockState) {
        val facing = state.getValue(FACING)
        val fluid = level.getFluidState(pos.relative(facing))
        val wet = fluid.`is`(Fluids.WATER) && fluid.isSource
        if (state.getValue(WATER_ACTIVE) != wet) {
            level.setBlock(pos, state.setValue(WATER_ACTIVE, wet), 3)
            net.omori_sunny.create_waterparked.game.water.WaterSlideConversion.propagateToAdjacentAnchors(level, pos, wet)
        }
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WATER_ACTIVE)
    }

    companion object {
        val FACING: DirectionProperty = DirectionProperty.create("facing", *Direction.values())
        val WATER_ACTIVE: BooleanProperty = BooleanProperty.create("water_active")

        fun defaultProperties(): BlockBehaviour.Properties =
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(1.5f)
                .sound(SoundType.COPPER)
                .noOcclusion()
    }
}
