"""Guard Micronaut benchmark database/Redis compilation dependencies."""
from pathlib import Path
from xml.etree import ElementTree as ET
import unittest


class MicronautDependencyTest(unittest.TestCase):
    def test_db_redis_workload_dependencies_are_on_compile_classpath(self):
        root = Path(__file__).resolve().parents[1]
        pom = ET.parse(root / "micronaut-app" / "pom.xml").getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        dependencies = pom.findall("./m:dependencies/m:dependency", ns)
        direct = {
            (dep.findtext("m:groupId", namespaces=ns), dep.findtext("m:artifactId", namespaces=ns)):
            dep.findtext("m:scope", default="compile", namespaces=ns)
            for dep in dependencies
        }
        for coordinate in [
            ("com.zaxxer", "HikariCP"),
            ("org.postgresql", "postgresql"),
            ("io.lettuce", "lettuce-core"),
        ]:
            self.assertIn(coordinate, direct, f"Missing direct Maven dependency: {coordinate}")
            self.assertIn(direct[coordinate], ("compile", "runtime"))


if __name__ == "__main__":
    unittest.main()
