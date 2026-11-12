import json, glob, sys
rides=json.load(open('rides.json')); OFF=-1.7
real=[r for r in rides if r['label'] in ('recupere','garde')]; fake=[r for r in rides if r['label']=='faux']; unk=[r for r in rides if r['label']=='inconnu']
def inside(t, r): return r['start']+OFF-4 <= t <= r['end']+OFF+1
def run(sign, B, D, LAT, ADJ, show=False):
    ev=[json.loads(l) for f in glob.glob(f'w3_{sign}_*.jsonl') for l in open(f)]
    ev=[e for e in ev if e['bodies']>=B and e['dur']>=D and e['lat']>=LAT and e['adj']>=ADJ]
    ev.sort(key=lambda e:e['t']); cl=[]
    for e in ev:
        if cl and e['t']-cl[-1][-1]['t']<4: cl[-1].append(e)
        else: cl.append([e])
    hit=set(); fk=set(); uk=set(); other=[]
    for c in cl:
        t=c[0]['t']; m=[i for i,r in enumerate(real) if inside(t,r)]
        if m: hit.update(m); continue
        m=[i for i,r in enumerate(fake) if inside(t,r)]
        if m: fk.update(m); continue
        m=[i for i,r in enumerate(unk) if inside(t,r)]
        if m: uk.update(m); continue
        other.append(c)
    print(f"sens {sign:>2} trajet≥{B} durée≥{D} latéral≥{LAT} écume≥{ADJ} : vraies {len(hit)}/35 · fausses connues {len(fk)}/7 · inconnues {len(uk)}/11 · ailleurs {len(other)} groupes ({len(other)/54:.1f}/min)")
    if show:
        for c in other: print("  ailleurs", c[0])
        print("  vraies manquées (temps film) :", [round(real[i]['start']+OFF,1) for i in range(35) if i not in hit])
    return other
for sign in ('1','-1'):
    for B,D,LAT,ADJ in [(3,0.9,1.5,0.5),(5,1.2,2,0.5),(5,1.2,2,0.7),(8,1.5,2,0.7)]:
        run(sign,B,D,LAT,ADJ)
if len(sys.argv)>1:
    a=sys.argv[1:]; run(a[0],float(a[1]),float(a[2]),float(a[3]),float(a[4]),True)
