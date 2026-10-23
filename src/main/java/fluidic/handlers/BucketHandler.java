package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.block.FluidicWater;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * An empty bucket scoops up shallow water too. Vanilla only takes full water blocks, so puddles and the thin top of a
 * pool could never be picked up. Aimed at partly filled water, the bucket gathers a block of water from the surface
 * around it, an eighth at a time from the highest blocks so the surface goes down evenly.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public class BucketHandler {

    /** Blocks out from the one aimed at that water is gathered from */
    private static final int REACH = 4;

    @SubscribeEvent
    public static void onFillBucket(FillBucketEvent event) {
        if(event.getResult() != Event.Result.DEFAULT || event.getEmptyBucket().getItem() != Items.BUCKET) return;
        RayTraceResult target = event.getTarget();
        if(target == null || target.typeOfHit != RayTraceResult.Type.BLOCK) return;
        World world = event.getWorld();
        EntityPlayer player = event.getEntityPlayer();
        BlockPos pos = target.getBlockPos();
        IBlockState state = world.getBlockState(pos);
        // Full blocks are scooped up by vanilla
        if(!FluidicWater.isWater(state) || FluidicWater.level(state) >= Water.LEVELS) return;
        if(!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos.offset(target.sideHit), target.sideHit, event.getEmptyBucket())) return;

        List<BlockPos> surface = surfaceAround(world, pos);
        int[] levels = new int[surface.size()];
        int total = 0;
        for(int n = 0; n < levels.length; n++) {
            levels[n] = FluidicWater.level(world.getBlockState(surface.get(n)));
            total += levels[n];
        }
        if(total < Water.LEVELS) return;

        // The client only predicts the bucket filling, the server takes the water
        if(!world.isRemote) {
            int[] taken = levels.clone();
            for(int n = 0; n < Water.LEVELS; n++) {
                int highest = 0;
                for(int k = 1; k < taken.length; k++) if(taken[k] > taken[highest]) highest = k;
                taken[highest]--;
            }
            WaterSimulation simulation = SimManager.get(world);
            for(int n = 0; n < taken.length; n++) {
                if(taken[n] == levels[n]) continue;
                BlockPos cell = surface.get(n);
                IBlockState lowered = taken[n] == 0 ? Blocks.AIR.getDefaultState() : FluidicWater.state(taken[n]);
                FluidicWater.placing = true;
                world.setBlockState(cell, lowered, 3);
                FluidicWater.placing = false;
                if(simulation != null) simulation.setTerrain(cell, Terrain.classify(world, cell, lowered));
            }
        }
        player.playSound(SoundEvents.ITEM_BUCKET_FILL, 1.0F, 1.0F);
        event.setFilledBucket(new ItemStack(Items.WATER_BUCKET));
        event.setResult(Event.Result.ALLOW);
    }

    /** Water blocks of the surface the aimed at block belongs to, connected at its height, the aimed at one first */
    private static List<BlockPos> surfaceAround(World world, BlockPos pos) {
        List<BlockPos> cells = new ArrayList<>();
        cells.add(pos);
        for(int n = 0; n < cells.size(); n++) {
            BlockPos cell = cells.get(n);
            for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos next = cell.offset(facing);
                if(Math.abs(next.getX() - pos.getX()) > REACH || Math.abs(next.getZ() - pos.getZ()) > REACH || cells.contains(next)) continue;
                if(!world.isBlockLoaded(next) || !FluidicWater.isWater(world.getBlockState(next))) continue;
                // Only the surface, water under more water is left alone
                if(FluidicWater.isWater(world.getBlockState(next.up()))) continue;
                cells.add(next);
            }
        }
        return cells;
    }
}
