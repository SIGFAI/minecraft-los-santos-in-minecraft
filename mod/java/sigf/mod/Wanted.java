package sigf.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** Wanted stars, the LSPD (police cars, officers, SWAT, helicopter) and the escape rules. */
public final class Wanted {
	public static int stars;
	public static boolean peace;   // the demo's finale: no more crimes
	static double heat;
	static long lastSpawn, lastGun, lastCrimeMsg, hiddenTicks;
	static final Map<String, Long> throttle = new HashMap<>();
	static final List<Skeleton> cops = new ArrayList<>();
	static ServerBossEvent bar;
	static int shownStars = -1;
	public static int kills, escapes, maxStars;
	static final int[] LEVELS = {1, 5, 10, 16, 24};
	public static Heli heli;

	private Wanted() {}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (Sigf.server() == null) return;
			Entity killer = source.getEntity() instanceof ServerPlayer sp0 ? sp0 : (entity.entityTags().contains("shot") ? Sigf.host() : null);
			if (entity instanceof ServerPlayer sp) { wasted(sp); return; }
			if (killer instanceof ServerPlayer p) {
				if (isPed(entity)) { crime(p, 2, "Murder"); loot(entity, 3 + (int) (Math.random() * 5)); kills++; }
				else if (isCop(entity)) { crime(p, 3, "Cop killer"); loot(entity, 4 + (int) (Math.random() * 6)); kills++; }
			}
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp) hit(sp, entity);
			return InteractionResult.PASS;
		});
	}

	public static boolean isCop(Entity e) { return e.entityTags().contains("lspd"); }
	public static boolean isPed(Entity e) { return e.entityTags().contains("ped"); }

	static void loot(Entity at, int n) {
		ItemEntity ie = new ItemEntity(Sigf.level(), at.getX(), at.getY() + 0.6, at.getZ(), new ItemStack(Weapons.CASH, n));
		ie.setDeltaMovement((Math.random() - .5) * 0.2, 0.3, (Math.random() - .5) * 0.2);
		Sigf.level().addFreshEntity(ie);
	}

	// ---------- crimes ----------
	public static void crime(ServerPlayer p, int pts, String why) {
		if (peace || Sigf.level().getGameTime() - lastEscape < 100) return;   // a few seconds of peace after losing the cops
		int before = stars;
		heat += pts;
		hiddenTicks = 0;
		lastCrime = Sigf.level().getGameTime();
		int s = 0;
		for (int i = 0; i < LEVELS.length; i++) if (heat >= LEVELS[i]) s = i + 1;
		if (s > stars) {
			stars = s;
			maxStars = Math.max(maxStars, stars);
			onStarsUp(p, before);
		}
	}

	static void onStarsUp(ServerPlayer p, int before) {
		Sigf.sound(Sounds.WANTED_UP, p.position(), 1.4f, 1f);
		String st = "★".repeat(stars) + "☆".repeat(5 - stars);
		if (before == 0) Sigf.title("WANTED", st, 2.2);
		else Sigf.title("", st + "  " + (stars < 4 ? "Police are closing in" : "Every unit is after you"), 1.4);
		if (before == 0) lastSpawn = Sigf.level().getGameTime() - 100;
	}

	public static void gunfire(ServerPlayer p) {
		long now = Sigf.level().getGameTime();
		if (now - lastGun > 160) { lastGun = now; crime(p, 1, "Shots fired"); }
	}

	/** The player hurt this entity. */
	public static void hit(ServerPlayer p, Entity e) {
		long now = Sigf.level().getGameTime();
		String key = isCop(e) ? "cop" : isPed(e) ? "ped" : Car.byBody(e) != null ? (Car.byBody(e).police ? "cop" : "car") : null;
		if (key == null) return;
		Long last = throttle.get(key);
		if (last != null && now - last < 30) return;
		throttle.put(key, now);
		crime(p, key.equals("cop") ? 2 : 1, key);
	}

	public static void copCarDestroyed(Car c) {
		ServerPlayer p = Sigf.host();
		if (p != null) crime(p, 4, "Police car destroyed");
	}

	// ---------- tick ----------
	public static void tick() {
		ServerPlayer p = Sigf.host();
		if (p == null || Sigf.level() == null) return;
		long now = Sigf.level().getGameTime();
		if (bar == null) {
			bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("WANTED"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
			bar.setVisible(false);
		}
		if (stars > 0) {
			bar.addPlayer(p);
			bar.setVisible(true);
			if (now % 10 == 0) {
				boolean seen = hiddenTicks == 0;
				bar.setColor(seen && (now / 10) % 2 == 0 ? BossEvent.BossBarColor.BLUE : BossEvent.BossBarColor.RED);
				String st = "★".repeat(stars) + "☆".repeat(5 - stars);
				bar.setName(Component.literal(seen || now - lastCrime < 300 ? "WANTED  " + st + "   —  the LSPD can see you" : "WANTED  " + st + "   —  hide to lose them"));
				bar.setProgress(seen ? 1f : (float) Math.max(0, 1.0 - hiddenTicks / 240.0));
			}
			stepPolice(p, now);
		} else {
			bar.setVisible(false);
			bar.removePlayer(p);
		}
		if (now % 20 == 0) hud(p);
	}

	static void hud(ServerPlayer p) {
		int cash = 0;
		for (ItemStack st : p.getInventory().getNonEquipmentItems()) if (st.is(Weapons.CASH)) cash += st.getCount() * 25;
		String quest = Quest.line();
		p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§a$" + cash + "   §f" + quest)));
	}

	static void stepPolice(ServerPlayer p, long now) {
		ServerLevel lv = Sigf.level();
		Vec3 pp = p.position();
		cops.removeIf(c -> !c.isAlive());
		// seen?
		boolean seen = false;
		for (Skeleton c : cops) if (c.position().distanceTo(pp) < 50) seen = true;
		for (Car c : Car.ALL) if (c.police && !c.decor && c.mode != Car.Mode.WRECK && c.pos().distanceTo(pp) < 50) seen = true;
		if (heli != null && heli.alive() && heli.pos().distanceTo(pp) < 70) seen = true;
		if (seen || now - lastCrime < 300) hiddenTicks = 0; else hiddenTicks++;
		if (hiddenTicks > 240) { escaped(p); return; }

		// police cars
		int want = Math.min(4, stars <= 1 ? 1 : stars <= 3 ? 2 : stars == 4 ? 3 : 4);
		int have = 0;
		for (Car c : Car.ALL) if (c.police && !c.decor && c.mode != Car.Mode.WRECK && c.mode != Car.Mode.PLAYER && !c.unloaded) have++;
		if (have < want && now - lastSpawn > 160) { lastSpawn = now; spawnCruiser(p); }

		// cars that arrived unload officers
		for (Car c : Car.ALL) {
			if (c.police && c.arrived && !c.unloaded && c.mode == Car.Mode.POLICE) {
				c.unloaded = true;
				c.mode = Car.Mode.PARKED;
				int n = stars >= 3 ? 3 : 2;
				for (int i = 0; i < n; i++) {
					Vec3 at = c.pos().add(Math.cos(c.yaw + 1.6 + i * 1.5) * 2.4, 0, -Math.sin(c.yaw + 1.6 + i * 1.5) * 2.4);
					officer(at, stars >= 3 && i == n - 1);
				}
				Sigf.sound(Sounds.RADIO, c.pos(), 1.2f, 1f);
				Sigf.sound(SoundEvents.IRON_DOOR_OPEN, c.pos(), 1f, 1f);
			}
		}
		// officers keep their eyes on the player
		if (now % 20 == 0) for (Skeleton c : cops) {
			double d = c.position().distanceTo(pp);
			if (d < 64) { c.setTarget(p); }
			else if (d > 110) c.discard();
		}
		// helicopter
		if (stars >= 3 && (heli == null || !heli.alive()) && now - lastHeli > 1200) {
			lastHeli = now;
			heli = Heli.spawn(p);
		}
		if (heli != null) { heli.tick(p); }
	}
	static long lastHeli = -2000, lastCrime, lastEscape = -1000;

	/** Demo: a cruiser that starts at a given spot and lane direction. */
	public static Car cruiserAt(ServerPlayer p, double rx, double rz, int dir) {
		Car c = Car.create(Car.Mode.POLICE, City.ox + 0.5 + rx, City.oz + 0.5 + rz, dir, true, 0);
		c.chaseTarget = p;
		c.speed = 0.3;
		Sigf.sound(Sounds.SIREN, c.pos(), 2.5f, 1f);
		return c;
	}

	static void spawnCruiser(ServerPlayer p) {
		Car best = null;
		for (int tries = 0; tries < 8; tries++) {
			int dir = (int) (Math.random() * 4);
			int L = City.ROADS[(int) (Math.random() * City.ROADS.length)];
			double sg = (dir == 0 || dir == 1) ? 1 : -1;
			double along = -(City.HALF - 4.0) * sg;
			double x, z;
			if (dir % 2 == 0) { x = City.ox + 0.5 + along; z = City.oz + 0.5 + L + Car.OFF[dir] * Car.LANE; }
			else { z = City.oz + 0.5 + along; x = City.ox + 0.5 + L + Car.OFF[dir] * Car.LANE; }
			if (Math.hypot(x - p.getX(), z - p.getZ()) < 45) continue;
			best = Car.create(Car.Mode.POLICE, x, z, dir, true, 0);
			break;
		}
		if (best == null) return;
		best.chaseTarget = p;
		best.speed = 0.3;
		Sigf.sound(Sounds.SIREN, best.pos(), 2.5f, 1f);
	}

	public static Skeleton officer(Vec3 at, boolean swat) {
		ServerLevel lv = Sigf.level();
		Skeleton s = EntityTypes.SKELETON.create(lv, EntitySpawnReason.COMMAND);
		s.snapTo(at.x, at.y, at.z, (float) (Math.random() * 360), 0f);
		int col = swat ? 0x1c1c22 : 0x1f3fa8;
		s.setItemSlot(EquipmentSlot.HEAD, dyed(swat ? Items.LEATHER_HELMET : Items.LEATHER_HELMET, swat ? 0x111114 : 0x16276e));
		s.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, col));
		s.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, swat ? 0x15151a : 0x16276e));
		s.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, 0x101018));
		s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		s.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
		s.addTag("lspd");
		s.setCustomName(Component.literal(swat ? "SWAT" : "LSPD Officer"));
		s.setCustomNameVisible(true);
		s.setPersistenceRequired();
		if (swat) {
			s.getAttribute(Attributes.SCALE).setBaseValue(1.15);
			s.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
			s.setHealth(40f);
			s.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
		}
		lv.addFreshEntity(s);
		cops.add(s);
		Sigf.particles(ParticleTypes.CLOUD, at.add(0, 0.5, 0), 6, 0.3);
		return s;
	}

	static ItemStack dyed(net.minecraft.world.item.Item item, int rgb) {
		ItemStack st = new ItemStack(item);
		st.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
		return st;
	}

	// ---------- losing the cops ----------
	public static void escaped(ServerPlayer p) {
		stars = 0; heat = 0; hiddenTicks = 0;
		lastEscape = Sigf.level().getGameTime();
		escapes++;
		Sigf.title("WANTED LEVEL LOST", "You shook off the LSPD", 3);
		Sigf.sound(SoundEvents.PLAYER_LEVELUP, p.position(), 1f, 1f);
		stand_down();
		Quest.onEscape(p);
	}

	/** Cops leave: officers vanish in smoke, cruisers drive off, the helicopter flies away. */
	static void stand_down() {
		for (Skeleton c : cops) { if (c.isAlive()) { Sigf.particles(ParticleTypes.CLOUD, c.position().add(0, 1, 0), 10, 0.4); c.discard(); } }
		cops.clear();
		for (Car c : Car.ALL) if (c.police && !c.decor && c.mode != Car.Mode.WRECK && c.mode != Car.Mode.PLAYER) { c.mode = Car.Mode.TRAFFIC; c.arrived = false; c.chaseTarget = null; c.police = false; c.topSpeed = 0.35; c.planNext(); }
		if (heli != null) { heli.leave(); heli = null; }
	}

	static void wasted(ServerPlayer p) {
		Sigf.title("§cWASTED", "", 3.5);
		Sigf.sound(Sounds.WASTED, p.position(), 1.5f, 1f);
		stars = 0; heat = 0; hiddenTicks = 0;
		stand_down();
	}

	public static void reset() { stars = 0; heat = 0; hiddenTicks = 0; stand_down(); }
}
