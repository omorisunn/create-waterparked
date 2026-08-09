package net.omori_sunny.create_waterparked.content.entrance

import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class WaterslideEntranceBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.WATERSLIDE_ENTRANCE_BE, pos, state)
