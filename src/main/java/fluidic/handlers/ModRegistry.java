package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.block.BlockSettledWater;
import fluidic.item.ItemFluidicBucket;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public class ModRegistry {

        public static Fluid SETTLED_FLUID;
        public static BlockSettledWater SETTLED_WATER;
        public static ItemFluidicBucket FLUIDIC_BUCKET;

        public static void init() {
                SETTLED_FLUID = new Fluid("fluidic_water", new ResourceLocation("minecraft", "blocks/water_still"), new ResourceLocation("minecraft", "blocks/water_flow"))
                        .setUnlocalizedName(Fluidic.MODID + ".water");
                FluidRegistry.registerFluid(SETTLED_FLUID);
                SETTLED_FLUID = FluidRegistry.getFluid(SETTLED_FLUID.getName());
        }

        @SubscribeEvent
        public static void registerBlockEvent(RegistryEvent.Register<Block> event) {
                SETTLED_WATER = new BlockSettledWater(SETTLED_FLUID);
                event.getRegistry().register(SETTLED_WATER);
        }

        @SubscribeEvent
        public static void registerItemEvent(RegistryEvent.Register<Item> event) {
                FLUIDIC_BUCKET = new ItemFluidicBucket();
                event.getRegistry().register(FLUIDIC_BUCKET);
        }
}
