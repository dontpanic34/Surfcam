import json, glob, sys
def hms(s): return "%02d:%02d:%04.1f" % (s//3600, s//60%60, s%60)
def sec(h): a=[float(x) for x in h.split(':')]; return a[0]*3600+a[1]*60+a[2]
truth=[sec(x) for x in "08:35:50 08:41:55 08:47:37 08:53:18 08:55:48 08:58:37 08:59:56 09:01:18 09:03:04 09:03:12 09:05:42".split()]
minb=float(sys.argv[1]) if len(sys.argv)>1 else 3; mind=float(sys.argv[2]) if len(sys.argv)>2 else 0.9
ev=[json.loads(l) for f in glob.glob('ws_*.jsonl') for l in open(f)]
ev=[e for e in ev if e['bodies']>=minb and e['dur']>=mind]
ev.sort(key=lambda e:e['t'])
cl=[]
for e in ev:
    if cl and e['t']-cl[-1][-1]['t']<4: cl[-1].append(e)
    else: cl.append([e])
hit=set(); fp=[]
for c in cl:
    t=c[0]['t']; m=[i for i,x in enumerate(truth) if x-5<=t<=x+5]
    if m: hit.update(m)
    else: fp.append(c)
print(f"seuils trajet≥{minb} corps, durée≥{mind}s : {len(ev)} détections, {len(cl)} groupes · vraies vagues trouvées {len(hit)}/{len(truth)} · autres groupes {len(fp)}")
print("  manquées :", [hms(truth[i]) for i in range(len(truth)) if i not in hit])
if len(sys.argv)>3:
    for c in fp: print("  autre", hms(c[0]['t']), c[0])
