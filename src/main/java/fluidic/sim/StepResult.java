package fluidic.sim;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything one simulation step asks the server thread to do: world changes, terrain snapshots and packets.
 */
final class StepResult {

    /** Block positions (BlockPos#toLong) of water that settled, with its level in {@link #settleLevels} */
    final LongArrayList settles = new LongArrayList();
    final IntArrayList settleLevels = new IntArrayList();
    /** Block positions of settled water blocks whose level changes to {@link #relevelLevels} (0 removes them) */
    final LongArrayList relevels = new LongArrayList();
    final IntArrayList relevelLevels = new IntArrayList();
    /** Block positions of flowers and the like that water washes away */
    final LongArrayList washes = new LongArrayList();
    /** Block positions of blocks that react to water, like concrete powder, that water reached */
    final LongArrayList touches = new LongArrayList();
    /** Block positions of dry sponges that soaked up all they can hold */
    final LongArrayList wetSponges = new LongArrayList();
    /** Block positions of settled water blocks to turn back into simulated water */
    final LongArrayList wakes = new LongArrayList();
    /** Section keys the simulation needs a terrain snapshot for */
    final LongArrayList terrainRequests = new LongArrayList();
    /** Section keys of ready sections whose terrain snapshot should be retaken */
    final LongArrayList terrainRefreshes = new LongArrayList();
    final List<Payload> payloads = new ArrayList<>();

    static final class Payload {
        final long key;
        /** Deflated height bytes, null when the section has no water */
        final byte[] data;
        /** Single receiving player, null for everyone watching the chunk */
        final UUID target;
        /** Sent a tick late so it reaches clients after the block changes of settled water */
        final boolean afterBlocks;

        Payload(long key, byte[] data, UUID target, boolean afterBlocks) {
            this.key = key;
            this.data = data;
            this.target = target;
            this.afterBlocks = afterBlocks;
        }
    }
}
