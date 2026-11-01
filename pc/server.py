#!/usr/bin/env python3
"""HubTools PC: shares your Steam playtime and achievements with the HubTools phone app
over your home Wi-Fi. Nothing is uploaded anywhere.

All files it creates (your code, cached game names and covers) live in a "data"
folder right next to the program, so you can move or delete the whole folder freely.
"""
import argparse
import ctypes
import json
import os
import secrets
import shutil
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
FROZEN = getattr(sys, "frozen", False)
APP_DIR = Path(sys.executable).parent if FROZEN else Path(__file__).resolve().parent
RES_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR))  # files bundled into the program (the icon)
DATA = Path(os.environ.get("HUBTOOLS_DATA") or (APP_DIR / "data"))

lock = threading.Lock()
IDENT = {}
fails = {}  # ip -> (wrong attempts, locked until)
state = {
    "json": b'{"generated_at":0,"games":[]}',
    "status": "Starting...",
    "ready": False,
    "error": False,
    "games": 0,
    "last_sync": 0.0,
    "note": "",
}


# ---------- data folder ----------
def migrate_old_data():
    """Older versions kept their files in %APPDATA%\\HubTools. Keep the same code so the phone stays paired."""
    appdata = os.environ.get("APPDATA")
    if not appdata or (DATA / "pairing.json").exists():
        return
    old = Path(appdata) / "HubTools"
    if not old.is_dir():
        return
    DATA.mkdir(parents=True, exist_ok=True)
    for name in ("pairing.json", "names_cache.json", "art_cache.json"):
        if (old / name).exists():
            shutil.copy2(old / name, DATA / name)


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


def load_settings():
    try:
        return json.loads((DATA / "settings.json").read_text())
    except Exception:
        return {}


def save_settings(d):
    DATA.mkdir(parents=True, exist_ok=True)
    (DATA / "settings.json").write_text(json.dumps(d))


# ---------- network side ----------
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
                state["last_sync"] = time.time()
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
        state["note"] = f"Automatic search is off ({e}). Enter the address in the app."


def rebuild(steam, account, fetch):
    data = sync.collect(steam, account, {"exclude_appids": [480]}, fetch)
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with lock:
        state["json"] = text
    state["games"] = len(data["games"])


def builder(steam, account):
    sync.NAMES, sync.ART = DATA / "names_cache.json", DATA / "art_cache.json"
    try:
        state["status"] = "Reading your Steam library..."
        rebuild(steam, account, False)  # quick first version
        state["ready"] = True
        state["status"] = "Ready. Looking up game names and covers (first time only)..."
        rebuild(steam, account, True)
        state["status"] = "Ready"
    except Exception as e:
        state["error"] = True
        state["status"] = f"Could not read Steam data: {e}"
    last = sync.signature(steam, account)
    while True:
        time.sleep(30)
        try:
            sig = sync.signature(steam, account)
            if sig != last:
                state["status"] = "Updating..."
                time.sleep(10)
                last = sync.signature(steam, account)
                rebuild(steam, account, True)
                state["status"] = "Ready"
        except Exception as e:
            state["status"] = f"Update failed: {e}"


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "unknown"


# ---------- Windows firewall ----------
def firewall_rule_exists():
    """True when Windows already has our allow rule (for the current program path when packaged)."""
    try:
        r = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", "name=HubTools PC", "verbose"],
                           capture_output=True, text=True, creationflags=0x08000000)
        if r.returncode != 0:
            return False
        if FROZEN:
            return sys.executable.lower() in r.stdout.lower()
        return True
    except Exception:
        return True  # cannot check, do not nag


def ensure_firewall():
    """Windows silently blocks incoming connections on "Public" networks (common on Ethernet) and when the
    allow prompt was dismissed. Add our own rule for every network type. Windows asks for permission once."""
    if os.name != "nt" or firewall_rule_exists():
        return
    state["note"] = "Windows is asking for permission so your phone can reach this PC. Please click Yes."
    if FROZEN:
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
    for _ in range(60):
        time.sleep(1)
        if firewall_rule_exists():
            state["note"] = ""
            return
    state["note"] = "Permission was not given, so the phone may not find this PC. Restart HubTools PC and click Yes."


# ---------- start with Windows ----------
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def startup_command():
    if FROZEN:
        return f'"{sys.executable}" --background'
    return f'"{sys.executable}" "{Path(__file__).resolve()}" --background'


def startup_enabled():
    if os.name != "nt":
        return False
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, "HubToolsPC")[0] == startup_command()
    except OSError:
        return False


def set_startup(enable):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enable:
            winreg.SetValueEx(k, "HubToolsPC", 0, winreg.REG_SZ, startup_command())
        else:
            try:
                winreg.DeleteValue(k, "HubToolsPC")
            except FileNotFoundError:
                pass


# ---------- window ----------
BG, CARD, LINE = "#0A0C12", "#141824", "#262D42"
TEXT, MUTED = "#F1F4FA", "#8A93A8"
ACCENT, GOLD = "#7B61FF", "#FFC857"
GREEN, AMBER, RED = "#3DDC97", "#FFB020", "#FF6B6B"


def ago(ts):
    if not ts:
        return "never"
    d = int(time.time() - ts)
    if d < 60:
        return "just now"
    if d < 3600:
        return f"{d // 60} min ago"
    if d < 86400:
        return f"{d // 3600} h ago"
    return f"{d // 86400} d ago"


def build_window(root, httpd, background):
    import tkinter as tk
    from tkinter import messagebox

    root.title("HubTools PC")
    root.configure(bg=BG)
    root.resizable(False, False)
    ico = RES_DIR / "hubtools.ico"
    try:
        root.iconbitmap(str(ico))
    except Exception:
        pass

    def label(parent, text="", size=10, color=TEXT, bold=False, font="Segoe UI", **kw):
        return tk.Label(parent, text=text, bg=parent["bg"], fg=color,
                        font=(font, size, "bold" if bold else "normal"), **kw)

    def card(pady=(0, 12)):
        f = tk.Frame(root, bg=CARD, highlightbackground=LINE, highlightthickness=1)
        f.pack(fill="x", padx=24, pady=pady)
        return f

    # header
    head = tk.Frame(root, bg=BG)
    head.pack(fill="x", padx=24, pady=(22, 14))
    label(head, "HubTools", 24, TEXT, True).pack(anchor="w")
    label(head, "PC companion for your phone app", 10, MUTED).pack(anchor="w")

    # code
    c = card()
    code = IDENT["code"]
    label(c, "YOUR CODE", 9, MUTED, True).pack(pady=(16, 0))
    label(c, f"{code[:3]} {code[3:]}", 38, GOLD, True, font="Consolas").pack(pady=(2, 6))
    copy_btn = tk.Button(c, text="Copy code", bg=ACCENT, fg="white", activebackground="#6A50E8",
                         activeforeground="white", relief="flat", bd=0, padx=18, pady=6,
                         font=("Segoe UI", 10, "bold"), cursor="hand2")

    def copy_code():
        root.clipboard_clear()
        root.clipboard_append(code)
        copy_btn.config(text="Copied")
        root.after(1500, lambda: copy_btn.config(text="Copy code"))

    copy_btn.config(command=copy_code)
    copy_btn.pack(pady=(0, 16))

    # steps
    s = card()
    steps = ("1.  Open HubTools on your phone (same Wi-Fi).\n"
             "2.  Tap  Find my PC  and pick this PC.\n"
             "3.  Type the code above. Only needed once.")
    label(s, steps, 10, TEXT, justify="left").pack(anchor="w", padx=16, pady=14)

    # status
    st = card()
    row = tk.Frame(st, bg=CARD)
    row.pack(fill="x", padx=16, pady=(14, 2))
    dot = label(row, "\u25cf", 12, AMBER)
    dot.pack(side="left")
    status = label(row, "", 10, TEXT, wraplength=330, justify="left")
    status.pack(side="left", padx=(8, 0))
    synced = label(st, "", 9, MUTED)
    synced.pack(anchor="w", padx=16)
    label(st, f"PC address (if the app needs it): {local_ip()}", 9, MUTED).pack(anchor="w", padx=16, pady=(0, 2))
    note = label(st, "", 9, AMBER, wraplength=360, justify="left")
    note.pack(anchor="w", padx=16, pady=(0, 12))

    # options
    o = card()
    var = tk.BooleanVar(value=startup_enabled())

    def toggle():
        try:
            set_startup(var.get())
        except Exception as e:
            var.set(not var.get())
            messagebox.showerror("HubTools PC", f"Could not change this setting:\n{e}")

    tk.Checkbutton(o, text="Start HubTools PC when Windows starts", variable=var, command=toggle,
                   bg=CARD, fg=TEXT, selectcolor=BG, activebackground=CARD, activeforeground=TEXT,
                   font=("Segoe UI", 10), bd=0, highlightthickness=0,
                   state="normal" if os.name == "nt" else "disabled").pack(anchor="w", padx=14, pady=12)

    # tray icon
    tray = None
    try:
        import pystray
        from PIL import Image
        img = Image.open(ico).convert("RGBA") if ico.exists() else Image.new("RGBA", (64, 64), (16, 24, 38, 255))

        def show(icon=None, item=None):
            root.after(0, lambda: (root.deiconify(), root.lift()))

        def quit_from_tray(icon=None, item=None):
            root.after(0, do_quit)

        tray = pystray.Icon("HubTools", img, "HubTools PC", pystray.Menu(
            pystray.MenuItem("Open HubTools PC", show, default=True),
            pystray.MenuItem("Quit", quit_from_tray),
        ))
        tray.run_detached()
    except Exception:
        tray = None

    def do_quit():
        try:
            if tray:
                tray.stop()
            threading.Thread(target=httpd.shutdown, daemon=True).start()
        finally:
            root.destroy()

    foot = tk.Frame(root, bg=BG)
    foot.pack(fill="x", padx=24, pady=(0, 20))
    tk.Button(foot, text="Quit HubTools", command=do_quit, bg=CARD, fg=TEXT, activebackground=LINE,
              activeforeground=TEXT, relief="flat", bd=0, padx=16, pady=7, font=("Segoe UI", 10),
              cursor="hand2").pack(side="right")
    if tray:
        label(foot, "Closing this window keeps HubTools running\nnear the clock. Use Quit to stop it.",
              9, MUTED, justify="left").pack(side="left")
        root.protocol("WM_DELETE_WINDOW", root.withdraw)
    else:
        root.protocol("WM_DELETE_WINDOW", do_quit)

    def refresh():
        if state["error"]:
            dot.config(fg=RED)
        elif state["ready"] and state["status"] == "Ready":
            dot.config(fg=GREEN)
        else:
            dot.config(fg=AMBER)
        status.config(text=state["status"] if state["status"] != "Ready"
                      else f"Running. {state['games']} games ready for your phone.")
        synced.config(text=f"Phone last synced: {ago(state['last_sync'])}")
        note.config(text=state["note"])
        root.after(1000, refresh)

    refresh()
    if background:
        root.withdraw() if tray else root.iconify()
    else:
        root.deiconify()


# ---------- main ----------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--background", action="store_true", help="start hidden (used when Windows starts)")
    ap.add_argument("--no-gui", action="store_true", help="run without a window (for testing)")
    ap.add_argument("--steam", help="Steam folder (auto-detected by default)")
    args = ap.parse_args()

    migrate_old_data()
    IDENT.update(identity())

    if args.no_gui:
        steam = Path(args.steam) if args.steam else sync.find_steam()
        account = sync.find_account(steam)
        httpd = ThreadingHTTPServer(("", HTTP_PORT), Handler)
        print(f"Code: {IDENT['code']}  Data folder: {DATA}")
        threading.Thread(target=discovery, daemon=True).start()
        threading.Thread(target=builder, args=(steam, account), daemon=True).start()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
        return

    import tkinter as tk
    from tkinter import filedialog, messagebox

    if os.name == "nt":
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # sharp text on high-DPI screens
        except Exception:
            pass
    root = tk.Tk()
    root.withdraw()

    def stop(msg):
        messagebox.showerror("HubTools PC", msg)
        root.destroy()
        sys.exit(0)

    try:
        httpd = ThreadingHTTPServer(("", HTTP_PORT), Handler)
    except OSError:
        stop("HubTools PC is already running.\nLook for its icon near the clock (bottom right of the screen).")

    steam = None
    saved = load_settings().get("steam")
    if args.steam:
        steam = Path(args.steam)
    elif saved and (Path(saved) / "userdata").is_dir():
        steam = Path(saved)
    else:
        try:
            steam = sync.find_steam()
        except SystemExit:
            messagebox.showinfo("HubTools PC", "HubTools could not find Steam by itself.\n"
                                "Please select your Steam folder (the one that contains steam.exe).")
            while steam is None:
                d = filedialog.askdirectory(title="Select your Steam folder")
                if not d:
                    stop("HubTools PC needs your Steam folder to work.")
                if (Path(d) / "userdata").is_dir():
                    steam = Path(d)
                    save_settings({**load_settings(), "steam": d})
                else:
                    messagebox.showerror("HubTools PC", "That folder does not look like Steam (no 'userdata' inside).")
    try:
        account = sync.find_account(steam)
    except SystemExit as e:
        stop(str(e))

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    threading.Thread(target=discovery, daemon=True).start()
    threading.Thread(target=builder, args=(steam, account), daemon=True).start()
    if not args.background:
        threading.Thread(target=ensure_firewall, daemon=True).start()

    build_window(root, httpd, args.background)
    root.mainloop()


if __name__ == "__main__":
    main()
