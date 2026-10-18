from pathlib import Path
import re,json,hashlib,subprocess,csv
r=Path(__file__).resolve().parent
src=Path('/work/flink-cdc-article1-06wcq8ar/flink-cdc-045e8da45df4b1017849b04e2d8bfe8f0ed7936d')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,obj):p.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
before=json.loads((r/'production-before.json').read_text());changed=[k for k,v in before.items() if sha(src/k)!=v];assert not changed
dump(r/'production-verification.json',{'cdc_commit':'045e8da45df4b1017849b04e2d8bfe8f0ed7936d','production_java_files_compared':len(before),'changed':changed,'probe_sha256':sha(r/'tests/ChunkRecoveryJob.java'),'production_classes':'Reused fixed-source compiled target/classes, loaded locations in execution logs; no production source edits or recompilation.'})
env={'cdc':'release-3.7.0 / 045e8da45df4b1017849b04e2d8bfe8f0ed7936d','flink':'1.20.3','paimon':'2.0.0 / 604e6d5e131c74a8d127333a2a3ad6d0319732bf','debezium':'1.9.8.Final plus fixed CDC in-tree overrides','java':'Temurin 11.0.29','mysql':'8.4.11 InnoDB ROW FULL GTID ON REPEATABLE-READ','container':subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.Image}}','cdc-chunk-recovery-20261018'],text=True).strip(),'parallelism':1,'bucket':1,'chunk_size':10,'schema':'fixed (id INT PRIMARY KEY,c INT NOT NULL,amount INT NOT NULL)','checkpoint_interval_ms':600000,'manual_checkpoints':True,'restart':'fixedDelayRestart(1,200ms)','backend':'filesystem Paimon in same host','fault':'one task RuntimeException in operator snapshotState after real precommit publication, before global completion; no process kill'};dump(r/'environment.json',env)
results=[]
for mode in ['move-pre','move-post','control-pre','control-post']:
 d=r/'final'/mode;log=(d/'execution.log').read_text();tl=(d/'timeline.txt').read_text()
 result=dict(l.split('=',1) for l in (d/'result.txt').read_text().splitlines());assert result['assertions']=='PASS'
 for f in ['execution-process-result.json','readback-process-result.json']:assert json.loads((d/f).read_text())['exit_code']==0
 actual=re.findall(r'Determining (low|high) watermark (\{[^\n]+?\}) for split MySqlSnapshotSplit\{[^\n]*?splitId=\'([^\']+)\'',log)
 observed=re.findall(r'POST_(LOW|HIGH) (\S+) offset=(\{[^\n]+\})',tl)
 assert len(actual)==(6 if mode.endswith('pre') else 4)
 watermarks=[]
 for k,o,s in actual:
  match=(k.upper(),s,o);assert match in observed;observed.remove(match);watermarks.append({'kind':k,'split':s,'actual_task_watermark':o,'post_hook_matches':True})
 assert not observed
 cp=int(result['restored_checkpoint']);failed=int(result['failed_checkpoint'])
 saved=re.search(r'READER_STATE checkpoint='+str(cp)+r' splits=(.*)',tl).group(1)
 restored=re.search(r'READER_ADD attempt=1 splits=(.*)',tl).group(1)
 assert saved==restored
 assert 'RESTORED_READER_STATE_MATCH checkpoint='+str(cp) in tl
 published=re.search(r'EXTERNAL_SNAPSHOT_PUBLISHED snapshot=(\d+) commitIdentifier=(\d+) globalFuturePending=true',tl);assert int(published[2])==failed
 assert failed not in json.loads(result['completed'])
 checks=[];reports=[]
 for phase in ['published-before-global-failure','after-recovery-checkpoint','after-extra-checkpoint','after-cancel','fresh-jvm']:
  source=d/('mysql-before-failure.tsv' if phase=='published-before-global-failure' else 'mysql-final.tsv');target=d/(phase+'-paimon.tsv')
  def rows(p):
   with p.open() as f:
    rd=csv.reader(f,delimiter='\t');assert next(rd)==['id','c','amount'];vs=[tuple(map(int,x)) for x in rd]
   assert len(vs)==len(set(v[0] for v in vs));return {v[0]:v for v in vs}
  a,b=rows(source),rows(target);missing=sorted(set(a)-set(b));extra=sorted(set(b)-set(a));diff=[k for k in a.keys()&b.keys() if a[k]!=b[k]]
  assert len(a)==20 and not extra and not diff and missing==([102] if mode.startswith('move') else [])
  checks.append({'phase':phase,'source_file':source.name,'source_rows':len(a),'target_rows':len(b),'missing':missing,'extra':extra,'field_difference_keys':diff,'data_equal':a==b})
  reports.append((d/(phase+'-reconciliation.txt')).read_text())
 (d/'reconciliation.txt').write_text('\n'.join(reports))
 dump(d/'state-watermark-verification.json',{'restored_checkpoint':cp,'failed_checkpoint':failed,'checkpoint_state':saved,'restored_first_split':restored,'split_text_matches':True,'external_published_snapshot':int(published[1]),'external_commit_identifier':failed,'global_checkpoint_pending_at_independent_scan':True,'watermarks':watermarks,'original_migration_relation':'H_A < E_102 < L_B == H_B verified by real raw binlog and Java BinlogOffset; original B watermarks retained separately from recovery B','independent_tsv_recheck':checks})
 snap=d/'warehouse/cdc_chunk_e2e.db/items/snapshot';dump(d/'snapshots.json',[json.loads(p.read_text()) for p in sorted(snap.glob('snapshot-*'))])
 results.append({'scenario':mode,**result,'reconciliations':checks})
dump(r/'result-summary.json',{'independent_scenarios':4,'all_characterization_assertions_pass':True,'data_equal_scenarios':2,'data_unequal_scenarios':2,'scope':'real MySQL -> single JVM Flink MiniCluster -> real filesystem Paimon; actual task restart; no physical process kill','runs':results,'not_verified':['checkpoint before A finishes','subsequent update to missing id102','full resnapshot','parallelism > 1','DDL with recovery','physical JM/TM/HA failures','input changelog in these scenarios','other storage backends'],'historical_tests_rerun':False})
print('verified',len(results),'scenarios; all production hashes unchanged; watermarks, restored states, 20 TSV comparisons match')
