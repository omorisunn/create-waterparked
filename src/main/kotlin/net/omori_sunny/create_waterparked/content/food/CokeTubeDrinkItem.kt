package net.omori_sunny.create_waterparked.content.food

import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim

// a drink, not a meal: the food numbers stay as registered, only the hold-to-use animation reads as drinking,
// which also routes its use sounds through the vanilla drink sound on its own
class CokeTubeDrinkItem(properties: Properties) : Item(properties) {
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.DRINK
}
