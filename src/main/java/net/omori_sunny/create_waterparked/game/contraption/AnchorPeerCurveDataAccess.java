package net.omori_sunny.create_waterparked.game.contraption;

import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * Read/write access to the private peer-curve maps inherited from
 * {@link dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity}.
 * Implemented by
 * {@code net.omori_sunny.create_waterparked.mixin.CoasterAnchorpointBlockEntityContraptionAccessorMixin}.
 *
 * <p>This interface intentionally lives OUTSIDE the {@code ...mixin} package:
 * Mixin forbids normal code from referencing classes inside a defined mixin
 * package ({@code IllegalClassLoadError}).</p>
 */
public interface AnchorPeerCurveDataAccess {
    Map<BlockPos, BezierConnection> waterparked$anchorPeerCurves();

    Map<BlockPos, Integer> waterparked$railRgb();

    Map<BlockPos, Integer> waterparked$beamRgb();
}
