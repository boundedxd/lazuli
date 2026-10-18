package dev.lazuli.core.command;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.goal.GoalYLevel;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {
    private final List<String> replies = new ArrayList<>();
    private int saved;
    private boolean busy;
    private final List<String> mined = new ArrayList<>();
    private Settings settings;
    private PathingSession session;
    private final dev.lazuli.core.waypoint.Waypoints waypoints = new dev.lazuli.core.waypoint.Waypoints(null);
    private final dev.lazuli.core.storage.ChestMemory chests = new dev.lazuli.core.storage.ChestMemory(null);

    private CommandHandler handler() {
        BlockView world = GridWorld.flatFloor(0, 0, 20, 20);
        settings = new Settings();
        session = new PathingSession(world, () -> -64, () -> 319, settings, Runnable::run, m -> replies.add(Chat.strip(m)));
        return new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return world; }
            public PathingSession session() { return session; }
            public Settings settings() { return settings; }
            public void reply(String msg) { replies.add(Chat.strip(msg)); }
            public void settingsChanged() { saved++; }
            public void mine(int quantity, List<String> blocks) {
                mined.add(quantity + " " + blocks);
            }
            public void follow(String mode, List<String> args, int distance) {
                mined.add("follow " + mode + " " + args + (distance > 0 ? " at " + distance : ""));
            }
            public void explore(int[] origin) {
                mined.add("explore " + (origin == null ? "here" : origin[0] + " " + origin[1]));
            }
            public void tunnel(int h, int w, int d) {
                mined.add("tunnel " + h + " " + w + " " + d);
            }
            public void farm(int range) {
                mined.add("farm " + range);
            }
            public void breed(List<String> kinds, int pairs) {
                mined.add("breed " + kinds + " " + pairs);
            }
            public void fish(int minutes) {
                mined.add("fish " + minutes);
            }
            public void iceboat(String mode, int[] target, int laps, String name) {
                mined.add("iceboat " + mode + (target == null ? "" : " " + java.util.Arrays.toString(target)) + " " + laps + " " + name);
            }
            public void container(boolean deposit, List<ItemCount> items, int[] at) {
                mined.add((deposit ? "deposit " : "withdraw ") + items + (at == null ? "" : " at " + at[0] + " " + at[1] + " " + at[2]));
            }
            public void drop(List<ItemCount> items) {
                mined.add("drop " + items);
            }
            public void craft(List<ItemCount> items) {
                mined.add("craft " + items);
            }
            public void smelt(ItemCount item) {
                mined.add("smelt " + item);
            }
            public void smeltCollect() {
                mined.add("collect");
            }
            private final dev.lazuli.core.task.Routines store = new dev.lazuli.core.task.Routines(null);
            public dev.lazuli.core.task.Routines routines() { return store; }
            public void runRoutine(String name, List<String> steps, int times) {
                mined.add("run " + name + " " + steps + " x" + times);
            }
            public void schematic(String mode, String file, int[] at) {
                mined.add("schem " + mode + " " + file + (at == null ? "" : " " + java.util.Arrays.toString(at)));
            }
            public void getItemDry(List<ItemCount> items) {
                mined.add("dry " + items);
            }
            public void getItem(dev.lazuli.core.craft.ItemRequest r) {
                mined.add("get " + r.items() + (r.returnTo() == null ? "" : " to " + r.returnTo()[0] + " " + r.returnTo()[1] + " " + r.returnTo()[2])
                    + (r.deposit() ? " deposit" : ""));
            }
            public int[] cameraFeet() { return new int[]{12, 1, 7}; }
            public void toggleFreecam() { mined.add("freecam"); }
            public void sleep() { mined.add("sleep"); }
            public dev.lazuli.core.waypoint.Waypoints waypoints() { return waypoints; }
            public String dimension() { return "overworld"; }
            public dev.lazuli.core.storage.ChestMemory chests() { return chests; }
            public void sort(int range) { mined.add("sort " + range); }
            public void bucket(String item, int n) { mined.add("bucket " + item + " " + n); }
            public void hunt(String item, int count) { mined.add("hunt " + item + " " + count); }
            public void smith(ItemCount item) { mined.add("smith " + item); }
            public boolean busy() { return busy; }
            public void selClear(dev.lazuli.core.area.Selection.Box b) { mined.add("clear " + b); }
            public void selFill(dev.lazuli.core.area.Selection.Box b, String block) { mined.add("fill " + b + " " + block); }
        });
    }

    @Test
    void comeGoesToTheCameraAndFreecamToggles() {
        CommandHandler h = handler();
        h.handle("#come");
        assertEquals(PathingSession.State.PATHING, session.snapshot().state(), replies.toString());
        assertEquals(Pos.pack(12, 1, 7), session.snapshot().path().get(session.snapshot().path().size() - 1));
        h.handle("#freecam");
        h.handle("#sleep");
        assertEquals(List.of("freecam", "sleep"), mined);
    }

    @Test
    void waypointsAreAddedListedAndGoneTo() {
        CommandHandler h = handler();
        h.handle("#wp add Home");
        h.handle("#wp add mine 15 1 3");
        assertEquals(new dev.lazuli.core.waypoint.Waypoints.Waypoint("home", "overworld", 0, 1, 0), waypoints.get("home", "overworld"));
        h.handle("#wp list");
        assertTrue(replies.stream().anyMatch(r -> r.contains("mine") && r.contains("15 1 3")), replies.toString());
        h.handle("#goto mine");
        assertEquals(PathingSession.State.PATHING, session.snapshot().state(), replies.toString());
        assertEquals(Pos.pack(15, 1, 3), session.snapshot().path().get(session.snapshot().path().size() - 1));
        h.handle("#wp del mine");
        assertNull(waypoints.get("mine", "overworld"));
        h.handle("#goto nowhere");
        assertTrue(replies.get(replies.size() - 1).contains("nowhere"), replies.toString());
    }

    @Test
    void findSaysWhichChestsHoldAnItem() {
        CommandHandler h = handler();
        chests.record("overworld", 5, 1, 5, java.util.Map.of("minecraft:diamond", 3));
        chests.record("overworld", 40, 1, 0, java.util.Map.of("minecraft:diamond", 10));
        h.handle("#find diamond");
        String all = String.join("|", replies);
        assertTrue(all.contains("13") && all.contains("5 1 5") && all.contains("40 1 0"), all);
        assertTrue(all.indexOf("5 1 5") < all.indexOf("40 1 0"), all);
        replies.clear();
        h.handle("#find netherite_ingot");
        assertTrue(replies.get(0).contains("netherite_ingot"), replies.toString());
    }

    @Test
    void backGoesWhereThePlayerDied() {
        CommandHandler h = handler();
        h.handle("#back");
        assertTrue(replies.get(replies.size() - 1).toLowerCase().contains("death"), replies.toString());
        waypoints.set(dev.lazuli.core.waypoint.Waypoints.DEATH, "overworld", 9, 1, 9);
        h.handle("#back");
        assertEquals(PathingSession.State.PATHING, session.snapshot().state(), replies.toString());
        assertEquals(Pos.pack(9, 1, 9), session.snapshot().path().get(session.snapshot().path().size() - 1));
    }

    @Test
    void waypointsSurviveARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path f = dir.resolve("waypoints.txt");
        new dev.lazuli.core.waypoint.Waypoints(f).set("Base", "the_nether", 1, -2, 3);
        assertEquals(new dev.lazuli.core.waypoint.Waypoints.Waypoint("base", "the_nether", 1, -2, 3),
            new dev.lazuli.core.waypoint.Waypoints(f).get("BASE", "the_nether"));
        assertTrue(new dev.lazuli.core.waypoint.Waypoints(f).list("overworld").isEmpty());
    }

    @Test
    void itemCommandsTakeCountsAndPlaces() {
        CommandHandler h = handler();
        for (String c : new String[]{"#deposit all", "#deposit Cobblestone 32", "#withdraw iron_ingot 5 10 64 ~-2",
            "#withdraw diamond ~ ~1 ~", "#drop dirt", "#drop dirt 3"}) h.handle(c);
        h.handle("#deposit");
        assertEquals(List.of("deposit [all -1]", "deposit [cobblestone 32]", "withdraw [iron_ingot 5] at 10 64 -2",
            "withdraw [diamond -1] at 0 2 0", "drop [dirt -1]", "drop [dirt 3]"), mined);
        assertTrue(replies.get(replies.size() - 1).startsWith("Usage: deposit"), replies.toString());
    }

    @Test
    void itemCommandsTakeSeveralItems() {
        CommandHandler h = handler();
        for (String c : new String[]{"#drop chest 1 oak_log 3", "#drop dirt sand 2 gravel", "#deposit dirt 5 cobblestone 10 64 ~-2",
            "#withdraw iron_ingot 3 Gold_Ingot 1 2 3"}) h.handle(c);
        assertEquals(List.of("drop [chest 1, oak_log 3]", "drop [dirt -1, sand 2, gravel -1]",
            "deposit [dirt 5, cobblestone -1] at 10 64 -2", "withdraw [iron_ingot 3, gold_ingot -1] at 1 2 3"), mined);
        h.handle("#drop 5 dirt");
        h.handle("#drop");
        assertEquals(2, replies.size(), replies.toString());
    }

    @Test
    void craftTakesItemsAndCounts() {
        CommandHandler h = handler();
        h.handle("#craft crafting_table");
        h.handle("#craft stick 8 Torch 4");
        h.handle("#craft");
        assertEquals(List.of("craft [crafting_table -1]", "craft [stick 8, torch 4]"), mined);
        assertTrue(replies.get(0).startsWith("Usage: craft"), replies.toString());
    }

    @Test
    void smeltTakesOneItemOrCollect() {
        CommandHandler h = handler();
        for (String c : new String[]{"#smelt iron_ingot 16", "#smelt Raw_Iron", "#smelt collect", "#smelt", "#smelt a 1 b 2"}) h.handle(c);
        assertEquals(List.of("smelt iron_ingot 16", "smelt raw_iron -1", "collect"), mined);
        assertEquals(2, replies.size(), replies.toString());
    }

    @Test
    void getItemTakesItemsSetsAndWhereToBringThem() {
        CommandHandler h = handler();
        waypoints.set("home", "overworld", 50, 70, -20);
        for (String c : new String[]{"#getitem iron_pickaxe", "#getitem Torch 16", "#getitem a 1 b 2", "#get iron_tools",
            "#getitem diamond_armor 2", "#getitem diamond 5 then back", "#getitem diamond then home deposit",
            "#getitem coal then deposit", "#getitem stone_gear"}) h.handle(c);
        assertEquals(List.of("get [iron_pickaxe -1]", "get [torch 16]", "get [a 1, b 2]",
            "get [iron_pickaxe -1, iron_axe -1, iron_shovel -1, iron_sword -1]",
            "get [diamond_helmet 2, diamond_chestplate 2, diamond_leggings 2, diamond_boots 2]",
            "get [diamond 5] to 0 1 0", "get [diamond -1] to 50 70 -20 deposit", "get [coal -1] deposit",
            "get [stone_pickaxe -1, stone_axe -1, stone_shovel -1, stone_sword -1]"), mined);
        assertTrue(replies.isEmpty() || replies.stream().noneMatch(r -> r.startsWith("Usage")), replies.toString());
        mined.clear();
        for (String c : new String[]{"#getitem", "#getitem diamond then nowhere", "#getitem then back", "#getitem 5 diamond"}) h.handle(c);
        assertTrue(mined.isEmpty(), mined.toString());
        assertEquals(4, replies.size(), replies.toString());
        assertTrue(replies.get(1).contains("nowhere"), replies.toString());
    }

    @Test
    void itemSetsExpand() {
        assertEquals(List.of("minecraft:golden_helmet", "minecraft:golden_chestplate", "minecraft:golden_leggings", "minecraft:golden_boots"),
            dev.lazuli.core.craft.ItemSets.expand("minecraft:gold_armor"));
        assertEquals(8, dev.lazuli.core.craft.ItemSets.expand("iron_gear").size());
        assertEquals(4, dev.lazuli.core.craft.ItemSets.expand("wood_tools").size());
        assertNull(dev.lazuli.core.craft.ItemSets.expand("iron_pickaxe"));
        assertNull(dev.lazuli.core.craft.ItemSets.expand("stone_armor"));
    }

    @Test
    void mineTakesBaritonesArguments() {
        CommandHandler h = handler();
        h.handle("#mine diamond_ore");
        h.handle("#mine 64 iron_ore minecraft:Coal_Ore");
        h.handle("#mine 5");
        assertEquals(List.of("0 [diamond_ore]", "64 [iron_ore, minecraft:coal_ore]"), mined);
        assertTrue(replies.get(0).startsWith("Usage: mine"), replies.toString());
    }

    @Test
    void processCommandsTakeBaritonesArguments() {
        CommandHandler h = handler();
        for (String c : new String[]{"#follow players", "#follow entities", "#follow player Steve Alex", "#follow entity Zombie",
            "#follow Notch", "#explore", "#explore 100 -200", "#tunnel", "#tunnel 3 3 50", "#farm", "#farm 16"}) h.handle(c);
        assertEquals(List.of("follow players []", "follow entities []", "follow player [steve, alex]", "follow entity [zombie]",
            "follow player [Notch]", "explore here", "explore 100 -200", "tunnel 2 1 0", "tunnel 3 3 50", "farm 0", "farm 16"), mined);
        h.handle("#tunnel 0 1 5");
        h.handle("#follow player");
        h.handle("#explore x 1");
        assertEquals(3, replies.size(), replies.toString());
    }

    @Test
    void followTakesADistanceLast() {
        CommandHandler h = handler();
        for (String c : new String[]{"#follow Notch 5", "#follow players 3", "#follow player Steve Alex 8", "#follow entity zombie 2"}) {
            h.handle(c);
        }
        assertEquals(List.of("follow player [Notch] at 5", "follow players [] at 3", "follow player [steve, alex] at 8",
            "follow entity [zombie] at 2"), mined);
    }

    @Test
    void tradeTakesEnchantsAndItems() {
        List<List<dev.lazuli.core.trade.Want>> got = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        Settings st = new Settings();
        CommandHandler h = new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return GridWorld.flatFloor(0, 0, 4, 4); }
            public PathingSession session() { return null; }
            public Settings settings() { return st; }
            public void reply(String msg) { raw.add(msg); }
            public void settingsChanged() {}
            public boolean isEnchantment(String id) { return id.equals("minecraft:sharpness"); }
            public void trade(List<dev.lazuli.core.trade.Want> wants) { got.add(wants); }
        });
        h.handle("#trade sharpness 5 bread 10");
        assertEquals(List.of(List.of(new dev.lazuli.core.trade.Want("minecraft:enchanted_book", "minecraft:sharpness", 5, 1),
            new dev.lazuli.core.trade.Want("minecraft:bread", null, 0, 10))), got);
        h.handle("#trade");
        assertTrue(Chat.strip(raw.get(raw.size() - 1)).startsWith("Usage: trade"), raw.toString());
    }

    @Test
    void repliesAreColoured() {
        List<String> raw = new ArrayList<>();
        Settings st = new Settings();
        CommandHandler h = new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return GridWorld.flatFloor(0, 0, 4, 4); }
            public PathingSession session() { return null; }
            public Settings settings() { return st; }
            public void reply(String msg) { raw.add(msg); }
            public void settingsChanged() {}
        });
        h.handle("#bogus");
        h.handle("#set allowBreak");
        assertTrue(raw.get(0).startsWith(Chat.RED), raw.get(0));
        assertEquals("Unknown command: bogus. Try #help", Chat.strip(raw.get(0)));
        assertTrue(raw.get(1).contains(Chat.GREEN + "true"), raw.get(1));
    }

    @Test
    void parserForms() {
        assertEquals(new GoalBlock(1, 2, 3), GoalParser.parse(new String[]{"1", "2", "3"}, 0, 0, 0).goal());
        assertEquals(new GoalXZ(1, -3), GoalParser.parse(new String[]{"1", "-3"}, 0, 0, 0).goal());
        assertEquals(new GoalYLevel(12), GoalParser.parse(new String[]{"12"}, 0, 0, 0).goal());
        assertEquals(new GoalBlock(5, 6, 7), GoalParser.parse(new String[]{}, 5, 6, 7).goal());
        assertNull(GoalParser.parse(new String[]{"clear"}, 0, 0, 0).goal());
    }

    @Test
    void parserRelativeAndFallback() {
        GoalParser.ParsedGoal p = GoalParser.parse(new String[]{"~", "~-2", "~10", "TRUE"}, 100, 64, -5);
        assertEquals(new GoalBlock(100, 62, 5), p.goal());
        assertTrue(p.fallback());
        assertFalse(GoalParser.parse(new String[]{"1", "2", "3", "false"}, 0, 0, 0).fallback());
    }

    @Test
    void parserGarbage() {
        assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"a", "b", "c"}, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"1", "2", "3", "4"}, 0, 0, 0));
    }

    @Test
    void nonCommandsPassThrough() {
        assertFalse(handler().handle("hello"));
    }

    @Test
    void gotoStartsPathing() {
        assertTrue(handler().handle("#goto 10 1 0"));
        assertEquals(PathingSession.State.PATHING, session.snapshot().state());
    }

    @Test
    void pathWithoutGoal() {
        handler().handle("#path");
        assertTrue(replies.contains("No goal set"));
    }

    @Test
    void rejectsUnstandableExactGoal() {
        CommandHandler h = handler();
        settings.allowBreak.set(false);
        settings.allowPlace.set(false);
        h.handle("#goal 5 0 5");
        assertTrue(replies.get(0).startsWith("Can't stand at"));
    }

    @Test
    void acceptsAirGoalWhenItCanBuild() {
        handler().handle("#goal 5 4 5");
        assertTrue(replies.get(0).startsWith("Goal set"), replies.toString());
    }

    @Test
    void badGoalArgsReplyInsteadOfThrowing() {
        assertTrue(handler().handle("#goal a b c"));
        assertEquals("Not a coordinate: a", replies.get(0));
    }

    @Test
    void setPersists() {
        handler().handle("#set maxFallHeight 5");
        assertEquals(5, settings.maxFallHeight.get());
        assertEquals(1, saved);
    }

    @Test
    void customPrefix() {
        CommandHandler h = handler();
        h.handle("#set prefix !");
        assertTrue(h.handle("!stop"));
        assertFalse(h.handle("#stop"));
    }

    @Test
    void clickSetsTheGoalFromTheGame() {
        CommandHandler h = handler();
        assertTrue(h.handle("#click"));
        assertEquals("Clicking isn't available here", replies.get(0));
        h.setGoal(new GoalBlock(3, 1, 4));
        assertEquals(new GoalBlock(3, 1, 4), h.goal());
        assertTrue(h.handle("#path"));
        assertEquals(PathingSession.State.PATHING, session.snapshot().state());
    }

    @Test
    void selPicksCornersThenFillsOrClears() {
        CommandHandler h = handler();
        h.handle("#sel fill stone");
        assertTrue(replies.get(replies.size() - 1).contains("pos1"), replies.toString());
        h.handle("#sel pos1");
        h.handle("#sel pos2 ~3 ~2 ~-1");
        h.handle("#sel fill stone");
        h.handle("#sel clear");
        h.handle("#sort");
        h.handle("#sort 10");
        assertEquals(List.of("fill 0 1 -1 to 3 3 0 minecraft:stone", "clear 0 1 -1 to 3 3 0", "sort 16", "sort 10"), mined);
        h.handle("#sel reset");
        assertNull(h.selection().box());
    }

    @Test
    void breedAndFish() {
        CommandHandler h = handler();
        for (String c : new String[]{"#breed", "#breed cow sheep 3", "#breed 2", "#fish", "#fish 10"}) h.handle(c);
        assertEquals(List.of("breed [] 0", "breed [minecraft:cow, minecraft:sheep] 3", "breed [] 2", "fish 0", "fish 10"), mined);
    }

    @Test
    void iceboat() {
        CommandHandler h = handler();
        for (String c : new String[]{"#iceboat", "#iceboat lap 3", "#iceboat record track1", "#iceboat line off", "#iceboat 100 -50",
            "#iceboat ~10 64 ~"}) h.handle(c);
        assertEquals(List.of("iceboat line 1 null", "iceboat lap 3 null", "iceboat record 0 track1", "iceboat use 0 off",
            "iceboat goal [100, " + Integer.MIN_VALUE + ", -50] 0 null", "iceboat goal [10, 64, 0] 0 null"), mined);
    }

    @Test
    void getItemDryRun() {
        CommandHandler h = handler();
        h.handle("#getitem iron_pickaxe 2 dry");
        assertEquals(1, mined.size());
        assertTrue(mined.get(0).startsWith("dry "), mined.toString());
        assertTrue(mined.get(0).contains("iron_pickaxe"), mined.toString());
    }

    @Test
    void thenChainsCommands() {
        CommandHandler h = handler();
        h.handle("#mine 8 iron_ore then smelt raw_iron 8");
        h.handle("#farm then deposit");
        assertEquals(List.of("run null [#mine 8 iron_ore, #smelt raw_iron 8] x1", "run null [#farm, #deposit] x0"), mined);
    }

    @Test
    void queueAndRoutines() {
        CommandHandler h = handler();
        h.handle("#queue mine 8 iron_ore ; #smelt raw_iron 8");
        h.handle("#routine add iron mine 8 iron_ore ; smelt raw_iron 8 ; deposit iron_ingot");
        h.handle("#routine iron 3");
        h.handle("#routine run iron forever");
        h.handle("#routine nope");
        assertEquals(List.of("run null [#mine 8 iron_ore, #smelt raw_iron 8] x1",
            "run iron [#mine 8 iron_ore, #smelt raw_iron 8, #deposit iron_ingot] x3",
            "run iron [#mine 8 iron_ore, #smelt raw_iron 8, #deposit iron_ingot] x0"), mined);
        assertTrue(replies.get(replies.size() - 1).contains("No routine nope"), replies.toString());
    }

    @Test
    void buildCommands() {
        CommandHandler h = handler();
        for (String c : new String[]{"#build house", "#build house 10 ~ -5", "#build list", "#build materials house"}) h.handle(c);
        assertEquals(List.of("schem build house", "schem build house [10, 1, -5]", "schem list null", "schem materials house"), mined);
    }

    @Test
    void bucketHuntAndSmithParse() {
        CommandHandler h = handler();
        for (String c : new String[]{"#bucket water", "#bucket lava 3", "#bucket milk 2", "#bucket obsidian 4", "#bucket sand",
            "#hunt string 20", "#hunt bone", "#hunt", "#smith netherite_pickaxe", "#smith netherite_sword 2"}) h.handle(c);
        assertEquals(List.of("bucket minecraft:water_bucket 1", "bucket minecraft:lava_bucket 3", "bucket minecraft:milk_bucket 2",
            "bucket minecraft:obsidian 4", "hunt string 20", "hunt bone 1", "smith netherite_pickaxe 1", "smith netherite_sword 2"),
            mined.stream().map(m -> m.replace("ItemCount[item=", "").replace(", count=", " ").replace("]", "")).toList());
        assertTrue(replies.stream().anyMatch(r -> r.contains("Usage: bucket")), replies.toString());
        assertTrue(replies.stream().anyMatch(r -> r.contains("Usage: hunt")), replies.toString());
    }

    @Test
    void numbersAreCheckedTheSameEverywhere() {
        CommandHandler h = handler();
        for (String c : new String[]{"#explore a b", "#tunnel x 1 1", "#fly 1 q", "#iceboat lap z", "#fish soon", "#farm big"}) {
            replies.clear();
            h.handle(c);
            assertTrue(replies.get(0).contains("must be a number") || replies.get(0).contains("Usage"), c + ": " + replies);
        }
        replies.clear();
        h.handle("#explore a b");
        assertTrue(replies.get(0).contains("'a' must be a number"), replies.toString());
        mined.clear();
        h.handle("#explore ~5 ~-3");
        h.handle("#fish 5");
        h.handle("#fish");
        assertEquals(List.of("explore 5 -3", "fish 5", "fish 0"), mined);
    }

    @Test
    void stopSaysStoppedOnceOnlyWhenSomethingRan() {
        CommandHandler h = handler();
        busy = true;
        h.handle("#stop");
        assertEquals(1, replies.stream().filter(r -> r.contains("Stopped")).count(), replies.toString());
        replies.clear();
        busy = false;
        h.handle("#cancel");
        assertTrue(replies.stream().noneMatch(r -> r.contains("Stopped")), replies.toString());
    }

    @Test
    void thenKnowsEveryCommandWord() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/lazuli/core/command/CommandHandler.java"));
        String run = src.substring(src.indexOf("private void run("), src.indexOf("private void help("));
        var labels = new java.util.TreeSet<String>();
        var m = java.util.regex.Pattern.compile("(?m)^            case ((?:\"[a-z]+\"(?:, )?)+) ->").matcher(run);
        while (m.find()) {
            var w = java.util.regex.Pattern.compile("\"([a-z]+)\"").matcher(m.group(1));
            while (w.find()) labels.add(w.group(1));
        }
        assertFalse(labels.isEmpty());
        assertEquals(labels, new java.util.TreeSet<>(CommandHandler.COMMANDS));
    }
}
