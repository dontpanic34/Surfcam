"""Prototype v3 : comme v1, mais le mouvement de la caméra est connu par le gyroscope (cap en degrés en fonction du temps
du film) : les points sont suivis dans un repère fixe. Usage : film.mp4 gyro.npy debut_s fin_s signe px_par_degre_960"""
import sys, json, cv2, numpy as np
path, gpath, a, b, sign, k = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), float(sys.argv[6])
g = np.load(gpath); gt, gc = g[:, 0], g[:, 1]
W, H = 960, 540
cap = cv2.VideoCapture(path); fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
cap.set(cv2.CAP_PROP_POS_MSEC, a * 1000)
step = max(1, int(round(fps / 5.0))); idx = 0
tracks = []; active = []; nid = 0
while True:
    ok = cap.grab()
    if not ok: break
    t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
    if t > b: break
    idx += 1
    if idx % step: continue
    ok, fr = cap.retrieve()
    if not ok: break
    fr = cv2.resize(fr, (W, H), interpolation=cv2.INTER_AREA)
    head = float(np.interp(t, gt, gc))
    shift = 0.0  # v4 : pas de compensation (le gyroscope du rapport est trop grossier), lien large à la place
    gray = cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    hsv = cv2.cvtColor(fr, cv2.COLOR_BGR2HSV)
    white = ((hsv[..., 2] > 175) & (hsv[..., 1] < 70)).astype(np.uint8)
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    near = cv2.dilate(white, np.ones((25, 25), np.uint8))
    local = cv2.blur(gray, (41, 41))
    dark = ((gray < local - 30) & (hsv[..., 2] < 110)).astype(np.uint8) & near
    n, lab, st, cen = cv2.connectedComponentsWithStats(dark, 8)
    blobs = []
    for i in range(1, n):
        x, y, w, h, ar = st[i]
        if 8 <= ar <= 900 and 5 <= h <= 60 and w <= 1.6 * h + 2 and y > 0.15 * H and hsv[y:y+h, x:x+w, 2].min() < 70:
            cx, cy = cen[i]
            adj = white[max(0, y - 6):y + h + 6, max(0, x - 6):x + w + 6].any()
            blobs.append((cx + shift, cy, h, adj, cx, cy))
    used = set(); still = []
    for tr in active:
        q = tr["pts"][-1]
        best = None; bd = 1e9
        for j, bb in enumerate(blobs):
            if j in used: continue
            d = np.hypot(bb[0] - q[1], bb[1] - q[2])
            if d < 60 * max(1.0, (t - q[0]) / 0.2) and d < bd: best, bd = j, d
        if best is not None:
            bb = blobs[best]; used.add(best); tr["pts"].append((t,) + bb); still.append(tr)
        elif t - q[0] < 0.6: still.append(tr)
        else: tracks.append(tr)
    for j, bb in enumerate(blobs):
        if j not in used:
            nid += 1; still.append({"pts": [(t,) + bb]})
    active = still
tracks += active
for tr in tracks:
    p = tr["pts"]
    if len(p) < 5: continue
    dur = p[-1][0] - p[0][0]
    if dur < 0.9: continue
    hmed = float(np.median([q[3] for q in p]))
    dx = p[-1][1] - p[0][1]; dy = p[-1][2] - p[0][2]
    adj = float(np.mean([q[4] for q in p]))
    print(json.dumps({"t": round(p[0][0], 1), "dur": round(dur, 1), "bodies": round(abs(dx) / max(hmed, 1), 1), "lat": round(abs(dx) / max(abs(dy), 1), 1),
                      "h": round(hmed, 1), "adj": round(adj, 2), "x": round(p[0][5] / W, 2), "y": round(p[0][6] / H, 2)}))
