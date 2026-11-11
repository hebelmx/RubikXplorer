import sys, math, re
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); mode=sys.argv[2]
P="com.exxerpro.cubentia"
def q(sql): return list(tp.query(sql))
thr=lambda where, main: q(f"select s.name name, s.ts ts, s.dur dur from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name like '{P}%' and s.{where} {'and t.tid=p.pid' if main else ''} order by s.ts")
frames=[(r.ts, r.ts+r.dur) for r in thr("name='Cube3D.drawFrame'", False) if r.dur>=0]
off=q("select ts, clock_value from clock_snapshot where clock_name='MONOTONIC' order by ts desc limit 1")[0]; off=off.ts-off.clock_value
inputs=[]
for r in thr("name like 'deliverInputEvent%'", True):
    m=re.search(r'eventTimeNano=(\d+)', r.name); t=r.ts
    if m:
        mt=int(m.group(1))+off
        if mt<=r.ts and r.ts-mt<=1e9: t=mt
    inputs.append((r.ts,t))
def nr(s,p): return s[max(0,min(len(s)-1, math.ceil(p/100*len(s))-1))]
wins=[];lat=[]
if mode=='turns':
    turns=[(r.ts,r.ts+r.dur) for r in q(f"select s.ts ts, s.dur dur from slice s join process_track pt on s.track_id=pt.id join process p using(upid) where s.name='Cube3D.turn' and p.name like '{P}%' order by s.ts")]
    vis=[r.ts for r in thr("name='Cube3D.turnVisible'", True)]
    print("turns",len(turns),"turnVisible",len(vis),"frames",len(frames),"inputs",len(inputs))
    prev=-1
    for i,(a,b) in enumerate(turns):
        up=[x for x in inputs if prev<x[0]<a][-1]; prev=a
        mk=[v for v in vis if a<=v<=b][0]; ack=[f for f in frames if f[0]>mk][0]
        lat.append((ack[1]-up[1])/1e6); w=[f[0] for f in frames if a<=f[0]<=b]; wins.append(w)
        print(f" turn{i}: start={a} end={b} dur={(b-a)/1e6:.2f}ms upInput={up[1]} (slice {up[0]}) turnVisible={mk} ackFrame=[{ack[0]},{ack[1]}] latency={lat[-1]:.3f}ms frames={len(w)} fps={(len(w)-1)/((w[-1]-w[0])/1e9):.2f}")
iv=[(b-a)/1e6 for w in wins for a,b in zip(w,w[1:])]; s=sorted(iv)
print("glFrameCount",sum(len(w) for w in wins),"glFps %.4f"%(sum(len(w)-1 for w in wins)/(sum(w[-1]-w[0] for w in wins)/1e9)),"worst %.4f"%min((len(w)-1)/((w[-1]-w[0])/1e9) for w in wins))
print("intervals p50 %.6f p90 %.6f p99 %.6f max %.6f over33 %d"%(nr(s,50),nr(s,90),nr(s,99),s[-1],sum(x>33.3 for x in s)))
ls=sorted(lat); print("inputToFrame p50 %.6f max %.6f"%(nr(ls,50),ls[-1]))
