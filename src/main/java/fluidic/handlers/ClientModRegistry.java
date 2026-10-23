package fluidic.handlers;

import fluidic.Fluidic;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nonnull;

@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public class ClientModRegistry {

    private static final ModelResourceLocation LEGACY_SETTLED_WATER_MODEL = new ModelResourceLocation(Fluidic.MODID + ":settled_water", "fluid");

    /** Fluidic water renders as vanilla water, the legacy block needs a model until it is converted, and the fountain
     * and drain need item models */
    @SubscribeEvent
    public static void modelRegisterEvent(ModelRegistryEvent event) {
        ModelLoader.setCustomStateMapper(ModRegistry.LEGACY_SETTLED_WATER, new StateMapperBase() {
            @Override
            @Nonnull
            protected ModelResourceLocation getModelResourceLocation(@Nonnull IBlockState state) {
                return LEGACY_SETTLED_WATER_MODEL;
            }
        });
        registerItemModel(Item.getItemFromBlock(ModRegistry.FOUNTAIN));
        registerItemModel(Item.getItemFromBlock(ModRegistry.DRAIN));
    }

    private static void registerItemModel(Item item) {
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }
}
