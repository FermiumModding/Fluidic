package fluidic.client;

import fluidic.Fluidic;
import fluidic.handlers.ModRegistry;
import fluidic.sim.SimulatedWater;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Random;

/**
 * Splashes for entities walking through or landing in puddles too shallow to count as being in water.
 * Purely visual, so every client makes them for the entities around it.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class PuddleEffects {

    /** Entities further than this from the camera are ignored */
    private static final double RANGE = 32.0D;
    /** Distance walked through a puddle between footstep splashes */
    private static final double STEP = 1.6D;

    private static final Int2ObjectOpenHashMap<Tracked> TRACKED = new Int2ObjectOpenHashMap<>();
    private static final Random RAND = new Random();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static WorldClient trackedWorld;
    private static int tick;

    private static final class Tracked {
        double x, z;
        boolean onGround;
        double fallSpeed;
        double walked;
        int seen;
    }

    private PuddleEffects() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if(world != trackedWorld) {
            TRACKED.clear();
            trackedWorld = world;
        }
        if(world == null || view == null || mc.isGamePaused()) return;
        tick++;

        for(Entity entity : world.loadedEntityList) {
            if(entity.getDistanceSq(view) > RANGE * RANGE || entity.noClip || entity.isRiding()) continue;
            Tracked tracked = TRACKED.get(entity.getEntityId());
            if(tracked == null) {
                tracked = new Tracked();
                tracked.x = entity.posX;
                tracked.z = entity.posZ;
                tracked.onGround = entity.onGround;
                TRACKED.put(entity.getEntityId(), tracked);
            }
            update(world, entity, tracked);
            tracked.seen = tick;
        }

        if(tick % 100 == 0) {
            for(ObjectIterator<Tracked> it = TRACKED.values().iterator(); it.hasNext(); ) {
                if(tick - it.next().seen > 100) it.remove();
            }
        }
    }

    private static void update(WorldClient world, Entity entity, Tracked tracked) {
        double dx = entity.posX - tracked.x, dz = entity.posZ - tracked.z;
        tracked.x = entity.posX;
        tracked.z = entity.posZ;
        boolean landed = entity.onGround && !tracked.onGround;
        double fallSpeed = tracked.fallSpeed;
        tracked.onGround = entity.onGround;
        // Remote entities on the client have no reliable motion, use how far they dropped
        tracked.fallSpeed = entity.onGround ? 0.0D : Math.max(0.0D, entity.prevPosY - entity.posY);

        if(entity.isInWater()) {
            tracked.walked = 0.0D;
            return;
        }
        float puddle = puddleDepth(world, entity);
        if(puddle <= 0.0F) {
            tracked.walked = STEP * 0.5D;
            return;
        }

        if(landed && fallSpeed > 0.08D) {
            splash(world, entity, puddle, (float)Math.min(1.0D, fallSpeed * 0.6D + 0.15D), 1.0F + entity.width * 12.0F);
            tracked.walked = 0.0D;
            return;
        }
        if(!entity.onGround) return;
        tracked.walked += Math.sqrt(dx * dx + dz * dz);
        if(tracked.walked >= STEP) {
            tracked.walked = 0.0D;
            splash(world, entity, puddle, 0.08F, 1.0F + entity.width * 4.0F);
        }
    }

    /** Depth 0-0.5 of a shallow puddle at the entity's feet, 0 if there is none */
    private static float puddleDepth(WorldClient world, Entity entity) {
        int x = MathHelper.floor(entity.posX), y = MathHelper.floor(entity.posY + 0.01D), z = MathHelper.floor(entity.posZ);
        float depth = 0.0F;
        IBlockState state = world.getBlockState(POS.setPos(x, y, z));
        if(state.getBlock() == ModRegistry.SETTLED_WATER) {
            depth = (state.getValue(BlockFluidBase.LEVEL) + 1) / 16.0F;
        }
        int height = SimulatedWater.height(world, x, y, z);
        if(height > 0) depth = Math.max(depth, height / 255.0F);
        // Deeper water is vanilla's, entities are in it
        if(depth > 0.5F || entity.posY - y >= depth) return 0.0F;
        return depth;
    }

    private static void splash(WorldClient world, Entity entity, float depth, float volume, float particles) {
        SoundEvent sound = entity instanceof EntityPlayer ? SoundEvents.ENTITY_PLAYER_SPLASH : SoundEvents.ENTITY_GENERIC_SPLASH;
        world.playSound(entity.posX, entity.posY, entity.posZ, sound, entity.getSoundCategory(), volume,
                1.4F + (RAND.nextFloat() - RAND.nextFloat()) * 0.4F, false);
        double surface = MathHelper.floor(entity.posY + 0.01D) + depth;
        for(int i = 0; i < particles; i++) {
            double ox = (RAND.nextDouble() * 2.0D - 1.0D) * entity.width;
            double oz = (RAND.nextDouble() * 2.0D - 1.0D) * entity.width;
            world.spawnParticle(EnumParticleTypes.WATER_SPLASH, entity.posX + ox, surface, entity.posZ + oz, ox * 0.2D, 0.2D, oz * 0.2D);
        }
    }
}
