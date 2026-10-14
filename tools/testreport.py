"""Summarize JUnit XML results: total count plus every failure message."""
import glob
import os
import re

total = failed = 0
for f in glob.glob('core/build/test-results/test/*.xml'):
    s = open(f, encoding='utf8').read()
    total += int(re.search(r'tests="(\d+)"', s).group(1))
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error) message="([^"]{0,400})', s):
        failed += 1
        print(os.path.basename(f), m.group(1), '=>', m.group(2))
print('total', total, 'failed', failed)
