package net.omori_sunny.create_waterparked.client.attachment

import com.mojang.blaze3d.vertex.PoseStack
import net.createmod.catnip.animation.LerpedFloat
import net.createmod.catnip.gui.UIRenderHelper
import net.createmod.catnip.gui.element.BoxElement
import net.createmod.ponder.foundation.ui.PonderProgressBar
import net.createmod.ponder.foundation.ui.PonderUI
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.PacketDistributor
import net.omori_sunny.create_waterparked.client.SlideClientSession
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.network.GrabBarInputPayload

// client mirror of a grab bar hold: pins the body, poses and draws the charge bar
@OnlyIn(Dist.CLIENT)
object GrabBarHoldClient {

    private const val TIMEOUT_TICKS = 10L
    private const val TRANSITION_TICKS = 7.0
    private const val CHASE_SPEED = 0.35
    private const val BAR_WIDTH = 220
    private const val BAR_HEIGHT = 1
    private const val BAR_ROW_LIFT = 14
    private const val HINT_LIFT = 16
    private const val PROGRESS_NONE = -1f
    private const val PROGRESS_LAUNCH = -2f

    private class Hold(
        var x: Double,
        var y: Double,
        var z: Double,
        var yaw: Float,
        var progress: Float,
        var seated: Boolean,
        var armPitch: Float,
        var grip: Vec3?,
        var tick: Long,
        var transitioning: Boolean = false,
        var launchTick: Long = -1L,
        var chargeStart: Long = -1L
    ) {
        val smoothProgress = LerpedFloat.linear().startWithValue(0.0)
        val smoothArmPitch = LerpedFloat.linear().startWithValue(0.0)
    }

    private val holds = HashMap<Int, Hold>()
    private val barProgress = LerpedFloat.linear().startWithValue(0.0)

    @JvmStatic
    fun isHolding(entityId: Int): Boolean = holds[entityId]?.let { !it.transitioning } ?: false

    // -1 leaves the model untouched, 1 is the full holding pose, easing to 0 across a launch
    @JvmStatic
    fun poseBlendFor(entityId: Int, partialTick: Float): Float {
        val hold = holds[entityId] ?: return -1f
        if (!hold.transitioning) return 1f
        val level = Minecraft.getInstance().level ?: return -1f
        val elapsed = (level.gameTime - hold.launchTick).toDouble() + partialTick
        val t = (elapsed / TRANSITION_TICKS).coerceIn(0.0, 1.0).toFloat()
        return 1f - t * t * (3f - 2f * t)
    }

    @JvmStatic
    fun yawFor(entityId: Int): Float = holds[entityId]?.yaw ?: 0f

    @JvmStatic
    fun seatedFor(entityId: Int): Boolean = holds[entityId]?.seated ?: false

    @JvmStatic
    fun smoothedProgressFor(entityId: Int, partialTick: Float): Float =
        holds[entityId]?.smoothProgress?.getValue(partialTick) ?: PROGRESS_NONE

    @JvmStatic
    fun smoothedArmPitchFor(entityId: Int, partialTick: Float): Float =
        holds[entityId]?.smoothArmPitch?.getValue(partialTick) ?: 0f

    @JvmStatic
    fun gripFor(entityId: Int): Vec3? = holds[entityId]?.grip

    @JvmStatic
    fun chargeTickFor(entityId: Int, partialTick: Float): Float {
        val hold = holds[entityId] ?: return -1f
        if (hold.chargeStart < 0L || hold.progress <= 0f) return 0f
        val level = Minecraft.getInstance().level ?: return -1f
        return (level.gameTime - hold.chargeStart).toFloat() + partialTick
    }

    @JvmStatic
    fun chargeTicks(): Float = ModConfig.grabChargeTicks().toFloat()

    fun apply(
        entityId: Int,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        progress: Float,
        seated: Boolean,
        armPitch: Float,
        gripX: Double,
        gripY: Double,
        gripZ: Double
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (progress <= PROGRESS_LAUNCH) {
            // the launch only eases the pose away, the session owns the player from here
            val launched = holds[entityId] ?: return
            launched.transitioning = true
            launched.launchTick = level?.gameTime ?: 0L
            return
        }
        if (progress < 0f) {
            // a backward let go snaps, it is not a transition
            holds.remove(entityId)
            return
        }
        val existing = holds[entityId]
        if (existing != null && existing.transitioning) {
            existing.transitioning = false
            existing.launchTick = -1L
        }
        val grip = Vec3(gripX, gripY, gripZ)
        val hold = existing ?: Hold(x, y, z, yaw, progress, seated, armPitch, grip, 0L).also {
            // a fresh hold starts where it is, it never eases up from nothing
            it.smoothProgress.setValue(progress.toDouble())
            it.smoothArmPitch.setValue(armPitch.toDouble())
            holds[entityId] = it
            if (entityId == mc.player?.id) mc.gui.setOverlayMessage(Component.empty(), false)
        }
        hold.x = x
        hold.y = y
        hold.z = z
        hold.yaw = yaw
        hold.progress = progress
        hold.seated = seated
        hold.armPitch = armPitch
        hold.grip = grip
        hold.tick = level?.gameTime ?: 0L
        if (progress <= 0f || hold.chargeStart < 0L) hold.chargeStart = hold.tick
    }

    fun clear() {
        holds.clear()
        barProgress.setValue(0.0)
    }

    fun onClientTick() {
        val level = Minecraft.getInstance().level ?: return clear()
        val ended = holds.entries.filter {
            it.value.transitioning && level.gameTime - it.value.launchTick >= TRANSITION_TICKS.toLong()
        }
        for (entry in ended) holds.remove(entry.key)
        val stale = holds.entries.filter {
            !it.value.transitioning && level.gameTime - it.value.tick > TIMEOUT_TICKS
        }
        for (entry in stale) holds.remove(entry.key)
        val player = Minecraft.getInstance().player
        val hold = if (player == null) null else holds[player.id]
        if (hold == null) {
            // a release must not ease out of a hold that no longer exists
            barProgress.setValue(0.0)
            return
        }
        for (entry in holds.values) {
            entry.smoothProgress.chase(entry.progress.toDouble(), CHASE_SPEED, LerpedFloat.Chaser.EXP)
            entry.smoothProgress.tickChaser()
            entry.smoothArmPitch.chase(entry.armPitch.toDouble(), CHASE_SPEED, LerpedFloat.Chaser.EXP)
            entry.smoothArmPitch.tickChaser()
        }
        if (!hold.transitioning) sendInput()
        barProgress.chase(hold.progress.toDouble(), 0.5, LerpedFloat.Chaser.EXP)
        barProgress.tickChaser()
    }

    // the hanging client owns its key state, the server cannot read it from the mounted vanilla path
    private fun sendInput() {
        val options = Minecraft.getInstance().options
        PacketDistributor.sendToServer(
            GrabBarInputPayload(options.keyUp.isDown, options.keyDown.isDown)
        )
    }

    // the seat the server put the player on suppresses the rider's own movement
    @JvmStatic
    fun onClientTickPost(event: net.neoforged.neoforge.client.event.ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val hold = holds[player.id] ?: return
        if (hold.transitioning) return
        if (SlideClientSession.isSliding()) return
        // a degenerate interpolation keeps the render origin exactly on the pinned point
        player.setPos(hold.x, hold.y, hold.z)
        player.xo = hold.x
        player.yo = hold.y
        player.zo = hold.z
        player.xOld = hold.x
        player.yOld = hold.y
        player.zOld = hold.z
        player.setDeltaMovement(Vec3.ZERO)
        player.fallDistance = 0f
        // only the body faces the bar, view yaw and pitch stay free
        player.yBodyRotO = hold.yaw
        player.setYBodyRot(hold.yaw)
    }

    // the ponder progress bar look, without a ponder scene to drive it
    fun renderHud(gui: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!isHolding(player.id)) return
        if (mc.options.hideGui || mc.screen != null) return
        val x = (gui.guiWidth() - BAR_WIDTH) / 2
        val y = dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
            .editHudRowAboveHotbarY(mc, gui) - BAR_ROW_LIFT
        val hint = if (ModClientConfig.showSlideExitHint()) {
            Component.translatable(
                "create_waterparked.grab_bar.hold_hint",
                mc.options.keyUp.getTranslatedKeyMessage(),
                mc.options.keyDown.getTranslatedKeyMessage()
            )
        } else {
            null
        }
        if (hint != null) {
            val hintWidth = mc.font.width(hint)
            gui.drawStringWithBackdrop(
                mc.font, hint, (gui.guiWidth() - hintWidth) / 2, y - HINT_LIFT, hintWidth, 0xFFFFFF
            )
        }
        val box = BoxElement()
        box.withBackground<BoxElement>(PonderUI.BACKGROUND_FLAT)
        box.gradientBorder<BoxElement>(PonderUI.COLOR_IDLE)
        box.at<BoxElement>(x.toFloat(), y.toFloat(), 400f)
        box.withBounds<BoxElement>(BAR_WIDTH, BAR_HEIGHT)
        box.render(gui)
        val pose: PoseStack = gui.pose()
        pose.pushPose()
        pose.translate((x - 2).toDouble(), (y - 2).toDouble(), 100.0)
        pose.pushPose()
        pose.scale((BAR_WIDTH + 4) * barProgress.getValue(partialTick), 1f, 1f)
        val top = PonderProgressBar.BAR_COLORS.getFirst()
        val bottom = PonderProgressBar.BAR_COLORS.getSecond()
        UIRenderHelper.drawGradientRect(pose.last().pose(), 310, 0f, 1f, 1f, 3f, top, top)
        UIRenderHelper.drawGradientRect(pose.last().pose(), 310, 0f, 3f, 1f, 4f, bottom, bottom)
        pose.popPose()
        pose.popPose()
    }
}
