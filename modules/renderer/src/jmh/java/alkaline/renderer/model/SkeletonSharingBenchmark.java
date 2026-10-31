package alkaline.renderer.model;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The CPU side of submitting one fully armored player to the model renderer: the body, then helmet, chestplate,
 * leggings and boots posed as the wearer. {@code separate} resolves and writes every layer's skeleton (the path
 * before skeleton sharing); {@code shared} resolves the body once and gives each armor layer a header with its
 * part mask. The stream is a plain array standing in for mapped memory.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SkeletonSharingBenchmark {
    private ModelPose body;
    private final ModelPose[] armor = new ModelPose[4];
    private final float[] root = new float[16];
    private final float[] matrices = new float[CuboidModel.MAX_PARTS * PoseMath.FLOATS_PER_PART];
    private final float[] stream = new float[1 << 16];
    private final SkeletonMatch match = new SkeletonMatch();

    @Setup
    public void setUp() {
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.limbSwing = 3.7f;
        motion.limbSwingAmount = 0.8f;
        motion.ageInTicks = 41.5f;
        motion.headYaw = 25f;
        motion.headPitch = -10f;
        motion.swingProgress = 0.4f;
        body = new ModelPose(EntityModels.model(EntityModels.Kind.PLAYER));
        EntityModels.animate(EntityModels.Kind.PLAYER, motion, body);
        LivingTransforms.root(4f, 64f, -2f, 30f, 1f, root);
        int[][] shown = {{EntityModels.HEAD, EntityModels.HAT}, {EntityModels.BODY, EntityModels.RIGHT_ARM,
                EntityModels.LEFT_ARM}, {EntityModels.BODY, EntityModels.RIGHT_LEG, EntityModels.LEFT_LEG},
                {EntityModels.RIGHT_LEG, EntityModels.LEFT_LEG}};
        for (int piece = 0; piece < 4; piece++) {
            armor[piece] = new ModelPose(EntityModels.model(piece == 2 ? EntityModels.Kind.ARMOR_INNER
                    : EntityModels.Kind.ARMOR_OUTER));
            armor[piece].copyFrom(body, EntityModels.BIPED_PARTS);
            armor[piece].visibleAll(false);
            for (int part : shown[piece]) {
                armor[piece].visible(part, true);
            }
        }
    }

    private int write(int at, int parts) {
        System.arraycopy(matrices, 0, stream, at, parts * PoseMath.FLOATS_PER_PART);
        return at + parts * PoseMath.FLOATS_PER_PART;
    }

    @Benchmark
    public void separate(Blackhole hole) {
        PoseMath.resolve(body, root, matrices);
        int at = write(0, body.model().partCount());
        for (ModelPose layer : armor) {
            PoseMath.resolve(layer, root, matrices);
            at = write(at, layer.model().partCount());
        }
        hole.consume(at);
    }

    @Benchmark
    public void shared(Blackhole hole) {
        PoseMath.resolveAll(body, root, matrices);
        int at = write(0, body.model().partCount());
        match.remember(body, root);
        long masks = PoseMath.visibleMask(body);
        stream[at] = Float.intBitsToFloat((int) masks);
        at += 4;
        for (ModelPose layer : armor) {
            if (!match.matches(layer, root)) {
                throw new IllegalStateException("armor should share the wearer's skeleton");
            }
            long mask = PoseMath.visibleMask(layer);
            stream[at] = Float.intBitsToFloat((int) mask);
            at += 4;
        }
        hole.consume(at);
    }
}
