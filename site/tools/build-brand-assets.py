#!/usr/bin/env python3
"""Rebuild brand exports from logo.svg. Requires rsvg-convert and Pillow."""
from pathlib import Path
import subprocess
from PIL import Image

public = Path(__file__).resolve().parents[1] / 'src/main/resources/public'
assets = public / 'assets'
logo = assets / 'logo.svg'
(assets / 'favicon.svg').write_bytes(logo.read_bytes())

def render(source, target, width, height):
    subprocess.run(['rsvg-convert', '-w', str(width), '-h', str(height),
                    str(source), '-o', str(target)], check=True)

render(logo, assets / 'icon-1024.png', 1024, 1024)
render(logo, assets / 'favicon-32.png', 32, 32)
icon = Image.open(assets / 'icon-1024.png').convert('RGBA')
icon.save(public / 'favicon.ico', sizes=[(16, 16), (32, 32), (48, 48)])
# Opaque backgrounds survive app-icon masks and circular social avatars.
for name, size, inset in [('apple-touch-icon.png', 180, 6), ('instagram-profile-1080.png', 1080, 66)]:
    canvas = Image.new('RGBA', (size, size), '#0c0e10')
    mark = icon.resize((size - inset * 2, size - inset * 2), Image.Resampling.LANCZOS)
    canvas.alpha_composite(mark, (inset, inset))
    canvas.convert('RGB').save(assets / name)

# Inline the vector so the social SVG is portable and has no linked-image dependency.
mark_svg = logo.read_text().replace('viewBox="0 0 512 512"', 'x="65" y="112" width="400" height="400" viewBox="0 0 512 512"', 1)
social = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 630">
<title>Machine Witness — Six points of view</title>
<rect width="1200" height="630" fill="#0c0e10"/>
<rect x="28" y="28" width="1144" height="574" rx="3" fill="none" stroke="#303438"/>
'''+mark_svg+'''
<g font-family="Arial, Helvetica, sans-serif">
  <text x="530" y="153" fill="#a8adb3" font-size="16" letter-spacing="3">AN EXPERIMENT IN AI &amp; ART</text>
  <text x="527" y="247" fill="#f0eee8" font-family="Georgia, serif" font-size="66" letter-spacing="-2">Machine Witness</text>
  <text x="530" y="311" fill="#d3d0c9" font-size="29">One week. Six points of view.</text>
  <text x="530" y="366" fill="#a8adb3" font-size="21">Six AI models turn the same news into art.</text>
  <text x="530" y="400" fill="#a8adb3" font-size="21">Every piece. Every perspective. Every week.</text>
  <circle cx="537" cy="456" r="6" fill="#4285f4"/><text x="553" y="462" fill="#a8adb3" font-size="17">Gemini</text>
  <circle cx="705" cy="456" r="6" fill="#d97757"/><text x="721" y="462" fill="#a8adb3" font-size="17">Claude</text>
  <circle cx="885" cy="456" r="6" fill="#10a37f"/><text x="901" y="462" fill="#a8adb3" font-size="17">ChatGPT</text>
  <circle cx="537" cy="493" r="6" fill="#cbd5e1"/><text x="553" y="499" fill="#a8adb3" font-size="17">Grok</text>
  <circle cx="705" cy="493" r="6" fill="#8b5cf6"/><text x="721" y="499" fill="#a8adb3" font-size="17">DeepSeek</text>
  <circle cx="885" cy="493" r="6" fill="#e8b32c"/><text x="901" y="499" fill="#a8adb3" font-size="17">Mistral</text>
  <text x="530" y="557" fill="#a8adb3" font-size="15" letter-spacing="2">MACHINEWITNESS.ART</text>
</g></svg>
'''
(assets / 'og-image.svg').write_text(social)
render(assets / 'og-image.svg', assets / 'og-image.png', 1200, 630)
print('Rebuilt favicon, app, profile, and social assets from logo.svg.')
