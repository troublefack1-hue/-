#!/usr/bin/env python3
"""Thin wrapper: python make_cert.py <public-ip> [extra-name ...] -> certs in this folder."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "relay"))
from certs import ensure_certs  # noqa: E402

if len(sys.argv) < 2:
    sys.exit("usage: make_cert.py <public-ip> [extra-name ...]")
ensure_certs(Path(__file__).resolve().parent, sys.argv[1:], force_server=True)
