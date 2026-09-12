package net.omori_sunny.create_waterparked.client.attachment

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelProvider
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment

// draws every live attachment from its provider parts, mirroring the support
// beam/bracket rendering architecture: block-atlas cutout quads, constant
// white vertex colour (no tint, no manual shading), one real light sample per
// part, and per-block tiled sprite UVs (one quad per block of face height).
// One draw routine serves the real world (level stage) and worlds without
// flywheel visualization (BER).
@OnlyIn(Dist.CLIENT)
object SlideAttachmentRenderer {

    @JvmStatic
    fun clear() {
        clipCaches.clear()
    }

    // clip results cached per SAB - the CSG is far too heavy for every frame
    private class ClipCache(val signature: String, val polys: List<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon>)
    private val clipCaches = HashMap<BlockPos, ClipCache>()

    // triangle buffer for CSG-clipped geometry (n-gon output), block atlas
    private val ATTACH_TRI_CUTOUT: RenderType = RenderType.create(
        "create_waterparked:attachment_tri_cutout",
        com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
        262144,
        RenderType.CompositeState.builder()
            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.BLOCK_SHEET_MIPPED)
            .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
            .setCullState(net.minecraft.client.renderer.RenderStateShard.CULL)
            .createCompositeState(true)
    )

    // translucent variant used while the attachment is being edited
    private val ATTACH_TRI_TRANSLUCENT: RenderType = RenderType.create(
        "create_waterparked:attachment_tri_translucent",
        com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
        131072,
        RenderType.CompositeState.builder()
            // mirror vanilla RenderType.translucent() exactly: the translucent
            // output target and a colour-only write mask, otherwise the depth
            // written by the front faces hides the blending behind them
            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.BLOCK_SHEET_MIPPED)
            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TARGET)
            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
            .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
            .createCompositeState(true)
    )

    /** draw all attachments; origin = world position to subtract from the pose */
    fun renderAll(poseStack: PoseStack, buffers: MultiBufferSource, origin: Vec3, partialTick: Float) {
        for (be in SlideAttachmentClientIndex.all()) {
            if (be.isRemoved) continue
            renderOne(be, poseStack, buffers, origin, partialTick)
        }
    }

    fun renderOne(
        be: SlideAttachmentBlockEntity,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        origin: Vec3,
        partialTick: Float
    ) {
        val entry = be.entry ?: return
        val type = SlideAttachmentTypes.byTypeIdString(entry.typeId) ?: return
        val level = be.level ?: return
        val resolved = SlideAttachmentGeometry.resolve(
            level, entry.curveA, entry.curveB, entry.t, entry.angle, be.blockPos, entry.data,
            renderTransform(level, entry.curveA)
        ) ?: return
        val ctx = resolved.context
        val provider = type.providerFactory()

        val sprite = try {
            net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
                .supportSprite(be.attachmentMaterial)
        } catch (_: Exception) {
            null
        }

        // translucency derives from the same editor set that draws the control
        // points, so a visible handle implies a translucent model
        val editing = net.omori_sunny.create_waterparked.client.editor.controlpoint
            .SlideControlPointEditor.isEditingAt(be.blockPos) ||
            net.omori_sunny.create_waterparked.client.editor.SlideAttachmentEdit.isEditing(be)
        val alpha = if (editing) (0.35f * 255).toInt() else 255
        editAlpha = alpha
        // edge-triggered: a sampled probe can miss a flip-flop, this cannot
        val prevEdit = lastEditState.put(be.blockPos, editing)
        if (prevEdit != null && prevEdit != editing) {
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SADebug] translucency {}->{} for {}", prevEdit, editing, be.blockPos
            )
        }
        poseStack.pushPose()
        poseStack.translate(-origin.x, -origin.y, -origin.z)
        // collect raw part polygons, then run the ghost-block CSG against the
        // tube wall prisms: proper cut faces, no texture or winding artefacts
        var polys = ArrayList<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon>()
        val manualQuads = ArrayList<SlideAttachmentModelProvider.Quad>()
        for (part in provider.parts(ctx)) {
            when (part) {
                is SlideAttachmentModelProvider.BoxPart ->
                    polys.addAll(drawBox(part, ctx, sprite, level))
                is SlideAttachmentModelProvider.PolygonPart ->
                    manualQuads.addAll(part.quads)
                is SlideAttachmentModelProvider.ModelPart ->
                    drawModel(part, ctx, poseStack, buffers, level)
            }
        }
        // clip: intersect the door with one convex prism at the door's own
        // cross-section (inner polygon ring extruded along the tangent)
        val csg = net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg
        val innerSolid = innerPrismAt(ctx)
        var clipped: List<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon> = polys
        val sig = buildString {
            append(entry.t).append('|').append(entry.angle).append('|')
                .append(net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorProvider
                    .smoothedOpen(be.blockPos, entry.data.getFloat(MechanicalDoorAttachment.TAG_OPEN))).append('|')
                .append(entry.data.getInt(MechanicalDoorAttachment.TAG_MODE)).append('|')
                .append(ctx.radius).append('|').append(ctx.wallThickness).append('|')
                .append(be.attachmentMaterial).append('|').append(polys.size)
                .append('|').append(editing)
        }
        val cached = clipCaches[be.blockPos]
        if (cached != null && cached.signature == sig) {
            clipped = cached.polys
        } else if (innerSolid != null) {
            clipped = try {
                val kept = ArrayList<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon>()
                for (p in polys) {
                    for (piece in innerSolid.intersectionOf(p)) {
                        if (piece.vertices.size >= 3) kept.add(piece)
                    }
                }
                kept + csg.cutFace(polys, listOf(innerSolid))
            } catch (t: Throwable) {
                polys
            }
            clipCaches[be.blockPos] = ClipCache(sig, clipped)
        }
        if (be.isRemoved) clipCaches.remove(be.blockPos)
        // manually generated parts bypass the CSG entirely
        if (manualQuads.isNotEmpty()) {
            val consumer2 = buffers.getBuffer(if (editing) ATTACH_TRI_TRANSLUCENT else ATTACH_TRI_CUTOUT)
            for (q in manualQuads) emitManualQuad(q, ctx, sprite, level, poseStack.last(), consumer2)
        }
        val consumer = buffers.getBuffer(if (editing) ATTACH_TRI_TRANSLUCENT else ATTACH_TRI_CUTOUT)
        for (poly in clipped) {
            val vs = poly.vertices
            if (vs.size < 3) continue
            for (i in 1 until vs.size - 1) {
                emitTri(consumer, poseStack.last(), vs[0], vs[i], vs[i + 1])
            }
        }
        poseStack.popPose()
        if (buffers is MultiBufferSource.BufferSource) {
            buffers.endBatch(ATTACH_TRI_CUTOUT)
            buffers.endBatch(ATTACH_TRI_TRANSLUCENT)
        }
    }



    /** single convex prism: the tube's inner polygon ring at the door's
     *  cross-section, extruded well past the door along the tangent */
    private fun innerPrismAt(
        ctx: SlideAttachmentModelContext
    ): net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Solid? {
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        val outer = ctx.radius + ctx.wallThickness - 0.1f
        val inner = (ctx.radius - 0.1f).coerceAtLeast(0.05f)
        val center = ctx.position.subtract(ctx.radialOut.scale(outer.toDouble()))
        val sides = net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh.crossSections()
        val ring = (0 until sides).map { k ->
            val a = Math.toRadians(90.0 + 360.0 * k / sides)
            center.add(lat.scale(inner * kotlin.math.cos(a)))
                .add(up.scale(inner * kotlin.math.sin(a)))
        }
        val ring0 = ring.map { it.subtract(tan.scale(2.0)) }
        val ring1 = ring.map { it.add(tan.scale(2.0)) }
        val c0 = ring0.first()
        val c1 = ring1.first()
        return net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.prismSolid(ring0, ring1, c0, c1)
    }


    /** one reversed quad per inner-wall facet: a single-plane solid whose
     *  inside is the region beyond that wall edge */
    private fun innerHalfSpaceSlabs(
        ctx: SlideAttachmentModelContext
    ): List<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Solid> {
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        val outer = ctx.radius + ctx.wallThickness - 0.1f
        val inner = (ctx.radius - 0.1f).coerceAtLeast(0.05f).toDouble()
        val center = ctx.position.subtract(ctx.radialOut.scale(outer.toDouble()))
        val sides = net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh.crossSections()
        fun ringPoint(k: Int, along: Double): Vec3 {
            val a = Math.toRadians(90.0 + 360.0 * k / sides)
            return center.add(tan.scale(along))
                .add(lat.scale(inner * kotlin.math.cos(a)))
                .add(up.scale(inner * kotlin.math.sin(a)))
        }
        val out = ArrayList<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Solid>(sides)
        for (k in 0 until sides) {
            val j = (k + 1) % sides
            // same winding as prismSolid's side quad, then REVERSED so the
            // half-space points out of the tube
            val pts = listOf(
                ringPoint(j, 2.0), ringPoint(k, 2.0),
                ringPoint(k, -2.0), ringPoint(j, -2.0)
            )
            val quad = net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon(
                pts.map {
                    net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Vertex(
                        it.x.toFloat(), it.y.toFloat(), it.z.toFloat(),
                        0f, 0f, 0f, 0.5f, 0.5f,
                        0xFFFFFFFF.toInt(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY
                    )
                }.toMutableList(),
                true
            )
            out.add(net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Solid(listOf(quad)))
        }
        return out
    }


    // edit translucency shared by all emit paths
    private var editAlpha: Int = 255

    // last editing state per attachment, for edge-triggered diagnostics
    private val lastEditState = HashMap<BlockPos, Boolean>()

    private fun withEditAlpha(argb: Int): Int =
        if (editAlpha >= 255) argb else (argb and 0x00FFFFFF) or (editAlpha shl 24)

    /** emit a manually built local-space quad with material UVs */
    private fun emitManualQuad(
        q: SlideAttachmentModelProvider.Quad,
        ctx: SlideAttachmentModelContext,
        sprite: TextureAtlasSprite?,
        level: net.minecraft.world.level.Level,
        pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
        consumer: VertexConsumer
    ) {
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        fun w(x: Double, y: Double, z: Double) = ctx.position
            .add(lat.scale(x)).add(up.scale(y)).add(tan.scale(z))
        fun uvs(f: Float) = if (sprite != null) f else 0.5f
        val u0 = if (sprite != null) borderU(sprite, q.u0) else 0.5f
        val v0 = if (sprite != null) borderV(sprite, q.v0) else 0.5f
        val u1 = if (sprite != null) borderU(sprite, q.u1) else 0.5f
        val v1 = if (sprite != null) borderV(sprite, q.v1) else 0.5f
        val u2 = if (sprite != null) borderU(sprite, q.u2) else 0.5f
        val v2 = if (sprite != null) borderV(sprite, q.v2) else 0.5f
        val u3 = if (sprite != null) borderU(sprite, q.u3) else 0.5f
        val v3 = if (sprite != null) borderV(sprite, q.v3) else 0.5f
        val corners = arrayOf(
            Triple(w(q.x0, q.y0, q.z0), u0, v0),
            Triple(w(q.x1, q.y1, q.z1), u1, v1),
            Triple(w(q.x2, q.y2, q.z2), u2, v2),
            Triple(w(q.x3, q.y3, q.z3), u3, v3)
        )
        val light = LevelRenderer.getLightColor(level, BlockPos.containing(w(q.x0, q.y0, q.z0)))
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) {
            val (pos, u, v) = corners[i]
            consumer.addVertex(pose, pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
                .setColor(255, 255, 255, editAlpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, q.nx.toFloat(), q.ny.toFloat(), q.nz.toFloat())
        }
    }

    private fun emitTri(
        consumer: VertexConsumer,
        pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
        a: net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Vertex,
        b: net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Vertex,
        c: net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Vertex
    ) {
        // CSG output carries vertex colours from the source polygons (alpha
        // 255) - the edit translucency must be applied here too, otherwise
        // clipped parts (the door panels) stay opaque
        consumer.addVertex(pose, a.x, a.y, a.z).setColor(withEditAlpha(a.color)).setUv(a.u, a.v)
            .setOverlay(a.overlay).setLight(a.light).setNormal(pose, a.nx, a.ny, a.nz)
        consumer.addVertex(pose, b.x, b.y, b.z).setColor(withEditAlpha(b.color)).setUv(b.u, b.v)
            .setOverlay(b.overlay).setLight(b.light).setNormal(pose, b.nx, b.ny, b.nz)
        consumer.addVertex(pose, c.x, c.y, c.z).setColor(withEditAlpha(c.color)).setUv(c.u, c.v)
            .setOverlay(c.overlay).setLight(c.light).setNormal(pose, c.nx, c.ny, c.nz)
    }

    /** plot-space -> render-space transform for slides inside Sable sub-levels */
    fun renderTransform(
        level: net.minecraft.world.level.Level,
        anchor: BlockPos
    ): net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentRenderTransform? {
        val sub = net.omori_sunny.create_waterparked.client.editor.SableClientEdit
            .resolve(level, anchor)?.sub ?: return null
        return net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentRenderTransform(
            { p -> dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
                .toRenderWorld(level, anchor, p) },
            { v ->
                val d = dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
                    .toRenderDirection(level, anchor, v)
                if (d.lengthSqr() < 1.0E-12) v.normalize() else d.normalize()
            }
        )
    }

    /** world position of a local-space point (origin = attachment wall point) */
    private fun localToWorld(
        ctx: SlideAttachmentModelContext,
        x: Double, y: Double, z: Double
    ): Vec3 {
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        return ctx.position.add(lat.scale(x)).add(up.scale(y)).add(tan.scale(z))
    }

    /**
     * support-style textured box: each face is split into one quad per block
     * of height; u spans the face width as a sprite fraction, v repeats the
     * whole sprite once per block (the beam tiling). Colour stays white and
     * one light sample covers the whole part, exactly like the beams.
     */
    private fun drawBox(
        part: SlideAttachmentModelProvider.BoxPart,
        ctx: SlideAttachmentModelContext,
        sprite: TextureAtlasSprite?,
        level: net.minecraft.world.level.Level
    ): List<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon> {
        val hx = part.sizeX / 2.0
        val hy = part.sizeY / 2.0
        val hz = part.sizeZ / 2.0
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)

        val center = localToWorld(ctx, part.cx, part.cy, part.cz)
        val light = LevelRenderer.getLightColor(level, BlockPos.containing(center))

        val out = ArrayList<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon>()
        for (face in Direction.entries) {
            val fb = faceBasis(face, hx, hy, hz, lat, up, tan)
            val wn = fb.normal
            val width = lengthOf(fb.du)
            val height = lengthOf(fb.dv)
            if (width < 1.0E-6 || height < 1.0E-6) continue

            var v0 = 0.0
            while (v0 < height - 1.0E-6) {
                val v1 = kotlin.math.min(v0 + 1.0, height)
                val cellH = (v1 - v0).toFloat()
                var u0 = 0.0
                while (u0 < width - 1.0E-6) {
                    val u1 = kotlin.math.min(u0 + 1.0, width)
                    val cellW = (u1 - u0).toFloat()
                    val ua = if (sprite != null) borderU(sprite, 0f) else 0.5f
                    val ub = if (sprite != null) borderU(sprite, cellW) else 0.5f
                    val va = if (sprite != null) borderV(sprite, 0f) else 0.5f
                    val vb = if (sprite != null) borderV(sprite, cellH) else 0.5f
                    addPoly(out, ctx, part, fb, u0, u1, v0, v1, ua, ub, va, vb, wn, light)
                    u0 = u1
                }
                v0 = v1
            }
        }
        return out
    }

    private fun addPoly(
        out: MutableList<net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon>,
        ctx: SlideAttachmentModelContext,
        part: SlideAttachmentModelProvider.BoxPart,
        fb: FaceBasis,
        u0: Double, u1: Double, v0: Double, v1: Double,
        ua: Float, ub: Float, va: Float, vb: Float,
        wn: Vec3, light: Int
    ) {
        val duLen = len(fb.du)
        val dvLen = len(fb.dv)
        val base = doubleArrayOf(part.cx + fb.origin[0], part.cy + fb.origin[1], part.cz + fb.origin[2])
        fun vert(u: Double, v: Double, texU: Float, texV: Float) =
            net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Vertex(
                (base[0] + fb.du[0] * u / duLen + fb.dv[0] * v / dvLen).toFloat(),
                (base[1] + fb.du[1] * u / duLen + fb.dv[1] * v / dvLen).toFloat(),
                (base[2] + fb.du[2] * u / duLen + fb.dv[2] * v / dvLen).toFloat(),
                wn.x.toFloat(), wn.y.toFloat(), wn.z.toFloat(),
                texU, texV, 0xFFFFFFFF.toInt(), light, OverlayTexture.NO_OVERLAY
            )
        // local-space vertices; shifted to world inside the CSG space below
        val vs = mutableListOf(
            vert(u0, v0, ua, va), vert(u1, v0, ub, va),
            vert(u1, v1, ub, vb), vert(u0, v1, ua, vb)
        )
        // convert to world/render space (the prism solids live there)
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        for (vx in vs) {
            val w = ctx.position
                .add(lat.scale(vx.x.toDouble()))
                .add(up.scale(vx.y.toDouble()))
                .add(tan.scale(vx.z.toDouble()))
            vx.x = w.x.toFloat(); vx.y = w.y.toFloat(); vx.z = w.z.toFloat()
        }
        out.add(net.omori_sunny.create_waterparked.client.render.WaterslideGhostCsg.Polygon(vs, false))
    }

    /** quad corners from a face origin + edge vectors, covering the u/v cell */
    private fun emitCell(
        consumer: VertexConsumer,
        ctx: SlideAttachmentModelContext,
        part: SlideAttachmentModelProvider.BoxPart,
        o: DoubleArray, du: DoubleArray, dv: DoubleArray,
        u0: Double, u1: Double, v0: Double, v1: Double,
        ua: Float, ub: Float, va: Float, vb: Float,
        wn: Vec3, light: Int,
        pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
        clipCx: Double, clipCy: Double, clipR: Double
    ) {
        val dvLen = len(dv)
        val duLen = len(du)
        fun corner(lx0: Double, ly0: Double, lz0: Double): Vec3 {
            // clip onto the tube's inner wall, using the SAME polygon
            // subdivision the tube mesh uses (crossSections sides, 90-degree
            // grid anchor) so vertices land on the facet planes
            var lx = lx0
            var ly = ly0
            val dx = lx - clipCx
            val dy = ly - clipCy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > 1.0E-9) {
                val sides = net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
                    .crossSections()
                val step = 2.0 * Math.PI / sides
                val phi = kotlin.math.atan2(dy, dx)
                // facet normals sit between the vertex angles (90-degree anchor)
                val m = phi - (Math.PI / 2.0 + step / 2.0)
                val mNorm = ((m % step) + step) % step - step / 2.0
                val boundary = clipR * kotlin.math.cos(step / 2.0) / kotlin.math.cos(mNorm)
                if (dist > boundary) {
                    val scale = boundary / dist
                    lx = clipCx + dx * scale
                    ly = clipCy + dy * scale
                }
            }
            return localToWorld(ctx, lx, ly, lz0)
        }
        fun add(w: Vec3, u: Float, v: Float) {
            consumer.addVertex(pose, w.x.toFloat(), w.y.toFloat(), w.z.toFloat())
                .setColor(255, 255, 255, editAlpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, wn.x.toFloat(), wn.y.toFloat(), wn.z.toFloat())
        }
        val base = doubleArrayOf(
            part.cx + o[0], part.cy + o[1], part.cz + o[2]
        )
        // cell corners at absolute face coords (u, v)
        fun pt(u: Double, v: Double): Vec3 {
            val p = doubleArrayOf(
                base[0] + du[0] * u / duLen + dv[0] * v / dvLen,
                base[1] + du[1] * u / duLen + dv[1] * v / dvLen,
                base[2] + du[2] * u / duLen + dv[2] * v / dvLen
            )
            return corner(p[0], p[1], p[2])
        }
        val a = pt(u0, v0)
        val b = pt(u1, v0)
        val c = pt(u1, v1)
        val d = pt(u0, v1)
        add(a, ua, va)
        add(b, ub, va)
        add(c, ub, vb)
        add(d, ua, vb)
    }

    private fun len(v: DoubleArray): Double =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun lengthOf(v: DoubleArray): Double = len(v)

    /** support bracket anti-bleed mapping: the sprite border ring is never
     *  sampled, f in [0,1] spans the inner strip */
    private fun borderU(sprite: TextureAtlasSprite, f: Float): Float {
        val texW = sprite.contents().width().toFloat()
        val border = net.omori_sunny.create_waterparked.config.ModConfig.sectorBorderPx().toFloat()
        return sprite.u0 + ((border + f.coerceIn(0f, 1f) * (texW - 2 * border)) / texW) * (sprite.u1 - sprite.u0)
    }

    private fun borderV(sprite: TextureAtlasSprite, f: Float): Float {
        val texH = sprite.contents().height().toFloat()
        val border = net.omori_sunny.create_waterparked.config.ModConfig.sectorBorderPx().toFloat()
        return sprite.v0 + ((border + f.coerceIn(0f, 1f) * (texH - 2 * border)) / texH) * (sprite.v1 - sprite.v0)
    }

    /** face origin corner + (width, height) edge vectors + outward normal */
    private fun faceBasis(
        face: Direction, hx: Double, hy: Double, hz: Double,
        lat: Vec3, up: Vec3, tan: Vec3
    ): FaceBasis {
        // local space: x = lateral, y = up, z = tangent
        return when (face) {
            Direction.DOWN -> FaceBasis(
                doubleArrayOf(-hx, -hy, -hz), doubleArrayOf(2 * hx, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 2 * hz),
                up.scale(-1.0)
            )
            Direction.UP -> FaceBasis(
                doubleArrayOf(-hx, hy, -hz), doubleArrayOf(0.0, 0.0, 2 * hz), doubleArrayOf(2 * hx, 0.0, 0.0),
                up
            )
            Direction.NORTH -> FaceBasis(
                doubleArrayOf(-hx, -hy, -hz), doubleArrayOf(0.0, 2 * hy, 0.0), doubleArrayOf(2 * hx, 0.0, 0.0),
                tan.scale(-1.0)
            )
            Direction.SOUTH -> FaceBasis(
                doubleArrayOf(-hx, -hy, hz), doubleArrayOf(2 * hx, 0.0, 0.0), doubleArrayOf(0.0, 2 * hy, 0.0),
                tan
            )
            Direction.WEST -> FaceBasis(
                doubleArrayOf(-hx, -hy, -hz), doubleArrayOf(0.0, 0.0, 2 * hz), doubleArrayOf(0.0, 2 * hy, 0.0),
                lat.scale(-1.0)
            )
            Direction.EAST -> FaceBasis(
                doubleArrayOf(hx, -hy, -hz), doubleArrayOf(0.0, 2 * hy, 0.0), doubleArrayOf(0.0, 0.0, 2 * hz),
                lat
            )
        }
    }

    private class FaceBasis(
        val origin: DoubleArray,
        val du: DoubleArray,
        val dv: DoubleArray,
        val normal: Vec3
    )

    private fun drawModel(
        part: SlideAttachmentModelProvider.ModelPart,
        ctx: SlideAttachmentModelContext,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        level: net.minecraft.world.level.Level
    ) {
        val mc = Minecraft.getInstance()
        val model = try {
            mc.modelManager.getModel(part.model)
        } catch (_: Exception) {
            mc.modelManager.missingModel
        }
        val (lat, up, tan) = SlideAttachmentGeometry.basis(ctx)
        val anchor = localToWorld(ctx, part.offsetX, part.offsetY, part.offsetZ)
        poseStack.pushPose()
        poseStack.translate(anchor.x, anchor.y, anchor.z)
        poseStack.mulPose(rotationTo(lat, up, tan))
        if (part.rollDeg != 0.0) poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(part.rollDeg.toFloat()))
        if (part.pitchDeg != 0.0) poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(part.pitchDeg.toFloat()))
        poseStack.scale(part.scale.toFloat(), part.scale.toFloat(), part.scale.toFloat())
        val consumer = buffers.getBuffer(RenderType.cutout())
        val random = RandomSource.create(42L)
        val quads = ArrayList<net.minecraft.client.renderer.block.model.BakedQuad>(24)
        quads.addAll(model.getQuads(null, null, random))
        for (dir in Direction.entries) quads.addAll(model.getQuads(null, dir, random))
        val pose = poseStack.last()
        for (quad in quads) {
            consumer.putBulkData(pose, quad, 1f, 1f, 1f, 1f, LevelRenderer.getLightColor(level, BlockPos.containing(anchor)), OverlayTexture.NO_OVERLAY, false)
        }
        poseStack.popPose()
    }

    /** rotation taking local axes onto the (right-handed) frame basis */
    private fun rotationTo(lat: Vec3, up: Vec3, tan: Vec3): org.joml.Quaternionf {
        val m = org.joml.Matrix3f(
            lat.x.toFloat(), up.x.toFloat(), tan.x.toFloat(),
            lat.y.toFloat(), up.y.toFloat(), tan.y.toFloat(),
            lat.z.toFloat(), up.z.toFloat(), tan.z.toFloat()
        )
        return org.joml.Quaternionf().setFromUnnormalized(m)
    }
}
