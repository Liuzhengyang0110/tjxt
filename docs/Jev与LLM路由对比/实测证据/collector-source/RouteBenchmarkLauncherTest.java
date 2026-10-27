package com.tianji.aigc.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;

/** Normal mvn test skips this explicit operator-only entry point. */
class RouteBenchmarkLauncherTest {
    @Test
    @EnabledIfSystemProperty(named="benchmark.run",matches="true")
    void launch() throws Exception {
        String root=System.getProperty("benchmark.root"),config=System.getProperty("benchmark.config");
        if(root==null||config==null)throw new IllegalArgumentException("benchmark.root and benchmark.config are required");
        System.out.println("Benchmark artifacts: "+RouteBenchmark.run(Path.of(root),Path.of(config)));
    }
}
