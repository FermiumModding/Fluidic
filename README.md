Disclaimer: This mod was created as a stress test of Claude Code (Opus 5.5) to determine its current state
in regards to Minecraft modding, its performance, and potential utility. As such 99% of this project is 
generated code. While extensive testing and iteration has been done, there will undoubtedly be bugs, primarily
due to the invasiveness of this project's features. Please report issues to the issue tracker.

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
  simulation, so a resting lake costs nothing. Puddles, sections whose water is no deeper than half a block
  and rests on no water, wait `Puddle Settle Steps` instead: they mostly evaporate, soak away or run into
  ponds, and settled each change would redraw their chunk. While it rains they wait `Storm Puddle Settle
  Steps`, by default until the rain stops, since every drop landing on a settled puddle changes its blocks.
  Puddles waiting are checked again every 100 steps, spread out so a storm ending does not settle them all
  at once. When a neighbour changes, or simulated water arrives at a
  different level, the simulation wakes them back up. Water placed by anything else, like a bucket, starts
  flowing the same way. Water placed while a chunk is generated, like lakes and springs, stays as generated
  (`ChunkMixin`) until something disturbs it or a random tick near a player finds it out of balance.
* **Oceans, rivers and swamps**. Full water below sea level that belongs to an ocean, river or swamp is an
  endless reservoir: water flowing in disappears, and holes or caves opened beside or below it fill up
  from it, so the sea never drains. A body of water counts when its surface at sea level is at least 128
  blocks and reaches into an ocean, river, beach or swamp biome (`Reservoirs`), so small ponds in a swamp
  stay ordinary water. The biomes are set by the `Reservoir Biomes` server option, as biome ids like
  `minecraft:swampland` or biome dictionary tags like `OCEAN`. Searching the body of water costs some time
  when water changes below sea level. With `Search Connected Water Bodies` turned off, the biome alone
  decides instead: all full water below sea level in a reservoir biome is endless, none outside it is. The whole body counts, also where it runs past the edge of its biome, while pools
  and flooded caves that do not reach the sea are ordinary water. Damming off part of the sea makes it
  ordinary water right away near the dam, and within two minutes further away.
* **Rain**. While it rains, random columns of player-loaded chunks get a drop. This is the same sampling
  vanilla uses to place snow. Thunderstorms drop more. Leaves absorb some of the water dripping through.
  The `Rain Biomes` server option limits the biomes it collects in, by biome id or biome dictionary tag, with `*` for
  every biome and `!` to leave biomes out. By default sandy and snowy biomes are left out, some modded deserts and snowy biomes report rain
  without showing any.
* **Evaporation**. Puddles, shallow pools and partly filled surfaces slowly evaporate, much faster in
  direct sun, and water resting on soil soaks into the ground. Nothing evaporates while it rains or
  thunders or below sea level, though soil keeps soaking water up. Lakes and oceans keep their water.
* **Client rendering** (`WaterRenderer`). The server sends deflated per-cell heights of changed sections
  to players watching the chunk. The client compiles each section into a display list, with smoothed
  corner heights, and draws it after the world. Between updates the water moves on smoothly (`WaterMotion`): within a
  few chunks of the camera each section eases its heights from where the water is shown to the new ones, over about the
  time between updates, and is rebuilt from the eased heights every frame with the sections around it, so every frame is
  a whole surface. The water is shown up to one update behind (`Smooth Water Motion` in the client config). Further
  than `Full Detail Distance` (2 chunks by default) updates of a section are held back and shown less often, up to half a
  second apart, as if they arrived later, so the water around still meets them, and saves rebuilding the water there.
  Sections holding only puddles, no water deeper than half a block or resting on water, are held back from
  `Puddle Detail Distance` (1 chunk) on, up to two seconds apart: rain keeps them changing all over the ground. There
  they are also drawn simply, each block flat at its own height with sides down to the water beside it, instead of with
  corners shared with the water around, which costs far less to build. Water blocks
  within two blocks of simulated water are left to this renderer by the chunks, so the two never meet where water moves
  (`ChunkRedraws`). Shader packs only shade what the chunks draw, so while an
  shader pack is in use, and always with Celeritas or its forks like Voxtavium, whose own chunk lighting separately drawn water
  would not match (`Draw Simulated Water In Chunks` in the client config) simulated water is
  drawn by the chunks instead: while a chunk redraws, empty blocks holding simulated water read as water
  blocks of the same level (`ChunkWater`). Water moving then makes its chunks redraw, which costs more. Simulated
  water is no block to the light engine, so while chunks are drawn, sky light in and under it is dimmed like water
  blocks dim it (`ChunkLight`), only for drawing.
* **Buckets**. Vanilla buckets scoop up full water blocks and pour them back, which then flow. Aimed at
  partly filled water, a bucket gathers a block of water from the surface around it (`BucketHandler`), an
  eighth at a time from the highest blocks, and only fills when there is a whole block to take, so no water
  is created and the vanilla infinite water source no longer works. Poured into water, a bucket adds a block
  of water on top of it.
* **Explosions**. Water still being simulated stops explosions like water blocks do (`ExplosionMixin`), so TNT
  going off in it does not harm the blocks around it.
* **Pumps, tanks and fluid containers** of other mods that use Forge's `FluidUtil.getFluidHandler` take and
  add Fluidic water an eighth of a block (125 mB) at a time, partly filled blocks and water still being
  simulated included (`WaterFluidHandlers`). Forge's own handler only takes and places whole source blocks.
  Mods that check blocks themselves instead still only see water blocks.
* **Fountains and drains**. Two blocks for building with water. A fountain faces the way it was placed, like a
  dispenser, and while it gets a redstone signal it pours water out of its front, 1 block per second by default
  (`Fountain Output`). It pours into the pool in front of it from the top. A drain takes in water touching it
  like a sponge that never fills, pools drain into it.
* **Vanilla water dimensions**. Dimensions listed in `Vanilla Water Dimensions` keep vanilla water: nothing is
  simulated, water flows and makes new sources as in vanilla, and buckets, lily pads, boats and rendering behave
  the vanilla way. Clients learn the list when they join. A powered fountain there places a water source in front
  of it, removed again when the signal stops, and a drain deletes the water touching it.
* **Lily pads**. Lily pads float on water more than half full, simulated water too, and follow the water
  level (`LilyPads`): water rising into a pad lifts it, and a pad whose water went down sinks to the new
  surface up to 4 blocks below. They only break when there is no water near enough.
* **Sponges**. A dry sponge soaks up water touching it, partly filled blocks and flowing water included,
  and pools drain into it. Once it holds the configured amount (16 blocks by default) it turns wet. A
  sponge touching an ocean or river fills up at once.
* **Hand sponges**. A sponge held in the hand (`ItemHandSponge`, 4 from a dry sponge) soaks up puddles and the
  surface of pools it is used on, partly filled and simulated water included, an eighth at a time like a bucket,
  until it holds a block of water and is soaked. Crafted with an empty bucket, a soaked sponge is wrung out into
  a water bucket and stays in the grid dry.
* **The void**. Water falling below the bottom of the world, out of the End or through a hole in the bedrock, is gone,
  as vanilla water is.
* **Persistence**. Water still moving when the world saves is written to
  `<dimension>/data/fluidic_water.dat`. Settled water is saved as ordinary blocks.

## Commands

* `/fluidic stats` shows step time, active sections and wet cells.
* `/fluidic inspect <x> <y> <z>` shows how the simulation sees one block.
* `/fluidic add <x> <y> <z> <blocks> [radius]` pours water into the simulation.

## Known limitations

* Pressure comes from slight compression of deep water, which only pushes a little each step. While water
  moves slowly, the surfaces joined through each body of full water are also levelled directly a little at a
  time (communicating vessels), so U-bends rise steadily and end level. Bodies larger than 16384 cells are not
  levelled this way and still equalize slowly.
* Mixins are loaded through FermiumBooter in two configs. `mixins.fluidic.vanilla.json` is always loaded by
  the coremod (`FluidicPlugin`): rendering, buckets, lily pads, sponges, fluid handlers and world generation.
  `mixins.fluidic.entity.json` is switched by `Enable Flowing Water Entity Physics`: entities swimming in,
  being carried by (harder with `Vanilla Water Push Strength`, as hard as vanilla flowing water wherever it flows) and drowning in water that is still moving, boats, mobs pathing around it, fishing in it,
  and farmland, concrete powder and sugar cane next to it. Water mobs like squid spawn in full water only,
  settled or still moving, not in partly filled water. Turned off, only settled water affects these, and water
  mobs spawn in any water as in vanilla.
* Fences, walls, doors and other partial blocks that block movement are solid unless listed as permeable
  in `Block Classifications`.
* Doors not listed in `Block Classifications` let water flow through them, but never across the side the door is
  on, open or closed: a closed door holds water back, and water in a door against its closed side stays in it.
* Glass panes are `thin` (`ThinBlockWater`): they hold back water like a wall, and water beside one is drawn reaching
  into its block up to the glass, on each side separately, instead of stopping at the edge of the block. Other blocks
  can be listed as thin too, like walls: panes, walls, fences and blocks of other mods that join their neighbours
  like them are drawn as a post with arms, other blocks by their bounding box. A thin block joining two or more blocks
  beside it blocks water across its whole block, water beside it is only drawn reaching in. One joining at most one,
  like a pane standing alone as a pole or at the end of a row, lets water flow through it like a ladder
  (`Terrain.passesWater`).

## Fluidlogged API

Fluidlogged API is optional. With it installed (`compat/FluidloggedCompat`):

* Water that settles inside a block Fluidlogged API can fluidlog, like a ladder, sign or stairs, is kept there as its
  fluid state, not as simulated water. Its fluid states of Fluidic water are settled water to the simulation, and
  are woken back into it when something nearby changes, leaving the block in place. Water it keeps inside a block
  that `Block Classifications` does not let water into, like stairs without a `waterloggable` entry, stays sealed
  there, as poured in with a bucket. Fluidlogged API draws it.
* A block placed into water that Fluidlogged API fluidlogs keeps the water inside, nothing is pushed out of it.
* Its buckets and other Forge fluid containers pour into a pool on top of the water there (`FluidUtilMixin`).
* It draws water with Forge's fluid model. Fluidic's corner heights are handed to it (`getExtendedState`), so water
  looks the same either way, apart from side faces between water at different heights, which reach down to the
  bottom of the block.

## Building

```
./gradlew build
```

Gradle must run on Java 17+. The mod itself is compiled with a Java 8 toolchain. Fluidic compiles against Fluidlogged
API, dev runs load it with `./gradlew -Pfluidlogged runClient`.
