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

// Lets waterslide track + anchor blocks assemble onto Create contraptions.
//
// Create's fallback movement checks are hostile to rail-shaped blocks:
//  - isMovementNecessaryFallback returns false for blocks whose collision shape
//    is empty, and elevated track shapes (AE/AW/AN/AS) are exactly that. Blocks
//    that are "not necessary" are silently skipped by BOTH the super-glue group
//    search AND the assembly BFS, before the movement-allowed query even runs.
//  - isMovementAllowedFallback refuses any ITrackBlock.
// Both waterslide blocks therefore register their own explicit checks:
//  - MovementNecessaryCheck: always declare the slide blocks necessary to move.
//  - MovementAllowedCheck: run before the fallback so the ITrackBlock refusal
//    never applies to them.
//  - AttachedCheck: the fallback knows neither block, so the BFS would only
//    capture them when a glue entity happens to span the boundary face. Declaring
//    them "attached" lets the BFS drag them off any adjacent already-captured
//    block.
// The anchor additionally gets a MovementBehaviour (created by the client actor
// visual) so its tube + in-tube water render on the move.
object WaterslideContraptionIntegration {

    // onCommonSetup is subscribed twice in this mod (once by the
    // @EventBusSubscriber annotation and once via MOD_BUS.addListener), so
    // registration must be idempotent: Create's SimpleRegistry throws on
    // duplicate values.
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

        // Attached-toward: lets the assembly BFS drag a waterslide block off any
        // adjacent already-captured block, even without a glue entity on the face.
        BlockMovementChecks.registerAttachedCheck(
            BlockMovementChecks.AttachedCheck { state: BlockState, _: Level, _: BlockPos, _: Direction ->
                if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
            }
        )

        // Anchor-only behaviour: rails come from Create's automatic block
        // models; the tube + in-tube water come from the anchor actor visual.
        MovementBehaviour.REGISTRY.register(
            ModBlocks.WATERSLIDE_ANCHOR,
            WaterslideContraptionBehaviour
        )

        // CCS (Create Coasters Simulated) registers its own MovementAllowedCheck
        // via enqueueWork during common setup, returning FAIL for every
        // CoasterAnchorpointBlock (its "coaster anchors belong to sub-level
        // assembly, not Create contraptions" guard). Create queries registered
        // checks newest-first, so CCS's FAIL hides our SUCCESS and the assembly
        // BFS silently skips our anchor's cell. Re-register our check when the
        // server starts - by then every mod's common setup (including
        // enqueueWork) has finished, so our SUCCESS is checked before CCS's
        // FAIL. We only claim WaterslideAnchorBlock; plain CCS anchors keep
        // CCS's restriction.
        NeoForge.EVENT_BUS.addListener(
            Consumer { _: ServerStartedEvent ->
                BlockMovementChecks.registerMovementAllowedCheck(
                    BlockMovementChecks.MovementAllowedCheck { state: BlockState, _: Level, _: BlockPos ->
                        if (isWaterslide(state)) CheckResult.SUCCESS else CheckResult.PASS
                    }
                )
            }
        )

        // Track slide-carrying contraptions for structure-velocity tracking and
        // the one-time contraption-internal water computation at assembly.
        ContraptionSlideSpaces.registerEvents()
    }
}