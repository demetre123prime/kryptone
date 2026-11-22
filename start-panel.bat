@echo off
cd /d "C:\Users\admin\Documents\Default Project\krypton\panel"
set PORT=8787
set PANEL_PASSWORD=sudo2
set PANEL_KEY=f82556b381176003d8b6c4859463ae593a53943fc6140be71941e737779896dd
set SESSION_SECRET=fe978179f7773d97f27fd05569a171935ceef9726cc2bc09cae4a05c9c513721a87c7b42af75eb023703f6d32df03670
set OWN_BTC=bc1qxq6u0lknqng4vr295p4rscfe5mjlgnjpelsrlj
set TRUST_PROXY=1
set COOKIE_SECURE=1
set DATA_DIR=C:\Users\admin\Documents\Default Project\krypton\data
set DIST_DIR=C:\Users\admin\Documents\Default Project\krypton\panel\dist
node server.js >> "C:\Users\admin\Documents\Default Project\krypton\panel-runtime.log" 2>&1
