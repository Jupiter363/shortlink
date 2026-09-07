"""One isolated TLS adapter instance, trusted temporary CA certificate, no verify=False."""
import datetime, http.client, json, os, pathlib, socket, ssl, subprocess, sys, time, uuid
sys.dont_write_bytecode=True
from component_adapters import ROOT, docker, case, RESULTS, require

run=uuid.uuid4().hex[:10]
directory=ROOT/".work"/("apisix-tls-"+run);directory.mkdir(parents=True)
def linux(path):
    path=pathlib.Path(path).resolve()
    return "/mnt/"+path.drive[0].lower()+str(path)[2:].replace("\\","/")
cert=directory/"cert.pem";key=directory/"key.pem";rendered=directory/"runtime"
subprocess.run(["wsl","-d",os.getenv("SHORTLINK_IT_WSL","shortlink-refactor-it"),"--exec","openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1",
    "-subj","/CN=s.it.test","-addext","subjectAltName=DNS:s.it.test,DNS:admin.it.test","-keyout",linux(key),"-out",linux(cert)],check=True,capture_output=True)
environment=dict(os.environ,APISIX_TLS_CERT_FILE=str(cert),APISIX_TLS_KEY_FILE=str(key),APISIX_TLS_SNIS="s.it.test,admin.it.test",MANAGEMENT_HOST="admin.it.test",SHORTLINK_HOST="s.it.test")
subprocess.run([sys.executable,str(ROOT/"deploy/apisix/render-tls-config.py"),"--output",str(rendered)],check=True,env=environment)
container="shortlink-refactor-it-apisix-tls-"+run
docker("run","--detach","--rm","--name",container,"--network","shortlink-refactor-it_default","-p","127.0.0.1:19443:9443",
    "-e","KAFKA_HOST=kafka","-e","APISIX_INSTANCE_ID="+container,"-e","MANAGEMENT_HOST=admin.it.test","-e","SHORTLINK_HOST=s.it.test",
    "-e","GATEWAY_UPSTREAM_HOST=apisix-stubs","-e","REDIRECT_UPSTREAM_HOST=apisix-stubs",
    "-v",linux(rendered/"config.yaml")+":/usr/local/apisix/conf/config.yaml:ro","-v",linux(rendered/"apisix.yaml")+":/usr/local/apisix/conf/apisix.yaml:ro",
    "-v",linux(ROOT/"deploy/apisix/plugins")+":/opt/shortlink:ro","apache/apisix:3.11.0-debian")
context=ssl.create_default_context(cafile=str(cert))
def request(host,method,path):
    with socket.create_connection(("127.0.0.1",19443),5) as transport:
        with context.wrap_socket(transport,server_hostname=host) as secure:
            secure.sendall((method+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\nX-Forwarded-Proto: http\r\nConnection: close\r\n\r\n").encode())
            response=http.client.HTTPResponse(secure,method=method);response.begin()
            return response.status,dict(response.getheaders()),response.read(),secure.version()
try:
    deadline=time.monotonic()+30
    while True:
        try:request("s.it.test","HEAD","/Ab");break
        except (OSError,ssl.SSLError):
            if time.monotonic()>deadline:raise
            time.sleep(1)
    def get():
        status,headers,body,version=request("s.it.test","GET","/Ab")
        require(status==302 and headers.get("Location")=="https://destination.it.test/","TLS redirect mismatch")
        require(version in ("TLSv1.2","TLSv1.3"),"Unexpected TLS version")
        return dict(status=status,tls=version,hostnameVerified=True)
    def head():
        status,_,body,_=request("s.it.test","HEAD","/Ab")
        require(status==302 and not body,"HEAD behavior changed under TLS")
        return dict(status=status,bodyBytes=len(body))
    def management():
        status,_,body,version=request("admin.it.test","GET","/api/short-link/admin/v1/group")
        require(status==200 and json.loads(body)["forwardedProto"]=="https","Untrusted inbound scheme survived TLS")
        return dict(status=status,tls=version,forwardedProto="https")
    case("TLS01-trusted-sni-redirect",get);case("TLS02-head",head);case("TLS03-management-scheme",management)
finally:
    docker("stop","--time","5",container)
    output=ROOT/".work/component-results";output.mkdir(parents=True,exist_ok=True)
    path=output/("tls-"+datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+".json")
    path.write_text(json.dumps(dict(certificate="temporary self-signed test certificate, trusted explicitly with hostname verification",results=RESULTS),indent=2),encoding="utf-8")
    print(path)
raise SystemExit(0 if RESULTS and all(row["outcome"]=="PASS" for row in RESULTS) else 1)
