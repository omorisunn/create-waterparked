package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackMaterial
import net.minecraft.world.level.block.state.BlockBehaviour

// Slide curve block, reusing Create's track logic.
class WaterslideTrackBlock(properties: BlockBehaviour.Properties, material: TrackMaterial) :
    TrackBlock(properties, material)
