@echo off
cd /d "C:\Users\admin\Documents\Default Project\krypton"
bin\cloudflared.exe tunnel --url http://127.0.0.1:8787 --no-autoupdate >> "C:\Users\admin\Documents\Default Project\krypton\tunnel.log" 2>&1
