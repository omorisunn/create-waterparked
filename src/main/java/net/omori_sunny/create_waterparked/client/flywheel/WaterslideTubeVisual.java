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
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
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
import net.minecraft.world.level.LightLayer;
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
import org.joml.Vector3d;

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
    // fixed cross-section fractions so every segment shares the SAME water model;
    // using a per-segment radius made adjacent segments' bed radius jump -> cracks
    private static final float WATER_IN_FRAC = 0.9f;
    private static final float WATER_SURF_FRAC = 0.8f;

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // same light sampling as Coasters Simulated's flywheel/BER renderers, with
    // a +3 brightness boost for the translucent water surfaces
    private int waterLight(Level level, Vec3 pos) {
        BlockPos bp = BlockPos.containing(pos);
        int block = level.getBrightness(LightLayer.BLOCK, bp) + 3;
        int sky = level.getBrightness(LightLayer.SKY, bp) + 3;
        return LightTexture.pack(Mth.clamp(block, 0, 15), Mth.clamp(sky, 0, 15));
    }

    // World gravity expressed in the curve's local coordinate space, so thrown
    // water follows the rotated shape of a Sable sub-level.
    private Vec3 localGravity(BezierConnection curve) {
        Level lvl = be.getLevel();
        if (lvl == null) return new Vec3(0.0, -32.0, 0.0);
        SubLevel sub = Sable.HELPER.getContaining(lvl, curve.bePositions.getFirst());
        if (sub == null) return new Vec3(0.0, -32.0, 0.0);
        Vector3d out = sub.logicalPose().transformNormalInverse(
            JOMLConversion.toJOML(new Vec3(0.0, -32.0, 0.0)), new Vector3d()
        );
        return JOMLConversion.toMojang(out);
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
        lastWaterTime = now;
        // set the phase directly (no accumulation) and wrap to [0,1): an
        // ever-growing accumulated phase makes the texture sampling density
        // degrade over time; direct flow*now keeps every segment's scroll speed
        // fixed and stable
        for (TubeCurve c : curves) {
            for (WaterslideTubeInstance w : c.waterInstances) {
                // direct per-segment texture scroll (no easing): flowUpstream
                // carries this instance's own scroll rate while flowStart/End
                // are reserved for jitter amplitude/time blending
                w.phaseStart = (w.flowUpstream * now) % 1.0f;
                w.phaseEnd = (w.flowUpstream * now) % 1.0f;
                w.phaseUpstream = (w.flowUpstream * now) % 1.0f;
                w.jitterTime = now;
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
        // 0.5-block sampling matching the server arc accumulation, so band
        // placement aligns with the simulated water segments
        private List<WaterslideTubeMesh.TubeSegmentFrame> waterFrames;
        // cumulative shader arc length at each water frame; used instead of the
        // flat 0.5 chord assumption so arcBase is bit-compatible with the
        // shader's arcLenTo and adjacent water instances share the same texture
        // phase at their boundary ring
        private float[] waterPrefixArcs;
        private float waterTotalArc;
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
            this.waterFrames = buildWaterFrames(r0, r1);
            rebuildWaterArcs();
            logJunctionDiagnostics();
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
            this.waterFrames = buildWaterFrames(r0, r1);
            rebuildWaterArcs();
            logJunctionDiagnostics();
            this.config = WaterslideSectorEdit.INSTANCE.previewConfigFor(a, b);
            if (this.config == null) {
                this.config = be.sectorConfigFor(peer);
            }
            this.models = WaterslideTubeMesh.INSTANCE.modelsFor(level, this.config);
            this.water = WaterFlowSimulation.INSTANCE.resultFor(level, curve);
            rebuildInstances();
        }

        // 0.5-block sampling matching the server's segIdx = floor(chordArc/0.5)
        // grid exactly. The old chord-merge drifted on curved segments (needing 3
        // fine CCS steps to reach 0.5), leaving fewer frames than server segments
        // and clamping every late arc to the same last frame -> vanishing bed at
        // the junction. Interpolate at uniform 0.5-chord boundaries instead.
        private List<WaterslideTubeMesh.TubeSegmentFrame> buildWaterFrames(float r0, float r1) {
            List<SlideCurveGeometry.Frame> sf =
                SlideCurveGeometry.INSTANCE.sampleFrames(level, curve, r0, r1, 0.5, true);
            List<WaterslideTubeMesh.TubeSegmentFrame> out = new ArrayList<>();
            if (sf.size() < 2) return out;
            double[] prefix = new double[sf.size()];
            for (int i = 1; i < sf.size(); i++) {
                prefix[i] = prefix[i - 1] + sf.get(i - 1).getCenter().distanceTo(sf.get(i).getCenter());
            }
            double total = prefix[sf.size() - 1];
            if (total < 1.0E-6) return out;
            int segCount = (int) Math.ceil(total / 0.5);
            if (segCount < 1) segCount = 1;

            Vec3 prevCenter = sf.get(0).getCenter();
            Vec3 prevTan = sf.get(0).getTangent();
            Vec3 prevLat = sf.get(0).getLateral();
            float prevRadius = sf.get(0).getRadius();
            int scan = 1;
            for (int s = 1; s <= segCount; s++) {
                double targetChord = Math.min(s * 0.5, total);
                while (scan + 1 < sf.size() && prefix[scan] < targetChord) scan++;
                double segLen = prefix[scan] - prefix[scan - 1];
                double f = segLen > 1.0E-9 ? (targetChord - prefix[scan - 1]) / segLen : 0.0;
                SlideCurveGeometry.Frame a = sf.get(scan - 1);
                SlideCurveGeometry.Frame b = sf.get(scan);
                Vec3 center = a.getCenter().add(b.getCenter().subtract(a.getCenter()).scale(f));
                // sample the real rail frame at the interpolated curve t instead
                // of linearly blending two tangents/laterals. Lerp can cancel out
                // on sharp bends and produce a control tangent pointing sideways
                // or backwards, which makes the cubic loop and spike out of the
                // tube at the affected ring.
                float t = (float) (a.getT() + (b.getT() - a.getT()) * f);
                Vec3 tan = CoasterBezierRailFrames.unitTangentAt(curve, t);
                if (tan.lengthSqr() < 1.0E-9) tan = a.getTangent();
                tan = tan.normalize();
                Vec3 lat = CoasterBezierRailFrames.lateralAt(curve, t, level);
                if (lat.lengthSqr() < 1.0E-9) lat = prevLat;
                if (lat.dot(prevLat) < 0.0) lat = lat.scale(-1.0);
                // never let a sampled rail tangent point backwards along this
                // 0.5-chord span: the shader reconstructs a cubic with these
                // tangents as controls, and a backwards control loops the mesh
                // into the long spike seen at segment joints
                Vec3 chordDir = center.subtract(prevCenter);
                if (chordDir.lengthSqr() > 1.0E-12) {
                    chordDir = chordDir.normalize();
                    if (prevTan.dot(chordDir) < 0.0) prevTan = prevTan.scale(-1.0);
                    if (tan.dot(chordDir) < 0.0) tan = tan.scale(-1.0);
                }
                float radius = (float) (a.getRadius() + (b.getRadius() - a.getRadius()) * f);
                WaterslideTubeMesh.TubeSegmentFrame frame = new WaterslideTubeMesh.TubeSegmentFrame(
                    prevCenter.subtract(origin), center.subtract(origin),
                    prevTan, tan, prevLat, lat, prevRadius, radius
                );
                out.add(frame);
                Vec3 chord = frame.getCurrSpine().subtract(frame.getPrevSpine());
                double chordLen = chord.length();
                double tangentDot = chordLen < 1.0E-9 ? 1.0 : chord.normalize().dot(tan);
                if (tangentDot < 0.25) {
                    CreateWaterparked.INSTANCE.getLOGGER().info(
                        "[WaterFrame] edge=({},{}) idx={} t={} dot={} chord={} tan={} lat={} r0={} r1={}",
                        curve.bePositions.getFirst().asLong(), curve.bePositions.getSecond().asLong(),
                        out.size() - 1, t, tangentDot, chord, tan, lat, prevRadius, radius
                    );
                }
                prevCenter = center;
                prevTan = tan;
                prevLat = lat;
                prevRadius = radius;
            }
            return out;
        }

        private void rebuildWaterArcs() {
            waterPrefixArcs = new float[waterFrames.size() + 1];
            for (int i = 0; i < waterFrames.size(); i++) {
                waterPrefixArcs[i + 1] = waterPrefixArcs[i] +
                    WaterslideTubeMesh.INSTANCE.arcLength(waterFrames.get(i));
            }
            waterTotalArc = Math.max(waterPrefixArcs[waterFrames.size()], 1.0E-4f);
        }

        // TEMP DIAGNOSTIC for the junction spike: print how the water end-ring
        // frame compares with the neighboring curve's frame.
        private void logJunctionDiagnostics() {
            if (waterFrames.size() < 2) return;
            for (boolean atFirst : new boolean[]{true, false}) {
                BlockPos anchor = atFirst
                    ? curve.bePositions.getFirst()
                    : curve.bePositions.getSecond();
                BezierConnection nb = neighborCurveAt(anchor);
                if (nb == null) continue;
                boolean nbAtFirst = nb.bePositions.getFirst().equals(anchor);
                float nr0 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                    level, nb.bePositions.getFirst(), ModConfig.INSTANCE.defaultSlideRadius()
                );
                float nr1 = WaterslideRadiusEdit.INSTANCE.radiusAt(
                    level, nb.bePositions.getSecond(), ModConfig.INSTANCE.defaultSlideRadius()
                );
                List<SlideCurveGeometry.Frame> nf = SlideCurveGeometry.INSTANCE.sampleFrames(
                    level, nb, nr0, nr1, 0.5, false
                );
                if (nf.size() < 2) continue;
                SlideCurveGeometry.Frame nbFrame = nbAtFirst ? nf.get(0) : nf.get(nf.size() - 1);
                int idx = atFirst ? 0 : waterFrames.size() - 1;
                WaterslideTubeMesh.TubeSegmentFrame f = waterFrames.get(idx);
                Vec3 ownAway = atFirst ? f.getPrevTangent() : f.getCurrTangent().scale(-1.0);
                Vec3 ownLatAway = atFirst ? f.getPrevLateral() : f.getCurrLateral().scale(-1.0);
                Vec3 nbAway = nbAtFirst ? nbFrame.getTangent() : nbFrame.getTangent().scale(-1.0);
                Vec3 nbLatAway = nbAtFirst ? nbFrame.getLateral() : nbFrame.getLateral().scale(-1.0);
                float ownR = atFirst ? f.getPrevRadius() : f.getCurrRadius();
                CreateWaterparked.INSTANCE.getLOGGER().info(
                    "[WaterJunction] edge=({},{}) side={} anchor={} tanDot={} latDot={} rDiff={} ownTan={} nbTan={} ownLat={} nbLat={}",
                    curve.bePositions.getFirst().asLong(), curve.bePositions.getSecond().asLong(),
                    atFirst ? "first" : "last", anchor,
                    ownAway.normalize().dot(nbAway.normalize()),
                    ownLatAway.normalize().dot(nbLatAway.normalize()),
                    ownR - nbFrame.getRadius(),
                    ownAway, nbAway, ownLatAway, nbLatAway
                );
            }
        }

        // the other watered curve sharing a junction anchor, if any
        @Nullable
        private BezierConnection neighborCurveAt(BlockPos anchor) {
            if (!(level.getBlockEntity(anchor) instanceof CoasterAnchorpointBlockEntity anchorBe)) {
                return null;
            }
            if (anchorBe.legCount() != 2) return null;
            for (Map.Entry<BlockPos, BezierConnection> e : anchorBe.getAnchorPeerCurvesView().entrySet()) {
                BezierConnection raw = e.getValue();
                if (raw == null) continue;
                BezierConnection bc = raw.isPrimary() ? raw : raw.secondary();
                if (bc == null || sameEdge(bc, curve)) continue;
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue;
                if (bc.bePositions.getFirst().equals(anchor) || bc.bePositions.getSecond().equals(anchor)) {
                    return bc;
                }
            }
            return null;
        }

        private boolean sameEdge(BezierConnection x, BezierConnection y) {
            long xa = x.bePositions.getFirst().asLong();
            long xb = x.bePositions.getSecond().asLong();
            long ya = y.bePositions.getFirst().asLong();
            long yb = y.bePositions.getSecond().asLong();
            return (xa == ya && xb == yb) || (xa == yb && xb == ya);
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
            // use the outlet frame's real lateral so the thrown sheet's
            // cross-section matches the in-tube band at the mouth; faceUp is
            // recomputed exactly like the vertex shader (cross(tangent, lateral))
            Vec3 lat0 = forward ? outlet.getCurrLateral() : outlet.getPrevLateral().scale(-1.0);
            Vec3 up0 = outletTan.cross(lat0);
            if (up0.lengthSqr() < 1.0E-9) {
                up0 = new Vec3(0.0, 1.0, 0.0);
            }
            up0 = up0.normalize();
            float radius = forward ? outlet.getCurrRadius() : outlet.getPrevRadius();
            float rIn = Math.max(0.05f, radius - WALL_THICKNESS * 1.5f);
            // depth scales with the radius so a narrow mouth keeps a visible surface
            float rSurf = Math.max(0.01f, rIn - Math.min(0.25f, radius * 0.25f));
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
            // initial throw direction follows the outlet tangent (strict physics);
            // magnitude taken from the simulated exit velocity
            Vec3 throwVel = outletTan.scale(exit.getVel().length());
            Pair<List<List<Vec3>>, List<List<Vec3>>> res =
                WaterFlowSimulation.INSTANCE.predictStreams(
                    level, exit.getPos(), throwVel, outletCenter.add(origin), lat0, up0,
                    rIn, rSurf, c0, c1, own, localGravity(curve)
                );
            if (res == null) return null;
            List<List<Vec3>> outer = res.getFirst();
            List<List<Vec3>> inner = res.getSecond();
            if (outer.isEmpty() || inner.isEmpty()) return null;
            // use the longest ray as the centerline so the sheet extends to the
            // farthest reach instead of cutting mid-air at the shortest ray
            List<Vec3> o = outer.get(0);
            for (List<Vec3> ray : outer) if (ray.size() > o.size()) o = ray;
            List<Vec3> in = inner.get(0);
            for (List<Vec3> ray : inner) if (ray.size() > in.size()) in = ray;
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
            // keep the outlet frame's cross-section (lat0/up0 already computed
            // from the outlet lateral + faceUp); re-projecting against the
            // vertical fall direction collapses it to zero, which flattens the
            // thrown sheet into a ground-hugging strip

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
                WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK / 40f *
                ModClientConfig.INSTANCE.waterFlowScale();
            List<StreamSegment> segs = new ArrayList<>();
            float arcBase = 0f;
            for (int i = 0; i < centers.size() - 1; i++) {
                Vec3 c0p = centers.get(i);
                Vec3 c1p = centers.get(i + 1);
                segs.add(new StreamSegment(
                    c0p, c1p, tans[i], tans[i + 1], lats[i], lats[i + 1], tubeRadius, tubeRadius, arcBase, speed
                ));
                // fixed 0.5 step matches the shader's arcCoord = arcBase + t*0.5,
                // so thrown-stream segments also share identical jitter at their seams
                arcBase += 0.5f;
            }
            return segs.isEmpty() ? null : segs;
        }

        private void buildWaterBand(float wallThickness, float mirror) {
            if (water == null || !water.getExists()) return;
            List<ServerWaterSimulation.WaterSegment> segments = water.getSegments();
            if (segments.isEmpty()) return;
            if (waterFrames.isEmpty() || waterPrefixArcs == null) return;
            float now = AnimationTickHolder.getRenderTime(level);
            float scale = ModClientConfig.INSTANCE.waterFlowScale();
            for (int i = 0; i < segments.size(); i++) {
                ServerWaterSimulation.WaterSegment seg = segments.get(i);
                ServerWaterSimulation.WaterSegment nxt = (i + 1 < segments.size())
                    ? segments.get(i + 1) : seg;
                // per-segment speed = particle speed; keep the same writing as
                // a normal interior water segment (no junction-specific tweaks)
                renderBand(seg.getArc(), seg.getSpeed(), nxt.getSpeed(),
                    wallThickness, mirror, scale, now);
                // fill a segment the server simulation occasionally misses
                if (i + 1 < segments.size()) {
                    float gap = nxt.getArc() - seg.getArc();
                    if (gap > 0.75f) {
                        float midArc = seg.getArc() + gap * 0.5f;
                        float midSpeed = (seg.getSpeed() + nxt.getSpeed()) * 0.5f;
                        renderBand(midArc, midSpeed, nxt.getSpeed(),
                            wallThickness, mirror, scale, now);
                    }
                }
            }
        }

        private void renderBand(float arc, float speed, float speedNext,
                                float wallThickness, float mirror, float scale, float now) {
            boolean segForward = speed >= 0f;
            int frameIdx = frameIndexAtArc(arc);
            WaterslideTubeMesh.TubeSegmentFrame f = waterFrames.get(frameIdx);
            // use this frame's own radius so the bed/surface fractions stay
            // correct when the tube narrows along the curve (fixes the
            // vanishing surface band at narrow mouths)
            float frameRadius = Math.max(0.1f, (f.getPrevRadius() + f.getCurrRadius()) * 0.5f);
            float rInFrac = WATER_IN_FRAC;
            float rSurfFrac = WATER_SURF_FRAC;
            // mirror the ring vertices for backward segments: combined with the
            // reversed instance frame this keeps the same physical vertex order,
            // so the cross-section U coordinate stays continuous at the seam.
            // The shader uses a mirror-symmetric angle key (cos(2*ang)) and the
            // mesh bakes boundaryFactor from -|u|, so the mirrored local angle
            // no longer breaks jitter or amplitude continuity.
            List<Float> verts = WaterslideTubeMesh.INSTANCE.bandVertices(rInFrac, rSurfFrac, !segForward);
            int vertsHalf = verts.size() / 2;
            Model waterModel = WaterslideTubeMesh.INSTANCE.waterModelFor(
                verts.subList(0, vertsHalf), verts.subList(vertsHalf, verts.size()), frameRadius
            );
            Instancer<WaterslideTubeInstance> waterInstancer = instancerProvider().instancer(
                WaterslideTubeInstanceType.INSTANCE, waterModel
            );
            WaterslideTubeInstance w = waterInstancer.createInstance();
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
            // flowStart/flowEnd follow the render direction exactly like a
            // normal interior segment (jitter amplitude/time blends to the next
            // segment in the same curve; no junction special-casing)
            float k = WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK * scale;
            float ownFlow = Math.abs(speed) * k / 40f;
            float flow;
            float flowEnd;
            if (segForward) {
                flow = ownFlow;
                flowEnd = Math.abs(speedNext) * k / 40f;
            } else {
                flow = Math.abs(speedNext) * k / 40f;
                flowEnd = ownFlow;
            }
            Vec3 mid = ps.add(cs).scale(0.5).add(origin);
            int light = waterLight(level, mid);
            w.setSegment(ps, cs, pt, ct, pl, cl, pr, cr)
                .light(light)
                .color(0.3f, 0.6f, 1f, 0.75f);
            w.wallThickness = wallThickness;
            w.mirror = mirror;
            // accumulate the shader's own bezier arc length (not the flat 0.5
            // chord grid) so consecutive instances land on exactly the same UV
            // coordinate at their shared ring; reverse-flow instances start at
            // the downstream end of their frame
            w.arcBase = segForward
                ? waterPrefixArcs[frameIdx]
                : waterTotalArc - waterPrefixArcs[frameIdx + 1];
            w.flowSign = -1f;
            w.flowStart = flow;
            w.flowEnd = flowEnd;
            // direct texture scroll: this segment's own speed, no easing
            w.flowUpstream = ownFlow;
            w.downstreamMix = 1f;
            w.jitterScale = ModClientConfig.INSTANCE.waterJitterScale();
            w.jitterFrequency = ModClientConfig.INSTANCE.waterJitterFrequency();
            w.jitterTimeScale = ModClientConfig.INSTANCE.waterJitterTimeScale();
            w.phaseUpstream = 0f;
            w.phaseStart = (now * ownFlow) % 1.0f;
            w.phaseEnd = (now * ownFlow) % 1.0f;
            w.setChanged();
            instances.add(w);
            waterInstances.add(w);
        }

        private int frameIndexAtArc(float arc) {
            int idx = (int) Math.floor(arc / 0.5f);
            if (idx < 0) idx = 0;
            if (idx >= waterFrames.size()) idx = waterFrames.size() - 1;
            return idx;
        }

        // a curve end is a true open end (tube mouth) only when the anchor
        // carries a single curve; legCount()==2 means an interior junction
        private boolean isOpenEnd(BlockPos anchor) {
            if (level.getBlockEntity(anchor) instanceof CoasterAnchorpointBlockEntity anchorBe) {
                return anchorBe.legCount() == 1;
            }
            return false;
        }

        private void buildStream(float wallThickness, float mirror) {
            if (water == null || !water.getExists() || water.getExit() == null) {
                streamWater = null;
                streamSegments = null;
                return;
            }
            // only throw from a true open end (legCount==1); a junction (legCount==2)
            // must not spawn thrown water through the seam
            boolean streamForward = water.getFlowSign() < 0f;
            BlockPos outletAnchor = streamForward
                ? curve.bePositions.getSecond()
                : curve.bePositions.getFirst();
            if (!isOpenEnd(outletAnchor)) {
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
            // thrown-water cross-section must match the in-tube band at the
            // outlet so the bed and surface rings line up (no hardcoded fraction)
            WaterslideTubeMesh.TubeSegmentFrame outletF = streamForward
                ? frames.get(frames.size() - 1) : frames.get(0);
            float outletRadius = Math.max(0.1f,
                streamForward ? outletF.getCurrRadius() : outletF.getPrevRadius());
            float rInFrac = WATER_IN_FRAC;
            float rSurfFrac = WATER_SURF_FRAC;
            List<Float> ring = WaterslideTubeMesh.INSTANCE.bandVertices(rInFrac, rSurfFrac, false);
            // split into the two arcs so no radial connecting quad is emitted
            int ringHalf = ring.size() / 2;
            Model streamModel = WaterslideTubeMesh.INSTANCE.waterModelFor(
                ring.subList(0, ringHalf), ring.subList(ringHalf, ring.size()), outletRadius
            );
            Instancer<WaterslideTubeInstance> streamInstancer = instancerProvider().instancer(
                WaterslideTubeInstanceType.INSTANCE,
                streamModel
            );
            WaterslideTubeInstance[] arr = new WaterslideTubeInstance[segs.size()];
            streamInstancer.createInstances(arr);
            // last third of the stream fades out; expressed in the shader's arc
            // coordinates (0.5 per segment) so the fade is continuous across
            // vertices instead of a per-segment alpha step.
            int fadeStart = Math.max(0, arr.length - arr.length / 3);
            float fadeStartArc = fadeStart * 0.5f;
            float fadeEndArc = arr.length * 0.5f;
            for (int i = 0; i < arr.length; i++) {
                StreamSegment s = segs.get(i);
                // ramp the jitter up toward the tail so the water breaks up
                // more violently as it thins out
                float jitterBoost = 1f;
                if (arr.length > 1 && i >= fadeStart) {
                    float tailT = (float) (i - fadeStart + 1) / (arr.length - fadeStart + 1);
                    // ramp the jitter up sharply toward the tail so the water
                    // breaks apart more violently as it thins out
                    jitterBoost = 1f + tailT * tailT * 8.0f;
                }
                Vec3 mid = s.prevSpine.add(s.currSpine).scale(0.5).add(origin);
                int light = waterLight(level, mid);
                arr[i]
                    .setSegment(
                        s.prevSpine, s.currSpine,
                        s.prevTangent, s.currTangent,
                        s.prevLateral, s.currLateral,
                        s.prevRadius, s.currRadius
                    )
                    .light(light)
                    .color(0.3f, 0.6f, 1f, 0.75f);
                arr[i].wallThickness = wallThickness;
                arr[i].mirror = mirror;
                arr[i].arcBase = s.arcBase;
                arr[i].flowSign = -1f;
                arr[i].flowStart = s.speed;
                arr[i].flowEnd = s.speed;
                arr[i].flowUpstream = s.speed;
                arr[i].downstreamMix = 1f;
                arr[i].jitterScale = ModClientConfig.INSTANCE.waterJitterScale() * jitterBoost;
                arr[i].jitterFrequency = ModClientConfig.INSTANCE.waterJitterFrequency();
                arr[i].jitterTimeScale = ModClientConfig.INSTANCE.waterJitterTimeScale();
                arr[i].tailFadeStart = fadeStartArc;
                arr[i].tailFadeEnd = fadeEndArc;
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

            // caps only at true open ends (legCount==1); an interior anchor
            // (legCount==2) is a junction between two curves and must stay open
            // so no cross-section disc is drawn across the tube there
            WaterslideTubeMesh.TubeSegmentFrame first = frames.get(0);
            WaterslideTubeMesh.TubeSegmentFrame last = frames.get(frames.size() - 1);

            if (isOpenEnd(curve.bePositions.getFirst())) {
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
            }

            if (isOpenEnd(curve.bePositions.getSecond())) {
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
            }

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
