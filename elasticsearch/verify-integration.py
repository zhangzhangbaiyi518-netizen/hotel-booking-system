"""Real ES hotel search checks. Requires local Nacos, Seata and Elasticsearch.

Run mvn verify, set JAVA_HOME to JDK 17, then:
    py -3 elasticsearch/verify-integration.py

Uses disposable MySQL/Redis, isolated ES aliases and Nacos groups. An HTTP fault
proxy simulates ES failures without stopping the shared Elasticsearch instance.
"""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from concurrent.futures import ThreadPoolExecutor
import atexit,base64,hashlib,hmac,json,os,socket,subprocess,threading,time,urllib.request,urllib.parse,urllib.error,uuid

root=Path(__file__).resolve().parent.parent
suffix=uuid.uuid4().hex[:8]
logs=root/'target/elasticsearch-integration'/suffix;logs.mkdir(parents=True,exist_ok=True)
print('Logs: '+str(logs),flush=True)
java_home=Path(os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/ms-17.0.18')))
java=str(java_home/'bin/java.exe')
if not Path(java).is_file():raise RuntimeError('Set JAVA_HOME to JDK 17')
for port in (24307,24309,24319,24317):
    with socket.socket() as probe:probe.bind(('127.0.0.1',port))
es_url=os.environ.get('ELASTICSEARCH_URIS','http://127.0.0.1:13317').split(',')[0].rstrip('/')
alias='hotel-search-test-'+suffix
group='hotel-search-test-'+suffix
def http(url,method='GET',body=None,headers=None,timeout=10):
    data=json.dumps(body).encode() if body is not None else None
    request=urllib.request.Request(url,data=data,method=method,headers=headers or {'Content-Type':'application/json'})
    try:response=urllib.request.urlopen(request,timeout=timeout)
    except urllib.error.HTTPError as error:response=error
    with response:
        raw=response.read();value=json.loads(raw) if raw else None
        return response.status,value
def es(path,method='GET',body=None):
    headers={'Content-Type':'application/json'}
    if os.environ.get('ELASTICSEARCH_USERNAME'):
        credentials=os.environ['ELASTICSEARCH_USERNAME']+':'+os.environ.get('ELASTICSEARCH_PASSWORD','')
        headers['Authorization']='Basic '+base64.b64encode(credentials.encode()).decode()
    return http(es_url+path,method,body,headers)
assert es('/_cluster/health')[0]==200,'Start Elasticsearch first'
nodes=es('/_nodes/plugins')[1]['nodes'].values()
assert all(any(plugin['name']=='analysis-ik' and plugin['version']=='8.5.3' for plugin in node['plugins']) for node in nodes), 'Build and start the IK-enabled Elasticsearch image first'
for analyzer in ('ik_max_word','ik_smart'):
    status,analysis=es('/_analyze','POST',{'analyzer':analyzer,'text':'南京市长江大桥'})
    assert status==200 and analysis['tokens'],(analyzer,status,analysis)
print('IK 8.5.3 is loaded; ik_max_word and ik_smart analysis are available.',flush=True)

mysql_container='hotel-search-db-'+suffix;redis_container='hotel-search-redis-'+suffix
subprocess.run(['docker','run','--rm','-d','--name',mysql_container,'-e','MYSQL_ALLOW_EMPTY_PASSWORD=yes','-p','127.0.0.1::3306','mysql:8.0'],check=True,capture_output=True)
atexit.register(lambda:subprocess.run(['docker','stop',mysql_container],capture_output=True))
subprocess.run(['docker','run','--rm','-d','--name',redis_container,'-p','127.0.0.1::6379','redis:7.2-alpine'],check=True,capture_output=True)
atexit.register(lambda:subprocess.run(['docker','stop',redis_container],capture_output=True))
mysql_port=subprocess.check_output(['docker','port',mysql_container,'3306/tcp'],text=True).strip().split(':')[-1]
redis_port=subprocess.check_output(['docker','port',redis_container,'6379/tcp'],text=True).strip().split(':')[-1]
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    ready=subprocess.run(['docker','exec',mysql_container,'mysql','-h127.0.0.1','-uroot','-e','SELECT 1'],capture_output=True)
    if ready.returncode==0:break
    time.sleep(1)
else:raise RuntimeError('Temporary MySQL did not become ready')
def sql(statement,db='hotel_search'):
    command=['docker','exec','-i',mysql_container,'mysql','-uroot','--batch','--skip-column-names','--default-character-set=utf8mb4']
    if db:command.append(db)
    return subprocess.check_output(command,input=statement,text=True,encoding='utf-8').strip()
sql('CREATE DATABASE hotel_search;',None)
sql((root/'catalog-service/src/main/resources/schema.sql').read_text())
sql("INSERT INTO hotel(id,name,address,phone) VALUES(1,'云栖海景酒店','上海黄浦区','02100001111'),(2,'City Hotel','北京朝阳区','01055550000'),(12,'山景酒店','杭州西湖区',NULL),(20,'100% Hotel','上海静安区','19900000000')")
for i in range(3,12):sql(f"INSERT INTO hotel(id,name,address,phone) VALUES({i},'海景商务酒店{i}','上海虹口区','150000000{i:02}')")
sql("INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(901,1,'大床房',328,2)")

fault={'mode':'normal','alias_failures':0}
class Proxy(BaseHTTPRequestHandler):
    def do_GET(self):self.forward()
    def do_POST(self):self.forward()
    def do_PUT(self):self.forward()
    def do_DELETE(self):self.forward()
    def forward(self):
        data=self.rfile.read(int(self.headers.get('Content-Length','0'))) or None
        if fault['mode']=='outage' or (fault['mode']=='alias-fail' and self.path.startswith('/_aliases')):
            if self.path.startswith('/_aliases'):fault['alias_failures']+=1
            self.send_response(503);self.send_header('Content-Type','application/json');self.end_headers()
            self.wfile.write(b'{"error":"injected ES outage","status":503}');return
        headers={key:value for key,value in self.headers.items() if key.lower() not in ('host','content-length','connection')}
        request=urllib.request.Request(es_url+self.path,data=data,method=self.command,headers=headers)
        try:response=urllib.request.urlopen(request,timeout=20)
        except urllib.error.HTTPError as error:response=error
        with response:status=response.status;body=response.read();content_type=response.headers.get('Content-Type','application/json')
        try:
            self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def log_message(self,*args):pass
proxy=ThreadingHTTPServer(('127.0.0.1',24317),Proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
secret=base64.b64encode(os.urandom(32)).decode()
def encode(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
value=encode({'alg':'HS256','typ':'JWT'})+'.'+encode({'iss':'hotel-system','sub':'1','iat':int(time.time()),'exp':int(time.time())+900})
jwt=value+'.'+base64.urlsafe_b64encode(hmac.new(base64.b64decode(secret),value.encode(),hashlib.sha256).digest()).decode().rstrip('=')
env=os.environ.copy();env.update({'JWT_SECRET':secret,'HOTEL_DB_USER':'root','HOTEL_DB_PASSWORD':'',
    'HOTEL_CATALOG_DB_URL':f'jdbc:mysql://127.0.0.1:{mysql_port}/hotel_search?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8',
    'NACOS_GROUP':group,'ELASTICSEARCH_URIS':'http://127.0.0.1:24317','HOTEL_SEARCH_ALIAS':alias,'HOTEL_SEARCH_SYNC_INTERVAL_MS':'1200',
    'REDIS_HOST':'127.0.0.1','REDIS_PORT':redis_port,'REDIS_DATABASE':'0','REDIS_PASSWORD':'','HOTEL_CACHE_PREFIX':'hotel:es-test:'+suffix+':',
    'HOTEL_SEARCH_LOCK_PREFIX':'hotel:es-test:'+suffix+':index-lock:','HOTEL_INVENTORY_LOCK_PREFIX':'hotel:es-test:'+suffix+':stock-lock:'})
processes={};handles=[]
def start(name,module,extra):
    child=dict(env);child.update(extra)
    output=open(logs/(name+'.log'),'w',encoding='utf-8');handles.append(output)
    processes[name]=subprocess.Popen([java,'-jar',module+'-0.0.1-SNAPSHOT.jar'],cwd=root/module/'target',env=child,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
def call(path,port=24307):return http(f'http://127.0.0.1:{port}'+path,headers={'Authorization':'Bearer '+jwt},timeout=10)
def search(keyword='',page=0,size=10,port=24307):
    status,result=call('/hotel/search?'+urllib.parse.urlencode({'keyword':keyword,'page':page,'size':size}),port)
    assert status==200,(status,result)
    return result
def await_condition(condition,seconds=35):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            result=condition()
            if result:return result
        except (OSError,AssertionError):pass
        for name,process in processes.items():
            if process.poll() is not None:raise RuntimeError((name,(logs/(name+'.log')).read_text(encoding='utf-8')[-3000:]))
        time.sleep(.25)
    raise AssertionError('Timed out waiting for hotel search condition')
def active_index():
    status,body=es('/_alias/'+alias)
    return next(iter(body)) if status==200 else None
try:
    start('catalog1','catalog-service',{'HOTEL_CATALOG_PORT':'24309'})
    await_condition(lambda:call('/hotel/search?size=1',24309)[0]==200,60)
    start('catalog2','catalog-service',{'HOTEL_CATALOG_PORT':'24319'})
    await_condition(lambda:call('/hotel/search?size=1',24319)[0]==200,60)
    start('gateway','gateway',{'HOTEL_GATEWAY_PORT':'24307'})
    await_condition(lambda:search(size=1)['source']=='elasticsearch',60)
    mapping=es('/'+alias+'/_mapping')[1]
    fields=next(iter(mapping.values()))['mappings']['properties']
    for field in ('name','address'):
        assert fields[field]['analyzer']=='ik_max_word' and fields[field]['search_analyzer']=='ik_smart'
    print('Two catalog instances indexed MySQL hotels; gateway searches use Elasticsearch.',flush=True)
    assert search('栖')['items'][0]['id']==1
    assert search('city')['items'][0]['id']==2
    assert search('上海 海景')['total']==10
    assert [item['id'] for item in search('1')['items']]==[1]
    assert search('#００１２')['items'][0]['id']==12
    assert search('01055550000')['items'][0]['id']==2
    assert search('#01055550000')['total']==0
    assert search('100%')['total']==1
    pages=[search(page=i,size=5) for i in range(3)]
    assert all(page['total']==13 and page['source']=='elasticsearch' for page in pages)
    assert len({item['id'] for page in pages for item in page['items']})==13
    assert call('/hotel/search?size=51')[0]==400
    assert call('/hotel/search?page=-1')[0]==400
    print('Chinese/English, multiple terms, exact IDs/phones, literal wildcards and pagination verified.',flush=True)

    original=active_index();fault['mode']='alias-fail';before=fault['alias_failures']
    sql("UPDATE hotel SET name='新云栖海景酒店' WHERE id=1; DELETE FROM hotel WHERE id=3; INSERT INTO hotel(id,name,address,phone) VALUES(30,'新开酒店','苏州',NULL),(31,'新增酒店','南京',NULL)")
    await_condition(lambda:fault['alias_failures']>before)
    assert active_index()==original
    assert es('/'+alias+'/_count')[1]['count']==13
    assert search('#1')['items'][0]['name']=='新云栖海景酒店'
    assert search('#3')['total']==0
    fault['mode']='normal'
    await_condition(lambda:active_index()!=original and es('/'+alias+'/_count')[1]['count']==14)
    assert search('苏州')['items'][0]['id']==30
    assert search('#3')['total']==0
    assert search('#1')['items'][0]['name']=='新云栖海景酒店'
    print('Atomic alias publication preserved the old index on failure; SQL updates, additions and deletion synchronized after recovery.',flush=True)

    with ThreadPoolExecutor(max_workers=6) as pool:
        responses=list(pool.map(lambda i:search('海景',port=24309 if i%2 else 24319),range(18)))
    assert all(result['source']=='elasticsearch' and result['total']==9 for result in responses)
    fault['mode']='outage'
    fallback=search('上海 海景')
    assert fallback['source']=='database' and fallback['total']==9
    assert search('100%')['total']==1
    assert call('/hotel/rooms?hotelId=1')[0]==200
    assert call('/hotel/searchroombyid?id=901')[1]['price']==328
    print('ES outage falls back to database search while room APIs stay available.',flush=True)
    fault['mode']='normal'
    await_condition(lambda:search('海景')['source']=='elasticsearch')
    unchanged=active_index();time.sleep(3);assert active_index()==unchanged
    print('ES recovered automatically; unchanged data did not create additional index generations.',flush=True)
    sql("INSERT INTO hotel(id,name,address,phone) VALUES(32,'南京市的长江大桥酒店','江苏南京',NULL)")
    await_condition(lambda:es('/'+alias+'/_count')[1]['count']==15)
    semantic=search('南京市长江大桥')
    assert semantic['source']=='elasticsearch' and any(hotel['id']==32 for hotel in semantic['items']),semantic
    print('Chinese word matching uses IK, including a hit that is not a literal substring of the hotel name.',flush=True)
    print('All Elasticsearch integration checks passed.',flush=True)
finally:
    fault['mode']='normal'
    for process in processes.values():
        if process.poll() is None:process.terminate()
    for process in processes.values():
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill()
    for handle in handles:handle.close()
    proxy.shutdown()
    status,indices=es('/_cat/indices/'+alias+'-g-*?format=json&h=index')
    if status==200:
        for item in indices:
            if item['index'].startswith(alias+'-g-'):es('/'+item['index'],'DELETE')
    print('Test processes and namespaced Elasticsearch indices cleaned up.',flush=True)
