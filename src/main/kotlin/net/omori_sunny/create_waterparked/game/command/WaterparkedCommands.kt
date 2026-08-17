package net.omori_sunny.create_waterparked.game.command

import net.omori_sunny.create_waterparked.game.water.ContraptionWaterSimulation
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.function.Consumer

// /waterparked refresh - recompute the physics water fields for every loaded
// watered slide (main world + Sable sub-levels + mounted contraption slides)
// and resend them to all players.
object WaterparkedCommands {

    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(
            Consumer { event: RegisterCommandsEvent ->
                event.dispatcher.register(
                    Commands.literal("waterparked")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.literal("refresh")
                                .executes { ctx ->
                                    val level = ctx.source.level
                                    if (level is ServerLevel) {
                                        ServerWaterSimulation.refresh(level)
                                        ContraptionWaterSimulation.refresh(level)
                                        ctx.source.sendSuccess(
                                            { Component.literal("Waterparked: recomputed water fields for all loaded slides") },
                                            true
                                        )
                                    }
                                    1
                                }
                        )
                )
            }
        )
    }
}