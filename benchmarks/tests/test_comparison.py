import csv
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from compare_versions import load, summarize

class CompareVersionsTest(unittest.TestCase):
    def test_medians_and_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'res.csv'
            with p.open('w',newline='') as out:
                writer=csv.DictWriter(out,fieldnames=['framework','case','rps','p99_ms','errors'])
                writer.writeheader()
                writer.writerows([
                  {'framework':'fastframe','case':'json','rps':100,'p99_ms':12,'errors':0},
                  {'framework':'fastframe','case':'json','rps':200,'p99_ms':10,'errors':0},
                  {'framework':'micronaut','case':'json','rps':300,'p99_ms':9,'errors':0},
                ])
            base=load(p,'fastframe')
            x=summarize(base,base)[0]
            self.assertEqual(x['baseline_rps'],150)
            self.assertEqual(x['rps_change_pct'],0)
            self.assertEqual(x['baseline_p99_ms'],11)
            with p.open('a') as out:
                out.write('fastframe,plain,0,0,1\n')
            with self.assertRaises(ValueError): load(p,'fastframe')

if __name__=='__main__': unittest.main()
