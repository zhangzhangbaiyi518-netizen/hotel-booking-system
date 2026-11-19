"""Seata AT regression checks against disposable MySQL databases.

Run from the project checkout after mvn verify and starting local Nacos + Seata:
    py -3 seata/verify-integration.py
Optionally exercise TC outage when no other applications use hotel-seata:
    py -3 seata/verify-integration.py --check-tc-outage

Ports 23309, 23310, 23319, 23320, 23329 and 23330 must be free. The script creates and removes
its own MySQL, Redis and RabbitMQ containers, uses isolated Nacos groups and writes logs under target.
Only Python's standard library is required. This script targets Windows/JDK 17.
"""

from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import os,subprocess,json,time,uuid,base64,hashlib,hmac,threading,urllib.request,urllib.parse,urllib.error
import argparse,atexit,socket,itertools,zipfile,re


parser=argparse.ArgumentParser(description='Real MySQL + Seata fault-injection checks. Requires local Nacos, Seata and built JARs.')
parser.add_argument('--check-tc-outage',action='store_true',help='Pause the local hotel-seata container to verify failure and recovery. Use only in an isolated demo environment.')
parser.add_argument('--check-order-mq',action='store_true',help='Check order notifications, delayed cancellation, paid-order safety and broker outage recovery.')
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
java_home=Path(os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/ms-17.0.18')))
java=str(java_home/'bin/java.exe')
if not Path(java).is_file():raise RuntimeError('Set JAVA_HOME to JDK 17 before running this script')
for module in ('catalog-service','order-service'):
    if not (root/module/'target'/(module+'-0.0.1-SNAPSHOT.jar')).is_file():
        raise RuntimeError('Run mvn verify to build the service JARs first')
for port in (23309,23310,23319,23320,23329,23330):
    with socket.socket() as probe:probe.bind(('127.0.0.1',port))
logs=root/'target/seata-integration'/uuid.uuid4().hex[:8];logs.mkdir(parents=True,exist_ok=True)
print('Integration logs: '+str(logs),flush=True)
suffix=uuid.uuid4().hex[:8];order_db='seata_order_'+suffix;stock_db='seata_stock_'+suffix

group='seata-check-'+suffix;rule_group=group+'-rules'
mysql_container='hotel-seata-test-'+suffix
subprocess.run(['docker','run','--rm','-d','--name',mysql_container,'-e','MYSQL_ALLOW_EMPTY_PASSWORD=yes','-p','127.0.0.1::3306','mysql:8.0'],check=True,capture_output=True)
atexit.register(lambda:subprocess.run(['docker','stop',mysql_container],capture_output=True))
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    result=subprocess.run(['docker','exec',mysql_container,'mysql','-h127.0.0.1','-uroot','-e','SELECT 1'],capture_output=True)
    if result.returncode==0:break
    time.sleep(1)
else:raise RuntimeError('Temporary MySQL startup timed out')

redis_container='hotel-lock-test-'+suffix
subprocess.run(['docker','run','--rm','-d','--name',redis_container,'-p','127.0.0.1::6379','redis:7.2-alpine'],check=True,capture_output=True)
atexit.register(lambda:subprocess.run(['docker','stop',redis_container],capture_output=True))
redis_port=subprocess.check_output(['docker','port',redis_container,'6379/tcp'],text=True).strip().split(':')[-1]
lock_prefix='hotel:seata:'+suffix+':inventory:'
def redis(*command):
    return subprocess.check_output(['docker','exec',redis_container,'redis-cli','--raw',*map(str,command)],text=True).strip()
deadline=time.monotonic()+20
while time.monotonic()<deadline:
    if redis('PING')=='PONG':break
    time.sleep(.2)
else:raise RuntimeError('Temporary Redis startup timed out')

mq_container='hotel-mq-test-'+suffix
subprocess.run(['docker','run','--rm','-d','--name',mq_container,'-e','RABBITMQ_DEFAULT_USER=test',
    '-e','RABBITMQ_DEFAULT_PASS=test-password','-e','RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2',
    '-p','127.0.0.1::5672','-p','127.0.0.1::15672','rabbitmq:4.2-management'],check=True,capture_output=True)
atexit.register(lambda:subprocess.run(['docker','stop',mq_container],capture_output=True))
mq_port=subprocess.check_output(['docker','port',mq_container,'5672/tcp'],text=True).strip().split(':')[-1]
mq_http_port=subprocess.check_output(['docker','port',mq_container,'15672/tcp'],text=True).strip().split(':')[-1]
mq_prefix='hotel.test.'+suffix
def mq(path,method='GET',body=None):
    req=urllib.request.Request('http://127.0.0.1:'+mq_http_port+'/api'+path,method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization':'Basic '+base64.b64encode(b'test:test-password').decode(),'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=5) as response:
        data=response.read();return json.loads(data) if data else None
deadline=time.monotonic()+60
while time.monotonic()<deadline:
    try:mq('/overview');break
    except (OSError,urllib.error.HTTPError):time.sleep(.5)
else:raise RuntimeError('Temporary RabbitMQ startup timed out')

def nacos(path,method='GET',params=None):
    params=dict(params or {})
    if 'access' in globals():params.setdefault('accessToken',access)
    namespace=os.environ.get('NACOS_NAMESPACE','')
    if path.startswith('/ns/'):params.setdefault('namespaceId',namespace)
    if path.startswith('/cs/'):params.setdefault('tenant',namespace)
    data=urllib.parse.urlencode(params).encode();url='http://'+os.environ.get('NACOS_SERVER_ADDR','127.0.0.1:8848')+'/nacos/v1'+path
    if method=='GET':url+='?'+data.decode();data=None
    with urllib.request.urlopen(urllib.request.Request(url,data=data,method=method),timeout=10) as response:return response.read().decode()
access=json.loads(nacos('/auth/login','POST',{'username':os.environ.get('NACOS_USERNAME','nacos'),'password':os.environ.get('NACOS_PASSWORD','nacos')}))['accessToken']
for p in (root/'sentinel').glob('*.json'):
    value=json.loads(p.read_text())
    if p.name.endswith('flow-rules.json'):value[0]['count']=1000
    nacos('/cs/configs','POST',{'dataId':p.name,'group':rule_group,'content':json.dumps(value),'type':'json','accessToken':access})
mysql_port=subprocess.check_output(['docker','port',mysql_container,'3306/tcp'],text=True).strip().split(':')[-1]
def sql(statement,db=None):
    args=['docker','exec','-i',mysql_container,'mysql','-uroot','--batch','--skip-column-names','--default-character-set=utf8mb4']
    if db:args.append(db)
    return subprocess.check_output(args,input=statement,text=True).strip()
sql(f'CREATE DATABASE {order_db}; CREATE DATABASE {stock_db};')
secret=base64.b64encode(os.urandom(32)).decode()
def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).decode().rstrip('=')
def jwt(user=1):
    value=enc({'alg':'HS256','typ':'JWT'})+'.'+enc({'iss':'hotel-system','sub':str(user),'iat':int(time.time()),'exp':int(time.time())+900})
    return value+'.'+base64.urlsafe_b64encode(hmac.new(base64.b64decode(secret),value.encode(),hashlib.sha256).digest()).decode().rstrip('=')
env=os.environ.copy();env.update({'HOTEL_DB_USER':'root','JWT_SECRET':secret,'HOTEL_DB_PASSWORD':'','NACOS_GROUP':group,'SENTINEL_NACOS_GROUP':rule_group,'HOTEL_ORDER_PORT':'23310','HOTEL_CATALOG_PORT':'23319','HOTEL_CACHE_PREFIX':'hotel:seata:'+suffix+':'})
env.update({'REDIS_HOST':'127.0.0.1','REDIS_PORT':redis_port,'REDIS_PASSWORD':'','REDIS_DATABASE':'0',
    'HOTEL_INVENTORY_LOCK_PREFIX':lock_prefix,'HOTEL_INVENTORY_LOCK_WAIT_MS':'250','HOTEL_INVENTORY_LOCK_WATCHDOG_MS':'3000'})
env.update({'RABBITMQ_HOST':'127.0.0.1','RABBITMQ_PORT':mq_port,'RABBITMQ_USERNAME':'test',
    'RABBITMQ_PASSWORD':'test-password','RABBITMQ_VHOST':'/','HOTEL_ORDER_MQ_PREFIX':mq_prefix})
for role,db in [('ORDER',order_db),('CATALOG',stock_db)]:
    env['HOTEL_'+role+'_DB_URL']=f'jdbc:mysql://127.0.0.1:{mysql_port}/{db}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8'


processes={};handles=[]
def start(name,module,extra=None):
    child=dict(env);child.update(extra or {})
    log=open(logs/(name+'.log'),'a',encoding='utf-8');handles.append(log)
    processes[name]=subprocess.Popen([java,'-jar',module+'-0.0.1-SNAPSHOT.jar'],cwd=root/module/'target',env=child,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
def wait(name,port):
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        if processes[name].poll() is not None:raise RuntimeError((name,(logs/(name+'.log')).read_text(encoding='utf-8')[-4000:]))
        try:urllib.request.urlopen(f'http://127.0.0.1:{port}/',timeout=1).close()
        except urllib.error.HTTPError as error:error.close();time.sleep(2);return
        except OSError:time.sleep(.3)
    raise RuntimeError('Startup timeout')
mode={'value':'normal'};xids=[];proxy_hits=[];reserve_ports=[];release_failures=[]
upstreams=itertools.cycle((23319,23329))
class Proxy(BaseHTTPRequestHandler):
    def do_GET(self):self.forward()
    def do_POST(self):self.forward()
    def forward(self):
        data=self.rfile.read(int(self.headers.get('Content-Length','0'))) or None
        headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','content-length','connection')}
        if self.path.startswith('/internal/inventory/') and self.command=='POST':
            xid=self.headers.get('TX_XID');assert xid,'Missing XID propagation'
            xids.append(xid)
        proxy_hits.append(self.path)
        upstream=next(upstreams)
        if self.path=='/internal/inventory/reserve':reserve_ports.append(upstream)
        snapshot=None
        if self.path=='/internal/inventory/import':
            def diagnose_import():
                try:
                    details=sql("SELECT waiting_pid,blocking_pid,waiting_query,blocking_query FROM sys.innodb_lock_waits; SELECT trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx")
                    with open(logs/'import-lock-waits.log','a',encoding='utf-8') as output:output.write(details+'\n')
                except Exception:pass
            snapshot=threading.Timer(2,diagnose_import);snapshot.daemon=True;snapshot.start()
        request=urllib.request.Request(f'http://127.0.0.1:{upstream}'+self.path,data=data,headers=headers,method=self.command)
        try:response=urllib.request.urlopen(request,timeout=30)
        except urllib.error.HTTPError as error:response=error
        with response:status=response.status;body=response.read();content_type=response.headers.get('Content-Type','application/json')
        if snapshot:snapshot.cancel()
        if status==200 and self.path=='/internal/inventory/reserve':
            if mode['value']=='after-reserve-error':status=500;body=b'{"message":"injected proxy failure"}'
            elif mode['value']=='after-reserve-timeout':time.sleep(5)
            elif mode['value']=='kill-order-connection':
                connections=sql("SELECT t.trx_mysql_thread_id FROM information_schema.innodb_trx t JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id WHERE p.DB='"+order_db+"'")
                assert connections,'No open order transaction to interrupt'
                for connection_id in connections.splitlines():sql('KILL CONNECTION '+connection_id)
        if status==200 and self.path.startswith('/internal/inventory/release/') and mode['value']=='after-release-error':
            release_failures.append(self.path)
            status=500;body=b'{"message":"injected release response failure"}'
        try:
            self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def log_message(self,*args):pass
proxy=ThreadingHTTPServer(('127.0.0.1',23309),Proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
nacos('/ns/instance','POST',{'serviceName':'catalog-service','groupName':group,'ip':'127.0.0.1','port':23309,'ephemeral':'true'})
stop=threading.Event()
def beat():
    while not stop.wait(2):
        item={'serviceName':group+'@@catalog-service','ip':'127.0.0.1','port':23309,'cluster':'DEFAULT','weight':1,'ephemeral':True}
        try:nacos('/ns/instance/beat','PUT',{'serviceName':'catalog-service','groupName':group,'beat':json.dumps(item)})
        except Exception:pass
threading.Thread(target=beat,daemon=True).start()
def call(path,method='GET',data=None,expected=None,port=23310,user=1):
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,method=method,data=json.dumps(data).encode() if data is not None else None,headers={'Authorization':'Bearer '+jwt(user),'Content-Type':'application/json'})
    try:response=urllib.request.urlopen(req,timeout=40)
    except urllib.error.HTTPError as error:response=error
    with response:
        payload=response.read();value=json.loads(payload) if payload else None
        if expected is not None:assert response.status==expected,(path,response.status,expected,value)
        return response.status,value
def book(room=901,key=None,user=1,port=23310):
    return call('/hotel/orders','POST',{'roomTypeId':room,'requestId':key or str(uuid.uuid4())},user=user,port=port)
def consistent(room,reserved,orders):
    end=time.monotonic()+15
    while time.monotonic()<end:
        count=sql(f'SELECT reserved_count FROM room_inventory WHERE room_type_id={room}',stock_db)
        rows=sql(f'SELECT COUNT(*) FROM stay_order WHERE room_type_id={room}',order_db)
        if count==str(reserved) and rows==str(orders):return
        time.sleep(.2)
    raise AssertionError((room,count,rows,reserved,orders))
try:
    start('catalog','catalog-service',{'SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED':'false'})
    wait('catalog',23319)
    start('catalog2','catalog-service',{'SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED':'false','HOTEL_CATALOG_PORT':'23329'})
    wait('catalog2',23329)
    sql("INSERT INTO hotel(id,name,address) VALUES(9,'Seata Hotel','Address'); INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(901,9,'Room A',100,2),(902,9,'Room B',100,2),(903,9,'Room C',100,2)",stock_db)

    sql((root/'order-service/src/main/resources/db/stay-order.sql').read_text(),order_db)
    sql("CREATE TABLE room_inventory(room_type_id BIGINT PRIMARY KEY,total_count INT NOT NULL,reserved_count INT NOT NULL); INSERT INTO room_inventory VALUES(904,7,1),(905,10,1)",order_db)
    legacy_id=str(uuid.uuid4());completed_id=str(uuid.uuid4())
    sql("INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(904,9,'Legacy',100,2),(905,9,'Expired',100,2)",stock_db)
    sql(f"INSERT INTO stay_order(id,user_id,hotel_id,room_type_id,hotel_name,room_name,amount,status,inventory_held,check_out_time) VALUES('{legacy_id}',5,9,904,'Legacy','Legacy',100,'PENDING_PAYMENT',1,NULL),('{completed_id}',5,9,905,'Legacy','Expired',100,'AWAITING_CHECK_IN',1,'2020-01-01')",order_db)
    start('order1','order-service');wait('order1',23310)
    for _ in range(30):
        status,_=call('/hotel/orders/inventory?roomTypeId=904')
        if status==200:break
        time.sleep(.5)
    assert status==200,status
    assert sql('SELECT CONCAT(total_count,":",reserved_count) FROM room_inventory WHERE room_type_id=904',stock_db)=='7:1'
    call('/hotel/orders/inventory?roomTypeId=905',expected=200)
    consistent(905,0,1)
    print('Legacy capacity and active holds migrated across databases; expired stay released.',flush=True)
    call('/hotel/orders/inventory?roomTypeId=901',expected=200)
    print('Seata TM/RM started with separate order and inventory databases.',flush=True)

    # Represent a different Redisson owner in this disposable Redis instance.
    # Failed contenders must leave its hash and lease intact.
    lock_key=lock_prefix+'901'
    redis('HSET',lock_key,'external-owner:1',1);redis('PEXPIRE',lock_key,30000)
    try:
        for _ in range(2):
            assert book(901)[0]==503
            consistent(901,0,0)
            assert redis('HGET',lock_key,'external-owner:1')=='1'
        sql("INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(906,9,'Independent',100,2)",stock_db)
        status,independent=book(906);assert status==201,(status,independent)
        call('/hotel/orders/'+independent['id']+'/cancel','POST',expected=200)
    finally:redis('DEL',lock_key)
    print('Room lock contention rejected writes without unlocking the other owner; another room remained bookable.',flush=True)

    mode['value']='after-reserve-error'
    key=str(uuid.uuid4());status,_=book(key=key)
    assert status>=500,status
    consistent(901,0,0)
    assert sql('SELECT COUNT(*) FROM room_reservation WHERE room_type_id=901',stock_db)=='0'
    assert sql('SELECT COUNT(*) FROM order_outbox WHERE order_id IN (SELECT id FROM stay_order WHERE room_type_id=901)',order_db)=='0'
    print('Inventory committed then RPC failed -> global rollback restored stock and removed order.',flush=True)
    mode['value']='after-reserve-timeout'
    status,_=book(key=key);assert status>=500,status
    consistent(901,0,0)
    print('Lost reserve response -> Seata rollback preserved inventory.',flush=True)
    mode['value']='kill-order-connection'
    status,_=book(key=key);assert status>=500,status
    consistent(901,0,0)
    print('Order SQL connection failed after stock commit -> both branches rolled back.',flush=True)

    mode['value']='normal'
    status,order=book(key=key);assert status==201,(status,order)
    consistent(901,1,1)
    assert book(key=key)[1]['id']==order['id']
    mode['value']='after-release-error'
    assert call('/hotel/orders/'+order['id']+'/cancel','POST')[0]>=500
    consistent(901,1,1)
    assert call('/hotel/orders/'+order['id'],expected=200)[1]['status']=='PENDING_PAYMENT'
    assert sql("SELECT state FROM room_reservation WHERE order_id='"+order['id']+"'",stock_db)=='HELD'
    mode['value']='normal'
    call('/hotel/orders/'+order['id']+'/cancel','POST',expected=200)
    call('/hotel/orders/'+order['id']+'/cancel','POST',expected=200)
    consistent(901,0,1)
    print('Cancellation global rollback and idempotent stock release verified.',flush=True)

    paid=book(902,user=2)[1]
    call('/hotel/orders/'+paid['id']+'/pay','POST',expected=200,user=2)
    call('/hotel/orders/'+paid['id']+'/refund-request','POST',expected=200,user=2)
    mode['value']='after-release-error'
    assert call('/hotel/orders/'+paid['id']+'/refund','POST',{'reason':'\u884c\u7a0b\u6709\u53d8'},user=2)[0]>=500
    consistent(902,1,1)
    assert call('/hotel/orders/'+paid['id'],user=2,expected=200)[1]['status']=='REFUND_REQUESTED'
    mode['value']='normal'
    call('/hotel/orders/'+paid['id']+'/refund','POST',{'reason':'\u884c\u7a0b\u6709\u53d8'},user=2,expected=200)
    consistent(902,0,1)
    print('Refund status and inventory participate in the same global transaction.',flush=True)
    assert xids and all(':13314:' in xid for xid in xids),xids[:3]
    print('XID propagation verified on inventory writes.',flush=True)

    start('order2','order-service',{'HOTEL_ORDER_PORT':'23320'});wait('order2',23320)
    assert sql('SELECT reserved_count FROM room_inventory WHERE room_type_id=904',stock_db)=='1'
    print('Second instance migration did not double-count existing stock.',flush=True)
    def attempt(index):
        request_id=str(uuid.uuid4());port=23310 if index%2 else 23320
        for _ in range(4):
            result=book(903,request_id,port=port)
            if result[0] in (201,409):return result
            time.sleep(.2)
        return result
    with ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(attempt,range(24)))
    counts={status:sum(r[0]==status for r in results) for status in set(r[0] for r in results)}
    assert counts.get(201)==10 and set(counts).issubset({201,409,503}),counts
    consistent(903,10,10)
    assert set(reserve_ports)=={23319,23329},set(reserve_ports)
    assert redis('EXISTS',lock_prefix+'903')=='0'
    print('Two order instances and two catalog instances sold exactly 10 of 24 requests; reservation locks were released.',flush=True)

    subprocess.run(['docker','pause',redis_container],check=True,capture_output=True)
    try:
        redis_outage_key=str(uuid.uuid4())
        assert book(901,redis_outage_key)[0]==503
        consistent(901,0,1)
        assert sql("SELECT COUNT(*) FROM stay_order WHERE request_id='"+redis_outage_key+"'",order_db)=='0'
        call('/hotel/searchall',port=23319,expected=200)
        print('Redis outage rejected stock deduction with no order leakage; catalog reads used the database.',flush=True)
    finally:subprocess.run(['docker','unpause',redis_container],check=True,capture_output=True)
    for _ in range(15):
        status,recovered=book(901,redis_outage_key)
        if status==201:break
        time.sleep(1)
    assert status==201,(status,recovered)
    call('/hotel/orders/'+recovered['id']+'/cancel','POST',expected=200)
    print('Redis recovered -> same request completed and released its room lock.',flush=True)

    if args.check_tc_outage:
        subprocess.run(['docker','pause','hotel-seata'],check=True,capture_output=True)
        try:
            outage_key=str(uuid.uuid4())
            status,_=book(901,outage_key)
            assert status==503,status
            consistent(901,0,2)
            assert sql("SELECT COUNT(*) FROM stay_order WHERE request_id='"+outage_key+"'",order_db)=='0'
            print('TC unavailable -> HTTP 503 with no order or inventory change.',flush=True)
        finally:subprocess.run(['docker','unpause','hotel-seata'],check=True,capture_output=True)
        for _ in range(15):
            status,recovered=book(901,outage_key)
            if status==201:break
            time.sleep(1)
        assert status==201,(status,recovered)
        call('/hotel/orders/'+recovered['id']+'/cancel','POST',expected=200)
        print('Coordinator recovered -> original request can succeed safely.',flush=True)
    if args.check_order_mq:
        # Finish the existing regression workload, then test a five-second payment window.
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            if sql("SELECT COUNT(*) FROM order_outbox WHERE state<>'SENT'",order_db)=='0':break
            time.sleep(.5)
        else:raise AssertionError('Outbox publisher did not drain the initial workload')
        for name in ('order1','order2'):
            processes[name].terminate();processes[name].wait(timeout=10)
        start('orderMq','order-service',{'HOTEL_ORDER_PORT':'23330','HOTEL_ORDER_PAYMENT_TIMEOUT_MS':'5000'})
        wait('orderMq',23330)
        for _ in range(40):
            if call('/hotel/orders/all',port=23330)[0]==200:break
            time.sleep(.25)
        # Also prove stock from the concurrent workload remains releasable after
        # restart, including requests that retried a conflicting AT branch.
        for status,created in results:
            if status!=201:continue
            for _ in range(12):
                cancel_status,_=call('/hotel/orders/'+created['id']+'/cancel','POST',port=23330)
                if cancel_status==200:break
                time.sleep(.3)
            assert cancel_status==200,(created['id'],cancel_status)
        consistent(903,0,10)
        print('Restart and cancellation released all ten rooms from the concurrent workload.',flush=True)
        sql("INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(907,9,'Timeout',100,2),(908,9,'Paid',100,2),(909,9,'Recovery',100,2)",stock_db)
        def mqcall(path,method='GET',data=None,expected=None):
            return call(path,method,data,expected,port=23330,user=7)
        def mqbook(room,key=None):return book(room,key,user=7,port=23330)
        def await_status(order_id,status,seconds=25):
            deadline=time.monotonic()+seconds
            while time.monotonic()<deadline:
                result=mqcall('/hotel/orders/'+order_id)
                if result[0]==200 and result[1]['status']==status:return result[1]
                time.sleep(.2)
            raise AssertionError((order_id,status,result))
        def notifications():
            return [json.loads(item['payload']) for item in mq('/queues/%2F/'+mq_prefix+'.created/get','POST',
                {'count':1000,'ackmode':'ack_requeue_true','encoding':'auto'})]
        def await_notification(order_id):
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                found=[item for item in notifications() if item['orderId']==order_id]
                if found:return found
                time.sleep(.3)
            raise AssertionError('Notification not delivered for '+order_id)
        def duplicate_timeout(order_id):
            result=mq('/exchanges/%2F/'+mq_prefix+'.events/publish','POST',{'properties':{'delivery_mode':2,'content_type':'application/json'},
                'routing_key':'timeout.cancel','payload':json.dumps({'eventId':'timeout:'+order_id,'orderId':order_id}),'payload_encoding':'string'})
            assert result['routed']

        # Use an independent parent TM to keep a real phase-one order globally
        # undecided. Pollers must not publish it, and rollback must remove its events.
        probe_root=logs/'global-probe';probe_libs=probe_root/'lib';probe_classes=probe_root/'classes'
        probe_libs.mkdir(parents=True);probe_classes.mkdir()
        with zipfile.ZipFile(root/'order-service/target/order-service-0.0.1-SNAPSHOT.jar') as jar:
            for member in jar.namelist():
                if member.startswith('BOOT-INF/lib/') and member.endswith('.jar'):
                    (probe_libs/Path(member).name).write_bytes(jar.read(member))
        subprocess.run([str(java_home/'bin/javac.exe'),'-encoding','UTF-8','-cp',str(probe_libs/'*'),
            '-d',str(probe_classes),str(root/'seata/GlobalOrderProbe.java')],check=True)
        sql("INSERT INTO room_type(id,hotel_id,name,price,capacity) VALUES(911,9,'Global rollback',100,2)",stock_db)
        probe_env=dict(env);probe_env.update({'PROBE_AUTH':'Bearer '+jwt(7),
            'PROBE_ORDER_BODY':json.dumps({'roomTypeId':911,'requestId':str(uuid.uuid4())})})
        probe_log=open(logs/'global-probe.log','w',encoding='utf-8');handles.append(probe_log)
        probe=subprocess.Popen([java,'-Dloader.main=com.hotel.testing.GlobalOrderProbe','-Dloader.path='+str(probe_classes),
            '-cp',str(root/'order-service/target/order-service-0.0.1-SNAPSHOT.jar'),'org.springframework.boot.loader.PropertiesLauncher'],
            env=probe_env,stdin=subprocess.PIPE,stdout=probe_log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        processes['globalProbe']=probe
        deadline=time.monotonic()+45;probe_order=None
        while time.monotonic()<deadline:
            content=(logs/'global-probe.log').read_text(encoding='utf-8')
            match=re.search(r'PROBE_ORDER ([a-f0-9-]+)',content)
            if match:probe_order=match.group(1);break
            if probe.poll() is not None:raise AssertionError(content[-4000:])
            time.sleep(.25)
        assert probe_order,'Parent Seata transaction did not reach phase one'
        time.sleep(2)
        assert sql("SELECT COUNT(*) FROM order_outbox WHERE order_id='"+probe_order+"' AND state<>'NEW'",order_db)=='0'
        assert not [item for item in notifications() if item['orderId']==probe_order]
        probe.stdin.write(b'r\n');probe.stdin.flush();assert probe.wait(timeout=25)==0
        deadline=time.monotonic()+10
        while time.monotonic()<deadline and sql("SELECT COUNT(*) FROM stay_order WHERE id='"+probe_order+"'",order_db)!='0':time.sleep(.2)
        assert sql("SELECT COUNT(*) FROM stay_order WHERE id='"+probe_order+"'",order_db)=='0'
        assert sql("SELECT COUNT(*) FROM order_outbox WHERE order_id='"+probe_order+"'",order_db)=='0'
        assert not [item for item in notifications() if item['orderId']==probe_order]
        print('Real AT phase-one rows stayed unpublished until global resolution; rollback removed the order and both outbox events.',flush=True)

        key=str(uuid.uuid4());status,expiring=mqbook(907,key);assert status==201,(status,expiring)
        assert mqbook(907,key)[1]['id']==expiring['id']
        payload=await_notification(expiring['id'])[0]
        assert payload['phone'].startswith('1990000') and len(payload['phone'])==11 and payload['virtualPhone']
        assert payload['stay']['hotelName']=='Seata Hotel' and payload['stay']['roomName']=='Timeout'
        assert payload['stay']['checkInTime'] is None and payload['stay']['checkInRule']
        before=len(release_failures);mode['value']='after-release-error'
        deadline=time.monotonic()+12
        while time.monotonic()<deadline and len(release_failures)==before:time.sleep(.1)
        assert len(release_failures)>before,'Delayed timeout did not reach the inventory service'
        time.sleep(.4)
        assert mqcall('/hotel/orders/'+expiring['id'])[1]['status']=='PENDING_PAYMENT'
        consistent(907,1,1)
        mode['value']='normal'
        await_status(expiring['id'],'CANCELLED');consistent(907,0,1)
        assert '(dead-lettered=true)' in (logs/'orderMq.log').read_text(encoding='utf-8')
        duplicate_timeout(expiring['id']);duplicate_timeout(expiring['id']);time.sleep(.5);consistent(907,0,1)
        print('Created notification contains virtual phone, order and stay info; TTL/DLX timeout retries safely after inventory rollback and releases once.',flush=True)

        status,paid=mqbook(908);assert status==201,(status,paid)
        mqcall('/hotel/orders/'+paid['id']+'/pay','POST',expected=200)
        time.sleep(6)
        duplicate_timeout(paid['id']);time.sleep(.5)
        assert mqcall('/hotel/orders/'+paid['id'])[1]['status']=='AWAITING_CHECK_IN'
        consistent(908,1,1)
        print('Paid orders are unaffected by delayed or duplicate timeout messages.',flush=True)

        subprocess.run(['docker','pause',mq_container],check=True,capture_output=True)
        try:
            processes['orderMq'].terminate();processes['orderMq'].wait(timeout=10)
            start('orderMqRestart','order-service',{'HOTEL_ORDER_PORT':'23330','HOTEL_ORDER_PAYMENT_TIMEOUT_MS':'5000'})
            wait('orderMqRestart',23330)
            for _ in range(40):
                if mqcall('/hotel/orders/all')[0]==200:break
                time.sleep(.25)
            recovery_key=str(uuid.uuid4());status,recovering=mqbook(909,recovery_key);assert status==201,(status,recovering)
            time.sleep(6)
            assert mqcall('/hotel/orders/'+recovering['id'])[1]['status']=='PENDING_PAYMENT'
            assert sql("SELECT COUNT(*) FROM order_outbox WHERE order_id='"+recovering['id']+"' AND state<>'SENT'",order_db)=='2'
            consistent(909,1,1)
        finally:subprocess.run(['docker','unpause',mq_container],check=True,capture_output=True)
        await_notification(recovering['id']);await_status(recovering['id'],'CANCELLED');consistent(909,0,1)
        assert mqbook(909,recovery_key)[1]['id']==recovering['id']
        print('Broker outage did not fail checkout; outbox recovered both messages and immediately cancelled the overdue unpaid order.',flush=True)
    print('All distributed transaction checks passed.',flush=True)
finally:
    parent_probe=processes.get('globalProbe')
    if parent_probe is not None and parent_probe.poll() is None:
        try:
            parent_probe.stdin.write(b'r\n');parent_probe.stdin.flush();parent_probe.wait(timeout=15)
        except (OSError,subprocess.TimeoutExpired):pass
    for process in processes.values():
        if process.poll() is None:process.terminate()
    for process in processes.values():
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill()
    for handle in handles:handle.close()
    stop.set();proxy.shutdown()
    nacos('/ns/instance','DELETE',{'serviceName':'catalog-service','groupName':group,'ip':'127.0.0.1','port':23309,'ephemeral':'true'})
    for p in (root/'sentinel').glob('*.json'):nacos('/cs/configs','DELETE',{'dataId':p.name,'group':rule_group,'accessToken':access})
    print('Isolated applications and temporary registry rules cleaned up.',flush=True)

