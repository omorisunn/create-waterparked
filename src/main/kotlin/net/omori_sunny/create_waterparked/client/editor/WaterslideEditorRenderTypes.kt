package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.HashMap
import java.util.OptionalDouble
import kotlin.math.abs

// editor render types
object WaterslideEditorRenderTypes {

    // reserved boundary textures
    val SECTOR_BOUNDARY_HANDLE_DEFAULT: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/ui/sector_boundary_handle.png")
    val SECTOR_BOUNDARY_HANDLE_HOVER: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/ui/sector_boundary_handle_hover.png")
    val SECTOR_BOUNDARY_HANDLE_DRAGGING: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/ui/sector_boundary_handle_drag.png")

    private val boundaryBillboards = HashMap<ResourceLocation, RenderType>()

    val SEE_THROUGH_LINES: RenderType = RenderType.create(
        "create_waterparked:waterslide_editor_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(RenderStateShard.LineStateShard(OptionalDouble.empty()))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false)
    )

    val COLORED_QUADS: RenderType = RenderType.create(
        "create_waterparked:waterslide_editor_quads",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        1536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false)
    )

    // texture billboard
    @JvmStatic
    fun boundaryHandleBillboard(texture: ResourceLocation): RenderType =
        boundaryBillboards.getOrPut(texture) {
            RenderType.create(
                "create_waterparked:sector_boundary_bb_" +
                    texture.namespace + "_" + texture.path.replace('/', '_'),
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false)
            )
        }

    @JvmStatic
    fun endBoundaryHandleBillboardBatches(bufferSource: MultiBufferSource.BufferSource) {
        bufferSource.endBatch(boundaryHandleBillboard(SECTOR_BOUNDARY_HANDLE_DEFAULT))
        bufferSource.endBatch(boundaryHandleBillboard(SECTOR_BOUNDARY_HANDLE_HOVER))
        bufferSource.endBatch(boundaryHandleBillboard(SECTOR_BOUNDARY_HANDLE_DRAGGING))
    }

    // eye-space quad
    @JvmStatic
    fun billboardQuad(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        centerWorld: Vec3,
        halfExtent: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val mat = poseStack.last().pose()
        val eye = worldToEye(cameraPos, cameraRotation, centerWorld)
        val h = halfExtent
        consumer.addVertex(mat, eye.x - h, eye.y - h, eye.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eye.x + h, eye.y - h, eye.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eye.x + h, eye.y + h, eye.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eye.x - h, eye.y + h, eye.z).setColor(r, g, b, a)
    }

    // camera strip
    @JvmStatic
    fun billboardStrip(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        tailWorld: Vec3,
        tipWorld: Vec3,
        halfWidth: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val delta = tipWorld.subtract(tailWorld)
        val len = delta.length()
        if (len < 1.0E-6) return
        val axis = delta.scale(1.0 / len)
        val mid = tailWorld.add(tipWorld).scale(0.5)
        var view = cameraPos.subtract(mid)
        val vlen = Math.sqrt(view.lengthSqr())
        if (vlen < 1.0E-9) return
        view = view.scale(1.0 / vlen)
        var right = view.cross(axis)
        if (right.lengthSqr() < 1.0E-10) {
            val ref = if (abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            right = ref.cross(axis)
            if (right.lengthSqr() < 1.0E-12) return
        }
        right = right.normalize()
        val hw = halfWidth.toDouble()
        val tailL = tailWorld.subtract(right.scale(hw))
        val tailR = tailWorld.add(right.scale(hw))
        val tipL = tipWorld.subtract(right.scale(hw))
        val tipR = tipWorld.add(right.scale(hw))
        val mat = poseStack.last().pose()
        val eTailL = worldToEye(cameraPos, cameraRotation, tailL)
        val eTailR = worldToEye(cameraPos, cameraRotation, tailR)
        val eTipR = worldToEye(cameraPos, cameraRotation, tipR)
        val eTipL = worldToEye(cameraPos, cameraRotation, tipL)
        consumer.addVertex(mat, eTailL.x, eTailL.y, eTailL.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eTailR.x, eTailR.y, eTailR.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eTipR.x, eTipR.y, eTipR.z).setColor(r, g, b, a)
        consumer.addVertex(mat, eTipL.x, eTipL.y, eTipL.z).setColor(r, g, b, a)
    }

    // textured quad
    @JvmStatic
    fun billboardTexturedQuad(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        centerWorld: Vec3,
        halfExtent: Float
    ) {
        val mat = poseStack.last().pose()
        val eye = worldToEye(cameraPos, cameraRotation, centerWorld)
        val h = halfExtent
        consumer.addVertex(mat, eye.x - h, eye.y - h, eye.z).setUv(0f, 1f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eye.x + h, eye.y - h, eye.z).setUv(1f, 1f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eye.x + h, eye.y + h, eye.z).setUv(1f, 0f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eye.x - h, eye.y + h, eye.z).setUv(0f, 0f).setColor(1f, 1f, 1f, 1f)
    }

    // oriented textured quad
    @JvmStatic
    fun billboardOrientedTexturedQuad(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        centerWorld: Vec3,
        upWorld: Vec3,
        halfW: Float,
        halfH: Float
    ) {
        var up = upWorld
        var view = cameraPos.subtract(centerWorld)
        val vlen = Math.sqrt(view.lengthSqr())
        if (vlen < 1.0E-9) return
        view = view.scale(1.0 / vlen)
        var right = view.cross(up)
        if (right.lengthSqr() < 1.0E-10) {
            val ref = if (abs(up.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            right = ref.cross(up)
            if (right.lengthSqr() < 1.0E-12) return
        }
        right = right.normalize()
        up = up.normalize()
        val mat = poseStack.last().pose()
        val hw = halfW.toDouble()
        val hh = halfH.toDouble()
        val bl = centerWorld.subtract(right.scale(hw)).subtract(up.scale(hh))
        val br = centerWorld.add(right.scale(hw)).subtract(up.scale(hh))
        val tr = centerWorld.add(right.scale(hw)).add(up.scale(hh))
        val tl = centerWorld.subtract(right.scale(hw)).add(up.scale(hh))
        val eBl = worldToEye(cameraPos, cameraRotation, bl)
        val eBr = worldToEye(cameraPos, cameraRotation, br)
        val eTr = worldToEye(cameraPos, cameraRotation, tr)
        val eTl = worldToEye(cameraPos, cameraRotation, tl)
        consumer.addVertex(mat, eBl.x, eBl.y, eBl.z).setUv(0f, 1f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eBr.x, eBr.y, eBr.z).setUv(1f, 1f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eTr.x, eTr.y, eTr.z).setUv(1f, 0f).setColor(1f, 1f, 1f, 1f)
        consumer.addVertex(mat, eTl.x, eTl.y, eTl.z).setUv(0f, 0f).setColor(1f, 1f, 1f, 1f)
    }

    // world to eye
    @JvmStatic
    fun worldToEye(cameraPos: Vec3, cameraRotation: Matrix4f, world: Vec3): Vector3f {
        val v = Vector3f(
            (world.x - cameraPos.x).toFloat(),
            (world.y - cameraPos.y).toFloat(),
            (world.z - cameraPos.z).toFloat()
        )
        cameraRotation.transformPosition(v)
        return v
    }

    // eye line
    @JvmStatic
    fun billboardLine(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        p0: Vec3,
        p1: Vec3,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val mat = poseStack.last().pose()
        val e0 = worldToEye(cameraPos, cameraRotation, p0)
        val e1 = worldToEye(cameraPos, cameraRotation, p1)
        consumer.addVertex(mat, e0.x, e0.y, e0.z).setColor(r, g, b, a).setNormal(poseStack.last(), 0f, 1f, 0f)
        consumer.addVertex(mat, e1.x, e1.y, e1.z).setColor(r, g, b, a).setNormal(poseStack.last(), 0f, 1f, 0f)
    }
}
