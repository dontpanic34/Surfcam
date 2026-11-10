import evaluate,os,truth,sys
det=os.path.abspath('det_full.csv'); OFF=-1.7
def rides(args):
    ev=evaluate.run([det]+args); out=[]; cur=None
    for e in ev:
        if e[0]=='TRIGGER': cur=[float(e[1]),None,False]; out.append(cur)
        elif e[0]=='START' and cur: cur[2]='started'
        elif e[0] in('STOP','DISCARD','CANCELLED') and cur and cur[1] is None:
            cur[1]=float(e[1])
            if e[0]=='STOP' and cur[2]=='started': cur[2]=True
            else: cur[2]=False
    for c in out:
        if c[1] is None: c[1]=c[0]+3
    return out
def score(args,verbose=False):
    R=rides(args); T=truth.load(); kept=[r for r in R if r[2]]
    res={'real':[0,0,0],'faux':[0,0,0]}  # kept-overlap, trigger-only overlap, none
    miss=[]
    for t in T:
        a,b=t['start']+OFF-1,t['end']+OFF+1
        k=any(r[0]<=b and r[1]>=a for r in kept); anyo=any(r[0]<=b and r[1]>=a for r in R)
        g='faux' if t['label']=='faux' else ('real' if t['label'] in('garde','recupere') else None)
        if g is None: continue
        i=0 if k else (1 if anyo else 2); res[g][i]+=1
        if g=='real' and not k: miss.append((t.get('n'),round(a+1,1),'trigger' if anyo else 'rien'))
    return res,len(R),len(kept),miss
if __name__=='__main__':
    for name,a in [('session',['mode=legacy','minride=4.796821','conf=0.4426','speed=1.5']),('v0.89',['mode=modern','minride=3','conf=0.35','speed=1.5']),('v0.90',['mode=modern','minride=2','conf=0.35','speed=1.5','jump=3.6','extra=6']),('v0.90+gap1',['mode=modern','minride=2','conf=0.35','speed=1.5','jump=3.6','extra=6','gap=1.0'])]:
        r,n,k,m=score(a); print(name,'| déclench',n,'gardés',k,'| vraies [couvertes, trigger écarté, rien]',r['real'],'| fausses',r['faux']); 
        if name=='v0.90+gap1': print(m)
