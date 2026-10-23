# Fluidic

Finite water simulation for Minecraft 1.12.2 (Forge 14.23.5.2860). Rain collects into
puddles and flows downhill, water keeps its volume, and moving water lives on its own threads. It only
becomes real blocks once it has settled.

## Why voxels instead of particles

The simulation is a sparse voxel grid with one integer mass per block cell (1024 = one full block), stored
in 16³ sections that line up with chunk sections. Particles (SPH or similar) were not used because:

* A single puddle needs tens of particles per block. A rainstorm over the loaded area would need millions,
  and each step runs a neighbour search over all of them. A voxel cell costs a few integer operations, and
  dry or resting cells cost nothing.
* Minecraft's world is a block grid. Voxels map straight onto blocks for collision, settling and saving.
  Particles would have to be voxelized every time they settle.
* Integer masses conserve water exactly and make the result the same no matter how sections are split
  across threads.

## How it works

* **Simulation thread per dimension** (`WaterSimulation`). The server thread starts at most one step per
  tick and never waits for it. When a step runs long, the simulation slows down; the server does not.
  The two threads only talk through queues: block changes, rain and chunk loads go in, and settled water,
  terrain snapshot requests and client packets come out.
* **Two-phase step** (`WaterSection`). Phase 1 computes every wet cell's outflows (falling, spreading,
  pressure) from the same masses. It runs in parallel across sections on a shared fork/join pool. Phase 2
  moves the water serially, which is cheap, so flows crossing section borders cannot race.
* **Terrain snapshots** (`Terrain`, `WaterWorldListener`). The simulation never touches the world. The
  server thread classifies blocks (open, washable like flowers, permeable like ladders and fences,
  waterloggable like stairs and slabs, porous leaves, solid with a friction, or water of other mods that water
  flowing in merges with) from the `Block Classifications` config, falling back to rules by material.
  Waterloggable blocks hold water like waterlogged blocks of later versions, but are sealed top and bottom so
  a roof of slabs does not leak. It hands over whole-section snapshots and keeps them current
  through a world listener.
* **Water blocks**. Vanilla water is replaced by Fluidic water under the same names (`minecraft:water`,
  `minecraft:flowing_water`), so vanilla and other mods treat it as plain water. It never flows on its own.
  Water blocks hold eighths of a block in vanilla's levels: level 0 is full, 1 to 7 hold 7/8 down to 1/8.
* **Settling**. A section that stays quiet for `Settle Steps` steps becomes water blocks and leaves the
  simulation, so a resting lake costs nothing. When a neighbour changes, or simulated water arrives at a
  different level, the simulation wakes them back up. Water placed by anything else, like a bucket, starts
  flowing the same way.
* **Oceans and rivers**. Full water below sea level that belongs to an ocean or river is an endless
  reservoir: water flowing in disappears, and holes or caves opened beside or below it fill up from it,
  so the sea never drains. A body of water counts when its surface at sea level is at least 128 blocks
  and reaches into an ocean, river or beach biome (`Reservoirs`). The whole body counts, also where it
  runs past the edge of its biome, while pools and flooded caves that do not reach the sea are ordinary
  water. Damming off part of the sea makes it ordinary water right away.
* **Rain**. While it rains, random columns of player-loaded chunks get a drop. This is the same sampling
  vanilla uses to place snow. Thunderstorms drop more. Leaves absorb some of the water dripping through.
* **Evaporation**. Puddles, shallow pools and partly filled surfaces slowly evaporate, much faster in
  direct sun, and water resting on soil soaks into the ground. Nothing evaporates while it rains or
  thunders or below sea level, though soil keeps soaking water up. Lakes and oceans keep their water.
* **Client rendering** (`WaterRenderer`). The server sends deflated per-cell heights of changed sections
  to players watching the chunk. The client compiles each section into a display list, with smoothed
  corner heights, and draws it after the world. Shader packs only shade what the chunks draw, so while an
  OptiFine shader pack is in use (`Draw Simulated Water In Chunks` in the client config) simulated water is
  drawn by the chunks instead: while a chunk redraws, empty blocks holding simulated water read as water
  blocks of the same level (`ChunkWater`). Water moving then makes its chunks redraw, which costs more.
* **Buckets**. Vanilla buckets scoop up full water blocks and pour them back, which then flow. Aimed at
  partly filled water, a bucket gathers a block of water from the surface around it (`BucketHandler`), an
  eighth at a time from the highest blocks, and only fills when there is a whole block to take, so no water
  is created and the vanilla infinite water source no longer works. Poured into water, a bucket adds a block
  of water on top of it.
* **Fountains and drains**. Two blocks for building with water. A fountain faces the way it was placed, like a
  dispenser, and while it gets a redstone signal it pours water out of its front, 1 block per second by default
  (`Fountain Output`). It pours into the pool in front of it from the top. A drain takes in water touching it
  like a sponge that never fills, pools drain into it.
* **Lily pads**. Lily pads float on water more than half full, simulated water too, and follow the water
  level (`LilyPads`): water rising into a pad lifts it, and a pad whose water went down sinks to the new
  surface up to 4 blocks below. They only break when there is no water near enough.
* **Sponges**. A dry sponge soaks up water touching it, partly filled blocks and flowing water included,
  and pools drain into it. Once it holds the configured amount (16 blocks by default) it turns wet. A
  sponge touching an ocean or river fills up at once.
* **Persistence**. Water still moving when the world saves is written to
  `<dimension>/data/fluidic_water.dat`. Settled water is saved as ordinary blocks. Settled water blocks
  of earlier versions (`fluidic:settled_water`) are converted to water blocks as their chunks load.

## Commands

* `/fluidic stats` shows step time, active sections and wet cells.
* `/fluidic inspect <x> <y> <z>` shows how the simulation sees one block.
* `/fluidic add <x> <y> <z> <blocks> [radius]` pours water into the simulation.

## Known limitations

* Pressure comes from slight compression of deep water, so U-bends equalize slowly (minutes over long
  distances).
* Entities swim in, are carried by and drown in water that is still moving through mixins loaded by
  FermiumBooter. Without FermiumBooter, only settled water affects entities.
* Fences, walls, doors and other partial blocks that block movement are solid unless listed as permeable
  in `Block Classifications`.

## Building

```
./gradlew build
```

Gradle must run on Java 17+. The mod itself is compiled with a Java 8 toolchain.
