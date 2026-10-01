#!/usr/bin/env python3
"""Render a named Kubernetes release without changing the source templates."""
import argparse
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--release", required=True, help="Unique release ID: lowercase letters, digits and hyphens, up to 40 characters")
parser.add_argument("--image", required=True, help="Already built image tag or registry digest")
args = parser.parse_args()
if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?", args.release):
    parser.error("--release must be a DNS-compatible identifier of 1–40 characters")
if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@-]*", args.image):
    parser.error("--image contains unsupported characters")
if ":" not in args.image.rsplit("/", 1)[-1] or args.image.endswith(":latest"):
    parser.error("--image must include an explicit version tag or digest, not latest")

root = Path(__file__).resolve().parent.parent
destination = root / ".local" / "k8s" / args.release
if destination.exists():
    parser.error("This release directory already exists; choose a new release ID")
rendered = {}
for name in ("configmap.yaml", "migrate.yaml", "web.yaml", "worker.yaml"):
    text = (root / "k8s" / name).read_text()
    text = text.replace("__RELEASE_ID__", args.release).replace("__APP_IMAGE__", json.dumps(args.image))
    if "__RELEASE_ID__" in text or "__APP_IMAGE__" in text:
        parser.error("Unresolved template placeholder")
    rendered[name] = text
destination.mkdir(parents=True)
for name, text in rendered.items():
    (destination / name).write_text(text)
(destination / "release.json").write_text(json.dumps(
    {"release": args.release, "image": args.image, "secret": "link-tracker-secrets"},
    indent=2) + "\n")
print(destination)
