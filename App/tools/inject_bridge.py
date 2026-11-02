#!/usr/bin/env python3
"""Inject the Cobalt native bridge into the built web app."""

from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: inject_bridge.py <www-dir>", file=sys.stderr)
        return 1

    www = Path(sys.argv[1]).resolve()
    if not www.is_dir():
        print(f"not a directory: {www}", file=sys.stderr)
        return 1

    bridge = www / "cobalt-bridge.js"
    if not bridge.is_file():
        print(f"missing bridge script: {bridge}", file=sys.stderr)
        return 1

    snippet = (
        '<script src="/cobalt-bridge.js"></script>\n'
        '<meta name="theme-color" content="#000000">\n'
    )

    count = 0
    for html in sorted(www.rglob("*.html")):
        text = html.read_text(encoding="utf-8")
        if "cobalt-bridge.js" in text:
            continue
        if "<head>" in text:
            text = text.replace("<head>", "<head>\n" + snippet, 1)
        elif "<head " in text:
            # Handle a <head> tag with attributes.
            index = text.find("<head ")
            end = text.find(">", index)
            if end != -1:
                text = text[:end + 1] + "\n" + snippet + text[end + 1:]
        else:
            text = snippet + text
        html.write_text(text, encoding="utf-8")
        count += 1

    print(f"injected bridge into {count} html file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
