package net.omori_sunny.create_waterparked.game.contraption

import com.simibubi.create.api.behaviour.movement.MovementBehaviour
import com.simibubi.create.content.contraptions.behaviour.MovementContext
import com.simibubi.create.content.contraptions.render.ActorVisual
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import net.minecraft.nbt.Tag
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// Movement behaviour for the waterslide anchor block. It renders the custom tube
// and static in-tube water visuals while the slide is assembled onto a Create
// contraption. Server-side movement hooks keep their default no-ops; the visual
// is client-only and driven entirely by the block entity data captured at pickup.
object WaterslideContraptionBehaviour : MovementBehaviour {

    @OnlyIn(Dist.CLIENT)
    override fun createVisual(
        visualizationContext: VisualizationContext,
        simulationWorld: VirtualRenderWorld,
        movementContext: MovementContext
    ): ActorVisual? {
        // Only anchors that carry peer curves have anything to draw; a lone
        // anchor (no connecting curves) needs no tube or water visuals.
        val data = movementContext.blockEntityData ?: return null
        if (!data.contains("AnchorPeerCurves", Tag.TAG_LIST.toInt())) return null
        return net.omori_sunny.create_waterparked.client.contraption.WaterslideContraptionTubeVisual(
            visualizationContext, simulationWorld, movementContext
        )
    }
}
