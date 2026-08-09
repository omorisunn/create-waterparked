package net.omori_sunny.create_waterparked.content.waterslide

import com.mojang.serialization.MapCodec
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlock
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor

// Waterslide anchor, reusing the CCS anchor behavior.
class WaterslideAnchorBlock(properties: BlockBehaviour.Properties) : CoasterAnchorpointBlock(properties) {

    override fun codec(): MapCodec<out BaseEntityBlock> = simpleCodec(::WaterslideAnchorBlock)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        var result: WaterslideAnchorBlockEntity? = null
        WaterslideAnchorBlockEntity.withPendingType(ModBlockEntities.WATERSLIDE_ANCHOR_BE) {
            result = WaterslideAnchorBlockEntity(pos, state)
        }
        return result!!
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T>? =
        createTickerHelper(type, ModBlockEntities.WATERSLIDE_ANCHOR_BE, WaterslideAnchorBlockEntity::tick)

    companion object {
        fun defaultProperties(): BlockBehaviour.Properties =
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(3.0f, 6.0f)
                .sound(SoundType.NETHERITE_BLOCK)
                .noOcclusion()
    }
}
