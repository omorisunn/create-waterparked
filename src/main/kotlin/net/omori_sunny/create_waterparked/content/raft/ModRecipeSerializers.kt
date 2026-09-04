package net.omori_sunny.create_waterparked.content.raft

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModRecipeSerializers {
    val REGISTRY: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, CreateWaterparked.ID)

    val DYE_BOAT: DeferredHolder<RecipeSerializer<*>, SimpleCraftingRecipeSerializer<InflatableBoat1x2DyeRecipe>> =
        REGISTRY.register("dye_inflatable_boat") { ->
            val factory = SimpleCraftingRecipeSerializer.Factory<InflatableBoat1x2DyeRecipe> { category ->
                InflatableBoat1x2DyeRecipe(category)
            }
            SimpleCraftingRecipeSerializer(factory)
        }
}
