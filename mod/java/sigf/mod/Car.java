package sigf.mod;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sigf.kit.Sigf;

/**
 * A car: an invisible, scaled pig gives the hitbox and the health; the body is a stack of block displays
 * (real 3D, painted with the game's own blocks). Modes: TRAFFIC (follows the street grid and the traffic
 * lights), POLICE (grid driving, hunts the target), PLAYER (steered by the driver's keys), PARKED, WRECK.
 */
public final class Car {
	static final net.minecraft.core.particles.DustParticleOptions RED_GLOW = new net.minecraft.core.particles.DustParticleOptions(0xFF2020, 1.3f);
	static final net.minecraft.core.particles.DustParticleOptions BLUE_GLOW = new net.minecraft.core.particles.DustParticleOptions(0x2060FF, 1.3f);
	public enum Mode { TRAFFIC, POLICE, PLAYER, PARKED, WRECK }
	public static final List<Car> ALL = new ArrayList<>();
	/** Where the player's car has been: the police follows this breadcrumb trail. */
	public static final List<Vec3> TRAIL = new ArrayList<>();
	static final double LANE = 2.25;
	static final double SEAT = 0.87 * 1.9;      // pig passenger attachment height
	static final int[] DX = {1, 0, -1, 0}, DZ = {0, 1, 0, -1};
	static final int[] OFF = {1, -1, -1, 1};

	record Part(float x, float y, float z, float sx, float sy, float sz, BlockState state, float pitch) {
		Part(float x, float y, float z, float sx, float sy, float sz, BlockState state) { this(x, y, z, sx, sy, sz, state, 0f); }
	}

	public Mode mode;
	public Pig body;
	final List<Display.BlockDisplay> disp = new ArrayList<>();
	final List<Part> parts = new ArrayList<>();
	public double x, z, y, speed, topSpeed;
	public double hp, maxHp;
	public float yaw;                       // radians, heading vector = (sin, cos)
	int d;                                   // grid direction 0 E, 1 S, 2 W, 3 N
	double turnAt = Double.NaN; int turnTo = -1; double stopAt = Double.NaN;
	public ServerPlayer driver;
	ArmorStand seat;
	boolean police;
	int lightA, lightB;                      // indexes of the flashing light-bar parts
	int age, stuck;
	public int wreckTicks;
	double lastHealth;
	float shownYaw = 9999;
	public Entity chaseTarget;
	public boolean arrived;
	int sirenCd;
	boolean gone;
	boolean inTrail;
	public double cap = 0.7;
	public boolean unloaded, decor;
	public final List<Vec3> path = new ArrayList<>();   // demo autopilot waypoints

	// ---------- models ----------
	static Block conc(DyeColor c) { return Blocks.CONCRETE.pick(c); }
	public static DyeColor nextPaint;
	static final DyeColor[] PAINT = {DyeColor.RED, DyeColor.YELLOW, DyeColor.BLUE, DyeColor.WHITE, DyeColor.ORANGE, DyeColor.LIME, DyeColor.PINK, DyeColor.CYAN, DyeColor.PURPLE, DyeColor.GRAY};

	void addParts(boolean cop, DyeColor paint, int kind) {
		BlockState P = conc(paint).defaultBlockState();
		BlockState black = conc(DyeColor.BLACK).defaultBlockState();
		BlockState glass = Blocks.TINTED_GLASS.defaultBlockState();
		BlockState white = conc(DyeColor.WHITE).defaultBlockState();
		BlockState gray = conc(DyeColor.GRAY).defaultBlockState();
		if (cop) {
			parts.add(new Part(-0.9f, 0.3f, -1.7f, 1.8f, 0.55f, 3.4f, black));
			parts.add(new Part(-0.93f, 0.35f, -0.95f, 1.86f, 0.5f, 1.5f, white));          // white doors
			parts.add(new Part(-0.8f, 0.85f, -1.0f, 1.6f, 0.5f, 1.6f, glass));
			parts.add(new Part(-0.8f, 1.35f, -1.0f, 1.6f, 0.08f, 1.6f, white));
			parts.add(new Part(-0.8f, 1.43f, 0.6f, 1.6f, 0.05f, 0.85f, glass, 40f));
			parts.add(new Part(-0.6f, 0.38f, 1.72f, 1.2f, 0.3f, 0.05f, black));             // push bar
			lightA = parts.size();
			parts.add(new Part(-0.6f, 1.43f, -0.35f, 0.6f, 0.2f, 0.5f, Blocks.REDSTONE_BLOCK.defaultBlockState()));
			lightB = parts.size();
			parts.add(new Part(0.0f, 1.43f, -0.35f, 0.6f, 0.2f, 0.5f, Blocks.LAPIS_BLOCK.defaultBlockState()));
		} else if (kind == 1) {
			// pickup: cab at the front, open bed behind
			parts.add(new Part(-0.9f, 0.3f, -1.7f, 1.8f, 0.55f, 3.4f, P));
			parts.add(new Part(-0.85f, 0.85f, 0.0f, 1.7f, 0.5f, 1.0f, glass));
			parts.add(new Part(-0.9f, 1.35f, -0.1f, 1.8f, 0.1f, 1.2f, P));
			parts.add(new Part(-0.85f, 1.43f, 1.1f, 1.7f, 0.05f, 0.8f, glass, 40f));
			parts.add(new Part(-0.9f, 0.85f, -1.7f, 0.12f, 0.35f, 1.7f, P));
			parts.add(new Part(0.78f, 0.85f, -1.7f, 0.12f, 0.35f, 1.7f, P));
			parts.add(new Part(-0.9f, 0.85f, -1.7f, 1.8f, 0.35f, 0.12f, P));
			parts.add(new Part(-0.78f, 0.86f, -1.58f, 1.56f, 0.04f, 1.5f, gray));
		} else {
			parts.add(new Part(-0.9f, 0.3f, -1.7f, 1.8f, 0.55f, 3.4f, P));
			parts.add(new Part(-0.8f, 0.85f, -1.0f, 1.6f, 0.5f, 1.6f, glass));
			parts.add(new Part(-0.8f, 1.35f, -1.0f, 1.6f, 0.08f, 1.6f, P));
			parts.add(new Part(-0.8f, 1.43f, 0.6f, 1.6f, 0.05f, 0.85f, glass, 40f));
			if (kind == 2) {
				parts.add(new Part(-0.75f, 0.86f, -1.7f, 1.5f, 0.08f, 0.2f, black));     // spoiler deck
				parts.add(new Part(-0.8f, 0.8f, -1.7f, 0.1f, 0.2f, 0.2f, black));
				parts.add(new Part(0.7f, 0.8f, -1.7f, 0.1f, 0.2f, 0.2f, black));
				parts.add(new Part(-0.2f, 0.86f, 0.6f, 0.4f, 0.015f, 1.1f, white));      // racing stripes
				parts.add(new Part(-0.2f, 1.436f, -1.0f, 0.4f, 0.015f, 1.6f, white));
			}
		}
		for (float wx : new float[]{-1.0f, 0.75f}) for (float wz : new float[]{-1.35f, 0.75f})
			parts.add(new Part(wx, 0.0f, wz, 0.25f, 0.6f, 0.6f, black));
		parts.add(new Part(-0.75f, 0.5f, 1.68f, 0.45f, 0.18f, 0.06f, Blocks.GLOWSTONE.defaultBlockState()));
		parts.add(new Part(0.3f, 0.5f, 1.68f, 0.45f, 0.18f, 0.06f, Blocks.GLOWSTONE.defaultBlockState()));
		parts.add(new Part(-0.8f, 0.5f, -1.74f, 0.5f, 0.18f, 0.06f, Blocks.REDSTONE_BLOCK.defaultBlockState()));
		parts.add(new Part(0.3f, 0.5f, -1.74f, 0.5f, 0.18f, 0.06f, Blocks.REDSTONE_BLOCK.defaultBlockState()));
		parts.add(new Part(-0.9f, 0.3f, 1.7f, 1.8f, 0.2f, 0.1f, gray));                  // bumpers
		parts.add(new Part(-0.9f, 0.3f, -1.8f, 1.8f, 0.2f, 0.1f, gray));
		parts.add(new Part(-1.0f, 0.95f, 0.45f, 0.12f, 0.12f, 0.22f, black));            // mirrors
		parts.add(new Part(0.88f, 0.95f, 0.45f, 0.12f, 0.12f, 0.22f, black));
		parts.add(new Part(-0.25f, 0.42f, -1.82f, 0.5f, 0.16f, 0.03f, white));           // number plate
	}

	// ---------- creation ----------
	public static Car create(Mode mode, double x, double z, int dir, boolean cop, int style) {
		ServerLevel lv = Sigf.level();
		Car c = new Car();
		c.mode = mode; c.police = cop;
		c.x = x; c.z = z; c.y = City.oy; c.d = dir;
		c.yaw = (float) Math.atan2(DX[dir], DZ[dir]);
		c.maxHp = cop ? 45 : 35; c.hp = c.maxHp;
		c.topSpeed = cop ? 0.6 : 0.3 + Math.random() * 0.08;
		Pig pig = EntityTypes.PIG.create(lv, EntitySpawnReason.COMMAND);
		pig.getAttribute(Attributes.SCALE).setBaseValue(1.9);
		pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
		pig.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(1.0);
		pig.setHealth(1000f);
		pig.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false)); pig.setSilent(true); pig.setNoAi(true); pig.setPersistenceRequired();
		pig.snapTo(x, c.y, z, (float) Math.toDegrees(-c.yaw), 0f);
		lv.addFreshEntity(pig);
		c.body = pig;
		c.lastHealth = 1000;
		DyeColor paint = nextPaint != null ? nextPaint : PAINT[(int) (Math.random() * PAINT.length)];
		nextPaint = null;
		c.addParts(cop, paint, style);
		for (int i = 0; i < c.parts.size(); i++) {
			Display.BlockDisplay bd = EntityTypes.BLOCK_DISPLAY.create(lv, EntitySpawnReason.COMMAND);
			bd.setBlockState(c.parts.get(i).state());
			bd.snapTo(x, c.y, z);
			bd.setPosRotInterpolationDuration(3);
			bd.setTransformationInterpolationDuration(3);
			bd.setViewRange(2.5f);
			bd.setBrightnessOverride(new net.minecraft.util.Brightness(12, 14));
			if (i == 0) { bd.setShadowRadius(1.3f); bd.setShadowStrength(0.8f); }
			lv.addFreshEntity(bd);
			bd.startRiding(pig, true, false);
			c.disp.add(bd);
		}
		c.applyTransforms(0);
		ALL.add(c);
		if (mode == Mode.TRAFFIC || mode == Mode.POLICE) c.planNext();
		return c;
	}

	void applyTransforms(int interp) {
		Quaternionf q = new Quaternionf().rotationY(yaw);
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			Vector3f t = new Vector3f(p.x(), p.y(), p.z());
			q.transform(t);
			t.y -= (float) SEAT;
			Display.BlockDisplay bd = disp.get(i);
			bd.setTransformationInterpolationDelay(0);
			bd.setTransformationInterpolationDuration(interp);
			Quaternionf rot = new Quaternionf(q);
			if (p.pitch() != 0f) rot.rotateX((float) Math.toRadians(p.pitch()));
			bd.setTransformation(new Transformation(t, rot, new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
		}
		shownYaw = yaw;
	}

	// ---------- grid helpers ----------
	double baseX() { return City.ox + 0.5; }
	double baseZ() { return City.oz + 0.5; }
	boolean xAxis() { return d % 2 == 0; }
	double along() { return xAxis() ? x : z; }
	int sgn() { return d == 0 || d == 1 ? 1 : -1; }

	/** Chooses the next intersection ahead and what to do there. */
	void planNext() {
		turnAt = Double.NaN; turnTo = -1; stopAt = Double.NaN;
		double pos = along();
		double base = xAxis() ? baseX() : baseZ();
		double best = Double.NaN;
		for (int L : City.ROADS) {
			double c = base + L;
			if (sgn() * (c - pos) > 5.5 && (Double.isNaN(best) || sgn() * (c - best) < 0)) best = c;
		}
		if (Double.isNaN(best)) return;
		stopAt = best;
		int act = choose(best);
		turnTo = (d + (act == 0 ? 0 : act == 1 ? 1 : 3)) % 4;
		if (act == 0) turnAt = Double.NaN;
		else {
			// on the line 'best' the new lane lies at OFF[turnTo] * LANE from its center
			turnAt = best + OFF[turnTo] * LANE;
		}
	}

	/** 0 straight, 1 right, 2 left. */
	int choose(double lineCoord) {
		if (mode == Mode.POLICE && chaseTarget != null && chaseTarget.isAlive()) {
			Vec3 t = chaseTarget.position();
			int bestAct = 0; double bestD = 1e9;
			for (int act = 0; act < 3; act++) {
				int nd = (d + (act == 0 ? 0 : act == 1 ? 1 : 3)) % 4;
				// position after the intersection, 12 blocks beyond it along the new direction
				double px, pz;
				if (xAxis()) { px = lineCoord; pz = z; } else { pz = lineCoord; px = x; }
				px += DX[nd] * 14; pz += DZ[nd] * 14;
				double dd = (px - t.x) * (px - t.x) + (pz - t.z) * (pz - t.z);
				if (act != 0 && Math.abs(px - baseX()) > City.HALF - 2 || Math.abs(pz - baseZ()) > City.HALF - 2) dd += 1e6;
				if (dd < bestD) { bestD = dd; bestAct = act; }
			}
			return bestAct;
		}
		double r = Math.random();
		return r < 0.55 ? 0 : r < 0.78 ? 1 : 2;
	}

	/** Light for an axis (0 = east/west traffic, 1 = north/south): 0 green, 1 yellow, 2 red. */
	public static int light(int axis, long tick) {
		double t = (tick / 20.0) % 24;
		double e = axis == 0 ? t : (t + 12) % 24;
		return e < 10 ? 0 : e < 12 ? 1 : 2;
	}

	// ---------- per tick ----------
	public static void tickAll() {
		for (var it = ALL.iterator(); it.hasNext(); ) {
			Car c = it.next();
			try { if (!c.tick()) { c.cleanup(); it.remove(); } }
			catch (Throwable e) { Sigf.log("SIGF_ERROR car " + e); e.printStackTrace(); c.cleanup(); it.remove(); }
		}
	}

	boolean tick() {
		age++;
		if (!body.isAlive() || body.isRemoved()) { if (mode != Mode.WRECK) wreck(); return false; }
		// damage taken goes to our own hit points
		float h = body.getHealth();
		double lost = lastHealth - h;
		if (lost > 0.01) {
			hp -= lost;
			body.setHealth(1000f); lastHealth = 1000;
			Sigf.sound(SoundEvents.IRON_GOLEM_HURT, pos(), 0.8f, 1.5f);
			Sigf.particles(ParticleTypes.CRIT, pos().add(0, 1, 0), 10, 0.5);
			Sigf.particles(ParticleTypes.ELECTRIC_SPARK, pos().add(0, 1, 0), 8, 0.6);
			if (mode == Mode.PARKED && !police) mode = Mode.TRAFFIC;
		}
		if (mode == Mode.WRECK) {
			wreckTicks--;
			if (wreckTicks % 3 == 0) Sigf.particles(ParticleTypes.LARGE_SMOKE, pos().add(0, 1.4, 0), 2, 0.4);
			if (wreckTicks % 8 == 0) Sigf.particles(ParticleTypes.FLAME, pos().add(0, 1.0, 0), 3, 0.5);
			return wreckTicks > 0;
		}
		if (hp <= 0) { wreck(); return true; }
		if (hp < maxHp * 0.4 && age % 4 == 0) Sigf.particles(ParticleTypes.SMOKE, pos().add(0, 1.2, 0), 2, 0.3);
		if (hp < maxHp * 0.2 && age % 3 == 0) Sigf.particles(ParticleTypes.FLAME, pos().add(0, 0.9, 0), 2, 0.3);
		if (police) flash();
		if (mode == Mode.PLAYER) drive();
		else if (mode == Mode.TRAFFIC || mode == Mode.POLICE) gridDrive();
		else { speed = 0; syncDisplay(); }
		if (mode != Mode.PARKED) crush();
		return !gone;
	}

	Vec3 pos() { return body.position(); }

	void flash() {
		boolean a = (age / 6) % 2 == 0;
		if (mode == Mode.WRECK) return;
		disp.get(lightA).setBlockState(a ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState());
		disp.get(lightB).setBlockState(a ? Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE).defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState());
		if (age % 6 == 0) Sigf.level().sendParticles(a ? RED_GLOW : BLUE_GLOW, x, y + 2.0, z, 3, 0.35, 0.1, 0.35, 0);
		if (--sirenCd <= 0 && mode == Mode.POLICE) { sirenCd = 62; Sigf.sound(Sounds.SIREN, pos(), 1.4f, 1f); }
	}

	/** Police on the player's tail: follow the breadcrumb trail of the player's car. */
	boolean trailChase() {
		Car pc = chaseTarget instanceof ServerPlayer sp ? driven(sp) : null;
		if (pc == null || TRAIL.size() < 10) return false;
		int newest = TRAIL.size() - 1;
		// nearest trail point to us
		int j = -1; double bd = 1e9;
		for (int i = 0; i <= newest; i++) { double dd = Math.hypot(TRAIL.get(i).x - x, TRAIL.get(i).z - z); if (dd < bd) { bd = dd; j = i; } }
		if (bd > 14) return false;
		// the point we should not pass: about 11 blocks behind the player's car
		int lag = newest; double acc = 0;
		while (lag > 0 && acc < 14) { acc += TRAIL.get(lag).distanceTo(TRAIL.get(lag - 1)); lag--; }
		int ti = Math.min(j + 4, newest);
		boolean waiting = j >= lag;
		Vec3 tp = TRAIL.get(Math.min(ti, newest));
		if (waiting) tp = pc.pos();
		double want = Math.atan2(tp.x - x, tp.z - z), diff = want - yaw;
		while (diff > Math.PI) diff -= 2 * Math.PI;
		while (diff < -Math.PI) diff += 2 * Math.PI;
		yaw += (float) Math.max(-0.14, Math.min(0.14, diff));
		double dist = Math.hypot(pc.x - x, pc.z - z);
		double tgt = waiting ? Math.max(0, Math.min(topSpeed, pc.speed + (dist - 13) * 0.05)) : topSpeed + Math.min(0.3, Math.max(0, (dist - 22) * 0.02));
		speed += Math.max(-0.06, Math.min(0.03, tgt - speed));
		if (speed < 0) speed = 0;
		x += Math.sin(yaw) * speed; z += Math.cos(yaw) * speed;
		y += (tp.y - y) * 0.25;
		inTrail = true;
		if (dist < 34 && age % 34 == 0 && Math.random() < 0.8) {
			// officers lean out and open fire on the player's car
			ServerLevel lv = Sigf.level();
			Vec3 from = pos().add(0, 1.7, 0), to = pc.pos().add((Math.random() - .5) * 2.5, 1.0, (Math.random() - .5) * 2.5);
			Vec3 dirv = to.subtract(from).normalize();
			for (double t = 2; t < from.distanceTo(to); t += 1.5) lv.sendParticles(ParticleTypes.CRIT, from.x + dirv.x * t, from.y + dirv.y * t, from.z + dirv.z * t, 1, 0, 0, 0, 0);
			Sigf.sound(Sounds.GUNSHOT, from, 1.4f, 0.9f);
			if (Math.random() < 0.3) { pc.hp -= 1; Sigf.particles(ParticleTypes.CRIT, pc.pos().add(0, 1.2, 0), 6, 0.5); }
		}
		arrived = dist < 16 && pc.speed < 0.05;
		body.snapTo(x, y, z, (float) Math.toDegrees(-yaw), 0f);
		body.setDeltaMovement(Vec3.ZERO);
		applyTransforms(2);
		return true;
	}

	void gridDrive() {
		if (mode == Mode.POLICE) {
			if (trailChase()) return;
			if (inTrail) {
				inTrail = false;
				double h = (yaw % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
				d = h < Math.PI / 4 || h >= 7 * Math.PI / 4 ? 1 : h < 3 * Math.PI / 4 ? 0 : h < 5 * Math.PI / 4 ? 3 : 2;
				yaw = (float) Math.atan2(DX[d], DZ[d]);
				planNext();
			}
		}
		double target = topSpeed;
		boolean hunting = mode == Mode.POLICE && chaseTarget != null;
		if (hunting) {
			double dist = pos().distanceTo(chaseTarget.position());
			if (dist < 14) { arrived = true; target = 0; }
			else if (dist < 28) target = topSpeed * 0.7;
		}
		// traffic lights (civilians only: the police ignores them)
		if (mode == Mode.TRAFFIC && !Double.isNaN(stopAt)) {
			double dist = sgn() * (stopAt - along());
			if (dist > 4.8 && dist < 9.5) {
				int st = light(xAxis() ? 0 : 1, Sigf.level().getGameTime());
				if (st == 2 || st == 1 && dist > 6.5) target = Math.min(target, Math.max(0, (dist - 5.2) * 0.08));
			}
		}
		// cars and people ahead
		double ahead = 99;
		for (Car o : ALL) {
			if (o == this || o.mode == Mode.WRECK || o.mode == Mode.PARKED) continue;
			double fx = o.x - x, fz = o.z - z;
			double fwd = fx * DX[d] + fz * DZ[d], lat = Math.abs(fx * DZ[d] - fz * DX[d]);
			if (fwd > 0 && fwd < 9 && lat < 1.8) ahead = Math.min(ahead, fwd);
		}
		if (mode == Mode.TRAFFIC) {
			AABB box = AABB.ofSize(new Vec3(x + DX[d] * 3.2, y + 1, z + DZ[d] * 3.2), 2.4, 2, 2.4);
			for (Entity e : Sigf.level().getEntities((Entity) null, box, e -> e instanceof LivingEntity && e != body && e.isAlive() && !(e instanceof Pig) && !(e instanceof ArmorStand)))
				ahead = Math.min(ahead, 3.8);
		}
		if (ahead < 9) target = Math.min(target, Math.max(0, (ahead - 3.9) * 0.1));
		speed += Math.max(-0.05, Math.min(0.02, target - speed));
		if (speed < 0) speed = 0;
		double step = speed;
		double pos = along();
		if (!Double.isNaN(turnAt) && turnTo >= 0 && sgn() * (pos + sgn() * step - turnAt) >= 0) {
			// reach the turn point, turn, and drive the rest of the step in the new direction
			double over = sgn() * (pos + sgn() * step - turnAt);
			if (xAxis()) x = turnAt; else z = turnAt;
			d = turnTo; yaw = (float) Math.atan2(DX[d], DZ[d]);
			x += DX[d] * over; z += DZ[d] * over;
			planNext();
			applyTransforms(4);
		} else {
			x += DX[d] * step; z += DZ[d] * step;
			if (!Double.isNaN(stopAt) && sgn() * (along() - stopAt) > 5.5) planNext();
			if (Double.isNaN(stopAt)) { /* leaving the map */ }
		}
		// settle on the lane
		double lane;
		if (xAxis()) { lane = City.oz + 0.5 + nearestLine(z - baseZ()) + OFF[d] * LANE; z += (lane - z) * 0.2; }
		else { lane = City.ox + 0.5 + nearestLine(x - baseX()) + OFF[d] * LANE; x += (lane - x) * 0.2; }
		syncDisplay();
		// leave the map
		if (Math.abs(x - baseX()) > City.HALF + 8 || Math.abs(z - baseZ()) > City.HALF + 8) gone = true;
	}

	static double nearestLine(double rel) {
		double best = City.ROADS[0]; double bd = 1e9;
		for (int L : City.ROADS) { double dd = Math.abs(rel - L - 0.0); if (dd < bd) { bd = dd; best = L; } }
		return best;
	}

	void syncDisplay() {
		body.snapTo(x, y, z, (float) Math.toDegrees(-yaw), 0f);
		body.setDeltaMovement(Vec3.ZERO);
		if (seat != null) seat.snapTo(x, y - 1.05, z, (float) Math.toDegrees(-yaw), 0f);
	}

	// ---------- player driving ----------
	void drive() {
		if (driver == null || !driver.isAlive() || driver.getVehicle() != seat) { exit(); return; }
		Input in = driver.getLastClientInput();
		if (!path.isEmpty()) in = autopilot(in);
		double acc = 0.028;
		if (in.forward()) speed += speed < 0 ? 0.06 : acc;
		else if (in.backward()) speed -= speed > 0.02 ? 0.06 : 0.02;
		else speed *= 0.975;
		if (in.jump()) speed *= 0.88;      // handbrake
		speed = Math.max(-0.25, Math.min(cap, speed));
		if (Math.abs(speed) < 0.004) speed = 0;
		double turn = 0.065 * Math.min(1.0, Math.abs(speed) / 0.25) * Math.signum(speed);
		if (in.left()) yaw += turn;
		if (in.right()) yaw -= turn;
		double dx = Math.sin(yaw) * speed, dz = Math.cos(yaw) * speed;
		body.setDeltaMovement(Vec3.ZERO);
		Vec3 before = body.position();
		body.move(MoverType.SELF, new Vec3(dx, -0.4, dz));
		Vec3 after = body.position();
		double moved = Math.hypot(after.x - before.x, after.z - before.z);
		if (moved < Math.abs(speed) * 0.5 && Math.abs(speed) > 0.2) {
			// hit a wall
			Sigf.sound(SoundEvents.ANVIL_LAND, after, 0.5f, 1.4f);
			Sigf.particles(ParticleTypes.CRIT, after.add(0, 1, 0), 8, 0.6);
			if (Math.abs(speed) > 0.4) hp -= 6;
			speed *= -0.2;
		}
		x = after.x; y = after.y; z = after.z;
		if (TRAIL.isEmpty() || TRAIL.get(TRAIL.size() - 1).distanceTo(after) >= 1.3) { TRAIL.add(after); if (TRAIL.size() > 600) TRAIL.remove(0); }
		applyTransforms(2);
		seat.snapTo(x, y - 1.05, z, (float) Math.toDegrees(-yaw), 0f);
		if (age % 50 == 0 && Math.abs(speed) > 0.1) Sigf.sound(Sounds.ENGINE, pos(), 0.9f, 0.9f + (float) Math.abs(speed));
		if (age % 7 == 0 && Math.abs(speed) > 0.3) Sigf.particles(ParticleTypes.CLOUD, new Vec3(x - Math.sin(yaw) * 1.6, y + 0.2, z - Math.cos(yaw) * 1.6), 1, 0.2);
	}

	int pathIdx;
	/** Pure pursuit along the polyline 'path' (demo bot): aims at a point 9 blocks ahead on it. */
	Input autopilot(Input fallback) {
		int n = path.size();
		if (n == 1) path.add(0, new Vec3(x, y, z));
		n = path.size();
		pathIdx = Math.max(0, Math.min(pathIdx, n - 2));
		// closest point on the next few segments
		double bestD = 1e9; int bi = pathIdx; double bt = 0;
		for (int i = pathIdx; i < Math.min(n - 1, pathIdx + 3); i++) {
			Vec3 a = path.get(i), b = path.get(i + 1);
			double abx = b.x - a.x, abz = b.z - a.z, len2 = abx * abx + abz * abz;
			double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((x - a.x) * abx + (z - a.z) * abz) / len2));
			double px = a.x + abx * t, pz = a.z + abz * t, dd = Math.hypot(x - px, z - pz);
			if (dd < bestD) { bestD = dd; bi = i; bt = t; }
		}
		pathIdx = bi;
		// walk 9 blocks ahead
		double rem = 6; int i = bi; Vec3 a = path.get(i), b = path.get(i + 1);
		double segLen = a.distanceTo(b); double pos = bt * segLen;
		Vec3 tgt = b;
		while (true) {
			double left = segLen - pos;
			if (rem <= left) { double f = (pos + rem) / segLen; tgt = a.add(b.subtract(a).scale(f)); break; }
			rem -= left; i++;
			if (i >= n - 1) { tgt = path.get(n - 1); break; }
			a = path.get(i); b = path.get(i + 1); segLen = a.distanceTo(b); pos = 0;
		}
		Vec3 end = path.get(n - 1);
		if (bi >= n - 2 && Math.hypot(end.x - x, end.z - z) < 4) { path.clear(); pathIdx = 0; return new Input(false, true, false, false, false, false, false); }
		double want = Math.atan2(tgt.x - x, tgt.z - z), diff = want - yaw;
		while (diff > Math.PI) diff -= 2 * Math.PI;
		while (diff < -Math.PI) diff += 2 * Math.PI;
		double lim = cap * Math.max(0.4, 1.0 - Math.abs(diff) * 0.9);
		boolean slow = speed > lim + 0.02;
		return new Input(!slow && speed < lim, slow && speed > lim + 0.12, diff > 0.03, diff < -0.03, false, false, false);
	}

	public void enter(ServerPlayer p) {
		if (driver != null || mode == Mode.WRECK) return;
		driver = p;
		if (mode == Mode.POLICE) police = true;
		mode = Mode.PLAYER;
		speed = Math.min(speed, 0.2);
		seat = EntityTypes.ARMOR_STAND.create(Sigf.level(), EntitySpawnReason.COMMAND);
		seat.setInvisible(true); seat.setNoGravity(true);
		seat.snapTo(x, y - 1.05, z, p.getYRot(), 0f);
		Sigf.level().addFreshEntity(seat);
		p.startRiding(seat, true, false);
		Sigf.sound(SoundEvents.IRON_DOOR_CLOSE, pos(), 1f, 1.2f);
	}

	void exit() {
		if (driver != null && driver.getVehicle() == seat) {
			driver.stopRiding();
			Sigf.teleport(driver, new Vec3(x + Math.cos(yaw) * 2.6, y + 0.2, z - Math.sin(yaw) * 2.6), Vec3.ZERO);
		}
		if (seat != null) { seat.discard(); seat = null; }
		driver = null;
		mode = Mode.PARKED; speed = 0;
	}

	// ---------- running people over ----------
	void crush() {
		if (Math.abs(speed) < 0.28 || mode != Mode.PLAYER) return;     // only the player's car runs people over
		AABB box = body.getBoundingBox().inflate(0.4, 0.2, 0.4).move(Math.sin(yaw) * speed * 3, 0, Math.cos(yaw) * speed * 3);
		for (Entity e : Sigf.level().getEntities((Entity) null, box, e -> e instanceof LivingEntity && e != body && e != driver && e.isAlive() && !(e instanceof Pig && isCarBody(e)) && !(e instanceof ArmorStand))) {
			LivingEntity le = (LivingEntity) e;
			if (le.getInvulnerableTime() > 5) continue;
			le.addTag("shot");
			le.hurtServer(Sigf.level(), mode == Mode.PLAYER && driver != null ? Sigf.level().damageSources().generic() : Sigf.level().damageSources().generic(), (float) (Math.abs(speed) * 18));
			le.setDeltaMovement(Math.sin(yaw) * speed * 1.4, 0.45, Math.cos(yaw) * speed * 1.4);
			le.syncVelocity = true;
			Sigf.particles(ParticleTypes.CRIT, le.position().add(0, 1, 0), 12, 0.4);
			Sigf.sound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, le.position(), 1f, 0.8f);
		}
	}

	public static Car driven(ServerPlayer p) { for (Car c : ALL) if (c.driver == p && c.mode == Mode.PLAYER) return c; return null; }
	public static boolean isCarBody(Entity e) { return byBody(e) != null; }
	public static Car byBody(Entity e) { for (Car c : ALL) if (c.body == e) return c; return null; }

	// ---------- death ----------
	void wreck() {
		if (mode == Mode.WRECK) return;
		if (driver != null) exit();
		mode = Mode.WRECK; wreckTicks = 220; speed = 0;
		Vec3 p = pos().add(0, 1, 0);
		Sigf.level().explode(null, p.x, p.y, p.z, 2.6f, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
		Sigf.particles(ParticleTypes.EXPLOSION_EMITTER, p, 1, 0.1);
		Sigf.particles(ParticleTypes.FLAME, p, 40, 1.2);
		Sigf.particles(ParticleTypes.LARGE_SMOKE, p, 30, 1.0);
		BlockState burnt = Blocks.BLACKSTONE.defaultBlockState();
		for (int i = 0; i < disp.size(); i++) {
			Block b = parts.get(i).state().getBlock();
			if (b == Blocks.TINTED_GLASS) continue;
			disp.get(i).setBlockState(i % 3 == 0 ? Blocks.COAL_BLOCK.defaultBlockState() : burnt);
		}
		if (body.isAlive()) { body.setHealth(1000f); }
		if (police) Wanted.copCarDestroyed(this);
	}

	void cleanup() {
		if (driver != null) exit();
		for (Display.BlockDisplay bd : disp) if (!bd.isRemoved()) bd.discard();
		if (!body.isRemoved()) body.discard();
		if (seat != null) seat.discard();
	}

	// ---------- spawning traffic ----------
	/** A civilian car at a random lane position (initial fill, or at the map edge when edge = true). */
	public static Car spawnTraffic(boolean edge) {
		for (int tries = 0; tries < 12; tries++) {
			int dir = (int) (Math.random() * 4);
			int L = City.ROADS[(int) (Math.random() * City.ROADS.length)];
			double a = edge ? -(City.HALF - 3.0) : (Math.random() * 2 - 1) * (City.HALF - 8);
			double sg = (dir == 0 || dir == 1) ? 1 : -1;
			double along = edge ? a * sg : a;
			double x, z;
			if (dir % 2 == 0) { x = City.ox + 0.5 + along; z = City.oz + 0.5 + L + OFF[dir] * LANE; }
			else { z = City.oz + 0.5 + along; x = City.ox + 0.5 + L + OFF[dir] * LANE; }
			boolean clash = false;
			for (Car o : ALL) if (Math.hypot(o.x - x, o.z - z) < 9) clash = true;
			if (clash) continue;
			// not inside an intersection
			if (!edge && isNearIntersection(x, z)) continue;
			int style = Math.random() < 0.25 ? 1 : Math.random() < 0.2 ? 2 : 0;
			return create(Mode.TRAFFIC, x, z, dir, false, style);
		}
		return null;
	}

	static boolean isNearIntersection(double x, double z) {
		boolean nx = false, nz = false;
		for (int L : City.ROADS) {
			if (Math.abs(x - (City.ox + 0.5 + L)) < 9) nx = true;
			if (Math.abs(z - (City.oz + 0.5 + L)) < 9) nz = true;
		}
		return nx && nz;
	}

	/** Cars parked at the curb (half on the sidewalk), and cruisers outside the LSPD station. */
	public static void spawnParked(int n) {
		for (int tries = 0; tries < n * 6 && n > 0; tries++) {
			int dir = (int) (Math.random() * 4);
			int L = City.ROADS[(int) (Math.random() * City.ROADS.length)];
			double along = (Math.random() * 2 - 1) * (City.HALF - 10);
			double side = Math.random() < 0.5 ? -3.9 : 3.9;
			double x, z;
			if (dir % 2 == 0) { x = City.ox + 0.5 + along; z = City.oz + 0.5 + L + side; dir = side > 0 ? 0 : 2; }
			else { z = City.oz + 0.5 + along; x = City.ox + 0.5 + L + side; dir = side > 0 ? 3 : 1; }
			if (isNearIntersection(x, z)) continue;
			boolean clash = false;
			for (Car o : ALL) if (Math.hypot(o.x - x, o.z - z) < 5) clash = true;
			if (Math.hypot(x - (City.ox + 16.5), z - (City.oz - 22)) < 14) clash = true;     // keep the demo start clear
			if (clash) continue;
			create(Mode.PARKED, x, z, dir, false, Math.random() < 0.25 ? 1 : 0);
			n--;
		}
	}

	public static void spawnStationCruisers() {
		double z = City.oz + 0.5 + 48 - 3.9;
		for (double rx : new double[]{-6.5, 6.5}) {
			Car c = create(Mode.PARKED, City.ox + 0.5 + rx, z, rx < 0 ? 2 : 0, true, 0);
			c.decor = true; c.unloaded = true;
		}
	}

	public static int count(Mode m) { int n = 0; for (Car c : ALL) if (c.mode == m) n++; return n; }
	public static int civilians() { int n = 0; for (Car c : ALL) if (!c.police && c.mode == Mode.TRAFFIC) n++; return n; }
}
