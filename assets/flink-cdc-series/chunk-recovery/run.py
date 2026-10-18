import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--port',required=True);p.add_argument('--mode',choices=['move-pre','move-post','control-pre','control-post'],required=True);p.add_argument('--output',required=True);p.add_argument('--readback',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parent;out=Path(a.output).resolve()
if not a.readback:out.mkdir(parents=True,exist_ok=False)
cmd=['/home/author/Library/Java/JavaVirtualMachines/temurin-11.0.29/Contents/Home/bin/java','-Xmx2g','-Dlog4j.configurationFile='+str(root/'log4j2.properties'),'-cp',str(root/'classes')+':'+(root/'classpath.txt').read_text().strip(),'article2recovery.ChunkRecoveryJob',str(out),a.port,a.mode]
if a.readback:cmd.append('readback')
prefix='readback' if a.readback else 'execution'
(out/(prefix+'-command.json')).write_text(json.dumps(cmd,indent=2))
with (out/(prefix+'.log')).open('w') as log:
 r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=300)
(out/(prefix+'-process-result.json')).write_text(json.dumps({'exit_code':r.returncode}))
print(prefix,a.mode,'exit',r.returncode,'output',out)
raise SystemExit(r.returncode)
