#!/usr/bin/env python3
"""Patch the preserved StageMonitor.class world comparison for Paper 26.1.2+.

Old bytecode calls org.bukkit.World.equals with invokevirtual. Paper 26.1.2 exposes
World as an interface, causing IncompatibleClassChangeError. This fixed-length
patch replaces that call+branch with reference identity comparison and preserves
all surrounding StageMonitor logic.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_stage_monitor_world_interface.py <StageMonitor.class>")

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
old = bytes.fromhex("b601e79a000504ac")
new = bytes.fromhex("a5000804ac000000")
count = bytes(data).count(old)
if count != 1:
    raise SystemExit(f"expected exactly one target sequence, found {count}")
i = bytes(data).find(old)
data[i:i+len(old)] = new
path.write_bytes(data)
print(f"patched StageMonitor.class at byte offset {i}")
