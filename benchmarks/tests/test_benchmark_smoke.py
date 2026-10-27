"""Prevent the dependency-free javac smoke from compiling Maven-only DB/Redis code."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class BenchmarkSmokeScopeTest(unittest.TestCase):
    def test_javac_smoke_lists_only_jdk_and_fastframe_sources(self):
        script = (ROOT / "scripts/benchmark-smoke.sh").read_text(encoding="utf-8")
        self.assertIn("smoke_sources=(", script)
        self.assertIn('"${smoke_sources[@]}"', script)
        self.assertIn('$benchmark_src/BenchController.java', script)
        self.assertIn('$benchmark_src/AsyncJwtController.java', script)
        self.assertNotIn('$benchmark_src/DbRedisWorkload.java', script)
        self.assertNotIn('$benchmark_src/DbRedisController.java', script)
        self.assertNotIn("$(find benchmarks/fastframe-app/src/main/java", script)

    def test_maven_ci_still_compiles_full_benchmark_module(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn('run: bash scripts/benchmark-smoke.sh', workflow)
        self.assertIn('mvn -B -ntp -pl benchmarks/fastframe-app -am clean package', workflow)
        pom = (ROOT / "benchmarks/fastframe-app/pom.xml").read_text(encoding="utf-8")
        self.assertIn('<artifactId>HikariCP</artifactId>', pom)
        self.assertIn('<artifactId>framework-cache-redis</artifactId>', pom)


if __name__ == "__main__":
    unittest.main()
