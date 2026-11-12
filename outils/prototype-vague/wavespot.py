"""Prototype : détecte un surfeur qui surfe comme « un point sombre qui file le long d'une vague (écume blanche ou face verte) ».
Usage : wavespot.py video.mp4 heure_debut_en_secondes  → une ligne JSON par départ trouvé."""
import sys, json, cv2, numpy as np
path, t0 = sys.argv[1], float(sys.argv[2])
FPS_OUT = 5.0
W, H = 960, 540
cap = cv2.VideoCapture(path)
fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
step = max(1, int(round(fps / FPS_OUT)))
prev = None; off = np.zeros(2); idx = 0
tracks = []; active = []; nid = 0
hann = None
while True:
    ok = cap.grab()
    if not ok: break
    if idx % step: idx += 1; continue
    ok, fr = cap.retrieve(); t = idx / fps; idx += 1
    if not ok: break
    fr = cv2.resize(fr, (W, H), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    if hann is None: hann = cv2.createHanningWindow((W, H), cv2.CV_32F)
    if prev is not None:
        (dx, dy), resp = cv2.phaseCorrelate(prev, gray, hann)
        if resp > 0.05: off += (dx, dy)   # déplacement de l'image (gimbal) : on suit les points dans un repère fixe
    prev = gray
    hsv = cv2.cvtColor(fr, cv2.COLOR_BGR2HSV)
    white = ((hsv[..., 2] > 175) & (hsv[..., 1] < 70)).astype(np.uint8)
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    near = cv2.dilate(white, np.ones((25, 25), np.uint8))          # autour de l'écume : face de la vague et écume
    local = cv2.blur(gray, (41, 41))
    dark = ((gray < local - 30) & (hsv[..., 2] < 110)).astype(np.uint8)
    dark &= near
    n, lab, st, cen = cv2.connectedComponentsWithStats(dark, 8)
    blobs = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if 5 <= a <= 900 and 3 <= h <= 60 and w <= 3 * h + 6:
            cx, cy = cen[i]
            adj = white[max(0, y - 6):y + h + 6, max(0, x - 6):x + w + 6].any()
            blobs.append((cx + off[0], cy + off[1], h, adj, cx, cy))
    # association simple au plus proche, dans un rayon lié à la taille
    used = set(); still = []
    for tr in active:
        lx, ly, lh = tr["pts"][-1][1], tr["pts"][-1][2], tr["pts"][-1][3]
        best = None; bd = 1e9
        for j, b in enumerate(blobs):
            if j in used: continue
            d = np.hypot(b[0] - lx, b[1] - ly)
            if d < max(30, 4 * lh) * (1 + (t - tr["pts"][-1][0]) / 0.2) / 2 and d < bd: best, bd = j, d
        if best is not None:
            b = blobs[best]; used.add(best); tr["pts"].append((t, b[0], b[1], b[2], b[3], b[4], b[5])); still.append(tr)
        elif t - tr["pts"][-1][0] < 0.6: still.append(tr)
        else: tracks.append(tr)
    for j, b in enumerate(blobs):
        if j not in used:
            nid += 1; still.append({"id": nid, "pts": [(t, b[0], b[1], b[2], b[3], b[4], b[5])]})
    active = still
tracks += active
for tr in tracks:
    p = tr["pts"]
    if len(p) < 5: continue
    dur = p[-1][0] - p[0][0]
    if dur < 0.9: continue
    hmed = float(np.median([q[3] for q in p]))
    dx = p[-1][1] - p[0][1]; dy = p[-1][2] - p[0][2]
    adj = np.mean([q[4] for q in p])
    lateral = abs(dx) >= 1.5 * abs(dy)
    bodies = abs(dx) / max(hmed, 1)
    if bodies >= 3.0 and lateral and adj >= 0.5:
        print(json.dumps({"t": round(t0 + p[0][0], 1), "dur": round(dur, 1), "bodies": round(bodies, 1), "h": round(hmed, 1),
                          "adj": round(float(adj), 2), "x": round(p[0][5] / W, 2), "y": round(p[0][6] / H, 2), "tin": round(p[0][0], 1)}))
