"""Boot the real production profile jars with explicit isolated adapters, collect health/metrics, then stop."""
import argparse, datetime, json, os, pathlib, subprocess, sys, time
sys.dont_write_bytecode=True
from component_adapters import ROOT, http

parser=argparse.ArgumentParser();parser.add_argument("--module",choices=["all","gateway","shortlink-redirect"],default="all");args=parser.parse_args()
if sys.platform != "win32":raise SystemExit("Run this component entry on Windows with a Java 17 JDK.")
java_home=os.getenv("SHORTLINK_IT_JAVA_HOME") or os.getenv("JAVA_HOME")
if not java_home or not java_home.strip():raise SystemExit("Set SHORTLINK_IT_JAVA_HOME or JAVA_HOME to a Windows Java 17 JDK.")
java=pathlib.Path(java_home)/"bin/java.exe"
if not java.is_file():raise SystemExit("The selected Java home must contain bin/java.exe; Java 17 is required.")
output=ROOT/".work/component-results";output.mkdir(parents=True,exist_ok=True)
stamp=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
env=dict(os.environ,REDIS_HOST="127.0.0.1",REDIS_PORT="16379",REDIS_PASSWORD="",APISIX_CIDRS="127.0.0.0/8",ADMIN_ALLOWED_HOSTS="admin.it.test",
    INTERNAL_TOKEN="production-config-component-test-token-32",ADMIN_URL="http://127.0.0.1:28002",SHORTLINK_ALLOWED_DOMAINS="s.example",
    BUSINESS_DB_URL=os.environ["SHORTLINK_IT_BUSINESS_DB_URL"],REDIRECT_DB_USERNAME=os.environ["SHORTLINK_IT_DB_USERNAME"],REDIRECT_DB_PASSWORD=os.environ["SHORTLINK_IT_DB_PASSWORD"],
    REDIRECT_INSTANCE_ID="component-production-"+stamp,ANALYTICS_HASH_KEY="11"*32,COMMAND_URL=os.getenv("SHORTLINK_IT_COMMAND_URL","http://127.0.0.1:28001"),KAFKA_BOOTSTRAP_SERVERS="localhost:19092")
rows=[]
for module,artifact,port,management in (("gateway","shortlink-gateway",18000,18100),("shortlink-redirect","shortlink-redirect",18003,18103)):
    if args.module not in ("all",module):continue
    log=output/(module+"-production-"+stamp+".log")
    with log.open("wb") as stream:
        command=[str(java),"-Dfile.encoding=UTF-8","-jar",str(ROOT/"services"/module/"target"/(artifact+"-1.0-SNAPSHOT.jar")),
            "--spring.profiles.active=production","--spring.config.location=classpath:application-production.properties",
            "--server.port="+str(port),"--management.server.port="+str(management),"--spring.data.redis.password=",
            "--management.endpoint.health.probes.enabled=true","--management.endpoint.health.show-details=always"]
        process=subprocess.Popen(command,env=env,stdout=stream,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline=time.monotonic()+60
            while True:
                if process.poll() is not None:raise RuntimeError(module+" failed to start; see "+log.name)
                try:
                    status,_,body=http(management,"GET","/actuator/health/liveness")
                    if status==200:break
                except (OSError,TimeoutError):pass
                if time.monotonic()>deadline:raise RuntimeError(module+" startup budget exceeded")
                time.sleep(1)
            health_status,_,health=http(management,"GET","/actuator/health")
            health_deadline=time.monotonic()+30
            while health_status!=200 and time.monotonic()<health_deadline:
                time.sleep(5)
                health_status,_,health=http(management,"GET","/actuator/health")
            metrics_status,_,metrics=http(management,"GET","/actuator/prometheus")
            if metrics_status!=200:raise RuntimeError(module+" Prometheus endpoint failed")
            metric_path=output/(module+"-prometheus-"+stamp+".txt");metric_path.write_bytes(metrics)
            rows.append(dict(module=module,liveness=json.loads(body),healthHttpStatus=health_status,health=json.loads(health),metricsHttpStatus=metrics_status,metrics=metric_path.name,log=log.name))
            print(module,"STARTED",health_status,flush=True)
            if health_status!=200:print(health.decode(),flush=True)
        finally:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
(output/("production-jars-"+stamp+".json")).write_text(json.dumps(rows,indent=2),encoding="utf-8")
raise SystemExit(0 if rows and all(row["healthHttpStatus"]==200 for row in rows) else 1)
