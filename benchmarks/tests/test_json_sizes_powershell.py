"""PowerShell size-token parsing regression (Windows CI invokes the script itself)."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class JsonSizesPowerShellCompatibilityTest(unittest.TestCase):
    def test_all_byte_literals_map_to_python_size_keys(self):
        wrapper = (ROOT / 'benchmarks/json-sizes.ps1').read_text(encoding='utf-8')
        expected = {'1024': '1kb', '10240': '10kb',
                    '102400': '100kb', '1048576': '1mb'}
        for numeric, canonical in expected.items():
            with self.subTest(numeric=numeric):
                self.assertRegex(wrapper, r"'" + numeric + r"'\s*=\s*'" + canonical + r"'")
        self.assertIn('if (-not $sizeAliases.ContainsKey($key))', wrapper)
        self.assertIn('$Sizes = [string[]]@(', wrapper)
        self.assertIn("$argsList += @('--sizes') + $Sizes", wrapper)

    def test_windows_ci_quotes_the_values_not_numeric_literals(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn("-Sizes '1kb','10kb','100kb','1mb'", workflow)
        self.assertNotIn('-Sizes 1kb,10kb,100kb,1mb', workflow)

    def test_usage_example_quotes_all_sizes(self):
        doc = (ROOT / 'benchmarks/JSON_SIZES.md').read_text(encoding='utf-8')
        self.assertIn("-Sizes '1kb','10kb','100kb','1mb'", doc)


if __name__ == '__main__':
    unittest.main()
