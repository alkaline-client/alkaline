package alkaline.renderer.world;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SectionMesherBenchmark {
    private static final MaterialTable MATERIALS = new MaterialTable(
            new MaterialPass[]{null, MaterialPass.OPAQUE, MaterialPass.CUTOUT, MaterialPass.TRANSLUCENT},
            new boolean[]{false, true, false, false});

    @Param({"16", "32"})
    public int size;

    @Param({"EMPTY", "SOLID", "PLANE", "SPARSE", "MIXED", "TRANSLUCENT"})
    public String shape;

    private SectionData section;
    private BaselineSectionMesher baselineMesher;
    private SectionMesher optimizedMesher;
    private BaselineMeshBuffer baselineOutput;
    private MeshBuffer optimizedOutput;
    private int visibleUnitFaces;
    private int baselineEmittedBytes;

    @Setup(Level.Trial)
    public void setup() {
        char[] voxels = new char[(size + 2) * (size + 2) * (size + 2)];
        int stride = size + 2;
        int sliceStride = stride * (size + 2);
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int id = materialAt(x, y, z);
                    voxels[(y + 1) * stride + (z + 1) * sliceStride + x + 1] = (char) id;
                }
            }
        }
        section = new SectionData(size, size, size, voxels);
        baselineMesher = new BaselineSectionMesher();
        optimizedMesher = new SectionMesher();
        baselineOutput = new BaselineMeshBuffer(0);
        optimizedOutput = new MeshBuffer(0);
        baselineMesher.mesh(section, MATERIALS, baselineOutput);
        optimizedMesher.mesh(section, MATERIALS, optimizedOutput);
        visibleUnitFaces = baselineOutput.vertexCount() / 4;
        baselineEmittedBytes = baselineOutput.emittedByteCount();
        System.out.printf("WORLD_MESHER case=%s size=%d visibleUnitFaces=%d baselineQuads=%d baselineBytes=%d optimizedQuads=%d optimizedBytes=%d%n",
                shape, size, visibleUnitFaces, visibleUnitFaces, baselineEmittedBytes,
                optimizedOutput.vertexCount() / 4, optimizedOutput.emittedByteCount());
    }

    @Benchmark
    public int baseline() {
        baselineMesher.mesh(section, MATERIALS, baselineOutput);
        return baselineOutput.vertexCount() + totalIndices(baselineOutput) + visibleUnitFaces + baselineEmittedBytes;
    }

    @Benchmark
    public int optimized() {
        optimizedMesher.mesh(section, MATERIALS, optimizedOutput);
        return optimizedOutput.vertexCount() + totalIndices(optimizedOutput) + visibleUnitFaces
                + optimizedOutput.emittedByteCount();
    }

    private int materialAt(int x, int y, int z) {
        return switch (shape) {
            case "EMPTY" -> 0;
            case "SOLID" -> 1;
            case "PLANE" -> y == size / 2 ? 1 : 0;
            case "SPARSE" -> (mix(x, y, z) & 15) == 0 ? 1 : 0;
            case "MIXED" -> switch (mix(x, y, z) & 3) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 3;
                default -> 0;
            };
            case "TRANSLUCENT" -> 3;
            default -> throw new IllegalArgumentException("Unknown shape: " + shape);
        };
    }

    private static int mix(int x, int y, int z) {
        int value = x * 0x9E3779B9 ^ y * 0x85EBCA6B ^ z * 0xC2B2AE35;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        return value;
    }

    private static int totalIndices(BaselineMeshBuffer output) {
        return output.indexCount(MaterialPass.OPAQUE) + output.indexCount(MaterialPass.CUTOUT)
                + output.indexCount(MaterialPass.TRANSLUCENT);
    }

    private static int totalIndices(MeshBuffer output) {
        return output.indexCount(MaterialPass.OPAQUE) + output.indexCount(MaterialPass.CUTOUT)
                + output.indexCount(MaterialPass.TRANSLUCENT);
    }
}
