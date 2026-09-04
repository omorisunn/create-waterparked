package net.omori_sunny.create_waterparked.content.raft

import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.Level

// shapeless crafting with any DyeItem: boat + dye -> dyed boat (aeronautics style,
// but done in the crafting grid). The dyed color is copied from the dye at runtime.
class InflatableBoat1x2DyeRecipe(category: CraftingBookCategory) : CustomRecipe(category) {

    // matches exactly one boat plus exactly one dye item
    override fun matches(input: CraftingInput, level: Level): Boolean {
        var boat = false
        var dye = false
        for (i in 0 until input.size()) {
            val stack = input.getItem(i)
            if (stack.isEmpty) continue
            if (stack.`is`(ModItems.INFLATABLE_BOAT_1X2)) {
                if (boat) return false
                boat = true
            } else if (stack.item is DyeItem) {
                if (dye) return false
                dye = true
            } else {
                return false
            }
        }
        return boat && dye
    }

    override fun assemble(input: CraftingInput, registries: net.minecraft.core.HolderLookup.Provider): ItemStack {
        var dye: DyeColor? = null
        for (i in 0 until input.size()) {
            val stack = input.getItem(i)
            if (!stack.isEmpty && stack.item is DyeItem) {
                dye = (stack.item as DyeItem).dyeColor
                break
            }
        }
        val out = ItemStack(ModItems.INFLATABLE_BOAT_1X2)
        out.set(
            DataComponents.DYED_COLOR,
            net.minecraft.world.item.component.DyedItemColor(dye?.textureDiffuseColor ?: 0xFFFFFF, true)
        )
        return out
    }

    override fun canCraftInDimensions(width: Int, height: Int): Boolean = width * height >= 2

    override fun getSerializer(): RecipeSerializer<*> = ModRecipeSerializers.DYE_BOAT.get()
}
