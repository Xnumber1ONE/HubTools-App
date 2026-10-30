import re
import sys
from pathlib import Path

PRODUCT = "HubTools"
COMPANY = "Xnumber1ONE"
DESCRIPTION = "HubTools PC - shares your Steam playtime and achievements with your phone"
COPYRIGHT = "Copyright (c) 2026 HubTools"
EXE_NAME = "HubTools.exe"


def parts(version: str):
    nums = [int(n) for n in re.findall(r"\d+", version or "")][:4]
    return tuple(nums + [0] * (4 - len(nums))) if nums else (0, 0, 0, 0)


v = parts(sys.argv[1] if len(sys.argv) > 1 else "")
dotted = ".".join(map(str, v))
text = f"""VSVersionInfo(
  ffi=FixedFileInfo(
    filevers={v},
    prodvers={v},
    mask=0x3f,
    flags=0x0,
    OS=0x40004,
    fileType=0x1,
    subtype=0x0,
    date=(0, 0)
  ),
  kids=[
    StringFileInfo([
      StringTable('040904B0', [
        StringStruct('CompanyName', '{COMPANY}'),
        StringStruct('FileDescription', '{DESCRIPTION}'),
        StringStruct('FileVersion', '{dotted}'),
        StringStruct('InternalName', 'HubTools-PC'),
        StringStruct('LegalCopyright', '{COPYRIGHT}'),
        StringStruct('OriginalFilename', '{EXE_NAME}'),
        StringStruct('ProductName', '{PRODUCT}'),
        StringStruct('ProductVersion', '{dotted}')
      ])
    ]),
    VarFileInfo([VarStruct('Translation', [1033, 1200])])
  ]
)
"""
out = Path(__file__).resolve().parent / "version_info.txt"
out.write_text(text, encoding="utf-8")
print(f"Wrote {out.name} for version {dotted}")
