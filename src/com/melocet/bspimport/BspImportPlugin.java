package com.melocet.bspimport;

import com.melocet.bspimport.bsp.BspFile;
import com.melocet.bspimport.bsp.EntityLogic;
import com.melocet.bspimport.bsp.MapData;
import com.melocet.bspimport.bsp.SourceBsp;
import com.melocet.bspimport.bsp.WadLibrary;
import com.melocet.bspimport.convert.BlockPalette;
import com.melocet.bspimport.convert.PatternPicker;
import com.melocet.bspimport.convert.Placer;
import com.melocet.bspimport.convert.PropBuilder;
import com.melocet.bspimport.convert.PropSet;
import com.melocet.bspimport.game.GameFiles;
import com.melocet.bspimport.game.MaterialColors;
import com.melocet.bspimport.convert.VoxelGrid;
import com.melocet.bspimport.convert.Voxelizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class BspImportPlugin extends JavaPlugin implements org.bukkit.event.Listener {

    private Path mapsDir;
    private Path wadsDir;
    /** Game content dropped into the plugin folder: VPKs, loose models/ and materials/, or whole game folders. */
    private Path contentDir;
    private WadLibrary wads;
    private Placer running;
    private String runningFor;
    /** The last build, finished or not, kept so /bsp undo can take it back. */
    private Placer lastImport;
    private String lastImportName;
    private boolean undoing;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mapsDir = getDataFolder().toPath().resolve("maps");
        wadsDir = getDataFolder().toPath().resolve("wads");
        contentDir = getDataFolder().toPath().resolve("content");
        try {
            Files.createDirectories(mapsDir);
            Files.createDirectories(wadsDir);
            Files.createDirectories(contentDir);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't create folders", e);
        }
        reload();
        purged.addAll(YamlConfiguration.loadConfiguration(purgedFile()).getStringList("tags"));
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** Tags of undone imports: their displays in chunks that weren't loaded at undo go when the chunk loads. */
    private final Set<String> purged = new HashSet<>();

    private File purgedFile() {
        return new File(getDataFolder(), "undone.yml");
    }

    @org.bukkit.event.EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        if (purged.isEmpty()) return;
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            if (!(e instanceof org.bukkit.entity.BlockDisplay)) continue;
            for (String t : e.getScoreboardTags()) {
                if (purged.contains(t)) {
                    e.remove();
                    break;
                }
            }
        }
    }

    @Override
    public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new EmptyWorld();
    }

    @Override
    public void onDisable() {
        if (running != null) running.cancel();
    }

    private void reload() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        wads = WadLibrary.load(wadsDir, getLogger());
        getLogger().info("WAD textures: " + wads.textureCount() + " from " + wads.fileCount() + " file(s).");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "info" -> {
                if (args.length < 2) error(sender, "Usage: /bsp info <map>");
                else showInfo(sender, args[1]);
            }
            case "import" -> {
                boolean confirm = List.of(args).subList(1, args.length).stream().anyMatch(a -> a.equalsIgnoreCase("confirm"));
                if (!(sender instanceof Player p)) {
                    // Console: /bsp import <map> <scale> <world> <x> <y> <z> [confirm]
                    World w = args.length >= 7 ? Bukkit.getWorld(args[3]) : null;
                    if (w == null) {
                        error(sender, "From the console: /bsp import <map> <scale> <world> <x> <y> <z>");
                        return true;
                    }
                    try {
                        startImport(sender, args[1], Double.parseDouble(args[2]),
                                new Location(w, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6])), confirm);
                    } catch (NumberFormatException e) {
                        error(sender, "Scale and coordinates have to be numbers.");
                    }
                } else if (args.length < 2) {
                    error(p, "Usage: /bsp import <map> [scale]");
                } else {
                    Double scale = null;
                    for (int i = 2; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("confirm")) continue;
                        try {
                            scale = Double.parseDouble(args[i]);
                        } catch (NumberFormatException e) {
                            error(p, "Scale is a number of CS units per block, like 32.");
                            return true;
                        }
                    }
                    startImport(p, args[1], scale, p.getLocation(), confirm);
                }
            }
            case "undo" -> undo(sender);
            case "cancel" -> {
                if (running == null) {
                    error(sender, "Nothing is being built.");
                } else {
                    running.cancel();
                    running = null;
                    runningFor = null;
                    info(sender, "Stopped. The blocks placed so far stay; /bsp undo removes them.");
                }
            }
            case "reload" -> {
                reload();
                info(sender, "Config and WADs reloaded (" + wads.textureCount() + " textures).");
            }
            default -> help(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("list", "info", "import", "undo", "cancel", "reload", "help"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("import"))) out.addAll(mapNames());
        else if (args.length == 3 && args[0].equalsIgnoreCase("import")) out.addAll(List.of("32", "24", "48"));
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    // ---------------------------------------------------------------- commands

    private void help(CommandSender s) {
        info(s, "Build CS 1.6 and Source maps out of blocks");
        line(s, "/bsp list", "the .bsp files in plugins/BspImport/maps");
        line(s, "/bsp info <map>", "size, textures, spawns, before building");
        line(s, "/bsp import <map> [scale]", "build it centered on you (use an empty world)");
        line(s, "/bsp undo", "take the last import back out, restoring what was there");
        line(s, "/bsp cancel · /bsp reload", "stop building, reload config and WADs");
    }

    private void list(CommandSender s) {
        List<String> names = mapNames();
        if (names.isEmpty()) {
            info(s, "No maps. Put .bsp files in plugins/BspImport/maps/.");
            return;
        }
        info(s, names.size() + " map(s): " + String.join(", ", names));
    }

    private List<String> mapNames() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(mapsDir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".bsp"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted().forEach(names::add);
        } catch (IOException ignored) {
            // empty list
        }
        return names;
    }

    private Path mapFile(String name) {
        Path p = mapsDir.resolve(name + ".bsp").normalize();
        return p.startsWith(mapsDir) && Files.isRegularFile(p) ? p : null;
    }

    private void showInfo(CommandSender s, String name) {
        Path file = mapFile(name);
        if (file == null) {
            error(s, "No " + name + ".bsp in plugins/BspImport/maps/.");
            return;
        }
        double scale = getConfig().getDouble("scale", 32);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                MapData bsp = load(file);
                Voxelizer vox = new Voxelizer(bsp, options(scale, bsp));
                int[] size = vox.size();
                int embedded = 0, fromWad = 0;
                List<String> missing = new ArrayList<>();
                String[] names = bsp.textureNames();
                for (int i = 0; i < names.length; i++) {
                    if (bsp.textureColors()[i] >= 0) embedded++;
                    else if (wads.color(names[i]) >= 0) fromWad++;
                    else missing.add(names[i]);
                }
                Map<String, Integer> classes = new TreeMap<>();
                int ct = 0, t = 0;
                String wadKey = "";
                for (Map<String, String> e : bsp.entities()) {
                    String cls = e.getOrDefault("classname", "?");
                    if (cls.equals("worldspawn")) wadKey = e.getOrDefault("wad", "");
                    if (bsp.humanSpawnClasses().contains(cls)) ct++;
                    if (bsp.zombieSpawnClasses().contains(cls)) t++;
                    if (e.getOrDefault("model", "").startsWith("*")) classes.merge(cls, 1, Integer::sum);
                }
                String terrainLine = bsp.extraSurfaces().isEmpty() ? null : "Terrain: " + bsp.extraSurfaces().size() + " triangles.";
                List<String> wadNames = new ArrayList<>();
                for (String w : wadKey.split(";")) {
                    if (w.isBlank()) continue;
                    String n = w.replace('\\', '/');
                    wadNames.add(n.substring(n.lastIndexOf('/') + 1));
                }
                String sizeLine = bsp.engine() + " map. Size at " + (int) scale + " units/block: up to " + size[0] + " × " + size[2] + " blocks, " + size[1] + " tall.";
                String texLine = "Textures: " + names.length + " (" + embedded + " with a known color, " + fromWad
                        + " from WADs, " + missing.size() + " unknown → name rules or " + getConfig().getString("fill-block") + ").";
                String spawnLine = "Spawns: " + ct + " human (CT), " + t + " zombie (T).";
                String wadLine = wadNames.isEmpty() ? null : "WADs it uses: " + String.join(", ", wadNames);
                String entLine = classes.isEmpty() ? null : "Brush entities: " + classes;
                String missLine = missing.isEmpty() ? null
                        : "Unknown textures: " + String.join(", ", missing.subList(0, Math.min(12, missing.size()))) + (missing.size() > 12 ? ", ..." : "");
                Bukkit.getScheduler().runTask(this, () -> {
                    info(s, name + ".bsp");
                    for (String l : new String[]{sizeLine, texLine, spawnLine, terrainLine, wadLine, entLine, missLine}) {
                        if (l != null) s.sendMessage(Component.text("  " + l, NamedTextColor.GRAY));
                    }
                });
            } catch (IOException | RuntimeException e) {
                Bukkit.getScheduler().runTask(this, () -> error(s, "Couldn't read " + name + ".bsp: " + e.getMessage()));
            }
        });
    }

    private void undo(CommandSender s) {
        if (lastImport == null) {
            error(s, "Nothing to undo since the server started.");
            return;
        }
        if (undoing) {
            error(s, "Already undoing.");
            return;
        }
        if (running != null) {
            running.cancel();
            running = null;
            runningFor = null;
        }
        undoing = true;
        String name = lastImportName;
        if (lastImport.displays() > 0) {
            purged.add(lastImport.tag());
            YamlConfiguration y = new YamlConfiguration();
            y.set("tags", new ArrayList<>(purged));
            try {
                y.save(purgedFile());
            } catch (IOException e) {
                getLogger().warning("Couldn't save undone.yml: " + e.getMessage());
            }
        }
        info(s, "Taking " + name + " back out (" + lastImport.placed() + " blocks)...");
        int[] shown = {0};
        lastImport.undo(percent -> {
            if (percent >= shown[0] + 25) {
                shown[0] = percent;
                info(s, "Undo: " + percent + "%");
            }
        }, () -> {
            undoing = false;
            lastImport = null;
            lastImportName = null;
            info(s, "Undone: " + name + " is gone and whatever was there before is back.");
        }).runTaskTimer(this, 1L, 1L);
    }

    private void startImport(CommandSender p, String name, Double scaleArg, Location at, boolean confirm) {
        if (undoing) {
            error(p, "Wait for the undo to finish.");
            return;
        }
        World target = at.getWorld();
        if (!confirm && target.equals(Bukkit.getWorlds().get(0))) {
            error(p, "That's the main world (" + target.getName() + "). Maps are best built in an empty world "
                    + "(/mv create <name> normal -g BspImport makes an empty one). To build here anyway: /bsp import " + name + " confirm");
            return;
        }
        if (running != null) {
            error(p, "Already building " + runningFor + ". /bsp cancel to stop it.");
            return;
        }
        Path file = mapFile(name);
        if (file == null) {
            error(p, "No " + name + ".bsp in plugins/BspImport/maps/.");
            return;
        }
        double scale = scaleArg != null ? scaleArg : getConfig().getDouble("scale", 32);
        if (scale < 8 || scale > 128) {
            error(p, "Scale has to be between 8 and 128 units per block.");
            return;
        }
        FileConfiguration c = getConfig();
        Material fill = material(c.getString("fill-block"), Material.STONE);
        Material sky = material(c.getString("sky-block"), Material.BARRIER);
        int perTick = Math.max(500, c.getInt("blocks-per-tick", 20000));
        World world = at.getWorld();
        runningFor = name;
        running = null;
        info(p, "Reading " + name + ".bsp and cutting it into blocks...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            VoxelGrid grid;
            BlockPalette.Style[] blocks;
            BlockPalette.Style[][] variants;
            BlockPalette.Style fillStyle;
            try {
                MapData bsp = load(file);
                boolean source = bsp instanceof SourceBsp;
                List<String> rules = new ArrayList<>();
                if (source) rules.addAll(c.getStringList("source-texture-rules"));
                rules.addAll(c.getStringList("texture-rules"));
                BlockPalette palette = new BlockPalette(rules, fill, getLogger());
                fillStyle = palette.style("", -1, BlockPalette.SeeThrough.NONE);
                GameFiles files = source ? openGameFiles(bsp) : null;
                MaterialColors materials = files == null ? null : new MaterialColors(files);
                java.util.function.Predicate<Map<String, String>> keep = keepFilter(bsp);
                boolean displays = c.getBoolean("props", true) && c.getBoolean("display-props", true);
                double propMin = Math.max(0, c.getDouble("prop-min-size", 12));
                PropSet props = buildProps(bsp, files, keep, displays ? Math.min(propMin, 4) : propMin, p);
                String[] names = bsp.textureNames();
                // Map textures first, then the props' materials, matching the indexes the voxelizer used.
                boolean shapes = c.getBoolean("shapes", true);
                blocks = new BlockPalette.Style[names.length + props.materialNames.size()];
                for (int i = 0; i < names.length; i++) {
                    int color = bsp.textureColors()[i] >= 0 ? bsp.textureColors()[i] : wads.color(names[i]);
                    int flags = materials == null ? 0 : materials.seeThrough("materials/" + names[i]);
                    blocks[i] = styleOf(palette, names[i], color, flags, shapes, false);
                }
                for (int i = 0; i < props.materialNames.size(); i++) {
                    String material = props.materialNames.get(i).split("\\|")[0].replaceFirst("^materials/", "");
                    blocks[names.length + i] = styleOf(palette, material, props.materialColors.get(i), props.materialFlags.get(i), shapes, true);
                }
                PatternPicker patterns = null;
                if (c.getBoolean("patterns", true)) {
                    java.util.function.IntFunction<com.melocet.bspimport.bsp.TextureImage> images = source
                            ? t -> materials == null ? null : materials.image("materials/" + names[t])
                            : t -> bsp.textureImage(t) != null ? bsp.textureImage(t) : wads.image(names[t]);
                    patterns = new PatternPicker(bsp, images, palette, java.util.Arrays.copyOf(blocks, names.length), shapes,
                            Math.max(1, c.getInt("pattern-contrast", 30)));
                }
                Voxelizer.Lights lights = c.getBoolean("lights", true)
                        ? new Voxelizer.Lights(true, pattern(c.getString("light-textures", "$^")), Math.max(1, Math.min(15, c.getInt("texture-light-level", 12))))
                        : null;
                Voxelizer.Displays shown = displays
                        ? new Voxelizer.Displays(Math.max(0.25, c.getDouble("display-max-size", 2)), Math.max(1, c.getInt("display-parts", 6)),
                                Math.max(0, c.getInt("display-limit", 8000)), propMin)
                        : null;
                int[] lastShown = {0};
                grid = new Voxelizer(bsp, options(scale, bsp)).run(percent -> {
                    if (percent >= lastShown[0] + 25) {
                        lastShown[0] = percent;
                        Bukkit.getScheduler().runTask(this, () -> info(p, "Reading the map: " + percent + "%"));
                    }
                }, new Voxelizer.Extras(props, pattern(c.getString("metal-door-names", "metal|steel|iron")),
                        Math.max(1, c.getInt("door-max-width", 2)), keep, seeThroughTextures(bsp, materials), patterns, lights, shown));
                variants = patterns == null ? new BlockPalette.Style[0][] : patterns.variants();
            } catch (IOException | RuntimeException e) {
                getLogger().log(Level.WARNING, "Import of " + name + " failed", e);
                Bukkit.getScheduler().runTask(this, () -> {
                    runningFor = null;
                    error(p, "Couldn't read " + name + ".bsp: " + e.getMessage());
                });
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> build(p, name, grid, blocks, variants, fillStyle, sky, world, at, perTick));
        });
    }

    /**
     * Glass stays glass and grates become bars, whatever their color. Slivers turn into fences and
     * walls only on props (table legs, posts): on the map's own walls a sliver is just the edge of a
     * wall reaching into the next block, and posts there would look like noise. shapes: false keeps
     * whole blocks only.
     */
    private static final Pattern FOLIAGE = Pattern.compile("tree|pine|shrub|bush|hedge|plant|foliage|leaf|leaves|ivy|vine|fern", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRATE_NAMES = Pattern.compile("grate|fence|chain|mesh|gate|bar(?!rel|n|ricade)|rail|wire|cage|grill", Pattern.CASE_INSENSITIVE);
    private static final Pattern GLASS_NAMES = Pattern.compile("glass|window|windshield", Pattern.CASE_INSENSITIVE);

    private static BlockPalette.Style styleOf(BlockPalette palette, String name, int color, int flags, boolean shapes, boolean prop) {
        // A see-through flag only says the material has holes; trees, bushes, hay and clothes have them
        // too. Leaves for plants, bars and panes only when the name says fence or glass.
        if (FOLIAGE.matcher(name).find()) {
            Material leaves = name.toLowerCase(java.util.Locale.ROOT).contains("pine") ? Material.SPRUCE_LEAVES : Material.OAK_LEAVES;
            return new BlockPalette.Style(leaves, null, null, null);
        }
        BlockPalette.SeeThrough see = (flags & MaterialColors.TRANSLUCENT) != 0 && GLASS_NAMES.matcher(name).find() ? BlockPalette.SeeThrough.GLASS
                : (flags & MaterialColors.ALPHATEST) != 0 && GRATE_NAMES.matcher(name).find() ? BlockPalette.SeeThrough.GRATE : BlockPalette.SeeThrough.NONE;
        BlockPalette.Style st = palette.style(name, color, see);
        boolean seeThrough = st.full() == Material.IRON_BARS || st.thin() != null && st.thin().name().endsWith("_PANE");
        Material thin = prop || seeThrough ? st.thin() : null;
        if (!shapes) return new BlockPalette.Style(st.full(), null, null, st.full() == Material.IRON_BARS ? Material.IRON_BARS : null);
        return new BlockPalette.Style(st.full(), st.slab(), st.stairs(), thin);
    }

    private void build(CommandSender p, String name, VoxelGrid g, BlockPalette.Style[] blocks, BlockPalette.Style[][] variants, BlockPalette.Style fill,
                       Material sky, World world, Location at, int perTick) {
        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        if (g.ny > max - min) {
            runningFor = null;
            error(p, "The map is " + g.ny + " blocks tall; the world only has " + (max - min) + ". Try a bigger scale.");
            return;
        }
        int ox = at.getBlockX() - g.nx / 2;
        int oz = at.getBlockZ() - g.nz / 2;
        int oy = Math.max(min, Math.min(max - g.ny, at.getBlockY()));
        int solid = g.count(VoxelGrid.SOLID) + g.count(VoxelGrid.SKY) + g.count(VoxelGrid.INVISIBLE)
                + g.count(VoxelGrid.WATER) + g.count(VoxelGrid.LADDER);
        info(p, "Building " + name + ": " + g.nx + " × " + g.nz + " blocks, " + g.ny + " tall, about " + solid + " blocks.");
        FileConfiguration c = getConfig();
        running = new Placer(g, blocks, variants, fill, sky, doorMaterial(c.getString("wood-door"), Material.OAK_DOOR),
                doorMaterial(c.getString("metal-door"), Material.WAXED_WEATHERED_COPPER_DOOR), world, ox, oy, oz, perTick,
                "bspimport-" + Long.toHexString(new java.util.Random().nextLong() & 0xFFFFFFFFFFL),
                percent -> info(p, "Building " + name + ": " + percent + "%"),
                () -> finished(p, name, g, world, ox, oy, oz));
        lastImport = running;
        lastImportName = name;
        running.runTaskTimer(this, 1L, 1L);
    }

    private void finished(CommandSender p, String name, VoxelGrid g, World world, int ox, int oy, int oz) {
        int placed = running.placed();
        int shownProps = running.displays();
        running = null;
        runningFor = null;
        List<Location> ct = locations(g.ctSpawns, world, ox, oy, oz);
        List<Location> t = locations(g.tSpawns, world, ox, oy, oz);
        double cx = ox + g.nx / 2.0;
        double cz = oz + g.nz / 2.0;
        double size = Math.max(g.nx, g.nz) + 4;
        saveImport(world, ct, t, cx, cz, size);
        String done = "Built " + name + " (" + placed + " blocks, " + g.count(VoxelGrid.LIGHT) + " lights, " + shownProps
                + " small prop parts). Spawns in the map: " + ct.size() + " CT, " + t.size() + " T.";
        info(p, done);
        if (p instanceof Player) getLogger().info(done);
        if (!ct.isEmpty() && p instanceof Player player && player.isOnline()) player.teleport(ct.get(0));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Props need the game's models: the configured game folders plus whatever the map packs. Doors
     * work without them (brush doors); model doors and furniture don't.
     */
    /** The game's content for a Source map: configured folders plus what the map packs. Null when none. */
    /** models/ and materials/ are content itself, not a game's folder. */
    private static boolean isContentSubfolder(Path d) {
        String n = d.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.equals("models") || n.equals("materials");
    }

    private GameFiles openGameFiles(MapData map) {
        List<Path> folders = new ArrayList<>();
        // plugins/BspImport/content and each folder directly in it (one per game, say)
        folders.add(contentDir);
        try (Stream<Path> inside = Files.list(contentDir)) {
            inside.filter(Files::isDirectory).filter(d -> !isContentSubfolder(d)).sorted().forEach(folders::add);
        } catch (IOException ignored) {
            // no content folder, only game-folders
        }
        for (String s : getConfig().getStringList("game-folders")) {
            if (!s.isBlank()) folders.add(Path.of(s.trim()));
        }
        GameFiles files = GameFiles.open(folders, map.packedFiles(), getLogger());
        return files.isEmpty() ? null : files;
    }

    /**
     * Which brush entities and props go in. Left out: things the round starts without (disabled,
     * never solid, invisible), things the map's logic switches while playing (random barricades,
     * blocked doorways) when skip-switched is on, and names matching skip-names.
     */
    private java.util.function.Predicate<Map<String, String>> keepFilter(MapData map) {
        FileConfiguration c = getConfig();
        EntityLogic logic = new EntityLogic(map.entities());
        boolean skipSwitched = c.getBoolean("skip-switched", true);
        Pattern names = pattern(c.getString("skip-names", "$^"));
        return e -> {
            if (EntityLogic.startsHidden(e)) return false;
            if (skipSwitched && logic.isSwitched(e)) return false;
            String name = e.getOrDefault("targetname", "");
            return name.isEmpty() || !names.matcher(name).find();
        };
    }

    /** Map textures that are see-through: from the material files when there are any, else by name. */
    private java.util.BitSet seeThroughTextures(MapData map, MaterialColors materials) {
        java.util.BitSet out = new java.util.BitSet();
        // The material flags say "see-through", the name says what kind: puddles, decals and plants
        // are see-through too, and must not turn into panes and bars.
        Pattern byName = Pattern.compile("^[{]|grate|fence|chainlink|chain_link|glass|window", Pattern.CASE_INSENSITIVE);
        Pattern kinds = Pattern.compile("glass|window|bar|rail|fence|grate|grill|chain|wire|mesh|cage", Pattern.CASE_INSENSITIVE);
        Pattern notThese = Pattern.compile("puddle|water|decal|overlay|blood|graffiti|leaf|leaves|foliage|grass|plant|tree|mirror|trim", Pattern.CASE_INSENSITIVE);
        String[] names = map.textureNames();
        for (int i = 0; i < names.length; i++) {
            if (names[i].startsWith("tools/") || notThese.matcher(names[i]).find()) continue;
            boolean flagged = materials != null && materials.seeThrough("materials/" + names[i]) != 0 && kinds.matcher(names[i]).find();
            if (flagged || byName.matcher(names[i]).find()) out.set(i);
        }
        return out;
    }

    private PropSet buildProps(MapData map, GameFiles files, java.util.function.Predicate<Map<String, String>> keep, double minSize, CommandSender p) {
        FileConfiguration c = getConfig();
        boolean wantProps = c.getBoolean("props", true);
        boolean wantDoors = c.getBoolean("doors", true);
        if (!wantProps && !wantDoors) return PropSet.empty();
        List<Pattern> skip = new ArrayList<>();
        for (String s : c.getStringList("prop-skip")) {
            Pattern pt = pattern(s);
            if (pt != null) skip.add(pt);
        }
        PropSet set = PropBuilder.build(map, files, new PropBuilder.Options(wantProps,
                minSize, skip, c.getBoolean("nonsolid-props", true), wantDoors, keep));
        String doorLine = set.doors.size() + " door(s)";
        String line;
        if (map.staticProps().isEmpty() && set.instances.isEmpty()) {
            line = "Doors: " + doorLine + ".";
        } else if (files == null) {
            line = "Doors: " + doorLine + ". Furniture and other props skipped: the game's files aren't set up (put them in plugins/BspImport/content/ or set game-folders in config.yml).";
        } else {
            line = "Props: " + set.placed + " placed, " + set.skipped + " skipped (tiny or filtered), " + set.missingModels
                    + " whose model isn't in the game files. " + doorLine + ".";
            if (!set.missingNames.isEmpty()) getLogger().info("Missing prop models: " + String.join(", ", set.missingNames));
        }
        Bukkit.getScheduler().runTask(this, () -> info(p, line));
        return set;
    }

    private Pattern pattern(String regex) {
        try {
            return Pattern.compile(regex == null ? "$^" : regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            getLogger().warning("Bad regular expression in config: " + regex);
            return Pattern.compile("$^");
        }
    }

    private Material doorMaterial(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null && m.createBlockData() instanceof org.bukkit.block.data.type.Door ? m : fallback;
    }

    /** Reads a CS 1.6 (GoldSrc) or a Source 1 map, told apart by the first bytes. */
    private static MapData load(Path file) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, 4) < 4) throw new IOException("File too short");
        }
        return SourceBsp.isSource(head) ? SourceBsp.read(file) : BspFile.read(file);
    }

    private Voxelizer.Options options(double scale, MapData map) {
        FileConfiguration c = getConfig();
        String prefix = map instanceof SourceBsp ? "source-" : "";
        return new Voxelizer.Options(scale,
                Math.max(1, Math.min(6, c.getInt("samples", 3))),
                Math.max(0.01, Math.min(1, c.getDouble("solid-threshold", 0.25))),
                Math.max(1, Math.min(8, c.getInt("shell", 2))),
                Math.max(0, Math.min(8, c.getInt("terrain-fill", 3))),
                lower(c.getStringList(prefix + "solid-entities")),
                lower(c.getStringList(prefix + "water-entities")),
                lower(c.getStringList(prefix + "ladder-entities")));
    }

    private static Set<String> lower(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null && m.isBlock() ? m : fallback;
    }

    private static List<Location> locations(List<VoxelGrid.Spawn> spawns, World w, int ox, int oy, int oz) {
        List<Location> out = new ArrayList<>();
        for (VoxelGrid.Spawn s : spawns) out.add(new Location(w, ox + s.x(), oy + s.y(), oz + s.z(), s.yaw(), 0));
        return out;
    }

    private File importsFile() {
        return new File(getDataFolder(), "imports.yml");
    }

    private void saveImport(World w, List<Location> ct, List<Location> t, double cx, double cz, double size) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(importsFile());
        String key = w.getName();
        y.set(key + ".ct", ct.stream().map(BspImportPlugin::writeLocation).toList());
        y.set(key + ".t", t.stream().map(BspImportPlugin::writeLocation).toList());
        y.set(key + ".center-x", cx);
        y.set(key + ".center-z", cz);
        y.set(key + ".size", size);
        try {
            y.save(importsFile());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't save imports.yml", e);
        }
    }

    private static String writeLocation(Location l) {
        return String.format(Locale.ROOT, "%.2f;%.2f;%.2f;%.1f", l.getX(), l.getY(), l.getZ(), l.getYaw());
    }

    private void line(CommandSender s, String cmd, String what) {
        s.sendMessage(Component.text(" " + cmd, NamedTextColor.WHITE).append(Component.text("  " + what, NamedTextColor.GRAY)));
    }

    private void info(CommandSender s, String text) {
        s.sendMessage(Component.text("[BspImport] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.GRAY)));
    }

    private void error(CommandSender s, String text) {
        s.sendMessage(Component.text("[BspImport] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.RED)));
    }
}
