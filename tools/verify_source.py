"""Check plugin metadata and hook contracts against an exteraLess checkout.

Usage: python tools/verify_source.py PATH_TO_EXTERALESS
This is a source compatibility check, not an Android UI test.
"""

import ast
import importlib.util
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
REPO = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".reference" / "exteraless"
JAVA = REPO / "TMessagesProj" / "src" / "main" / "java"
PYTHON = REPO / "TMessagesProj" / "src" / "main" / "python"


def main():
    parser_path = PYTHON / "extera_utils" / "metadata_parser.py"
    spec = importlib.util.spec_from_file_location("verified_metadata_parser", parser_path)
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    metadata = parser.read_metadata(str(ROOT / "chat_tags.plugin"))
    assert metadata["id"] == "chat_tags", metadata
    assert set(metadata["permissions"]) == {"ui", "messages.read", "hooks"}, metadata
    tree = ast.parse((ROOT / "chat_tags.plugin").read_text(encoding="utf-8"))
    security = ast.parse((PYTHON / "extera_utils/plugin_loader.py").read_text(encoding="utf-8"))
    denied = next(ast.literal_eval(node.value.args[0]) for node in security.body
                  if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == "_JAVA_CLASS_DENIED"
                          for target in node.targets))
    literals = {node.value for node in ast.walk(tree)
                if isinstance(node, ast.Constant) and isinstance(node.value, str)}
    assert not literals.intersection(denied), ("Forbidden engine class", literals.intersection(denied))
    mappings = {
        "Profile": "org/telegram/ui/ProfileActivity.java",
        "Storage": "org/telegram/messenger/MessagesStorage.java",
        "Pager": "org/telegram/ui/Components/SearchViewPager.java",
    }
    methods = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        if node.func.attr != "_hook" or len(node.args) < 3:
            continue
        alias = node.args[0].attr
        name, expected = ast.literal_eval(node.args[1]), ast.literal_eval(node.args[2])
        source = (JAVA / mappings[alias]).read_text(encoding="utf-8")
        signatures = re.findall(
            r"(?:public|protected|private)\s+(?:(?:static|final|synchronized)\s+)*"
            r"[\w<>\[\].?]+\s+" + re.escape(name) + r"\s*\(([^)]*)\)", source)
        counts = [len(re.split(r",(?![^<]*>)", sig)) if sig.strip() else 0 for sig in signatures]
        assert counts.count(expected) == 1, (alias, name, expected, counts)
        methods.append(f"{alias}.{name}/{expected}")
    assert len(methods) == 11, methods
    storage = (JAVA / mappings["Storage"]).read_text(encoding="utf-8")
    controller = (JAVA / "org/telegram/messenger/BaseController.java").read_text(encoding="utf-8")
    assert "MessagesStorage extends BaseController" in storage
    assert "protected final int currentAccount" in controller
    for name in ("getUser", "getChat", "getEncryptedChat", "getUserSync", "getChatSync"):
        assert re.search(r"public TLRPC\.\w+ " + name + r"\(long \w+\)", storage), name
    print(json.dumps({"metadata": metadata, "hooks": methods, "result": "OK"},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
