import sys, cv2, numpy as np
path, t1, t2, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
W,H=960,540
cap=cv2.VideoCapture(path); fps=cap.get(cv2.CAP_PROP_FPS)
tiles=[]
for t in np.arange(t1,t2,(t2-t1)/6):
    cap.set(cv2.CAP_PROP_POS_MSEC, t*1000); ok,fr=cap.read()
    if not ok: break
    fr=cv2.resize(fr,(W,H),interpolation=cv2.INTER_AREA)
    gray=cv2.cvtColor(fr,cv2.COLOR_BGR2GRAY).astype(np.float32); hsv=cv2.cvtColor(fr,cv2.COLOR_BGR2HSV)
    white=((hsv[...,2]>175)&(hsv[...,1]<70)).astype(np.uint8)
    white=cv2.morphologyEx(white,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
    near=cv2.dilate(white,np.ones((25,25),np.uint8))
    local=cv2.blur(gray,(41,41))
    dark=((gray<local-30)&(hsv[...,2]<110)).astype(np.uint8)
    vis=fr.copy(); vis[white>0]=(vis[white>0]*0.5+np.array([255,120,0])*0.5).astype(np.uint8)
    n,lab,st,cen=cv2.connectedComponentsWithStats(dark,8)
    for i in range(1,n):
        x,y,w,h,a=st[i]
        if 5<=a<=900 and 3<=h<=60 and w<=3*h+6:
            col=(0,0,255) if near[int(cen[i][1]),int(cen[i][0])] else (0,255,255)
            cv2.rectangle(vis,(x-2,y-2),(x+w+2,y+h+2),col,1)
    cv2.putText(vis,f"{t:.1f}",(8,24),cv2.FONT_HERSHEY_SIMPLEX,0.8,(0,255,255),2)
    tiles.append(cv2.resize(vis,(640,360)))
rows=[np.hstack(tiles[i:i+3]) for i in range(0,len(tiles),3)]
cv2.imwrite(out,np.vstack(rows))
