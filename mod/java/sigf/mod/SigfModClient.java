package sigf.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;

/** Client side: a GTA-style chase camera behind the car while driving. */
public final class SigfModClient implements ClientModInitializer {
	private static boolean driving;
	private static double orbit;
	private static CameraType before = CameraType.FIRST_PERSON;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) { driving = false; return; }
			mc.gui.toastManager().clear();
			// idle stream camera: a wider, higher orbit over the whole city (after the kit's own, so it wins)
			if (!sigf.kit.Sigf.isDemo() && mc.player.isSpectator() && City.ready) {
				orbit += 0.0025;
				double cx = City.ox + 0.5, cy = City.oy + 5, cz = City.oz + 0.5;
				double x = cx + Math.cos(orbit) * 66, y = cy + 60, z = cz + Math.sin(orbit) * 66;
				double dx = cx - x, dy = cy - y, dz = cz - z;
				mc.player.setPos(x, y, z);
				mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
				mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
				return;
			}
			boolean now = mc.player.getVehicle() instanceof ArmorStand;
			CameraType wanted = Cam.mode == 1 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK;
			if (now && !driving) before = mc.options.getCameraType();
			if (now && mc.options.getCameraType() != wanted) mc.options.setCameraType(wanted);
			if (!now && driving) mc.options.setCameraType(before == CameraType.THIRD_PERSON_BACK ? CameraType.FIRST_PERSON : before);
			driving = now;
			if (now) {
				float want = mc.player.getVehicle().getYRot();
				float d = net.minecraft.util.Mth.wrapDegrees(want - mc.player.getYRot());
				mc.player.setYRot(mc.player.getYRot() + d * 0.1f);
				float dp = (Cam.mode == 1 ? 4f : 8f) - mc.player.getXRot();
				mc.player.setXRot(mc.player.getXRot() + dp * 0.08f);
			}
		});
	}
}
