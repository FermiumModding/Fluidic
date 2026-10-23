package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.block.BlockFluidicFlowingWater;
import fluidic.block.BlockFluidicWater;
import fluidic.block.BlockDrain;
import fluidic.block.BlockFountain;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBucket;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;

@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public class ModRegistry {

        public static BlockFountain FOUNTAIN;
        public static BlockDrain DRAIN;
        private static BlockFluidicFlowingWater flowingWater;

        /**
         * Vanilla water is replaced by Fluidic water under the same names, so everything expecting water, vanilla or
         * modded, finds it. Forge updates the Blocks.WATER and Blocks.FLOWING_WATER fields to the replacements.
         */
        @SubscribeEvent
        public static void registerBlockEvent(RegistryEvent.Register<Block> event) {
                BlockFluidicWater water = new BlockFluidicWater();
                event.getRegistry().register(water);
                flowingWater = new BlockFluidicFlowingWater();
                event.getRegistry().register(flowingWater);
                FOUNTAIN = new BlockFountain();
                event.getRegistry().register(FOUNTAIN);
                DRAIN = new BlockDrain();
                event.getRegistry().register(DRAIN);
                bindForgeWater(water);
        }

        /** Forge's water fluid was bound to the vanilla block before it was replaced */
        private static void bindForgeWater(Block water) {
                try {
                        Field block = Fluid.class.getDeclaredField("block");
                        block.setAccessible(true);
                        block.set(FluidRegistry.WATER, water);
                }
                catch(ReflectiveOperationException ex) {
                        Fluidic.LOGGER.error("Could not bind Forge's water fluid to Fluidic water", ex);
                }
        }

        /** The vanilla water bucket kept the flowing water block it was created with, before that was replaced */
        @SubscribeEvent
        public static void registerItemEvent(RegistryEvent.Register<Item> event) {
                ReflectionHelper.setPrivateValue(ItemBucket.class, (ItemBucket)Items.WATER_BUCKET, flowingWater, "containedBlock", "field_77876_a");
                event.getRegistry().register(new ItemBlock(FOUNTAIN).setRegistryName(FOUNTAIN.getRegistryName()));
                event.getRegistry().register(new ItemBlock(DRAIN).setRegistryName(DRAIN.getRegistryName()));
        }
}
