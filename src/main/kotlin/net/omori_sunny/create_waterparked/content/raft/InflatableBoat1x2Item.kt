package net.omori_sunny.create_waterparked.content.raft

import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

// spawns a colored InflatableBoat1x2Entity where the player right-clicks (water or block)
class InflatableBoat1x2Item(properties: Properties) : Item(properties) {

    // colored name prefix like the Aeronautics envelopes, e.g. "红色充气船 1x2"
    override fun getName(stack: ItemStack): Component {
        val base = super.getName(stack)
        val rgb = stack.get(DataComponents.DYED_COLOR)?.rgb() ?: return base
        val dye = DyeColor.entries.firstOrNull { it.textureDiffuseColor == rgb } ?: return base
        return Component.translatable("color.minecraft." + dye.serializedName).append(" ").append(base)
    }

    // package-style drops: every world-spawned item entity of the boat becomes
    // the boat entity (player toss, belt eject, funnel drop, loot), exactly
    // like PackageItem.createEntity -> PackageEntity.fromDroppedItem
    override fun hasCustomEntity(stack: ItemStack): Boolean = true

    override fun createEntity(world: Level, location: Entity, itemstack: ItemStack): Entity? {
        if (location !is ItemEntity) return null
        val boat = InflatableBoat1x2Entity(ModEntityTypes.INFLATABLE_BOAT_1X2, world)
        boat.setPos(location.x, location.y, location.z)
        boat.yRot = location.yRot
        // packages keep their toss momentum scaled up, same here
        boat.deltaMovement = location.deltaMovement.scale(1.5)
        boat.color = itemstack.get(DataComponents.DYED_COLOR)?.rgb() ?: 0xFFFFFF
        return boat
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level.isClientSide) return InteractionResult.SUCCESS
        val loc = context.clickLocation
        val color = context.itemInHand.get(DataComponents.DYED_COLOR)?.rgb() ?: 0xFFFFFF
        val entity = InflatableBoat1x2Entity(ModEntityTypes.INFLATABLE_BOAT_1X2, level)
        // spawn half a block above the click position so the boat can settle down
        entity.moveTo(loc.x, loc.y + 0.5, loc.z, context.rotation, 0f)
        entity.color = color
        level.addFreshEntity(entity)
        return InteractionResult.SUCCESS
    }
}
