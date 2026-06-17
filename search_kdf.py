import sys
sys.stdout.reconfigure(encoding='utf-8')
import pypdf

reader = pypdf.PdfReader('F:/paper-agent/source/26-42802-001_Aliro_1.0_specification.pdf')

for i, page in enumerate(reader.pages):
    text = page.extract_text() or ''
    if '8.3.1.5' in text or ('hkdf' in text.lower() and 'key material' in text.lower()):
        print(f'=== Page {i+1} ===')
        print(text[:3000])
        print()
