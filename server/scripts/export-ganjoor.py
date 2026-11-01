"""Stream the entire pinned public Ganjoor export; retain only short classical love excerpts."""
import argparse,collections,gzip,hashlib,html,json,re,sqlite3,tarfile,tempfile,time,unicodedata,urllib.request
from pathlib import Path
REVISION='34f4ab3e463903f9399d7ca208b9333911389f94'
def normalized(text):
    text=unicodedata.normalize('NFKD',text).replace('ي','ی').replace('ك','ک').replace('\u200c',' ')
    return ' '.join(''.join(c for c in text if not unicodedata.combining(c)).split())
REVIEWED=json.loads((Path(__file__).resolve().parents[1]/'data/ganjoor-reviewed.json').read_text())
APPROVED={r['source_url']:normalized(r['text']) for r in REVIEWED if r['decision']=='approved'}
RULES=json.loads((Path(__file__).resolve().parents[1]/'data/ganjoor-balanced-rules.json').read_text())
def classify(text,context=''):
    t=re.sub(r'[^\w\s]',' ',normalized(text));t=' '.join(t.split())
    if re.search(RULES['reject'],t) or re.search(RULES['reject_context'],normalized(context)):return None
    signals=[key for key,value in RULES['signals'].items() if re.search(value['pattern'],t)]
    score=sum(RULES['signals'][key]['weight'] for key in signals)
    personal='address' in signals and any(s in signals for s in ['speaker','relationship','imagery'])
    emotional=any(s in signals for s in ['longing','together']) or ('joy' in signals and any(s in signals for s in ['affection','relationship']))
    return {'score':score,'signals':signals} if personal and emotional and score>=RULES['minimum_score'] else None
def excerpts(poem):
    groups=collections.defaultdict(list)
    for verse in sorted(poem.get('Verses',[]),key=lambda v:v.get('VOrder',0)):
        if verse.get('Position') in ('Right','Left'):groups[verse['CoupletIndex']].append(verse)
    pairs=[]
    for index,rows in sorted(groups.items()):
        if len(rows)==2 and [v.get('Position') for v in rows]==['Right','Left']:
            text='\n'.join(html.unescape(re.sub('<[^>]+>','',v['Text'])).strip() for v in rows)
            url='https://ganjoor.net'+poem['FullUrl']+'#bn'+str(index+1)
            decision={'score':99,'signals':['contextually-reviewed']} if APPROVED.get(url)==normalized(text) else classify(text,poem.get('FullTitle',''))
            if decision and 30<=len(text)<=320:pairs.append((index,text,decision))
    # One complete couplet per excerpt; never join unrelated lines or different poems.
    return pairs

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='server/data/imports');parser.add_argument('--archive');args=parser.parse_args()
    out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
    report={'revision':REVISION,'schema':4,'poems_scanned':0,'poets_scanned':0,'romantic_candidates':0,'selected':0,'rights_excluded':0,'duplicates':0,'classification':'multi-signal relational/emotional heuristic plus exact contextual reviews; not full human review','selection_policy':'balanced-v3','reviewed_couplets':len(REVIEWED),'excerpt_couplets':1}
    with tempfile.TemporaryDirectory() as temp:
        db=sqlite3.connect(Path(temp)/'candidates.sqlite');db.executescript('CREATE TABLE poets(slug TEXT PRIMARY KEY,name TEXT,eligible INTEGER);CREATE TABLE excerpts(slug TEXT,poem_id INTEGER,title TEXT,url TEXT,idx INTEGER,text TEXT,score INTEGER,signals TEXT);')
        url='https://codeload.github.com/ganjoor/ganjoor-data/tar.gz/'+REVISION
        response=open(args.archive,'rb') if args.archive else urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Delneveshte-catalog-import/1.0'}),timeout=180)
        archive_hash=hashlib.sha256()
        class Reader:
            def read(self,n=-1):
                chunk=response.read(n);archive_hash.update(chunk);return chunk
        manifest=None
        with response,tarfile.open(fileobj=Reader(),mode='r|gz') as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith('.json'):continue
                path=member.name.split('/',1)[-1]
                if path=='manifest.json':manifest=json.load(archive.extractfile(member));continue
                if not path.startswith('poets/'):continue
                if member.size>20_000_000:raise RuntimeError('Unexpected oversized dataset member')
                data=json.load(archive.extractfile(member));slug=path.split('/')[1]
                if path.endswith('/poet.json'):
                    death=data.get('DeathYearInLHijri',0)
                    eligible=slug in {'saadi','hafez'} or (isinstance(death,int) and 0<death<=1290)
                    db.execute('INSERT INTO poets VALUES(?,?,?)',(slug,data.get('Nickname') or data.get('Name') or slug,int(eligible)));report['poets_scanned']+=1
                elif isinstance(data,dict) and isinstance(data.get('Verses'),list):
                    report['poems_scanned']+=1
                    for index,text,decision in excerpts(data):
                        db.execute('INSERT INTO excerpts VALUES(?,?,?,?,?,?,?,?)',(slug,data['Id'],data.get('Title',''),'https://ganjoor.net'+data['FullUrl'],index,text,decision['score'],json.dumps(decision['signals'])));report['romantic_candidates']+=1
                    if report['poems_scanned']%10000==0:db.commit();print('Scanned poems:',report['poems_scanned'],flush=True)
        db.commit()
        if not manifest or manifest.get('SchemaVersion')!=4:raise RuntimeError('Missing or unsupported Ganjoor manifest')
        if report['poems_scanned']!=manifest['PoemsCount'] or report['poets_scanned']!=manifest['PoetsCount']:raise RuntimeError('Incomplete corpus: manifest counts do not match scan')
        seen=set();samples=[];author_counts=collections.Counter()
        with gzip.open(out/'ganjoor-v1.ndjson.gz','wt',encoding='utf-8') as output:
            for slug,poem_id,title,url,index,text,score,signals,author,eligible in db.execute('SELECT e.*,p.name,p.eligible FROM excerpts e LEFT JOIN poets p ON e.slug=p.slug ORDER BY poem_id,idx'):
                if not eligible:report['rights_excluded']+=1;continue
                key=normalized(text)
                if key in seen:report['duplicates']+=1;continue
                seen.add(key)
                row={'text':text,'author':author,'title':title,'source_url':url+'#bn'+str(index+1),'source':'ganjoor','language':'fa','rights':'classical-public-domain; reviewed named classical author','source_revision':REVISION,'couplet_index':index,'selection_policy':'balanced-v3','selection_score':score,'selection_signals':json.loads(signals)}
                output.write(json.dumps(row,ensure_ascii=False)+'\n');report['selected']+=1
                if author_counts[author]<2 and len(samples)<60:samples.append(row)
                author_counts[author]+=1
        report['by_author']=dict(author_counts);report['review_samples']=samples;report['archive_sha256']=archive_hash.hexdigest();report['manifest_poems']=manifest['PoemsCount'];report['manifest_poets']=manifest['PoetsCount'];report['completed_at_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
        report['output_sha256']=hashlib.sha256((out/'ganjoor-v1.ndjson.gz').read_bytes()).hexdigest()
        (out/'ganjoor-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False),flush=True)
if __name__=='__main__':main()
