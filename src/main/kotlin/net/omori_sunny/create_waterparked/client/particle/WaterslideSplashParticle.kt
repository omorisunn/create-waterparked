package net.omori_sunny.create_waterparked.client.particle

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SplashParticle
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// Vanilla splash texture, but with controlled velocity: 1% of the player's
// velocity, fast per-tick drag, and a linear fade over its short lifetime.
// Size is left at the vanilla splash size.
@OnlyIn(Dist.CLIENT)
class WaterslideSplashParticle(
    level: ClientLevel,
    x: Double,
    y: Double,
    z: Double,
    vx: Double,
    vy: Double,
    vz: Double,
    sprites: SpriteSet
) : SplashParticle(level, x, y, z, vx, vy, vz) {

    init {
        pickSprite(sprites)
        lifetime = 10 + random.nextInt(10)
        age = 0
        gravity = 0f
        hasPhysics = false
        xd = vx
        yd = vy
        zd = vz
        alpha = 1f
    }

    override fun tick() {
        if (age++ >= lifetime) {
            remove()
            return
        }
        xo = x
        yo = y
        zo = z

        // fast decay: velocity shrinks sharply every tick
        xd *= 0.75
        yd *= 0.75
        zd *= 0.75
        move(xd, yd, zd)

        val lifeT = 1f - age.toFloat() / lifetime
        alpha = lifeT
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType,
            level: ClientLevel,
            x: Double,
            y: Double,
            z: Double,
            vx: Double,
            vy: Double,
            vz: Double
        ): WaterslideSplashParticle =
            WaterslideSplashParticle(level, x, y, z, vx, vy, vz, sprites)
    }
}

