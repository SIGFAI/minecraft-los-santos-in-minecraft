package sigf.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

public final class SigfMod implements ModInitializer {
	static boolean started, populated, staged;

	@Override
	public void onInitialize() {
		Sounds.init();
		Weapons.init();
		Wanted.init();
		Demo.init();
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide()) return InteractionResult.PASS;
			Car c = Car.byBody(entity);
			if (c != null && player instanceof ServerPlayer sp && c.driver == null && player.getVehicle() == null) {
				if (!c.police && c.mode == Car.Mode.TRAFFIC) Wanted.crime(sp, 1, "Carjacking");
				c.enter(sp);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			City.tick();
			if (Sigf.level() != null && Sigf.level().getGameTime() % 5 == 0) City.tickSignals(Sigf.level().getGameTime());
			if (Sigf.level() == null) return;
			Car.tickAll();
			Weapons.tick();
			Wanted.tick();
			long now = Sigf.level().getGameTime();
			Peds.tick(now);
			ServerPlayer host = Sigf.host();
			if (host != null && now % 10 == 0) Quest.tick(host);
			if (!started) {
				started = true;
				// build at the world spawn as soon as the world is up, so the city stands before the player is even in
				net.minecraft.core.BlockPos sp = Sigf.level().getRespawnData().pos();
				int cy = Sigf.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ());
				City.build(sp.getX(), cy, sp.getZ());
				server.getGameRules().set(GameRules.MOB_GRIEFING, false, server);
				Sigf.command("time set 12100");
			}
			if (City.ready && !populated) {
				populated = true;
				for (int i = 0; i < 16; i++) Car.spawnTraffic(false);
				Peds.populate();
				Car.spawnParked(10);
				Car.spawnStationCruisers();
			}
			if (populated && !staged && host != null) {
				staged = true;
				Sigf.teleport(host, new Vec3(City.ox + 16.5, City.oy, City.oz - 22.0), Vec3.ZERO);
				Sigf.lookAt(host, new Vec3(City.ox + 0.5, City.oy + 44, City.oz - 124));
				if (Sigf.isDemo()) Weapons.give(host);
			}
			if (populated && now % 40 == 0 && Car.civilians() < 16) Car.spawnTraffic(true);
		});
	}
}
