package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.minecraft.core.BlockPos;
import net.omori_sunny.create_waterparked.game.contraption.AnchorPeerCurveDataAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

// Expose the inherited peer-curve maps so waterslide anchors can rewrite their
// absolute-space data when a contraption is disassembled.
@Mixin(CoasterAnchorpointBlockEntity.class)
public abstract class CoasterAnchorpointBlockEntityContraptionAccessorMixin implements AnchorPeerCurveDataAccess {

    @Shadow
    private Map<BlockPos, BezierConnection> anchorPeerCurves;

    @Shadow
    private Map<BlockPos, Integer> anchorPeerCurveRailDiffuseRgb;

    @Shadow
    private Map<BlockPos, Integer> anchorPeerCurveBeamDiffuseRgb;

    @Override
    @Unique
    public Map<BlockPos, BezierConnection> waterparked$anchorPeerCurves() {
        return anchorPeerCurves;
    }

    @Override
    @Unique
    public Map<BlockPos, Integer> waterparked$railRgb() {
        return anchorPeerCurveRailDiffuseRgb;
    }

    @Override
    @Unique
    public Map<BlockPos, Integer> waterparked$beamRgb() {
        return anchorPeerCurveBeamDiffuseRgb;
    }
}
