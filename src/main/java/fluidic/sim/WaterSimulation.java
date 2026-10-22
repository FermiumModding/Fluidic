package fluidic.sim;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.FluidicWater;
import fluidic.network.MessageWaterSection;
import fluidic.network.PacketHandler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * Water simulation of one dimension.
 *
 * The grid lives on a dedicated simulation thread and is stepped at most once per server tick. When a step
 * takes longer than a tick the simulation slows down instead of the server. The server thread and the
 * simulation only talk through two queues: commands (block changes, rain, chunk loads) go in, and
 * {@link StepResult}s (settled water to place, terrain snapshots to take, packets to send) come out.
 */
public final class WaterSimulation {

    /** Below this many busy sections stepping in parallel costs more than it saves */
    private static final int PARALLEL_THRESHOLD = 8;
    /** Steps an empty section is kept around before it is dropped */
    private static final int EVICT_STEPS = 200;
    /**
     * Steps between terrain snapshot refreshes of a section. Vanilla only reports block changes in chunks that
     * a player has ticked, so changes elsewhere are caught by refreshing.
     */
    private static final int REFRESH_STEPS = 100;
    private static final int REFRESHES_PER_STEP = 8;
    /** Steps between evaporation passes over dormant shallow water */
    private static final int EVAPORATE_STEPS = 200;
    /** Steps between adding rain held back from water that keeps moving */
    private static final int RAIN_RELEASE_STEPS = 200;
    /** Largest pool surface levelled in one go, bounds the cost of settling a section of a huge lake */
    private static final int MAX_POOL = 16384;
    /**
     * Times a settling section waits for the sections its pool surfaces reach. Puddles on flat ground join into one
     * surface that can run on for many chunks, so after this it levels the part that is known instead.
     */
    private static final int MAX_LEVEL_WAITS = 2;
    private static final String SAVE_FILE = "fluidic_water.dat";

    // ---------------------------------------------------------------- server thread state

    private final WorldServer world;
    private final int dimension;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Consumer<WaterSimulation>> commands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<StepResult> results = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean stepping = new AtomicBoolean();
    private final List<StepResult.Payload> latePayloads = new ArrayList<>();
    /**
     * Woken blocks, removed one step after their water went into the simulation so clients get the simulated water
     * before the block disappears. Otherwise the pool shows a hole down to its floor for a moment.
     */
    private final LongArrayList removals = new LongArrayList();
    private final LongOpenHashSet removing = new LongOpenHashSet();

    // ---------------------------------------------------------------- simulation thread state

    private final Long2ObjectOpenHashMap<WaterSection> sections = new Long2ObjectOpenHashMap<>();
    /** Water heights last sent to clients, 255 = full block, for entity physics on the server thread */
    private final ConcurrentHashMap<Long, byte[]> published = new ConcurrentHashMap<>();
    private final ArrayList<WaterSection> stepList = new ArrayList<>();
    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    private final byte[] deflateBuffer = new byte[4096];
    private final IntArrayList overflow = new IntArrayList();
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private final LongArrayList poolCells = new LongArrayList();
    /** Keys of sections a pool surface reached that are no longer simulated, requested after iterating */
    private final LongArrayList poolMissing = new LongArrayList();
    /** Block position / mass pairs */
    private final LongArrayList overflowCells = new LongArrayList();
    private StepResult result = new StepResult();
    private int stepCount;
    private final Random random = new Random();
    /** Daytime without rain, written by the server thread every tick */
    private volatile boolean sunny;
    /** Raining or thundering, written by the server thread every tick */
    private volatile boolean raining;

    // Statistics written by the simulation thread
    private volatile int statSections;
    private volatile int statActive;
    private volatile int statCells;
    private volatile int statWaiting;
    private volatile int statFlowing;
    private volatile long statStepNanos;
    private volatile int statSteps;

    WaterSimulation(WorldServer world) {
        this.world = world;
        this.dimension = world.provider.getDimension();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Fluidic Simulation DIM" + dimension);
            thread.setDaemon(true);
            return thread;
        });
        NBTTagCompound saved = readSaveFile();
        if(saved != null) enqueue(sim -> sim.restore(saved));
    }

    // ================================================================ server thread API

    /** Height 0-255 of simulated water at a block as last sent to clients, safe from any thread */
    public int height(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        byte[] heights = published.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return heights == null ? 0 : heights[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
    }

    public void enqueue(Consumer<WaterSimulation> command) {
        commands.add(command);
    }

    public void addWater(BlockPos pos, int amount) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        enqueue(sim -> sim.doAddWater(x, y, z, amount, true));
    }

    public void addRain(BlockPos pos, int amount) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        enqueue(sim -> sim.doAddWater(x, y, z, amount, true, true));
    }

    public void setTerrain(BlockPos pos, byte code) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        enqueue(sim -> sim.doSetTerrain(x, y, z, code));
    }

    /** A settled water block saw a neighbour change, the simulation decides whether it has to start flowing again */
    public void neighborChanged(BlockPos pos, int level, boolean reservoir) {
        if(isRemoving(pos)) return;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        byte[] codes = Terrain.neighbors(world, pos);
        enqueue(sim -> sim.doNeighborCheck(x, y, z, level, reservoir, codes));
    }

    public void chunkLoaded(int cx, int cz) {
        enqueue(sim -> sim.doChunkLoaded(cx, cz));
    }

    public void chunkUnloaded(int cx, int cz) {
        enqueue(sim -> sim.doChunkUnloaded(cx, cz));
    }

    public void sendChunk(UUID player, int cx, int cz) {
        enqueue(sim -> sim.doSendChunk(player, cx, cz));
    }

    /** Applies finished steps and starts the next one if the simulation is idle */
    public void tick() {
        sunny = world.isDaytime() && !world.isRaining();
        raining = world.isRaining();
        for(StepResult.Payload payload : latePayloads) send(payload);
        latePayloads.clear();

        StepResult finished;
        while((finished = results.poll()) != null) apply(finished);

        if(stepping.compareAndSet(false, true)) {
            executor.execute(this::runStep);
        }
    }

    public void save() {
        StepResult finished;
        while((finished = results.poll()) != null) apply(finished);
        // Their water is saved with the simulation, the blocks must not be saved too
        removeWoken();
        try {
            writeSaveFile(executor.submit(this::serialize).get(10, TimeUnit.SECONDS));
        }
        catch(Exception ex) {
            Fluidic.LOGGER.error("Failed to save water simulation of dimension " + dimension, ex);
        }
    }

    /** Stops the simulation thread and saves everything, including settled water that never got placed */
    public void shutdown() {
        executor.shutdown();
        try {
            if(!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                Fluidic.LOGGER.warn("Water simulation of dimension " + dimension + " did not stop in time");
                return;
            }
            Consumer<WaterSimulation> command;
            while((command = commands.poll()) != null) command.accept(this);
            StepResult unapplied;
            while((unapplied = results.poll()) != null) unsettle(unapplied);
            unsettle(result);
            writeSaveFile(serialize());
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void apply(StepResult step) {
        // This step added the water of blocks woken by the last one, and its payloads carry it
        removeWoken();
        for(int n = 0; n < step.terrainRequests.size(); n++) {
            long key = step.terrainRequests.getLong(n);
            byte[] terrain = Terrain.snapshot(world, Water.keyX(key), Water.keyY(key), Water.keyZ(key));
            enqueue(sim -> sim.doTerrain(key, terrain));
        }
        for(int n = 0; n < step.terrainRefreshes.size(); n++) {
            long key = step.terrainRefreshes.getLong(n);
            byte[] terrain = Terrain.snapshot(world, Water.keyX(key), Water.keyY(key), Water.keyZ(key));
            if(terrain != null) enqueue(sim -> sim.doRefreshTerrain(key, terrain));
        }

        for(int n = 0; n < step.washes.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.washes.getLong(n));
            if(!world.isBlockLoaded(pos)) continue;
            IBlockState state = world.getBlockState(pos);
            // Breaks with drops and effects, and updates neighbours so the other half of tall plants goes too
            if(Terrain.isWashable(state)) world.destroyBlock(pos, true);
            else setTerrain(pos, Terrain.classify(world, pos, state));
        }

        for(int n = 0; n < step.touches.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.touches.getLong(n));
            if(!world.isBlockLoaded(pos)) continue;
            // The block rechecks its surroundings as if water had been placed beside it, concrete powder hardens
            IBlockState state = world.getBlockState(pos);
            state.getBlock().neighborChanged(state, world, pos, Blocks.WATER, pos);
            setTerrain(pos, Terrain.classify(world, pos, world.getBlockState(pos)));
        }

        for(int n = 0; n < step.settles.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.settles.getLong(n));
            int level = step.settleLevels.getInt(n);
            if(!world.isBlockLoaded(pos)) continue;
            IBlockState state = world.getBlockState(pos);
            int flags = 2;
            if(FluidicWater.isWater(state)) {
                level = Math.max(level, FluidicWater.level(state));
            }
            else if(!Terrain.isOpen(state)) {
                // Something moved in since the step ran, keep the water
                addWater(pos.up(), level * Water.UNIT);
                continue;
            }
            else if(state.getMaterial() != Material.AIR) {
                // Washed away like vanilla water does to plants and torches, neighbours are updated so the other
                // half of tall plants breaks too
                state.getBlock().dropBlockAsItem(world, pos, state, 0);
                flags = 3;
            }
            place(pos, FluidicWater.state(level), flags);
            setTerrain(pos, Terrain.classify(world, pos, world.getBlockState(pos)));
        }

        for(int n = 0; n < step.relevels.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.relevels.getLong(n));
            if(!world.isBlockLoaded(pos) || !FluidicWater.isWater(world.getBlockState(pos))) continue;
            int level = step.relevelLevels.getInt(n);
            IBlockState state = level == 0 ? Blocks.AIR.getDefaultState() : FluidicWater.state(level);
            place(pos, state, 2);
            setTerrain(pos, Terrain.classify(world, pos, state));
        }

        for(int n = 0; n < step.wakes.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.wakes.getLong(n));
            if(!world.isBlockLoaded(pos)) continue;
            IBlockState state = world.getBlockState(pos);
            if(FluidicWater.isWater(state) && !FluidicWater.isReservoir(world, pos, state) && removing.add(pos.toLong())) {
                removals.add(pos.toLong());
                // The world listener is not told about changes in unpopulated chunks, so open the cell directly
                setTerrain(pos, WaterSection.OPEN);
                addWater(pos, FluidicWater.level(state) * Water.UNIT);
            }
            else {
                setTerrain(pos, Terrain.classify(world, pos, state));
            }
        }

        for(StepResult.Payload payload : step.payloads) {
            if(payload.afterBlocks) latePayloads.add(payload);
            else send(payload);
        }
    }

    private void removeWoken() {
        for(int n = 0; n < removals.size(); n++) {
            BlockPos pos = BlockPos.fromLong(removals.getLong(n));
            if(world.isBlockLoaded(pos) && FluidicWater.isWater(world.getBlockState(pos))) place(pos, Blocks.AIR.getDefaultState(), 2);
        }
        removals.clear();
        removing.clear();
    }

    /** Whether the block is water that already went into the simulation and is about to be removed */
    public boolean isRemoving(BlockPos pos) {
        return removing.contains(pos.toLong());
    }

    /** Places a block for the simulation, water placed here is not reported back to it */
    private void place(BlockPos pos, IBlockState state, int flags) {
        FluidicWater.placing = true;
        world.setBlockState(pos, state, flags);
        FluidicWater.placing = false;
    }

    private void send(StepResult.Payload payload) {
        MessageWaterSection message = new MessageWaterSection(dimension, payload.key, payload.data);
        if(payload.target != null) {
            EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(payload.target);
            if(player != null && player.world == world) PacketHandler.INSTANCE.sendTo(message, player);
            return;
        }
        int cx = Water.keyX(payload.key);
        int cz = Water.keyZ(payload.key);
        for(EntityPlayer player : world.playerEntities) {
            if(player instanceof EntityPlayerMP && world.getPlayerChunkMap().isPlayerWatchingChunk((EntityPlayerMP)player, cx, cz)) {
                PacketHandler.INSTANCE.sendTo(message, (EntityPlayerMP)player);
            }
        }
    }

    private File saveFile() {
        return new File(new File(world.getChunkSaveLocation(), "data"), SAVE_FILE);
    }

    private NBTTagCompound readSaveFile() {
        File file = saveFile();
        if(!file.isFile()) return null;
        try(InputStream in = new FileInputStream(file)) {
            return CompressedStreamTools.readCompressed(in);
        }
        catch(IOException ex) {
            Fluidic.LOGGER.error("Failed to read water simulation of dimension " + dimension, ex);
            return null;
        }
    }

    private void writeSaveFile(NBTTagCompound tag) {
        File file = saveFile();
        File temp = new File(file.getParentFile(), SAVE_FILE + ".tmp");
        try {
            file.getParentFile().mkdirs();
            try(OutputStream out = new FileOutputStream(temp)) {
                CompressedStreamTools.writeCompressed(tag, out);
            }
            if(file.exists() && !file.delete()) throw new IOException("Could not replace " + file);
            if(!temp.renameTo(file)) throw new IOException("Could not move " + temp);
        }
        catch(IOException ex) {
            Fluidic.LOGGER.error("Failed to write water simulation of dimension " + dimension, ex);
        }
    }

    // ================================================================ simulation thread

    private void runStep() {
        try {
            long start = System.nanoTime();
            Consumer<WaterSimulation> command;
            while((command = commands.poll()) != null) command.accept(this);
            step();
            statStepNanos = (statStepNanos * 19 + System.nanoTime() - start) / 20;
            statSteps = stepCount;
        }
        catch(Throwable ex) {
            Fluidic.LOGGER.error("Water simulation of dimension " + dimension + " failed a step", ex);
        }
        finally {
            results.add(result);
            result = new StepResult();
            stepping.set(false);
        }
    }

    private void step() {
        final int step = ++stepCount;
        ForgeConfigHandler.ServerConfig config = ForgeConfigHandler.server;
        final double leafAbsorption = config.leafAbsorption;

        stepList.clear();
        for(WaterSection section : sections.values()) {
            section.maxFlow = 0;
            if(section.needsStep()) {
                linkNeighbors(section);
                stepList.add(section);
            }
        }

        // Phase 1: every section computes its outflows from the same masses, in parallel
        if(stepList.size() >= PARALLEL_THRESHOLD) {
            SimManager.pool().submit(() -> stepList.parallelStream().forEach(section -> section.computeFlows(step, leafAbsorption))).join();
        }
        else {
            for(WaterSection section : stepList) section.computeFlows(step, leafAbsorption);
        }

        // Phase 2: move the water, cheap and serial since flows cross section borders
        for(WaterSection section : stepList) section.applyFlows();

        for(WaterSection section : stepList) {
            for(int n = 0; n < section.wakes.size(); n++) {
                long cell = section.wakes.getLong(n);
                WaterSection target = sections.get(cell >>> 12);
                int i = (int)(cell & 0xFFF);
                if(target != null && target.state == WaterSection.STATE_READY && target.terrain[i] >= WaterSection.SETTLED) {
                    target.terrain[i] = WaterSection.WAKING;
                    result.wakes.add(blockPos(target, i));
                }
            }
            for(int n = 0; n < section.missing.size(); n++) {
                long key = section.missing.getLong(n);
                if(!sections.containsKey(key)) createPending(key);
            }
            for(int n = 0; n < section.touches.size(); n++) {
                long cell = section.touches.getLong(n);
                WaterSection target = sections.get(cell >>> 12);
                int i = (int)(cell & 0xFFF);
                // Reported once, the server thread restores the code if the block did not change
                if(target != null && target.state == WaterSection.STATE_READY && target.terrain[i] == WaterSection.SOLID_REACTIVE) {
                    target.terrain[i] = WaterSection.SOLID;
                    result.touches.add(blockPos(target, i));
                }
            }
            for(int n = 0; n < section.washes.size(); n++) {
                int i = section.washes.getInt(n);
                section.terrain[i] = WaterSection.OPEN;
                result.washes.add(blockPos(section, i));
            }
            for(int n = 0; n < section.wakeSections.size(); n++) {
                WaterSection dormant = sections.get(section.wakeSections.getLong(n));
                if(dormant != null) dormant.wakeUp();
            }
        }

        boolean flushNet = step % Math.max(1, config.networkInterval) == 0;
        int cells = 0, waiting = 0, flowing = 0;
        int refreshBudget = REFRESHES_PER_STEP;
        // Match the rate settled water dries at, a block gets a random tick about every 1365 ticks.
        // Dormant water is shallow water around plants, so it is a surface resting on soil, sky is not checked.
        // Nothing evaporates in rain, the ground still soaks water up.
        double evaporation = ((raining ? 0.0D : config.evaporationChance * (sunny ? config.sunEvaporationMultiplier : 1.0D)) + config.groundAbsorptionChance)
                * EVAPORATE_STEPS / 1365.0D;
        ObjectIterator<WaterSection> iterator = sections.values().iterator();
        while(iterator.hasNext()) {
            WaterSection section = iterator.next();
            cells += section.filled;
            // Rain held back from water that keeps moving is added now and then, so it does not stay hidden
            if(section.rain != null && step % RAIN_RELEASE_STEPS == 0) section.releaseRain();
            if(section.dormant) {
                section.idleSteps = 0;
                if(step % EVAPORATE_STEPS == 0) {
                    linkNeighbors(section);
                    if(section.evaporate(random, evaporation)) section.dirtyNet = true;
                }
            }
            else if(section.state == WaterSection.STATE_READY && section.filled > 0) {
                section.idleSteps = 0;
                if(section.pending || section.touched || section.maxFlow > Water.QUIET_FLOW) {
                    section.quietSteps = 0;
                    if(section.pending) waiting++;
                    else if(section.maxFlow > Water.QUIET_FLOW) flowing++;
                }
                else if(++section.quietSteps >= config.settleSteps) {
                    settle(section);
                }
            }
            else {
                section.quietSteps = 0;
                if(section.filled == 0 && ++section.idleSteps > EVICT_STEPS && section.lastSent == null
                        && section.deferred == null && section.state != WaterSection.STATE_PENDING && !section.needsStep()) {
                    iterator.remove();
                    continue;
                }
            }
            section.touched = false;
            if(flushNet && section.dirtyNet) flushNet(section, false);
            if(section.state == WaterSection.STATE_READY && refreshBudget > 0 && step - section.refreshedStep >= REFRESH_STEPS) {
                section.refreshedStep = step;
                refreshBudget--;
                result.terrainRefreshes.add(section.key);
            }
        }

        // Compressed water pushed out of the top of settled sections, added after iterating since it may create sections
        for(int n = 0; n < overflowCells.size(); n += 2) {
            BlockPos pos = BlockPos.fromLong(overflowCells.getLong(n));
            doAddWater(pos.getX(), pos.getY(), pos.getZ(), (int)overflowCells.getLong(n + 1), false);
        }
        overflowCells.clear();
        for(int n = 0; n < poolMissing.size(); n++) {
            long key = poolMissing.getLong(n);
            if(!sections.containsKey(key)) createPending(key);
        }
        poolMissing.clear();

        statSections = sections.size();
        statActive = stepList.size();
        statCells = cells;
        statWaiting = waiting;
        statFlowing = flowing;
    }

    /** Describes how the simulation sees a block, waits for the simulation thread. Server thread only. */
    public String inspect(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        try {
            return executor.submit(() -> {
                if(y < 0 || y > 255) return "outside the world";
                WaterSection section = sections.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
                if(section == null) return "not simulated";
                String[] states = {"pending terrain", "frozen", "ready"};
                int i = Water.cellIndex(x & 15, y & 15, z & 15);
                String terrain = section.terrain == null ? "none" : String.valueOf(section.terrain[i]);
                return String.format("section %s, terrain %s, mass %d/%d, section quiet %d steps, %d wet cells",
                        states[section.state], terrain, section.mass[i], Water.FULL, section.quietSteps, section.filled);
            }).get(5, TimeUnit.SECONDS);
        }
        catch(Exception ex) {
            return "failed: " + ex;
        }
    }

    /** Human readable statistics, safe to call from any thread */
    public String stats() {
        return String.format("DIM%d: %d steps, %.2f ms/step, %d active of %d sections (%d flowing, %d waiting on terrain or wakes), %d wet cells",
                dimension, statSteps, statStepNanos / 1.0E6D, statActive, statSections, statFlowing, statWaiting, statCells);
    }

    private void linkNeighbors(WaterSection section) {
        for(int dir = 0; dir < 6; dir++) {
            int ny = section.sy + Water.DY[dir];
            section.neighbors[dir] = ny < 0 || ny > 15 ? WaterSection.OUT_OF_WORLD : sections.get(section.neighborKey(dir));
        }
    }

    /** Turns a section that stopped moving into real blocks */
    private void settle(WaterSection section) {
        // Rain held back while the water was moving joins it now, before it is levelled
        section.releaseRain();
        overflow.clear();
        section.decompress(overflow);
        for(int n = 0; n < overflow.size(); n += 2) {
            int column = overflow.getInt(n);
            overflowCells.add(new BlockPos(section.sx * 16 + (column & 15), section.sy * 16 + 16, section.sz * 16 + (column >> 4)).toLong());
            overflowCells.add(overflow.getInt(n + 1));
        }
        // Waits for the terrain of neighbouring sections the pool reaches, so it is levelled as a whole
        if(!levelSurfaces(section, section.levelWaits < MAX_LEVEL_WAITS)) {
            section.levelWaits++;
            return;
        }
        section.levelWaits = 0;
        for(int i = 0; i < 4096; i++) {
            int mass = section.mass[i];
            if(mass == 0) continue;
            byte code = section.terrain[i];
            int level = Water.settleLevel(mass);
            // Shallow water around a flower stays simulated, it only washes the flower away above half a block
            if(code == WaterSection.WASHABLE && level > 0 && level <= Water.LEVELS / 2) continue;
            // A water block cannot share a ladder, sign or leaves, water there stays simulated and is drawn inside them
            if((code == WaterSection.PERMEABLE || code == WaterSection.POROUS) && level > 0) continue;
            section.mass[i] = 0;
            // Less than half a level dries up
            if(level > 0 && WaterSection.isOpen(code)) {
                section.terrain[i] = (byte)(WaterSection.SETTLED + level);
                result.settles.add(blockPos(section, i));
                result.settleLevels.add(level);
            }
        }
        section.afterSettle();
        section.quietSteps = 0;
        flushNet(section, true);
    }

    /**
     * Levels every connected pool surface in a section that is about to settle. The cut-off that stops nearly
     * equal cells from trading water forever leaves long pools with a slight slope, so the surface is evened out
     * directly before it becomes blocks. Surface cells of the same pool that already settled are re-levelled too.
     */
    private boolean levelSurfaces(WaterSection section, boolean wait) {
        visited.clear();
        boolean complete = true;
        for(int i = 0; i < 4096; i++) {
            if(!visited.contains(WaterSection.packCell(section.key, i)) && isPoolSurface(section, i)) complete &= levelSurface(section, i, wait);
        }
        return complete;
    }

    /**
     * Levels one pool surface. When it reaches a section that has no terrain yet it either returns false without
     * changing it, to wait for that section, or levels the part it found.
     */
    private boolean levelSurface(WaterSection start, int startIndex, boolean wait) {
        poolCells.clear();
        long first = WaterSection.packCell(start.key, startIndex);
        visited.add(first);
        poolCells.add(first);
        long total = 0;
        boolean complete = true;
        for(int n = 0; n < poolCells.size(); n++) {
            long cell = poolCells.getLong(n);
            WaterSection section = sections.get(cell >>> 12);
            int i = (int)(cell & 0xFFF);
            total += surfaceMass(section, i);
            for(int dir = Water.NORTH; dir <= Water.EAST && poolCells.size() < MAX_POOL; dir++) {
                WaterSection ns = WaterSection.crosses(i, dir) ? sections.get(section.neighborKey(dir)) : section;
                if(ns == null || ns.state == WaterSection.STATE_PENDING) {
                    // Sections drop out of the simulation a while after they settle, the pool may continue there
                    if(wait) {
                        if(ns == null) poolMissing.add(section.neighborKey(dir));
                        complete = false;
                    }
                    continue;
                }
                if(ns.state != WaterSection.STATE_READY) continue;
                int ni = WaterSection.neighborIndex(i, dir);
                long packed = WaterSection.packCell(ns.key, ni);
                if(visited.contains(packed) || !isPoolSurface(ns, ni)) continue;
                // Water still moving in another section is where the pool is being filled or drained from. Evening it
                // out with the still part would pull its surface down every time a section beside it settles.
                if(ns != start && WaterSection.isOpen(ns.terrain[ni]) && isMoving(ns)) continue;
                visited.add(packed);
                poolCells.add(packed);
            }
        }
        if(!complete) return false;
        int count = poolCells.size();
        if(count < 2) return true;

        int base = (int)(total / count);
        int remainder = (int)(total % count);
        for(int n = 0; n < count; n++) {
            long cell = poolCells.getLong(n);
            WaterSection section = sections.get(cell >>> 12);
            int i = (int)(cell & 0xFFF);
            int mass = base + (n < remainder ? 1 : 0);
            if(WaterSection.isOpen(section.terrain[i])) {
                section.mass[i] = mass;
                section.dirtyNet = true;
                // Shallow water around a flower may now be deep enough to wash it away
                section.wakeUp();
            }
            else {
                int level = Water.settleLevel(mass);
                if(level == section.terrain[i] - WaterSection.SETTLED) continue;
                section.terrain[i] = level == 0 ? WaterSection.OPEN : (byte)(WaterSection.SETTLED + level);
                result.relevels.add(blockPos(section, i));
                result.relevelLevels.add(level);
            }
        }
        return true;
    }

    /** Whether a section has not been still for half the steps water needs to settle */
    private static boolean isMoving(WaterSection section) {
        return !section.dormant && section.quietSteps < ForgeConfigHandler.server.settleSteps / 2;
    }

    /** A partly filled cell resting on something, simulated or settled, that belongs to a pool surface */
    private boolean isPoolSurface(WaterSection section, int i) {
        int mass = surfaceMass(section, i);
        if(mass <= 0 || mass >= Water.FULL) return false;
        WaterSection below;
        if(!WaterSection.crosses(i, Water.DOWN)) below = section;
        else if(section.sy == 0) return true;
        else below = sections.get(section.neighborKey(Water.DOWN));
        int bi = WaterSection.neighborIndex(i, Water.DOWN);
        int code = WaterSection.codeOf(below, bi);
        if(code < 0 || code == WaterSection.WAKING) return false;
        if(WaterSection.holds(code)) return below.mass[bi] >= Water.FULL - Water.SPREAD_DIFF;
        if(code >= WaterSection.SETTLED) return code - WaterSection.SETTLED == Water.LEVELS;
        return true;
    }

    private static int surfaceMass(WaterSection section, int i) {
        byte code = section.terrain[i];
        if(WaterSection.isOpen(code)) return section.mass[i];
        if(code > WaterSection.SETTLED && code < WaterSection.SETTLED + Water.LEVELS) return (code - WaterSection.SETTLED) * Water.UNIT;
        return 0;
    }

    /** Puts settled water that was never placed back into the grid so it gets saved */
    private void unsettle(StepResult step) {
        for(int n = 0; n < step.settles.size(); n++) {
            BlockPos pos = BlockPos.fromLong(step.settles.getLong(n));
            WaterSection section = sections.get(Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            if(section == null) continue;
            int i = Water.cellIndex(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if(section.terrain != null) section.terrain[i] = WaterSection.OPEN;
            section.addMass(i, step.settleLevels.getInt(n) * Water.UNIT);
        }
        step.settles.clear();
        step.settleLevels.clear();
    }

    private void flushNet(WaterSection section, boolean afterBlocks) {
        section.dirtyNet = false;
        byte[] heights = null;
        if(section.state == WaterSection.STATE_READY && section.filled > 0) {
            heights = new byte[4096];
            boolean any = false;
            for(int i = 0; i < 4096; i++) {
                heights[i] = Water.quantize(section.mass[i]);
                any |= heights[i] != 0;
            }
            if(!any) heights = null;
        }
        if(Arrays.equals(heights, section.lastSent)) return;
        section.lastSent = heights;
        if(heights == null) published.remove(section.key);
        else published.put(section.key, heights);
        result.payloads.add(new StepResult.Payload(section.key, heights == null ? null : deflate(heights), null, afterBlocks));
    }

    private byte[] deflate(byte[] data) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        while(!deflater.finished()) {
            int count = deflater.deflate(deflateBuffer);
            out.write(deflateBuffer, 0, count);
        }
        return out.toByteArray();
    }

    private WaterSection createPending(long key) {
        WaterSection section = new WaterSection(Water.keyX(key), Water.keyY(key), Water.keyZ(key));
        section.state = WaterSection.STATE_PENDING;
        sections.put(key, section);
        result.terrainRequests.add(key);
        return section;
    }

    private static long blockPos(WaterSection section, int i) {
        return new BlockPos(section.sx * 16 + (i & 15), section.sy * 16 + (i >> 8), section.sz * 16 + ((i >> 4) & 15)).toLong();
    }

    private WaterSection readySection(int x, int y, int z) {
        if(y < 0 || y > 255) return null;
        WaterSection section = sections.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return section != null && section.state == WaterSection.STATE_READY ? section : null;
    }

    /** Whether water falling into a block would go on, as far as the simulation knows the block */
    private boolean acceptsFalling(int x, int y, int z) {
        WaterSection section = readySection(x, y, z);
        if(section == null) return false;
        int i = Water.cellIndex(x & 15, y & 15, z & 15);
        byte code = section.terrain[i];
        if(code == WaterSection.SINK || code == WaterSection.RESERVOIR) return true;
        if(WaterSection.holds(code)) return section.mass[i] < Water.FULL - Water.SPREAD_DIFF;
        return code >= WaterSection.SETTLED && code - WaterSection.SETTLED < Water.LEVELS;
    }

    private int massAt(int x, int y, int z) {
        WaterSection section = readySection(x, y, z);
        return section == null ? 0 : section.mass[Water.cellIndex(x & 15, y & 15, z & 15)];
    }

    // ---------------------------------------------------------------- commands

    private void doAddWater(int x, int y, int z, int amount, boolean allowUp) {
        doAddWater(x, y, z, amount, allowUp, false);
    }

    /**
     * Adds water to a cell. Rain does not count as a change that keeps a section from settling, otherwise a section
     * would never settle while it rains, and drops landing in a pool are held back until the pool settles instead
     * of setting it rippling, see WaterSection.bankRain.
     */
    private void doAddWater(int x, int y, int z, int amount, boolean allowUp, boolean rain) {
        if(amount <= 0 || y < 0 || y > 255) return;
        long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
        WaterSection section = sections.get(key);
        if(section == null) section = createPending(key);
        if(section.state == WaterSection.STATE_PENDING) {
            section.defer(() -> doAddWater(x, y, z, amount, allowUp, rain));
            return;
        }
        if(section.state == WaterSection.STATE_FROZEN) return;
        int i = Water.cellIndex(x & 15, y & 15, z & 15);
        if(WaterSection.holds(section.terrain[i])) {
            if(rain && section.mass[i] > 0 && !section.dormant) {
                section.bankRain(i, amount);
                return;
            }
            section.addMass(i, amount);
            section.wakeUp();
            if(!rain) section.touched = true;
            section.dirtyNet = true;
        }
        else if(allowUp) {
            doAddWater(x, y + 1, z, amount, false, rain);
        }
    }

    private void doSetTerrain(int x, int y, int z, byte code) {
        WaterSection section = readySection(x, y, z);
        if(section != null) applyTerrain(section, Water.cellIndex(x & 15, y & 15, z & 15), code);
    }

    private void doRefreshTerrain(long key, byte[] terrain) {
        WaterSection section = sections.get(key);
        if(section == null || section.state != WaterSection.STATE_READY) return;
        for(int i = 0; i < 4096; i++) applyTerrain(section, i, terrain[i]);
    }

    private void applyTerrain(WaterSection section, int i, byte code) {
        byte old = section.terrain[i];
        // A waking block is still settled water in the world until the server thread removes it
        if(old == code || (old == WaterSection.WAKING && code >= WaterSection.SETTLED)) return;
        section.terrain[i] = code;
        section.touched = true;
        // Shallow water resting next to the change may have somewhere to go now
        section.wakeUp();
        for(int dir = 0; dir < 6; dir++) {
            WaterSection neighbor = WaterSection.crosses(i, dir) ? sections.get(section.neighborKey(dir)) : null;
            if(neighbor != null) neighbor.wakeUp();
        }
        int mass = section.mass[i];
        if(!WaterSection.holds(code) && mass > 0) {
            // A block was placed into water, push the water up out of it
            section.removeMass(i, mass);
            section.dirtyNet = true;
            doAddWater(section.sx * 16 + (i & 15), section.sy * 16 + (i >> 8) + 1, section.sz * 16 + ((i >> 4) & 15), mass, false);
        }
    }

    private void doTerrain(long key, byte[] terrain) {
        WaterSection section = sections.get(key);
        if(section == null || section.state != WaterSection.STATE_PENDING) return;
        if(terrain == null) {
            section.state = WaterSection.STATE_FROZEN;
            section.deferred = null;
            return;
        }
        section.terrain = terrain;
        section.state = WaterSection.STATE_READY;
        section.touched = true;
        section.dirtyNet = true;
        for(int i = 0; i < 4096; i++) {
            int mass = section.mass[i];
            if(mass > 0 && !WaterSection.holds(terrain[i])) {
                section.removeMass(i, mass);
                doAddWater(section.sx * 16 + (i & 15), section.sy * 16 + (i >> 8) + 1, section.sz * 16 + ((i >> 4) & 15), mass, false);
            }
        }
        List<Runnable> deferred = section.deferred;
        section.deferred = null;
        if(deferred != null) for(Runnable command : deferred) command.run();
    }

    private void doChunkLoaded(int cx, int cz) {
        for(int sy = 0; sy < 16; sy++) {
            long key = Water.sectionKey(cx, sy, cz);
            WaterSection section = sections.get(key);
            if(section != null && section.state == WaterSection.STATE_FROZEN) {
                section.state = WaterSection.STATE_PENDING;
                result.terrainRequests.add(key);
            }
        }
    }

    private void doChunkUnloaded(int cx, int cz) {
        for(int sy = 0; sy < 16; sy++) {
            WaterSection section = sections.get(Water.sectionKey(cx, sy, cz));
            if(section != null && section.state == WaterSection.STATE_READY) {
                section.state = WaterSection.STATE_FROZEN;
                section.terrain = null;
                section.lastSent = null;
                published.remove(section.key);
                section.dirtyNet = false;
                section.quietSteps = 0;
            }
        }
    }

    private void doNeighborCheck(int x, int y, int z, int level, boolean reservoir, byte[] codes) {
        WaterSection self = readySection(x, y, z);
        int i = Water.cellIndex(x & 15, y & 15, z & 15);
        if(self != null && self.terrain[i] == WaterSection.WAKING) return;

        // The codes come fresh from the world, catch up in case the change was not reported
        for(int dir = 0; dir < 6; dir++) {
            doSetTerrain(x + Water.DX[dir], y + Water.DY[dir], z + Water.DZ[dir], codes[dir]);
        }

        if(reservoir) {
            // Oceans never wake, they pour into open space beside and below them and keep it filled
            for(int dir = 0; dir < 6; dir++) {
                if(dir == Water.UP || !WaterSection.isOpen(codes[dir])) continue;
                int nx = x + Water.DX[dir], ny = y + Water.DY[dir], nz = z + Water.DZ[dir];
                if(massAt(nx, ny, nz) < Water.FULL - Water.SPREAD_DIFF) doAddWater(nx, ny, nz, WaterSection.RESERVOIR_FLOW, false);
            }
            return;
        }

        boolean wake = false;
        for(int dir = 0; dir < 6 && !wake; dir++) {
            int code = codes[dir];
            int nx = x + Water.DX[dir], ny = y + Water.DY[dir], nz = z + Water.DZ[dir];
            if(dir == Water.DOWN) {
                // Leaves below take water until they are full of it
                wake = code == WaterSection.SINK || code == WaterSection.RESERVOIR
                        || (WaterSection.holds(code) && massAt(nx, ny, nz) < Water.FULL - Water.SPREAD_DIFF)
                        || (code >= WaterSection.SETTLED && code - WaterSection.SETTLED < Water.LEVELS);
            }
            else if(dir == Water.UP) {
                wake = code >= WaterSection.SETTLED && level < Water.LEVELS;
            }
            else if(WaterSection.isOpen(code)) {
                int mass = massAt(nx, ny, nz);
                // Thin water stays on flat ground, but spills over the edge of a ledge
                wake = mass == 0 ? level >= 2 || acceptsFalling(nx, ny - 1, nz) : level * Water.UNIT - mass > Water.WAKE_DIFF;
            }
            else if(code == WaterSection.POROUS) {
                // A full block fills leaves beside it, like leaves under the surface of a pool
                wake = level == Water.LEVELS && massAt(nx, ny, nz) < Water.FULL - Water.SPREAD_DIFF;
            }
            else if(code == WaterSection.SINK) {
                wake = level >= 2;
            }
            else if(code == WaterSection.RESERVOIR) {
                // Water beside an ocean fills up to its level
                wake = level < Water.LEVELS;
            }
            else if(code >= WaterSection.SETTLED) {
                wake = Math.abs(level - (code - WaterSection.SETTLED)) * Water.UNIT > Water.WAKE_DIFF;
            }
        }
        if(!wake) return;
        if(self != null) self.terrain[i] = WaterSection.WAKING;
        result.wakes.add(new BlockPos(x, y, z).toLong());
    }

    private void doSendChunk(UUID player, int cx, int cz) {
        for(int sy = 0; sy < 16; sy++) {
            WaterSection section = sections.get(Water.sectionKey(cx, sy, cz));
            if(section != null && section.lastSent != null) {
                result.payloads.add(new StepResult.Payload(section.key, deflate(section.lastSent), player, false));
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private NBTTagCompound serialize() {
        NBTTagList list = new NBTTagList();
        for(WaterSection section : sections.values()) {
            section.releaseRain();
            if(section.filled == 0) continue;
            int[] cells = new int[section.filled * 2];
            int n = 0;
            for(int i = 0; i < 4096 && n < cells.length; i++) {
                if(section.mass[i] > 0) {
                    cells[n++] = i;
                    cells[n++] = section.mass[i];
                }
            }
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("key", section.key);
            tag.setIntArray("cells", cells);
            list.appendTag(tag);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", 1);
        root.setTag("sections", list);
        return root;
    }

    private void restore(NBTTagCompound root) {
        NBTTagList list = root.getTagList("sections", Constants.NBT.TAG_COMPOUND);
        for(int n = 0; n < list.tagCount(); n++) {
            NBTTagCompound tag = list.getCompoundTagAt(n);
            long key = tag.getLong("key");
            WaterSection section = sections.get(key);
            if(section == null) section = createPending(key);
            int[] cells = tag.getIntArray("cells");
            for(int c = 0; c + 1 < cells.length; c += 2) {
                if(cells[c] >= 0 && cells[c] < 4096 && cells[c + 1] > 0) section.addMass(cells[c], cells[c + 1]);
            }
        }
    }
}
