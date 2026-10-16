package dev.lazuli.core.command;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Setting;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import dev.lazuli.core.chat.Chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Baritone-style chat commands. Messages not starting with the configured prefix are left alone. */
public final class CommandHandler {
    public interface Context {
        /** Player feet block {x, y, z}. */
        int[] playerFeet();

        BlockView world();

        PathingSession session();

        Settings settings();

        void reply(String msg);

        void settingsChanged();

        /** Starts recording a golden trace; the reply says where it will be saved. */
        default void recordStart(String name) {
            reply(Chat.err("Recording isn't available here"));
        }

        default void recordStop() {
            reply(Chat.err("Recording isn't available here"));
        }

        /** Arms #click: the next left-click on a block sets the goal (see {@link CommandHandler#setGoal(Goal)}). */
        default void startClick() {
            reply(Chat.err("Clicking isn't available here"));
        }

        /** Opens the settings screen. */
        default void openConfig() {
            reply(Chat.err("No config screen here; use set <name> <value>"));
        }

        /**
         * Starts mining: find, walk to and break these blocks (ids like {@code diamond_ore} or
         * {@code minecraft:diamond_ore}) until {@code quantity} are broken, or every one in reach when it's 0.
         */
        default void mine(int quantity, List<String> blocks) {
            reply(Chat.err("Mining isn't available here"));
        }

        /** Stops whatever process (mining...) is running on top of pathing. */
        default void stopProcesses() {}

        /** Hard reset when something is stuck: every job and process, the planner thread, held keys and the view. */
        default void kill() {
            stopProcesses();
            session().kill();
            reply(Chat.ok("Killed everything; ready for new commands"));
        }

        /**
         * Follows entities: {@code mode} is players, entities, player (names in {@code args}) or entity (type ids in
         * {@code args}).
         */
        default void follow(String mode, List<String> args, int distance) {
            reply(Chat.err("Following isn't available here"));
        }

        /** Explores outwards from {x, z}, or from the player when null. */
        default void explore(int[] origin) {
            reply(Chat.err("Exploring isn't available here"));
        }

        /** Digs a straight tunnel the way the player faces; depth 0 means no end. */
        default void tunnel(int height, int width, int depth) {
            reply(Chat.err("Tunnelling isn't available here"));
        }

        /** Farms around the player; range 0 means the farmRange setting. */
        default void farm(int range) {
            reply(Chat.err("Farming isn't available here"));
        }

        /** Feeds animals in pairs so they breed; {@code kinds} entity ids (empty: any), {@code pairs} 0: as many as it can. */
        default void breed(List<String> kinds, int pairs) {
            reply(Chat.err("Breeding isn't available here"));
        }

        /**
         * #iceboat: {@code mode} "goal" (target x, y or MIN_VALUE, z), "lap" (n laps), "line" (race the recorded line),
         * "record" (name or null), "use" (a saved line by name, or "off").
         */
        default void iceboat(String mode, int[] target, int laps, String name) {
            reply(Chat.err("Ice boat racing isn't available here"));
        }

        /** Fishes at the nearest open water; minutes 0: until stopped. */
        default void fish(int minutes) {
            reply(Chat.err("Fishing isn't available here"));
        }

        /** Throws out items (ids like {@code dirt}, or "all" for everything); a negative count: all of it. */
        default void drop(List<ItemCount> items) {
            reply(Chat.err("Dropping isn't available here"));
        }

        /**
         * Moves items between the inventory and a container: {@code at} {x, y, z}, or null for the nearest storage.
         * Each item is an id or "all"; a negative count means all of it.
         */
        default void container(boolean deposit, List<ItemCount> items, int[] at) {
            reply(Chat.err("Containers aren't available here"));
        }

        /** Crafts items (ids like {@code stick}), their ingredients first if need be; a negative count: one craft. */
        default void craft(List<ItemCount> items) {
            reply(Chat.err("Crafting isn't available here"));
        }

        /** Smelts an item (what comes out, like iron_ingot, or what goes in); a negative count: all it can. */
        default void smelt(ItemCount item) {
            reply(Chat.err("Smelting isn't available here"));
        }

        /**
         * Gets items from scratch: mines, crafts and smelts what it takes, tools included; counts: how many to have. Then
         * brings them back (and puts them in a chest) if asked.
         */
        default void getItem(dev.lazuli.core.craft.ItemRequest request) {
            reply(Chat.err("Getting items isn't available here"));
        }

        /** Brews {@code count} bottles of a potion (fire_resistance, long_swiftness...) in a brewing stand. */
        default void brew(String potion, dev.lazuli.core.craft.Brewing.Form form, int count) {
            reply(Chat.err("Brewing isn't available here"));
        }

        /** Enchants {@code count} of an item at an enchanting table: {@code option} 1-3, or 0 for the best affordable. */
        default void enchant(String item, int count, int option) {
            reply(Chat.err("Enchanting isn't available here"));
        }

        /** Repairs an item at an anvil, or with {@code with} set, puts the two together (an enchanted book...). */
        default void anvil(String item, String with) {
            reply(Chat.err("Anvils aren't available here"));
        }

        /** #sort: sorts the chests and barrels within {@code range} blocks. */
        default void sort(int range) {
            reply(Chat.err("Sorting isn't available here"));
        }

        /** #sel clear: breaks every block in the box. */
        default void selClear(dev.lazuli.core.area.Selection.Box box) {
            reply(Chat.err("Building isn't available here"));
        }

        /** #sel fill: places {@code block} (an item id) in every empty spot of the box. */
        default void selFill(dev.lazuli.core.area.Selection.Box box, String block) {
            reply(Chat.err("Building isn't available here"));
        }

        /** Flies there with an elytra (y null: anywhere at x z). */
        default void fly(int x, Integer y, int z) {
            reply(Chat.err("Flying isn't available here"));
        }

        /** #portal [list]: through the nearest known portal (or one built here), or list the known ones. */
        default void portal(boolean list) {
            reply(Chat.err("Portals aren't available here"));
        }

        /** A goal far enough that the nether is quicker (netherGoto): true if it set off that way. */
        default boolean netherInstead(Goal goal) {
            return false;
        }

        /** A goal far enough to fly to instead (elytraGoto): true if it took off for it. */
        default boolean flyInstead(Goal goal) {
            return false;
        }

        /** Goes back to the furnaces it left cooking and takes what's done. */
        default void smeltCollect() {
            reply(Chat.err("Smelting isn't available here"));
        }

        /** The block the camera is in {x, y, z}: the freecam's when it's on, otherwise the player's feet. */
        default int[] cameraFeet() {
            return playerFeet();
        }

        /** Gets into a bed (the nearest, or one placed from the inventory) until morning. */
        default void sleep() {
            reply(Chat.err("Sleeping isn't available here"));
        }

        /** What's in the containers opened so far ({@code #find}); null when not kept. */
        default dev.lazuli.core.storage.ChestMemory chests() {
            return null;
        }

        /** Saved places ({@code #wp}); null when there are none here. */
        default dev.lazuli.core.waypoint.Waypoints waypoints() {
            return null;
        }

        /** The player's dimension (overworld, the_nether...), for waypoints. */
        default String dimension() {
            return "overworld";
        }

        /** An enchantment's id (minecraft:sharpness): #trade wants an enchanted book with it. */
        default boolean isEnchantment(String id) {
            return false;
        }

        /** Trades with villagers for these (selling things for emeralds first if need be). */
        default void trade(List<dev.lazuli.core.trade.Want> wants) {
            reply(Chat.err("Trading isn't available here"));
        }

        /** Sells {@code count} of {@code item} (negative: all of it) to villagers for emeralds. */
        default void tradeSell(String item, int count) {
            reply(Chat.err("Trading isn't available here"));
        }

        /** Walks to the villagers around whose trades aren't known and looks at them. */
        default void tradeScan() {
            reply(Chat.err("Trading isn't available here"));
        }

        /** Every villager's trades seen so far; null when not kept. */
        default dev.lazuli.core.trade.TradeMemory trades() {
            return null;
        }

        /** Detaches the camera to fly around (or puts it back). */
        default void toggleFreecam() {
            reply(Chat.err("Freecam isn't available here"));
        }
    }

    private final Context ctx;
    private Goal goal;
    private boolean fallback;

    private final dev.lazuli.core.area.Selection selection = new dev.lazuli.core.area.Selection();

    public CommandHandler(Context ctx) {
        this.ctx = ctx;
    }

    /** #sel's corners (drawn by the mod). */
    public dev.lazuli.core.area.Selection selection() {
        return selection;
    }

    public Goal goal() {
        return goal;
    }

    /** Returns true if the message was a command (and must not be sent to the server). */
    public boolean handle(String message) {
        String prefix = ctx.settings().prefix.get();
        if (!message.startsWith(prefix)) return false;
        String body = message.substring(prefix.length()).trim();
        String[] parts = body.isEmpty() ? new String[0] : body.split("\\s+");
        String cmd = parts.length == 0 ? "help" : parts[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(parts, Math.min(1, parts.length), parts.length);
        try {
            run(cmd, args, prefix);
        } catch (IllegalArgumentException e) {
            ctx.reply(Chat.err(e.getMessage()));
        }
        return true;
    }

    private void run(String cmd, String[] args, String prefix) {
        switch (cmd) {
            case "goal" -> setGoal(args);
            case "path" -> startPath();
            case "goto" -> {
                if (args.length == 1 && !looksLikeCoordinate(args[0])) goToWaypoint(args[0]);
                else if (setGoal(args)) startPath();
            }
            case "wp", "waypoint", "waypoints" -> waypoint(args);
            case "sleep" -> ctx.sleep();
            case "find" -> find(args);
            case "trade", "trades" -> trade(args);
            case "back" -> goToWaypoint(dev.lazuli.core.waypoint.Waypoints.DEATH);
            case "stop", "cancel" -> {
                ctx.stopProcesses();
                ctx.session().stop();
            }
            case "kill" -> ctx.kill();
            case "set" -> set(args);
            case "settings" -> {
                ctx.reply(Chat.info("Settings ({}set <name> <value> to change):", prefix));
                for (Setting<?> s : ctx.settings().all()) ctx.reply(Chat.pair(s.name(), s.get()));
            }
            case "record" -> record(args);
            case "config" -> ctx.openConfig();
            case "click" -> ctx.startClick();
            case "come" -> {
                int[] c = ctx.cameraFeet();
                ctx.stopProcesses();
                goTo(new GoalBlock(c[0], c[1], c[2]), true);
            }
            case "freecam" -> ctx.toggleFreecam();
            case "mine" -> mine(args);
            case "follow" -> follow(args);
            case "explore" -> {
                if (args.length == 0) ctx.explore(null);
                else if (args.length == 2) ctx.explore(new int[]{integer(args[0]), integer(args[1])});
                else ctx.reply(Chat.err("Usage: explore [x z]"));
            }
            case "tunnel" -> {
                if (args.length == 0) ctx.tunnel(2, 1, 0);
                else if (args.length == 3) {
                    int h = integer(args[0]), w = integer(args[1]), d = integer(args[2]);
                    if (h < 1 || w < 1 || d < 0 || h > 8 || w > 9) ctx.reply(Chat.err("Height 1-8, width 1-9, depth 0 or more"));
                    else ctx.tunnel(h, w, d);
                } else ctx.reply(Chat.err("Usage: tunnel [height width depth]"));
            }
            case "farm" -> {
                if (args.length == 0) ctx.farm(0);
                else if (args.length == 1) ctx.farm(Math.max(1, integer(args[0])));
                else ctx.reply(Chat.err("Usage: farm [range]"));
            }
            case "breed" -> {
                List<String> kinds = new ArrayList<>();
                int n = 0;
                for (String a : args) {
                    if (a.matches("[0-9]+")) n = Integer.parseInt(a);
                    else kinds.add(a.contains(":") ? a.toLowerCase(Locale.ROOT) : "minecraft:" + a.toLowerCase(Locale.ROOT));
                }
                ctx.breed(kinds, n);
            }
            case "portal", "portals" -> ctx.portal(cmd.equals("portals") || args.length > 0 && args[0].equalsIgnoreCase("list"));
            case "iceboat", "race" -> iceboat(args);
            case "fish" -> {
                if (args.length == 0) ctx.fish(0);
                else if (args.length == 1 && args[0].matches("[0-9]+")) ctx.fish(Integer.parseInt(args[0]));
                else ctx.reply(Chat.err("Usage: fish [minutes]"));
            }
            case "drop" -> {
                List<ItemCount> items = items(args, 0, args.length);
                if (items == null) ctx.reply(Chat.err("Usage: drop <item|all> [count] [item [count]...]"));
                else ctx.drop(items);
            }
            case "deposit", "withdraw" -> container(cmd.equals("deposit"), args);
            case "getitem", "get" -> getItem(args);
            case "smelt" -> {
                List<ItemCount> items = args.length == 1 && args[0].equalsIgnoreCase("collect") ? null : items(args, 0, args.length);
                if (args.length == 1 && args[0].equalsIgnoreCase("collect")) ctx.smeltCollect();
                else if (items == null || items.size() != 1) ctx.reply(Chat.err("Usage: smelt <item> [count] | smelt collect"));
                else ctx.smelt(items.get(0));
            }
            case "brew" -> brew(args);
            case "enchant" -> {
                if (args.length < 1 || args.length > 3) {
                    ctx.reply(Chat.err("Usage: enchant <item> [count] [1|2|3]"));
                } else {
                    int count = args.length > 1 ? Math.max(1, integer(args[1])) : 1;
                    int option = args.length > 2 ? integer(args[2]) : 0;
                    if (option < 0 || option > 3) ctx.reply(Chat.err("The option is 1, 2 or 3 (top to bottom)"));
                    else ctx.enchant(args[0], count, option);
                }
            }
            case "repair" -> {
                if (args.length != 1) ctx.reply(Chat.err("Usage: repair <item>"));
                else ctx.anvil(args[0], null);
            }
            case "combine" -> {
                if (args.length != 2) ctx.reply(Chat.err("Usage: combine <item> <with> (an enchanted_book, another of it...)"));
                else ctx.anvil(args[0], args[1]);
            }
            case "fly" -> fly(args);
            case "sel" -> sel(args);
            case "sort" -> {
                if (args.length == 0) ctx.sort(16);
                else if (args.length == 1) ctx.sort(Math.max(2, Math.min(64, integer(args[0]))));
                else ctx.reply(Chat.err("Usage: sort [range]"));
            }
            case "craft" -> {
                List<ItemCount> items = items(args, 0, args.length);
                if (items == null) ctx.reply(Chat.err("Usage: craft <item> [count] [item [count]...]"));
                else ctx.craft(items);
            }
            case "help" -> help(prefix);
            default -> ctx.reply(Chat.err("Unknown command: {}. Try {}help", cmd, prefix));
        }
    }

    private void help(String p) {
        ctx.reply(Chat.info("Lazuli commands:"));
        ctx.reply(Chat.usage(p + "goal [x y z | x z | y | clear] [true|false]", "set the goal (true: fall back to the nearest safe spot)"));
        ctx.reply(Chat.usage(p + "path", "go to the goal"));
        ctx.reply(Chat.usage(p + "goto <goal>", "set the goal and go"));
        ctx.reply(Chat.usage(p + "goto <waypoint>", "go to a saved place"));
        ctx.reply(Chat.usage(p + "wp add <name> [x y z] | del <name> | list", "save places (here without coordinates)"));
        ctx.reply(Chat.usage(p + "back", "go back where you last died"));
        ctx.reply(Chat.usage(p + "click", "left-click a block to make it the goal"));
        ctx.reply(Chat.usage(p + "come", "come to the camera (in freecam: wherever it is)"));
        ctx.reply(Chat.usage(p + "freecam", "fly the camera around; left-click a block to go there"));
        ctx.reply(Chat.usage(p + "mine [quantity] <block> [block...]", "find and mine blocks, e.g. " + p + "mine 16 diamond_ore"));
        ctx.reply(Chat.usage(p + "follow players | entities | player <name...> | entity <type...> [distance]", "keep close to someone"));
        ctx.reply(Chat.usage(p + "explore [x z]", "walk into chunks never loaded, spiralling out"));
        ctx.reply(Chat.usage(p + "tunnel [height width depth]", "dig straight ahead (default 2x1, no end)"));
        ctx.reply(Chat.usage(p + "farm [range]", "harvest and replant crops around here"));
        ctx.reply(Chat.usage(p + "breed [animal...] [pairs]", "feed animals two at a time so they breed (any kind it has food for)"));
        ctx.reply(Chat.usage(p + "iceboat <x z | x y z> | lap [n] | record [name] | line [name|off]",
            "race the boat you're in: to a point or laps round the track (worked out from the blocks), or along a line you drove"));
        ctx.reply(Chat.usage(p + "fish [minutes]", "fish at the nearest open water (until stopped without minutes)"));
        ctx.reply(Chat.usage(p + "drop <item|all> [count] [item [count]...]", "throw items out of the inventory (all of them without a count)"));
        ctx.reply(Chat.usage(p + "deposit <item|all> [count] [item [count]...] [x y z]", "put items into a container (the nearest chest, barrel...)"));
        ctx.reply(Chat.usage(p + "withdraw <item|all> [count] [item [count]...] [x y z]", "take items out of a container"));
        ctx.reply(Chat.usage(p + "craft <item> [count] [item [count]...]", "craft items, making the ingredients too (logs into planks...)"));
        ctx.reply(Chat.usage(p + "getitem <item> [count] [item [count]...] [then back|<waypoint>] [deposit]",
            "get items from scratch: mines, crafts and smelts what it takes (until you have count); iron_tools, diamond_armor, iron_gear too"));
        ctx.reply(Chat.usage(p + "smelt <item> [count]", "cook items in a furnace (iron_ingot or raw_iron; blast furnace/smoker when there's one)"));
        ctx.reply(Chat.usage(p + "smelt collect", "go back for what it left cooking"));
        ctx.reply(Chat.usage(p + "brew <potion> [count] [splash|lingering]", "brew potions (fire_resistance, long_swiftness...); fills bottles, gets what's missing"));
        ctx.reply(Chat.usage(p + "enchant <item> [count] [1|2|3]", "enchant at an enchanting table (the best it can pay for)"));
        ctx.reply(Chat.usage(p + "sel pos1|pos2 [x y z] | fill <block> | clear | reset", "select a box, then fill it with a block or clear it out"));
        ctx.reply(Chat.usage(p + "sort [range]", "sort the chests and barrels around (default 16 blocks): each item into as few as it fits"));
        ctx.reply(Chat.usage(p + "repair <item>", "repair at an anvil with its material or another one"));
        ctx.reply(Chat.usage(p + "combine <item> <with>", "put two things together at an anvil (an enchanted_book...)"));
        ctx.reply(Chat.usage(p + "portal [list]", "go through the nearest known nether portal (or build one here) | the ones known here"));
        ctx.reply(Chat.usage(p + "fly <x z | x y z | waypoint>", "fly there with an elytra and rockets (far #goto does too: elytraGoto)"));
        ctx.reply(Chat.usage(p + "find <item>", "which chests (opened before) hold an item"));
        ctx.reply(Chat.usage(p + "trade <enchant [level]|item [count]>...", "trade with villagers, e.g. " + p + "trade sharpness 5 (sells string, sticks... for emeralds first if need be)"));
        ctx.reply(Chat.usage(p + "trade list | scan | forget", "the trades seen so far | go look at the villagers around | forget them"));
        ctx.reply(Chat.usage(p + "trade sell <item> [count|all] | find <enchant [level]|item>", "sell for emeralds | who sells it, cheapest first"));
        ctx.reply(Chat.usage(p + "sleep", "sleep in the nearest bed (or one from the hotbar) until morning"));
        ctx.reply(Chat.usage(p + "stop", "stop pathing and whatever it's doing"));
        ctx.reply(Chat.usage(p + "kill", "when something's stuck: reset everything (planner thread, keys, jobs), then any command works again"));
        ctx.reply(Chat.usage(p + "set <name> [value]", "show or change a setting"));
        ctx.reply(Chat.usage(p + "settings", "list every setting"));
        ctx.reply(Chat.usage(p + "config", "open the settings screen"));
        ctx.reply(Chat.usage(p + "record start <name> | stop", "record a movement trace"));
    }

    /** iceboat <x z | x y z> | lap [n] | record [name] | line [name|off]; bare: race the recorded line. */
    private void iceboat(String[] args) {
        int[] f = ctx.playerFeet();
        if (args.length == 0) {
            ctx.iceboat("line", null, 1, null);
        } else if (args[0].equalsIgnoreCase("lap") || args[0].equalsIgnoreCase("laps")) {
            ctx.iceboat("lap", null, args.length > 1 && number(args[1]) ? Math.max(1, integer(args[1])) : 1, null);
        } else if (args[0].equalsIgnoreCase("record")) {
            ctx.iceboat("record", null, 0, args.length > 1 ? args[1] : null);
        } else if (args[0].equalsIgnoreCase("line")) {
            if (args.length > 1) ctx.iceboat("use", null, 0, args[1]);
            else ctx.iceboat("line", null, 1, null);
        } else if (args.length == 2 && coordinate(args[0]) && coordinate(args[1])) {
            ctx.iceboat("goal", new int[]{coord(args[0], f[0]), Integer.MIN_VALUE, coord(args[1], f[2])}, 0, null);
        } else if (args.length == 3 && coordinate(args[0]) && coordinate(args[1]) && coordinate(args[2])) {
            ctx.iceboat("goal", new int[]{coord(args[0], f[0]), coord(args[1], f[1]), coord(args[2], f[2])}, 0, null);
        } else {
            ctx.reply(Chat.err("Usage: iceboat <x z | x y z> | lap [n] | record [name] | line [name|off]"));
        }
    }

    /** follow players | entities | player <name...> | entity <type...> [distance]; a bare name means that player. */
    private void follow(String[] args) {
        // a number last: how close to keep (else followRadius)
        int distance = 0;
        if (args.length > 1 && args[args.length - 1].matches("\\d{1,3}")) {
            distance = Integer.parseInt(args[args.length - 1]);
            args = Arrays.copyOf(args, args.length - 1);
        }
        int d = distance;
        if (args.length == 0) {
            ctx.reply(Chat.err("Usage: follow players | entities | player <name...> | entity <type...>"));
            return;
        }
        String m = args[0].toLowerCase(Locale.ROOT);
        List<String> rest = List.of(Arrays.copyOfRange(args, 1, args.length));
        switch (m) {
            case "players", "entities" -> ctx.follow(m, List.of(), d);
            case "player", "entity" -> {
                if (rest.isEmpty()) ctx.reply(Chat.err("Usage: follow {} <{}...>", m, m.equals("player") ? "name" : "type"));
                else ctx.follow(m, rest.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList(), d);
            }
            default -> ctx.follow("player", List.of(args), d); // follow Steve
        }
    }

    /** deposit|withdraw <item|all> [count] [item [count]...] [x y z]; coordinates may be relative (~). */
    private void container(boolean deposit, String[] args) {
        int n = args.length;
        int[] at = null;
        // items are never numbers, so three trailing coordinates can't be a count
        if (n >= 4 && coordinate(args[n - 3]) && coordinate(args[n - 2]) && coordinate(args[n - 1])) {
            int[] f = ctx.playerFeet();
            at = new int[]{coord(args[n - 3], f[0]), coord(args[n - 2], f[1]), coord(args[n - 1], f[2])};
            n -= 3;
        }
        List<ItemCount> items = items(args, 0, n);
        if (items == null) ctx.reply(Chat.err("Usage: {} <item|all> [count] [item [count]...] [x y z]", deposit ? "deposit" : "withdraw"));
        else ctx.container(deposit, items, at);
    }

    /** getitem <item> [count] [item [count]...] [then back|<waypoint>] [deposit] */
    private void getItem(String[] args) {
        int then = -1;
        for (int i = 0; i < args.length && then < 0; i++) if (args[i].equalsIgnoreCase("then")) then = i;
        List<ItemCount> raw = items(args, 0, then < 0 ? args.length : then);
        String usage = "Usage: getitem <item> [count] [item [count]...] [then back|<waypoint>] [deposit]";
        if (raw == null) {
            ctx.reply(Chat.err(usage));
            return;
        }
        List<ItemCount> items = new ArrayList<>();
        for (ItemCount i : raw) {
            List<String> set = dev.lazuli.core.craft.ItemSets.expand(i.item());
            if (set == null) items.add(i);
            else for (String s : set) items.add(new ItemCount(s, i.count()));
        }
        int[] to = null;
        boolean deposit = false;
        if (then >= 0) {
            List<String> rest = List.of(args).subList(then + 1, args.length);
            if (rest.isEmpty() || rest.size() > 2 || rest.size() == 2 && !store(rest.get(1)) || rest.size() == 2 && store(rest.get(0))) {
                ctx.reply(Chat.err(usage));
                return;
            }
            String where = rest.get(0).toLowerCase(Locale.ROOT);
            deposit = rest.size() == 2 || store(where);
            if (where.equals("back") || where.equals("here")) to = ctx.playerFeet();
            else if (!store(where)) {
                var wps = ctx.waypoints();
                var w = wps == null ? null : wps.get(where, ctx.dimension());
                if (w == null) {
                    ctx.reply(Chat.err("No waypoint {} in this dimension", where));
                    return;
                }
                to = new int[]{w.x(), w.y(), w.z()};
            }
        }
        ctx.getItem(new dev.lazuli.core.craft.ItemRequest(items, to, deposit));
    }

    private static boolean store(String s) {
        return s.equalsIgnoreCase("deposit") || s.equalsIgnoreCase("store");
    }

    /** item [count] pairs from {@code args[from, to)}, a missing count meaning all (-1); null if empty or malformed. */
    private static List<ItemCount> items(String[] args, int from, int to) {
        List<ItemCount> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (number(args[i])) return null;
            String item = args[i].toLowerCase(Locale.ROOT);
            int count = -1;
            if (i + 1 < to && number(args[i + 1])) count = Math.max(1, integer(args[++i]));
            out.add(new ItemCount(item, count));
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean number(String s) {
        return s.matches("-?\\d+");
    }

    private static boolean coordinate(String s) {
        return number(s) || s.matches("~(-?\\d+)?");
    }

    /** A coordinate: a number, or ~ / ~n relative to {@code here}. */
    private static int coord(String s, int here) {
        if (s.startsWith("~")) return here + (s.length() == 1 ? 0 : integer(s.substring(1)));
        return integer(s);
    }

    /** brew <potion> [count] [splash|lingering] */
    private void brew(String[] args) {
        if (args.length < 1 || args.length > 3) {
            ctx.reply(Chat.err("Usage: brew <potion> [count] [splash|lingering]"));
            return;
        }
        int count = 3;
        var form = dev.lazuli.core.craft.Brewing.Form.DRINK;
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(java.util.Locale.ROOT);
            if (a.equals("splash")) form = dev.lazuli.core.craft.Brewing.Form.SPLASH;
            else if (a.equals("lingering")) form = dev.lazuli.core.craft.Brewing.Form.LINGERING;
            else count = Math.max(1, integer(a));
        }
        if (!dev.lazuli.core.craft.Brewing.known(args[0])) {
            ctx.reply(Chat.err("Unknown potion: {} (like fire_resistance, long_swiftness, strong_healing)", args[0]));
            return;
        }
        ctx.brew(args[0], form, count);
    }

    /** fly <x z | x y z | waypoint> */
    private static final long SEL_MAX = 200_000;

    /** sel pos1|pos2 [x y z] | fill <block> | clear | reset | (nothing: what's selected) */
    private void sel(String[] args) {
        String usage = "Usage: sel pos1|pos2 [x y z] | fill <block> | clear | reset";
        if (args.length == 0) {
            var b = selection.box();
            ctx.reply(b == null ? Chat.info("Nothing selected ({}sel pos1, {}sel pos2)", ctx.settings().prefix.get(), ctx.settings().prefix.get())
                : Chat.info("Selected {} ({} blocks)", b, b.volume()));
            return;
        }
        String a = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (a) {
            case "pos1", "1", "pos2", "2" -> {
                int which = a.endsWith("1") ? 1 : 2;
                int[] f = ctx.playerFeet();
                if (args.length == 1) selection.set(which, f[0], f[1], f[2]);
                else if (args.length == 4) selection.set(which, coord(args[1], f[0]), coord(args[2], f[1]), coord(args[3], f[2]));
                else {
                    ctx.reply(Chat.err(usage));
                    return;
                }
                int[] p = which == 1 ? selection.pos1() : selection.pos2();
                var b = selection.box();
                ctx.reply(Chat.ok("pos{} at {} {} {}{}", which, p[0], p[1], p[2], b == null ? "" : " (" + b.volume() + " blocks)"));
            }
            case "reset", "none", "deselect" -> {
                selection.reset();
                ctx.reply(Chat.ok("Selection cleared"));
            }
            case "fill", "clear" -> {
                var b = selection.box();
                if (b == null) {
                    ctx.reply(Chat.err("Pick both corners first: {}sel pos1, {}sel pos2", ctx.settings().prefix.get(), ctx.settings().prefix.get()));
                } else if (b.volume() > SEL_MAX) {
                    ctx.reply(Chat.err("That's {} blocks; at most {}", b.volume(), SEL_MAX));
                } else if (a.equals("clear")) {
                    if (args.length != 1) ctx.reply(Chat.err(usage));
                    else ctx.selClear(b);
                } else if (args.length != 2) {
                    ctx.reply(Chat.err("Usage: sel fill <block>"));
                } else {
                    String id = args[1].toLowerCase(java.util.Locale.ROOT);
                    ctx.selFill(b, id.contains(":") ? id : "minecraft:" + id);
                }
            }
            default -> ctx.reply(Chat.err(usage));
        }
    }

    private void fly(String[] args) {
        int[] f = ctx.playerFeet();
        if (args.length == 1) {
            var wps = ctx.waypoints();
            var w = wps == null ? null : wps.get(args[0], ctx.dimension());
            if (w == null) ctx.reply(Chat.err("No waypoint {} in this dimension", args[0]));
            else ctx.fly(w.x(), w.y(), w.z());
        } else if (args.length == 2) {
            ctx.fly(coord(args[0], f[0]), null, coord(args[1], f[2]));
        } else if (args.length == 3) {
            ctx.fly(coord(args[0], f[0]), coord(args[1], f[1]), coord(args[2], f[2]));
        } else {
            ctx.reply(Chat.err("Usage: fly <x z | x y z | waypoint>"));
        }
    }

    private static int integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + s);
        }
    }

    /** Baritone's form: mine [quantity] <block> [block...]. */
    private void mine(String[] args) {
        int quantity = 0;
        int from = 0;
        if (args.length > 0 && args[0].matches("\\d+")) {
            quantity = Integer.parseInt(args[0]);
            from = 1;
        }
        if (from >= args.length) {
            ctx.reply(Chat.err("Usage: mine [quantity] <block> [block...]"));
            return;
        }
        List<String> blocks = new ArrayList<>();
        for (int i = from; i < args.length; i++) blocks.add(args[i].toLowerCase(Locale.ROOT));
        ctx.mine(quantity, blocks);
    }

    private void record(String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            ctx.recordStop();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start") && args[1].matches("[A-Za-z0-9_-]+")) {
            ctx.recordStart(args[1]);
        } else {
            ctx.reply(Chat.err("Usage: record start <name> | record stop  (name: letters, digits, _ and -)"));
        }
    }

    /** Sets the goal and goes (freecam clicks, #come); {@code fallback}: the nearest safe spot will do. */
    public void goTo(Goal g, boolean fallback) {
        goal = g;
        this.fallback = fallback;
        ctx.reply(Chat.ok("Going to {}", goal.describe()));
        startPath();
    }

    /** Sets the goal directly (from #click). */
    public void setGoal(Goal g) {
        goal = g;
        fallback = false;
        ctx.reply(Chat.ok("Goal set: {}", goal.describe()));
    }

    private static boolean looksLikeCoordinate(String s) {
        return coordinate(s) || s.equalsIgnoreCase("clear");
    }

    /** wp add <name> [x y z] | del <name> | list */
    private void waypoint(String[] args) {
        var wps = ctx.waypoints();
        if (wps == null) {
            ctx.reply(Chat.err("Waypoints aren't available here"));
            return;
        }
        String dim = ctx.dimension();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add", "set", "save" -> {
                if (args.length != 2 && args.length != 5) {
                    ctx.reply(Chat.err("Usage: wp add <name> [x y z]"));
                    return;
                }
                int[] f = ctx.playerFeet();
                int[] at = args.length == 5 ? new int[]{coord(args[2], f[0]), coord(args[3], f[1]), coord(args[4], f[2])} : f;
                wps.set(args[1], dim, at[0], at[1], at[2]);
                ctx.reply(Chat.ok("Waypoint {} at {}", args[1].toLowerCase(Locale.ROOT), at[0] + " " + at[1] + " " + at[2]));
            }
            case "del", "remove", "delete" -> {
                if (args.length != 2) ctx.reply(Chat.err("Usage: wp del <name>"));
                else if (wps.remove(args[1], dim)) ctx.reply(Chat.ok("Removed {}", args[1]));
                else ctx.reply(Chat.err("No waypoint {} here", args[1]));
            }
            case "list" -> {
                var list = wps.list(dim);
                if (list.isEmpty()) ctx.reply(Chat.info("No waypoints in this dimension"));
                for (var w : list) ctx.reply(Chat.pair(w.name(), w.x() + " " + w.y() + " " + w.z()));
            }
            case "goto" -> {
                if (args.length != 2) ctx.reply(Chat.err("Usage: wp goto <name>"));
                else goToWaypoint(args[1]);
            }
            default -> ctx.reply(Chat.err("Usage: wp add <name> [x y z] | del <name> | list | goto <name>"));
        }
    }

    /** trade <enchant [level]|item [count]>... | list | scan | forget */
    private void trade(String[] args) {
        String usage = "Usage: trade <enchant [level]|item [count]>... | list | scan | forget | sell <item> [count|all] | find <what>";
        if (args.length == 0) {
            ctx.reply(Chat.err(usage));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> ctx.tradeScan();
            case "sell" -> {
                if (args.length < 2 || args.length > 3) {
                    ctx.reply(Chat.err("Usage: trade sell <item> [count|all]"));
                    return;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                id = id.contains(":") ? id : "minecraft:" + id;
                int count = -1;
                if (args.length == 3 && !args[2].equalsIgnoreCase("all")) {
                    try {
                        count = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        ctx.reply(Chat.err("Usage: trade sell <item> [count|all]"));
                        return;
                    }
                }
                ctx.tradeSell(id, count);
            }
            case "find", "search" -> {
                var mem = ctx.trades();
                var wants = args.length > 1 ? dev.lazuli.core.trade.Want.parse(java.util.Arrays.copyOfRange(args, 1, args.length), ctx::isEnchantment) : null;
                if (mem == null || wants == null) {
                    ctx.reply(Chat.err(mem == null ? "Trade memory is off" : "Usage: trade find <enchant [level]|item>"));
                    return;
                }
                int[] f = ctx.playerFeet();
                var found = dev.lazuli.core.trade.TradePlanner.find(wants.get(0), mem.all(ctx.dimension()), f[0], f[1], f[2]);
                if (found.isEmpty()) {
                    ctx.reply(Chat.info("No villager seen here sells {}", wants.get(0).describe()));
                    return;
                }
                ctx.reply(Chat.info("{} offers of {}, cheapest first:", found.size(), wants.get(0).describe()));
                for (int i = 0; i < Math.min(5, found.size()); i++) {
                    var s = found.get(i);
                    var t = s.trader();
                    ctx.reply(Chat.pair("  " + s.offer().describe() + " (" + t.profession() + " at " + t.x() + " " + t.y() + " " + t.z() + ", "
                        + (int) Math.sqrt(t.distSq(f[0], f[1], f[2])) + "m)", s.offer().left() > 0 ? s.offer().left() + " left" : "sold out"));
                }
            }
            case "forget", "clear" -> {
                var mem = ctx.trades();
                if (mem != null) mem.clear();
                ctx.reply(Chat.ok("Forgot every villager's trades"));
            }
            case "list" -> {
                var mem = ctx.trades();
                if (mem == null) {
                    ctx.reply(Chat.err("Trade memory is off"));
                    return;
                }
                int[] f = ctx.playerFeet();
                var near = mem.near(ctx.dimension(), f[0], f[1], f[2]);
                if (near.isEmpty()) {
                    ctx.reply(Chat.info("No villager's trades seen here yet (open one, or {}trade scan)", ctx.settings().prefix.get()));
                    return;
                }
                ctx.reply(Chat.info("{} villagers' trades known; nearest:", near.size()));
                for (int i = 0; i < Math.min(4, near.size()); i++) {
                    var t = near.get(i);
                    ctx.reply(Chat.pair(t.profession() + " at " + t.x() + " " + t.y() + " " + t.z(),
                        (int) Math.sqrt(t.distSq(f[0], f[1], f[2])) + "m"));
                    for (var o : t.offers()) ctx.reply(Chat.pair("  " + o.describe(), o.left() > 0 ? o.left() + " left" : "sold out"));
                }
            }
            default -> {
                var wants = dev.lazuli.core.trade.Want.parse(args, ctx::isEnchantment);
                if (wants == null) ctx.reply(Chat.err(usage));
                else ctx.trade(wants);
            }
        }
    }

    /** find <item>: the remembered containers holding it, nearest first. */
    private void find(String[] args) {
        var mem = ctx.chests();
        if (mem == null) {
            ctx.reply(Chat.err("Chest memory is off"));
            return;
        }
        if (args.length != 1) {
            ctx.reply(Chat.err("Usage: find <item>"));
            return;
        }
        String item = args[0].toLowerCase(Locale.ROOT);
        if (!item.contains(":")) item = "minecraft:" + item;
        int[] f = ctx.playerFeet();
        var spots = mem.find(item, ctx.dimension(), f[0], f[1], f[2]);
        String name = item.substring(item.indexOf(':') + 1);
        if (spots.isEmpty()) {
            ctx.reply(Chat.info("No {} in any chest opened in this dimension", name));
            return;
        }
        int total = spots.stream().mapToInt(s -> s.count()).sum();
        ctx.reply(Chat.info("{} {} in {} container{}:", total, name, spots.size(), spots.size() == 1 ? "" : "s"));
        for (int i = 0; i < Math.min(8, spots.size()); i++) {
            var s = spots.get(i);
            ctx.reply(Chat.pair(s.x() + " " + s.y() + " " + s.z(), s.count() + " (" + (int) Math.sqrt(s.distSq(f[0], f[1], f[2])) + "m)"));
        }
    }

    private void goToWaypoint(String name) {
        var wps = ctx.waypoints();
        var w = wps == null ? null : wps.get(name, ctx.dimension());
        if (w == null) {
            ctx.reply(Chat.err(name.equalsIgnoreCase(dev.lazuli.core.waypoint.Waypoints.DEATH)
                ? "No death point in this dimension" : "No waypoint {} in this dimension", name));
            return;
        }
        ctx.stopProcesses();
        goTo(new GoalBlock(w.x(), w.y(), w.z()), true);
    }

    private boolean setGoal(String[] args) {
        int[] f = ctx.playerFeet();
        GoalParser.ParsedGoal parsed = GoalParser.parse(args, f[0], f[1], f[2]);
        if (parsed.goal() == null) {
            goal = null;
            ctx.reply(Chat.ok("Goal cleared"));
            return false;
        }
        if (parsed.goal() instanceof GoalBlock b && !parsed.fallback() && knownAndUnstandable(b)) {
            ctx.reply(Chat.err("Can't stand at {} (use true to allow the nearest safe spot)", b.x() + " " + b.y() + " " + b.z()));
            return false;
        }
        goal = parsed.goal();
        fallback = parsed.fallback();
        ctx.reply(Chat.ok("Goal set: {}" + (fallback ? " (fallback on)" : ""), goal.describe()));
        return true;
    }

    private boolean knownAndUnstandable(GoalBlock b) {
        BlockView w = ctx.world();
        // breaking and placing can make almost any spot standable; the planner decides those
        Settings s = ctx.settings();
        if (s.allowBreak.get() || s.allowPlace.get()) {
            return w.get(b.x(), b.y(), b.z()) == BlockKind.DANGER || w.get(b.x(), b.y() + 1, b.z()) == BlockKind.DANGER;
        }
        for (int dy = -1; dy <= 1; dy++) {
            if (w.get(b.x(), b.y() + dy, b.z()) == BlockKind.UNKNOWN) return false;
        }
        return !MoveGraph.isStandable(w, b.x(), b.y(), b.z());
    }

    private void startPath() {
        if (goal == null) {
            ctx.reply(Chat.err("No goal set"));
            return;
        }
        if (ctx.flyInstead(goal) || ctx.netherInstead(goal)) return;
        int[] f = ctx.playerFeet();
        ctx.session().start(goal, fallback, Pos.pack(f[0], f[1], f[2]));
    }

    private void set(String[] args) {
        if (args.length == 0 || args.length > 2) {
            ctx.reply(Chat.err("Usage: set <name> [value]"));
            return;
        }
        var found = ctx.settings().byName(args[0]);
        if (found.isEmpty()) {
            ctx.reply(Chat.err("Unknown setting: {}", args[0]));
            return;
        }
        Setting<?> s = found.get();
        if (args.length == 2) {
            s.parse(args[1]);
            ctx.settingsChanged();
        }
        ctx.reply(Chat.pair(s.name(), s.get()));
    }
}
