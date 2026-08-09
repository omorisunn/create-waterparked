package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateWaterparked.ID)

    val WATERSLIDE_ANCHOR_BE: BlockEntityType<WaterslideAnchorBlockEntity> by
    REGISTRY.register("waterslide_anchor") { ->
        // Set pendingType for the type-swap mixin.
        var resolvedType: BlockEntityType<WaterslideAnchorBlockEntity>? = null
        val type = BlockEntityType.Builder.of(
            { pos, state ->
                val pending = resolvedType
                if (pending != null) {
                    var be: WaterslideAnchorBlockEntity? = null
                    WaterslideAnchorBlockEntity.withPendingType(pending) {
                        be = WaterslideAnchorBlockEntity(pos, state)
                    }
                    be!!
                } else {
                    WaterslideAnchorBlockEntity(pos, state)
                }
            },
            ModBlocks.WATERSLIDE_ANCHOR
        ).build(null)
        resolvedType = type
        type
    }

    val WATERSLIDE_ENTRANCE_BE: BlockEntityType<WaterslideEntranceBlockEntity> by
    REGISTRY.register("waterslide_entrance") { ->
        BlockEntityType.Builder.of(::WaterslideEntranceBlockEntity, ModBlocks.WATERSLIDE_ENTRANCE).build(null)
    }
}
