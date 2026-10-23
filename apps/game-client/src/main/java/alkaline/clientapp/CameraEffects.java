package alkaline.clientapp;

import alkaline.client.settings.ClientSettings;
import alkaline.game.player.LocalPlayer;
import alkaline.renderer.math.Mat4;

/**
 * EntityRenderer's camera effects for the local player, as eye-space transforms and field-of-view factors:
 * hurtCameraEffect (the tilt after taking damage), setupViewBobbing (the sway of walking), the sprint and flight
 * FOV change (updateFovModifierHand), and Alkaline's Zoom module.
 *
 * <p>Vanilla applies the same bobbing to the camera and to the hand. View bobbing Minimal keeps the camera level
 * and bobs only the hand, so the world transform and the hand transform are kept apart.
 */
final class CameraEffects {
    private final float[] world = new float[16];
    private final float[] hand = new float[16];
    private float fovModifier = 1f;
    private float previousFovModifier = 1f;
    private float zoom = 1f;
    private float previousZoom = 1f;
    private int ticks;
    private float portal;
    private float previousPortal;
    private boolean nausea;

    /**
     * One 20 Hz tick: the FOV modifier eases halfway toward its target, as vanilla's does; zoom eases the same way
     * when smooth.
     *
     * @param zoomFactor magnification while the zoom key is held, or 1
     */
    void tick(LocalPlayer player, float zoomFactor, boolean smoothZoom, int nauseaTicks) {
        ticks++;
        previousFovModifier = fovModifier;
        // EntityPlayerSP.getFovModifier: flying 1.1x, the movement speed (Speed, Slowness and sprinting included)
        // over the walk speed averaged with 1, and a drawn bow narrowing it by up to 15%.
        float target = player.flying ? 1.1f : 1f;
        float walk = player.walkSpeed;
        target = (float) (target * ((player.groundSpeed() / walk + 1.0) / 2.0));
        if (walk == 0f || Float.isNaN(target) || Float.isInfinite(target)) {
            target = 1f;
        }
        if (player.usingItem && player.itemInUse == 261) {
            float drawn = player.itemUseTicks / 20f;
            drawn = drawn > 1f ? 1f : drawn * drawn;
            target *= 1f - drawn * 0.15f;
        }
        fovModifier += (target - fovModifier) * 0.5f;
        fovModifier = Math.max(0.1f, Math.min(1.5f, fovModifier));
        // EntityPlayerSP.onLivingUpdate: Nausea (with over 60 ticks left) builds the portal distortion up slowly.
        previousPortal = portal;
        if (nauseaTicks > 60) {
            portal = Math.min(1f, portal + 0.006666667f);
        } else {
            portal = Math.max(0f, portal - 0.05f);
        }
        nausea = nauseaTicks >= 0;
        previousZoom = zoom;
        zoom = smoothZoom ? zoom + (zoomFactor - zoom) * 0.5f : zoomFactor;
        if (Math.abs(zoom - zoomFactor) < 0.001f) {
            zoom = zoomFactor;
        }
    }

    /** The world's vertical field of view in degrees. */
    float fov(float baseDegrees, float partialTick) {
        float modifier = previousFovModifier + (fovModifier - previousFovModifier) * partialTick;
        float magnification = previousZoom + (zoom - previousZoom) * partialTick;
        return baseDegrees * modifier / Math.max(1f, magnification);
    }

    /** Whether the view is zoomed in (the mouse turns slower, as with vanilla's cinematic camera). */
    float magnification() {
        return Math.max(1f, zoom);
    }

    /**
     * Builds this frame's transforms. Returns the world eye transform (null when there is none, which saves the
     * multiply); {@link #hand()} holds the hand's.
     */
    float[] update(LocalPlayer player, ClientSettings.ViewBobbing bobbing, boolean hurtShake, float partialTick) {
        Mat4.identity(world);
        Mat4.identity(hand);
        boolean any = false;
        if (hurtShake) {
            any |= hurt(player, partialTick, world);
            hurt(player, partialTick, hand);
        }
        if (bobbing != ClientSettings.ViewBobbing.OFF) {
            if (bobbing == ClientSettings.ViewBobbing.FULL) {
                bob(player, partialTick, world);
                any = true;
            }
            bob(player, partialTick, hand);
        }
        any |= distort(partialTick, world);
        return any ? world : null;
    }

    float[] hand() {
        return hand;
    }

    /** hurtCameraEffect: a roll of up to 14 degrees, sin((t / 10)^4 * pi) over the 10 hurt ticks. */
    private static boolean hurt(LocalPlayer player, float partialTick, float[] out) {
        float time = player.hurtTime - partialTick;
        if (time < 0f) {
            return false;
        }
        time /= 10f;
        time = (float) Math.sin(Math.pow(time, 4) * Math.PI);
        // The server never tells the client where an attack came from (attackedAtYaw stays 0).
        Mat4.rotate(out, (float) Math.toRadians(-time * 14f), 0f, 0f, 1f, out);
        return true;
    }

    /**
     * EntityRenderer.setupCameraTransform's portal and Nausea distortion: the view squeezed along an axis that
     * turns 7 degrees a tick (20 in a portal).
     */
    private boolean distort(float partialTick, float[] out) {
        float amount = previousPortal + (portal - previousPortal) * partialTick;
        if (amount <= 0f) {
            return false;
        }
        float speed = nausea ? 7f : 20f;
        float squeeze = 5f / (amount * amount + 5f) - amount * 0.04f;
        squeeze *= squeeze;
        float angle = (float) Math.toRadians((ticks + partialTick) * speed);
        float axis = (float) (1 / Math.sqrt(2));
        Mat4.rotate(out, angle, 0f, axis, axis, out);
        Mat4.scale(out, 1f / squeeze, 1f, 1f, out);
        Mat4.rotate(out, -angle, 0f, axis, axis, out);
        return true;
    }

    /** setupViewBobbing. */
    private static void bob(LocalPlayer player, float partialTick, float[] out) {
        float step = player.distanceWalked - player.previousDistanceWalked;
        float phase = -(player.distanceWalked + step * partialTick);
        float yaw = player.previousCameraYaw + (player.cameraYaw - player.previousCameraYaw) * partialTick;
        float pitch = player.previousCameraPitch + (player.cameraPitch - player.previousCameraPitch) * partialTick;
        float sin = (float) Math.sin(phase * Math.PI);
        float cos = (float) Math.cos(phase * Math.PI);
        Mat4.translate(out, sin * yaw * 0.5f, -Math.abs(cos * yaw), 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(sin * yaw * 3f), 0f, 0f, 1f, out);
        Mat4.rotate(out, (float) Math.toRadians(Math.abs((float) Math.cos(phase * Math.PI - 0.2f) * yaw) * 5f),
                1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(pitch), 1f, 0f, 0f, out);
    }
}
