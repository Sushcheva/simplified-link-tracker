#!/usr/bin/env python3
"""Disposable Docker/PostgreSQL 16.4 load, scale and process lifecycle checks.
All containers/networks created here have a unique prefix. No working DB is used.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
import uuid
from http_client import Client

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', required=True, help='Already built image; never rebuild during a run')
parser.add_argument('--output', default='.local/verification')
parser.add_argument('--duration', type=int, default=20, help='Seconds measured per load scenario')
parser.add_argument('--clients', default='4,16', help='Closed-loop concurrent users, comma separated')
parser.add_argument('--postgres-image', default='postgres:16.4')
args = parser.parse_args()
levels = [int(value) for value in args.clients.split(',')]
assert 1 <= min(levels) <= max(levels) <= 64 and 5 <= args.duration <= 120
output = Path(args.output).resolve(); output.mkdir(parents=True,exist_ok=True)
prefix = 'lt-check-' + uuid.uuid4().hex[:10]
network = prefix + '-net'
containers = []
report = {'started_at':datetime.now(timezone.utc).isoformat(),'success':False,'application_image':args.image,
          'host':platform.platform(),'postgres_image':args.postgres_image,
          'limits':{'web_cpus':1,'web_memory_mib':768,'java_heap_mib':256,'db_pool_per_process':5},
          'load_model':'closed-loop; no think time; persistent HTTP/1.1; 70% list, 20% history, 10% update; one account/link per user; client round-robin without a proxy; external API fixture',
          'load':[],'lifecycle':{},'startup_samples_seconds':[]}
clients = []
fixture = None

def command(*argv, check=True):
    p=subprocess.run(argv,capture_output=True,text=True,timeout=180)
    if check and p.returncode: raise RuntimeError('Command failed: '+ ' '.join(argv[:4])+'\n'+p.stderr[-2000:])
    return p.stdout.strip()

def docker(*argv, **kwargs): return command('docker',*argv,**kwargs)

def create(name,image,*flags,command_args=()):
    full=prefix+'-'+name
    docker('create','--name',full,'--network',network,*flags,image,*command_args)
    containers.append(full)
    return full

def port(name,container_port):
    return docker('port',name,str(container_port)+'/tcp').rsplit(':',1)[1]

def wait_until(predicate, timeout=120, message='Condition timed out'):
    start=time.perf_counter()
    while time.perf_counter()-start < timeout:
        try:
            if predicate(): return time.perf_counter()-start
        except (OSError,RuntimeError,AssertionError,ValueError): pass
        time.sleep(.1)
    raise AssertionError(message)

def ready(origin):
    c=Client([origin])
    try: return c.request('GET','/actuator/health/readiness')['status']=='UP'
    finally: c.close()

def start_web(name):
    start=time.perf_counter(); docker('start',name)
    origin='http://127.0.0.1:'+port(name,8080)
    wait_until(lambda:ready(origin),message='Web did not become ready')
    report['startup_samples_seconds'].append(round(time.perf_counter()-start,3))
    return origin

def stop(name, signal='SIGTERM'):
    start=time.perf_counter();docker('kill','--signal',signal,name)
    exit_code=docker('wait',name)
    elapsed=round(time.perf_counter()-start,3)
    if signal=='SIGTERM': assert elapsed < 30, 'Shutdown exceeded 30 seconds'
    return {'seconds':elapsed,'exit_code':exit_code,'signal':signal}

def control(**values): fixture.request('POST','/control',values,csrf=False)
def status(): return fixture.request('GET','/status')

def app(name, worker=False):
    env={'DATABASE_URL':'jdbc:postgresql://'+prefix+'-db:5432/linktracker','DATABASE_USER':'tracker',
         'DATABASE_PASSWORD':'isolated-runtime-test','MIGRATIONS_ENABLED':'false','SESSION_COOKIE_SECURE':'false',
         'GITHUB_API_URL':'http://'+prefix+'-fixture:8090','STACKOVERFLOW_API_URL':'http://'+prefix+'-fixture:8090',
         'JAVA_TOOL_OPTIONS':'-Xms64m -Xmx256m','DB_POOL_SIZE':'5','SCHEDULER_ENABLED':'true' if worker else 'false',
         'SCHEDULER_INTERVAL':'1s' if worker else '60s','SPRING_MAIN_WEB_APPLICATION_TYPE':'none' if worker else 'servlet',
         'APP_MODE':'worker' if worker else 'web'}
    flags=['--cpus','1','--memory','768m']
    if not worker: flags+=['-p','127.0.0.1::8080']
    for key,value in env.items(): flags+=['-e',key+'='+value]
    return create(name,args.image,*flags)

def percentile(samples,q): return round(sorted(samples)[max(0,math.ceil(len(samples)*q)-1)]*1000,2)

def workload(users, origins, duration):
    deadline=time.perf_counter()+duration
    started=time.perf_counter()
    def loop(user):
        c,link=user
        c.origins=origins
        latencies=[]; errors=Counter(); served=Counter(); count=0
        while time.perf_counter()<deadline:
            begin=time.perf_counter(); server=count%len(origins)
            try:
                action=count%10
                if action<7:
                    data=c.request('GET','/api/links',server=server)
                    assert data['total']==1 and data['links'][0]['id']==link['id'], 'Wrong collection'
                elif action<9: c.request('GET','/api/updates',server=server)
                else:
                    c.request('PUT','/api/links/'+str(link['id']),
                              {'url':link['url'],'title':'Load '+str(count),'tags':['load'],'enabled':False},server=server)
                served[str(server+1)]+=1
            except Exception as error: errors[str(error)[:200]]+=1
            latencies.append(time.perf_counter()-begin);count+=1
        return latencies,errors,served
    with ThreadPoolExecutor(max_workers=len(users)) as pool: results=list(pool.map(loop,users))
    elapsed=time.perf_counter()-started
    times=[];errors=Counter();served=Counter()
    for samples,failures,counts in results:times+=samples;errors.update(failures);served.update(counts)
    return {'replicas':len(origins),'clients':len(users),'elapsed_seconds':round(elapsed,3),'requests':len(times),
            'rps':round(len(times)/elapsed,2),'p50_ms':percentile(times,.5),'p95_ms':percentile(times,.95),
            'p99_ms':percentile(times,.99),'errors':sum(errors.values()),'error_details':dict(errors),
            'successful_requests_per_replica':dict(served)}

try:
    docker('network','create',network)
    report['docker']=json.loads(docker('info','--format','{{json .}}'))
    report['docker']={key:report['docker'].get(key) for key in ['ServerVersion','NCPU','MemTotal','Architecture','OperatingSystem']}
    report['application_image_id']=docker('image','inspect',args.image,'--format','{{.Id}}')
    db=create('db',args.postgres_image,'--memory','512m','-e','POSTGRES_DB=linktracker','-e','POSTGRES_USER=tracker','-e','POSTGRES_PASSWORD=isolated-runtime-test')
    docker('start',db)
    wait_until(lambda:command('docker','exec',db,'pg_isready','-U','tracker','-d','linktracker',check=False).startswith('/var/run/postgresql:5432 - accepting'))
    report['postgres_version']=docker('exec',db,'psql','-U','tracker','-d','linktracker','-Atc','SHOW server_version')
    assert report['postgres_version'].startswith('16.4'),report['postgres_version']
    fixture_container=create('fixture','python:3.12-alpine','--memory','128m','-p','127.0.0.1::8090',command_args=('python','/fixture.py'))
    docker('cp',str(ROOT/'scripts/benchmark_fixture.py'),fixture_container+':/fixture.py')
    docker('start',fixture_container)
    fixture=Client(['http://127.0.0.1:'+port(fixture_container,8090)])
    wait_until(lambda:status()['version']==1)
    migration=create('migrate',args.image,'--memory','768m','--cpus','1',
        '-e','DATABASE_URL=jdbc:postgresql://'+db+':5432/linktracker','-e','DATABASE_USER=tracker',
        '-e','DATABASE_PASSWORD=isolated-runtime-test','-e','JAVA_TOOL_OPTIONS=-Xmx256m',
        '-e','APP_MODE=migrate','-e','MIGRATIONS_ENABLED=true','-e','SCHEDULER_ENABLED=false',
        '-e','SPRING_MAIN_WEB_APPLICATION_TYPE=none')
    docker('start',migration);assert docker('wait',migration)=='0','Migration failed'
    web1,web2=app('web1'),app('web2')
    origin1=start_web(web1);origin2=start_web(web2)
    print('Ready: two web containers and PostgreSQL '+report['postgres_version'],flush=True)
    # Keep existing auth/CRUD/monitoring smoke checks part of this exact image's validation.
    import os
    subprocess.run([sys.executable,str(ROOT/'scripts/check_api.py'),origin1],check=True,
                   env={**os.environ,'SECOND_ORIGIN':origin2,'FIXTURE_URL':fixture.origins[0]})
    control(version=1,delay=0)
    users=[]
    for index in range(max(levels)):
        c=Client([origin1,origin2]);clients.append(c)
        c.register(f'load-{index}@example.test','runtime-verification-2026')
        link=c.request('POST','/api/links',{'url':f'https://github.com/load/item-{index}', 'title':'Load','tags':['load'],'enabled':False},201)
        users.append((c,link))
    # Warm both JVMs. Warm-up measurements are explicitly discarded.
    workload(users[:min(4,len(users))],[origin1,origin2],5)
    for count in levels:
        for origins in ([origin1],[origin1,origin2]):
            result=workload(users[:count],origins,args.duration)
            report['load'].append(result);print(json.dumps(result),flush=True)
            assert result['errors']==0,'Load scenario returned errors'
    report['lifecycle']['idle_sigterm']=stop(web2)
    origin2=start_web(web2)
    for c,_ in users: c.origins=[origin1,origin2];c.close()
    owner,link=users[0];link_id=link['id']
    assert owner.request('GET','/api/auth/me',server=1)['email']=='load-0@example.test'
    report['lifecycle']['session_survived_restart']=True
    owner.request('POST',f'/api/links/{link_id}/check',server=0)
    control(version=2,delay=3)
    with ThreadPoolExecutor(max_workers=1) as pool:
        active=pool.submit(owner.request,'POST',f'/api/links/{link_id}/check',None,200,0)
        wait_until(lambda:status()['active']>0,message='No in-flight check')
        report['lifecycle']['inflight_sigterm']=stop(web1)
        checked=active.result(timeout=15)
        assert '2026-01-02' in checked['lastSeenAt']
    report['lifecycle']['inflight_request_completed']=True
    origin1=start_web(web1);owner.close();owner.origins=[origin1,origin2]
    assert len(owner.request('GET',f'/api/updates?linkId={link_id}'))==1
    control(version=4,delay=3)
    with ThreadPoolExecutor(max_workers=1) as pool:
        active=pool.submit(owner.request,'POST',f'/api/links/{link_id}/check',None,200,0)
        wait_until(lambda:status()['active']>0)
        report['lifecycle']['inflight_sigkill']=stop(web1,'SIGKILL')
        try: active.result(timeout=15)
        except (OSError,http.client.HTTPException): pass
        else: raise AssertionError('Killed request unexpectedly completed')
    # A different process still sees the previous committed state and no partial event.
    owner.close()
    assert '2026-01-02' in owner.request('GET',f'/api/links/{link_id}',server=1)['lastSeenAt']
    assert len(owner.request('GET',f'/api/updates?linkId={link_id}',server=1))==1
    wait_until(lambda:status()['active']==0)
    control(delay=0)
    begin=time.perf_counter()
    owner.request('POST',f'/api/links/{link_id}/check',server=1)
    report['lifecycle']['recovery_on_other_replica_seconds']=round(time.perf_counter()-begin,3)
    owner.request('POST',f'/api/links/{link_id}/check',server=1)
    assert len(owner.request('GET',f'/api/updates?linkId={link_id}',server=1))==2
    report['lifecycle']['crash_rollback_retry_no_duplicates']=True
    # Two independent workers compete for the same due rows. Stop one web to keep memory bounded.
    owner.origins=[origin2];owner.close()
    worker_links=[]
    for index in range(8):
        row=owner.request('POST','/api/links',{'url':f'https://github.com/workers/item-{index}','title':'Worker','tags':[],'enabled':True},201)
        worker_links.append(row['id'])
    control(version=1,delay=.3,reset_counts=True)
    worker1,worker2=app('worker1',True),app('worker2',True)
    docker('start',worker1,worker2)
    def baselines_ready():
        return all(owner.request('GET',f'/api/links/{i}')['lastSeenAt'] for i in worker_links)
    wait_until(baselines_ready,message='Workers did not establish baselines')
    control(version=2,delay=.3,reset_counts=True)
    def all_events():
        return all(len(owner.request('GET',f'/api/updates?linkId={i}'))==1 for i in worker_links)
    wait_until(all_events,message='Workers did not record updates')
    # Force a known in-flight worker task, then kill both workers to inspect rollback.
    report['workers']={'replicas':2,'links':len(worker_links),'one_event_per_link':True,'max_external_requests_in_parallel':status()['max_active']}
    assert report['workers']['max_external_requests_in_parallel']>=2,'Did not observe worker concurrency'
    control(version=4,delay=3)
    wait_until(lambda:status()['active']>=2,message='Both workers did not start a check')
    report['lifecycle']['worker_sigterm']=stop(worker1)
    stop(worker2)
    report['workers']['both_processes_checked_links']=all('Link checked:' in docker('logs',w) for w in (worker1,worker2))
    assert report['workers']['both_processes_checked_links']
    report['success']=True
except Exception as error:
    report['failure']=str(error)
    raise
finally:
    report['finished_at']=datetime.now(timezone.utc).isoformat()
    (output/'runtime.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    for c in clients: c.close()
    if fixture: fixture.close()
    for name in containers:
        # Logs contain only generated test accounts/resources and dummy DB credentials.
        log=subprocess.run(['docker','logs',name],capture_output=True,text=True)
        (output/(name.removeprefix(prefix+'-')+'.log')).write_text(log.stdout+log.stderr)
        docker('rm','--force',name,check=False)
    docker('network','rm',network,check=False)
    print('Results:',output/'runtime.json',flush=True)
