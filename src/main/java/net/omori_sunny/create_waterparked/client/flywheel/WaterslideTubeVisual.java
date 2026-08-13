package net.omori_sunny.create_waterparked.client.flywheel;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import kotlin.Pair;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation;
import net.omori_sunny.create_waterparked.CreateWaterparked;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.config.ModClientConfig;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry;
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// one visual per anchor
public class WaterslideTubeVisual extends AbstractVisual
    implements BlockEntityVisual<WaterslideAnchorBlockEntity>, ShaderLightVisual, SimpleDynamicVisual {

    private static final Set<WaterslideTubeVisual> ACTIVE =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private static final float WALL_THICKNESS = 0.1f;

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static boolean wasEditing = false;
    private static boolean wasDragging = false;
    private static BlockPos lastEditAnchor = null;
    private static float lastPolygonScale = -1f;

    private final WaterslideAnchorBlockEntity be;
    private final List<TubeCurve> curves = new ArrayList<>();
    private String lastDataSig = "";
    private float lastWaterTime = -1f;
    @Nullable
    private SectionCollector lightSections;

    public WaterslideTubeVisual(VisualizationContext ctx, WaterslideAnchorBlockEntity be, float partialTick) {
        super(ctx, be.getLevel(), partialTick);
        this.be = be;
        collect();
        ACTIVE.add(this);
    }

    @Override
    public void update(float partialTick) {
        float scale = ModClientConfig.INSTANCE.polygonScale();
        if (scale != lastPolygonScale) {
            lastPolygonScale = scale;
            WaterslideTubeMesh.INSTANCE.clearModels();
        }
        String sig = dataSignature();
        if (sig.equals(lastDataSig)) return;
        lastDataSig = sig;
        collect();
    }

    // radius/config only; water data arrives through the sync version
    private String dataSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(WaterFlowSimulation.INSTANCE.version()).append('|');
        sb.append(ModClientConfig.INSTANCE.polygonScale()).append('|');
        sb.append(ModClientConfig.INSTANCE.wallThickness()).append('|');
        sb.append(be.getRadius()).append('|');
        for (Map.Entry<BlockPos, WaterslideSectorConfig> e : be.getSectorConfigs().entrySet()) {
            sb.append(e.getKey().asLong()).append('=');
            WaterslideSectorConfig cfg = e.getValue();
            sb.append(cfg.getStartAngle()).append(';');
            for (net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector s : cfg.getSectors()) {
                sb.append(s.getId()).append(',').append(s.getMaterial()).append(',').append(s.getBlockId())
                    .append(',').append(s.getType()).append(',').append(s.getWidthDegrees()).append(';');
            }
        }
        Level lvl = be.getLevel();
        for (Map.Entry<BlockPos, BezierConnection> e : be.getAnchorPeerCurvesView().entrySet()) {
            BezierConnection raw = e.getValue();
            if (raw == null || !raw.isPrimary()) continue;
            Vec3 h0 = raw.starts.getFirst();
            Vec3 h1 = raw.starts.getSecond();
            sb.append(e.getKey().asLong()).append('=')
                .append(raw.getSegmentCount()).append(',')
                .append(h0.x).append(',').append(h0.y).append(',').append(h0.z).append(',')
                .append(h1.x).append(',').append(h1.y).append(',').append(h1.z).append(',')
                .append(WaterslideRadiusEdit.INSTANCE.radiusAt(
                    lvl, raw.bePositions.getSecond(), ModConfig.INSTANCE.defaultSlideRadius()
                )).append(';');
        }
        return sb.toString();
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        Level lvl = be.getLevel();
        if (lvl == null) return;
        float now = AnimationTickHolder.getRenderTime(lvl);
        float delta = lastWaterTime < 0f ? 0f : Math.min(now - lastWaterTime, 0.25f);
        lastWaterTime = now;
        if (delta <= 0f) return;
        for (TubeCurve c : curves) {
            for (WaterslideTubeInstance w : c.waterInstances) {
                w.phaseStart += w.flowStart * delta;
                w.phaseEnd += w.flowEnd * delta;
                w.phaseUpstream += w.flowUpstream * delta;
                w.setChanged();
            }
        }
    }

    private void collect() {
        for (TubeCurve c : curves) {
            c.delete();
        }
        curves.clear();
        for (Map.Entry<BlockPos, BezierConnection> e : be.getAnchorPeerCurvesView().entrySet()) {
            BezierConnection raw = e.getValue();
            if (raw == null || !raw.isPrimary()) continue;
            if (!WaterslideTrackMaterials.isWaterslide(raw)) continue;
            curves.add(new TubeCurve(e.getKey(), raw));
        }
        for (TubeCurve c : curves) {
            c.rebuildInstances();
        }
        if (lightSections != null) {
            lightSections.sections(collectLightSections());
        }
    }

    public LongSet collectLightSections() {
        LongSet out = new LongArraySet();
        for (TubeCurve c : curves) {
            AABB bounds = c.curve.getBounds();
            int minX = Mth.floor(bounds.minX) - 1;
            int minY = Mth.floor(bounds.minY) - 1;
            int minZ = Mth.floor(bounds.minZ) - 1;
            int maxX = Mth.ceil(bounds.maxX) + 1;
            int maxY = Mth.ceil(bounds.maxY) + 1;
            int maxZ = Mth.ceil(bounds.maxZ) + 1;
            int minSectionX = SectionPos.blockToSectionCoord(minX);
            int minSectionY = SectionPos.blockToSectionCoord(minY);
            int minSectionZ = SectionPos.blockToSectionCoord(minZ);
            int maxSectionX = SectionPos.blockToSectionCoord(maxX);
            int maxSectionY = SectionPos.blockToSectionCoord(maxY);
            int maxSectionZ = SectionPos.blockToSectionCoord(maxZ);
            for (int x = minSectionX; x <= maxSectionX; x++) {
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    for (int z = minSectionZ; z <= maxSectionZ; z++) {
                        out.add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void setSectionCollector(SectionCollector sectionCollector) {
        this.lightSections = sectionCollector;
        this.lightSections.sections(collectLightSections());
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        for (TubeCurve c : curves) {
            c.collectCrumblingInstances(consumer);
        }
    }

    @Override
    protected void _delete() {
        for (TubeCurve c : curves) {
            c.delete();
        }
        curves.clear();
        ACTIVE.remove(this);
    }

    // translucent when edited; refresh while dragging
    public static void tickVisibility() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            wasEditing = false;
            wasDragging = false;
            lastEditAnchor = null;
            return;
        }
        boolean edit = BezierHandleEditMode.isActive();
        BlockPos editAnchor = edit ? BezierHandleEditMode.getActiveAnchor() : null;
        boolean editingNow = edit && editAnchor != null;
        boolean editExited = wasEditing && !editingNow;
        boolean showSkeleton = ModClientConfig.INSTANCE.showSkeletonWhenTranslucent();
        boolean dragging =
            WaterslideSectorEdit.INSTANCE.isDraggingControlPoint() ||
                WaterslideRadiusEdit.INSTANCE.isDragging() ||
                BezierHandleDragManager.isDraggingTangentHandle();
        boolean dragEnded = wasDragging && !dragging;
        BlockPos refreshAnchor = editExited || dragEnded ? lastEditAnchor : editingNow ? editAnchor : null;
        // recompute water for the whole chain when an edit commits
        if ((editExited || dragEnded) && refreshAnchor != null) {
            refreshChainAfterEdit(mc.level, refreshAnchor);
        }
        // iterate snapshots
        for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
            if (visual.be.isRemoved() || visual.be.getLevel() != mc.level) continue;
            for (TubeCurve c : new ArrayList<>(visual.curves)) {
                if (!visual.curves.contains(c)) continue;
                c.setShowSkeleton(showSkeleton);
                boolean belongs = edit && editAnchor != null &&
                    (editAnchor.equals(c.curve.bePositions.getFirst()) ||
                        editAnchor.equals(c.curve.bePositions.getSecond()));
                c.setTranslucent(belongs);
            }
            // refresh only while dragging or after edit/drag end
            if (refreshAnchor != null) {
                visual.refreshAnchorCurves(refreshAnchor);
            }
        }
        wasEditing = editingNow;
        wasDragging = dragging;
        if (editingNow) {
            lastEditAnchor = editAnchor;
        } else if (editExited) {
            lastEditAnchor = null;
        }
    }

    // rebuild after a BE data packet
    public static void refreshAnchor(BlockPos anchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        boolean changed = false;
        for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
            if (visual.be.isRemoved() || visual.be.getLevel() != mc.level) continue;
            if (visual.be.getBlockPos().equals(anchor)) {
                String sig = visual.dataSignature();
                if (sig.equals(visual.lastDataSig)) continue;
                visual.lastDataSig = sig;
                visual.collect();
                changed = true;
            }
        }
        if (changed) {
            refreshChainAfterEdit(mc.level, anchor);
            for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
                if (visual.be.isRemoved() || visual.be.getLevel() != mc.level) continue;
                visual.refreshAnchorCurves(anchor);
            }
        }
    }

    // refresh water for every leg of the edited chain
    public static void refreshChain(Level level, List<Pair<Long, Long>> edges, BlockPos skipAnchor) {
        for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
            if (visual.be.isRemoved() || visual.be.getLevel() != level) continue;
            visual.collect();
        }
    }

    // the server recomputes and syncs water after an edit; just redraw locally
    public static void refreshChainAfterEdit(Level level, BlockPos anchor) {
        for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
            if (visual.be.isRemoved() || visual.be.getLevel() != level) continue;
            visual.collect();
        }
    }

    // water sync data changed; redraw every visual
    public static void refreshAll() {
        for (WaterslideTubeVisual visual : new ArrayList<>(ACTIVE)) {
            if (visual.be.isRemoved()) continue;
            visual.collect();
        }
    }

    private void refreshAnchorCurves(BlockPos anchor) {
        for (TubeCurve c : new ArrayList<>(curves)) {
            if (!curves.contains(c)) continue;
            if (anchor.equals(c.curve.bePositions.getFirst()) ||
                anchor.equals(c.curve.bePositions.getSecond())) {
                c.refresh();
            }
        }
    }

    private class TubeCurve {
        private final BezierConnection curve;
        private final BlockPos peer;
        private final Level level;
        private final Vec3 origin;
        private List<WaterslideTubeMesh.TubeSegmentFrame> frames;
        private WaterslideTubeMesh.TubeModels models;
        private WaterslideSectorConfig config;
        private final List<WaterslideTubeInstance> instances = new ArrayList<>();
        @Nullable
        private WaterFlowSimulation.CurveWater streamWater;
        @Nullable
        private List<StreamSegment> streamSegments;
        private final List<WaterslideTubeInstance> streamInstances = new ArrayList<>();
        private final List<WaterslideTubeInstance> waterInstances = new ArrayList<>();
        private boolean translucent = false;
        private boolean showSkeleton = false;
        private float mirror = 1f;
        private WaterFlowSimulation.CurveWater water;

        TubeCurve(BlockPos peer, BezierConnection bc) {
            this.curve = bc;
            this.peer = peer;
            this.level = be.getLevel();
            BlockPos a = bc.bePositions.getFirst();
            BlockPos b = bc.bePositions.getSecond();
            float r0 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                this.level, a, ModConfig.INSTANCE.defaultSlideRadius()
            );
            float r1 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                this.level, b, ModConfig.INSTANCE.defaultSlideRadius()
            );
            this.origin = Vec3.atLowerCornerOf(renderOrigin());
            this.frames = WaterslideTubeMesh.INSTANCE.sampleSegments(
                this.level, bc, r0, r1, this.origin
            );
            this.config = be.sectorConfigFor(peer);
            this.models = WaterslideTubeMesh.INSTANCE.modelsFor(
                this.level, this.config
            );
            this.water = WaterFlowSimulation.INSTANCE.resultFor(this.level, curve);
        }

        // rebuild from preview
        void refresh() {
            BlockPos a = curve.bePositions.getFirst();
            BlockPos b = curve.bePositions.getSecond();
            float r0 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                level, a, ModConfig.INSTANCE.defaultSlideRadius()
            );
            float r1 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                level, b, ModConfig.INSTANCE.defaultSlideRadius()
            );
            this.frames = WaterslideTubeMesh.INSTANCE.sampleSegments(level, curve, r0, r1, origin);
            this.config = WaterslideSectorEdit.INSTANCE.previewConfigFor(a, b);
            if (this.config == null) {
                this.config = be.sectorConfigFor(peer);
            }
            this.models = WaterslideTubeMesh.INSTANCE.modelsFor(level, this.config);
            this.water = WaterFlowSimulation.INSTANCE.resultFor(level, curve);
            rebuildInstances();
        }

        @Nullable
        private List<StreamSegment> buildStreamSegments() {
            ServerWaterSimulation.ExitInfo exit = water.getExit();
            if (exit == null) return null;
            boolean forward = water.getFlowSign() < 0f;
            WaterslideTubeMesh.TubeSegmentFrame outlet = forward
                ? frames.get(frames.size() - 1)
                : frames.get(0);
            Vec3 outletCenter = forward ? outlet.getCurrSpine() : outlet.getPrevSpine();
            Vec3 outletTan = forward
                ? outlet.getCurrTangent()
                : outlet.getPrevTangent().scale(-1.0);
            Pair<Vec3, Vec3> outletFrame = SlideCurveGeometry.INSTANCE.stableFrame(outletTan);
            Vec3 up0 = outletFrame.getSecond();
            Vec3 lat0 = outletFrame.getFirst();
            float radius = forward ? outlet.getCurrRadius() : outlet.getPrevRadius();
            float rIn = Math.max(0.05f, radius - WALL_THICKNESS);
            float rSurf = Math.max(0.01f, rIn - 0.3f);
            // fixed band for the thrown water sheet
            float c0 = 210f;
            float c1 = 330f;
            if (!forward) {
                float t = c0;
                c0 = -c1;
                c1 = -t;
            }
            List<Vec3> own = new ArrayList<>();
            for (WaterslideTubeMesh.TubeSegmentFrame f : frames) {
                own.add(f.getPrevSpine().add(origin));
                own.add(f.getCurrSpine().add(origin));
            }
            Pair<List<List<Vec3>>, List<List<Vec3>>> res =
                WaterFlowSimulation.INSTANCE.predictStreams(
                    level, exit.getPos(), exit.getVel(), outletCenter.add(origin), lat0, up0,
                    rIn, rSurf, c0, c1, own
                );
            if (res == null) return null;
            List<List<Vec3>> outer = res.getFirst();
            List<List<Vec3>> inner = res.getSecond();
            if (outer.isEmpty() || inner.isEmpty()) return null;
            List<Vec3> o = outer.get(0);
            List<Vec3> in = inner.get(0);
            if (o.size() < 2 || in.size() < 2) return null;

            int samples = o.size();
            int streamMaxSegments = Math.max(
                4, Math.round(48 * ModClientConfig.INSTANCE.polygonScale())
            );
            float streamLen = 0f;
            for (int k = 1; k < samples; k++) {
                streamLen += (float) o.get(k).distanceTo(o.get(k - 1));
            }
            int desired = Math.max(streamMaxSegments, (int) Math.ceil(streamLen / 0.5));
            int stride = Math.max(1, (samples - 1) / desired);
            Vec3 dir = o.get(0).subtract(in.get(0)).normalize();
            float tubeRadius = radius;

            List<Vec3> centers = new ArrayList<>();
            for (int k = 0; k < samples; k += stride) {
                centers.add(o.get(k).subtract(dir.scale(rIn)).subtract(origin));
            }
            if (centers.size() < 2) return null;

            Vec3 tan0 = centers.get(1).subtract(centers.get(0)).normalize();
            if (tan0.lengthSqr() < 1.0E-6) tan0 = new Vec3(0.0, 0.0, 1.0);
            up0 = up0.subtract(tan0.scale(up0.dot(tan0)));
            if (up0.lengthSqr() < 1.0E-6) {
                up0 = new Vec3(0.0, 1.0, 0.0).subtract(tan0.scale(tan0.y));
            }
            up0 = up0.normalize();
            lat0 = up0.cross(tan0).normalize();
            if (lat0.lengthSqr() < 1.0E-6) {
                lat0 = new Vec3(1.0, 0.0, 0.0).subtract(tan0.scale(tan0.x)).normalize();
            }

            // shared junction tangents so adjacent segments meet ring-to-ring
            Vec3[] tans = new Vec3[centers.size()];
            for (int i = 0; i < centers.size(); i++) {
                Vec3 a = centers.get(Math.max(0, i - 1));
                Vec3 b = centers.get(Math.min(centers.size() - 1, i + 1));
                Vec3 t = b.subtract(a).normalize();
                if (t.lengthSqr() < 1.0E-6) t = lat0.cross(up0).normalize();
                tans[i] = t;
            }
            Vec3[] lats = new Vec3[centers.size()];
            Vec3[] ups = new Vec3[centers.size()];
            Vec3 lat = lat0;
            Vec3 up = up0;
            for (int i = 0; i < centers.size(); i++) {
                Vec3 l = lat.subtract(tans[i].scale(lat.dot(tans[i])));
                if (l.lengthSqr() < 1.0E-6) l = lat;
                l = l.normalize();
                Vec3 u = tans[i].cross(l).normalize();
                if (l.dot(lat) < 0.0) {
                    l = l.scale(-1.0);
                    u = u.scale(-1.0);
                }
                lats[i] = l;
                ups[i] = u;
                lat = l;
                up = u;
            }

            float speed = (float) exit.getVel().length() *
                WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK *
                ModClientConfig.INSTANCE.waterFlowScale();
            List<StreamSegment> segs = new ArrayList<>();
            float arcBase = 0f;
            for (int i = 0; i < centers.size() - 1; i++) {
                Vec3 c0p = centers.get(i);
                Vec3 c1p = centers.get(i + 1);
                segs.add(new StreamSegment(
                    c0p, c1p, tans[i], tans[i + 1], lats[i], lats[i + 1], tubeRadius, tubeRadius, arcBase, speed
                ));
                arcBase += (float) c0p.distanceTo(c1p);
            }
            return segs.isEmpty() ? null : segs;
        }

        private void buildWaterBand(float wallThickness, float mirror) {
            if (water == null || !water.getExists()) return;
            List<ServerWaterSimulation.WaterSegment> segments = water.getSegments();
            if (segments.isEmpty()) return;
            float legLen = 0f;
            for (WaterslideTubeMesh.TubeSegmentFrame f : frames) {
                legLen += WaterslideTubeMesh.arcLength(f);
            }
            float now = AnimationTickHolder.getRenderTime(level);
            float scale = ModClientConfig.INSTANCE.waterFlowScale();
            float radius = Math.max(0.1f, frames.get(0).getPrevRadius());
            float rInFrac = Math.max(0.1f, (radius - WALL_THICKNESS) / radius);
            for (ServerWaterSimulation.WaterSegment seg : segments) {
                float speed = seg.getSpeed();
                // each segment flows along its own sampled direction
                boolean segForward = speed >= 0f;
                // fixed depth like the thrown water sheet
                float depth = 0.25f;
                float rSurfFrac = Math.max(0.05f, rInFrac - depth / radius);
                List<Float> verts = WaterslideTubeMesh.INSTANCE.bandVertices(rInFrac, rSurfFrac, !segForward);
                Model waterModel = WaterslideTubeMesh.INSTANCE.waterModelFor(
                    verts, verts, radius
                );
                Instancer<WaterslideTubeInstance> waterInstancer = instancerProvider().instancer(
                    WaterslideTubeInstanceType.INSTANCE, waterModel
                );
                WaterslideTubeInstance w = waterInstancer.createInstance();
                WaterslideTubeMesh.TubeSegmentFrame f =
                    frameAtArc(segForward ? seg.getArc() : legLen - seg.getArc());
                Vec3 ps, cs, pt, ct, pl, cl;
                float pr, cr;
                if (segForward) {
                    ps = f.getPrevSpine(); cs = f.getCurrSpine();
                    pt = f.getPrevTangent(); ct = f.getCurrTangent();
                    pl = f.getPrevLateral(); cl = f.getCurrLateral();
                    pr = f.getPrevRadius(); cr = f.getCurrRadius();
                } else {
                    ps = f.getCurrSpine(); cs = f.getPrevSpine();
                    pt = f.getCurrTangent().scale(-1.0); ct = f.getPrevTangent().scale(-1.0);
                    pl = f.getCurrLateral().scale(-1.0); cl = f.getPrevLateral().scale(-1.0);
                    pr = f.getCurrRadius(); cr = f.getPrevRadius();
                }
                float flow = Math.abs(speed) * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK * scale;
                w.setSegment(ps, cs, pt, ct, pl, cl, pr, cr)
                    .light(LightTexture.FULL_BRIGHT)
                    .color(0.3f, 0.6f, 1f, 0.75f);
                w.wallThickness = wallThickness;
                w.mirror = mirror;
                // arc base along the physical axis from the upstream end
                w.arcBase = segForward ? seg.getArc() : legLen - seg.getArc();
                // frames are ordered along the flow, so the texture always scrolls
                // toward the downstream end (currSpine)
                w.flowSign = -1f;
                w.flowStart = flow;
                w.flowEnd = flow;
                w.flowUpstream = flow;
                w.downstreamMix = 1f;
                w.phaseUpstream = 0f;
                w.phaseStart = now * flow;
                w.phaseEnd = now * flow;
                w.setChanged();
                instances.add(w);
                waterInstances.add(w);
            }
        }

        private WaterslideTubeMesh.TubeSegmentFrame frameAtArc(float arc) {
            float acc = 0f;
            WaterslideTubeMesh.TubeSegmentFrame last = frames.get(0);
            for (WaterslideTubeMesh.TubeSegmentFrame f : frames) {
                float len = WaterslideTubeMesh.arcLength(f);
                if (acc + len >= arc) return f;
                acc += len;
                last = f;
            }
            return last;
        }

        private void buildStream(float wallThickness, float mirror) {
            if (water == null || !water.getExists() || water.getExit() == null) {
                streamWater = null;
                streamSegments = null;
                return;
            }
            if (streamWater != water) {
                streamWater = water;
                streamSegments = buildStreamSegments();
            }
            List<StreamSegment> segs = streamSegments;
            if (segs == null) return;
            // band model on the tube angular grid for the thrown water
            List<Float> ring = WaterslideTubeMesh.INSTANCE.bandVertices(0.85f, 0.6f, false);
            Model streamModel = WaterslideTubeMesh.INSTANCE.waterModelFor(ring, ring, 1f);
            Instancer<WaterslideTubeInstance> streamInstancer = instancerProvider().instancer(
                WaterslideTubeInstanceType.INSTANCE,
                streamModel
            );
            WaterslideTubeInstance[] arr = new WaterslideTubeInstance[segs.size()];
            streamInstancer.createInstances(arr);
            for (int i = 0; i < arr.length; i++) {
                StreamSegment s = segs.get(i);
                arr[i]
                    .setSegment(
                        s.prevSpine, s.currSpine,
                        s.prevTangent, s.currTangent,
                        s.prevLateral, s.currLateral,
                        s.prevRadius, s.currRadius
                    )
                    .light(LightTexture.FULL_BRIGHT)
                    .color(0.3f, 0.6f, 1f, 0.75f);
                arr[i].wallThickness = wallThickness;
                arr[i].mirror = mirror;
                arr[i].arcBase = s.arcBase;
                arr[i].flowSign = -1f;
                arr[i].flowStart = s.speed;
                arr[i].flowEnd = s.speed;
                arr[i].flowUpstream = s.speed;
                arr[i].downstreamMix = 1f;
                arr[i].phaseUpstream = 0f;
                arr[i].phaseStart = 0f;
                arr[i].phaseEnd = 0f;
                arr[i].setChanged();
                streamInstances.add(arr[i]);
                waterInstances.add(arr[i]);
            }
        }

        void setTranslucent(boolean value) {
            if (this.translucent == value) return;
            this.translucent = value;
            rebuildInstances();
        }

        void setShowSkeleton(boolean value) {
            if (this.showSkeleton == value) return;
            this.showSkeleton = value;
            if (translucent) {
                rebuildInstances();
            }
        }

        private void rebuildInstances() {
            delete();
            float wallThickness = ModClientConfig.INSTANCE.wallThickness();
            float mirror = this.mirror;
            Instancer<WaterslideTubeInstance> wallInstancer =
                instancerProvider().instancer(
                    WaterslideTubeInstanceType.INSTANCE,
                    translucent ? models.getWallTranslucent() : models.getWall()
                );
            WaterslideTubeInstance[] wall = new WaterslideTubeInstance[frames.size()];
            wallInstancer.createInstances(wall);
            for (int i = 0; i < wall.length; i++) {
                WaterslideTubeMesh.TubeSegmentFrame f = frames.get(i);
                Vec3 mid = f.getPrevSpine().add(f.getCurrSpine()).scale(0.5).add(origin);
                int light = LevelRenderer.getLightColor(level, BlockPos.containing(mid));
                wall[i]
                    .setSegment(
                        f.getPrevSpine(), f.getCurrSpine(),
                        f.getPrevTangent(), f.getCurrTangent(),
                        f.getPrevLateral(), f.getCurrLateral(),
                        f.getPrevRadius(), f.getCurrRadius()
                    )
                    .light(light)
                    .setChanged();
                wall[i].wallThickness = wallThickness;
                wall[i].mirror = mirror;
                if (translucent) {
                    wall[i].color(1f, 1f, 1f, 0.35f);
                }
                instances.add(wall[i]);
            }

            // degenerate cap segments
            WaterslideTubeMesh.TubeSegmentFrame first = frames.get(0);
            WaterslideTubeMesh.TubeSegmentFrame last = frames.get(frames.size() - 1);

            Instancer<WaterslideTubeInstance> startCapInstancer =
                instancerProvider().instancer(
                    WaterslideTubeInstanceType.INSTANCE,
                    translucent ? models.getStartCapTranslucent() : models.getStartCap()
                );
            WaterslideTubeInstance startCap = startCapInstancer.createInstance();
            Vec3 startTip = first.getPrevSpine();
            Vec3 startTan = first.getPrevTangent();
            int startLight = LevelRenderer.getLightColor(level, BlockPos.containing(startTip.add(origin)));
            startCap
                .setSegment(
                    startTip, startTip.add(startTan.scale(0.001)),
                    startTan, startTan,
                    first.getPrevLateral(), first.getPrevLateral(),
                    first.getPrevRadius(), first.getPrevRadius()
                )
                .light(startLight)
                .setChanged();
            startCap.wallThickness = wallThickness;
            startCap.mirror = mirror;
            if (translucent) {
                startCap.color(1f, 1f, 1f, 0.35f);
            }
            instances.add(startCap);

            Instancer<WaterslideTubeInstance> endCapInstancer =
                instancerProvider().instancer(
                    WaterslideTubeInstanceType.INSTANCE,
                    translucent ? models.getEndCapTranslucent() : models.getEndCap()
                );
            WaterslideTubeInstance endCap = endCapInstancer.createInstance();
            Vec3 endTip = last.getCurrSpine();
            Vec3 endTan = last.getCurrTangent();
            int endLight = LevelRenderer.getLightColor(level, BlockPos.containing(endTip.add(origin)));
            endCap
                .setSegment(
                    endTip, endTip.add(endTan.scale(0.001)),
                    endTan, endTan,
                    last.getCurrLateral(), last.getCurrLateral(),
                    last.getCurrRadius(), last.getCurrRadius()
                )
                .light(endLight)
                .setChanged();
            endCap.wallThickness = wallThickness;
            endCap.mirror = mirror;
            if (translucent) {
                endCap.color(1f, 1f, 1f, 0.35f);
            }
            instances.add(endCap);

            buildWaterBand(wallThickness, mirror);

            buildStream(wallThickness, mirror);

            // skeleton rings
            if (translucent && showSkeleton) {
                Instancer<WaterslideTubeInstance> ringInstancer =
                    instancerProvider().instancer(
                        WaterslideTubeInstanceType.INSTANCE,
                        models.getRingTranslucent()
                    );
                for (int i = 1; i < frames.size() - 1; i++) {
                    WaterslideTubeMesh.TubeSegmentFrame f = frames.get(i);
                    Vec3 junction = f.getPrevSpine();
                    Vec3 tan = f.getPrevTangent();
                    int ringLight = LevelRenderer.getLightColor(
                        level, BlockPos.containing(junction.add(origin))
                    );
                    WaterslideTubeInstance ring = ringInstancer.createInstance();
                    ring
                        .setSegment(
                            junction, junction.add(tan.scale(0.001)),
                            tan, tan,
                            f.getPrevLateral(), f.getPrevLateral(),
                            f.getPrevRadius(), f.getPrevRadius()
                        )
                        .light(ringLight)
                        .color(1f, 1f, 1f, 0.35f)
                        .setChanged();
                    ring.wallThickness = wallThickness;
                    ring.mirror = mirror;
                    instances.add(ring);
                }
            }
        }

        void delete() {
            for (WaterslideTubeInstance in : instances) {
                in.delete();
            }
            for (WaterslideTubeInstance in : streamInstances) {
                in.delete();
            }
            waterInstances.clear();
            streamInstances.clear();
            instances.clear();
        }

        void collectCrumblingInstances(Consumer<Instance> consumer) {
            for (WaterslideTubeInstance in : instances) {
                consumer.accept(in);
            }
        }
    }

    private static class StreamSegment {
        final Vec3 prevSpine;
        final Vec3 currSpine;
        final Vec3 prevTangent;
        final Vec3 currTangent;
        final Vec3 prevLateral;
        final Vec3 currLateral;
        final float prevRadius;
        final float currRadius;
        final float arcBase;
        final float speed;

        StreamSegment(
            Vec3 prevSpine, Vec3 currSpine,
            Vec3 prevTangent, Vec3 currTangent,
            Vec3 prevLateral, Vec3 currLateral,
            float prevRadius, float currRadius,
            float arcBase, float speed
        ) {
            this.prevSpine = prevSpine;
            this.currSpine = currSpine;
            this.prevTangent = prevTangent;
            this.currTangent = currTangent;
            this.prevLateral = prevLateral;
            this.currLateral = currLateral;
            this.prevRadius = prevRadius;
            this.currRadius = currRadius;
            this.arcBase = arcBase;
            this.speed = speed;
        }
    }
}
