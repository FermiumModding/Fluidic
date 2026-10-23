package fluidic.mixin.vanilla;

import fluidic.block.LilyPads;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A lily pad that lost its water moves with it instead of breaking, see {@link LilyPads#follow} */
@Mixin(BlockBush.class)
public abstract class BlockBushMixin {

    @Inject(method = "checkAndDropBlock", at = @At("HEAD"), cancellable = true)
    private void fluidic_lilyPadFollowsWater(World world, BlockPos pos, IBlockState state, CallbackInfo ci) {
        if(!((Object)this instanceof BlockLilyPad) || world.isRemote) return;
        if(!((BlockBush)(Object)this).canBlockStay(world, pos, state) && LilyPads.follow(world, pos, state)) ci.cancel();
    }
}
