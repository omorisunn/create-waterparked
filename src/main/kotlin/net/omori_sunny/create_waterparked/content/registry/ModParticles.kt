package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

// particle types
object ModParticles {
    val REGISTRY: DeferredRegister<ParticleType<*>> =
        DeferredRegister.create(Registries.PARTICLE_TYPE, CreateWaterparked.ID)

    val WATER_SLIDE_SPLASH: SimpleParticleType by REGISTRY.register("waterslide_splash") { ->
        SimpleParticleType(false)
    }
}
