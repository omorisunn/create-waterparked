package net.omori_sunny.create_waterparked.game.contraption

import com.simibubi.create.api.behaviour.movement.MovementBehaviour
import com.simibubi.create.api.contraption.BlockMovementChecks
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackBlock
import net.omori_sunny.create_waterparked.game.physics.ContraptionSlideSpaces
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import java.util.function.Consumer

// registers explicit movement checks so slide blocks assemble onto contraptions
object WaterslideContraptionIntegration {

    // registration must be idempotent, common setup runs twice
    @Volatile
    private var registered = false

    private fun isWaterslide(state: BlockState): Boolean {
        val block = state.block
        return block is WaterslideTrackBlock || block is WaterslideAnchorBlock
    }

    fun register() {
        if (registered) return
        registered = true

        BlockMovementChecks.registerMovementNecessaryCheck(
            BlockMovementChecks.MovementNecessaryCheck { state: BlockState, _: Level, _: BlockPos ->
                if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
            }
        )

        BlockMovementChecks.registerMovementAllowedCheck(
            BlockMovementChecks.MovementAllowedCheck { state: BlockState, _: Level, _: BlockPos ->
                if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
            }
        )

        // attached lets the BFS drag slide blocks off captured ones
        BlockMovementChecks.registerAttachedCheck(
            BlockMovementChecks.AttachedCheck { state: BlockState, _: Level, _: BlockPos, _: Direction ->
                if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
            }
        )

        // anchor only behaviour, the tube comes from the actor visual
        MovementBehaviour.REGISTRY.register(
            ModBlocks.WATERSLIDE_ANCHOR,
            WaterslideContraptionBehaviour
        )

        // re register after server start so our success beats CCS fail
        NeoForge.EVENT_BUS.addListener(
            Consumer { _: ServerStartedEvent ->
                BlockMovementChecks.registerMovementAllowedCheck(
                    BlockMovementChecks.MovementAllowedCheck { state: BlockState, _: Level, _: BlockPos ->
                        if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
                    }
                )
            }
        )

        // track slide contraptions for velocity and the one time water field
        ContraptionSlideSpaces.registerEvents()
    }
}