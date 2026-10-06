package sigf.mod;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** The Los Santos arsenal: pistol, micro SMG, rocket launcher, sticky bombs and the car keys. */
public final class Weapons {
	public static Item PISTOL, SMG, RPG, CASH, KEYS, BADGE, STICKY;
	static final List<LargeFireball> rockets = new ArrayList<>();
	static final java.util.Map<LargeFireball, Entity> homing = new java.util.HashMap<>();
	static final DustParticleOptions MUZZLE = new DustParticleOptions(0xFFD040, 0.6f);
	static final DustParticleOptions BLOOD = new DustParticleOptions(0xB01010, 1.2f);

	private Weapons() {}

	public static void init() {
		PISTOL = Sigf.item("pistol", p -> new Item(p.stacksTo(1)));
		SMG = Sigf.item("smg", p -> new Item(p.stacksTo(1)));
		RPG = Sigf.item("rpg", p -> new Item(p.stacksTo(1)));
		CASH = Sigf.item("cash", p -> new Item(p.stacksTo(64)));
		KEYS = Sigf.item("car_keys", p -> new Item(p.stacksTo(1)));
		BADGE = Sigf.item("lspd_badge", p -> new Item(p.stacksTo(1)));
		STICKY = Sigf.item("sticky_bomb", p -> new Item(p.stacksTo(16)));
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			ItemStack st = player.getItemInHand(hand);
			Item it = st.getItem();
			if (player.getCooldowns().isOnCooldown(st)) return InteractionResult.FAIL;
			if (it == PISTOL) { fire(sp, 7f, 0.004, true); player.getCooldowns().addCooldown(st, 7); return InteractionResult.SUCCESS; }
			if (it == SMG) {
				for (int i = 0; i < 5; i++) { final int k = i; Sigf.after(i * 0.07, () -> { if (sp.isAlive()) fire(sp, 3.5f, 0.03, k == 0); }); }
				player.getCooldowns().addCooldown(st, 9); return InteractionResult.SUCCESS;
			}
			if (it == RPG) { rocket(sp); player.getCooldowns().addCooldown(st, 50); return InteractionResult.SUCCESS; }
			if (it == STICKY) { sticky(sp, st); player.getCooldowns().addCooldown(st, 14); return InteractionResult.SUCCESS; }
			if (it == KEYS) { callCar(sp); player.getCooldowns().addCooldown(st, 60); return InteractionResult.SUCCESS; }
			return InteractionResult.PASS;
		});
	}

	public static void give(ServerPlayer p) {
		String n = p.getGameProfile().name();
		Sigf.command("give " + n + " sigf:pistol");
		Sigf.command("give " + n + " sigf:smg");
		Sigf.command("give " + n + " sigf:rpg");
		Sigf.command("give " + n + " sigf:sticky_bomb 4");
		Sigf.command("give " + n + " sigf:car_keys");
	}

	// ---------- bullets ----------
	/** A hitscan shot from the player's eyes. Returns the entity hit (or null). */
	public static Entity fire(ServerPlayer p, float damage, double spread, boolean sound) {
		Vec3 eye = p.getEyePosition();
		Vec3 dir = p.getLookAngle();
		if (spread > 0) dir = dir.add((Math.random() - .5) * spread * 2, (Math.random() - .5) * spread * 2, (Math.random() - .5) * spread * 2).normalize();
		return shoot(p, eye, dir, damage, sound, 80);
	}

	/** Shot from any origin (the demo uses it to shoot at targets). */
	public static Entity shoot(ServerPlayer p, Vec3 from, Vec3 dir, float damage, boolean sound, double range) {
		ServerLevel lv = Sigf.level();
		Vec3 to = from.add(dir.scale(range));
		BlockHitResult bh = lv.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (bh.getType() != HitResult.Type.MISS) to = bh.getLocation();
		Entity hit = null; Vec3 hitPos = null; double best = from.distanceTo(to);
		AABB box = new AABB(from, to).inflate(1.5);
		for (Entity e : lv.getEntities(p, box, e -> e.isAlive() && e.isPickable() && e != p.getVehicle() && !(e instanceof ArmorStand))) {
			var c = e.getBoundingBox().inflate(0.15).clip(from, to);
			if (c.isPresent()) { double d = from.distanceTo(c.get()); if (d < best) { best = d; hit = e; hitPos = c.get(); } }
		}
		Vec3 end = hit != null ? hitPos : to;
		double len = from.distanceTo(end);
		Vec3 step = end.subtract(from).normalize();
		for (double t = 2.5; t < len; t += 1.4) lv.sendParticles(ParticleTypes.CRIT, from.x + step.x * t, from.y + step.y * t - 0.1, from.z + step.z * t, 1, 0, 0, 0, 0);
		Vec3 muzzle = from.add(step.scale(1.9)).add(0, -0.25, 0);
		lv.sendParticles(MUZZLE, muzzle.x, muzzle.y, muzzle.z, 2, 0.04, 0.04, 0.04, 0);
		lv.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 1, 0.03, 0.03, 0.03, 0.005);
		if (sound) Sigf.sound(damage > 5 ? Sounds.GUNSHOT : Sounds.SMG, from, 2.0f, 1f);
		Wanted.gunfire(p);
		if (hit != null) {
			hit.addTag("shot");
			if (hit instanceof LivingEntity le) {
				le.hurtServer(lv, lv.damageSources().generic(), damage);
				lv.sendParticles(BLOOD, end.x, end.y, end.z, 14, 0.15, 0.15, 0.15, 0.05);
				lv.sendParticles(ParticleTypes.DAMAGE_INDICATOR, end.x, end.y, end.z, 3, 0.1, 0.1, 0.1, 0.1);
				le.setDeltaMovement(le.getDeltaMovement().add(step.scale(0.25).add(0, 0.08, 0)));
				le.syncVelocity = true;
			}
			Sigf.sound(SoundEvents.PLAYER_ATTACK_CRIT, end, 0.8f, 1.6f);
			Wanted.hit(p, hit);
		} else {
			lv.sendParticles(ParticleTypes.CRIT, end.x, end.y, end.z, 6, 0.1, 0.1, 0.1, 0.2);
			lv.sendParticles(ParticleTypes.SMOKE, end.x, end.y, end.z, 3, 0.05, 0.05, 0.05, 0.02);
			Sigf.sound(SoundEvents.STONE_HIT, end, 0.7f, 1.5f);
		}
		return hit;
	}

	// ---------- rocket launcher ----------
	public static void rocket(ServerPlayer p) {
		ServerLevel lv = Sigf.level();
		Vec3 dir = p.getLookAngle();
		LargeFireball fb = new LargeFireball(lv, p, dir.scale(0.12), 3);
		Vec3 at = p.getEyePosition().add(dir.scale(1.6)).add(0, -0.25, 0);
		fb.snapTo(at.x, at.y, at.z, p.getYRot(), p.getXRot());
		fb.setDeltaMovement(dir.scale(1.6));
		lv.addFreshEntity(fb);
		rockets.add(fb);
		Entity lock = lockOn(p, dir);
		if (lock != null) homing.put(fb, lock);
		lv.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.05);
		lv.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 8, 0.3, 0.3, 0.3, 0.02);
		Sigf.sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, at, 2f, 0.7f);
		Sigf.sound(SoundEvents.GENERIC_EXPLODE.value(), at, 0.6f, 1.8f);
		Wanted.crime(p, 2, "Rocket launched");
	}

	/** The demo bot fires a rocket from any spot at a target point. */
	public static void rocketAt(ServerPlayer p, Vec3 from, Vec3 target, Entity lock) {
		ServerLevel lv = Sigf.level();
		Vec3 dir = target.subtract(from).normalize();
		LargeFireball fb = new LargeFireball(lv, p, dir.scale(0.12), 3);
		fb.snapTo(from.x, from.y, from.z, 0f, 0f);
		fb.setDeltaMovement(dir.scale(1.5));
		lv.addFreshEntity(fb);
		rockets.add(fb);
		if (lock != null) homing.put(fb, lock);
		lv.sendParticles(ParticleTypes.FLAME, from.x, from.y, from.z, 12, 0.2, 0.2, 0.2, 0.05);
		lv.sendParticles(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 8, 0.3, 0.3, 0.3, 0.02);
		Sigf.sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, from, 2f, 0.7f);
		Wanted.crime(p, 2, "Rocket launched");
	}

	/** The homing launcher: locks onto the cop, car or chopper nearest to the crosshair. */
	static Entity lockOn(ServerPlayer p, Vec3 dir) {
		Entity best = null; double bs = 0.93;
		Vec3 eye = p.getEyePosition();
		for (Entity e : Sigf.near(eye, 70, e -> e.isAlive() && e != p && e != p.getVehicle() && !(e instanceof ArmorStand) && (Wanted.isCop(e) || Wanted.isPed(e) || Car.byBody(e) != null))) {
			Vec3 to = e.position().add(0, e.getBbHeight() / 2, 0).subtract(eye).normalize();
			double dot = to.dot(dir);
			if (dot > bs) { bs = dot; best = e; }
		}
		return best;
	}

	public static void tick() {
		for (var it = rockets.iterator(); it.hasNext(); ) {
			LargeFireball fb = it.next();
			if (!fb.isAlive()) { it.remove(); homing.remove(fb); continue; }
			Vec3 q = fb.position();
			Entity lock = homing.get(fb);
			if (lock != null && lock.isAlive()) {
				Vec3 want = lock.position().add(0, lock.getBbHeight() * 0.5, 0).subtract(q).normalize();
				Vec3 cur = fb.getDeltaMovement();
				double sp = Math.max(1.2, cur.length());
				Vec3 nd = cur.normalize().scale(0.72).add(want.scale(0.28)).normalize().scale(sp);
				fb.setDeltaMovement(nd);
				fb.syncVelocity = true;
			}
			Sigf.particles(ParticleTypes.FLAME, q, 3, 0.08);
			Sigf.particles(ParticleTypes.LARGE_SMOKE, q, 2, 0.12);
			Sigf.particles(ParticleTypes.CAMPFIRE_COSY_SMOKE, q, 1, 0.1);
			if (fb.tickCount > 120) fb.discard();
		}
	}

	// ---------- sticky bomb ----------
	static void sticky(ServerPlayer p, ItemStack st) {
		ServerLevel lv = Sigf.level();
		Vec3 from = p.getEyePosition(), dir = p.getLookAngle(), to = from.add(dir.scale(10));
		BlockHitResult bh = lv.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (bh.getType() != HitResult.Type.MISS) to = bh.getLocation();
		Entity target = null; double best = from.distanceTo(to);
		for (Entity e : lv.getEntities(p, new AABB(from, to).inflate(1.5), e -> e.isAlive() && e.isPickable() && e != p.getVehicle() && !(e instanceof ArmorStand))) {
			var c = e.getBoundingBox().inflate(0.3).clip(from, to);
			if (c.isPresent() && from.distanceTo(c.get()) < best) { best = from.distanceTo(c.get()); target = e; to = c.get(); }
		}
		if (!p.getAbilities().instabuild) st.shrink(1);
		final Entity tgt = target;
		final Vec3 spot = to;
		Sigf.sound(SoundEvents.SLIME_BLOCK_PLACE, spot, 1f, 1.4f);
		if (tgt != null) Wanted.hit(p, tgt);
		Wanted.crime(p, 1, "Explosives");
		for (int i = 0; i < 6; i++) {
			final int k = i;
			Sigf.after(0.4 + k * 0.4, () -> {
				Vec3 at = tgt != null && tgt.isAlive() ? tgt.position().add(0, tgt.getBbHeight() * 0.6, 0) : spot;
				Sigf.particles(ParticleTypes.FLAME, at, 2, 0.1);
				Sigf.sound(SoundEvents.NOTE_BLOCK_BIT.value(), at, 1.2f, 0.7f + k * 0.2f);
			});
		}
		Sigf.after(2.8, () -> {
			Vec3 at = tgt != null && tgt.isAlive() ? tgt.position().add(0, tgt.getBbHeight() * 0.6, 0) : spot;
			lv.explode(null, at.x, at.y, at.z, 3.5f, Level.ExplosionInteraction.NONE);
			Sigf.particles(ParticleTypes.EXPLOSION_EMITTER, at, 1, 0);
			Sigf.particles(ParticleTypes.FLAME, at, 40, 1.0);
			for (Entity e : Sigf.near(at, 5)) {
				if (e instanceof LivingEntity le && e != p) {
					le.hurtServer(lv, lv.damageSources().generic(), 24f);
					Wanted.hit(p, e);
				}
			}
		});
	}

	// ---------- car keys ----------
	static void callCar(ServerPlayer p) {
		Vec3 look = p.getLookAngle();
		Vec3 at = p.position().add(look.x * 5, 0, look.z * 5);
		Car.nextPaint = net.minecraft.world.item.DyeColor.YELLOW;
		Car c = Car.create(Car.Mode.PARKED, at.x, at.z, Math.abs(look.x) > Math.abs(look.z) ? (look.x > 0 ? 0 : 2) : (look.z > 0 ? 1 : 3), false, 2);
		c.y = p.getY();
		c.maxHp = 90; c.hp = 90;
		c.yaw = (float) Math.atan2(look.x, look.z);
		c.applyTransforms(0);
		c.syncDisplay();
		Sigf.particles(ParticleTypes.CLOUD, at.add(0, 0.3, 0), 20, 0.8);
		Sigf.sound(SoundEvents.NOTE_BLOCK_PLING.value(), at, 1f, 1.8f);
		c.enter(p);
	}
}
