"""Embed native/build/dex/classes.dex into chat_tags.plugin as _NATIVE_DEX."""
import ast
import base64
from pathlib import Path
import sys

plugin = Path(__file__).resolve().parents[1] / "chat_tags.plugin"
dex = Path(sys.argv[1]).read_bytes()
assert dex.startswith(b"dex\n"), "Invalid DEX magic"
source = plugin.read_text(encoding="utf-8")
node = next(n for n in ast.parse(source).body if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "_NATIVE_DEX" for t in n.targets))
encoded = base64.b64encode(dex).decode("ascii")
replacement = "_NATIVE_DEX = (\n" + "".join('    "' + encoded[i:i + 100] + '"\n'
                                         for i in range(0, len(encoded), 100)) + ")\n"
lines = source.splitlines(keepends=True)
lines[node.lineno - 1:node.end_lineno] = [replacement]
plugin.write_bytes("".join(lines).encode("utf-8"))
print(f"Embedded DEX: {len(dex)} bytes")
