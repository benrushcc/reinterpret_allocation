package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(value = Mode.AverageTime)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector",
        "-Xmx1g", "-Xms1g"
})
public class Utf8ValidationBench {

    private static final int BATCH = 1000;

    @Param({"16", "64", "256"})
    private int N;

    private List<byte[]> utfMixedList;


    @Setup(Level.Trial)
    public void setup() {
        Random random = ThreadLocalRandom.current();
        utfMixedList = new ArrayList<>(BATCH);
        String[] allSamples = {"a", "hello", "\u00A2", "\u20AC", "\u4E2D", "\uD83D\uDE00"};

        for (int i = 0; i < BATCH; i++) {
            StringBuilder sbMixed = new StringBuilder();
            for (int j = 0; j < N; j++) {
                sbMixed.append(allSamples[random.nextInt(allSamples.length)]);
            }
            utfMixedList.add(sbMixed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        utfMixedList = null;
    }

    // ==================== scalarValidateHeap ====================
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void scalarUtfMixed(Blackhole bh) {
        for (int i = 0; i < BATCH; i++) {
            byte[] data = utfMixedList.get(i);
            bh.consume(Utf8Validator.scalarValidateHeap(data, 0, data.length));
        }
    }

    // ==================== validate (vectorized) ====================
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void vecValidateUtfMixed(Blackhole bh) {
        for (int i = 0; i < BATCH; i++) {
            byte[] data = utfMixedList.get(i);
            bh.consume(Utf8Validator.validate(data, 0, data.length));
        }
    }
}
