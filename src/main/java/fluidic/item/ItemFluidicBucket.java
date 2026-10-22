package fluidic.item;

import fluidic.Fluidic;
import fluidic.handlers.ModRegistry;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidBase;

import javax.annotation.Nonnull;

/**
 * A bucket holding one block of Fluidic water, filled by scooping up a full settled water block with an empty
 * bucket. Pouring it adds the block of water back to the simulation and leaves an empty bucket.
 */
public class ItemFluidicBucket extends Item {

    public ItemFluidicBucket() {
        setRegistryName(Fluidic.MODID, "fluidic_bucket");
        setTranslationKey(Fluidic.MODID + ".fluidic_bucket");
        setMaxStackSize(1);
        setContainerItem(Items.BUCKET);
        setCreativeTab(CreativeTabs.MISC);
    }

    @Override
    @Nonnull
    public ActionResult<ItemStack> onItemRightClick(@Nonnull World world, @Nonnull EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        RayTraceResult hit = rayTrace(world, player, false);
        if(hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return new ActionResult<>(EnumActionResult.PASS, stack);

        BlockPos pos = hit.getBlockPos();
        // Pour into the block itself when water can be there, like tall grass, otherwise in front of it
        BlockPos target = Terrain.isOpen(world.getBlockState(pos)) ? pos : pos.offset(hit.sideHit);
        if(!world.isBlockModifiable(player, target) || !player.canPlayerEdit(target, hit.sideHit, stack)) {
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }

        if(!world.isRemote) {
            WaterSimulation simulation = SimManager.get(world);
            if(simulation == null) {
                player.sendStatusMessage(new TextComponentTranslation("item.fluidic.fluidic_bucket.disabled"), true);
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            pour(world, simulation, target);
        }
        world.playSound(player, target, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
        return new ActionResult<>(EnumActionResult.SUCCESS, player.capabilities.isCreativeMode ? stack : new ItemStack(Items.BUCKET));
    }

    /** Fills settled water at the target up first, so pouring into a pool does not wake it, the rest flows on top */
    private static void pour(World world, WaterSimulation simulation, BlockPos target) {
        int amount = Water.FULL;
        IBlockState state = world.getBlockState(target);
        if(state.getBlock() == ModRegistry.SETTLED_WATER) {
            int level = state.getValue(BlockFluidBase.LEVEL) + 1;
            int raised = Math.min(Water.LEVELS, level + amount / Water.UNIT);
            if(raised > level) {
                IBlockState filled = state.withProperty(BlockFluidBase.LEVEL, raised - 1);
                world.setBlockState(target, filled, 2);
                simulation.setTerrain(target, Terrain.classify(filled));
                amount -= (raised - level) * Water.UNIT;
            }
            target = target.up();
        }
        if(amount > 0) simulation.addWater(target, amount);
    }
}
