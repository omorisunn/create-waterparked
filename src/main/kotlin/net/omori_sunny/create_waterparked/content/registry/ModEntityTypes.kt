package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
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
}
