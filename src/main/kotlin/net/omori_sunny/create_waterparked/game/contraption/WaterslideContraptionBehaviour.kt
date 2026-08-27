package net.omori_sunny.create_waterparked.game.contraption

import com.simibubi.create.api.behaviour.movement.MovementBehaviour
import com.simibubi.create.content.contraptions.behaviour.MovementContext
import com.simibubi.create.content.contraptions.render.ActorVisual
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import net.omori_sunny.create_waterparked.client.contraption.WaterslideContraptionTubeVisual
import net.minecraft.nbt.Tag
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// renders the tube and in tube water for a mounted slide anchor
object WaterslideContraptionBehaviour : MovementBehaviour {

    @OnlyIn(Dist.CLIENT)
    override fun createVisual(
        visualizationContext: VisualizationContext,
        simulationWorld: VirtualRenderWorld,
        movementContext: MovementContext
    ): ActorVisual? {
        // draw only anchors that carry peer curves
        val data = movementContext.blockEntityData ?: return null
        if (!data.contains("AnchorPeerCurves", Tag.TAG_LIST.toInt())) return null
        return WaterslideContraptionTubeVisual(
            visualizationContext, simulationWorld, movementContext
        )
    }
}
