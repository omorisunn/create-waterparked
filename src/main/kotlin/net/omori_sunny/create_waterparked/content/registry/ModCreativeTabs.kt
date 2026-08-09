package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModCreativeTabs {
    val REGISTRY: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateWaterparked.ID)

    val MAIN: CreativeModeTab by REGISTRY.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_waterparked"))
            .icon { ItemStack(ModItems.WATERSLIDE_TRACK) }
            .displayItems { _, output ->
                output.accept(ModItems.WATERSLIDE_TRACK)
                output.accept(ModItems.WATERSLIDE_ANCHOR)
                output.accept(ModItems.WATERSLIDE_ENTRANCE)
            }
            .build()
    }
}
