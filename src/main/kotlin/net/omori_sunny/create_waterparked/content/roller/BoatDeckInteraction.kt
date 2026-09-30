package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import kotlin.math.abs

// a boat lying on a deck comes back out on a plain right click, and sneak takes it into the inventory
object BoatDeckInteraction {

    private const val REACH = 0.55f

    @JvmStatic
    fun takeBoat(level: Level, pos: BlockPos, player: Player): Boolean {
        val segment = level.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return false
        val inventory = segment.inventory ?: return false
        val centre = segment.index + .5f
        if (!hasBoatNear(inventory, centre)) return false
        if (level.isClientSide) return true

        if (player.isShiftKeyDown) {
            inventory.applyToEachWithin(centre, REACH) { transported ->
                if (transported.stack.item !is InflatableBoat1x2Item) return@applyToEachWithin null
                player.inventory.placeItemBackInInventory(transported.stack.copy())
                TransportedResult.removeItem()
            }
        } else {
            var spawned = false
            inventory.applyToEachWithin(centre, REACH) { transported ->
                if (spawned || transported.stack.item !is InflatableBoat1x2Item) return@applyToEachWithin null
                spawned = true
                val boat = InflatableBoat1x2Entity(ModEntityTypes.INFLATABLE_BOAT_1X2, level)
                boat.setPos(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                val dye = transported.stack.get(DataComponents.DYED_COLOR)
                boat.color = dye?.rgb() ?: 0xFFFFFF
                level.addFreshEntity(boat)
                player.startRiding(boat)
                TransportedResult.removeItem()
            }
        }

        level.playSound(
            null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
            0.2f, 1.0f + level.random.nextFloat()
        )
        return true
    }

    private fun hasBoatNear(inventory: RollerDeckInventory, centre: Float): Boolean =
        inventory.transportedItems.any {
            !it.stack.isEmpty && it.stack.item is InflatableBoat1x2Item && abs(it.beltPosition - centre) <= REACH
        }
}
