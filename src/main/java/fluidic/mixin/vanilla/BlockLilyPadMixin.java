package fluidic.mixin.vanilla;

import fluidic.block.LilyPads;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Lily pads float on water more than half full, simulated water too, and check now and then whether the water under
 * them went down, see {@link LilyPads}. They sit on the water's surface, which is below the top of its block unless
 * the water is full: drawn there, outlined there and stood on there.
 */
@Mixin(BlockLilyPad.class)
public abstract class BlockLilyPadMixin extends BlockBush {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fluidic_followWater(CallbackInfo ci) {
        setTickRandomly(true);
    }

    @Inject(method = "canBlockStay", at = @At("RETURN"), cancellable = true)
    private void fluidic_floatOnFluidicWater(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && pos.getY() > 0 && pos.getY() < 256 && LilyPads.floats(world, pos.down())) cir.setReturnValue(true);
    }

    /** A pad placed on water that is still flowing keeps an eye on it, see {@link LilyPads#watch} */
    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        if(!world.isRemote) LilyPads.watch(world, pos);
    }

    @Override
    public Vec3d getOffset(IBlockState state, IBlockAccess world, BlockPos pos) {
        return new Vec3d(0.0D, LilyPads.sink(world, pos), 0.0D);
    }

    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void fluidic_outlineOnSurface(IBlockState state, IBlockAccess world, BlockPos pos, CallbackInfoReturnable<AxisAlignedBB> cir) {
        double sink = LilyPads.sink(world, pos);
        if(sink != 0.0D) cir.setReturnValue(cir.getReturnValue().offset(0.0D, sink, 0.0D));
    }

    @Inject(method = "addCollisionBoxToList", at = @At("HEAD"), cancellable = true)
    private void fluidic_standOnSurface(IBlockState state, World world, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> boxes, Entity entity, boolean actualState, CallbackInfo ci) {
        if(!(entity instanceof EntityBoat)) addCollisionBoxToList(pos, entityBox, boxes, getBoundingBox(state, world, pos));
        ci.cancel();
    }
}
