package fluidic.sim;

import fluidic.block.BlockFluidicWater;
import fluidic.block.FluidicWater;
import fluidic.block.LilyPads;
import fluidic.compat.FluidloggedCompat;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Keeps the simulation's terrain snapshot in sync with block changes in the world.
 */
final class WaterWorldListener implements IWorldEventListener {

    private final WaterSimulation simulation;

    WaterWorldListener(WaterSimulation simulation) {
        this.simulation = simulation;
    }

    @Override
    public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
        if(oldState == newState) return;
        Reservoirs.blockChanged(world, pos, oldState, newState);
        if(FluidicWater.isWater(oldState) || FluidicWater.isWater(newState)) LilyPads.waterChanged(world, pos);
        // World generation places blocks by the thousand, mostly where nothing is simulated. Simulated sections it
        // reaches catch up with their next terrain refresh.
        if(FluidicWater.generating > 0) return;
        if(!FluidicWater.placing && FluidicWater.isWater(oldState) && !FluidicWater.isWater(newState)) displaced(world, pos, oldState, newState);
        // Thin blocks never join water, air or plants, water settling and drying beside them changes nothing
        if(oldState.getMaterial().blocksMovement() || newState.getMaterial().blocksMovement()) thinBlocksBeside(world, pos);
        if(Terrain.isDoor(oldState) || Terrain.isDoor(newState)) {
            // A door opening only changes its lower half, the upper half turns with it. The old state no longer tells
            // the door's old side, the world holds the new one already, so both halves are set again.
            simulation.setTerrain(pos, Terrain.classify(world, pos, newState));
            BlockPos other = newState.getBlock() instanceof BlockDoor && newState.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER
                    || oldState.getBlock() instanceof BlockDoor && oldState.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER ? pos.down() : pos.up();
            if(world.isBlockLoaded(other)) simulation.setTerrain(other, Terrain.classify(world, other, world.getBlockState(other)));
            // Doors open and close without updating their neighbours, settled water beside them would not notice
            // that it can flow through now
            for(BlockPos half : new BlockPos[] {pos, other}) {
                for(EnumFacing facing : EnumFacing.VALUES) {
                    BlockPos beside = half.offset(facing);
                    if(!world.isBlockLoaded(beside)) continue;
                    IBlockState water = world.getBlockState(beside);
                    if(FluidicWater.isWater(water)) BlockFluidicWater.neighborChanged(world, beside, water);
                }
            }
            return;
        }
        byte code = Terrain.classify(world, pos, newState);
        if(code != Terrain.classify(world, pos, oldState)) simulation.setTerrain(pos, code);
    }

    /**
     * Thin blocks like panes join the blocks beside them, and whether water passes through them depends on how many
     * they join, see Terrain.passesWater. A block changing beside one changes that without changing the thin block's
     * state, so it is set again, and settled water around it checks whether it can flow through now.
     */
    private void thinBlocksBeside(World world, BlockPos pos) {
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos thin = pos.offset(facing);
            if(!world.isBlockLoaded(thin)) continue;
            IBlockState state = world.getBlockState(thin);
            if(!Terrain.isThin(state)) continue;
            simulation.setTerrain(thin, Terrain.classify(world, thin, state));
            for(EnumFacing side : EnumFacing.HORIZONTALS) {
                BlockPos beside = thin.offset(side);
                if(beside.equals(pos) || !world.isBlockLoaded(beside)) continue;
                IBlockState water = world.getBlockState(beside);
                if(FluidicWater.isWater(water)) BlockFluidicWater.neighborChanged(world, beside, water);
            }
        }
    }

    /**
     * A block took the place of a water block: sand fell in, a piston pushed in, a player built into a pool. Water is
     * finite, so it moves instead of vanishing: into the block when water can be in it, like stairs, or on top of the
     * water above it. Water taken out on purpose, leaving air, is gone. Ice keeps the water, and the sea is endless.
     */
    private void displaced(World world, BlockPos pos, IBlockState water, IBlockState block) {
        Material material = block.getMaterial();
        if(material == Material.AIR || material == Material.ICE || material == Material.PACKED_ICE) return;
        // Fluidlogged API kept it inside the new block, as it does for stairs and the like
        if(FluidloggedCompat.waterLevel(world, pos) > 0) return;
        if(FluidicWater.isReservoir(world, pos, water)) return;
        int amount = FluidicWater.level(water) * Water.UNIT;
        if(WaterSection.holds(Terrain.classify(world, pos, block))) {
            simulation.addWater(pos, amount);
            return;
        }
        BlockPos top = pos;
        while(top.getY() < 255 && world.isBlockLoaded(top.up()) && FluidicWater.isWater(world.getBlockState(top.up()))) top = top.up();
        simulation.addWater(top.up(), amount);
    }

    @Override
    public void notifyLightSet(BlockPos pos) {}

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent sound, SoundCategory category, double x, double y, double z, float volume, float pitch) {}

    @Override
    public void playRecord(SoundEvent sound, BlockPos pos) {}

    @Override
    public void spawnParticle(int particleID, boolean ignoreRange, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {}

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {}

    @Override
    public void onEntityAdded(Entity entity) {}

    @Override
    public void onEntityRemoved(Entity entity) {}

    @Override
    public void broadcastSound(int soundID, BlockPos pos, int data) {}

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {}

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
}
