import sys, os, evaluate, collections
det=os.path.abspath('det_full.csv')
configs={
 'session 05/11 (appli v0.85, appris)':['mode=legacy','minride=4.796821','conf=0.4426','speed=1.5'],
 'v0.89 actuel':['mode=modern','minride=3','conf=0.35','speed=1.5'],
 'v0.89 + portée de suivi x2 (jump 2.4, extra 4)':['mode=modern','minride=3','conf=0.35','speed=1.5','jump=2.4','extra=4'],
 'v0.89 + portée de suivi x3 (jump 3.6, extra 6)':['mode=modern','minride=3','conf=0.35','speed=1.5','jump=3.6','extra=6'],
 'v0.89 + portée x3, durée 2 s':['mode=modern','minride=2','conf=0.35','speed=1.5','jump=3.6','extra=6'],
}
for name,a in configs.items():
    res,n,extra,rows,ends,ex=evaluate.evaluate(det,a)
    R=lambda lab,o: res[(lab,o)]
    real=[ 'garde','recupere']
    rk=sum(R(l,'garde') for l in real); rr=sum(R(l,'rejet') for l in real); rn=sum(R(l,'rien') for l in real)
    fk=R('faux','garde'); fr=R('faux','rejet'); fn=R('faux','rien')
    print(f"{name}\n   déclenchements {n} (hors vérité {extra}) | VRAIES (35) : gardées {rk}, rejetées {rr}, non vues {rn} | FAUSSES (7) : gardées {fk}, rejetées {fr}, non vues {fn}")
