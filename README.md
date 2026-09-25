# BspImport

A Paper plugin that builds Counter-Strike 1.6 and Source 1 maps out of Minecraft blocks.

Drop a `.bsp` file into the plugin folder, stand in an empty world, run `/bsp import <map>`, and the
map is built around you: walls, floors, stairs, ladders, water, doors, glass, fences, props and
lights.

| CS 1.6 (GoldSrc) | Source |
| --- | --- |
| [![BspImport building a CS 1.6 map](https://img.youtube.com/vi/IT6ONVaogBU/hqdefault.jpg)](https://www.youtube.com/watch?v=IT6ONVaogBU) | [![BspImport building a Source map](https://img.youtube.com/vi/91sLvlXdp-M/hqdefault.jpg)](https://www.youtube.com/watch?v=91sLvlXdp-M) |

## Supported maps

- **GoldSrc** (BSP v30): Counter-Strike 1.6, Half-Life, Day of Defeat and other GoldSrc mods.
- **Source 1** (VBSP v19 to v29): CS:S, CS:GO, HL2, Garry's Mod, Left 4 Dead 2, Contagion and other
  Source games that use the standard lump layout.

Source 2 maps are not supported.

## What it builds

- **Brushes** become full blocks, slabs and stairs, depending on how much of each block the wall fills.
- **Textures** pick blocks by name and color. Glass becomes glass and panes; grates, fences and
  railings become iron bars; trees and bushes become leaves. With `patterns` on, each wall block
  looks at the part of its texture it covers, so tile grout, stripes and signs show up.
- **Doors** (`func_door`, `func_door_rotating`, `prop_door_rotating`, and door models placed as
  plain props) become Minecraft doors that open by hand. Openings wider than `door-max-width` stay
  open.
- **Props** (Source only) are built from the game's own models. Small props (chairs, bottles,
  signs) and props you walk through (door frames, metal detectors) become scaled block displays, so
  they look right without blocking the way.
- **Lights**: light entities and glowing textures become invisible light blocks, so rooms are lit
  where the map lights them and dark where it doesn't.
- **Terrain**: Source displacements become ground with a few blocks of fill underneath.
- **Map logic**: things the round starts without (disabled walls, invisible blockers) and things the
  map switches while it's played (random barricades, walls that open) are left out, so passages
  stay open.
- **Spawns**: the map's spawn points are saved to `plugins/BspImport/imports.yml`, and you're
  teleported to one when the build finishes.

## Requirements

- Paper 26.3 or newer, Java 25.
- The maps themselves. BspImport ships no game content.
- For CS 1.6 texture colors: the game's `.wad` files (`cstrike.wad`, `halflife.wad`, ...). Maps
  usually only name their textures and keep the pixels in these.
- For Source props, doors and texture patterns: the game's files. Put its `*_dir.vpk` files (with
  their numbered parts) or loose `models/` and `materials/` folders in
  `plugins/BspImport/content/`, or point `game-folders` in `config.yml` at where the game is
  installed. Nothing needs extracting; files are read straight from the VPKs.

## Install

1. Put `BspImport-<version>.jar` in `plugins/` and start the server once.
2. Put `.bsp` files in `plugins/BspImport/maps/`.
3. For CS 1.6 maps, put the game's `.wad` files in `plugins/BspImport/wads/`.
4. For Source maps, put the game's VPKs (or `models/` and `materials/`) in
   `plugins/BspImport/content/`. One folder per game inside it is fine too:

   ```
   plugins/BspImport/content/
     contagion/
       materials_dir.vpk
       materials_000.vpk
       models_dir.vpk
       models_000.vpk
       ...
     cstrike/
       models/
       materials/
   ```

   Or leave the game where it is and set `game-folders` in `config.yml`.

An empty world works best. BspImport has a void generator for that:

```
/mv create maps normal -g BspImport
```

## Commands

All commands need `bspimport.use` (ops by default).

| Command | What it does |
| --- | --- |
| `/bsp list` | The maps in `plugins/BspImport/maps/` |
| `/bsp info <map>` | Size, textures, spawns and brush entities, before building |
| `/bsp import <map> [scale]` | Build the map centered on you |
| `/bsp undo` | Take the last import back out and restore what was there |
| `/bsp cancel` | Stop a build in progress |
| `/bsp reload` | Reload the config and the WADs |

Imports into the server's main world ask for `confirm` at the end of the command first.

From the console: `/bsp import <map> <scale> <world> <x> <y> <z> [confirm]`.

## Scale

`scale` is map units per block. The default of 32 makes a player 2.25 blocks tall and a standard
64-unit door 2 blocks wide. 24 builds the map a third bigger with thinner walls and more detail. 48
builds it smaller and rougher.

## Configuration

Everything is in `plugins/BspImport/config.yml`, with a comment on each setting. The ones most worth
knowing:

| Setting | Default | |
| --- | --- | --- |
| `scale` | `32` | Map units per block |
| `shapes` | `true` | Slabs, stairs, fences and panes where a block is only partly filled |
| `game-folders` | `[]` | Game folders outside the plugin folder, besides `content/` |
| `props` | `true` | Build Source props |
| `display-props` | `true` | Small and walk-through props as block displays |
| `lights` | `true` | Light blocks at the map's lights |
| `patterns` | `true` | Wall blocks follow their texture's pattern |
| `pattern-contrast` | `30` | Higher keeps more walls plain |
| `skip-switched` | `true` | Leave out whatever the map's logic switches on and off |
| `texture-rules` | | Force a block for texture names matching a regular expression |

## Building from source

The build compiles against a Paper server's own libraries, so you need a Paper server folder that has
been started once:

```
PAPER_DIR=/path/to/paper-server ./build.sh
```

The JDK comes from `JAVA_HOME`, or from `PATH`. The jar lands next to `build.sh`.

`test/run.sh` builds two tiny hand-made maps (one GoldSrc, one Source) and voxelizes them without a
server.

## License

[Mozilla Public License 2.0](LICENSE).
