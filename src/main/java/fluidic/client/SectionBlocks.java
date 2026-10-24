package fluidic.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;

/**
 * The world around a section of simulated water while its mesh is built. Every cell looks at the blocks around it and
 * its corners, the same blocks are looked up many times over, here each once per rebuild. Client thread only.
 */
final class SectionBlocks implements IBlockAccess {

    /** Blocks kept around the section, as far as the mesh looks */
    private static final int MARGIN = 2;
    private static final int SIZE = 16 + MARGIN * 2;

    private final IBlockState[] states = new IBlockState[SIZE * SIZE * SIZE];
    /** Rebuild each entry was looked up in, older ones are stale */
    private final int[] stamps = new int[SIZE * SIZE * SIZE];
    private final int[] lights = new int[SIZE * SIZE * SIZE];
    private final int[] lightStamps = new int[SIZE * SIZE * SIZE];
    private int stamp;
    private World world;
    private int x0, y0, z0;

    /** Starts the rebuild of the section with the given block origin */
    SectionBlocks begin(World world, int ox, int oy, int oz) {
        this.world = world;
        x0 = ox - MARGIN;
        y0 = oy - MARGIN;
        z0 = oz - MARGIN;
        stamp++;
        return this;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return state(pos.getX(), pos.getY(), pos.getZ(), pos);
    }

    IBlockState state(int x, int y, int z) {
        return state(x, y, z, null);
    }

    private IBlockState state(int x, int y, int z, @Nullable BlockPos pos) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if(lx < 0 || ly < 0 || lz < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) return world.getBlockState(pos != null ? pos : new BlockPos(x, y, z));
        int index = (ly * SIZE + lz) * SIZE + lx;
        if(stamps[index] != stamp) {
            stamps[index] = stamp;
            states[index] = world.getBlockState(pos != null ? pos : new BlockPos(x, y, z));
        }
        return states[index];
    }

    /** Combined light at a block, cells look at their own and the one above theirs */
    int light(int x, int y, int z) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if(lx < 0 || ly < 0 || lz < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) return world.getCombinedLight(new BlockPos(x, y, z), 0);
        int index = (ly * SIZE + lz) * SIZE + lx;
        if(lightStamps[index] != stamp) {
            lightStamps[index] = stamp;
            lights[index] = world.getCombinedLight(new BlockPos(x, y, z), 0);
        }
        return lights[index];
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return world.getTileEntity(pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return world.getCombinedLight(pos, lightValue);
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        IBlockState state = getBlockState(pos);
        return state.getBlock().isAir(state, this, pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return world.getBiome(pos);
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return world.getStrongPower(pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        return world.getWorldType();
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return world.isSideSolid(pos, side, _default);
    }
}
