# HubTools

See your Steam playtime and achievements on your Android phone.
A small program on your Windows PC reads Steam's local files and shares them with the app over your home Wi-Fi.
Nothing is uploaded anywhere, and no account is needed.

## Setup
1. **Phone:** download `HubTools-vX.apk` from [Releases](../../releases) and install it.
2. **PC:** download `HubTools-PC.exe` from the same page and run it. A window shows a 6-digit code.
   If Windows asks about the firewall, tick **Private networks** and click Allow.
   Windows may also show a "protected your PC" warning because the program is new: click More info, then Run anyway.
3. **App:** open HubTools, tap **Find my PC**, pick your PC and type the code. Done.

From then on, just open the app while your PC is on and on the same Wi-Fi. It shows fresh data every time.
When the PC is off or you are away, the app shows the last data it received.

HubTools PC can start with Windows (it asks the first time), so you never need to think about it again.

## Troubleshooting
- **No PC found:** make sure HubTools PC is running, the phone is on the same Wi-Fi (not mobile data),
  and the router does not isolate devices ("AP isolation"). You can type the PC address shown in the PC window instead.
- **Wrong code:** the code is shown in the PC window each time it runs, and stays the same.
- **Covers or names missing at first:** the first start looks up names and cover art once, which takes a few minutes.
  Pull the data again later with the refresh button.

## Check from anywhere (optional, advanced)
Instead of the home Wi-Fi, the app can read your data from a private GitHub repo. Run `python pc/sync.py` on your PC
(it uploads `stats.json` to that repo) and fill in the "Advanced" section of the app's settings with a read-only token.

## Privacy
The PC program only answers phones that know the code. Traffic stays on your home network.
