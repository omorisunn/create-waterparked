package net.omori_sunny.create_waterparked.game.physics

import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

// Player slide control (server-side).
object PlayerSlideController {

    private val slidingPlayers = mutableSetOf<UUID>()

    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        for (level in event.server.allLevels) {
            for (entity in level.players()) {
                if (entity is ServerPlayer) {
                    tickPlayer(entity)
                }
            }
        }
    }

    private fun tickPlayer(player: ServerPlayer) {
        val level = player.level()
        val state = level.getBlockState(player.blockPosition())
        val entrance = state.block as? WaterslideEntranceBlock
        val onWetEntrance = entrance != null && state.getValue(WaterslideEntranceBlock.WATER_ACTIVE)

        if (onWetEntrance) {
            slidingPlayers += player.uuid
            val facing = state.getValue(WaterslideEntranceBlock.FACING)
            val into = Vec3.atLowerCornerOf(facing.normal)
            val boost = ModConfig.entranceBoost().toFloat()
            val vel = player.deltaMovement
            player.deltaMovement = vel.add(into.scale(boost.toDouble() * 0.05))
        } else {
            slidingPlayers -= player.uuid
        }

        if (player.isShiftKeyDown) {
            slidingPlayers -= player.uuid
        }

        // Slide friction from config.
        if (player.uuid in slidingPlayers && nearbySlideAnchor(level, player.blockPosition())) {
            val vel = player.deltaMovement
            val friction = ModConfig.slideFriction().toFloat()
            player.deltaMovement = Vec3(
                vel.x * friction,
                vel.y,
                vel.z * friction
            )
        }
    }

    private fun nearbySlideAnchor(level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos): Boolean {
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (level.getBlockEntity(pos.offset(dx, dy, dz)) is WaterslideAnchorBlockEntity) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
