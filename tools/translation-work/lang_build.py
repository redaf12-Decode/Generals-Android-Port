# usage: lang_build.py <lang> <chunk>: glossary by label + <lang>/work/<chunk>*.tsv ("i<TAB>text", \n escaped)
import json,sys,glob,subprocess,os
lang,n=sys.argv[1],sys.argv[2]
# GeneralsX @bugfix Android port 12/11/2026 Resolve translate_kit.py from the repository this
# script lives in, not from an absolute path in one developer's checkout.
_here=os.path.dirname(os.path.abspath(__file__))
_kit=os.path.join(_here,'..','..','scripts','language','translate_kit.py')
g={e['label']:e['text'] for e in json.load(open(f'{lang}/glossary.json'))}
d=json.load(open(f'chunks/{n}.json'))
tr={}
for p in sorted(glob.glob(f'{lang}/work/{n}*.tsv')):
    for line in open(p,encoding='utf-8'):
        line=line.rstrip('\n')
        if not line.strip(): continue
        i,t=line.split('\t',1); tr[int(i)]=t.replace('\\n','\n')
out=[];miss=[]
for i,x in enumerate(d):
    t=tr.get(i, g.get(x['label']))
    if t is None: miss.append(i); t=x['text']
    out.append({'label':x['label'],'text':t})
json.dump(out,open(f'{lang}/out/{n}.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('missing',miss)
subprocess.run(['python3',_kit,'check',f'chunks/{n}.json',f'{lang}/out/{n}.json'])
