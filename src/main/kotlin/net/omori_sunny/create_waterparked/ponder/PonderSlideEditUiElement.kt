package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.foundation.ponder.CreateSceneBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import java.util.concurrent.ConcurrentHashMap

// Ponder edit-state driver; the BER renders the ghost tube plus the edit ring UI.
class PonderSlideEditUiElement {

    class Frame(
        val center: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float,
        val progress: Float
    )

    companion object {
        // per-level Ponder edit anchor: level -> anchor block
        private val ponderEditAnchors = ConcurrentHashMap<Level, BlockPos>()

        // queue the Ponder edit state at the storyboard beat (runs scene-time)
        @JvmStatic
        fun show(scene: CreateSceneBuilder, be: WaterslideAnchorBlockEntity) {
            scene.addInstruction { setPonderEdit(it.getWorld(), be.blockPos, true) }
        }

        // queue the Ponder edit state removal at the storyboard beat
        @JvmStatic
        fun hide(scene: CreateSceneBuilder, anchor: BlockPos) {
            scene.addInstruction { setPonderEdit(it.getWorld(), anchor, false) }
        }

        // drives the BER edit-state (translucent ghost tube + ring/handle UI)
        @JvmStatic
        fun setPonderEdit(level: Level, anchor: BlockPos, editing: Boolean) {
            if (editing) ponderEditAnchors[level] = anchor.immutable() else ponderEditAnchors.remove(level)
        }

        @JvmStatic
        fun ponderEditAnchorFor(level: Level): BlockPos? = ponderEditAnchors[level]
    }
}
