package fluidic.handlers;

import fluidic.Fluidic;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public class ClientModRegistry {

    /** Fluidic water renders as vanilla water, the fountain and drain need item models */
    @SubscribeEvent
    public static void modelRegisterEvent(ModelRegistryEvent event) {
        registerItemModel(Item.getItemFromBlock(ModRegistry.FOUNTAIN));
        registerItemModel(Item.getItemFromBlock(ModRegistry.DRAIN));
    }

    private static void registerItemModel(Item item) {
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }
}
