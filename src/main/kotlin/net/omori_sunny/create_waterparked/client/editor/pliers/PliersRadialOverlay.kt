package net.omori_sunny.create_waterparked.client.editor.pliers

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.simibubi.create.AllSoundEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.network.chat.Component
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditSounds
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW
import kotlin.math.cos
import kotlin.math.sin

// ctrl-held radial settings dial drawn as a gui overlay, not a screen
object PliersRadialOverlay {

    private var open = false
    private var alpha = 0f

    private var readout: String? = null
    private var readoutAt = 0L
    private var readoutAlpha = 0f

    private val ringRotation = FloatArray(3)
    private val ringRotationTarget = FloatArray(3)
    private val ringSelAccum = IntArray(3)

    private var hlR0 = -1f
    private var hlR1 = -1f
    private var hlSpan = -1f

    private val rlCache = HashMap<String, ResourceLocation>()

    private fun rl(name: String) = rlCache.getOrPut(name) {
        ResourceLocation.fromNamespaceAndPath(
            CreateWaterparked.ID, "textures/gui/pliers/$name.png"
        )
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        open = mc.screen == null && BrassPliersEditor.holdsPlier(mc) &&
            net.minecraft.client.gui.screens.Screen.hasControlDown()
    }

    fun onKey(event: InputEvent.Key) {
        if (event.action != GLFW.GLFW_PRESS || event.key != GLFW.GLFW_KEY_TAB) return
        if (!open) return
        BrassPliersModes.ring = BrassPliersModes.ring.next()
        tickSound(1.0f)
    }

    fun onMouseScroll(event: InputEvent.MouseScrollingEvent) {
        if (!open) return
        event.isCanceled = true
        val ring = BrassPliersModes.ring
        val n = entriesOf(ring).size
        val idx = ring.ordinal
        ringSelAccum[idx] += if (event.scrollDeltaY > 0) 1 else -1
        val sector = (2.0 * Math.PI / n).toFloat()
        ringRotationTarget[idx] = -sector * ringSelAccum[idx]
        BrassPliersModes.cycleInRing(event.scrollDeltaY > 0)
        tickSound(1.2f)
    }

    fun showReadout(text: String) {
        readout = text
        readoutAt = System.currentTimeMillis()
    }

    private fun tickSound(pitch: Float) {
        WaterslideEditSounds.playUi(AllSoundEvents.SCROLL_VALUE.mainEvent, 0.4f, pitch)
    }

    @JvmStatic
    fun onGuiRender(event: RenderGuiEvent.Post) {
        val graphics = event.guiGraphics

        val r = readout
        if (r != null) {
            val age = System.currentTimeMillis() - readoutAt
            val target = if (age < 1000L) 1f else 0f
            readoutAlpha += (target - readoutAlpha) * 0.25f
            if (age > 1000L && readoutAlpha < 0.02f) {
                readout = null
            } else {
                graphics.drawCenteredString(
                    Minecraft.getInstance().font, Component.literal(r),
                    graphics.guiWidth() / 2, graphics.guiHeight() / 2 + 40,
                    ((readoutAlpha.coerceIn(0f, 1f) * 224).toInt() shl 24) or 0xE0E0E0
                )
            }
        }

        if (!open && alpha < 0.02f) {
            alpha = 0f
            return
        }
        alpha += ((if (open) 1f else 0f) - alpha) * 0.35f
        val fade = alpha.coerceIn(0f, 1f)

        for (i in ringRotation.indices) {
            ringRotation[i] += (ringRotationTarget[i] - ringRotation[i]) * 0.3f
        }

        val cx = graphics.guiWidth() / 2
        val cy = graphics.guiHeight() / 2 + CY_OFFSET
        val font = Minecraft.getInstance().font

        for (ring in PlierRing.entries) {
            val (r0, r1) = RING_RADII[ring.ordinal]
            val base = if (ring == BrassPliersModes.ring) 0.74f else 0.58f
            annulus(graphics, cx.toFloat(), cy.toFloat(), r0, r1, base * fade)
        }

        val focused = BrassPliersModes.ring
        val (tr0, tr1) = RING_RADII[focused.ordinal]
        val tSpan = (Math.PI / entriesOf(focused).size).toFloat()
        if (hlR0 < 0f) { hlR0 = tr0; hlR1 = tr1; hlSpan = tSpan }
        hlR0 += (tr0 - hlR0) * 0.25f
        hlR1 += (tr1 - hlR1) * 0.25f
        hlSpan += (tSpan - hlSpan) * 0.25f
        annulus(graphics, cx.toFloat(), cy.toFloat(), hlR0 - 2, hlR1 + 2, 0.55f * fade,
            -Math.PI / 2 - hlSpan, -Math.PI / 2 + hlSpan, tint = 0xFFE089.toInt())

        for (ring in PlierRing.entries) {
            val idx = ring.ordinal
            val entries = entriesOf(ring)
            val n = entries.size
            val sector = 2.0 * Math.PI / n
            val (r0, r1) = RING_RADII[idx]
            val iconR = (r0 + r1) / 2
            for ((i, e) in entries.withIndex()) {
                val mid = -Math.PI / 2 + sector * i + ringRotation[idx]
                val ix = cx + (cos(mid) * iconR).toInt() - ICON / 2
                val iy = cy + (sin(mid) * iconR).toInt() - ICON / 2
                RenderSystem.enableBlend()
                graphics.blit(e.icon, ix, iy, 0f, 0f, ICON, ICON, ICON, ICON)
                graphics.drawCenteredString(
                    font,
                    Component.literal(net.minecraft.client.resources.language.I18n.get(e.label)),
                    ix + ICON / 2, iy + ICON,
                    ((fade * 210).toInt() shl 24) or 0xC8C8D0
                )
            }
        }

        graphics.drawCenteredString(
            font,
            Component.literal("Tab 切换环 · Ctrl+滚轮 切换设置"),
            cx, cy + 104, ((fade * 200).toInt() shl 24) or 0xB8B8C0
        )
    }

    private class Entry(val icon: ResourceLocation, val label: String)

    private fun entriesOf(ring: PlierRing): List<Entry> = when (ring) {
        PlierRing.ALIGN -> listOf(
            Entry(rl("cart"), PlierAlign.CARTESIAN.langKey),
            Entry(rl("polar"), PlierAlign.POLAR.langKey)
        )
        PlierRing.SPACE -> listOf(
            Entry(rl("abs"), PlierSpace.ABSOLUTE.langKey),
            Entry(rl("rel"), PlierSpace.RELATIVE.langKey)
        )
        PlierRing.PLANE -> listOf(
            Entry(rl("oxy"), PlierPlane.OXY.langKey),
            Entry(rl("oxz"), PlierPlane.OXZ.langKey),
            Entry(rl("oyz"), PlierPlane.OYZ.langKey)
        )
    }

    private fun annulus(
        graphics: GuiGraphics,
        cx: Float, cy: Float,
        r0: Float, r1: Float,
        alpha: Float,
        a0: Double = 0.0,
        a1: Double = 2.0 * Math.PI,
        tint: Int = 0
    ) {
        val r: Float
        val g: Float
        val b: Float
        if (tint != 0) {
            r = ((tint shr 16) and 255).toFloat() / 255f
            g = ((tint shr 8) and 255).toFloat() / 255f
            b = (tint and 255).toFloat() / 255f
        } else {
            r = 0.06f; g = 0.06f; b = 0.07f
        }
        val a = alpha.coerceIn(0f, 1f)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        val builder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR)
        val pose: Matrix4f = graphics.pose().last().pose()

        val steps = 96
        for (i in 0..steps) {
            val t = a0 + (a1 - a0) * i / steps
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()
            builder.addVertex(pose, cx + c * r1, cy + s * r1, 0f).setColor(r, g, b, a)
            builder.addVertex(pose, cx + c * r0, cy + s * r0, 0f).setColor(r, g, b, a)
        }

        BufferUploader.drawWithShader(builder.buildOrThrow())
        RenderSystem.disableBlend()
    }

    private const val ICON = 16
    private const val CY_OFFSET = -24

    private val RING_RADII = listOf(22f to 38f, 48f to 64f, 74f to 92f)
}
