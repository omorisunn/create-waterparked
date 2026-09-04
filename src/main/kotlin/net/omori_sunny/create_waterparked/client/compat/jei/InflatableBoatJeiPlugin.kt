package net.omori_sunny.create_waterparked.client.compat.jei

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter
import mezz.jei.api.ingredients.subtypes.UidContext
import mezz.jei.api.registration.ISubtypeRegistration
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModItems

// JEI treats each dyed inflatable_boat color as its own subtype, so 16 colors show up
// separately instead of being logged as duplicates.
@JeiPlugin
class InflatableBoatJeiPlugin : IModPlugin {

    override fun getPluginUid(): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "jei_plugin")

    override fun registerItemSubtypes(registration: ISubtypeRegistration) {
        registration.registerSubtypeInterpreter(
            ModItems.INFLATABLE_BOAT_1X2,
            object : ISubtypeInterpreter<ItemStack> {
                override fun getSubtypeData(stack: ItemStack, context: UidContext): String {
                    val color = stack.get(DataComponents.DYED_COLOR)
                    return color?.rgb()?.toString() ?: "white"
                }

                override fun getLegacyStringSubtypeInfo(stack: ItemStack, context: UidContext): String =
                    getSubtypeData(stack, context)
            }
        )
    }
}
