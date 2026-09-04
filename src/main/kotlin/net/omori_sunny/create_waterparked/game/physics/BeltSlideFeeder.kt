package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

// belt ends near a slide mouth feed end-segment items into the tube (Core BeltInterception pattern)
object BeltSlideFeeder {

    private const val BELT_SCAN_RADIUS = 3
    private const val MOUTH_REFRESH_TICKS = 20L
    private const val BELT_RESCAN_TICKS = 40L
    private const val END_SEGMENT = 0.25f

    private class FeederMouth(
        val access: SlideSpaceAccess,
        val scanCenter: BlockPos,
        val worldPos: Vec3,
        val worldTangent: Vec3
    ) {
        var belts: List<BlockPos> = emptyList()
        var nextBeltScan: Long = 0L
    }

    private val mouths = HashMap<ResourceKey<Level>, Pair<Long, MutableList<FeederMouth>>>()

    @JvmStatic
    fun tick(level: ServerLevel) {
        val time = level.gameTime
        val cached = mouths[level.dimension()]
        val list: MutableList<FeederMouth> = when {
            cached != null && time - cached.first < MOUTH_REFRESH_TICKS -> cached.second
            else -> {
                val rebuilt = PlayerSlideController.allSlideMouths(level).map { m ->
                    FeederMouth(
                        m.access,
                        // sub-level belts live at plot coords
                        BlockPos.containing(m.localPos), m.worldPos, m.worldTangent
                    )
                }.toMutableList()
                mouths[level.dimension()] = time to rebuilt
                rebuilt
            }
        }
        for (mouth in list) {
            if (mouth.belts.isEmpty() && time < mouth.nextBeltScan) continue
            if (time >= mouth.nextBeltScan) {
                mouth.nextBeltScan = time + BELT_RESCAN_TICKS
                mouth.belts = scanBelts(level, mouth)
            }
            feed(level, mouth)
        }
    }

    private fun scanBelts(level: ServerLevel, mouth: FeederMouth): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val r = BELT_SCAN_RADIUS
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val pos = mouth.scanCenter.offset(dx, dy, dz)
            if (mouth.access.getBlockEntity(pos) is BeltBlockEntity) out += pos.immutable()
        }
        return out
    }

    private fun feed(level: ServerLevel, mouth: FeederMouth) {
        for (pos in mouth.belts) {
            val belt = mouth.access.getBlockEntity(pos) as? BeltBlockEntity ?: continue
            val speed = belt.speed
            if (abs(speed) < 1.0f / 512.0f) continue
            val inv = belt.inventory ?: continue
            val length = belt.beltLength
            val items = inv.transportedItems
            val iter = items.iterator()
            while (iter.hasNext()) {
                val tis = iter.next()
                if (tis.beltPosition < length - END_SEGMENT) continue
                val stack = tis.stack
                if (stack.isEmpty) continue
                iter.remove()
                val dirVec = Vec3.atLowerCornerOf(belt.movementFacing.normal)
                val endLocal = Vec3.atCenterOf(pos)
                    .add(dirVec.scale((tis.beltPosition - length + 0.5).toDouble()))
                val spawn = mouth.access.toWorld(endLocal)
                // exact 3D transport vector, slopes included
                val chainDir = Vec3.atLowerCornerOf(belt.beltChainDirection).normalize()
                val velWorld = mouth.access.toWorldNormal(
                    chainDir.scale(belt.beltMovementSpeed.toDouble())
                )
                val entity = ItemEntity(level, spawn.x, spawn.y, spawn.z, stack.copy())
                entity.deltaMovement = velWorld
                // yaw + pitch exactly on the belt direction
                val horiz = kotlin.math.sqrt(velWorld.x * velWorld.x + velWorld.z * velWorld.z)
                entity.setYRot(
                    Math.toDegrees(kotlin.math.atan2(-velWorld.x, velWorld.z)).toFloat()
                )
                entity.setXRot(
                    Math.toDegrees(kotlin.math.atan2(-velWorld.y, horiz)).toFloat()
                )
                entity.setDefaultPickUpDelay()
                entity.hurtMarked = true
                level.addFreshEntity(entity)
            }
        }
    }
}
