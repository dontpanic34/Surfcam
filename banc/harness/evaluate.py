import sys, subprocess, json, truth, collections
OFFSET=-1.7   # temps du film = temps du rapport + OFFSET
def run(args):
    out=subprocess.run(['gradle','-q','--console=plain','run','-Pmain=ReplayKt','--args='+' '.join(args)],cwd='../jvmtest',capture_output=True,text=True).stdout
    return [l.split(',') for l in out.splitlines() if l and l[0].isalpha() and l.split(',')[0] in ('TRIGGER','START','DISCARD','STOP','ENDED','CANCELLED')]
def evaluate(det,mode_args,tol=3.0,verbose=False):
    ev=run([det]+mode_args)
    starts=[float(e[1]) for e in ev if e[0]=='START']
    ends={}  # start -> outcome
    cur=None
    for e in ev:
        if e[0]=='START': cur=float(e[1])
        elif e[0] in ('DISCARD','STOP') and cur is not None:
            ends[cur]='garde' if e[0]=='STOP' else 'rejet'; cur=None
    T=truth.load()
    res=collections.Counter(); rows=[]
    for r in T:
        ts=r['start']+OFFSET
        near=[s for s in starts if abs(s-ts)<=tol]
        out=ends.get(near[0]) if near else None
        res[(r['label'],out or 'rien')]+=1
        rows.append((r['label'],r.get('n'),round(ts,1),out))
    matched={s for s in starts if any(abs(s-(r['start']+OFFSET))<=tol for r in T)}
    extra=[s for s in starts if s not in matched]
    return res,len(starts),len(extra),rows,ends,extra
if __name__=='__main__':
    import os; det=os.path.abspath(sys.argv[1]); args=sys.argv[2:]
    res,n,extra,rows,ends,ex=evaluate(det,args)
    print('déclenchements:',n,'| hors vérité connue:',extra)
    for k in sorted(res): print(k,res[k])
