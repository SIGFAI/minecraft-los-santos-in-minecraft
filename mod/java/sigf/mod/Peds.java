package sigf.mod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** Pedestrians strolling the sidewalks. Hurting them draws the police. */
public final class Peds {
	static final List<Villager> peds = new ArrayList<>();
	static final List<ResourceKey<VillagerProfession>> JOBS = List.of(VillagerProfession.FARMER, VillagerProfession.BUTCHER, VillagerProfession.LIBRARIAN,
		VillagerProfession.CLERIC, VillagerProfession.TOOLSMITH);
	public static final int TARGET = 18;

	private Peds() {}

	/** A random sidewalk spot. */
	public static Vec3 sidewalkSpot() {
		int L = City.ROADS[(int) (Math.random() * City.ROADS.length)];
		double along = (Math.random() * 2 - 1) * (City.HALF - 6);
		double off = (Math.random() < .5 ? -1 : 1) * (4.6 + Math.random() * 1.0);
		boolean xr = Math.random() < .5;
		double x = xr ? City.ox + 0.5 + L + off : City.ox + 0.5 + along;
		double z = xr ? City.oz + 0.5 + along : City.oz + 0.5 + L + off;
		return new Vec3(x, City.oy, z);
	}

	public static Villager spawn(Vec3 at) {
		ServerLevel lv = Sigf.level();
		Villager v = EntityTypes.VILLAGER.create(lv, EntitySpawnReason.COMMAND);
		v.snapTo(at.x, at.y, at.z, (float) (Math.random() * 360), 0f);
		v.setVillagerData(v.getVillagerData().withProfession(lv.registryAccess(), JOBS.get((int) (Math.random() * JOBS.size()))));
		v.setVillagerXp(1);
		v.addTag("ped");
		v.setPersistenceRequired();
		double sc = 0.92 + Math.random() * 0.2;
		v.getAttribute(Attributes.SCALE).setBaseValue(sc);
		lv.addFreshEntity(v);
		peds.add(v);
		return v;
	}

	public static void populate() { for (int i = 0; i < TARGET; i++) spawn(sidewalkSpot()); }

	public static void tick(long now) {
		peds.removeIf(v -> !v.isAlive());
		if (now % 20 == 0) {
			for (Villager v : peds) {
				if (v.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty() && v.getNavigation().isDone()) {
					Vec3 t = sidewalkNear(v.position());
					v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(BlockPos.containing(t), 0.55f, 1));
				}
			}
		}
		if (now % 100 == 0 && peds.size() < TARGET) {
			ServerPlayer p = Sigf.host();
			Vec3 s = sidewalkSpot();
			if (p == null || p.position().distanceTo(s) > 25) spawn(s);
		}
	}

	/** A spot 8 to 18 blocks along the same sidewalk. */
	static Vec3 sidewalkNear(Vec3 from) {
		double rx = from.x - (City.ox + 0.5), rz = from.z - (City.oz + 0.5);
		int nx = (int) Math.round(nearestRoad(rx)), nz = (int) Math.round(nearestRoad(rz));
		boolean alongZ = Math.abs(rx - nx) < Math.abs(rz - nz);   // on a north-south sidewalk
		double d = (Math.random() < .5 ? -1 : 1) * (8 + Math.random() * 10);
		double x = from.x, z = from.z;
		if (alongZ) z = Math.max(City.oz - City.HALF + 4, Math.min(City.oz + City.HALF - 4, z + d));
		else x = Math.max(City.ox - City.HALF + 4, Math.min(City.ox + City.HALF - 4, x + d));
		return new Vec3(x, City.oy, z);
	}

	static double nearestRoad(double rel) {
		double best = City.ROADS[0], bd = 1e9;
		for (int L : City.ROADS) { double dd = Math.abs(rel - L); if (dd < bd) { bd = dd; best = L; } }
		return best;
	}
}
