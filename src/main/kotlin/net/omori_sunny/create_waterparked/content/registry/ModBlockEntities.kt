package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerWorldShaftBlockEntity
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
        // set pendingType for the type swap mixin
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

    val WATERSLIDE_RIVET_BE: BlockEntityType<net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetBlockEntity> by
    REGISTRY.register("waterslide_rivet") { ->
        BlockEntityType.Builder.of(
            { pos, state ->
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetBlockEntity(pos, state)
            },
            ModBlocks.WATERSLIDE_RIVET
        ).build(null)
    }

    // carrier subclasses take their own type in the constructor, so bind it after build
    val ROLLER_CONVEYOR_BE: BlockEntityType<RollerConveyorBlockEntity> by
    REGISTRY.register("roller_conveyor") { ->
        var resolvedType: BlockEntityType<RollerConveyorBlockEntity>? = null
        val type = BlockEntityType.Builder.of(
            { pos, state -> RollerConveyorBlockEntity(resolvedType!!, pos, state) },
            ModBlocks.ROLLER_CONVEYOR
        ).build(null)
        resolvedType = type
        type
    }

    // the world shaft carries no state of its own beyond the run it drives
    val ROLLER_HINGE_SHAFT_BE: BlockEntityType<RollerWorldShaftBlockEntity> by
    REGISTRY.register("roller_hinge_shaft") { ->
        var resolvedType: BlockEntityType<RollerWorldShaftBlockEntity>? = null
        val type = BlockEntityType.Builder.of(
            { pos, state -> RollerWorldShaftBlockEntity(resolvedType!!, pos, state) },
            ModBlocks.ROLLER_HINGE_SHAFT
        ).build(null)
        resolvedType = type
        type
    }
}
