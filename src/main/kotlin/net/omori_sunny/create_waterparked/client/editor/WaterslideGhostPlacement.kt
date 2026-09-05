package net.omori_sunny.create_waterparked.client.editor
// Ghost blocks: wall pick, place with a block item, destroy with an axe right-click.

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.GhostBlockEntry
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.network.WaterslideGhostMinePayload
import net.omori_sunny.create_waterparked.network.WaterslideGhostPlacePayload
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.abs

@OnlyIn(Dist.CLIENT)
object WaterslideGhostPlacement {

    private const val OUTLINE_KEY = "waterslide_ghost_hover"
    private const val MINE_SOUND_INTERVAL = 4

    data class GhostPick(
        val curve: BezierConnection,
        val spaceAnchor: BlockPos,
        val be: WaterslideAnchorBlockEntity,
        val peer: BlockPos,
        val t: Float,
        val angle: Float,
        val surfacePlot: Vec3,
        val cell: BlockPos,
        val worldSurface: Vec3,
        val worldCell: BlockPos
    )

    private class MiningState(
        var entry: GhostBlockEntry,
        var pick: GhostPick,
        var progress: Float,
        var ticks: Int
    )

    private var hovered: GhostPick? = null
    private var mining: MiningState? = null
    private var mineHover: GhostPick? = null
    private var crackStage = -1
    private var crackCell: BlockPos? = null
    private var minedRecentlyAt = 0L
    private var minedRecentlyCell: BlockPos? = null

    // ghost placement requires: main hand WRENCH + offhand BLOCK item; a lone
    // wrench in the main hand keeps its edit interactions (offhand empty)
    fun ghostPlacementStack(player: net.minecraft.world.entity.player.Player): ItemStack? {
        if (!player.mainHandItem.`is`(com.simibubi.create.AllItems.WRENCH.get())) return null
        val off = player.offhandItem
        if (off.item !is BlockItem) return null
        return off
    }

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.isShiftKeyDown) return
        if (WaterslideClipboardPaste.isActive()) return
        if (WaterslideClipboardPaste.isActive()) return
        if (player.mainHandItem.item is net.minecraft.world.item.AxeItem) {
            val pick = minePickAtCursor(mc) ?: return
            event.setCanceled(true)
            event.setSwingHand(true)
            beginMine(mc, pick)
            return
        }
        val stack = ghostPlacementStack(player) ?: return
        val pick = pickAtCursor(mc) ?: return
        event.setCanceled(true)
        event.setSwingHand(true)
        place(mc, pick, stack)
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        if (WaterslideClipboardPaste.isActive()) return
        if (player.mainHandItem.item is net.minecraft.world.item.AxeItem) {
            if (player.isShiftKeyDown) return
            val mc = Minecraft.getInstance()
            val pick = minePickAtCursor(mc) ?: return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            beginMine(mc, pick)
            return
        }
        val stack = ghostPlacementStack(player) ?: return
        if (player.isShiftKeyDown) return
        val mc = Minecraft.getInstance()
        val pick = pickAtCursor(mc) ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        place(mc, pick, stack)
    }

    @JvmStatic
    fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        val mc = Minecraft.getInstance()
        if (WaterslideClipboardPaste.isActive()) return
        if (player.mainHandItem.item is net.minecraft.world.item.AxeItem) {
            if (player.isShiftKeyDown) return
            val pick = minePickAtCursor(mc) ?: return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            beginMine(mc, pick)
            return
        }
        val stack = ghostPlacementStack(player) ?: return
        if (player.isShiftKeyDown) return
        val pick = pickAtCursor(mc) ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        place(mc, pick, stack)
    }

    @JvmStatic
    fun onAttackKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isAttack) return
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return
        if (minePickAtCursor(mc) == null) return
        event.setCanceled(true)
        event.setSwingHand(false)
    }

    @JvmStatic
    fun fakeGhostHit(mc: Minecraft): net.minecraft.world.phys.BlockHitResult? {
        val pick = mineHover ?: return null
        val player = mc.player ?: return null
        val view = player.getViewVector(1f)
        return net.minecraft.world.phys.BlockHitResult(
            player.eyePosition.add(view.scale(0.5)),
            net.minecraft.core.Direction.getNearest(view),
            pick.worldCell,
            false
        )
    }

    @JvmStatic
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        if (!event.level.isClientSide) return
        val mc = Minecraft.getInstance()
        if (minePickAtCursor(mc) == null) return
        event.isCanceled = true
        event.setUseItem(TriState.FALSE)
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()

        updateHover(mc, level, player)
        mineHover = minePickAtCursor(mc)
        tickMining(mc, player, level)
    }

    private var destroyHover: GhostPick? = null

    private fun updateHover(mc: Minecraft, level: Level, player: net.minecraft.world.entity.player.Player) {
        // placement outline matches the placement gate: wrench + offhand block
        val usable = ghostPlacementStack(player) != null && !player.isShiftKeyDown
        val pick = if (usable) pickAtCursor(mc) else null
        val destroyPick = if (player.mainHandItem.item is net.minecraft.world.item.AxeItem && !player.isShiftKeyDown &&
            !WaterslideClipboardPaste.isActive()
        )
            minePickAtCursor(mc)
        else
            null
        if (pick == null) {
            if (hovered != null) {
                CreateWaterparked.LOGGER.debug(
                    "WaterslideGhostPick: no hover (usable={})", usable
                )
            }
        } else if (pick != hovered) {
            CreateWaterparked.LOGGER.debug(
                "WaterslideGhostPick: sub={} curveSpaceSurface={} worldSurface={} curveSpaceCell={} worldCell={} curve={}-{} angle={} t={}",
                if (SableClientEdit.resolve(level, pick.spaceAnchor)?.sub != null) "yes" else "no",
                pick.surfacePlot, pick.worldSurface, pick.cell, pick.worldCell,
                pick.curve.bePositions.getFirst(), pick.curve.bePositions.getSecond(),
                pick.angle, pick.t
            )
        }
        if (pick == hovered && destroyPick == destroyHover) return
        hovered = pick
        destroyHover = destroyPick
        when {
            pick != null -> showHoverBox(pick, 0xFFFFFF)
            destroyPick != null -> showHoverBox(destroyPick, 0xFF0000)
            else -> Outliner.getInstance().remove(OUTLINE_KEY)
        }
    }

    private fun showHoverBox(pick: GhostPick, color: Int) {
        Outliner.getInstance().showAABB(OUTLINE_KEY, AABB(pick.worldCell).inflate(0.05))
            .colored(color)
            .lineWidth(0.0625f)
    }

    fun pickAtCursor(mc: Minecraft): GhostPick? {
        val player = mc.player ?: return null
        if (ghostPlacementStack(player) == null) return null
        if (player.isShiftKeyDown) return null
        if (player.mainHandItem.item is com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem) return null
        if (WaterslideClipboardPaste.isActive()) return null
        val pick = wallPickAtCursor(mc) ?: return null
        if (WaterslideSectorEdit.isPendingSectorEdit()) return null
        // no support-hover suppression: this pick only runs under the
        // wrench+offhand-block combo, and the support editor already yields
        // that combo - the old suppression here is what swallowed the click
        // whenever the wall hovered over support geometry
        val placed = WaterslideSectorLayout.place(pick.be.sectorConfigFor(pick.peer))
        val sector = WaterslideSectorLayout.sectorAt(placed, pick.angle) ?: return null
        if (sector.sector.material != SectorMaterial.BLOCK && sector.sector.material != SectorMaterial.OPEN) return null
        if (blockedByRealBlock(mc, pick)) return null
        if (pick.be.ghostBlockAtCell(pick.peer, pick.cell) != null) return null
        return pick
    }

    private fun blockedByRealBlock(mc: Minecraft, pick: GhostPick): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(pick.worldCell)
        return !state.isAir && !state.canBeReplaced()
    }

    private fun minePickAtCursor(mc: Minecraft): GhostPick? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (WaterslideSectorEdit.isPendingSectorEdit()) return null
        if (WaterslideSupportEdit.hoveredPick() != null) return null
        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        val rayEnd = eye.add(view.scale(6.0))
        var best: GhostPick? = null
        var bestD = Double.MAX_VALUE
        val anchors = LinkedHashMap<WaterslideAnchorBlockEntity, Boolean>()
        for (be in net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer.clientAnchors()) {
            if (!be.isRemoved) anchors[be] = true
        }
        for (pos in net.omori_sunny.create_waterparked.game.SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            if (!be.isRemoved) anchors[be] = true
        }
        for (be in anchors.keys) {
            val ctx = SableClientEdit.resolve(level, be.blockPos) ?: continue
            val sub = ctx.sub
            for ((peer, entries) in be.ghostBlocks) {
                val curve = ghostCurveForPeer(level, be, peer) ?: continue
                for (entry in entries) {
                    val worldCell = ghostWorldCell(level, curve, entry, sub) ?: continue
                    val hit = AABB(worldCell).inflate(0.02).clip(eye, rayEnd)
                    if (hit.isEmpty) continue
                    val d = eye.distanceTo(hit.get())
                    if (d >= bestD) continue
                    bestD = d
                    best = GhostPick(
                        curve = curve,
                        spaceAnchor = be.blockPos,
                        be = be,
                        peer = peer,
                        t = entry.t,
                        angle = entry.angle,
                        surfacePlot = Vec3.ZERO,
                        cell = entry.cell,
                        worldSurface = Vec3.atCenterOf(worldCell),
                        worldCell = worldCell
                    )
                }
            }
        }
        return best
    }

    private fun ghostCurveForPeer(level: Level, be: WaterslideAnchorBlockEntity, peer: BlockPos): BezierConnection? {
        val selfGlobal = SableClientEdit.resolve(level, be.blockPos)?.globalPos ?: return null
        for ((_, raw) in be.anchorPeerCurvesView) {
            val primary = if (raw.isPrimary) raw else raw.secondary() ?: continue
            if (!net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials.isWaterslide(primary)) continue
            val a = primary.bePositions.getFirst()
            val b = primary.bePositions.getSecond()
            val ctxA = SableClientEdit.resolve(level, a)?.globalPos
            val ctxB = SableClientEdit.resolve(level, b)?.globalPos
            val ours = (ctxA != null && ctxA == selfGlobal && b == peer) ||
                (ctxB != null && ctxB == selfGlobal && a == peer)
            if (ours) return primary
        }
        return null
    }

    private fun ghostWorldCell(
        level: Level,
        curve: BezierConnection,
        entry: GhostBlockEntry,
        sub: dev.ryanhcode.sable.sublevel.ClientSubLevel?
    ): BlockPos? {
        val a = curve.bePositions.getFirst()
        val b = curve.bePositions.getSecond()
        val r0 = ghostRadiusAt(level, a)
        val r1 = ghostRadiusAt(level, b)
        val radius = Mth.lerp(entry.t, r0, r1)
        val center = curve.getPosition(entry.t.toDouble())
        val tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames.unitTangentAt(curve, entry.t)
        val (lat, up) = net.omori_sunny.create_waterparked.game.SlideCurveGeometry.stableFrame(tangent)
        val outer = radius + (net.omori_sunny.create_waterparked.config.ModConfig.wallThickness() - 0.1f)
        val rad = Math.toRadians(entry.angle.toDouble())
        val dir = lat.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
        val seat = center.add(dir.scale((outer - 0.5).toDouble()))
        val world = if (sub != null)
            CoasterAnchorClientSpace.toRenderWorld(level, a, seat)
        else
            seat
        return BlockPos.containing(world)
    }

    private fun wallPickAtCursor(mc: Minecraft): GhostPick? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val realHit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
        if (realHit != null &&
            level.getBlockState(realHit.blockPos).block is net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
        ) return null
        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        var best: WaterslideSectorEdit.WallHit? = null
        var bestD = Double.MAX_VALUE
        var d = 0.0
        while (d <= 6.0) {
            val hit = WaterslideSectorEdit.resolveWallHit(level, eye.add(view.scale(d)))
            if (hit != null && d < bestD) {
                bestD = d
                best = hit
            }
            d += 0.075
        }
        val wall = best ?: return null
        return toGhostPick(level, wall)
    }

    private fun toGhostPick(level: Level, wall: WaterslideSectorEdit.WallHit): GhostPick? {
        val anchor = wall.spaceAnchor ?: return null
        val ctx = SableClientEdit.resolve(level, anchor) ?: return null
        val be = ctx.be
        val coarsePlot = wall.surfacePlot ?: return null
        val coarseWorld = if (ctx.sub != null)
            CoasterAnchorClientSpace.toRenderWorld(level, anchor, coarsePlot)
        else
            coarsePlot
        val player = Minecraft.getInstance().player
        val eye = player?.eyePosition ?: coarseWorld
        val view = player?.getViewVector(1f) ?: Vec3.ZERO
        val refinedWorld = net.omori_sunny.create_waterparked.client.render.WaterslideGhostRenderer
            .rayHitSurface(level, be, wall.curve, eye, view, BlockPos.containing(coarseWorld))
            ?: coarseWorld
        val surfacePlot = if (ctx.sub != null)
            SableClientEdit.worldToPlot(ctx.sub, refinedWorld)
        else
            refinedWorld
        val worldSurface = refinedWorld
        val seat = run {
            val t = wall.t
            val center = wall.curve.getPosition(t.toDouble())
            val tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames.unitTangentAt(wall.curve, t)
            val (lat, up) = net.omori_sunny.create_waterparked.game.SlideCurveGeometry.stableFrame(tangent)
            val rad = Math.toRadians(wall.angle.toDouble())
            val dir = lat.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
            surfacePlot.subtract(dir.scale(0.5))
        }
        val cell = BlockPos.containing(seat)
        val worldCell = if (ctx.sub != null)
            BlockPos.containing(CoasterAnchorClientSpace.toRenderWorld(level, anchor, seat))
        else
            BlockPos.containing(seat)
        val a = wall.curve.bePositions.getFirst()
        val b = wall.curve.bePositions.getSecond()
        val peer = otherEndpoint(level, ctx.globalPos, a, b)
        return GhostPick(
            curve = wall.curve,
            spaceAnchor = anchor,
            be = be,
            peer = peer,
            t = wall.t,
            angle = wall.angle,
            surfacePlot = surfacePlot,
            cell = cell,
            worldSurface = worldSurface,
            worldCell = worldCell
        )
    }

    private fun otherEndpoint(level: Level, selfGlobal: BlockPos, a: BlockPos, b: BlockPos): BlockPos {
        val ctxA = SableClientEdit.resolve(level, a)?.globalPos
        val ctxB = SableClientEdit.resolve(level, b)?.globalPos
        return when {
            ctxA != null && ctxA == selfGlobal -> b
            ctxB != null && ctxB == selfGlobal -> a
            else -> a
        }
    }

    private fun ghostRadiusAt(level: Level, pos: BlockPos): Float =
        SableClientEdit.resolve(level, pos)?.be?.radius ?: ModConfig.defaultSlideRadius()

    private fun place(mc: Minecraft, pick: GhostPick, held: net.minecraft.world.item.ItemStack) {
        val player = mc.player ?: return
        val blockItem = held.item as? BlockItem ?: return
        if (pick.be.ghostBlockCount(pick.peer) >= ModConfig.maxGhostBlocksPerCurve()) return
        if (pick.be.ghostBlockAtCell(pick.peer, pick.cell) != null) return
        val state = blockItem.block.defaultBlockState()

        PacketDistributor.sendToServer(
            WaterslideGhostPlacePayload(
                curveA = pick.curve.bePositions.getFirst(),
                curveB = pick.curve.bePositions.getSecond(),
                cell = pick.cell,
                stack = held.copyWithCount(1),
                t = pick.t,
                angle = pick.angle
            )
        )
        CreateWaterparked.LOGGER.debug(
            "WaterslideGhostPlacement: placed {} at {} on curve {}-{}",
            blockItem, pick.cell, pick.curve.bePositions.getFirst(), pick.curve.bePositions.getSecond()
        )
    }

    private fun beginMine(mc: Minecraft, pick: GhostPick) {
        val entry = pick.be.ghostBlockAtCell(pick.peer, pick.cell) ?: return
        if (mining != null && mining!!.pick.cell == pick.cell) return
        val now = System.currentTimeMillis()
        if (minedRecentlyCell == pick.cell && now - minedRecentlyAt < 1000) return
        if (mc.player?.isCreative == true) {
            finishMine(mc, entry, pick)
            return
        }
        mining = MiningState(entry, pick, 0f, 0)
        crackStage = 0
        crackCell = pick.worldCell
    }

    private fun tickMining(mc: Minecraft, player: net.minecraft.world.entity.player.Player, level: Level) {
        val mine = mining ?: return
        if (!mc.options.keyUse.isDown) {
            cancelMining()
            return
        }
        val pick = minePickAtCursor(mc)
        if (pick == null || pick.cell != mine.pick.cell) {
            cancelMining()
            if (pick != null) beginMine(mc, pick)
            return
        }
        val state = mine.entry.state
        val increment = if (player.isCreative) 1f
        else state.getDestroyProgress(player, level, mine.pick.worldCell) *
            dev.silvergold.simulatedcoasters.track.CoasterTrackGauge.survivalCurveDigScale(player)
        mine.progress += increment
        mine.ticks++
        if (mine.progress >= 1f) {
            finishMine(mc, mine.entry, mine.pick)
            return
        }
        crackStage = (mine.progress * 10).toInt().coerceIn(0, 9)
        crackCell = mine.pick.worldCell
    }

    private fun finishMine(mc: Minecraft, entry: GhostBlockEntry, pick: GhostPick) {
        mining = null
        crackStage = -1
        crackCell = null
        minedRecentlyCell = pick.cell
        minedRecentlyAt = System.currentTimeMillis()
        val state = entry.state
        PacketDistributor.sendToServer(
            WaterslideGhostMinePayload(
                curveA = pick.curve.bePositions.getFirst(),
                curveB = pick.curve.bePositions.getSecond(),
                cell = pick.cell
            )
        )
        CreateWaterparked.LOGGER.debug(
            "WaterslideGhostPlacement: mined ghost block at {} on curve {}-{}",
            pick.cell, pick.curve.bePositions.getFirst(), pick.curve.bePositions.getSecond()
        )
    }

    private fun cancelMining() {
        mining = null
        crackStage = -1
        crackCell = null
    }

    @JvmStatic
    fun crackState(): Pair<BlockPos, Int>? =
        crackCell?.let { it to crackStage }

    @JvmStatic
    fun clear() {
        hovered = null
        destroyHover = null
        mineHover = null
        mining = null
        crackStage = -1
        crackCell = null
        minedRecentlyCell = null
        minedRecentlyAt = 0L
        Outliner.getInstance().remove(OUTLINE_KEY)
    }
}