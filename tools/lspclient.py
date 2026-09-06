#!/usr/bin/env python3
"""Minimal LSP client over stdio, used to drive vrml-lsp from the shell.

Kept deliberately small: framing, one request at a time, notifications fire and
forget. It is a test harness, not a client library - if it grows options it has
outgrown its purpose and the JUnit-side fixtures should take over.

usage:
    tools/lspclient.py [--jar target/vrml-lsp.jar] {smoke|open FILE LINE CHAR}
"""
import argparse
import json
import os
import subprocess
import sys
import time


class LspProcess:
    def __init__(self, jar, extra_jvm=()):
        cmd = ["java", "-jar", jar, *extra_jvm]
        # stderr carries our log; keep it on the terminal so failures are visible.
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=sys.stderr)
        self._id = 0

    def request(self, method, params, timeout=20.0):
        self._id += 1
        mid = self._id
        self._send({"jsonrpc": "2.0", "id": mid, "method": method, "params": params})
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = self._recv(timeout - (time.time() - deadline))
            if msg is None:
                break
            if msg.get("id") == mid and ("result" in msg or "error" in msg):
                if "error" in msg:
                    raise RuntimeError(f"{method} -> {msg['error']}")
                return msg["result"]
            # Interleaved server-initiated request/notification: show and keep going.
            print(f"  [server] {msg.get('method') or 'request'} "
                  f"{_brief(msg.get('params'))}", file=sys.stderr)
        raise TimeoutError(f"no response to {method} (id={mid}) within {timeout}s")

    def notify(self, method, params=None):
        body = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        self._send(body)

    def wait_for_notification(self, method, timeout=10.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = self._recv(timeout - (time.time() - deadline))
            if msg is None:
                break
            if msg.get("method") == method:
                return msg.get("params")
        return None

    def _send(self, body):
        data = json.dumps(body).encode("utf-8")
        self.p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
        self.p.stdin.flush()

    def _recv(self, timeout):
        stream = self.p.stdout
        if timeout is not None and timeout <= 0:
            return None
        deadline = None if timeout is None else time.time() + timeout

        def readline_or_none():
            line = stream.readline()
            if not line:
                return None
            return line.decode("utf-8", "replace").rstrip("\r\n")

        length = None
        while True:
            line = readline_or_none()
            if line is None:
                self._die("server closed stdout")
            if line == "":
                break
            if line.lower().startswith("content-length:"):
                length = int(line.split(":", 1)[1].strip())
        if length is None:
            self._die("frame without Content-Length")
        body = stream.read(length)
        if len(body) != length:
            self._die("truncated frame")
        if deadline:
            pass  # framing worked; timing only guards the caller loop
        return json.loads(body.decode("utf-8"))

    def _die(self, why):
        code = self.p.poll()
        raise RuntimeError(f"{why} (exit={code})")

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        try:
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def _brief(obj):
    s = json.dumps(obj, ensure_ascii=False)
    return s if len(s) <= 160 else s[:157] + "..."


def doc_uri(path):
    return "file://" + os.path.abspath(path)


def initialize(client, root):
    res = client.request("initialize", {
        "processId": os.getpid(),
        "clientInfo": {"name": "lspclient.py"},
        "rootUri": doc_uri(root) if root else None,
        "capabilities": {
            "textDocument": {
                "synchronization": {"dynamicTextDocumentSyncOptions": True},
                "completion": {"completionItem": {"snippetSupport": True}},
                "publishDiagnostics": {"relatedInformation": True},
            },
            "workspace": {"workspaceFolders": True},
        },
        "workspaceFolders": [{"uri": doc_uri(root), "name": os.path.basename(root)}] if root else None,
    })
    client.notify("initialized", {})
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default="target/vrml-lsp.jar")
    ap.add_argument("--root", default=".")
    ap.add_argument("command", nargs="?", default="smoke")
    ap.add_argument("args", nargs="*")
    ns = ap.parse_args()

    client = LspProcess(ns.jar)
    try:
        res = initialize(client, ns.root)
        caps = res.get("capabilities", {})
        print("serverInfo          :", json.dumps(res.get("serverInfo")))
        print("positionEncoding    :", caps.get("positionEncoding"))
        print("textDocumentSync    :", json.dumps(caps.get("textDocumentSync")))
        print("completionProvider  :", json.dumps(caps.get("completionProvider")))
        for key in ("hoverProvider", "documentSymbolProvider", "definitionProvider",
                    "referencesProvider", "declarationProvider",
                    "documentFormattingProvider", "documentRangeFormattingProvider"):
            print(f"{key:22}: {caps.get(key)}")

        if ns.command == "smoke":
            client.request("shutdown", None)
            client.notify("exit")
            print("SMOKE OK")
            return 0
        if ns.command == "open" and ns.args:
            path = ns.args[0]
            text = open(path, encoding="utf-8", errors="replace").read()
            client.notify("textDocument/didOpen", {"textDocument": {
                "uri": doc_uri(path), "languageId": "vrml97", "version": 1, "text": text}})
            for feature, params in (
                ("textDocument/documentSymbol", {"textDocument": {"uri": doc_uri(path)}}),
                ("textDocument/completion", {"textDocument": {"uri": doc_uri(path)},
                                             "position": {"line": int(ns.args[1]),
                                                          "character": int(ns.args[2])},
                                             "context": {"triggerKind": 1}}),
            ):
                out = client.request(feature, params)
                print(f"{feature:32}: {_brief(out)}")
            client.request("shutdown", None)
            client.notify("exit")
            return 0
        raise SystemExit("unknown command " + ns.command)
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
