package net.omori_sunny.create_waterparked.client.gui

import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen
import com.simibubi.create.foundation.gui.widget.IconButton
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableMenu
import net.omori_sunny.create_waterparked.game.SlideProfile
import net.omori_sunny.create_waterparked.game.SlideSketchData
import kotlin.math.hypot

class SlideDraftingTableScreen(
    container: SlideDraftingTableMenu,
    inventory: Inventory,
    title: Component
) : AbstractSimiContainerScreen<SlideDraftingTableMenu>(container, inventory, title) {

    private var anchors = ArrayList<Pair<Float, Float>>()
    private var handles = ArrayList<Pair<Float, Float>>()
    private var inHandles = ArrayList<Pair<Float, Float>>()
    private var anchorModes = ArrayList<BezierMode>()
    private var dragAnchor = -1
    private var dragHandle = -1
    private var dragInHandle = -1
    private val selectedAnchors = LinkedHashSet<Int>()
    private var tool = Tool.MOVE
    private var snap = true
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    private enum class Tool { MOVE, ADD, DELETE }

    private data class EditState(
        val anchors: List<Pair<Float, Float>>,
        val handles: List<Pair<Float, Float>>,
        val inHandles: List<Pair<Float, Float>>,
        val modes: List<BezierMode>
    )

    private enum class BezierMode(val langKey: String) {
        NONE("create_waterparked.sketch.bezier.none"),
        SYMMETRIC("create_waterparked.sketch.bezier.symmetric"),
        FREE("create_waterparked.sketch.bezier.free");

        fun next(): BezierMode = entries[(ordinal + 1) % entries.size]
    }

    private val showHandles: Boolean get() = anchorModes.any { it != BezierMode.NONE }

    private var staticInk: RoughCanvas.PixelBuffer? = null
    private var staticGuide: RoughCanvas.PixelBuffer? = null
    private var staticAccent: RoughCanvas.PixelBuffer? = null
    private var staticHandle: RoughCanvas.PixelBuffer? = null
    private var staticWarn: RoughCanvas.PixelBuffer? = null
    private var cacheKey = ""

    private var btnMove: SketchIconButton? = null
    private var btnAdd: SketchIconButton? = null
    private var btnDelete: SketchIconButton? = null
    private var btnSnap: SketchIconButton? = null
    private var btnHandles: SketchIconButton? = null
    private var btnReset: SketchIconButton? = null
    private var btnUndo: SketchIconButton? = null
    private var btnRedo: SketchIconButton? = null
    private var btnSave: SketchIconButton? = null

    companion object {
        val BACKGROUND = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            net.omori_sunny.create_waterparked.CreateWaterparked.ID, "textures/gui/slide_drafting_table.png"
        )
        val SLOT_FRAME = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            net.omori_sunny.create_waterparked.CreateWaterparked.ID, "textures/gui/sketch/slot.png"
        )
        val SLIDER_TRACK = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            net.omori_sunny.create_waterparked.CreateWaterparked.ID, "textures/gui/sketch/track.png"
        )
        const val CANVAS_X = 8
        const val CANVAS_Y = 18
        const val CANVAS_W = 122
        const val CANVAS_H = 92
        const val TOOLBAR_X = 132
        const val TOOLBAR_X2 = 153
        const val TOOLBAR_Y = 18
        const val TOOLBAR_STEP = 18
        const val SCALE = 28f
        const val CANVAS_MAX_X = (CANVAS_W / 2f - 4f) / SCALE
        const val CANVAS_MAX_Y = (CANVAS_H / 2f - 4f) / SCALE
        const val SNAP_STEPS = 4f
        const val PANEL_H = 126
        const val ARGB_INK = 0xFF2B2620.toInt()
        const val ARGB_GUIDE = 0xFFB8B0A2.toInt()
        const val ARGB_ACCENT = 0xFF5B7C99.toInt()
        const val ARGB_HANDLE = 0xB08A8378.toInt()
        const val ARGB_HANDLE_ACTIVE = 0xD05B7C99.toInt()
        const val ARGB_WARN = 0xFFB3402E.toInt()
    }

    init {
        imageWidth = 176
        imageHeight = PANEL_H + 4 + 108
        resetToCircle()
    }

    private fun resetToCircle() {
        anchors = ArrayList(listOf(
            1.0f to 0.0f, 0.0f to 1.0f, -1.0f to 0.0f, 0.0f to -1.0f
        ))
        handles = ArrayList(anchors.map { (x, y) -> -y * 0.45f to x * 0.45f })
        inHandles = ArrayList(handles.map { (x, y) -> -x to -y })
        anchorModes = ArrayList(List(anchors.size) { BezierMode.SYMMETRIC })
        dragAnchor = -1
        dragHandle = -1
        dragInHandle = -1
        selectedAnchors.clear()
    }

    override fun init() {
        super.init()
        loadFromSketchSlot()
        buildToolbar()
    }

    private fun loadFromSketchSlot() {
        val stack = menu.sketchStack()
        hasSketch = stack.item === ModItems.SLIDE_SKETCH
        val data = SlideSketchData.of(stack)
        if (data != null) {
            val a = ArrayList<Pair<Float, Float>>()
            for (i in 0 until data.profile.anchors.size / 2) {
                a += data.profile.anchors[i * 2] to data.profile.anchors[i * 2 + 1]
            }
            if (a.size >= 3) {
                anchors = a
                val h = ArrayList<Pair<Float, Float>>()
                for (i in 0 until data.profile.handles.size / 2) {
                    h += data.profile.handles[i * 2] to data.profile.handles[i * 2 + 1]
                }
                handles = if (h.size == a.size) h else ArrayList(List(a.size) { 0.35f to 0f })
                val dataIn = data.profile.inHandles
                inHandles = if (dataIn != null && dataIn.size == a.size * 2) {
                    ArrayList(List(a.size) { i -> dataIn[i * 2] to dataIn[i * 2 + 1] })
                } else {
                    ArrayList(handles.map { (x, y) -> -x to -y })
                }
                val dataModes = data.profile.modes
                anchorModes = if (dataModes != null && dataModes.size == a.size) {
                    ArrayList(List(a.size) { i ->
                        when (dataModes[i]) {
                            SlideProfile.MODE_NONE -> BezierMode.NONE
                            SlideProfile.MODE_FREE -> BezierMode.FREE
                            else -> BezierMode.SYMMETRIC
                        }
                    })
                } else {
                    ArrayList(List(a.size) { BezierMode.SYMMETRIC })
                }
                selectedAnchors.clear()
                return
            }
        }
        resetToCircle()
    }

    private fun buildToolbar() {
        val x1 = leftPos + TOOLBAR_X
        val x2 = leftPos + TOOLBAR_X2
        var y1 = topPos + TOOLBAR_Y
        var y2 = y1

        btnMove = SketchIconButton(x1, y1, TextureIcon.MOVE)
        btnMove!!.withCallback<SketchIconButton>(java.lang.Runnable { onToolMove() })
        btnMove!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.move"))
        addRenderableWidget(btnMove)
        y1 += TOOLBAR_STEP

        btnAdd = SketchIconButton(x1, y1, TextureIcon.ADD)
        btnAdd!!.withCallback<SketchIconButton>(java.lang.Runnable { onToolAdd() })
        btnAdd!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.add"))
        addRenderableWidget(btnAdd)
        y1 += TOOLBAR_STEP

        btnDelete = SketchIconButton(x1, y1, TextureIcon.DELETE)
        btnDelete!!.withCallback<SketchIconButton>(java.lang.Runnable { onToolDelete() })
        btnDelete!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.delete"))
        addRenderableWidget(btnDelete)
        y1 += TOOLBAR_STEP

        btnSnap = SketchIconButton(x1, y1, TextureIcon.SNAP)
        btnSnap!!.withCallback<SketchIconButton>(java.lang.Runnable { onToggleSnap() })
        btnSnap!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.snap"))
        addRenderableWidget(btnSnap)

        btnHandles = SketchIconButton(x2, y2, TextureIcon.BEZIER_SYMMETRIC)
        btnHandles!!.withCallback<SketchIconButton>(java.lang.Runnable { onCycleBezier() })
        btnHandles!!.setToolTip(bezierTooltip())
        addRenderableWidget(btnHandles)
        y2 += TOOLBAR_STEP

        btnReset = SketchIconButton(x2, y2, TextureIcon.RESET)
        btnReset!!.withCallback<SketchIconButton>(java.lang.Runnable { onReset() })
        btnReset!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.reset"))
        addRenderableWidget(btnReset)
        y2 += TOOLBAR_STEP

        btnUndo = SketchIconButton(x2, y2, TextureIcon.UNDO)
        btnUndo!!.withCallback<SketchIconButton>(java.lang.Runnable { onUndo() })
        btnUndo!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.undo"))
        addRenderableWidget(btnUndo)
        y2 += TOOLBAR_STEP

        btnRedo = SketchIconButton(x2, y2, TextureIcon.REDO)
        btnRedo!!.withCallback<SketchIconButton>(java.lang.Runnable { onRedo() })
        btnRedo!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.redo"))
        addRenderableWidget(btnRedo)
        y2 += TOOLBAR_STEP

        btnSave = SketchIconButton(x2, y2, TextureIcon.SAVE)
        btnSave!!.withCallback<SketchIconButton>(java.lang.Runnable { onSave() })
        btnSave!!.setToolTip(Component.translatable("create_waterparked.sketch.tool.save"))
        addRenderableWidget(btnSave)

        updateToolbarState()
    }

    private fun updateToolbarState() {
        btnMove?.selected = tool == Tool.MOVE
        btnAdd?.selected = tool == Tool.ADD
        btnSnap?.selected = snap
        btnDelete?.active = selectedAnchors.isNotEmpty()
        btnHandles?.active = selectedAnchors.isNotEmpty()
        btnUndo?.active = undoStack.isNotEmpty()
        btnRedo?.active = redoStack.isNotEmpty()
    }

    private fun pushUndo() {
        undoStack.addLast(EditState(anchors.toList(), handles.toList(), inHandles.toList(), anchorModes.toList()))
        if (undoStack.size > 64) undoStack.removeFirst()
        redoStack.clear()
        updateToolbarState()
    }

    private fun onToolMove() { tool = Tool.MOVE; updateToolbarState() }
    private fun onToolAdd() { tool = Tool.ADD; updateToolbarState() }
    private fun onToolDelete() { tool = Tool.DELETE; updateToolbarState() }
    private fun onToggleSnap() { snap = !snap; updateToolbarState() }

    private fun bezierTooltip(): Component {
        val modes = selectedAnchors.mapNotNull { i -> anchorModes.getOrNull(i) }.toSet()
        val label = when {
            modes.size == 1 -> modes.first().langKey
            modes.isEmpty() -> "create_waterparked.sketch.bezier.none"
            else -> "create_waterparked.sketch.bezier.mixed"
        }
        return Component.translatable(label)
    }

    private fun onCycleBezier() {
        if (selectedAnchors.isEmpty()) return
        val first = selectedAnchors.firstOrNull()?.let { anchorModes.getOrNull(it) } ?: return
        val next = first.next()
        for (i in selectedAnchors) {
            if (i < anchorModes.size) anchorModes[i] = next
            if (next == BezierMode.SYMMETRIC && i < handles.size && i < inHandles.size) {
                inHandles[i] = -handles[i].first to -handles[i].second
            }
        }
        refreshBezierButton()
        updateToolbarState()
    }

    private fun onReset() {
        pushUndo()
        resetToCircle()
    }

    private fun onUndo() {
        val state = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(EditState(anchors.toList(), handles.toList(), inHandles.toList(), anchorModes.toList()))
        anchors = ArrayList(state.anchors)
        handles = ArrayList(state.handles)
        inHandles = ArrayList(state.inHandles)
        anchorModes = ArrayList(state.modes)
        selectedAnchors.clear()
        refreshBezierButton()
        updateToolbarState()
    }

    private fun onRedo() {
        val state = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(EditState(anchors.toList(), handles.toList(), inHandles.toList(), anchorModes.toList()))
        anchors = ArrayList(state.anchors)
        handles = ArrayList(state.handles)
        inHandles = ArrayList(state.inHandles)
        anchorModes = ArrayList(state.modes)
        selectedAnchors.clear()
        refreshBezierButton()
        updateToolbarState()
    }

    private fun onSave() {
        if (hasSketch && loopSelfIntersects()) {
            dev.silvergold.simulatedcoasters.client.track.BezierHandleClientSounds.playCreateDeny()
            return
        }
        val profile = currentProfile() ?: return
        val stack = menu.sketchStack()
        if (stack.item !== ModItems.SLIDE_SKETCH) return

        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            net.omori_sunny.create_waterparked.network.SlideSketchSavePayload(
                menu.table.blockPos,
                profile.anchors,
                profile.handles,
                profile.transition,
                profile.inHandles,
                profile.modes
            )
        )
        stack.set(
            net.omori_sunny.create_waterparked.content.registry.ModDataComponents.SLIDE_SKETCH,
            SlideSketchData(profile, net.omori_sunny.create_waterparked.network.SlideSketchSavePayload.EDITED_NAME)
        )
        net.minecraft.client.Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.0f
            )
        )
        lastSketchStack = stack.copy()
    }

    fun currentProfile(): SlideProfile? {
        val flatA = FloatArray(anchors.size * 2)
        for ((i, p) in anchors.withIndex()) {
            flatA[i * 2] = p.first
            flatA[i * 2 + 1] = p.second
        }
        val flatH = FloatArray(handles.size * 2)
        for ((i, h) in handles.withIndex()) {
            flatH[i * 2] = h.first
            flatH[i * 2 + 1] = h.second
        }
        val flatI = FloatArray(inHandles.size * 2)
        for ((i, h) in inHandles.withIndex()) {
            flatI[i * 2] = h.first
            flatI[i * 2 + 1] = h.second
        }
        val modes = ByteArray(anchorModes.size) { anchorModes[it].ordinal.toByte() }
        return SlideProfile.of(flatA, flatH, flatI, modes, SlideProfile.DEFAULT_TRANSITION)
    }

    public override fun renderBg(graphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        val current = menu.sketchStack()
        if (!ItemStack.matches(current, lastSketchStack)) {
            lastSketchStack = current.copy()
            loadFromSketchSlot()
            refreshBezierButton()
            updateToolbarState()
        }
        graphics.blit(BACKGROUND, leftPos, topPos, 0f, 0f, imageWidth, PANEL_H, 256, 256)
        graphics.blit(
            SLIDER_TRACK,
            leftPos + TOOLBAR_X - 1, topPos + TOOLBAR_Y - 1,
            0f, 0f, 18, 36, 18, 36
        )
        renderPlayerInventory(graphics, leftPos, topPos + PANEL_H + 4)
        graphics.drawString(font, title, leftPos + (imageWidth - 8 - font.width(title)) / 2, topPos + 6, 0x505050, false)
        renderCanvas(graphics, mouseX, mouseY)
        val slotX = leftPos + SlideDraftingTableMenu.SKETCH_SLOT_X - 1
        val slotY = topPos + SlideDraftingTableMenu.SKETCH_SLOT_Y - 1
        graphics.blit(SLOT_FRAME, slotX, slotY, 0f, 0f, 18, 18, 18, 18)
    }

    private fun renderCanvas(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val ox = leftPos + CANVAS_X
        val oy = topPos + CANVAS_Y
        val cx = ox + CANVAS_W / 2f
        val cy = oy + CANVAS_H / 2f

        var hoverA = -1
        var hoverH = -1
        if (hasSketch) {
            val mx = ((mouseX - cx) / SCALE)
            val my = ((cy - mouseY) / SCALE)
            var bestD = 0.14
            for ((i, p) in anchors.withIndex()) {
                val d = hypot((p.first - mx).toDouble(), (p.second - my).toDouble())
                if (d < bestD) { bestD = d; hoverA = i }
            }
            if (hoverA < 0 && dragAnchor < 0) {
                bestD = 0.18
                for ((i, p) in anchors.withIndex()) {
                    if (i >= handles.size) continue
                    val hx = p.first + handles[i].first
                    val hy = p.second + handles[i].second
                    val d = hypot((hx - mx).toDouble(), (hy - my).toDouble())
                    if (d < bestD) { bestD = d; hoverH = i }
                }
            }
        }

        var addHint: Pair<Float, Float>? = null
        if (hasSketch && tool == Tool.ADD &&
            mouseX >= ox && mouseX <= ox + CANVAS_W && mouseY >= oy && mouseY <= oy + CANVAS_H
        ) {
            val mxU = ((mouseX - cx) / SCALE).toFloat()
            val myU = ((cy - mouseY) / SCALE).toFloat()
            addHint = nearestOnLoop(mxU, myU).second
        }

        val hintKey = if (addHint != null) "${(addHint.first * SCALE).toInt()},${(addHint.second * SCALE).toInt()}" else "-"
        val selfCross = hasSketch && loopSelfIntersects()
        val newKey = "$leftPos,$topPos,${anchors.hashCode()},${handles.hashCode()},${inHandles.hashCode()},${anchorModes.hashCode()},$hoverA,$hoverH,$dragAnchor,$dragHandle,${selectedAnchors.hashCode()},$snap,$hasSketch,$hintKey,$selfCross"
        if (newKey != cacheKey) {
            cacheKey = newKey
            val guide = RoughCanvas.PixelBuffer()
            val ink = RoughCanvas.PixelBuffer()
            val accent = RoughCanvas.PixelBuffer()
            val handleBuf = RoughCanvas.PixelBuffer()
            val warnBuf = RoughCanvas.PixelBuffer()

            for (gx in -2..2) {
                val px = cx + gx * SCALE
                if (px < ox + 2 || px > ox + CANVAS_W - 2) continue
                guide.ops(RoughCanvas.lineOps(px, oy + 2f, px, oy + CANVAS_H - 2f, RoughCanvas.Options(100L + gx * 17L)))
            }
            for (gy in -2..2) {
                val py = cy + gy * SCALE
                if (py < oy + 2 || py > oy + CANVAS_H - 2) continue
                guide.ops(RoughCanvas.lineOps(ox + 2f, py, ox + CANVAS_W - 2f, py, RoughCanvas.Options(300L + gy * 31L)))
            }
            guide.ops(RoughCanvas.circleOps(cx, cy, SCALE, RoughCanvas.Options(700L)))

            if (hasSketch) {
                val pts = anchors.map { (ax, ay) -> (cx + ax * SCALE) to (cy - ay * SCALE) }
                val outVecs = anchors.indices.map { i ->
                    if (i < handles.size) (handles[i].first * SCALE) to (-handles[i].second * SCALE) else 0f to 0f
                }
                val inVecs = anchors.indices.map { i ->
                    if (i < inHandles.size) (inHandles[i].first * SCALE) to (-inHandles[i].second * SCALE) else 0f to 0f
                }
                val curved = anchors.indices.map { i -> i < anchorModes.size && anchorModes[i] != BezierMode.NONE }
                val loopBuf = if (selfCross) warnBuf else ink
                if (curved.any { it }) {
                    loopBuf.ops(RoughCanvas.bezierLoopOps(pts, outVecs, inVecs, curved, RoughCanvas.Options(1000L + anchors.size * 37L)))
                } else {
                    loopBuf.ops(RoughCanvas.polygonOps(pts, RoughCanvas.Options(1000L + anchors.size * 37L)))
                }

                addHint?.let { (ux, uy) ->
                    val hx = cx + ux * SCALE
                    val hy = cy - uy * SCALE
                    accent.ops(RoughCanvas.circleOps(hx, hy, 3.5f, RoughCanvas.Options(9000L)))
                    accent.ops(RoughCanvas.lineOps(hx - 5f, hy, hx + 5f, hy, RoughCanvas.Options(9100L)))
                    accent.ops(RoughCanvas.lineOps(hx, hy - 5f, hx, hy + 5f, RoughCanvas.Options(9200L)))
                }

                if (showHandles) {
                    for ((i, p) in pts.withIndex()) {
                        if (i >= handles.size) continue
                        val mode = if (i < anchorModes.size) anchorModes[i] else BezierMode.SYMMETRIC
                        if (mode == BezierMode.NONE) continue

                        val h = handles[i]
                        val hx = p.first + h.first * SCALE
                        val hy = p.second - h.second * SCALE
                        val outActive = hoverH == i || dragHandle == i
                        val outBuf = if (outActive) accent else handleBuf
                        outBuf.ops(RoughCanvas.lineOps(p.first, p.second, hx, hy, RoughCanvas.Options(3000L + i * 53L)))
                        outBuf.ops(RoughCanvas.circleOps(hx, hy, 3f, RoughCanvas.Options(4000L + i * 59L)))

                        if (mode == BezierMode.FREE && i < inHandles.size) {
                            val ih = inHandles[i]
                            val ix = p.first + ih.first * SCALE
                            val iy = p.second - ih.second * SCALE
                            val inActive = dragInHandle == i
                            val inBuf = if (inActive) accent else handleBuf
                            inBuf.ops(RoughCanvas.lineOps(p.first, p.second, ix, iy, RoughCanvas.Options(7000L + i * 61L)))
                            inBuf.ops(RoughCanvas.circleOps(ix, iy, 3f, RoughCanvas.Options(8000L + i * 67L)))
                        }
                    }
                }

                for ((i, p) in pts.withIndex()) {
                    val isSelected = i in selectedAnchors
                    val isHover = hoverA == i
                    val isDrag = dragAnchor == i
                    val (size, buf) = when {
                        isDrag -> 7f to accent
                        isHover -> 6f to accent
                        isSelected -> 5f to accent
                        else -> 4f to ink
                    }
                    buf.ops(
                        RoughCanvas.polygonOps(
                            listOf(
                                (p.first - size / 2) to (p.second - size / 2),
                                (p.first + size / 2) to (p.second - size / 2),
                                (p.first + size / 2) to (p.second + size / 2),
                                (p.first - size / 2) to (p.second + size / 2)
                            ),
                            RoughCanvas.Options(5000L + i * 71L)
                        )
                    )
                    if (isSelected || isDrag) {
                        buf.ops(
                            RoughCanvas.polygonOps(
                                listOf(
                                    (p.first - size / 2 - 2) to (p.second - size / 2 - 2),
                                    (p.first + size / 2 + 2) to (p.second - size / 2 - 2),
                                    (p.first + size / 2 + 2) to (p.second + size / 2 + 2),
                                    (p.first - size / 2 - 2) to (p.second + size / 2 + 2)
                                ),
                                RoughCanvas.Options(6000L + i * 83L)
                            )
                        )
                    }
                }
            }

            staticGuide = guide
            staticInk = ink
            staticAccent = accent
            staticHandle = handleBuf
            staticWarn = warnBuf
        }

        staticGuide?.flush(graphics, ARGB_GUIDE)
        staticInk?.flush(graphics, ARGB_INK)
        staticWarn?.flush(graphics, ARGB_WARN)
        staticHandle?.flush(graphics, ARGB_HANDLE)
        staticAccent?.flush(graphics, ARGB_ACCENT)
    }

    private var lastSketchStack: ItemStack = ItemStack.EMPTY
    private var hasSketch = false

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val ox = leftPos + CANVAS_X
        val oy = topPos + CANVAS_Y
        val cx = ox + CANVAS_W / 2f
        val cy = oy + CANVAS_H / 2f
        if (hasSketch && mouseX >= ox && mouseX <= ox + CANVAS_W && mouseY >= oy && mouseY <= oy + CANVAS_H) {
            val mx = ((mouseX - cx) / SCALE).toFloat()
            val my = ((cy - mouseY) / SCALE).toFloat()
            when (tool) {
                Tool.ADD -> {
                    val (seg, proj) = nearestOnLoop(mx, my)
                    var px = proj.first
                    var py = proj.second
                    if (snap) {
                        px = (px * SNAP_STEPS).toInt() / SNAP_STEPS
                        py = (py * SNAP_STEPS).toInt() / SNAP_STEPS
                    }
                    px = px.coerceIn(-CANVAS_MAX_X, CANVAS_MAX_X)
                    py = py.coerceIn(-CANVAS_MAX_Y, CANVAS_MAX_Y)
                    pushUndo()
                    val insertAt = seg + 1
                    anchors.add(insertAt, px to py)
                    val prev = anchors[seg]
                    val next = anchors[(seg + 2) % anchors.size]
                    val dx = next.first - prev.first
                    val dy = next.second - prev.second
                    val len = maxOf(kotlin.math.sqrt(dx * dx + dy * dy), 0.01f)
                    val out = dx / len * 0.35f to dy / len * 0.35f
                    handles.add(insertAt, out)
                    inHandles.add(insertAt, -out.first to -out.second)
                    anchorModes.add(insertAt, BezierMode.SYMMETRIC)
                    selectedAnchors.clear()
                    selectedAnchors.add(insertAt)
                    refreshBezierButton()
                    updateToolbarState()
                    playPlaceTick()
                    return true
                }
                Tool.DELETE -> {
                    val toRemove = if (selectedAnchors.isNotEmpty()) selectedAnchors.toList()
                    else listOfNotNull(anchorAt(mx, my).takeIf { it >= 0 })
                    if (toRemove.isNotEmpty() && anchors.size - toRemove.size >= 3) {
                        pushUndo()
                        for (idx in toRemove.sortedDescending()) {
                            if (idx < anchors.size) {
                                anchors.removeAt(idx)
                                if (idx < handles.size) handles.removeAt(idx)
                                if (idx < inHandles.size) inHandles.removeAt(idx)
                                if (idx < anchorModes.size) anchorModes.removeAt(idx)
                            }
                        }
                        selectedAnchors.clear()
                        updateToolbarState()
                    }
                    return true
                }
                Tool.MOVE -> {
                    val inHit = inHandleAt(mx, my)
                    if (inHit >= 0) {
                        pushUndo()
                        dragInHandle = inHit
                        setFocused(this)
                        setDragging(true)
                        playGrab()
                        return true
                    }
                    val hHit = handleAt(mx, my)
                    if (hHit >= 0 && showHandles) {
                        pushUndo()
                        dragHandle = hHit
                        setFocused(this)
                        setDragging(true)
                        playGrab()
                        return true
                    }
                    val aHit = anchorAt(mx, my)
                    if (aHit >= 0) {
                        if (hasShiftDown()) {
                            if (!selectedAnchors.add(aHit)) selectedAnchors.remove(aHit)
                        } else {
                            if (aHit !in selectedAnchors) {
                                selectedAnchors.clear()
                                selectedAnchors.add(aHit)
                            }
                            pushUndo()
                            dragAnchor = aHit
                            setFocused(this)
                            setDragging(true)
                            playGrab()
                        }
                        refreshBezierButton()
                        updateToolbarState()
                        return true
                    }
                    if (selectedAnchors.isNotEmpty()) {
                        selectedAnchors.clear()
                        refreshBezierButton()
                        updateToolbarState()
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val ox = leftPos + CANVAS_X
        val oy = topPos + CANVAS_Y
        val cx = ox + CANVAS_W / 2f
        val cy = oy + CANVAS_H / 2f
        val mx = ((mouseX - cx) / SCALE).toFloat()
        val my = ((cy - mouseY) / SCALE).toFloat()

        if (dragAnchor >= 0) {
            var px = mx.coerceIn(-CANVAS_MAX_X, CANVAS_MAX_X)
            var py = my.coerceIn(-CANVAS_MAX_Y, CANVAS_MAX_Y)
            if (snap) {
                px = (px * SNAP_STEPS).toInt() / SNAP_STEPS
                py = (py * SNAP_STEPS).toInt() / SNAP_STEPS
            }
            anchors[dragAnchor] = px to py
            playDragTick(px, py)
            return true
        }
        if (dragHandle >= 0) {
            val anchor = anchors[dragHandle]
            var hx = (mx - anchor.first).coerceIn(-2.5f, 2.5f)
            var hy = (my - anchor.second).coerceIn(-2.5f, 2.5f)
            if (snap) {
                hx = (hx * SNAP_STEPS).toInt() / SNAP_STEPS
                hy = (hy * SNAP_STEPS).toInt() / SNAP_STEPS
            }
            hx = (anchor.first + hx).coerceIn(-CANVAS_MAX_X, CANVAS_MAX_X) - anchor.first
            hy = (anchor.second + hy).coerceIn(-CANVAS_MAX_Y, CANVAS_MAX_Y) - anchor.second
            handles[dragHandle] = hx to hy
            if (dragHandle < anchorModes.size && anchorModes[dragHandle] == BezierMode.SYMMETRIC && dragHandle < inHandles.size) {
                inHandles[dragHandle] = -hx to -hy
            }
            playDragTick(hx, hy)
            return true
        }
        if (dragInHandle >= 0) {
            val anchor = anchors[dragInHandle]
            var hx = (mx - anchor.first).coerceIn(-2.5f, 2.5f)
            var hy = (my - anchor.second).coerceIn(-2.5f, 2.5f)
            if (snap) {
                hx = (hx * SNAP_STEPS).toInt() / SNAP_STEPS
                hy = (hy * SNAP_STEPS).toInt() / SNAP_STEPS
            }
            hx = (anchor.first + hx).coerceIn(-CANVAS_MAX_X, CANVAS_MAX_X) - anchor.first
            hy = (anchor.second + hy).coerceIn(-CANVAS_MAX_Y, CANVAS_MAX_Y) - anchor.second
            inHandles[dragInHandle] = hx to hy
            if (dragInHandle < anchorModes.size && anchorModes[dragInHandle] == BezierMode.SYMMETRIC && dragInHandle < handles.size) {
                handles[dragInHandle] = -hx to -hy
            }
            playDragTick(hx, hy)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dragAnchor >= 0 || dragHandle >= 0 || dragInHandle >= 0) {
            dragAnchor = -1
            dragHandle = -1
            dragInHandle = -1
            updateToolbarState()
            dev.silvergold.simulatedcoasters.client.track.BezierHandleClientSounds
                .playCommitSuccessSound(net.minecraft.client.Minecraft.getInstance())
            lastSoundPos = null
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (hasControlDown()) {
            when (keyCode) {
                org.lwjgl.glfw.GLFW.GLFW_KEY_Z -> {
                    onUndo()
                    return true
                }
                org.lwjgl.glfw.GLFW.GLFW_KEY_Y -> {
                    onRedo()
                    return true
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private var lastDragTickMs = 0L
    private var lastSoundPos: Pair<Float, Float>? = null

    private fun playCreateUi(volume: Float, pitch: Float) {
        val event = com.simibubi.create.AllSoundEvents.SCROLL_VALUE.mainEvent ?: return
        dev.silvergold.simulatedcoasters.client.track.BezierHandleClientSounds.playCreateUi(event, volume, pitch)
    }

    private fun playGrab() = playCreateUi(0.6f, 0.7f)

    private fun playPlaceTick() = playCreateUi(0.5f, 1.0f)

    private fun playDragTick(x: Float, y: Float) {
        val last = lastSoundPos
        if (last != null && last.first == x && last.second == y) return
        lastSoundPos = x to y
        val now = System.currentTimeMillis()
        if (now - lastDragTickMs < 60) return
        lastDragTickMs = now
        playCreateUi(0.35f, 1.0f)
    }

    private fun anchorAt(mx: Float, my: Float): Int {
        var best = -1
        var bestD = 0.14
        for ((i, p) in anchors.withIndex()) {
            val d = hypot((p.first - mx).toDouble(), (p.second - my).toDouble())
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun nearestOnLoop(mx: Float, my: Float): Pair<Int, Pair<Float, Float>> {
        val n = anchors.size
        var bestSeg = 0
        var bestD = Double.MAX_VALUE
        var bestP = anchors.getOrElse(0) { 0f to 0f }
        val steps = 8
        for (i in 0 until n) {
            val j = (i + 1) % n
            val p0 = anchors[i]
            val p1 = anchors.getOrElse(j) { p0 }
            val iCurved = i < anchorModes.size && anchorModes[i] != BezierMode.NONE
            val jCurved = j < anchorModes.size && anchorModes[j] != BezierMode.NONE
            val out = handles.getOrElse(i) { 0f to 0f }
            val inH = if (j < inHandles.size) inHandles[j]
                else handles.getOrNull(j)?.let { (x, y) -> -x to -y } ?: (0f to 0f)
            val c1x = if (iCurved) p0.first + out.first else p0.first
            val c1y = if (iCurved) p0.second + out.second else p0.second
            val c2x = if (jCurved) p1.first + inH.first else p1.first
            val c2y = if (jCurved) p1.second + inH.second else p1.second
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val u = 1f - t
                val sxp = u * u * u * p0.first + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * p1.first
                val syp = u * u * u * p0.second + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * p1.second
                val d = hypot((sxp - mx).toDouble(), (syp - my).toDouble())
                if (d < bestD) {
                    bestD = d
                    bestSeg = i
                    bestP = sxp to syp
                }
            }
        }
        return bestSeg to bestP
    }

    private fun loopSelfIntersects(): Boolean {
        val n = anchors.size
        if (n < 3) return false
        val steps = 8
        val pts = ArrayList<Pair<Float, Float>>(n * steps)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val p0 = anchors[i]
            val p1 = anchors.getOrElse(j) { p0 }
            val iCurved = i < anchorModes.size && anchorModes[i] != BezierMode.NONE
            val jCurved = j < anchorModes.size && anchorModes[j] != BezierMode.NONE
            val out = handles.getOrElse(i) { 0f to 0f }
            val inH = if (j < inHandles.size) inHandles[j]
                else handles.getOrNull(j)?.let { (x, y) -> -x to -y } ?: (0f to 0f)
            val c1x = if (iCurved) p0.first + out.first else p0.first
            val c1y = if (iCurved) p0.second + out.second else p0.second
            val c2x = if (jCurved) p1.first + inH.first else p1.first
            val c2y = if (jCurved) p1.second + inH.second else p1.second
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val u = 1f - t
                pts += (u * u * u * p0.first + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * p1.first) to
                    (u * u * u * p0.second + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * p1.second)
            }
        }
        val m = pts.size
        fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
            (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        for (a in 0 until m) {
            val b = (a + 1) % m
            val (ax, ay) = pts[a]
            val (bx, by) = pts[b]
            for (c in a + 2 until m) {
                if (a == 0 && c == m - 1) continue
                val d = (c + 1) % m
                val (cx, cy) = pts[c]
                val (dx, dy) = pts[d]
                val d1 = orient(cx, cy, dx, dy, ax, ay)
                val d2 = orient(cx, cy, dx, dy, bx, by)
                if ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) {
                    val d3 = orient(ax, ay, bx, by, cx, cy)
                    val d4 = orient(ax, ay, bx, by, dx, dy)
                    if ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f)) return true
                }
            }
        }
        return false
    }

    private fun handleAt(mx: Float, my: Float): Int {
        var best = -1
        var bestD = 0.18
        for ((i, p) in anchors.withIndex()) {
            if (i >= handles.size) continue
            val hx = p.first + handles[i].first
            val hy = p.second + handles[i].second
            val d = hypot((hx - mx).toDouble(), (hy - my).toDouble())
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun inHandleAt(mx: Float, my: Float): Int {
        var best = -1
        var bestD = 0.18
        for ((i, p) in anchors.withIndex()) {
            if (i >= inHandles.size || i >= anchorModes.size) continue
            if (anchorModes[i] != BezierMode.FREE) continue
            val hx = p.first + inHandles[i].first
            val hy = p.second + inHandles[i].second
            val d = hypot((hx - mx).toDouble(), (hy - my).toDouble())
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun refreshBezierButton() {
        val modes = selectedAnchors.mapNotNull { i -> anchorModes.getOrNull(i) }.toSet()
        val icon = when {
            modes.size == 1 && modes.first() == BezierMode.NONE -> TextureIcon.BEZIER_NONE
            modes.size == 1 && modes.first() == BezierMode.SYMMETRIC -> TextureIcon.BEZIER_SYMMETRIC
            modes.isEmpty() -> TextureIcon.BEZIER_NONE
            else -> TextureIcon.BEZIER_FREE
        }
        btnHandles?.setIcon(icon)
        btnHandles?.setToolTip(bezierTooltip())
    }
}
