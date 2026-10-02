package net.omori_sunny.create_waterparked.content.registry

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredRegister
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableBlockEntity
import net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableMenu
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModMenus {
    val REGISTRY: DeferredRegister<MenuType<*>> =
        DeferredRegister.create(Registries.MENU, CreateWaterparked.ID)

    val SLIDE_DRAFTING_TABLE: MenuType<SlideDraftingTableMenu> by
        REGISTRY.register("slide_drafting_table") { ->
            IMenuTypeExtension.create { containerId, inv, buf ->
                val pos = buf.readBlockPos()
                val be = inv.player.level().getBlockEntity(pos) as? SlideDraftingTableBlockEntity
                SlideDraftingTableMenu(
                    containerId, inv,
                    be ?: SlideDraftingTableBlockEntity(
                        ModBlockEntities.SLIDE_DRAFTING_TABLE_BE_TYPE,
                        pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    )
                )
            }
        }
}
