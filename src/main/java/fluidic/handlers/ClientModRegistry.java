package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.client.SettledWaterModel;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraftforge.client.event.ColorHandlerEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nonnull;

@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public class ClientModRegistry {

    private static final ModelResourceLocation SETTLED_WATER_MODEL = new ModelResourceLocation(Fluidic.MODID + ":settled_water", "fluid");

    @SubscribeEvent
    public static void modelRegisterEvent(ModelRegistryEvent event) {
        // Every level renders through Forge's fluid model, which reads the level itself
        ModelLoader.setCustomStateMapper(ModRegistry.SETTLED_WATER, new StateMapperBase() {
            @Override
            @Nonnull
            protected ModelResourceLocation getModelResourceLocation(@Nonnull IBlockState state) {
                return SETTLED_WATER_MODEL;
            }
        });
        ModelLoader.setCustomModelResourceLocation(ModRegistry.FLUIDIC_BUCKET, 0, new ModelResourceLocation(ModRegistry.FLUIDIC_BUCKET.getRegistryName(), "inventory"));
    }

    @SubscribeEvent
    public static void modelBakeEvent(ModelBakeEvent event) {
        IBakedModel model = event.getModelRegistry().getObject(SETTLED_WATER_MODEL);
        if(model != null) event.getModelRegistry().putObject(SETTLED_WATER_MODEL, new SettledWaterModel(model));
    }

    @SubscribeEvent
    public static void blockColorEvent(ColorHandlerEvent.Block event) {
        event.getBlockColors().registerBlockColorHandler((state, world, pos, tintIndex) ->
                world != null && pos != null ? BiomeColorHelper.getWaterColorAtPos(world, pos) : -1, ModRegistry.SETTLED_WATER);
    }
}
