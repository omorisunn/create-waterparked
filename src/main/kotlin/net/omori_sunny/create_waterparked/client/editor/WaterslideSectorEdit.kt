package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.simibubi.create.AllItems
import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlayRenderTypes
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.client.track.EndpointHandleTextures
import dev.silvergold.simulatedcoasters.client.track.BezierHandleTangentTextures
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlock
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.SectorType
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.network.SectorEditAction
import net.omori_sunny.create_waterparked.network.WaterslideSectorEditPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max

// Client-side sector editing.
@OnlyIn(Dist.CLIENT)
object WaterslideSectorEdit {

    private const val PICK_RADIUS = 0.3
    private const val CONTROL_RING_SCALE = 1.35f
    private const val WALL_SEGMENTS = 24
    private const val KEY_PENDING = "waterslide_sector_pending"
    private val previewConfigs = mutableMapOf<Pair<Long, Long>, WaterslideSectorConfig>()
    private var dragging = false
    private var draggingBoundary = false
    private var dragCurveKey: Pair<Long, Long>? = null
    private var dragSectorId = -1
    private var dragBoundarySectorId = -1
    // Eased drag angle.
    private var currentDragAngle = 0f
    private var currentBoundaryAngle = 0f
    private var pendingBlockAnchor: BlockPos? = null

    fun previewConfigFor(a: BlockPos, b: BlockPos): WaterslideSectorConfig? =
        previewConfigs[curveKey(a, b)]

    // Right-click add/delete.

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity ?: return
        if (AllItems.WRENCH.isIn(player.mainHandItem)) return

        val mainBlockId = (player.mainHandItem.item as? BlockItem)?.let {
            BuiltInRegistries.BLOCK.getKey(it.block)
        }

        if (!event.level.isClientSide) {
// block vanilla placement
            if (mainBlockId != null && !player.isShiftKeyDown &&
                event.level.getBlockState(event.pos).block is CoasterAnchorpointBlock
            ) {
                event.isCanceled = true
                event.cancellationResult = InteractionResult.SUCCESS
            }
            return
        }

        // Two-step block add.
        if (mainBlockId != null) {
            handleBlockAdd(event, player, mainBlockId)
            return
        }

// add open / delete sector
        if (!AllItems.WRENCH.isIn(player.offhandItem)) return
        val hitVec = event.hitVec?.location ?: return
        val hit = resolveWallHit(event.level, hitVec) ?: return
        val action = when {
            player.isShiftKeyDown -> SectorEditAction.DELETE
            else -> SectorEditAction.ADD_OPEN
        }

        sendSector(event.level, hit.curve, action, hit.angle, null)
        CreateWaterparked.LOGGER.debug(
            "WaterslideSectorEdit: sent {} angle={}",
            action, hit.angle
        )
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun handleBlockAdd(
        event: PlayerInteractEvent.RightClickBlock,
        player: net.minecraft.world.entity.player.Player,
        blockId: net.minecraft.resources.ResourceLocation
    ) {
        val level = event.level
        val pos = event.pos
        val pending = pendingBlockAnchor
        CreateWaterparked.LOGGER.debug(
            "WaterslideSectorEdit: blockAdd at {} pending={} block={}",
            pos, pending, blockId
        )

// sneak = vanilla place
        if (player.isShiftKeyDown) {
            clearPending(level)
            return
        }

        if (pending == null) {
            // First click selects the anchor.
            if (level.getBlockState(pos).block !is CoasterAnchorpointBlock) {
                CreateWaterparked.LOGGER.debug(
                    "WaterslideSectorEdit: first click not an anchor at {} state={}",
                    pos, level.getBlockState(pos)
                )
                return
            }
            pendingBlockAnchor = pos.immutable()
            showPendingBox(level, pos)
            spawnParticles(level, Vec3.atCenterOf(pos), 12)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }

        // Second click targets the other anchor.
        if (level.getBlockState(pos).block is CoasterAnchorpointBlock) {
            if (pos == pending) {
                clearPending(level)
                event.isCanceled = true
                event.cancellationResult = InteractionResult.SUCCESS
                return
            }
            val curve = findCurveByAnchors(level, pending, pos)
            CreateWaterparked.LOGGER.debug(
                "WaterslideSectorEdit: second anchor click {} -> {} curve={}",
                pending, pos, curve
            )
            if (curve == null) {
                // No curve between anchors.
                player.displayClientMessage(
                    Component.translatable("create_waterparked.track.must_attach_to_slide_anchors")
                        .withStyle(ChatFormatting.RED),
                    true
                )
                event.isCanceled = true
                event.cancellationResult = InteractionResult.SUCCESS
                return
            }
// insert at 0 degrees
            optimisticAdd(level, curve, SectorEditAction.ADD_BLOCK, 0f, blockId)
            sendSector(level, curve, SectorEditAction.ADD_BLOCK, 0f, blockId)
            spawnParticles(level, Vec3.atCenterOf(pending), 8)
            spawnParticles(level, Vec3.atCenterOf(pos), 8)
            clearPending(level)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }

// insert at hit angle
        val hitVec = event.hitVec?.location ?: run {
            clearPending(level)
            return
        }
        val hit = resolveWallHit(level, hitVec, requireAnchor = pending)
        if (hit == null) {
            clearPending(level)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }
        optimisticAdd(level, hit.curve, SectorEditAction.ADD_BLOCK, hit.angle, blockId)
        sendSector(level, hit.curve, SectorEditAction.ADD_BLOCK, hit.angle, blockId)
        spawnParticles(level, hitVec, 10)
        clearPending(level)
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun sendSector(
        level: Level,
        curve: BezierConnection,
        action: SectorEditAction,
        angle: Float,
        blockId: net.minecraft.resources.ResourceLocation?
    ) {
        PacketDistributor.sendToServer(
            WaterslideSectorEditPayload(
                curveA = curve.bePositions.getFirst(),
                curveB = curve.bePositions.getSecond(),
                action = action,
                angleDegrees = angle,
                blockId = blockId
            )
        )
    }

// local preview
    private fun optimisticAdd(
        level: Level,
        curve: BezierConnection,
        action: SectorEditAction,
        angle: Float,
        blockId: net.minecraft.resources.ResourceLocation?
    ) {
        val key = curveKey(curve.bePositions.getFirst(), curve.bePositions.getSecond())
        val base = configForCurve(level, key) ?: return
        if (base.sectors.size >= ModConfig.maxSectors()) return
        val config = base.copyOf()
        val placed = WaterslideSectorLayout.place(config.sectors)
        val insertIndex = WaterslideSectorLayout.insertionIndex(placed, angle)
        val material = if (action == SectorEditAction.ADD_BLOCK) SectorMaterial.BLOCK else SectorMaterial.OPEN
        config.sectors.add(
            insertIndex,
            WaterslideSector(
                id = config.newId(),
                material = material,
                blockId = if (action == SectorEditAction.ADD_BLOCK) blockId else null,
                type = SectorType.AUTO,
                widthDegrees = 0f
            )
        )
        previewConfigs[key] = config
    }

// wall hit test
    private fun resolveWallHit(
        level: Level,
        hitVec: Vec3,
        requireAnchor: BlockPos? = null
    ): WallHit? {
        var best: WallHit? = null
        var bestScore = Double.MAX_VALUE
        val seen = mutableSetOf<Pair<Long, Long>>()
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val primary = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(primary)) continue
                val key = curveKey(primary.bePositions.getFirst(), primary.bePositions.getSecond())
                if (!seen.add(key)) continue
                if (requireAnchor != null &&
                    primary.bePositions.getFirst() != requireAnchor &&
                    primary.bePositions.getSecond() != requireAnchor
                ) continue

                val r0 = radiusAt(level, primary.bePositions.getFirst())
                val r1 = radiusAt(level, primary.bePositions.getSecond())
                val samples = max(64, primary.getSegmentCount() * 4)
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val center = primary.getPosition(t.toDouble())
                    val rel = hitVec.subtract(center)
                    val dist = rel.length()
                    val radius = Mth.lerp(t, r0, r1)
// wall surface
                    if (dist > radius + 0.4) continue
                    val score = abs(dist - radius)
                    if (score >= bestScore) continue
                    bestScore = score
                    val lateral = CoasterBezierRailFrames.lateralAt(primary, t, level)
                    val up = CoasterBezierRailFrames.faceUpAt(primary, t, level)
                    val degrees = Math.toDegrees(Math.atan2(rel.dot(up), rel.dot(lateral)))
                    best = WallHit(primary, t, WaterslideSectorLayout.normalize(degrees.toFloat()))
                }
            }
        }
        return best
    }

    private fun radiusAt(level: Level, pos: BlockPos): Float =
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.radius ?: ModConfig.defaultSlideRadius()

    private fun findCurveByAnchors(level: Level, a: BlockPos, b: BlockPos): BezierConnection? {
        return findCurveOneWay(level, a, b) ?: findCurveOneWay(level, b, a)
    }

    private fun findCurveOneWay(level: Level, a: BlockPos, b: BlockPos): BezierConnection? {
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    private fun showPendingBox(level: Level, pos: BlockPos) {
        val state = level.getBlockState(pos)
        val shape = state.getShape(level, pos)
        val box = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
        Outliner.getInstance().showAABB(KEY_PENDING, box.inflate(0.05))
            .colored(0xFFFFFF)
            .lineWidth(0.0625f)
    }

    private fun clearPending(level: Level?) {
        pendingBlockAnchor = null
        Outliner.getInstance().remove(KEY_PENDING)
    }

    private fun spawnParticles(level: Level, pos: Vec3, count: Int) {
        val clientLevel = level as? ClientLevel ?: return
        val dust = DustParticleOptions(Vector3f(1f, 1f, 1f), 1f)
        for (i in 0 until count) {
            clientLevel.addParticle(
                dust,
                pos.x + (clientLevel.random.nextDouble() - 0.5) * 0.8,
                pos.y + (clientLevel.random.nextDouble() - 0.5) * 0.8,
                pos.z + (clientLevel.random.nextDouble() - 0.5) * 0.8,
                0.0, 0.0, 0.0
            )
        }
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return clearPending(null)
        val level = mc.level ?: return clearPending(null)
        val pending = pendingBlockAnchor ?: return
        if (player.mainHandItem.item !is BlockItem) {
            clearPending(level)
            return
        }
        val be = level.getBlockEntity(pending)
        if (be !is CoasterAnchorpointBlockEntity || be.isRemoved) {
            CreateWaterparked.LOGGER.debug(
                "WaterslideSectorEdit: pending cleared, BE at {} is {}",
                pending, be
            )
            clearPending(level)
            return
        }
        showPendingBox(level, pending)
    }

    private data class WallHit(val curve: BezierConnection, val t: Float, val angle: Float)

    data class LiveAnchorFrame(val center: Vec3, val lateral: Vec3, val up: Vec3)

    // Control point dragging.

    @JvmStatic
    fun mixinClientTick(mc: Minecraft) {
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()
        if (!BezierHandleEditMode.isActive()) return clear()
        if (!AllItems.WRENCH.isIn(player.mainHandItem) && !AllItems.WRENCH.isIn(player.offhandItem)) return clear()
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return clear()
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return clear()

        val useDown = mc.options.keyUse.isDown
        if (dragging || draggingBoundary) {
            val key = dragCurveKey ?: return clear()
            val curve = findCurve(level, key) ?: return clear()
            val targetAngle = angleFromDrag(mc, level, anchor, curve) ?: return clear()
            val config = previewConfigs[key] ?: return clear()
            if (draggingBoundary) {
                currentBoundaryAngle = easeAngle(currentBoundaryAngle, targetAngle)
                WaterslideSectorLayout.applyBoundaryResize(config, dragBoundarySectorId, currentBoundaryAngle)
            } else {
                currentDragAngle = easeAngle(currentDragAngle, targetAngle)
                applyMove(config, dragSectorId, currentDragAngle)
            }
            if (!useDown) {
                PacketDistributor.sendToServer(
                    WaterslideSectorEditPayload(
                        curveA = curve.bePositions.getFirst(),
                        curveB = curve.bePositions.getSecond(),
                        action = if (draggingBoundary) SectorEditAction.RESIZE else SectorEditAction.MOVE,
                        angleDegrees = if (draggingBoundary) currentBoundaryAngle else currentDragAngle,
                        sectorId = if (draggingBoundary) dragBoundarySectorId else dragSectorId
                    )
                )
                previewConfigs.remove(key)
                dragging = false
                draggingBoundary = false
                dragCurveKey = null
                dragSectorId = -1
                dragBoundarySectorId = -1
            }
        } else if (useDown) {
            val pick = pickControlPoint(mc, level, be)
            if (pick == null) return
            val config = configForCurve(level, pick.key) ?: return
            previewConfigs[pick.key] = config.copyOf()
            dragCurveKey = pick.key
            val placed = WaterslideSectorLayout.place(config.sectors)
            val p = placed.firstOrNull { it.sector.id == pick.sectorId }
            if (pick.boundary) {
                draggingBoundary = true
                dragBoundarySectorId = pick.sectorId
                currentBoundaryAngle = WaterslideSectorLayout.normalize(p?.endAngle ?: 0f)
            } else {
                dragging = true
                dragSectorId = pick.sectorId
                currentDragAngle = p?.centerAngle ?: 0f
            }
        }
    }

// eased angle
    private fun easeAngle(current: Float, target: Float): Float {
        var delta = WaterslideSectorLayout.normalize(target - current)
        if (delta > 180f) delta -= 360f
        return WaterslideSectorLayout.normalize(current + delta * 0.3f)
    }

    @JvmStatic
    fun renderControlPoints(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val level = mc.level ?: return
        if (!BezierHandleEditMode.isActive()) return
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return

        poseStack.pushPose()

        val center = CoasterAnchorpointBlockEntity.worldCenter(level, anchor)
        val frame = anchorFrame(level, be)
        val radius = be.radius * CONTROL_RING_SCALE
        val strips = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        drawCircle(poseStack, strips, cameraPos, cameraRotation, center, frame, radius, 0.15f, 0.85f, 1.0f, 1.0f)

        for (cp in controlPoints(level, be)) {
            val draggingThis =
                (dragging && dragCurveKey == cp.key && dragSectorId == cp.sectorId && !cp.boundary) ||
                    (draggingBoundary && dragCurveKey == cp.key && dragBoundarySectorId == cp.sectorId && cp.boundary)
            val hover = if (!draggingThis) pickControlPoint(mc, level, be) else null
            val hoveringThis = hover != null && hover.key == cp.key && hover.sectorId == cp.sectorId
            if (cp.boundary) {
                val tex = boundaryHandleTexture(mc, draggingThis, hoveringThis)
// radial normal alignment
                WaterslideEditorRenderTypes.billboardOrientedTexturedQuad(
                    poseStack,
                    bufferSource.getBuffer(WaterslideEditorRenderTypes.boundaryHandleBillboard(tex)),
                    cameraPos, cameraRotation, cp.world,
                    cp.normal ?: Vec3(0.0, 1.0, 0.0),
                    0.11f, 0.11f
                )
            } else {
                val tex = when {
                    draggingThis -> BezierHandleTangentTextures.DRAGGING
                    hoveringThis -> BezierHandleTangentTextures.HOVER
                    else -> BezierHandleTangentTextures.DEFAULT
                }
                drawHandleTexturedQuad(
                    poseStack,
                    bufferSource.getBuffer(BezierHandleOverlayRenderTypes.tangentHandleBillboard(tex)),
                    cameraPos, cameraRotation, cp.world
                )
            }
        }

        poseStack.popPose()
    }

    @JvmStatic
    fun isHoveringOrDraggingControlPoint(mc: Minecraft): Boolean {
        if (dragging || draggingBoundary) return true
        val level = mc.level ?: return false
        if (!BezierHandleEditMode.isActive()) return false
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return false
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return false
        return pickControlPoint(mc, level, be) != null
    }

    // Dragging state for the Flywheel visual.
    @JvmStatic
    fun isDraggingControlPoint(): Boolean = dragging || draggingBoundary

    private data class ControlPoint(
        val key: Pair<Long, Long>,
        val sectorId: Int,
        val world: Vec3,
        val boundary: Boolean = false,
        val normal: Vec3? = null
    )

    private fun controlPoints(level: Level, be: WaterslideAnchorBlockEntity): List<ControlPoint> {
        val out = mutableListOf<ControlPoint>()
        val anchor = be.blockPos
        for ((peer, raw) in be.anchorPeerCurvesView) {
            val primary = if (raw.isPrimary) raw else raw.secondary()
            val config = previewConfigFor(anchor, peer) ?: configForCurve(level, curveKey(anchor, peer)) ?: continue
            val placed = WaterslideSectorLayout.place(config.sectors)
            val t = if (primary.bePositions.getFirst() == anchor) 0f else 1f
            val live = liveAnchorFrame(level, anchor)
            val center = live?.center ?: CoasterAnchorpointBlockEntity.worldCenter(level, anchor)
            val lateral = live?.lateral ?: CoasterBezierRailFrames.lateralAt(primary, t, level)
            val up = live?.up ?: CoasterBezierRailFrames.faceUpAt(primary, t, level)
            val ringRadius = be.radius * CONTROL_RING_SCALE
            val key = curveKey(anchor, peer)
            for (p in placed) {
                val rad = Math.toRadians(p.centerAngle.toDouble())
                val pos = center
                    .add(lateral.scale(Math.cos(rad) * ringRadius))
                    .add(up.scale(Math.sin(rad) * ringRadius))
                out += ControlPoint(key, p.sector.id, pos)
            }
// junction control points
            val seenBoundaries = HashSet<Float>()
            for (p in placed) {
                val angle = WaterslideSectorLayout.normalize(p.endAngle)
                if (!seenBoundaries.add(angle)) continue
                val rad = Math.toRadians(angle.toDouble())
                val pos = center
                    .add(lateral.scale(Math.cos(rad) * ringRadius))
                    .add(up.scale(Math.sin(rad) * ringRadius))
                val normal = lateral.scale(Math.cos(rad)).add(up.scale(Math.sin(rad))).normalize()
                out += ControlPoint(key, p.sector.id, pos, boundary = true, normal = normal)
            }
        }
        return out
    }

    private fun pickControlPoint(mc: Minecraft, level: Level, be: WaterslideAnchorBlockEntity): ControlPoint? {
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        return controlPoints(level, be)
            .filter { raySphere(eye, view, it.world, PICK_RADIUS) }
            .minByOrNull { it.world.distanceToSqr(eye) }
    }

    private fun angleFromDrag(mc: Minecraft, level: Level, anchor: BlockPos, curve: BezierConnection): Float? {
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        val center = CoasterAnchorpointBlockEntity.worldCenter(level, anchor)
        val t = if (curve.bePositions.getFirst() == anchor) 0f else 1f
        val lateral = CoasterBezierRailFrames.lateralAt(curve, t, level)
        val up = CoasterBezierRailFrames.faceUpAt(curve, t, level)
        val normal = lateral.cross(up)
        val denom = view.dot(normal)
        if (abs(denom) < 1.0E-6) return null
        val d = normal.dot(center.subtract(eye)) / denom
        if (d < 0.0) return null
        val hit = eye.add(view.scale(d))
        val rel = hit.subtract(center)
        val degrees = Math.toDegrees(Math.atan2(rel.dot(up), rel.dot(lateral)))
        return WaterslideSectorLayout.normalize(degrees.toFloat())
    }

    private fun applyMove(config: WaterslideSectorConfig, sectorId: Int, newCenterAngle: Float) {
        WaterslideSectorLayout.applyMove(config, sectorId, newCenterAngle)
    }

    // Helpers.

    private fun findCurve(level: Level, key: Pair<Long, Long>): BezierConnection? {
        val a = BlockPos.of(key.first)
        val b = BlockPos.of(key.second)
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    private fun configForCurve(level: Level, key: Pair<Long, Long>): WaterslideSectorConfig? {
        val curve = findCurve(level, key) ?: return null
        val storage = level.getBlockEntity(curve.bePositions.getFirst()) as? WaterslideAnchorBlockEntity ?: return null
        return storage.sectorConfigFor(curve.bePositions.getSecond())
    }

    private fun curveKey(a: BlockPos, b: BlockPos): Pair<Long, Long> {
        val la = a.asLong()
        val lb = b.asLong()
        return if (la <= lb) la to lb else lb to la
    }

    private data class CircleFrame(val lateral: Vec3, val up: Vec3)

    private fun anchorFrame(level: Level, be: WaterslideAnchorBlockEntity): CircleFrame {
        liveAnchorFrame(level, be.blockPos)?.let { return CircleFrame(it.lateral, it.up) }
        for ((_, raw) in be.anchorPeerCurvesView) {
            val primary = if (raw.isPrimary) raw else raw.secondary()
            val t = if (primary.bePositions.getFirst() == be.blockPos) 0f else 1f
            return CircleFrame(
                CoasterBezierRailFrames.lateralAt(primary, t, level),
                CoasterBezierRailFrames.faceUpAt(primary, t, level)
            )
        }
        val up = CoasterAnchorpointBlockEntity.localUp(level, be.blockPos)
        val ref = if (abs(up.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        return CircleFrame(up.cross(ref).normalize(), up)
    }

// live preview frame
    @JvmStatic
    fun liveAnchorFrame(level: Level, anchor: BlockPos): LiveAnchorFrame? {
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return null
        for ((_, raw) in be.anchorPeerCurvesView) {
            val world = if (raw.isPrimary) raw else raw.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(world)) continue
            val preview = BezierHandleDragManager.previewCurveOrWorld(edgeKeyString(world), world)
            if (preview === world) continue
            val first = preview.bePositions.getFirst() == anchor
            val t = if (first) 0f else 1f
            val centerLocal = if (first) preview.starts.getFirst() else preview.starts.getSecond()
            val center = CoasterAnchorClientSpace.pathCenterlineRenderWorld(level, anchor, centerLocal)
            var lateral = CoasterAnchorClientSpace.toRenderDirection(
                level, anchor, CoasterBezierRailFrames.lateralAt(preview, t, level)
            )
            var up = CoasterAnchorClientSpace.toRenderDirection(
                level, anchor, CoasterBezierRailFrames.faceUpAt(preview, t, level)
            )
            if (lateral.lengthSqr() < 1.0E-12 || up.lengthSqr() < 1.0E-12) continue
            lateral = lateral.normalize()
            up = up.normalize()
            return LiveAnchorFrame(center, lateral, up)
        }
        return null
    }

    private fun edgeKeyString(bc: BezierConnection): String {
        val a = bc.bePositions.getFirst().asLong()
        val b = bc.bePositions.getSecond().asLong()
        return if (a <= b) "$a:$b" else "$b:$a"
    }

    private fun drawCircle(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        center: Vec3,
        frame: CircleFrame,
        radius: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        for (i in 0 until WALL_SEGMENTS) {
            val a0 = i.toDouble() / WALL_SEGMENTS * 2.0 * Math.PI
            val a1 = (i + 1).toDouble() / WALL_SEGMENTS * 2.0 * Math.PI
            val p0 = circlePoint(center, frame, radius, a0)
            val p1 = circlePoint(center, frame, radius, a1)
            WaterslideEditorRenderTypes.billboardStrip(
                poseStack, consumer, cameraPos, cameraRotation, p0, p1, 0.045f, r, g, b, a
            )
        }
    }

// texture fallback
    private fun boundaryHandleTexture(
        mc: Minecraft,
        dragging: Boolean,
        hovering: Boolean
    ): ResourceLocation {
        val ours = when {
            dragging -> WaterslideEditorRenderTypes.SECTOR_BOUNDARY_HANDLE_DRAGGING
            hovering -> WaterslideEditorRenderTypes.SECTOR_BOUNDARY_HANDLE_HOVER
            else -> WaterslideEditorRenderTypes.SECTOR_BOUNDARY_HANDLE_DEFAULT
        }
        val fallback = when {
            dragging -> EndpointHandleTextures.DRAGGING
            hovering -> EndpointHandleTextures.HOVER
            else -> EndpointHandleTextures.DEFAULT
        }
        return if (mc.resourceManager.getResource(ours).isPresent) ours else fallback
    }

    private fun circlePoint(center: Vec3, frame: CircleFrame, radius: Float, rad: Double): Vec3 =
        center
            .add(frame.lateral.scale(Math.cos(rad) * radius))
            .add(frame.up.scale(Math.sin(rad) * radius))

    private fun drawHandleTexturedQuad(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        center: Vec3
    ) {
        WaterslideEditorRenderTypes.billboardTexturedQuad(
            poseStack, consumer, cameraPos, cameraRotation, center, 0.11f
        )
    }

    private fun raySphere(ro: Vec3, rd: Vec3, center: Vec3, radius: Double): Boolean {
        val oc = ro.subtract(center)
        val b = oc.dot(rd)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc < 0.0) return false
        val sqrt = Math.sqrt(disc)
        val t0 = -b - sqrt
        val t1 = -b + sqrt
        return t0 >= 1.0E-4 || t1 >= 1.0E-4
    }

    private fun clear() {
        previewConfigs.clear()
        dragging = false
        draggingBoundary = false
        dragCurveKey = null
        dragSectorId = -1
        dragBoundarySectorId = -1
    }
}
