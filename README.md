# Vector API `reinterpret` Allocation Bug Reproducer

本项目用于复现 JDK 28 EA 相较于 JDK 26 中 Vector API `ByteVector.reinterpretAsInts()` 行为变化导致的性能回退问题。

## 问题描述

在 JDK 26 中，`ByteVector.reinterpretAsInts()` 是一个零分配的 reinterpret 操作，仅重新解释向量的类型而不产生额外内存分配。然而在 JDK 28 EA 中，该操作产生了大量堆外/堆上分配（每次调用分配数百至数千字节），导致向量化 UTF-8 校验器的性能急剧下降，GC 压力显著增加。

核心表现：从 JDK 26 切换到 JDK 28 EA 后，向量化实现的 `gc.alloc.rate.norm` 从接近 0 B/op 飙升至数百甚至数千 B/op，GC count 从 ≈0 变为上百次。

该问题目前仅在 x86_64 环境（AMD Ryzen 9 7900X）上复现，在 MacBook aarch64（Apple Silicon）环境下未观察到类似现象。

## 运行环境

- **OS**: Ubuntu 26.04.1 LTS (WSL2, Kernel 6.18.33.2-microsoft-standard-WSL2)
- **CPU**: AMD Ryzen 9 7900X 12-Core Processor (24 threads, supports AVX-512)
- **JMH**: 1.37
- **JMH GC Profiler**: `-prof gc`

## 构建与运行

### JDK 26

```bash
sdk use java 26.0.1-open
mvn clean package -Dmaven.compiler.release=26
java --enable-preview --add-modules jdk.incubator.vector -jar ./target/benchmarks.jar -prof gc Utf8ValidationBench
```

### JDK 28 EA

```bash
sdk use java 28.0.0+ea.14-open
mvn clean package -Dmaven.compiler.release=28
java --enable-preview --add-modules jdk.incubator.vector -jar ./target/benchmarks.jar -prof gc Utf8ValidationBench
```

## Benchmark 结果

### JDK 26 (26.0.1-open)

| Benchmark | (N) | Score | Error | Units | gc.alloc.rate | gc.alloc.rate.norm | gc.count |
|---|---|---|---|---|---|---|---|
| scalarUtfMixed | 16 | 36.222 | ± 10.188 | ns/op | 0.007 MB/sec | ≈ 10⁻⁴ B/op | ≈ 0 |
| scalarUtfMixed | 64 | 309.775 | ± 10.959 | ns/op | 0.007 MB/sec | 0.002 B/op | ≈ 0 |
| scalarUtfMixed | 256 | 1246.112 | ± 48.994 | ns/op | 0.007 MB/sec | 0.009 B/op | ≈ 0 |
| **vecValidateUtfMixed** | **16** | **6.718** | **± 0.050** | **ns/op** | **0.007 MB/sec** | **≈ 10⁻⁴ B/op** | **≈ 0** |
| **vecValidateUtfMixed** | **64** | **15.886** | **± 0.250** | **ns/op** | **0.007 MB/sec** | **≈ 10⁻⁴ B/op** | **≈ 0** |
| **vecValidateUtfMixed** | **256** | **55.480** | **± 2.210** | **ns/op** | **0.007 MB/sec** | **≈ 10⁻³ B/op** | **≈ 0** |

向量化路径几乎零分配，GC count ≈ 0。

### JDK 28 EA (28.0.0+ea.14-open)

| Benchmark | (N) | Score | Error | Units | gc.alloc.rate | gc.alloc.rate.norm | gc.count |
|---|---|---|---|---|---|---|---|
| scalarUtfMixed | 16 | 32.824 | ± 3.319 | ns/op | 0.006 MB/sec | ≈ 10⁻⁴ B/op | ≈ 0 |
| scalarUtfMixed | 64 | 299.551 | ± 4.514 | ns/op | 0.006 MB/sec | 0.002 B/op | ≈ 0 |
| scalarUtfMixed | 256 | 1231.087 | ± 30.183 | ns/op | 0.006 MB/sec | 0.008 B/op | ≈ 0 |
| **vecValidateUtfMixed** | **16** | **26.312** | **± 0.477** | **ns/op** | **20292.070 MB/sec** | **560.000 B/op** | **165** |
| **vecValidateUtfMixed** | **64** | **66.080** | **± 1.572** | **ns/op** | **20448.562 MB/sec** | **1417.360 B/op** | **167** |
| **vecValidateUtfMixed** | **256** | **302.309** | **± 3.994** | **ns/op** | **20287.507 MB/sec** | **6432.722 B/op** | **166** |

向量化路径出现大量分配，GC count 激增至 160+ 次。

### 性能对比汇总

| (N) | JDK 26 vec ns/op | JDK 28 EA vec ns/op | 性能退化 | JDK 26 alloc B/op | JDK 28 EA alloc B/op |
|---|---|---|---|---|---|
| 16 | 6.718 | 26.312 | **~3.9x** | ≈ 10⁻⁴ | **560.000** |
| 64 | 15.886 | 66.080 | **~4.2x** | ≈ 10⁻⁴ | **1417.360** |
| 256 | 55.480 | 302.309 | **~5.4x** | ≈ 10⁻³ | **6432.722** |

标量实现在两个版本之间性能基本一致，确认问题出在 Vector API 层面。

## 项目结构

```
├── pom.xml                                              # Maven 构建配置
├── src/main/java/com/example/
│   ├── Utf8Validator.java                               # UTF-8 校验器 (向量 + 标量实现)
│   └── Utf8ValidationBench.java                         # JMH 基准测试
└── target/benchmarks.jar                                # 可执行 fat jar
```
