import json,sys
root=sys.argv[1]; ed=json.load(open(sys.argv[2]))['corrections']; changed=set()
for e in ed:
    p=f"{root}/Fixtures/corner/{e['group']}/{e['hold']}.json"
    d=json.load(open(p,encoding='utf-8'))
    t=d['labeledSamples'][e['face']]['truth']
    assert t[e['index']].upper()==e['from'], (p,e,t[e['index']])
    t[e['index']]=e['to']
    d['truthSources'][e['face']][e['index']]='entered'
    open(p,'w',encoding='utf-8').write(json.dumps(d,indent=4,ensure_ascii=False))  # originals have no trailing newline; changed.add(p)
print(len(ed),len(changed))