package sigf.mod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** The scripted showcase: the bot player plays the mod while the clip records. */
public final class Demo {
	static Villager ped;
	static Car car;

	private Demo() {}

	static Vec3 rel(double x, double z) { return new Vec3(City.ox + 0.5 + x, City.oy, City.oz + 0.5 + z); }
	static Vec3 relY(double x, double y, double z) { return new Vec3(City.ox + 0.5 + x, City.oy + y, City.oz + 0.5 + z); }
	static void at(double t, Runnable r) { Sigf.demo(t, r); }
	static ServerPlayer me() { return Sigf.host(); }

	static void aim(Vec3 t) { Sigf.lookAt(me(), t); }

	static void fire(Vec3 target, float dmg) {
		ServerPlayer p = me();
		Vec3 eye = p.getEyePosition();
		Weapons.shoot(p, eye, target.subtract(eye).normalize(), dmg, true, 90);
	}

	static void slot(int i) { me().getInventory().setSelectedSlot(i); }

	public static void init() {
		// ---- 0 to 6 s: the city, the Vinewood sign, then a pedestrian in the way ----
		at(0.1, () -> {
			slot(0);
			ped = Peds.spawn(rel(21.6, -34));
			ped.setNoAi(true);
			aim(relY(0, 44, -124));
		});
		for (int i = 1; i <= 12; i++) {
			final double k = i / 12.0, e = k * k * (3 - 2 * k);
			at(0.4 + i * 0.35, () -> {
				Vec3 a = relY(0, 44, -124), b = relY(21.6, 1.2, -34);
				aim(a.add(b.subtract(a).scale(e)));
			});
		}
		at(1.0, () -> Sigf.title("LOS SANTOS", "Welcome to the city of blocks", 3.2));
		// ---- first crime: the pistol ----
		for (int i = 0; i < 3; i++) {
			at(4.4 + i * 0.5, () -> {
				if (ped != null && ped.isAlive()) { Vec3 t = ped.position().add(0, 1.0, 0); aim(t); fire(t, 7f); }
			});
		}
		// ---- the LSPD arrives ----
		at(6.0, () -> Wanted.cruiserAt(me(), 14.25, -52, 1));
		at(6.6, () -> slot(1));
		for (int i = 0; i < 14; i++) {
			at(9.6 + i * 0.75, () -> {
				Skeleton best = null; double bd = 1e9;
				for (Skeleton c : Wanted.cops) { double d = c.position().distanceTo(me().position()); if (c.isAlive() && d < bd && d < 40) { bd = d; best = c; } }
				if (best == null) return;
				Vec3 t = best.position().add(0, 1.0, 0);
				aim(t);
				for (int k = 0; k < 4; k++) Sigf.after(k * 0.07, () -> fire(t, 4.2f));
			});
		}
		at(16.0, () -> { if (Wanted.stars < 3) Wanted.crime(me(), 10, "Demo heat"); });
		// ---- grab a ride with the car keys and run for it ----
		at(17.5, () -> {
			slot(4);
			ServerPlayer p = me();
			Car.nextPaint = net.minecraft.world.item.DyeColor.YELLOW;
			car = Car.create(Car.Mode.PARKED, City.ox + 18.75, City.oz - 27.5, 3, false, 2);
			car.maxHp = 90; car.hp = 90; car.cap = 0.55;
			Sigf.particles(ParticleTypes.CLOUD, car.pos().add(0, 0.4, 0), 10, 0.9);
			Sigf.sound(SoundEvents.NOTE_BLOCK_PLING.value(), car.pos(), 1.5f, 1.8f);
			car.enter(p);
			double[][] route = {{18.75, -27.5}, {18.75, -45.25}, {46.25, -45.25}, {46.25, 14.25}, {-13.25, 14.25}, {-13.25, -74}, {-8, -96}, {-4, -114}};
			car.path.addAll(rounded(route, 4.0));
		});
		at(20.0, () -> Cam.mode = 1);
		at(36.0, () -> Cam.mode = 0);
		// two cruisers pick up the trail and give chase
		at(21.0, () -> { Wanted.cruiserAt(me(), 18.3, -26, 3); });
		at(22.3, () -> { Wanted.cruiserAt(me(), 18.3, -20, 3); });
		// ---- rockets at the helicopter and the lead cruiser ----
		at(26.0, Demo::shootHeli);
		at(28.5, Demo::shootHeli);
		at(30.5, () -> { if (Wanted.heli != null && Wanted.heli.alive()) Wanted.heli.hp = 0; });
		at(32.5, Demo::shootCruiser);
		at(35.0, Demo::shootCruiser);
		at(38.0, () -> { if (Wanted.stars > 0) Wanted.escaped(me()); });
		at(58.0, () -> Sigf.title("LOS SANTOS IN MINECRAFT", "Wanted. Blocky. Yours.", 5));
		// ---- the hill: look up at the sign, then turn around to see the city at sunset ----
		at(50.0, () -> Wanted.peace = true);
		at(51.0, () -> { if (car != null && car.driver != null) { car.path.clear(); car.speed = 0; } });
		at(52.0, () -> { if (car != null) car.exit(); });
		for (int i = 0; i <= 100; i++) {
			final double t = 53.0 + i * 0.22;
			final double k = i / 100.0;
			at(t, () -> {
				if (me().getVehicle() != null) return;
				double yaw, pitch;
				if (k < 0.2) { double u = k / 0.2; yaw = 200 - 40 * u; pitch = -40 - 8 * u; }                      // up at the sign
				else if (k < 0.55) { double u = (k - 0.2) / 0.35, e = u * u * (3 - 2 * u); yaw = 160 - 150 * e; pitch = -48 + 51 * e; }   // turn to the city
				else { double u = (k - 0.55) / 0.45, e = u * u * (3 - 2 * u); yaw = 10 + 70 * e; pitch = 3 - 2 * e; }              // slow pan into the sunset
				ServerPlayer p = me();
				p.connection.teleport(p.getX(), p.getY(), p.getZ(), (float) yaw, (float) pitch);
			});
		}
	}

	/** A polyline through the corners with each corner rounded by a quadratic curve of radius r (so the bot takes turns wide). */
	static java.util.List<Vec3> rounded(double[][] c, double r) {
		java.util.List<Vec3> out = new java.util.ArrayList<>();
		out.add(rel(c[0][0], c[0][1]));
		for (int i = 1; i < c.length - 1; i++) {
			double[] p0 = c[i - 1], p = c[i], p1 = c[i + 1];
			double ux = p[0] - p0[0], uz = p[1] - p0[1], ul = Math.hypot(ux, uz); ux /= ul; uz /= ul;
			double vx = p1[0] - p[0], vz = p1[1] - p[1], vl = Math.hypot(vx, vz); vx /= vl; vz /= vl;
			double rr = Math.min(r, Math.min(ul, vl) * 0.45);
			if (Math.abs(ux * vz - uz * vx) < 0.1) { out.add(rel(p[0], p[1])); continue; }   // straight on
			double sx = p[0] - ux * rr, sz = p[1] - uz * rr, ex = p[0] + vx * rr, ez = p[1] + vz * rr;
			for (int k = 0; k <= 6; k++) {
				double t = k / 6.0, a = (1 - t) * (1 - t), b = 2 * t * (1 - t), cc = t * t;
				out.add(rel(a * sx + b * p[0] + cc * ex, a * sz + b * p[1] + cc * ez));
			}
		}
		out.add(rel(c[c.length - 1][0], c[c.length - 1][1]));
		return out;
	}

	static void shootHeli() {
		Heli h = Wanted.heli;
		if (h == null || !h.alive() || car == null) return;
		Weapons.rocketAt(me(), car.pos().add(0, 2.2, 0), h.pos(), h.body);
	}

	static void shootCruiser() {
		if (car == null) return;
		Car best = null; double bd = 1e9;
		for (Car c : Car.ALL) if (c.police && c.mode == Car.Mode.POLICE) { double d = c.pos().distanceTo(car.pos()); if (d < bd) { bd = d; best = c; } }
		if (best == null) return;
		Weapons.rocketAt(me(), car.pos().add(0, 2.0, 0), best.pos().add(0, 0.8, 0), best.body);
	}
}
