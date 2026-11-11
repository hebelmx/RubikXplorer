import re,sys,statistics
# Face-turn lines ("turn n=.. fast=..") by default; View Rotation lines ("view n=..") with --view.
view='--view' in sys.argv[1:]
path=[a for a in sys.argv[1:] if a!='--view'][0]
rows=[]
for line in open(path):
    if view:
        m=re.search(r'view n=(\d+) engine=(\S+)() elapsedMs=([\d.]+) frames=(\d+) p50=([\d.]+) p90=([\d.]+) p99=([\d.]+) max=([\d.]+) over33=(\d+) readbackOk=(\S+)',line)
    else:
        m=re.search(r'turn n=(\d+) engine=(\S+) fast=(\S+) elapsedMs=([\d.]+) frames=(\d+) p50=([\d.]+) p90=([\d.]+) p99=([\d.]+) max=([\d.]+) over33=(\d+) readbackOk=(\S+)',line)
    if m: rows.append(m.groups())
print("views" if view else "turns",len(rows))
p90=[float(r[6]) for r in rows]; p50=[float(r[5]) for r in rows]; p99=[float(r[7]) for r in rows]
print("engines",set(r[1] for r in rows),"fast",sum(r[2]=='true' for r in rows))
print("frames",sum(int(r[4]) for r in rows))
print("median p50",statistics.median_low(p50),"median p90",statistics.median_low(p90),"worst p90",max(p90),"worst p99",max(p99))
print("max",max(float(r[8]) for r in rows),"over33",sum(int(r[9]) for r in rows),"readbackOk all",all(r[10]=='true' for r in rows))
print("median elapsed",statistics.median_low(float(r[3]) for r in rows))
w=max(rows,key=lambda r:float(r[6])); print("worst turn",w)
print("autorun done" in open(path).read())
