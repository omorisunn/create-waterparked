package net.omori_sunny.create_waterparked.client.flywheel;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.config.ModClientConfig;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// one visual per anchor
public class WaterslideTubeVisual extends AbstractVisual
    implements BlockEntityVisual<WaterslideAnchorBlockEntity>, ShaderLightVisual {

    private static final Set<WaterslideTubeVisual> ACTIVE =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final WaterslideAnchorBlockEntity be;
    private final List<TubeCurve> curves = new ArrayList<>();
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
        collect();
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
        if (mc.level == null) return;
        boolean edit = BezierHandleEditMode.isActive();
        BlockPos editAnchor = edit ? BezierHandleEditMode.getActiveAnchor() : null;
        boolean showSkeleton = ModClientConfig.INSTANCE.showSkeletonWhenTranslucent();
        boolean dragging =
            WaterslideSectorEdit.INSTANCE.isDraggingControlPoint() ||
                WaterslideRadiusEdit.INSTANCE.isDragging() ||
                BezierHandleDragManager.isDraggingTangentHandle();
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
            // avoid per-tick rebuild
            if (dragging && editAnchor != null) {
                visual.refreshAnchorCurves(editAnchor);
            }
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
        private final List<WaterslideTubeInstance> instances = new ArrayList<>();
        private boolean translucent = false;
        private boolean showSkeleton = false;

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
            WaterslideSectorConfig config = be.sectorConfigFor(peer);
            this.models = WaterslideTubeMesh.INSTANCE.modelsFor(
                this.level, config, Math.max(r0, r1)
            );
            rebuildInstances();
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
            WaterslideSectorConfig config = WaterslideSectorEdit.INSTANCE.previewConfigFor(a, b);
            if (config == null) {
                config = be.sectorConfigFor(peer);
            }
            this.models = WaterslideTubeMesh.INSTANCE.modelsFor(level, config, Math.max(r0, r1));
            rebuildInstances();
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
            if (translucent) {
                endCap.color(1f, 1f, 1f, 0.35f);
            }
            instances.add(endCap);

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
                    instances.add(ring);
                }
            }
        }

        void delete() {
            for (WaterslideTubeInstance in : instances) {
                in.delete();
            }
            instances.clear();
        }

        void collectCrumblingInstances(Consumer<Instance> consumer) {
            for (WaterslideTubeInstance in : instances) {
                consumer.accept(in);
            }
        }
    }
}
