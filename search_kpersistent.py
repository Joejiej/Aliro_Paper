import sys
sys.stdout.reconfigure(encoding='utf-8')
import pypdf

reader = pypdf.PdfReader('F:/paper-agent/source/26-42802-001_Aliro_1.0_specification.pdf')

for i, page in enumerate(reader.pages):
    text = page.extract_text() or ''
    tl = text.lower()
    if 'kpersistent' in tl and any(k in tl for k in ['deriv', 'generat', 'hkdf', 'salt_persistent', 'provid', 'establish']):
        print(f'=== Page {i+1} ===')
        print(text[:3000])
        print()
