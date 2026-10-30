#!/usr/bin/env python3
"""HubTools PC: shares your Steam playtime and achievements with the HubTools phone app
over your home Wi-Fi. Nothing is uploaded anywhere.

Run it, open the app, tap "Find my PC" and type the code shown here.
"""
import argparse
import ctypes
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import sync

HTTP_PORT = 8787
DISCOVERY_PORT = 8788
DATA = Path(os.environ.get("HUBTOOLS_DATA") or (Path(os.environ.get("APPDATA") or Path.home()) / "HubTools"))
lock = threading.Lock()
state = {"json": b'{"generated_at":0,"games":[]}'}
fails = {}  # ip -> (wrong attempts, locked until)


def identity():
    DATA.mkdir(parents=True, exist_ok=True)
    f = DATA / "pairing.json"
    if f.exists():
        try:
            d = json.loads(f.read_text())
            if d.get("id") and d.get("code"):
                return d
        except Exception:
            pass
    d = {"id": uuid.uuid4().hex, "code": f"{secrets.randbelow(10**6):06d}"}
    f.write_text(json.dumps(d))
    return d


IDENT = identity()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, status, body):
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        ip = self.client_address[0]
        count, until = fails.get(ip, (0, 0))
        if time.time() < until:
            self.send(429, {"error": "too many wrong codes"})
            return False
        given = self.headers.get("X-Code", "")
        if secrets.compare_digest(given.encode(), IDENT["code"].encode()):
            fails.pop(ip, None)
            return True
        count += 1
        fails[ip] = (count, time.time() + 300 if count >= 8 else 0)
        self.send(401, {"error": "wrong code"})
        return False

    def do_GET(self):
        if self.path.startswith("/ping"):
            self.send(200, {"app": "hubtools", "id": IDENT["id"], "name": socket.gethostname()})
        elif self.path.startswith("/auth"):
            if self.authorized():
                self.send(200, {"ok": True})
        elif self.path.startswith("/stats.json"):
            if self.authorized():
                with lock:
                    body = state["json"]
                self.send(200, body)
        else:
            self.send(404, {})


def discovery():
    """Answers the phone's 'who is out there?' broadcast."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", DISCOVERY_PORT))
        reply = json.dumps({"id": IDENT["id"], "name": socket.gethostname(), "port": HTTP_PORT}).encode()
        while True:
            data, addr = s.recvfrom(256)
            if data.strip() == b"HUBTOOLS?":
                s.sendto(reply, addr)
    except OSError as e:
        print("Auto-discovery is off (", e, "). Enter the address manually in the app.")


def rebuild(steam, account, fetch):
    data = sync.collect(steam, account, {"exclude_appids": [480]}, fetch)
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with lock:
        state["json"] = text
    return len(data["games"])


def builder(steam, account):
    sync.NAMES, sync.ART = DATA / "names_cache.json", DATA / "art_cache.json"
    try:
        rebuild(steam, account, False)   # quick first version
        print(f"Ready: {rebuild(steam, account, True)} games.")  # then names and covers
    except Exception as e:
        print("Could not read Steam data:", e)
    last = sync.signature(steam, account)
    while True:
        time.sleep(30)
        try:
            sig = sync.signature(steam, account)
            if sig != last:
                time.sleep(10)
                last = sync.signature(steam, account)
                rebuild(steam, account, True)
        except Exception as e:
            print("Update failed:", e)


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "your PC's IP"


def firewall_rule_exists():
    """True when Windows already has our allow rule (for the current program path when packaged)."""
    try:
        r = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", "name=HubTools PC", "verbose"],
                           capture_output=True, text=True, creationflags=0x08000000)
        if r.returncode != 0:
            return False
        if getattr(sys, "frozen", False):
            return sys.executable.lower() in r.stdout.lower()
        return True
    except Exception:
        return True  # cannot check, do not nag


def ensure_firewall():
    """Windows silently blocks incoming connections on "Public" networks (common on Ethernet) and when the
    allow prompt was dismissed. Add our own rule for every network type. Windows asks for permission once."""
    if os.name != "nt" or firewall_rule_exists():
        return
    print("Windows will ask for permission once, so your phone can reach this PC. Please click Yes.\n")
    if getattr(sys, "frozen", False):
        prog = sys.executable
        cmds = [
            f'netsh advfirewall firewall delete rule name=all program="{prog}"',
            f'netsh advfirewall firewall add rule name="HubTools PC" dir=in action=allow program="{prog}" profile=any',
        ]
    else:
        cmds = [
            f'netsh advfirewall firewall add rule name="HubTools PC" dir=in action=allow protocol=TCP localport={HTTP_PORT} profile=any',
            f'netsh advfirewall firewall add rule name="HubTools PC discovery" dir=in action=allow protocol=UDP localport={DISCOVERY_PORT} profile=any',
        ]
    try:
        ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", "/c " + " & ".join(cmds), None, 0)
    except Exception:
        pass
    for _ in range(40):
        time.sleep(1)
        if firewall_rule_exists():
            print("Done. Your phone can now reach this PC.\n")
            return
    print("Permission was not given. If the app cannot find this PC, restart the program and click Yes.\n")


def set_startup(enable):
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                         0, winreg.KEY_SET_VALUE)
    if enable:
        exe = f'"{sys.executable}"' if getattr(sys, "frozen", False) else f'"{sys.executable}" "{Path(__file__).resolve()}"'
        winreg.SetValueEx(key, "HubToolsPC", 0, winreg.REG_SZ, exe + " --background")
    else:
        try:
            winreg.DeleteValue(key, "HubToolsPC")
        except FileNotFoundError:
            pass


def ask_startup():
    flag = DATA / "startup_asked"
    if flag.exists() or os.name != "nt":
        return
    flag.write_text("1")
    if input("Start HubTools PC automatically with Windows? (y/n): ").strip().lower().startswith("y"):
        try:
            set_startup(True)
            print("Done. It will start hidden when you sign in.")
        except Exception as e:
            print("Could not set it up:", e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--background", action="store_true", help="hide the window (used at startup)")
    ap.add_argument("--steam", help="Steam folder (auto-detected by default)")
    args = ap.parse_args()

    try:
        steam = Path(args.steam) if args.steam else sync.find_steam()
        account = sync.find_account(steam)
    except SystemExit as e:
        input(f"{e}\nPress Enter to close.")
        return

    if os.name == "nt":
        try:
            ctypes.windll.kernel32.SetConsoleTitleW("HubTools PC")
        except Exception:
            pass

    c = IDENT["code"]
    print("HubTools PC is running.\n")
    print(f"    Your code:  {c[:3]} {c[3:]}\n")
    print("  1. Open the HubTools app on your phone (same Wi-Fi as this PC).")
    print('  2. Tap "Find my PC" and pick this PC.')
    print("  3. Type the code above. You only do this once.\n")
    print(f"  Manual address if needed: {local_ip()}")
    print("  Keep this window open (you can minimize it).\n")
    if args.background:
        try:
            ctypes.windll.user32.ShowWindow(ctypes.windll.kernel32.GetConsoleWindow(), 0)
        except Exception:
            pass
    else:
        ensure_firewall()
        ask_startup()

    threading.Thread(target=discovery, daemon=True).start()
    threading.Thread(target=builder, args=(steam, account), daemon=True).start()
    try:
        ThreadingHTTPServer(("", HTTP_PORT), Handler).serve_forever()
    except OSError as e:
        input(f"Could not start (is HubTools PC already running?): {e}\nPress Enter to close.")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
