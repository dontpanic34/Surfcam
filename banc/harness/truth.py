import re, json
def load(path='../rapport-session-2026-11-05.txt'):
    ev=[]
    for l in open(path):
        m=re.search(r'\(\+\s*([\d,]+) s\)',l)
        if not m: continue
        t=float(m[1].replace(',','.'))
        if 'décision auto : Start' in l: ev.append(('S',t))
        elif 'décision auto : Discard' in l: ev.append(('D',t))
        elif 'décision auto : Stop' in l: ev.append(('E',t))
    rides=[]; cur=None; n=0
    real={8,16,17,18,19,21,22,23,24,27,28,29,30,32,33,34,35,36,40}
    seen_real={1,2,3,4,5,6,7}
    seen_false={9,10,11,12,13,14,15}
    for k,t in ev:
        if k=='S': cur=t
        elif cur is not None:
            if k=='D':
                n+=1; lab='recupere' if (n in real or n in seen_real) else ('faux' if n in seen_false else 'inconnu')
                rides.append(dict(start=cur,end=t,label=lab,n=n))
            else: rides.append(dict(start=cur,end=t,label='garde'))
            cur=None
    return rides
if __name__=='__main__':
    r=load(); import collections
    print(collections.Counter(x['label'] for x in r))
