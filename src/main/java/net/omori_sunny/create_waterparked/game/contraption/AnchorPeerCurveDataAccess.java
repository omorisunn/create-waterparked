package net.omori_sunny.create_waterparked.game.contraption;

import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.core.BlockPos;

import java.util.Map;

// read and write access to the inherited peer curve maps
public interface AnchorPeerCurveDataAccess {
    Map<BlockPos, BezierConnection> waterparked$anchorPeerCurves();

    Map<BlockPos, Integer> waterparked$railRgb();

    Map<BlockPos, Integer> waterparked$beamRgb();
}
