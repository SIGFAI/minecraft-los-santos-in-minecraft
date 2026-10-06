package sigf.mod;

/** Shared between the demo script and the client camera (both run in the same game). */
public final class Cam {
	/** 0 = chase camera behind the car, 1 = camera in front of the car looking back at the pursuers. */
	public static volatile int mode = 0;

	private Cam() {}
}
