package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimManager;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemBucket;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A water bucket emptied into water adds a block of water to it. Vanilla would place a water block over the water
 * that is there, which changes nothing once the block is full, and the bucket's water would be lost. A full bucket
 * aims through water at the ground below, so the water goes on top of the water above that.
 */
@Mixin(ItemBucket.class)
public abstract class ItemBucketMixin {

    @Shadow
    @Final
    private Block containedBlock;

    @Inject(method = "tryPlaceContainedLiquid", at = @At("HEAD"), cancellable = true)
    private void fluidic_pourIntoWater(EntityPlayer player, World world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if(containedBlock.getDefaultState().getMaterial() != Material.WATER || !FluidicWater.isWater(world.getBlockState(pos))) return;
        if(world.isRemote) {
            // The server plays the sound to everyone but the player, whose own client plays it, as vanilla does
            world.playSound(player, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
            cir.setReturnValue(true);
            return;
        }
        WaterSimulation simulation = SimManager.get(world);
        if(simulation == null) return;
        BlockPos top = pos;
        while(top.getY() < 255 && FluidicWater.isWater(world.getBlockState(top.up()))) top = top.up();
        simulation.addWater(top.up(), Water.FULL);
        world.playSound(player, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
        cir.setReturnValue(true);
    }
}
