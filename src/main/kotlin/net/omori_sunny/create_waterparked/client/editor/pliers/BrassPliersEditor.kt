package net.omori_sunny.create_waterparked.client.editor.pliers

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditSounds
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.network.WaterslideRadiusEditPayload
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// precision drag layer over the coaster lib's control points
object BrassPliersEditor {

    enum class Kind { NONE, TANGENT, TILT, LIFT, RADIUS }

    private class Drag(
        val kind: Kind,
        val anchor: BlockPos,
        val primaryHost: BlockPos = BlockPos.ZERO,
        val remoteEnd: BlockPos = BlockPos.ZERO,
        val endpointIndex: Int = 0,
        val endpointPos: BlockPos = BlockPos.ZERO,
        val depth: Double = 0.0,
        val grab: Vec3 = Vec3.ZERO,
        val center: Vec3 = Vec3.ZERO,
        val grabOffset: Vec3 = Vec3.ZERO
    ) {
        var constrained: Vec3 = grab
        var target: Vec3 = grab
        var snapPoints: List<Vec3> = emptyList()
        var activeSnap: Vec3? = null
        var previewResult: CoasterBezierHandleEdit.PreviewResult? = null
    }

    private var drag: Drag? = null
    private var useWasDown = false
    private var hovered = false
    private var lastTickMs = 0L
    private var lastSnapKey: String = "free"
    private var lastHudTick = 0L

    private var dragRawRender: Vec3? = null
    private var lastYaw = 0f
    private var lastPitch = 0f

    private fun beginPixelTransport(mc: Minecraft) {
        dragRawRender = null
        lastSnapKey = "free"
        val p = mc.player ?: return
        lastYaw = p.yRot
        lastPitch = p.xRot
    }

    class HoverInfo(val anchor: BlockPos, val point: Vec3, val frame: TrackFrame?)

    private var hoverList: List<HoverInfo> = emptyList()

    @JvmStatic
    fun holdsPlier(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        return player.mainHandItem.item === ModItems.BRASS_PLIER ||
            player.offhandItem.item === ModItems.BRASS_PLIER
    }

    @JvmStatic
    fun isPlierStack(stack: net.minecraft.world.item.ItemStack): Boolean =
        stack.item === ModItems.BRASS_PLIER

    @JvmStatic
    fun isDragging(): Boolean = drag != null

    @JvmStatic
    fun isSession(mc: Minecraft): Boolean = sessionActive(mc)

    @JvmStatic
    fun isLibPreviewHeld(): Boolean = libPreviewHoldTicks > 0

    @JvmStatic
    fun lastEdited(): BezierConnection? =
        drag?.takeIf { it.kind == Kind.TANGENT }?.previewResult?.edited()

    @JvmStatic
    fun isHoveringOrDragging(mc: Minecraft): Boolean = drag != null || (sessionActive(mc) && hovered)

    private fun sessionActive(mc: Minecraft): Boolean =
        mc.player != null && mc.level != null &&
BezierHandleEditMode.isActive() && holdsPlier(mc)

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || event.isCanceled) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.mainHandItem.item !== ModItems.BRASS_PLIER) return
        val level = mc.level ?: return
        if (drag != null) return

        val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
        val anchorPos = when {
            hit != null && level.getBlockEntity(hit.blockPos) is CoasterAnchorpointBlockEntity ->
                hit.blockPos.immutable()
            else -> {
                val wall = net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
                    .pickWallAtCursor(mc) ?: return
                val raw = wall.curve
                val primary = if (raw.isPrimary) raw else raw.secondary() ?: return
                if (!net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
                        .isWaterslide(primary)
                ) return
                primary.bePositions.getFirst()
            }
        }
        event.setCanceled(true)
        event.setSwingHand(true)
        if (BezierHandleEditMode.getActiveAnchor() == anchorPos) return
        when {
            BezierHandleEditMode.isActive() ->
                BezierHandleEditMode.switchActiveAnchorTo(anchorPos, level)
            else -> BezierHandleEditMode.tryActivateFromInteract(level, player, anchorPos)
        }
    }

    @JvmStatic
    fun frameUpdate(mc: Minecraft) {
        val d = drag ?: return
        if (mc.player == null || mc.level == null || mc.screen != null) return
        if (mc.options.keyUse.isDown) update(mc, d)
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        onClientTickRaw(Minecraft.getInstance())
    }

    @JvmStatic
    fun onClientTickRaw(mc: Minecraft) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || mc.screen != null) {
            abandonDrag()
            hovered = false
            hoverList = emptyList()
            return
        }
        if (!sessionActive(mc)) {
            abandonDrag()
            hovered = false
            hoverList = emptyList()
            useWasDown = false
            return
        }
        if (drag == null && libPreviewHoldTicks > 0) {
            libPreviewHoldTicks--
            if (libPreviewHoldTicks == 0) clearLibPreview()
        }
        val useDown = mc.options.keyUse.isDown
        if (useDown && !useWasDown) tryBegin(mc)
        val d = drag
        if (!useDown && d != null) commit(mc, d)
        if (!useDown) hovered = computeHover(mc)
        useWasDown = useDown
    }

    private fun abandonDrag() {
        val d = drag ?: return
        clearLibPreview()
        WaterslideRadiusEdit.pliersPreviewRadius(d.anchor, null)
        drag = null
        dragRawRender = null
    }

    private fun eye(mc: Minecraft): Vec3 = mc.player!!.eyePosition
    private fun look(mc: Minecraft): Vec3 = mc.player!!.getViewVector(1f).normalize()

    private fun toLocal(level: net.minecraft.world.level.Level, anchor: BlockPos, world: Vec3): Vec3 =
        CoasterAnchorClientSpace.toPlotLocal(level, anchor, world)

    private fun toWorld(level: net.minecraft.world.level.Level, anchor: BlockPos, local: Vec3): Vec3 =
        CoasterAnchorClientSpace.toRenderWorld(level, anchor, local)

    private fun raySphere(eye: Vec3, view: Vec3, center: Vec3, radius: Double): Boolean {
        val oc = eye.subtract(center)
        val b = oc.dot(view)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc < 0.0) return false
        val t = -b - sqrt(disc)
        return t > 0.0
    }

    private fun tryBegin(mc: Minecraft) {
        val level = mc.level ?: return
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return

        val tangent = BezierHandleDragManager.rayPickClosestHandle(mc)
        if (tangent != null) {
            val worldTip = tangent.handleTipWorld()
            val bc = activeCurveOf(mc, tangent.primaryHost(), tangent.remoteEnd()) ?: return
            val ep = if (tangent.endpointIndex() == 1) bc.bePositions.second else bc.bePositions.first
            val center = CoasterAnchorpointBlockEntity.worldCenter(level, ep)
            val localTip = toLocal(level, ep, worldTip)
            drag = Drag(
                Kind.TANGENT, anchor,
                tangent.primaryHost(), tangent.remoteEnd(), tangent.endpointIndex(), ep,
                (worldTip.subtract(eye(mc))).dot(look(mc)).coerceIn(0.3, 24.0),
                localTip, center, localTip.subtract(center)
            )
            return
        }
        val tilt = BezierHandleDragManager.rayPickClosestTiltHandle(mc)
        if (tilt != null) beginSimple(mc, Kind.TILT, anchor, tilt.anchorPos(), tilt.tipWorld())
        val lift = BezierHandleDragManager.rayPickClosestLiftHandle(mc)
        if (lift != null && drag == null) beginSimple(mc, Kind.LIFT, anchor, lift.anchorPos(), lift.tipWorld())

        if (drag == null && level.getBlockEntity(anchor) is WaterslideAnchorBlockEntity) {
            val tipPlot = WaterslideRadiusEdit.pliersHandleTip(level, anchor)
            val tipRender = CoasterAnchorClientSpace.toRenderWorld(level, anchor, tipPlot)
            val center = WaterslideRadiusEdit.pliersAnchorCenter(level, anchor)
            if (raySphere(eye(mc), look(mc), tipRender, 0.35)) {
                drag = Drag(
                    Kind.RADIUS, anchor,
                    depth = (tipRender.subtract(eye(mc))).dot(look(mc)).coerceIn(0.3, 24.0),
                    grab = tipPlot, center = center
                )
            }
        }

        if (drag != null) beginPixelTransport(mc)
    }

    private fun beginSimple(mc: Minecraft, kind: Kind, anchor: BlockPos, pos: BlockPos, worldTip: Vec3) {
        val level = mc.level ?: return
        val center = CoasterAnchorpointBlockEntity.worldCenter(level, pos)
        val localTip = toLocal(level, pos, worldTip)
        drag = Drag(
            kind, anchor, pos, BlockPos.ZERO, 0, pos,
            (worldTip.subtract(eye(mc))).dot(look(mc)).coerceIn(0.3, 24.0),
            localTip, center, localTip.subtract(center)
        )
    }

    private fun rawLocal(mc: Minecraft, d: Drag): Vec3 {
        val world = eye(mc).add(look(mc).scale(d.depth))
        return toLocal(mc.level!!, d.spaceAnchor(), world)
    }

    private fun Drag.spaceAnchor(): BlockPos =
        if (kind == Kind.TANGENT) endpointPos else anchor

    private fun trackFrameFor(mc: Minecraft, d: Drag): TrackFrame? =
        if (d.kind == Kind.TANGENT) {
            val level = mc.level ?: return null
            frameOfCurve(level, activeCurveOf(mc, d), d.endpointIndex)
        } else {
            activeFrameOf(mc, d.anchor)
        }

    private fun activeCurveOf(mc: Minecraft, d: Drag): BezierConnection? {
        val level = mc.level ?: return null
        val host = if (d.kind == Kind.TANGENT) d.primaryHost else d.anchor
        val be = level.getBlockEntity(host) as? CoasterAnchorpointBlockEntity ?: return null
        val remote = if (d.kind == Kind.TANGENT) d.remoteEnd else d.anchor
        val raw = be.anchorPeerCurvesView[remote] ?: return null
        return if (raw.isPrimary) raw else raw.secondary()
    }

    private fun dragAxisOf(
        mc: Minecraft,
        d: Drag,
        frame: TrackFrame?
    ): Vec3 {
        if (d.kind == Kind.RADIUS) {
            frame?.tangent?.let { return it }
        }
        return BrassPliersModes.planeNormalPlot(mc.level, d.spaceAnchor(), frame)
    }

    private fun update(mc: Minecraft, d: Drag) {
        var raw = rawLocal(mc, d)
        val snapEnabled = !com.simibubi.create.AllKeys.altDown()
        val neighbors = neighborsOf(mc, d)
        d.snapPoints = neighbors

        val constrained: Vec3?
        var snapPoint: Vec3? = null
        if (d.kind == Kind.TANGENT || d.kind == Kind.RADIUS) {
            val frame = trackFrameFor(mc, d)
            val axis = dragAxisOf(mc, d, frame)

            run {
                val axisR = CoasterAnchorClientSpace.toRenderDirection(mc.level!!, d.spaceAnchor(), axis)
                val lookV = look(mc)
                val denom = lookV.dot(axisR)
                var handled = false
                if (kotlin.math.abs(denom) > 0.35) {
                    val planePoint = toWorld(mc.level!!, d.spaceAnchor(), d.grab)
                    val t = planePoint.subtract(eye(mc)).dot(axisR) / denom
                    if (t > 0.0) {
                        raw = eye(mc).add(lookV.scale(t))
                            .let { toLocal(mc.level!!, d.spaceAnchor(), it) }
                        dragRawRender = toWorld(mc.level!!, d.spaceAnchor(), raw)
                        handled = true
                    }
                }
                if (!handled) {
                    val player = mc.player!!
                    val dYaw = net.minecraft.util.Mth.wrapDegrees(player.yRot - lastYaw)
                    val dPitch = player.xRot - lastPitch
                    val seed = dragRawRender ?: toWorld(mc.level!!, d.spaceAnchor(), d.grab)
                        .also { dragRawRender = it }
                    val upRef = if (kotlin.math.abs(lookV.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
                    val right = lookV.cross(upRef).normalize()
                    val camUp = right.cross(lookV).normalize()
                    val dist = seed.distanceTo(eye(mc)).coerceAtLeast(0.5)
                    var delta = right.scale(Math.toRadians(dYaw.toDouble()) * dist)
                        .add(camUp.scale(-Math.toRadians(dPitch.toDouble()) * dist))
                    delta = delta.subtract(axisR.scale(delta.dot(axisR)))
                    val rawRender = seed.add(delta)
                    dragRawRender = rawRender
                    raw = toLocal(mc.level!!, d.spaceAnchor(), rawRender)
                }
                lastYaw = mc.player!!.yRot
                lastPitch = mc.player!!.xRot
            }
            val centerNow = if (d.kind == Kind.RADIUS)
                WaterslideRadiusEdit.pliersAnchorCenter(mc.level!!, d.anchor) else d.center
            val origin = if (
                BrassPliersModes.align == PlierAlign.POLAR &&
                BrassPliersModes.space == PlierSpace.ABSOLUTE
            ) centerNow else d.grab
            val p0 = d.grab.subtract(origin).subtract(axis.scale(d.grab.subtract(origin).dot(axis)))

            var v = raw.subtract(d.grab)
            v = v.subtract(axis.scale(v.dot(axis)))
            var p = p0.add(v)
            if (snapEnabled) {
                val e1 = planeBasis1(axis)
                val e2 = axis.cross(e1).normalize()
                if (BrassPliersModes.align == PlierAlign.CARTESIAN) {
                    var c1 = p.dot(e1)
                    var c2 = p.dot(e2)
                    for (n in neighbors) {
                        val nv = n.subtract(origin)
                        val n1 = nv.dot(e1)
                        val n2 = nv.dot(e2)
                        var hit = false
                        if (abs(n1 - c1) < 0.15) { c1 = n1; hit = true }
                        if (abs(n2 - c2) < 0.15) { c2 = n2; hit = true }
                        if (hit) snapPoint = n
                    }
                    p = e1.scale(c1).add(e2.scale(c2))
                } else {
                    var r = kotlin.math.sqrt(p.dot(p))
                    var ang = atan2(p.dot(e2), p.dot(e1))
                    for (n in neighbors) {
                        val nv = n.subtract(origin)
                        val nr = kotlin.math.sqrt(nv.dot(nv))
                        val na = atan2(nv.dot(e2), nv.dot(e1))
                        var delta = na - ang
                        while (delta > Math.PI) delta -= 2 * Math.PI
                        while (delta < -Math.PI) delta += 2 * Math.PI
                        var hit = false
                        if (abs(delta) < Math.toRadians(5.0)) { ang = na; hit = true }
                        if (abs(nr - r) < 0.15) { r = nr; hit = true }
                        if (hit) snapPoint = n
                    }
                    p = e1.scale(cos(ang) * r).add(e2.scale(sin(ang) * r))
                }
            }
            var c = origin.add(p)
            if (d.kind == Kind.RADIUS) {
                val clamped = net.omori_sunny.create_waterparked.config.ModConfig.clampSlideRadius(
                    c.subtract(centerNow).length().toFloat()
                )
                val cur = c.subtract(centerNow).length().coerceAtLeast(1.0E-4)
                c = centerNow.add(c.subtract(centerNow).scale(clamped.toDouble() / cur))
                WaterslideRadiusEdit.pliersPreviewRadius(d.anchor, clamped)
                c = centerNow.add(
                    WaterslideRadiusEdit.pliersHandleLateral(mc.level!!, d.anchor)
                        .scale(clamped.toDouble())
                )
            }
            constrained = c
        } else {
            constrained = raw
        }
        d.constrained = constrained ?: d.grab
        d.target = d.constrained
        d.activeSnap = snapPoint

        updatePreview(mc, d)
        if (d.kind == Kind.TANGENT) {
            val offset = d.target.subtract(d.center)
            if (offset.lengthSqr() > 1.0E-12) {
                val draggedLen = computedDraggedLen(
                    mc.level!!, activeCurveOf(mc, d), offset
                )
                if (draggedLen > 0.0) {
                    d.constrained = d.center.add(offset.normalize().scale(draggedLen))
                }
            }
        } else if (d.kind == Kind.TILT) {
            val edited = d.previewResult?.edited()
            if (edited != null) {
                val atAnchor = edited.bePositions.first == d.anchor
                val start = edited.starts.get(atAnchor)
                val normal = edited.normals.get(atAnchor)
                if (start != null && normal != null && normal.length() > 1.0E-6) {
                    d.constrained = start.add(normal.normalize().scale(0.75))
                }
            }
        } else if (d.kind == Kind.LIFT) {
            val blocks = dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
                .computeLiftBlocksFromVirtualTarget(mc.level!!, d.anchor, d.target)
            if (blocks != null) {
                dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
                    .liftVirtualTargetWorldForStoredLift(mc.level!!, d.anchor, d.target, blocks)
                    ?.let { d.constrained = it }
            }
        }
        snapTick(d)
        updateHud(mc, d)
    }

    private fun planeBasis1(axis: Vec3): Vec3 {
        val ref = if (abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        return axis.cross(ref).normalize()
    }

    private fun neighborsOf(mc: Minecraft, d: Drag): List<Vec3> {
        val level = mc.level ?: return emptyList()
        val out = ArrayList<Vec3>()
        val host = if (d.kind == Kind.TANGENT) d.primaryHost else d.anchor
        val be = level.getBlockEntity(host) as? CoasterAnchorpointBlockEntity ?: return out
        for ((peer, raw) in be.anchorPeerCurvesView) {
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
            controlTipOf(bc, host)?.let { out += it }
            controlTipOf(bc, peer)?.let { out += it }
            out += CoasterAnchorpointBlockEntity.worldCenter(level, peer)
            if (peer == d.remoteEnd || peer == host) continue
        }
        out += CoasterAnchorpointBlockEntity.worldCenter(level, host)
        return out
    }

    private fun computedLengths(
        world: BezierConnection,
        draggedIdx: Int,
        draggedLen: Double
    ): Pair<Double, Double> {
        val otherLen = dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths
            .getLengthAtEndpoint(world, 1 - draggedIdx)
        val len0 = if (draggedIdx == 0) draggedLen else otherLen
        val len1 = if (draggedIdx == 0) otherLen else draggedLen
        val firstIsLexMin = world.bePositions.first.asLong() < world.bePositions.second.asLong()
        return if (firstIsLexMin) len0 to len1 else len1 to len0
    }

    private fun computedDraggedLen(
        level: net.minecraft.world.level.Level,
        world: BezierConnection?,
        offset: Vec3
    ): Double {
        if (world == null) return offset.length()
        return minOf(
            offset.length(),
            dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths
                .maxHandleLengthForEdge(level, world)
        )
    }

    private fun updatePreview(mc: Minecraft, d: Drag) {
        val level = mc.level ?: return
        when (d.kind) {
            Kind.TANGENT -> {
                val offset = d.target.subtract(d.center)
                d.previewResult = CoasterBezierHandleEdit.computePreview(
                    level, d.primaryHost, d.remoteEnd, d.endpointIndex, offset
                )
                val world = activeCurveOf(mc, d)
                val draggedLen = computedDraggedLen(level, world, offset)
                if (world != null) {
                    val (lo, hi) = computedLengths(world, d.endpointIndex, draggedLen)
                    d.previewResult?.edited()?.let {
                        dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths
                            .putPreviewCanonical(it, lo, hi)
                    }
                }
            }
            Kind.LIFT -> d.previewResult = CoasterBezierHandleEdit.computeLiftPreview(
                level, d.anchor, d.constrained
            )
            Kind.TILT -> d.previewResult = CoasterBezierHandleEdit.computeTiltPreview(
                level, d.anchor, d.constrained
            )
            Kind.RADIUS -> d.previewResult = null
            Kind.NONE -> d.previewResult = null
        }
        pushPreviewToLib(d)
    }

    private var libPreviewField: java.lang.reflect.Field? = null
    private var libTiltField: java.lang.reflect.Field? = null
    private var libLiftField: java.lang.reflect.Field? = null
    private var libDraggingField: java.lang.reflect.Field? = null
    private var libDraggingTiltField: java.lang.reflect.Field? = null
    private var libDraggingLiftField: java.lang.reflect.Field? = null

    private fun libField(name: String): java.lang.reflect.Field? =
        runCatching {
            BezierHandleDragManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
        }.getOrNull()

    private fun pushPreviewToLib(d: Drag) {
        if (libPreviewField == null) libPreviewField = libField("preview")
        if (libTiltField == null) libTiltField = libField("tiltPreview")
        if (libLiftField == null) libLiftField = libField("liftPreview")
        if (libDraggingField == null) libDraggingField = libField("dragging")
        if (libDraggingTiltField == null) libDraggingTiltField = libField("draggingTilt")
        if (libDraggingLiftField == null) libDraggingLiftField = libField("draggingLift")
        runCatching {
            libPreviewField?.set(null, if (d.kind == Kind.TANGENT) d.previewResult else null)
            libTiltField?.set(null, if (d.kind == Kind.TILT) d.previewResult else null)
            libLiftField?.set(null, if (d.kind == Kind.LIFT) d.previewResult else null)
            libDraggingField?.setBoolean(null, d.kind == Kind.TANGENT)
            libDraggingTiltField?.setBoolean(null, d.kind == Kind.TILT)
            libDraggingLiftField?.setBoolean(null, d.kind == Kind.LIFT)
        }
    }

    private fun clearLibPreview() {
        runCatching {
            libPreviewField?.set(null, null)
            libTiltField?.set(null, null)
            libLiftField?.set(null, null)
            libDraggingField?.setBoolean(null, false)
            libDraggingTiltField?.setBoolean(null, false)
            libDraggingLiftField?.setBoolean(null, false)
        }
    }

    private var libPreviewHoldTicks = 0

    private fun commit(mc: Minecraft, d: Drag) {
        val level = mc.level
        drag = null
        dragRawRender = null
        libPreviewHoldTicks = 15
        if (level == null) return
        when (d.kind) {
            Kind.TANGENT -> {
                val offset = d.target.subtract(d.center)
                val world = activeCurveOf(mc, d)
                if (world != null) {
                    val draggedLen = computedDraggedLen(level, world, offset)
                    val (lo, hi) = computedLengths(world, d.endpointIndex, draggedLen)
                    d.previewResult?.edited()?.let {
                        dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths
                            .putCanonical(it, lo, hi)
                    }
                }
                PacketDistributor.sendToServer(
                    dev.silvergold.simulatedcoasters.track.BezierHandleEditPayload(
                        d.primaryHost, d.remoteEnd, d.endpointIndex, offset
                    )
                )
            }
            Kind.LIFT -> PacketDistributor.sendToServer(
                dev.silvergold.simulatedcoasters.track.BezierLiftEditPayload(
                    d.anchor, d.target
                )
            )
            Kind.TILT -> PacketDistributor.sendToServer(
                dev.silvergold.simulatedcoasters.track.BezierTiltEditPayload(
                    d.anchor, d.target
                )
            )
            Kind.RADIUS -> {
                val radius = net.omori_sunny.create_waterparked.config.ModConfig
                    .clampSlideRadius(
                        d.constrained
                            .subtract(WaterslideRadiusEdit.pliersAnchorCenter(level, d.anchor))
                            .length().toFloat()
                    )
                PacketDistributor.sendToServer(WaterslideRadiusEditPayload(d.anchor, radius))
                WaterslideRadiusEdit.pliersPreviewRadius(d.anchor, null)
            }
            Kind.NONE -> {}
        }
        WaterslideEditSounds.playCommitSuccess()
    }

    private fun snapTick(d: Drag) {
        val snap = d.activeSnap
        val key = if (snap != null) {
            String.format("%.2f,%.2f,%.2f", snap.x, snap.y, snap.z)
        } else "free"
        if (key == lastSnapKey) return
        lastSnapKey = key
        val now = System.currentTimeMillis()
        if (now - lastTickMs < 80) return
        lastTickMs = now
        WaterslideEditSounds.playDragTick(if (snap != null) 0.9f else 0.6f)
    }

    private fun updateHud(mc: Minecraft, d: Drag) {
        val now = mc.level?.gameTime ?: return
        if (now - lastHudTick < 5) return
        lastHudTick = now
        val readout = when (d.kind) {
            Kind.RADIUS -> String.format(
                "%.2f", net.omori_sunny.create_waterparked.config.ModConfig
                    .clampSlideRadius(d.constrained.subtract(d.center).length().toFloat())
            )
            else -> String.format(
                "%.2f", d.constrained.subtract(d.center).length()
            )
        }
        PliersRadialOverlay.showReadout(readout)
    }

    private fun controlTipOf(bc: BezierConnection, endpoint: BlockPos): Vec3? {
        val first = bc.bePositions.first == endpoint
        val start = bc.starts.get(first) ?: return null
        val axis = bc.axes.get(first) ?: return null
        val len = dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths
            .getLengthAtEndpoint(bc, if (first) 0 else 1)
        val alen = axis.length()
        if (alen < 1.0E-6 || len <= 0.0) return null
        return start.add(axis.scale(len / alen))
    }

    private fun proximityHovers(mc: Minecraft, level: net.minecraft.world.level.Level) {
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return
        val out = ArrayList<HoverInfo>()
        for ((peer, raw) in be.anchorPeerCurvesView) {
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
            for (endpoint in listOf(anchor, peer)) {
                val tip = controlTipOf(bc, endpoint) ?: continue
                val idx = if (bc.bePositions.first == endpoint) 0 else 1
                out += HoverInfo(
                    anchor,
                    CoasterAnchorClientSpace.toRenderWorld(level, anchor, tip),
                    frameOfCurve(level, bc, idx)
                )
            }
        }
        hoverList = out
    }

    private fun computeHover(mc: Minecraft): Boolean {
        hoverList = emptyList()
        if (drag != null) return true
        val level = mc.level ?: return false

        proximityHovers(mc, level)

        val handle = BezierHandleDragManager.rayPickClosestHandle(mc)
        if (handle != null) {
            val bc = activeCurveOf(mc, handle.primaryHost(), handle.remoteEnd())
            val frame = frameOfCurve(level, bc, handle.endpointIndex())
            hoverList = listOf(HoverInfo(handle.primaryHost(), handle.handleTipWorld(), frame))
            return true
        }
        val tilt = BezierHandleDragManager.rayPickClosestTiltHandle(mc)
        if (tilt != null) return true
        val lift = BezierHandleDragManager.rayPickClosestLiftHandle(mc)
        if (lift != null) return true
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return hoverList.isNotEmpty()
        if (level.getBlockEntity(anchor) !is WaterslideAnchorBlockEntity) return hoverList.isNotEmpty()
        val tip = WaterslideRadiusEdit.pliersHandleTip(level, anchor)
        if (raySphere(eye(mc), look(mc), tip, 0.35)) {
            val frame = activeFrameOf(mc, anchor)
            hoverList = listOf(
                HoverInfo(anchor, CoasterAnchorClientSpace.toRenderWorld(level, anchor, tip), frame)
            )
            return true
        }
        return false
    }

    private fun frameOfCurve(
        level: net.minecraft.world.level.Level,
        bc: BezierConnection?,
        endpointIndex: Int
    ): TrackFrame? {
        if (bc == null) return null
        val t = if (endpointIndex == 0) 0f else 1f
        return runCatching {
            val tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
                .unitTangentAt(bc, t).normalize()
            val lateral = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
                .lateralAt(bc, t, level).normalize()
            val up = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
                .faceUpAt(bc, t, level).normalize()
            TrackFrame(lateral, up, tangent)
        }.getOrNull()
    }

    private fun activeCurveOf(mc: Minecraft, host: BlockPos, remote: BlockPos): BezierConnection? {
        val level = mc.level ?: return null
        val be = level.getBlockEntity(host) as? CoasterAnchorpointBlockEntity ?: return null
        val raw = be.anchorPeerCurvesView[remote] ?: return null
        return if (raw.isPrimary) raw else raw.secondary()
    }

    private fun activeFrameOf(mc: Minecraft, anchor: BlockPos): TrackFrame? {
        val level = mc.level ?: return null
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return null
        for ((_, raw) in be.anchorPeerCurvesView) {
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
            if (!net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
                    .isWaterslide(bc)
            ) continue
            val atFirst = bc.bePositions.first == anchor
            return frameOfCurve(level, bc, if (atFirst) 0 else 1)
        }
        return null
    }

    class DragInfo(
        val kind: Kind,
        val anchor: BlockPos,
        val endpoint: BlockPos,
        val center: Vec3,
        val grab: Vec3,
        val constrained: Vec3,
        val activeSnap: Vec3?,
        val snapPoints: List<Vec3>,
        val axis: Vec3,
        val polar: Boolean,
        val origin: Vec3
    )

    @JvmStatic
    fun currentDrag(): DragInfo? {
        val mc = Minecraft.getInstance()
        val d = drag ?: return null
        val polar = BrassPliersModes.align == PlierAlign.POLAR
        val origin = if (polar && BrassPliersModes.space == PlierSpace.ABSOLUTE) {
            if (d.kind == Kind.RADIUS) WaterslideRadiusEdit.pliersAnchorCenter(mc.level!!, d.anchor)
            else d.center
        } else d.grab
        return DragInfo(
            d.kind, d.anchor, d.spaceAnchor(), d.center, d.grab, d.constrained, d.activeSnap, d.snapPoints,
            dragAxisOf(mc, d, trackFrameFor(mc, d)),
            polar,
            origin
        )
    }

    @JvmStatic
    fun currentHovers(): List<HoverInfo> = hoverList

    @JvmStatic
    fun draggedAnchorOf(kind: Kind): BlockPos? =
        drag?.takeIf { it.kind == kind }?.anchor
}
