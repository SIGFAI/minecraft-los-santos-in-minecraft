package sigf.mod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** The short story: get wheels, make trouble, lose the cops, reach the Vinewood sign. */
public final class Quest {
	public static int step;
	static final String[] TEXT = {
		"Mission 1/4: GET WHEELS  - right-click a car or use the Car Keys",
		"Mission 2/4: MAKE SOME NOISE  - get to two stars",
		"Mission 3/4: SHAKE THE LSPD  - hide until the stars are gone",
		"Mission 4/4: HEAD FOR THE VINEWOOD SIGN  - drive up the hill",
		"All missions passed - Los Santos is yours",
	};

	private Quest() {}

	public static String line() { return TEXT[Math.min(step, TEXT.length - 1)]; }

	public static void tick(ServerPlayer p) {
		switch (step) {
			case 0 -> { if (Car.driven(p) != null) advance(p, "WHEELS ACQUIRED"); }
			case 1 -> { if (Wanted.stars >= 2) advance(p, "TROUBLE STARTED"); }
			case 2 -> { }
			case 3 -> {
				Vec3 sign = new Vec3(City.ox + 0.5, City.oy + 20, City.oz + City.SIGN_Z);
				double dx = p.getX() - sign.x, dz = p.getZ() - sign.z;
				if (Math.abs(dx) < 50 && Math.abs(dz) < 22 && p.getY() > City.oy + 12) advance(p, "VINEWOOD REACHED");
			}
			default -> { }
		}
	}

	public static void onEscape(ServerPlayer p) { if (step == 2) advance(p, "COPS SHAKEN"); }

	static void advance(ServerPlayer p, String what) {
		step++;
		Sigf.title("MISSION PASSED", what, 3);
		Sigf.sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, p.position(), 1f, 1f);
		if (step == 4) {
			ItemEntity ie = new ItemEntity(Sigf.level(), p.getX(), p.getY() + 1, p.getZ(), new ItemStack(Weapons.BADGE));
			Sigf.level().addFreshEntity(ie);
		}
	}
}
