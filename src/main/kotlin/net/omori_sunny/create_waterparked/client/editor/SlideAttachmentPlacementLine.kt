package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.content.logistics.packagePort.PackagePortTargetSelectionHandler
import net.createmod.catnip.outliner.Outliner
import net.createmod.catnip.theme.Color
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentRenderer
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents

@OnlyIn(Dist.CLIENT)
object SlideAttachmentPlacementLine {

    private const val KEY = "create_waterparked:attachment_site"

    private const val FACE_KEY = "create_waterparked:attachment_face"

    private val VALID = Color(0x9EDE73)
    private val INVALID = Color(0xFF7171)

    private var shown = false
    private var faceShown = false

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val stack = player.mainHandItem
        val item = stack.item as? SlideAttachmentBlockItem
        val pos = if (item == null) null else stack.get(ModDataComponents.SLIDE_ATTACHMENT_POS)
        if (item == null || pos == null || player.isShiftKeyDown) {
            clear()
            return
        }
        val type = item.type()
        val color = if (item.validatePlacement(level, BlockPos.ZERO, type, pos) == null) VALID else INVALID
        val curve = SlideAttachmentEdit.resolveCurve(level, pos.curveA, pos.curveB)
        val target = if (curve == null) {
            Vec3.atBottomCenterOf(pos.curveA)
        } else {
            val ctx = SlideAttachmentGeometry.contextAt(
                level, BlockPos.ZERO, curve, pos.t, pos.angle, CompoundTag(),
                SlideAttachmentRenderer.renderTransform(level, pos.curveA)
            )
            SlideAttachmentGeometry.worldBounds(ctx, type.providerFactory().boundingBox(ctx)).center
        }
        Outliner.getInstance()
            .chaseAABB(KEY, AABB(target, target))
            .colored(color)
            .lineWidth(1 / 16f)
            .disableLineNormals()
        shown = true
        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type == HitResult.Type.MISS) return
        Outliner.getInstance()
            .chaseAABB(FACE_KEY, faceOutline(hit))
            .colored(color)
            .lineWidth(1 / 16f)
            .disableLineNormals()
        faceShown = true
        val landing = if (level.getBlockState(hit.blockPos).canBeReplaced()) hit.blockPos
        else hit.blockPos.relative(hit.direction)
        PackagePortTargetSelectionHandler.animateConnection(mc, Vec3.atBottomCenterOf(landing), target, color)
    }

    private fun faceOutline(hit: BlockHitResult): AABB {
        val box = AABB(hit.blockPos)
        return when (hit.direction) {
            Direction.DOWN -> box.contract(0.0, 1.0, 0.0).deflate(0.125, 0.0, 0.125)
            Direction.UP -> box.contract(0.0, -1.0, 0.0).deflate(0.125, 0.0, 0.125)
            Direction.NORTH -> box.contract(0.0, 0.0, 1.0).deflate(0.125, 0.125, 0.0)
            Direction.SOUTH -> box.contract(0.0, 0.0, -1.0).deflate(0.125, 0.125, 0.0)
            Direction.WEST -> box.contract(1.0, 0.0, 0.0).deflate(0.0, 0.125, 0.125)
            Direction.EAST -> box.contract(-1.0, 0.0, 0.0).deflate(0.0, 0.125, 0.125)
        }
    }

    private fun clear() {
        if (!shown && !faceShown) return
        Outliner.getInstance().remove(KEY)
        Outliner.getInstance().remove(FACE_KEY)
        shown = false
        faceShown = false
    }
}
