package sigf.mod;

import net.minecraft.sounds.SoundEvent;
import sigf.kit.Sigf;

public final class Sounds {
	public static SoundEvent GUNSHOT, SMG, SIREN, RADIO, WASTED, ENGINE, HELI, WANTED_UP;

	private Sounds() {}

	public static void init() {
		GUNSHOT = Sigf.registerSound("gunshot");
		SMG = Sigf.registerSound("smg_burst");
		SIREN = Sigf.registerSound("siren");
		RADIO = Sigf.registerSound("radio_chatter");
		WASTED = Sigf.registerSound("wasted");
		ENGINE = Sigf.registerSound("car_engine");
		HELI = Sigf.registerSound("helicopter");
		WANTED_UP = Sigf.registerSound("wanted_up");
	}
}
