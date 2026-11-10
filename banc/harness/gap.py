import os, evaluate
det=os.path.abspath('det_full.csv')
base=['mode=modern','minride=2','conf=0.35','speed=1.5','jump=3.6','extra=6']
for g in (0,0.7,1.0,1.5,2.0):
    res,n,extra,rows,ends,ex=evaluate.evaluate(det,base+[f'gap={g}'])
    R=lambda l,o:res[(l,o)]
    real=['garde','recupere']
    print(f"gap {g}s | déclenchements {n} | vraies gardées {sum(R(l,'garde') for l in real)}, rejetées {sum(R(l,'rejet') for l in real)}, non vues {sum(R(l,'rien') for l in real)} | fausses gardées {R('faux','garde')} rejetées {R('faux','rejet')} non vues {R('faux','rien')}")
