package sigf.mod;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sigf.kit.Sigf;

/** The LSPD helicopter: a block-built chopper with spinning blades that circles the player, lights them up and shoots. */
public final class Heli {
	record Part(float x, float y, float z, float sx, float sy, float sz, BlockState s, int kind) {}   // kind 0 body, 1 rotor, 2 tail rotor, 3 light
	final Mob body;
	final List<Display.BlockDisplay> disp = new ArrayList<>();
	final List<Part> parts = new ArrayList<>();
	double x, y, z, vx, vz;
	float yaw;
	double hp = 70;
	double angle = Math.random() * 6.28;
	int age, crash = -1;
	float spin;
	double seatY;
	boolean leaving, gone;
	double lastH = 1000;

	static Block conc(DyeColor c) { return Blocks.CONCRETE.pick(c); }

	public static Heli spawn(ServerPlayer p) {
		ServerLevel lv = Sigf.level();
		Heli h = new Heli(lv, p);
		Sigf.log("helicopter spawned");
		Sigf.sound(Sounds.RADIO, p.position(), 1.5f, 1f);
		return h;
	}

	Heli(ServerLevel lv, ServerPlayer p) {
		double a = Math.random() * 6.28;
		x = p.getX() + Math.cos(a) * 70; z = p.getZ() + Math.sin(a) * 70; y = City.oy + 30;
		BlockState blue = conc(DyeColor.BLUE).defaultBlockState(), white = conc(DyeColor.WHITE).defaultBlockState(), black = conc(DyeColor.BLACK).defaultBlockState();
		BlockState glass = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
		parts.add(new Part(-0.9f, 0f, -1.6f, 1.8f, 1.5f, 3.4f, blue, 0));
		parts.add(new Part(-0.95f, 0.5f, -1.0f, 1.9f, 0.3f, 2.6f, white, 0));
		parts.add(new Part(-0.8f, 0.55f, 1.0f, 1.6f, 0.85f, 1.1f, glass, 0));
		parts.add(new Part(-0.25f, 0.7f, -4.9f, 0.5f, 0.5f, 3.4f, blue, 0));
		parts.add(new Part(-0.1f, 0.7f, -5.0f, 0.2f, 1.3f, 0.7f, white, 0));
		parts.add(new Part(-1.1f, -0.45f, -1.5f, 0.15f, 0.15f, 3.2f, black, 0));
		parts.add(new Part(0.95f, -0.45f, -1.5f, 0.15f, 0.15f, 3.2f, black, 0));
		parts.add(new Part(-1.0f, -0.4f, -0.6f, 0.12f, 0.5f, 0.12f, black, 0));
		parts.add(new Part(0.88f, -0.4f, -0.6f, 0.12f, 0.5f, 0.12f, black, 0));
		parts.add(new Part(-0.15f, 1.5f, -0.15f, 0.3f, 0.4f, 0.3f, conc(DyeColor.GRAY).defaultBlockState(), 0));
		parts.add(new Part(0, 0, 0, 8.0f, 0.06f, 0.4f, black, 1));
		parts.add(new Part(0, 0, 0, 0.4f, 0.06f, 8.0f, black, 1));
		parts.add(new Part(0, 0, 0, 0.1f, 1.4f, 0.3f, black, 2));
		parts.add(new Part(-0.6f, 1.5f, 0.6f, 0.45f, 0.2f, 0.45f, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
		parts.add(new Part(0.15f, 1.5f, 0.6f, 0.45f, 0.2f, 0.45f, Blocks.LAPIS_BLOCK.defaultBlockState(), 4));
		parts.add(new Part(-0.5f, -0.1f, 1.7f, 1.0f, 0.3f, 0.1f, Blocks.SEA_LANTERN.defaultBlockState(), 0));
		Mob ph = EntityTypes.GHAST.create(lv, EntitySpawnReason.COMMAND);
		ph.getAttribute(Attributes.SCALE).setBaseValue(0.85);
		ph.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
		ph.setHealth(1000f);
		ph.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		ph.setSilent(true); ph.setNoAi(true); ph.setNoGravity(true); ph.setPersistenceRequired();
		ph.addTag("lspd");
		ph.snapTo(x, y, z, 0f, 0f);
		lv.addFreshEntity(ph);
		body = ph;
		for (Part pt : parts) {
			Display.BlockDisplay bd = EntityTypes.BLOCK_DISPLAY.create(lv, EntitySpawnReason.COMMAND);
			bd.setBlockState(pt.s());
			bd.snapTo(x, y, z);
			bd.setViewRange(4f);
			bd.setBrightnessOverride(new net.minecraft.util.Brightness(13, 15));
			lv.addFreshEntity(bd);
			bd.startRiding(ph, true, false);
			disp.add(bd);
		}
		seatY = ph.getPassengerRidingPosition(disp.get(0)).y - ph.getY();
		transforms(0);
	}

	public boolean alive() { return !gone && crash < 0 && body.isAlive(); }
	public Vec3 pos() { return new Vec3(x, y, z); }

	void transforms(int interp) {
		Quaternionf q = new Quaternionf().rotationY(yaw);
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			Display.BlockDisplay bd = disp.get(i);
			Vector3f t;
			Quaternionf rot = new Quaternionf(q);
			if (p.kind() == 1) {
				// spins around the hub, which sits at (0, 1.9, 0) in the chopper's frame
				Quaternionf r = new Quaternionf(q).rotateY(spin);
				Vector3f half = new Vector3f(p.sx() / 2, 0, p.sz() / 2);
				r.transform(half);
				t = new Vector3f(0, 1.95f, 0); q.transform(t);
				t.sub(half);
				rot = r;
			} else if (p.kind() == 2) {
				// tail rotor, spins around X at the end of the tail boom
				Quaternionf r = new Quaternionf(q).rotateX(spin * 1.5f);
				Vector3f half = new Vector3f(0.05f, 0.7f, 0.15f);
				r.transform(half);
				t = new Vector3f(0.35f, 1.1f, -4.7f); q.transform(t);
				t.sub(half);
				rot = r;
			} else {
				t = new Vector3f(p.x(), p.y(), p.z());
				q.transform(t);
			}
			t.y -= (float) seatY;
			bd.setTransformationInterpolationDelay(0);
			bd.setTransformationInterpolationDuration(interp);
			bd.setTransformation(new Transformation(t, rot, new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
		}
	}

	public void leave() { leaving = true; clearLight(); }

	public void tick(ServerPlayer p) {
		age++;
		ServerLevel lv = Sigf.level();
		if (gone) return;
		// damage
		float h = body.getHealth();
		double lost = 1000 - h;
		if (lost > 0.01) {
			hp -= lost; body.setHealth(1000f);
			Sigf.sound(SoundEvents.IRON_GOLEM_HURT, pos(), 1f, 1.2f);
			Sigf.particles(ParticleTypes.ELECTRIC_SPARK, pos().add(0, 1, 0), 12, 0.9);
			Sigf.particles(ParticleTypes.SMOKE, pos().add(0, 1, 0), 5, 0.6);
			if (crash < 0) Wanted.hit(p, body);
		}
		// rockets: the ghast base shrugs off fireballs, so the blast is applied by hand
		for (var it = Weapons.rockets.iterator(); it.hasNext(); ) {
			var fb = it.next();
			if (fb.isAlive() && fb.position().distanceTo(pos().add(0, 1.2, 0)) < 3.6) {
				Vec3 q = fb.position();
				fb.discard(); it.remove();
				hp -= 45;
				lv.explode(null, q.x, q.y, q.z, 3f, Level.ExplosionInteraction.NONE);
				Sigf.particles(ParticleTypes.EXPLOSION_EMITTER, q, 1, 0);
				Sigf.particles(ParticleTypes.FLAME, q, 25, 0.8);
				Sigf.sound(SoundEvents.GENERIC_EXPLODE.value(), q, 3f, 1f);
				if (crash < 0) Wanted.hit(p, body);
			}
		}
		if (crash < 0 && hp <= 0) { clearLight(); crash = 0; vx = vz = 0; Wanted.crime(p, 4, "Helicopter down"); }
		if (crash >= 0) { crashTick(); return; }
		spin += 1.2f;
		if (age % 2 == 0) transforms(2);
		if (age % 5 == 0) {
			boolean a = (age / 5) % 2 == 0;
			disp.get(13).setBlockState(a ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState());
			disp.get(14).setBlockState(a ? Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE).defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState());
		}
		if (hp < 25 && age % 3 == 0) Sigf.particles(ParticleTypes.LARGE_SMOKE, pos().add(0, 1, -2), 2, 0.3);
		// flight: circle the player at 14 blocks, 15 up
		angle += 0.022;
		double tx, ty, tz;
		if (leaving) { tx = x + 60; ty = City.oy + 60; tz = z + 60; if (age % 10 == 0 && Math.hypot(x - p.getX(), z - p.getZ()) > 120) { gone = true; cleanup(); return; } }
		else { double rad = Sigf.isDemo() ? 10 : 14; tx = p.getX() + Math.cos(angle) * rad; tz = p.getZ() + Math.sin(angle) * rad; ty = Math.max(p.getY(), City.oy) + (Sigf.isDemo() ? 10 : 15); }
		double dx = tx - x, dz = tz - z, dy = ty - y;
		double dist = Math.sqrt(dx * dx + dz * dz);
		double sp = Math.min(0.75, dist * 0.08);
		vx += ((dist > 0.01 ? dx / dist * sp : 0) - vx) * 0.08;
		vz += ((dist > 0.01 ? dz / dist * sp : 0) - vz) * 0.08;
		x += vx; z += vz; y += Math.max(-0.35, Math.min(0.35, dy * 0.08));
		// face the player
		double fx = p.getX() - x, fz = p.getZ() - z;
		float want = (float) Math.atan2(fx, fz);
		float diff = want - yaw;
		while (diff > Math.PI) diff -= 2 * Math.PI;
		while (diff < -Math.PI) diff += 2 * Math.PI;
		yaw += diff * 0.06f;
		body.snapTo(x, y, z, (float) Math.toDegrees(-yaw), 0f);
		if (age % 50 == 1) Sigf.sound(Sounds.HELI, pos(), 4f, 1f);
		if (leaving) return;
		// spotlight: a thin beam and a real light that follows the player
		Vec3 from = pos().add(0, 0.3, 0), to = p.position().add(0, 0.2, 0);
		Vec3 dir = to.subtract(from);
		if (age % 3 == 0) for (int i = 1; i <= 3; i++) { Vec3 q = from.add(dir.scale(i / 8.0)); lv.sendParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0.1, 0.1, 0.1, 0); }
		if (age % 4 == 0) moveLight(net.minecraft.core.BlockPos.containing(p.getX(), p.getY() + 2.2, p.getZ()));
		// door gunner
		if (age % 36 == 20 && age > 60) {
			Vec3 muzzle = from.add(0, 0.2, 0);
			Vec3 aim = p.position().add(0, 1.0, 0).subtract(muzzle).add((Math.random() - .5) * 2.2, (Math.random() - .5) * 1.4, (Math.random() - .5) * 2.2).normalize();
			double len = muzzle.distanceTo(p.position());
			for (double t = 1; t < len; t += 1.0) lv.sendParticles(ParticleTypes.CRIT, muzzle.x + aim.x * t, muzzle.y + aim.y * t, muzzle.z + aim.z * t, 1, 0, 0, 0, 0);
			Sigf.sound(Sounds.SMG, muzzle, 2.5f, 0.8f);
			lv.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 3, 0.1, 0.1, 0.1, 0.02);
			if (Math.random() < 0.4) p.hurtServer(lv, lv.damageSources().mobAttack(body), 2f);
		}
	}

	void crashTick() {
		crash++;
		ServerLevel lv = Sigf.level();
		spin += 0.6f;
		yaw += 0.35f;
		y -= Math.min(1.2, 0.1 + crash * 0.07);
		boolean ground = lv.getBlockState(net.minecraft.core.BlockPos.containing(x, y - 1.5, z)).isSolid() || y <= City.oy + 1;
		body.snapTo(x, y, z, (float) Math.toDegrees(-yaw), 0f);
		transforms(1);
		Sigf.particles(ParticleTypes.LARGE_SMOKE, pos().add(0, 1, -1), 3, 0.5);
		Sigf.particles(ParticleTypes.FLAME, pos().add(0, 1, -1), 3, 0.4);
		if (crash % 6 == 0) Sigf.sound(SoundEvents.ANVIL_LAND, pos(), 0.6f, 0.6f);
		if (ground) {
			Vec3 p = pos().add(0, 1, 0);
			lv.explode(null, p.x, p.y, p.z, 4f, Level.ExplosionInteraction.NONE);
			Sigf.particles(ParticleTypes.EXPLOSION_EMITTER, p, 2, 1.5);
			Sigf.particles(ParticleTypes.FLAME, p, 60, 2.0);
			Sigf.particles(ParticleTypes.LARGE_SMOKE, p, 40, 1.5);
			Sigf.sound(SoundEvents.GENERIC_EXPLODE.value(), p, 3f, 0.7f);
			gone = true; cleanup();
		}
	}

	net.minecraft.core.BlockPos lightAt;
	void moveLight(net.minecraft.core.BlockPos np) {
		ServerLevel lv = Sigf.level();
		if (np.equals(lightAt)) return;
		clearLight();
		if (lv.getBlockState(np).isAir()) { lv.setBlock(np, Blocks.LIGHT.defaultBlockState(), 2); lightAt = np; }
	}
	void clearLight() {
		if (lightAt != null) { ServerLevel lv = Sigf.level(); if (lv.getBlockState(lightAt).is(Blocks.LIGHT)) lv.setBlock(lightAt, Blocks.AIR.defaultBlockState(), 2); lightAt = null; }
	}

	void cleanup() {
		clearLight();
		for (Display.BlockDisplay bd : disp) if (!bd.isRemoved()) bd.discard();
		if (!body.isRemoved()) body.discard();
	}
}
