"""Isolated APISIX and official ClickHouse Kafka Connect component checks (no business E2E)."""
import argparse, base64, datetime, json, os, pathlib, subprocess, time, uuid
from http.client import HTTPConnection

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESULTS = []


def http(port, method, path, body=None, headers=None):
    connection = HTTPConnection("127.0.0.1", port, timeout=8)
    encoded = json.dumps(body).encode() if isinstance(body, (dict, list)) else body
    merged = dict(headers or {})
    if isinstance(body, (dict, list)):
        merged["Content-Type"] = "application/json"
    try:
        connection.request(method, path, body=encoded, headers=merged)
        response = connection.getresponse()
        return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read()
    finally:
        connection.close()


def docker(*args, data=None, timeout=60):
    command = ["wsl", "-d", os.getenv("SHORTLINK_IT_WSL", "shortlink-refactor-it"), "--exec", "docker", *args]
    result = subprocess.run(command, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Isolated Docker command failed: " + result.stdout.decode(errors="replace")[-1000:])
    return result.stdout.decode("utf-8", errors="replace").strip()


def case(name, action):
    started = time.monotonic()
    try:
        evidence = action()
        RESULTS.append(dict(id=name, outcome="PASS", seconds=round(time.monotonic()-started, 3), evidence=evidence))
    except Exception as failure:
        RESULTS.append(dict(id=name, outcome="FAIL", seconds=round(time.monotonic()-started, 3), error=str(failure)))
    print(name, RESULTS[-1]["outcome"], flush=True)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def apisix():
    def request(method, path, host, expected, headers=None):
        status, response_headers, body = http(19080, method, path, headers={"Host":host, **(headers or {})})
        require(status == expected, f"Expected {expected}; received {status}: {body[:200]!r}")
        return status, response_headers, body
    def redirect():
        status, headers, body = request("GET", "/Ab9", "s.it.test", 302)
        require(headers.get("location") == "https://destination.it.test/", "Wrong upstream/location")
        return dict(status=status, location=headers["location"], bodyBytes=len(body))
    def head():
        status, headers, body = request("HEAD", "/Ab9", "s.it.test", 302)
        require(body == b"", "HEAD emitted body")
        return dict(status=status, bodyBytes=len(body), location=headers.get("location"))
    def spoofed():
        status, _, body = request("GET", "/api/short-link/admin/v1/group", "admin.it.test", 200,
            {"x-shortlink-tenant-id":"forged", "x-agent-username":"forged", "userId":"999", "realName":"forged",
             "X-Internal-Token":"forged", "username":"credential-user", "X-Forwarded-For":"1.2.3.4", "X-Forwarded-Proto":"https", "X-Request-ID":"forged"})
        echoed=json.loads(body)
        for key in ("spoofedTenant", "spoofedAgent", "legacyUserId", "legacyRealName", "internalToken"):
            require(echoed[key] == "", key + " survived boundary")
        require(echoed["username"] == "credential-user", "Management credential was discarded")
        require(echoed["forwardedFor"] != "1.2.3.4" and echoed["forwardedProto"] == "http", "Forwarding claims survived")
        require(echoed["requestId"] and echoed["requestId"] != "forged", "Request ID not regenerated")
        return dict(status=status, echoed=echoed)
    case("AP01-public-get", redirect)
    case("AP02-head", head)
    for name, method, path, host, status in [
        ("AP03-method", "POST", "/Ab9", "s.it.test", 405),
        ("AP04-host", "GET", "/Ab9", "unregistered.it.test", 404),
        ("AP05-public-internal", "GET", "/internal/command/risk/current", "s.it.test", 404),
        ("AP06-management-internal", "GET", "/internal/command/risk/current", "admin.it.test", 404),
        ("AP07-encoded-path", "GET", "/Ab%2F9", "s.it.test", 400),
        ("AP08-double-slash", "GET", "/Ab//9", "s.it.test", 400),
        ("AP09-long-alias", "GET", "/"+"A"*33, "s.it.test", 404),
        ("AP10-host-normalization", "GET", "/Ab9", "S.IT.TEST:80", 302)]:
        case(name, lambda m=method,p=path,h=host,s=status:dict(status=request(m,p,h,s)[0]))
    case("AP11-identity-forwarding", spoofed)


def initialize_clickhouse():
    database="shortlink_analytics_it"
    for filename in ("001-analytics.sql", "002-connect-landing.sql"):
        sql=(ROOT/"deploy/clickhouse"/filename).read_text(encoding="utf-8").replace("shortlink_analytics",database)
        docker("exec","-i","shortlink-refactor-it-clickhouse-1","clickhouse-client","--user","shortlink_it","--password","shortlink-it-only","--multiquery",data=sql.encode())
    return {"database":database, "ddl":["001-analytics.sql","002-connect-landing.sql"], "destructiveOperations":False}


def initialize_topics():
    script=(ROOT/"deploy/kafka/create-topics.sh").read_text(encoding="utf-8").replace("\r\n","\n")
    result=docker("exec","-i","-e","BOOTSTRAP_SERVERS=localhost:9092","-e","REPLICATION_FACTOR=1","-e","MIN_INSYNC_REPLICAS=1","-e","PARTITIONS=2",
        "shortlink-refactor-it-kafka-1","sh",data=script.encode(),timeout=120)
    return {"scope":"isolated Kafka only", "partitions":2,"replicationFactor":1,"minIsr":1,"output":result}


def ch(sql):
    token=base64.b64encode(b"shortlink_it:shortlink-it-only").decode()
    status, _, body=http(18123,"POST","/",sql.encode(),{"Authorization":"Basic "+token})
    require(status==200,"ClickHouse query failed: "+body[:500].decode(errors="replace"))
    return body.decode().strip()


def wait_for(check, seconds=45):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        result=check()
        if result:
            return result
        time.sleep(1)
    raise AssertionError("Component condition did not converge within budget")


def connect():
    status, _, payload=http(18083,"GET","/connector-plugins")
    plugins=json.loads(payload)
    require(status==200 and any(p["class"]=="com.clickhouse.kafka.connect.ClickHouseSinkConnector" and p["version"] in ("1.4.0","v1.4.0") for p in plugins),"Official ClickHouse connector 1.4.0 is absent")
    run=uuid.uuid4().hex[:12]
    now=int(time.time()*1000)
    record=dict(kind="CLICK",clusterId="adapter-it",topicId=run,sourceTopic="shortlink.click.raw.v1",sourcePartition=0,sourceOffset=now,
        receivedAt=now,timestampType="LogAppendTime",eventId="v1:"+str(now)+":adapter-"+run,payloadHash="a"*64,tenantId="1001",linkId=9007199254740993,
        occurredAt=now,visitorHash="b"*32,ipHash="c"*32,browser="Chrome",os="Linux",device="Desktop",country="UNKNOWN",refererDomain="fixture.it.test",
        requestSource="",decisionStage="",status=302,reason="",validationVersion="broker-time-v2",validationResult="VALID",detailDatasetVersion="detail-v1",parserVersion="builtin-ua-v1",hashVersion="hmac-sha256-128-v1")
    def lane(suffix, malformed):
        name="shortlink-it-adapter-"+run+"-"+suffix
        topic="shortlink.it.adapter."+run+"."+suffix
        table="adapter_"+run+"_"+suffix
        require(table.startswith("adapter_") and table.replace("_","").isalnum(),"Invalid test table")
        ch("CREATE TABLE shortlink_analytics_it."+table+" AS shortlink_analytics_it.derived_events")
        docker("exec","shortlink-refactor-it-kafka-1","/opt/kafka/bin/kafka-topics.sh","--bootstrap-server","localhost:9092","--create","--topic",topic,"--partitions","1","--replication-factor","1")
        config=json.loads((ROOT/"deploy/clickhouse/connect-config.json").read_text())["config"]
        config.update({"tasks.max":"1","topics":topic,"topic2TableMap":topic+"="+table,"database":"shortlink_analytics_it","username":"shortlink_it","errors.retry.timeout":"1000"})
        code,_,body=http(18083,"POST","/connectors",{"name":name,"config":config})
        require(code==201,"Connector registration failed: "+str(code))
        try:
            def connector_status():
                code,_,body=http(18083,"GET","/connectors/"+name+"/status")
                if code==404:return {"tasks":[]}  # The distributed status topic is populated asynchronously.
                require(code==200,"Connector status unavailable")
                return json.loads(body)
            wait_for(lambda:connector_status().get("tasks") and all(task["state"]=="RUNNING" for task in connector_status()["tasks"]))
            item=dict(record)
            if malformed:item["sourcePartition"]="invalid-uint32"
            docker("exec","-i","shortlink-refactor-it-kafka-1","/opt/kafka/bin/kafka-console-producer.sh","--bootstrap-server","localhost:9092","--topic",topic,data=(json.dumps(item)+"\n").encode())
            if malformed:
                state=wait_for(lambda:(lambda s:s if any(task["state"]=="FAILED" for task in s.get("tasks",[])) else None)(connector_status()))
                count=ch("SELECT count() FROM shortlink_analytics_it."+table)
                require(count=="0","Malformed event was stored")
                return dict(connector=name,topic=topic,table=table,taskStates=[t["state"] for t in state["tasks"]],storedRows=0,
                    failureSummary=[t.get("trace","").splitlines()[0] for t in state["tasks"] if t["state"]=="FAILED"])
            count=wait_for(lambda:(lambda n:n if int(n)>0 else None)(ch("SELECT count() FROM shortlink_analytics_it."+table+" WHERE eventId='"+record["eventId"]+"'")))
            row=json.loads(ch("SELECT linkId,sourceOffset,refererDomain,hashVersion FROM shortlink_analytics_it."+table+" WHERE eventId='"+record["eventId"]+"' LIMIT 1 FORMAT JSONEachRow"))
            require(int(row["linkId"])==record["linkId"] and row["refererDomain"]==record["refererDomain"] and row["hashVersion"]==record["hashVersion"],"Landing values/schema drifted")
            return dict(connector=name,topic=topic,table=table,eventId=record["eventId"],storedRows=int(count),row=row)
        finally:
            http(18083,"DELETE","/connectors/"+name)
    case("CHC01-official-landing",lambda:lane("valid",False))
    case("CHC02-schema-error-fails-task",lambda:lane("invalid",True))


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("mode",choices=["init","topics","apisix","connect"]);args=parser.parse_args()
    try:
        if args.mode=="init":case("CH00-initialize",initialize_clickhouse)
        elif args.mode=="topics":case("KF00-initialize",initialize_topics)
        elif args.mode=="apisix":apisix()
        else:connect()
    except Exception as failure:
        RESULTS.append(dict(id=args.mode+"-setup",outcome="FAIL",error=str(failure)))
    output=ROOT/".work/component-results";output.mkdir(parents=True,exist_ok=True)
    path=output/(args.mode+"-"+datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+".json")
    path.write_text(json.dumps(dict(scope="isolated component integration; no business E2E or load test",results=RESULTS),ensure_ascii=False,indent=2),encoding="utf-8")
    print(path,flush=True)
    raise SystemExit(0 if all(item["outcome"]=="PASS" for item in RESULTS) else 1)
