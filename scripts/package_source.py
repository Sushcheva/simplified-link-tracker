#!/usr/bin/env python3
"""Archive only source/configuration/documentation; never include local secrets or build products."""
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
version = ET.parse(root / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
destination = root / "dist" / f"simplified-link-tracker-{version}.zip"
destination.parent.mkdir(exist_ok=True)
excluded = {".git", ".idea", ".local", "target", "dist", "__pycache__", ".DS_Store"}
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or path.is_symlink() or any(part in excluded for part in relative.parts):
            continue
        if path.name.startswith(".env") and path.name != ".env.example":
            continue
        if path.suffix in {".log", ".iml", ".pyc"}:
            continue
        archive.write(path, Path("simplified-link-tracker") / relative)
print(destination)
