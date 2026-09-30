package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.AllItems
import com.simibubi.create.foundation.ponder.CreateSceneBuilder
import net.createmod.catnip.math.Pointing
import net.createmod.ponder.api.scene.SceneBuilder
import net.createmod.ponder.api.scene.SceneBuildingUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerHinge

// the roller conveyor storyboards, laid out from the schematics block by block: both bring their own 5x5
// checkerboard floor, so the ponder plate itself stays hidden and every block of the build is revealed one at
// a time. Every world change is an instruction - the storyboard body runs once at compile, and a replay resets
// the level from the schematic, so the run is formed, driven and loaded inside modifyBlockEntity each playback
object RollerPonderScene {

    @JvmStatic
    fun rollerConveyor(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.ROLLER_SCENE_ID, "The Roller Conveyor")
        scene.configureBasePlate(0, 0, 5)
        scene.showBasePlate()
        scene.idle(10)

        // the structure above its own ground: the scaffolding spine under the run, then the run, one block
        // at a time - the checkerboard the schematic also carries stays with the base plate, shown Create's way
        for (z in 0..4) {
            scene.world().showSection(util.select().position(2, 1, z), Direction.DOWN)
            scene.idle(2)
        }
        for (z in 0..3) {
            scene.world().showSection(util.select().position(2, 2, z), Direction.DOWN)
            scene.idle(3)
        }
        scene.world().showSection(util.select().position(2, 2, 4), Direction.DOWN)
        scene.idle(3)
        // the feeder: a funnel over the run and a chest at its end
        scene.world().showSection(util.select().position(2, 3, 3), Direction.DOWN)
        scene.idle(3)
        scene.world().showSection(util.select().position(2, 3, 4), Direction.DOWN)
        scene.idle(10)

        val run = PonderRollerHelper.findRun(scene.scene.world) ?: return
        seed(scene, run, 32f)
        scene.idle(10)

        val mid = run.controller.relative(run.facing, run.length / 2)
        scene.overlay().showText(80)
            .text("Roller conveyors carry loads along a run of rollers")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(mid))
        scene.idle(90)

        scene.overlay().showControls(util.vector().topOf(2, 4, 3), Pointing.DOWN, 40)
        scene.overlay().showText(80)
            .text("Funnels and chests feed the run on their own")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 4, 3))
        scene.idle(90)

        dropLoad(scene, run, ItemStack(Items.IRON_INGOT), 0.5f)
        scene.addInstruction(PonderRollerLoadInstruction(run.controller, 130))
        scene.overlay().showText(80)
            .text("Loads ride the rollers and leave the run at its end")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(run.end))
        scene.idle(140)
    }

    @JvmStatic
    fun rollerHinge(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.ROLLER_HINGE_SCENE_ID, "The Roller Hinge")
        scene.configureBasePlate(0, 0, 5)
        scene.showBasePlate()
        scene.idle(10)

        // the drive above the base plate: girders, its shaft line, the cogs that turn it, one block at a time
        for (pos in listOf(BlockPos(1, 1, 4), BlockPos(3, 1, 4))) {
            scene.world().showSection(util.select().position(pos), Direction.DOWN)
            scene.idle(3)
        }
        for (pos in listOf(
            BlockPos(0, 2, 5), BlockPos(1, 2, 4), BlockPos(1, 2, 5), BlockPos(2, 2, 5),
            BlockPos(3, 2, 4), BlockPos(3, 2, 5), BlockPos(4, 2, 5)
        )) {
            scene.world().showSection(util.select().position(pos), Direction.DOWN)
            scene.idle(3)
        }
        for (pos in listOf(
            BlockPos(0, 3, 4), BlockPos(1, 3, 4), BlockPos(2, 3, 4), BlockPos(3, 3, 4), BlockPos(4, 3, 4)
        )) {
            scene.world().showSection(util.select().position(pos), Direction.DOWN)
            scene.idle(3)
        }
        scene.idle(10)

        val shaft = BlockPos(2, 3, 4)
        val facing = runFacingBeside(scene.scene.world, shaft)
        val state = deckState(facing)
        for (i in 1..3) {
            scene.world().setBlock(shaft.relative(facing, i), state, true)
            scene.world().showSection(util.select().position(shaft.relative(facing, i)), Direction.DOWN)
            scene.idle(4)
        }
        val run = PonderRollerHelper.Run(shaft.relative(facing), facing, 3)
        seed(scene, run, 32f)
        scene.idle(10)

        scene.overlay().showText(80)
            .text("Mount a hinge shaft on the end of a run, insert a shaft, and the run can rotate")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(shaft))
        scene.idle(90)

        scene.overlay().showControls(util.vector().topOf(shaft), Pointing.DOWN, 40)
            .withItem(ItemStack(AllItems.WRENCH.get()))
        scene.overlay().showText(80)
            .text("A wrench on the shaft flips its latch")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(shaft))
        scene.idle(90)

        scene.overlay().showText(80)
            .text("Unlatched, a tilted run lets its loads slide on their own")
            .attachKeyFrame()
            .placeNearTarget()
            .pointAt(util.vector().topOf(run.controller.relative(facing)))
        scene.idle(90)
    }

    // the run is formed and driven inside one block entity instruction, so every replay seeds it again
    private fun seed(scene: CreateSceneBuilder, run: PonderRollerHelper.Run, rpm: Float) {
        scene.world().modifyBlockEntity(run.controller, RollerConveyorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            val level = be.level ?: return@modifyBlockEntity
            PonderRollerHelper.formRun(level, run)
            PonderRollerHelper.seedSpeed(level, run.controller, rpm)
        }
    }

    private fun dropLoad(scene: CreateSceneBuilder, run: PonderRollerHelper.Run, stack: ItemStack, position: Float) {
        scene.world().modifyBlockEntity(run.controller, RollerConveyorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            val level = be.level ?: return@modifyBlockEntity
            PonderRollerHelper.dropLoad(level, run.controller, stack, position)
        }
    }

    // the run's facing that fits beside a world shaft: perpendicular to the shaft's axis, into open cells
    private fun runFacingBeside(level: net.minecraft.world.level.Level, shaft: BlockPos): Direction {
        val shaftAxis = level.getBlockState(shaft).getValue(BlockStateProperties.AXIS)
        val candidates = Direction.Plane.HORIZONTAL.filter { it.axis != shaftAxis }
        for (facing in candidates) {
            var fits = true
            for (i in 1..3) if (!level.getBlockState(shaft.relative(facing, i)).isAir) fits = false
            if (fits) return facing
        }
        return candidates.first()
    }

    private fun deckState(facing: Direction): BlockState =
        ModBlocks.ROLLER_CONVEYOR.defaultBlockState()
            .setValue(RollerConveyorBlock.HORIZONTAL_FACING, facing)
            .setValue(RollerConveyorBlock.HINGE, RollerHinge.Side.NONE)
            .setValue(BlockStateProperties.WATERLOGGED, false)
}
