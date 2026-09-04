package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity
import net.omori_sunny.create_waterparked.content.sit.SlideSitEntity
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModEntityTypes {
    val REGISTRY: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(Registries.ENTITY_TYPE, CreateWaterparked.ID)

    val SLIDE_SIT: EntityType<SlideSitEntity> by REGISTRY.register("slide_sit") { ->
        EntityType.Builder.of(
            { type, level -> SlideSitEntity(type, level) },
            MobCategory.MISC
        )
            .sized(0.01f, 0.01f)
            .noSummon()
            .noSave()
            .updateInterval(1)
            .build("slide_sit")
    }

    val INFLATABLE_BOAT_1X2: EntityType<InflatableBoat1x2Entity> by REGISTRY.register("inflatable_boat_1x2") { ->
        EntityType.Builder.of(
            { type, level -> InflatableBoat1x2Entity(type, level) },
            MobCategory.MISC
        )
            // collision box matches the model: 1 block wide, deck height 3/16
            .sized(1.0f, 0.1875f)
            .setShouldReceiveVelocityUpdates(true)
            .setUpdateInterval(1)
            .build("inflatable_boat_1x2")
    }
}
