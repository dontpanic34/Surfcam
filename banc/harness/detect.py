import sys, subprocess, numpy as np, onnxruntime as ort, time, struct
model='/home/user/surfcam/app/src/main/assets/yolov8n_736x1280.onnx'
video, out, fps, t0, t1, threads = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), int(sys.argv[6])
so=ort.SessionOptions(); so.intra_op_num_threads=threads
sess=ort.InferenceSession(model, so, providers=['CPUExecutionProvider'])
name=sess.get_inputs()[0].name
W,H=1280,720; IW,IH=1280,736; offY=8
cmd=['ffmpeg','-v','error','-ss',str(t0),'-t',str(t1-t0),'-i',video,'-vf',f'fps={fps},scale={W}:{H}:flags=area','-f','rawvideo','-pix_fmt','rgb24','-']
p=subprocess.Popen(cmd,stdout=subprocess.PIPE,bufsize=W*H*3*4)
pad=np.full((1,3,IH,IW),114/255,dtype=np.float32)
def iou(a,b):
    x1=max(a[0],b[0]);y1=max(a[1],b[1]);x2=min(a[2],b[2]);y2=min(a[3],b[3])
    i=max(0,x2-x1)*max(0,y2-y1); u=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-i
    return i/u if u>0 else 0
n=0; start=time.time()
with open(out,'w') as f:
    while True:
        buf=p.stdout.read(W*H*3)
        if len(buf)<W*H*3: break
        img=np.frombuffer(buf,np.uint8).reshape(H,W,3)
        x=pad.copy()
        x[0,:,offY:offY+H,:]=img.transpose(2,0,1).astype(np.float32)/255
        o=sess.run(None,{name:x})[0][0]   # [84,19320]
        score=o[4]
        idx=np.where(score>=0.25)[0]
        cands=[]
        for a in idx:
            cx,cy,w,h=o[0,a],o[1,a],o[2,a],o[3,a]
            b=[min(max(cx-w/2,0),W),min(max(cy-h/2-offY,0),H),min(max(cx+w/2,0),W),min(max(cy+h/2-offY,0),H)]
            cands.append((float(score[a]),b))
        cands.sort(key=lambda c:-c[0]); kept=[]
        for s,b in cands:
            if all(iou(k[1],b)<=0.45 for k in kept): kept.append((s,b))
        t=t0+n/fps
        for s,b in kept: f.write(f"{t:.3f},{s:.3f},{b[0]:.1f},{b[1]:.1f},{b[2]:.1f},{b[3]:.1f}\n")
        f.write(f"{t:.3f},-1,0,0,0,0\n")  # frame marker
        n+=1
        if n%100==0: print(n,time.time()-start,flush=True)
print('done',n,time.time()-start)
