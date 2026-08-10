package net.omori_sunny.create_waterparked.game

import com.simibubi.create.AllDataComponents
import com.simibubi.create.AllSoundEvents
import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.graph.CoasterTrackPropagator
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.network.WaterslideHotbarSelectionSyncPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

// Slide connection state machine.
object WaterslideTrackPlacement {

    fun setAnchorFirstSelection(stack: ItemStack, anchor: BlockPos) {
        stack.remove(AllDataComponents.TRACK_CONNECTING_FROM)
        stack.set(ModDataComponents.CONNECTING_FROM, anchor.immutable())
    }

    fun clearAnchorFirstSelection(stack: ItemStack) {
        stack.remove(ModDataComponents.CONNECTING_FROM)
        stack.remove(AllDataComponents.TRACK_CONNECTING_FROM)
        stack.remove(AllDataComponents.TRACK_EXTENDED_CURVE)
    }

    fun readAnchorFirstSelection(stack: ItemStack): BlockPos? {
        stack.get(ModDataComponents.CONNECTING_FROM)?.let { return it.immutable() }
        val from = stack.get(AllDataComponents.TRACK_CONNECTING_FROM) ?: return null
        return from.pos().immutable()
    }

    fun hasAnchorFirstSelection(stack: ItemStack): Boolean =
        stack.item is WaterslideTrackItem &&
            (stack.has(ModDataComponents.CONNECTING_FROM) || stack.has(AllDataComponents.TRACK_CONNECTING_FROM))

    fun clearPendingConnection(stack: ItemStack) {
        clearAnchorFirstSelection(stack)
    }

    fun applyAnchorFirstSelection(
        level: Level,
        stack: ItemStack,
        anchor: BlockPos,
        player: Player?,
        hand: InteractionHand
    ) {
        setAnchorFirstSelection(stack, anchor.immutable())
        level.playSound(null, anchor, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1.0f)
        if (!level.isClientSide && player != null) {
            player.setItemInHand(hand, stack)
            if (player is ServerPlayer) {
                syncMainHand(player, hand, anchor)
            }
        }
    }

    fun syncMainHand(player: ServerPlayer, hand: InteractionHand, anchorFirstOrNull: BlockPos?) {
        val stack = player.getItemInHand(hand)
        if (stack.item !is WaterslideTrackItem) return
        player.setItemInHand(hand, stack)
        if (hand == InteractionHand.OFF_HAND) return
        WaterslideHotbarSelectionSyncPayload.broadcast(
            player,
            player.inventory.selected,
            anchorFirstOrNull?.asLong() ?: 0L
        )
    }

    fun clearServerTrackSelection(player: ServerPlayer, hand: InteractionHand) {
        val stack = player.getItemInHand(hand)
        if (stack.item !is WaterslideTrackItem) return
        clearPendingConnection(stack)
        player.setItemInHand(hand, stack)
        syncMainHand(player, hand, null)
    }

    fun commitSecondAnchorOnServer(
        player: ServerPlayer,
        hand: InteractionHand,
        firstAnchor: BlockPos,
        secondAnchor: BlockPos
    ): Boolean {
        if (firstAnchor == BlockPos.ZERO || firstAnchor == secondAnchor) return false
        val level = player.serverLevel()
        var stack = player.getItemInHand(hand)
        if (stack.item !is WaterslideTrackItem) return false

        val beA = level.getBlockEntity(firstAnchor) as? WaterslideAnchorBlockEntity
        if (beA?.viewAnchorPeerCurvesSnapshot()?.containsKey(secondAnchor) == true) {
            clearServerTrackSelection(player, hand)
            return true
        }

        val existing = readAnchorFirstSelection(stack)
        if (existing == null || existing != firstAnchor) {
            applyAnchorFirstSelection(level, stack, firstAnchor, player, hand)
            stack = player.getItemInHand(hand)
        }

        val ok = tryConnectSecondAnchorOnServer(level, player, stack, secondAnchor, hand)
        if (!ok) {
            clearServerTrackSelection(player, hand)
        }
        return ok
    }

    fun tryConnectSecondAnchorOnServer(
        level: ServerLevel,
        player: Player,
        stack: ItemStack,
        secondAnchor: BlockPos,
        hand: InteractionHand
    ): Boolean {
        if (stack.item !is WaterslideTrackItem) return false
        if (!hasAnchorFirstSelection(stack)) return false
        val first = readAnchorFirstSelection(stack) ?: return false
        if (first == secondAnchor) return false

        val state = level.getBlockState(secondAnchor)
        if (state.block !is WaterslideAnchorBlock) return false
        val beB = level.getBlockEntity(secondAnchor) as? WaterslideAnchorBlockEntity ?: return false
        if (beB.legCount() >= 2) return false

        val result = WaterslideConnectionRules.validate(level, first, secondAnchor)
        if (!result.valid) {
            if (!level.isClientSide) {
                AllSoundEvents.DENY.playFrom(player, 1.0f, 1.0f)
            }
            return false
        }

        val placement = CoasterAnchorBezierOptimizer.buildAnchorAnchorPlacement(
            level, first, secondAnchor, WaterslideTrackMaterials.WATERSLIDE, false
        ) ?: return false
        val primary = placement.primary()
        if (!CoasterAnchorBezierOptimizer.isBuiltPlacementCurveValid(primary)) return false
        val beA = level.getBlockEntity(first) as? WaterslideAnchorBlockEntity ?: return false

        CoasterTrackPropagator.runBatchUpdate(level, Runnable {
            val smoothing = WaterslideNeighborSmoothing.build(level, first, secondAnchor, primary, placement)
            val finalCurve = smoothing?.primary ?: primary
            beA.putAnchorPeerCurve(level, secondAnchor, finalCurve)
            if (smoothing != null) {
                WaterslideNeighborSmoothing.commitNeighbors(level, smoothing.neighbors)
            }
        })
        beA.initCurveSectorConfig(level, secondAnchor)
        logJunction(level, first, secondAnchor, placement)
        CreateWaterparked.LOGGER.info("Slide connected {} -> {}", first, secondAnchor)

        clearPendingConnection(stack)
        player.setItemInHand(hand, stack)
        if (player is ServerPlayer) {
            syncMainHand(player, hand, null)
        }
        return true
    }

// junction alignment check
    private fun logJunction(
        level: ServerLevel,
        a: BlockPos,
        b: BlockPos,
        result: CoasterAnchorBezierOptimizer.AnchorAnchorBuildResult
    ) {
        val dots = result.neighborJoinWrites.mapNotNull { write ->
            val raw = (level.getBlockEntity(write.sharedAnchor()) as? CoasterAnchorpointBlockEntity)
                ?.getAnchorPeerCurvesView()?.get(write.remotePos()) ?: return@mapNotNull null
            val bc = if (raw.isPrimary) raw else raw.secondary()
            val committed = (level.getBlockEntity(a) as? CoasterAnchorpointBlockEntity)
                ?.getAnchorPeerCurvesView()?.get(b) ?: return@mapNotNull null
            val primaryBc = if (committed.isPrimary) committed else committed.secondary()
            val nAxis = axisAt(bc, write.sharedAnchor()) ?: return@mapNotNull null
            val pAxis = axisAt(primaryBc, write.sharedAnchor()) ?: return@mapNotNull null
            nAxis.normalize().dot(pAxis.normalize())
        }
        CreateWaterparked.LOGGER.info(
            "Slide junction {} -> {}: smoothedNeighbors={} axisDot={}",
            a, b, result.neighborJoinWrites.size, dots
        )
    }

    private fun axisAt(bc: BezierConnection, anchor: BlockPos): Vec3? {
        return if (bc.bePositions.getFirst() == anchor) {
            bc.axes.getFirst()
        } else if (bc.bePositions.getSecond() == anchor) {
            bc.axes.getSecond()
        } else {
            null
        }
    }
}
