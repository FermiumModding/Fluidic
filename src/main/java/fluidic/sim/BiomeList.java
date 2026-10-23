package fluidic.sim;

import fluidic.Fluidic;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Biomes listed in a config option. Entries are biome ids like minecraft:swampland, biome dictionary tags like OCEAN,
 * or * for every biome. An entry starting with ! leaves its biomes out again, whatever order the entries are in.
 */
public final class BiomeList {

    private BiomeList() {}

    public static Set<Biome> parse(String option, String[] entries) {
        Set<Biome> included = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Biome> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
        for(String raw : entries) {
            String entry = raw.trim();
            boolean exclude = entry.startsWith("!");
            if(exclude) entry = entry.substring(1).trim();
            if(entry.isEmpty()) continue;
            Collection<Biome> biomes = resolve(option, entry);
            if(biomes != null) (exclude ? excluded : included).addAll(biomes);
        }
        included.removeAll(excluded);
        return included;
    }

    private static Collection<Biome> resolve(String option, String entry) {
        if(entry.equals("*")) return ForgeRegistries.BIOMES.getValuesCollection();
        if(entry.indexOf(':') >= 0) {
            Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(entry));
            if(biome != null) return Collections.singleton(biome);
            Fluidic.LOGGER.warn(option + ": no biome " + entry);
            return null;
        }
        for(BiomeDictionary.Type type : BiomeDictionary.Type.getAll()) {
            if(type.getName().equalsIgnoreCase(entry)) return BiomeDictionary.getBiomes(type);
        }
        Fluidic.LOGGER.warn(option + ": no biome tag " + entry + ", biome ids need their mod id, like minecraft:" + entry.toLowerCase());
        return null;
    }
}
