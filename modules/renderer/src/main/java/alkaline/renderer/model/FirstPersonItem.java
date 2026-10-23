package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;

/**
 * Vanilla 1.8.9's first-person held-item placement (ItemRenderer.renderItemInFirstPerson for an item in normal
 * use) as one matrix from item model space (the 0..1 block an item model occupies) to eye space (y up, looking
 * down -z), plus the two item lights it uses. Draw with the hand projection: perspective at the base field of
 * view (70 degrees by default), unaffected by sprinting.
 */
public final class FirstPersonItem {
    private FirstPersonItem() {
    }

    /** What the held item is being used for (Item.getItemUseAction while the use key holds it). */
    public enum Use { NONE, EAT, DRINK, BLOCK, BOW }

    /**
     * How the hand animates beyond vanilla: {@code blockHit} keeps the attack swing visible while blocking with a
     * sword (1.7's look; vanilla 1.8 freezes it), {@code swingWhileUsing} does the same while eating, drinking or
     * drawing a bow, and the offset (in blocks) and scale move and size the held item.
     */
    public record Style(boolean blockHit, boolean swingWhileUsing, float offsetX, float offsetY, float offsetZ,
                        float scale) {
        public static final Style VANILLA = new Style(false, false, 0f, 0f, 0f, 1f);
    }

    /** The legacy entry point: an item in normal use, vanilla's style. */
    public static float[] transform(ItemDefinition.Display display, float equipProgress, float swingProgress,
                                    float armPitchLag, float armYawLag, float[] out) {
        return transform(display, false, equipProgress, swingProgress, armPitchLag, armYawLag, Use.NONE, false, 0f,
                0, Style.VANILLA, out);
    }

    /**
     * ItemRenderer.renderItemInFirstPerson: one matrix from item model space (the 0..1 block an item model occupies)
     * to eye space.
     *
     * @param block3d       a block item (ItemRenderer.renderItem doubles 3D models)
     * @param equipProgress 0 when fully raised, 1 when lowered out of view (vanilla's {@code 1 - equipped})
     * @param swingProgress 0..1 through an attack swing, 0 when idle
     * @param armPitchLag   view pitch minus the arm's lagging pitch, degrees (0 when the view is still)
     * @param armYawLag     view yaw minus the arm's lagging yaw, degrees
     * @param using         whether the item is in use (getItemInUseCount() > 0), with {@code use} its action
     * @param useLeft       getItemInUseCount() - partialTicks + 1: ticks of use left, interpolated
     * @param maxUse        the item's getMaxItemUseDuration (32 for food and drink, 72000 for bows and swords)
     */
    public static float[] transform(ItemDefinition.Display display, boolean block3d, float equipProgress,
                                    float swingProgress, float armPitchLag, float armYawLag, Use use, boolean using,
                                    float useLeft, int maxUse, Style style, float[] out) {
        Mat4.identity(out);
        // rotateWithPlayerRotations: the arm trails fast turns.
        Mat4.rotate(out, (float) Math.toRadians(armPitchLag * 0.1f), 1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(armYawLag * 0.1f), 0f, 1f, 0f, out);
        Mat4.translate(out, style.offsetX(), style.offsetY(), style.offsetZ(), out);
        if (using) {
            boolean swings = use == Use.BLOCK ? style.blockHit() : style.swingWhileUsing();
            float swing = swings ? swingProgress : 0f;
            if (swings) {
                usedTranslation(swingProgress, out);
            }
            switch (use) {
                case EAT, DRINK -> {
                    drinking(useLeft, maxUse, out);
                    place(equipProgress, swing, out);
                }
                case BLOCK -> {
                    place(equipProgress, swing, out);
                    // doBlockTransformations.
                    Mat4.translate(out, -0.5f, 0.2f, 0f, out);
                    Mat4.rotate(out, (float) Math.toRadians(30), 0f, 1f, 0f, out);
                    Mat4.rotate(out, (float) Math.toRadians(-80), 1f, 0f, 0f, out);
                    Mat4.rotate(out, (float) Math.toRadians(60), 0f, 1f, 0f, out);
                }
                case BOW -> {
                    place(equipProgress, swing, out);
                    bow(useLeft, maxUse, out);
                }
                default -> place(equipProgress, swing, out);
            }
        } else {
            usedTranslation(swingProgress, out);
            place(equipProgress, swingProgress, out);
        }
        Mat4.scale(out, style.scale(), style.scale(), style.scale(), out);
        if (block3d) {
            Mat4.scale(out, 2f, 2f, 2f, out);
        }
        // The model's first-person display transform (ItemCameraTransforms.applyTransform).
        Mat4.translate(out, display.translationX(), display.translationY(), display.translationZ(), out);
        Mat4.rotate(out, (float) Math.toRadians(display.rotationY()), 0f, 1f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(display.rotationX()), 1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(display.rotationZ()), 0f, 0f, 1f, out);
        Mat4.scale(out, display.scaleX(), display.scaleY(), display.scaleZ(), out);
        // RenderItem.renderItem: half size, centered on the model block.
        Mat4.scale(out, 0.5f, 0.5f, 0.5f, out);
        return Mat4.translate(out, -0.5f, -0.5f, -0.5f, out);
    }

    /** doItemUsedTransformations: the swing's sideways and upward push. */
    private static void usedTranslation(float swingProgress, float[] out) {
        float root = (float) Math.sqrt(swingProgress);
        Mat4.translate(out, -0.4f * sin(root * (float) Math.PI), 0.2f * sin(root * (float) Math.PI * 2f),
                -0.2f * sin(swingProgress * (float) Math.PI), out);
    }

    /** transformFirstPersonItem: the item's place in view, lowered while equipping, turned through a swing. */
    private static void place(float equipProgress, float swingProgress, float[] out) {
        Mat4.translate(out, 0.56f, -0.52f, -0.71999997f, out);
        Mat4.translate(out, 0f, equipProgress * -0.6f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(45), 0f, 1f, 0f, out);
        float swing = sin(swingProgress * swingProgress * (float) Math.PI);
        float swingRoot = sin((float) Math.sqrt(swingProgress) * (float) Math.PI);
        Mat4.rotate(out, (float) Math.toRadians(swing * -20f), 0f, 1f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(swingRoot * -20f), 0f, 0f, 1f, out);
        Mat4.rotate(out, (float) Math.toRadians(swingRoot * -80f), 1f, 0f, 0f, out);
        Mat4.scale(out, 0.4f, 0.4f, 0.4f, out);
    }

    /** performDrinking: the item brought to the mouth, bobbing until the last fifth of the use. */
    private static void drinking(float useLeft, int maxUse, float[] out) {
        float fraction = useLeft / maxUse;
        float bob = Math.abs((float) Math.cos(useLeft / 4f * Math.PI) * 0.1f);
        if (fraction >= 0.8f) {
            bob = 0f;
        }
        Mat4.translate(out, 0f, bob, 0f, out);
        float lift = 1f - (float) Math.pow(fraction, 27.0);
        Mat4.translate(out, lift * 0.6f, lift * -0.5f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(lift * 90f), 0f, 1f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(lift * 10f), 1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(lift * 30f), 0f, 0f, 1f, out);
    }

    /** doBowTransformations: the bow turned to aim, pulled back and trembling once drawn. */
    private static void bow(float useLeft, int maxUse, float[] out) {
        Mat4.rotate(out, (float) Math.toRadians(-18), 0f, 0f, 1f, out);
        Mat4.rotate(out, (float) Math.toRadians(-12), 0f, 1f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(-8), 1f, 0f, 0f, out);
        Mat4.translate(out, -0.9f, 0.2f, 0f, out);
        float drawn = maxUse - useLeft;
        float power = drawn / 20f;
        power = (power * power + power * 2f) / 3f;
        if (power > 1f) {
            power = 1f;
        }
        if (power > 0.1f) {
            float shake = sin((drawn - 0.1f) * 1.3f) * (power - 0.1f);
            Mat4.translate(out, 0f, shake * 0.01f, 0f, out);
        }
        Mat4.translate(out, 0f, 0f, power * 0.1f, out);
        Mat4.scale(out, 1f, 1f, 1f + power * 0.2f, out);
    }

    /**
     * Eye-space directions toward vanilla's two item lights for a view pitch and yaw (degrees): the standard
     * item lights placed under the view's rotation, as rotateArroundXAndY does. Writes 3 floats to each array.
     */
    public static void lights(float pitchDegrees, float yawDegrees, float[] light0, float[] light1) {
        float[] rotation = Mat4.identity(new float[16]);
        Mat4.rotate(rotation, (float) Math.toRadians(pitchDegrees), 1f, 0f, 0f, rotation);
        Mat4.rotate(rotation, (float) Math.toRadians(yawDegrees), 0f, 1f, 0f, rotation);
        apply(rotation, 0.2f, 1.0f, -0.7f, light0);
        apply(rotation, -0.2f, 1.0f, 0.7f, light1);
    }

    private static void apply(float[] m, float x, float y, float z, float[] out) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;
        out[0] = m[0] * x + m[4] * y + m[8] * z;
        out[1] = m[1] * x + m[5] * y + m[9] * z;
        out[2] = m[2] * x + m[6] * y + m[10] * z;
    }

    private static float sin(float radians) {
        return (float) Math.sin(radians);
    }
}
