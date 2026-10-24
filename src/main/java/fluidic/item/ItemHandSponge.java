package fluidic.item;

import fluidic.Fluidic;
import fluidic.block.FluidicWater;
import fluidic.handlers.BucketHandler;
import fluidic.sim.SimManager;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A sponge held in the hand that soaks up puddles and the surface of pools until it holds a block of water, then it is
 * soaked. A dry sponge keeps the eighths it took so far, it takes them like a bucket does, see BucketHandler, from the
 * highest blocks of the surface first. Crafted with an empty bucket, a soaked sponge is wrung out into it and is dry again.
 */
public class ItemHandSponge extends Item {

    public static final int DRY = 0, SOAKED = 1;
    /** Eighths of a block a dry sponge holds, in its tag */
    private static final String WATER = "Water";
    private static final int WATER_COLOR = 0x3F76E4;

    public ItemHandSponge() {
        setHasSubtypes(true);
        setMaxStackSize(16);
        setCreativeTab(CreativeTabs.TOOLS);
        setTranslationKey(Fluidic.MODID + ".sponge");
        setRegistryName(Fluidic.MODID, "sponge");
    }

    @Override
    @Nonnull
    public String getTranslationKey(ItemStack stack) {
        return super.getTranslationKey() + (stack.getMetadata() == SOAKED ? "_soaked" : "");
    }

    @Override
    public void getSubItems(@Nonnull CreativeTabs tab, @Nonnull NonNullList<ItemStack> items) {
        if(!isInCreativeTab(tab)) return;
        items.add(new ItemStack(this, 1, DRY));
        items.add(new ItemStack(this, 1, SOAKED));
    }

    @Override
    @Nonnull
    public ActionResult<ItemStack> onItemRightClick(@Nonnull World world, @Nonnull EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if(stack.getMetadata() != DRY) return new ActionResult<>(EnumActionResult.PASS, stack);
        RayTraceResult target = rayTrace(world, player, true);
        //noinspection ConstantConditions
        if(target == null || target.typeOfHit != RayTraceResult.Type.BLOCK) return new ActionResult<>(EnumActionResult.PASS, stack);
        BlockPos pos = target.getBlockPos();
        if(!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos.offset(target.sideHit), target.sideHit, stack)) {
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        int room = Water.LEVELS - water(stack);
        int taken;
        IBlockState state = world.getBlockState(pos);
        if(!FluidicWater.isFinite(world)) {
            // Vanilla water comes in whole sources, a source block soaks the sponge
            taken = FluidicWater.isWater(state) && state.getValue(BlockLiquid.LEVEL) == 0 ? room : 0;
            if(taken > 0 && !world.isRemote) world.setBlockToAir(pos);
        }
        // Water still flowing is no block to aim at, the sponge went through it to the block behind
        else if(!FluidicWater.isWater(state)) taken = soakSimulated(world, pos.offset(target.sideHit), room);
        else taken = soakSettled(world, pos, room);
        if(taken == 0) return new ActionResult<>(EnumActionResult.PASS, stack);

        player.playSound(SoundEvents.ITEM_BUCKET_FILL, 0.6F, 1.5F);
        // The client only predicts the sponge soaking, the server's stack replaces it
        if(world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        ItemStack soaked = withWater(water(stack) + taken);
        if(stack.getCount() == 1) return new ActionResult<>(EnumActionResult.SUCCESS, soaked);
        stack.shrink(1);
        if(!player.inventory.addItemStackToInventory(soaked)) player.dropItem(soaked, false);
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    /** Takes up to the given eighths from the settled water surface around a water block, returns how many it took */
    private static int soakSettled(World world, BlockPos pos, int room) {
        List<BlockPos> surface = BucketHandler.surfaceAround(world, pos);
        int[] levels = new int[surface.size()];
        int total = 0;
        for(int n = 0; n < levels.length; n++) {
            levels[n] = FluidicWater.level(world.getBlockState(surface.get(n)));
            total += levels[n];
        }
        int taken = Math.min(room, total);
        if(taken > 0 && !world.isRemote) BucketHandler.takeLevels(world, surface, levels, taken);
        return taken;
    }

    /**
     * Takes up to the given eighths of simulated water around the block the sponge went through, whole eighths only so
     * the sponge holds exactly what the water lost. Judged from the heights last sent to players, like buckets.
     */
    private static int soakSimulated(World world, BlockPos pos, int room) {
        WaterSimulation simulation = SimManager.get(world);
        if(!world.isRemote && simulation == null) return 0;
        if(BucketHandler.scoopable(world, simulation, pos.getX(), pos.getY(), pos.getZ()) == 0) return 0;
        int total = 0;
        int reach = BucketHandler.REACH;
        for(int dx = -reach; dx <= reach; dx++) {
            for(int dz = -reach; dz <= reach; dz++) total += BucketHandler.scoopable(world, simulation, pos.getX() + dx, pos.getY(), pos.getZ() + dz);
        }
        int taken = Math.min(room, total * Water.LEVELS / 255);
        if(taken > 0 && simulation != null) simulation.takeWater(pos, taken * Water.UNIT, reach);
        return taken;
    }

    /** Eighths of a block of water a dry sponge holds */
    public static int water(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0 : Math.max(0, Math.min(Water.LEVELS, tag.getInteger(WATER)));
    }

    /** A sponge holding the given eighths, soaked once it holds a whole block */
    private ItemStack withWater(int eighths) {
        if(eighths >= Water.LEVELS) return new ItemStack(this, 1, SOAKED);
        ItemStack stack = new ItemStack(this, 1, DRY);
        if(eighths > 0) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger(WATER, eighths);
            stack.setTagCompound(tag);
        }
        return stack;
    }

    @Override
    public boolean showDurabilityBar(ItemStack stack) {
        return stack.getMetadata() == DRY && water(stack) > 0;
    }

    @Override
    public double getDurabilityForDisplay(ItemStack stack) {
        return 1.0D - (double)water(stack) / Water.LEVELS;
    }

    @Override
    public int getRGBDurabilityForDisplay(@Nonnull ItemStack stack) {
        return WATER_COLOR;
    }

    /** Wrung out into a bucket, a soaked sponge stays in the crafting grid dry */
    @Override
    public boolean hasContainerItem(@Nonnull ItemStack stack) {
        return stack.getMetadata() == SOAKED;
    }

    @Override
    @Nonnull
    public ItemStack getContainerItem(@Nonnull ItemStack stack) {
        return stack.getMetadata() == SOAKED ? new ItemStack(this, 1, DRY) : ItemStack.EMPTY;
    }
}
