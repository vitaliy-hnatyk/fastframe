import sys,unittest,tempfile,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]));import protocols
class ProtocolBenchmarkTests(unittest.TestCase):
 def test_strict_version_and_http_status(self):
  self.assertTrue(protocols.parse('3,200,0.02\n','h3')[0][3]);self.assertFalse(protocols.parse('2,200,0.02\n','h3')[0][3]);self.assertFalse(protocols.parse('3,415,0.02\n','h3')[0][3])
 def test_report_offline(self):
  with tempfile.TemporaryDirectory() as d:
   row={'framework':'fastframe','requested':'h3','observed':'2','status':'INVALID_OR_FALLBACK','rps':None,'p99_ms':None,'stderr':'error'}
   protocols.report(Path(d),[row]);self.assertIn('INVALID_OR_FALLBACK',(Path(d)/'report.html').read_text(encoding='utf-8'))
   self.assertEqual(json.loads((Path(d)/'results.json').read_text(encoding='utf-8'))[0]['rps'],None)
