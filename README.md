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
  server thread classifies blocks (open, washable like flowers, permeable like ladders, porous leaves, solid
  with a friction, or vanilla water that water flowing in merges with) from the `Block Classifications`
  config, falling back to rules by material. It hands over whole-section snapshots and keeps them current
  through a world listener.
* **Settling**. A section that stays quiet for `Settle Steps` steps becomes `fluidic:settled_water`
  blocks, which have 16 levels and render with Forge's fluid model. It then leaves the simulation, so a
  resting lake costs nothing. Settled blocks never flow on their own. When a neighbour changes, or simulated water
  arrives at a different level, the simulation wakes them back up.
* **Rain**. While it rains, random columns of player-loaded chunks get a drop. This is the same sampling
  vanilla uses to place snow. Thunderstorms drop more. Leaves absorb some of the water dripping through.
* **Evaporation**. The surface of still water slowly evaporates, much faster in direct sun, and water
  resting on soil soaks into the ground.
* **Client rendering** (`WaterRenderer`). The server sends deflated per-cell heights of changed sections
  to players watching the chunk. The client compiles each section into a display list, with smoothed
  corner heights, and draws it after the world.
* **Buckets**. An empty bucket scoops up a full block of settled water into a Fluidic water bucket, which
  pours that block back as simulated water. Partly filled blocks cannot be scooped, so no water is created.
* **Persistence**. Water still moving when the world saves is written to
  `<dimension>/data/fluidic_water.dat`. Settled water is saved as ordinary blocks.

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
