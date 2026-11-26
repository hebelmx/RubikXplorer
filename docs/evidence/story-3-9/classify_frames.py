import sys, subprocess, glob, os, json
from PIL import Image, ImageChops, ImageStat
mp4, out, refdir = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
for f in glob.glob(out + '/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',mp4,'-fps_mode','passthrough',out+'/f%05d.png'], check=True)
p = subprocess.run(['ffprobe','-v','error','-select_streams','v','-show_entries','frame=pts_time','-of','csv=p=0',mp4], capture_output=True, text=True)
pts = [float(x) for x in p.stdout.split()]
def small(f): return Image.open(f).convert('L').resize((90,160))
# ignore status bar (top 6%) where the clock changes
def d(a, b): return sum(ImageStat.Stat(ImageChops.difference(a, b).crop((0,10,90,160))).mean)
refs = {k: small(f'{refdir}/{k}.png') for k in ('editor','preparing','playback')}
cls = []
for i, f in enumerate(sorted(glob.glob(out + '/f*.png'))):
    s = small(f); ds = {k: d(s, r) for k, r in refs.items()}
    cls.append((pts[i], min(ds, key=ds.get)))
start = next((t for t, c in cls if c != 'editor'), None)
end = next((t for t, c in cls if c == 'playback' and start is not None and t > start), None)
seq = []
for t, c in cls:
    if not seq or seq[-1][1] != c: seq.append((round(t,3), c))
print(json.dumps({'frames': len(cls), 'start': start, 'end': end, 'elapsed': round(end-start,3) if start is not None and end is not None else None, 'seq': seq}))
