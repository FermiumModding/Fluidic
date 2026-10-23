package fluidic.block;

import fluidic.Fluidic;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;

/**
 * A builder's block that takes water away like a sponge that never fills: water touching it on any side flows into
 * it and is gone, and pools drain into it. The simulation does the work, see WaterSection.DRAIN.
 */
public class BlockDrain extends Block {

    public BlockDrain() {
        super(Material.ROCK);
        setHardness(3.5F);
        setSoundType(SoundType.STONE);
        setCreativeTab(CreativeTabs.REDSTONE);
        setTranslationKey(Fluidic.MODID + ".drain");
        setRegistryName(Fluidic.MODID, "drain");
    }
}
