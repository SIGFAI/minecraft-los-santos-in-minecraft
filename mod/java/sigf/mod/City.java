package sigf.mod;

import com.mojang.math.Transformation;
import java.util.ArrayDeque;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sigf.kit.Sigf;

/**
 * A blocky Los Santos around the stream spawn. Coordinates passed to the helpers are relative to the plaza
 * (x east, z south, y 0 = the standing level; the street surface is at y -1).
 */
public final class City {
	public static int ox, oy, oz;           // plaza center, standing level
	public static final int[] ROADS = {-48, -16, 16, 48};
	public static final int HALF = 74;      // city half size
	static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
	public static volatile boolean ready = false;
	static ServerLevel lv;
	static final Random rng = new Random(4242);

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState ASPHALT = conc(DyeColor.BLACK).defaultBlockState();
	static final BlockState ASPHALT2 = conc(DyeColor.GRAY).defaultBlockState();
	static final BlockState WALK = conc(DyeColor.LIGHT_GRAY).defaultBlockState();
	static final BlockState YELLOW = conc(DyeColor.YELLOW).defaultBlockState();
	static final BlockState WHITE = conc(DyeColor.WHITE).defaultBlockState();

	static Block conc(DyeColor c) { return Blocks.CONCRETE.pick(c); }
	static Block glass(DyeColor c) { return Blocks.STAINED_GLASS.pick(c); }
	static Block terra(DyeColor c) { return Blocks.DYED_TERRACOTTA.pick(c); }

	private City() {}

	// ---------- primitives ----------
	static BlockPos P(int x, int y, int z) { return new BlockPos(ox + x, oy + y, oz + z); }
	public static void set(int x, int y, int z, BlockState s) { lv.setBlock(P(x, y, z), s, 18); }
	public static void set(int x, int y, int z, Block b) { set(x, y, z, b.defaultBlockState()); }
	public static BlockState get(int x, int y, int z) { return lv.getBlockState(P(x, y, z)); }
	public static void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, s);
	}
	static void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) { fill(x1, y1, z1, x2, y2, z2, b.defaultBlockState()); }
	static boolean isRoad(int c) { for (int r : ROADS) if (Math.abs(c - r) <= 4) return true; return false; }
	/** Nearest road center line along an axis. */
	static boolean sidewalk(int c) { for (int r : ROADS) if (Math.abs(c - r) <= 6 && Math.abs(c - r) > 4) return true; return false; }

	/** Floating text sign (a text display), facing yaw degrees (0 = readable from the south, 180 = from the north). */
	public static Display.TextDisplay sign(String text, double x, double y, double z, float yaw, float scale, int argb) {
		Display.TextDisplay d = EntityTypes.TEXT_DISPLAY.create(lv, EntitySpawnReason.COMMAND);
		d.snapTo(ox + x, oy + y, oz + z, yaw, 0f);
		d.setText(Component.literal(text));
		d.setBackgroundColor(0);
		d.setTextOpacity((byte) -1);
		d.setViewRange(4f);
		d.setFlags((byte) (Display.TextDisplay.FLAG_SEE_THROUGH));
		d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		d.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
		d.setText(Component.literal(text).withColor(argb));
		lv.addFreshEntity(d);
		return d;
	}

	// ---------- build queue ----------
	static void task(Runnable r) { tasks.add(r); }

	/** Runs queued work for about 25 ms of this tick. */
	public static void tick() {
		if (tasks.isEmpty()) return;
		long end = System.nanoTime() + 25_000_000L;
		while (!tasks.isEmpty() && System.nanoTime() < end) {
			Runnable r = tasks.poll();
			try { r.run(); } catch (Throwable e) { Sigf.log("SIGF_ERROR city " + e); e.printStackTrace(); }
		}
		if (tasks.isEmpty()) { ready = true; Sigf.log("city ready"); }
	}

	public static void build(int cx, int cy, int cz) {
		ox = cx; oy = cy; oz = cz; lv = Sigf.level();
		// 1. flatten the whole stage, hill included
		final int X0 = -112, X1 = 112, Z0 = -150, Z1 = 112;
		for (int zz = Z0; zz <= Z1; zz += 8) {
			final int z0 = zz;
			task(() -> { for (int z = z0; z < z0 + 8 && z <= Z1; z++) for (int x = X0; x <= X1; x++) column(x, z); });
		}
		// 2. streets
		task(City::streets);
		// 3. plots
		int[][] plots = {{-72, -55}, {-41, -23}, {-9, 9}, {23, 41}, {55, 72}};
		for (int a = 0; a < 5; a++) for (int b = 0; b < 5; b++) {
			final int pa = a, pb = b;
			task(() -> plot(pa, pb, plots[pa][0], plots[pa][1], plots[pb][0], plots[pb][1]));
		}
		// 4. plaza, trees, lamps, sign
		task(City::plaza);
		task(City::lamps);
		task(City::palms);
		task(City::signals);
		task(City::vinewood);
	}

	static void column(int x, int z) {
		int top = lv.getHeight(Heightmap.Types.WORLD_SURFACE, ox + x, oz + z);
		int hh = hillHeight(x, z);
		int surf = oy - 1 + hh;
		for (int y = surf + 1; y < top + 1; y++) lv.setBlock(new BlockPos(ox + x, y, oz + z), AIR, 18);
		int low = Math.min(top - 1, oy - 6);
		for (int y = Math.max(low, oy - 8); y <= surf; y++) {
			BlockState s;
			if (y == surf) s = hh > 0 ? hillSurface(x, z) : Blocks.GRASS_BLOCK.defaultBlockState();
			else s = y > surf - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
			lv.setBlock(new BlockPos(ox + x, y, oz + z), s, 18);
		}
		if (hh > 3 && y0(x, z) && rng.nextInt(60) == 0) lv.setBlock(new BlockPos(ox + x, surf + 1, oz + z), Blocks.DEAD_BUSH.defaultBlockState(), 18);
	}
	static BlockState hillSurface(int x, int z) {
		int h = (((x * 73856093) ^ (z * 19349663)) >>> 7) % 20;
		if (h < 8) return Blocks.COARSE_DIRT.defaultBlockState();
		if (h < 12) return Blocks.GRASS_BLOCK.defaultBlockState();
		if (h < 16) return Blocks.TERRACOTTA.defaultBlockState();
		return Blocks.RED_SAND.defaultBlockState();
	}
	static boolean y0(int x, int z) { return true; }

	/** The Vinewood hills north of town: a gentle ramp up to a plateau behind the sign. */
	static int hillHeight(int x, int z) {
		if (z > -80) return 0;
		double tz = Math.min(1.0, (-80.0 - z) / 42.0);
		double s = tz * tz * (3 - 2 * tz);
		double ax = Math.min(1.0, Math.max(0.0, (Math.abs(x) - 62.0) / 48.0));
		double fx = 1 - ax * ax * (3 - 2 * ax);
		return (int) Math.round(HILL * s * fx);
	}
	static final int HILL = 24;

	// ---------- streets ----------
	static void streets() {
		for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) {
			boolean rx = isRoad(x), rz = isRoad(z);
			BlockState s;
			if (rx || rz) {
				s = ASPHALT;
				// lane markings
				for (int r : ROADS) {
					if (rx && !rz && x == r && (z & 3) < 2) s = YELLOW;
					if (rz && !rx && z == r && (x & 3) < 2) s = YELLOW;
					if (rx && !rz && Math.abs(x - r) == 4 && false) s = WHITE;
				}
				// crosswalks at intersections edges
				if (rx && rz) s = ASPHALT;
				else if (rx && nearCross(z) && (z + 100) % 2 == 0) s = WHITE;
				else if (rz && nearCross(x) && (x + 100) % 2 == 0) s = WHITE;
			} else if (sidewalk(x) || sidewalk(z)) s = WALK;
			else s = Blocks.STONE_BRICKS.defaultBlockState();
			set(x, -1, z, s);
		}
		// intersections: yellow stays off
		for (int a : ROADS) for (int b : ROADS) fill(a - 4, -1, b - 4, a + 4, -1, b + 4, ASPHALT);
	}
	static boolean nearCross(int c) { for (int r : ROADS) if (Math.abs(c - r) <= 6 && Math.abs(c - r) >= 5) return true; return false; }

	// ---------- plots ----------
	static final BlockState[] STUCCO = {
		conc(DyeColor.WHITE).defaultBlockState(), terra(DyeColor.ORANGE).defaultBlockState(), conc(DyeColor.PINK).defaultBlockState(),
		terra(DyeColor.YELLOW).defaultBlockState(), conc(DyeColor.LIGHT_BLUE).defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
		terra(DyeColor.CYAN).defaultBlockState(), conc(DyeColor.LIME).defaultBlockState()};

	static void plot(int a, int b, int x1, int x2, int z1, int z2) {
		// special plots
		if (a == 2 && b == 2) return; // plaza
		if (a == 2 && b == 3) { lspd(x1, x2, z1, z2); return; }
		if (a == 0 && b == 2) { gasStation(x1, x2, z1, z2); return; }
		if (a == 4 && b == 2) { burger(x1, x2, z1, z2); return; }
		if (a == 2 && b == 1) { park(x1, x2, z1, z2); return; }
		if (a == 2 && b == 0 || a == 2 && b == 4) { lot(x1, x2, z1, z2); return; }
		int kind = rng.nextInt(10);
		int mx = x1 + 1 + rng.nextInt(2), Mx = x2 - 1 - rng.nextInt(2), mz = z1 + 1 + rng.nextInt(2), Mz = z2 - 1 - rng.nextInt(2);
		boolean tower = (a == 1 || a == 3) && (b == 1 || b == 3) || kind >= 8;
		if (tower) {
			int h = 34 + rng.nextInt(20) - (b == 0 || b == 4 ? 10 : 0);
			box(mx, mz, Mx, Mz, h, conc(DyeColor.GRAY).defaultBlockState(), rng.nextBoolean() ? glass(DyeColor.LIGHT_BLUE).defaultBlockState() : glass(DyeColor.CYAN).defaultBlockState(), true);
			fill(mx + 2, h + 1, mz + 2, mx + 3, h + 6, mz + 3, Blocks.IRON_BARS.defaultBlockState());
			set(mx + 2, h + 7, mz + 2, Blocks.REDSTONE_BLOCK);
			String[] names = {"PACIFIC BANK", "VESPUCCI TOWER", "ROCKFORD PLAZA", "SAN ANDREAS OIL", "DELPERRO TRUST", "WEAZEL NEWS"};
			String nm = names[(a * 5 + b) % names.length];
			sign(nm, (mx + Mx) / 2.0 + 0.5, 6.0, Mz + 1.6, 0f, 4f, 0xFFFFFFFF);
		} else {
			int h = 8 + rng.nextInt(8);
			BlockState wall = STUCCO[rng.nextInt(STUCCO.length)];
			box(mx, mz, Mx, Mz, h, wall, Blocks.GLASS.defaultBlockState(), false);
			// awning + sign
			String[] shops = {"LIQUOR", "HOTEL", "PAWN", "TACOS", "DINER", "CLUB", "BARBER", "VINEWOOD INN", "NAIL BAR", "RECORDS"};
			int[] cols = {0xFFFF55FF, 0xFF55FFFF, 0xFFFFAA00, 0xFFFF5555, 0xFF55FF55};
			sign(shops[rng.nextInt(shops.length)], (mx + Mx) / 2.0 + 0.5, 4.2, Mz + 1.6, 0f, 3.2f, cols[rng.nextInt(cols.length)]);
			// roof tank
			fill(mx + 2, h + 1, mz + 2, mx + 3, h + 2, mz + 3, Blocks.BARREL.defaultBlockState());
		}
	}

	/** Some windows glow warm, so the skyline is alive at sunset. */
	static BlockState lit(int x, int y, int z, BlockState glass) {
		int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
		return ((h >>> 5) % 100) < 17 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : glass;
	}

	/** Hollow building with window bands, floors every 4 blocks, a door on the south side. */
	static void box(int x1, int z1, int x2, int z2, int h, BlockState wall, BlockState glass, boolean curtain) {
		for (int y = 0; y < h; y++) {
			for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				if (!edge) continue;
				boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
				BlockState s;
				if (corner) s = wall;
				else if (curtain) s = (x - x1) % 4 == 0 || (z - z1) % 4 == 0 ? wall : lit(x, y, z, glass);
				else if (y % 4 == 1 || y % 4 == 2) s = ((x + z) % 3 == 0) ? wall : lit(x, y, z, glass);
				else s = wall;
				if (y == 0 && z == z2 && Math.abs(x - (x1 + x2) / 2) <= 1 && y < 3) s = AIR;
				if (y == 1 && z == z2 && Math.abs(x - (x1 + x2) / 2) <= 1) s = AIR;
				if (y == 2 && z == z2 && Math.abs(x - (x1 + x2) / 2) <= 1) s = AIR;
				set(x, y, z, s);
			}
		}
		fill(x1, -1, z1, x2, -1, z2, Blocks.SMOOTH_STONE);
		for (int y = 3; y <= h; y += 4) {
			fill(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, y == h ? wall : Blocks.SMOOTH_STONE.defaultBlockState());
			for (int x = x1 + 3; x < x2 - 1; x += 5) for (int z = z1 + 3; z < z2 - 1; z += 5) if (y < h) set(x, y, z, Blocks.SEA_LANTERN);
		}
		fill(x1, h, z1, x2, h, z2, wall);
		// parapet
		for (int x = x1; x <= x2; x++) { set(x, h + 1, z1, wall); set(x, h + 1, z2, wall); }
		for (int z = z1; z <= z2; z++) { set(x1, h + 1, z, wall); set(x2, h + 1, z, wall); }
	}

	static void lot(int x1, int x2, int z1, int z2) {
		fill(x1, -1, z1, x2, -1, z2, ASPHALT2);
		for (int x = x1 + 1; x < x2; x += 4) fill(x, -1, z1 + 1, x, -1, z2 - 1, (z1 + x) % 8 == 0 ? WHITE : ASPHALT2);
		for (int x = x1 + 1; x <= x2 - 1; x += 4) fill(x, -1, z1 + 1, x, -1, z1 + 5, WHITE);
		sign("PARKING", (x1 + x2) / 2.0 + 0.5, 2.5, z2 - 0.5, 0f, 3f, 0xFFFFFF55);
	}

	static void park(int x1, int x2, int z1, int z2) {
		fill(x1, -1, z1, x2, -1, z2, Blocks.GRASS_BLOCK);
		for (int i = 0; i < 6; i++) {
			int x = x1 + 2 + rng.nextInt(x2 - x1 - 3), z = z1 + 2 + rng.nextInt(z2 - z1 - 3);
			palm(x, z, 6 + rng.nextInt(3));
		}
		fill((x1 + x2) / 2 - 1, -1, z1, (x1 + x2) / 2 + 1, -1, z2, Blocks.DIRT_PATH);
	}

	static void gasStation(int x1, int x2, int z1, int z2) {
		fill(x1, -1, z1, x2, -1, z2, ASPHALT2);
		// canopy
		int cx1 = x1 + 2, cx2 = x2 - 2, cz1 = z1 + 3, cz2 = z1 + 11;
		fill(cx1, 5, cz1, cx2, 5, cz2, conc(DyeColor.WHITE));
		fill(cx1, 6, cz1, cx2, 6, cz1, conc(DyeColor.RED));
		fill(cx1, 6, cz2, cx2, 6, cz2, conc(DyeColor.RED));
		for (int x : new int[]{cx1, cx2}) for (int z : new int[]{cz1, cz2}) fill(x, 0, z, x, 4, z, conc(DyeColor.WHITE));
		for (int x = cx1 + 3; x <= cx2 - 3; x += 4) {
			fill(x, 0, z1 + 7, x, 1, z1 + 7, conc(DyeColor.RED));
			set(x, 2, z1 + 7, Blocks.IRON_BLOCK);
		}
		for (int x = cx1 + 1; x < cx2; x += 3) set(x, 4, cz1 + 4, Blocks.SEA_LANTERN);
		box(x1 + 2, z2 - 6, x2 - 2, z2 - 1, 5, conc(DyeColor.WHITE).defaultBlockState(), Blocks.GLASS.defaultBlockState(), false);
		sign("PUMP & GO", (cx1 + cx2) / 2.0 + 0.5, 7.2, cz2 + 0.2, 0f, 5f, 0xFFFF3333);
		sign("24/7 MART", (x1 + x2) / 2.0 + 0.5, 3.4, z2 - 0.4 + 0.1, 0f, 3f, 0xFF55FF55);
	}

	static void burger(int x1, int x2, int z1, int z2) {
		fill(x1, -1, z1, x2, -1, z2, ASPHALT2);
		box(x1 + 2, z1 + 2, x2 - 2, z1 + 12, 6, conc(DyeColor.ORANGE).defaultBlockState(), Blocks.GLASS.defaultBlockState(), false);
		fill(x1 + 2, 7, z1 + 2, x2 - 2, 8, z1 + 12, conc(DyeColor.RED));
		// giant burger on the roof
		int bx = (x1 + x2) / 2, bz = z1 + 7;
		fill(bx - 2, 9, bz - 2, bx + 2, 9, bz + 2, Blocks.HAY_BLOCK);
		fill(bx - 2, 10, bz - 2, bx + 2, 10, bz + 2, conc(DyeColor.GREEN));
		fill(bx - 2, 11, bz - 2, bx + 2, 11, bz + 2, conc(DyeColor.BROWN));
		fill(bx - 2, 12, bz - 2, bx + 2, 12, bz + 2, terra(DyeColor.ORANGE));
		fill(bx - 1, 13, bz - 1, bx + 1, 13, bz + 1, terra(DyeColor.ORANGE));
		sign("BURGER JOINT", bx + 0.5, 5.0, z1 + 12.7, 0f, 3f, 0xFFFFFF00);
	}

	static void lspd(int x1, int x2, int z1, int z2) {
		fill(x1, -1, z1, x2, -1, z2, WALK);
		BlockState wall = conc(DyeColor.BLUE).defaultBlockState();
		box(x1 + 1, z1 + 1, x2 - 1, z2 - 2, 14, wall, glass(DyeColor.LIGHT_BLUE).defaultBlockState(), false);
		fill(x1 + 1, 6, z2 - 2, x2 - 1, 6, z2 - 2, conc(DyeColor.WHITE));
		// helipad
		int hx = (x1 + x2) / 2;
		fill(hx - 3, 15, z1 + 3, hx + 3, 15, z1 + 9, conc(DyeColor.GRAY));
		fill(hx - 1, 15, z1 + 4, hx - 1, 15, z1 + 8, conc(DyeColor.YELLOW));
		fill(hx + 1, 15, z1 + 4, hx + 1, 15, z1 + 8, conc(DyeColor.YELLOW));
		fill(hx - 1, 15, z1 + 6, hx + 1, 15, z1 + 6, conc(DyeColor.YELLOW));
		sign("LSPD", hx + 0.5, 9.0, z2 - 1.4, 0f, 6f, 0xFFFFFFFF);
		sign("LOS SANTOS POLICE DEPT", hx + 0.5, 5.0, z2 - 1.4, 0f, 2.2f, 0xFFFFD700);
		// parked cruisers in front
		police = new int[]{hx - 6, z2 - 0, hx + 6};
	}
	public static int[] police = {0, 0, 0};

	// ---------- plaza ----------
	static void plaza() {
		fill(-9, -1, -9, 9, -1, 9, Blocks.SMOOTH_SANDSTONE);
		fill(-9, -1, -9, 9, -1, -9, Blocks.CUT_SANDSTONE);
		// checkerboard
		for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) if ((x + z) % 2 == 0 && (Math.abs(x) > 3 || Math.abs(z) > 3)) set(x, -1, z, Blocks.SANDSTONE);
		// fountain
		fill(-3, -1, -3, 3, 0, 3, Blocks.QUARTZ_BLOCK);
		fill(-2, 0, -2, 2, 0, 2, Blocks.WATER);
		fill(0, 0, 0, 0, 3, 0, Blocks.QUARTZ_PILLAR);
		set(0, 4, 0, Blocks.WATER);
		sign("LOS SANTOS", 0.5, 6.6, 0.5, 0f, 4f, 0xFFFFAA00);
		sign("CITY OF DREAMS", 0.5, 5.6, 0.5, 0f, 2f, 0xFFFFFFFF);
		sign("LOS SANTOS", 0.5, 6.6, 0.5, 180f, 4f, 0xFFFFAA00);
		sign("CITY OF DREAMS", 0.5, 5.6, 0.5, 180f, 2f, 0xFFFFFFFF);
		for (int[] p : new int[][]{{-7, -7}, {7, -7}, {-7, 7}, {7, 7}}) palm(p[0], p[1], 6 + rng.nextInt(2));
	}

	// ---------- street furniture ----------
	static void lamps() {
		for (int r : ROADS) for (int t = -68; t <= 68; t += 12) {
			if (isRoad(t)) continue;
			lamp(r + 5, t); lamp(r - 5, t + 6);
			lamp(t, r + 5); lamp(t + 6, r - 5);
		}
	}
	static void lamp(int x, int z) {
		if (!sidewalk(x) && !sidewalk(z)) return;
		if (isRoad(x) && isRoad(z)) return;
		if (Math.abs(x) > HALF || Math.abs(z) > HALF) return;
		if (get(x, -1, z).getBlock() != conc(DyeColor.LIGHT_GRAY)) return;
		fill(x, 0, z, x, 5, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
		set(x, 6, z, Blocks.SEA_LANTERN);
	}

	static void palms() {
		for (int r : ROADS) for (int t = -66; t <= 66; t += 13) {
			palmSide(r + 6, t); palmSide(r - 6, t + 4); palmSide(t, r + 6); palmSide(t + 4, r - 6);
		}
	}
	static void palmSide(int x, int z) {
		if (Math.abs(x) > HALF || Math.abs(z) > HALF || isRoad(x) && isRoad(z)) return;
		if (get(x, -1, z).getBlock() != conc(DyeColor.LIGHT_GRAY)) return;
		if (get(x, 0, z).getBlock() != Blocks.AIR) return;
		if (rng.nextInt(5) < 2) return;
		if (Math.abs(x - 16) <= 8 && z < 0 && z > -78) return;   // keep the view down the main avenue to the Vinewood sign clear
		palm(x, z, 7 + rng.nextInt(4));
	}

	public static void palm(int x, int z, int h) {
		BlockState log = Blocks.JUNGLE_LOG.defaultBlockState();
		BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		int lean = rng.nextInt(4), dx = lean == 0 ? 1 : lean == 1 ? -1 : 0, dz = lean == 2 ? 1 : lean == 3 ? -1 : 0;
		int cx = x, cz = z;
		for (int y = 0; y < h; y++) {
			if (y == h / 2 || y == h * 3 / 4) { cx += dx; cz += dz; }
			set(cx, y, cz, log);
		}
		int ty = h;
		set(cx, ty, cz, leaf);
		set(cx, ty + 1, cz, leaf);
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs) {
			for (int i = 1; i <= 3; i++) if (get(cx + d[0] * i, ty, cz + d[1] * i).isAir()) set(cx + d[0] * i, ty - (i == 3 ? 1 : 0), cz + d[1] * i, leaf);
			if (get(cx + d[0] * 4, ty - 1, cz + d[1] * 4).isAir()) set(cx + d[0] * 4, ty - 2, cz + d[1] * 4, leaf);
		}
		for (int[] d : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
			if (get(cx + d[0], ty, cz + d[1]).isAir()) set(cx + d[0], ty, cz + d[1], leaf);
			if (get(cx + d[0] * 2, ty - 1, cz + d[1] * 2).isAir()) set(cx + d[0] * 2, ty - 1, cz + d[1] * 2, leaf);
		}
	}

	// ---------- traffic signals ----------
	static final java.util.List<int[]> poles = new java.util.ArrayList<>();   // x, z, axis (0 = east-west traffic)
	static final int[] lastState = {-1, -1};

	static void signals() {
		for (int a : ROADS) for (int b : ROADS) {
			int[][] corners = {{a + 6, b - 6, 0}, {a - 6, b + 6, 0}, {a - 6, b - 6, 1}, {a + 6, b + 6, 1}};
			for (int[] k : corners) {
				fill(k[0], 0, k[1], k[0], 4, k[1], Blocks.COBBLESTONE_WALL.defaultBlockState());
				fill(k[0], 5, k[1], k[0], 7, k[1], conc(DyeColor.BLACK));
				poles.add(k);
			}
		}
		lastState[0] = lastState[1] = -1;
	}

	/** Recolors the signals when a phase changes (called every few ticks). */
	public static void tickSignals(long now) {
		if (!ready) return;
		for (int axis = 0; axis < 2; axis++) {
			int st = Car.light(axis, now);
			if (st == lastState[axis]) continue;
			lastState[axis] = st;
			for (int[] k : poles) {
				if (k[2] != axis) continue;
				set(k[0], 7, k[1], st == 2 ? conc(DyeColor.RED) : conc(DyeColor.BLACK));
				set(k[0], 6, k[1], st == 1 ? conc(DyeColor.YELLOW) : conc(DyeColor.BLACK));
				set(k[0], 5, k[1], st == 0 ? conc(DyeColor.LIME) : conc(DyeColor.BLACK));
			}
		}
	}

	// ---------- the sign ----------
	static final String[][] FONT = {
		{"10001", "10001", "10001", "10001", "01010", "01010", "00100"}, // V
		{"11111", "00100", "00100", "00100", "00100", "00100", "11111"}, // I
		{"10001", "11001", "10101", "10011", "10001", "10001", "10001"}, // N
		{"11111", "10000", "10000", "11110", "10000", "10000", "11111"}, // E
		{"10001", "10001", "10001", "10101", "10101", "11011", "10001"}, // W
		{"01110", "10001", "10001", "10001", "10001", "10001", "01110"}, // O
		{"01110", "10001", "10001", "10001", "10001", "10001", "01110"}, // O
		{"11110", "10001", "10001", "10001", "10001", "10001", "11110"}, // D
	};
	/** Scale of one font pixel in blocks. */
	static final int PX = 2;
	public static final int SIGN_Z = -124;

	static void vinewood() {
		int total = 8 * 5 * PX + 7 * 3;     // letters + gaps
		int x0 = -total / 2;
		for (int i = 0; i < 8; i++) {
			int lx = x0 + i * (5 * PX + 3);
			int base = hillHeight(lx + 5, SIGN_Z) + 3;
			for (int py = 0; py < 7; py++) for (int px = 0; px < 5; px++) {
				if (FONT[i][py].charAt(px) != '1') continue;
				for (int a = 0; a < PX; a++) for (int b = 0; b < PX; b++)
					for (int dz = 0; dz < 2; dz++)
						set(lx + px * PX + a, base + (6 - py) * PX + b, SIGN_Z + dz, dz == 0 ? Blocks.SEA_LANTERN : conc(DyeColor.WHITE));
			}
			// scaffold legs
			for (int leg : new int[]{lx + 1, lx + 5 * PX - 2}) {
				int g = hillHeight(leg, SIGN_Z);
				fill(leg, g, SIGN_Z + 1, leg, base, SIGN_Z + 1, Blocks.IRON_BARS.defaultBlockState());
			}
		}
		// red beacon lights
		set(x0 - 2, hillHeight(x0 - 2, SIGN_Z) + 4, SIGN_Z, Blocks.REDSTONE_LAMP);
	}
}
