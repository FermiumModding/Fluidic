package fluidic.mixin.vanilla;

import fluidic.sim.SimulatedWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forge picks the underwater fog colour by the block at the camera, looked up again instead of through
 * ActiveRenderInfo, see ActiveRenderInfoMixin. In water that is still being simulated it reads as water there too,
 * or the fog takes the colour of the sky.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Redirect(method = "updateFogColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"), require = 0)
    private IBlockState fluidic_simulatedWaterFogColor(WorldClient world, BlockPos pos, float partialTicks) {
        IBlockState state = world.getBlockState(pos);
        if(state.getMaterial().isLiquid()) return state;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if(view == null) return state;
        Vec3d eyes = ActiveRenderInfo.projectViewFromEntity(view, partialTicks);
        return SimulatedWater.eyesSubmerged(world, eyes.x, eyes.y, eyes.z) ? Blocks.WATER.getDefaultState() : state;
    }
}
