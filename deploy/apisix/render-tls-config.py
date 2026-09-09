"""Render standalone APISIX TLS config from mounted secret files; never print key material."""
import argparse, json, os, pathlib, re, ssl

parser=argparse.ArgumentParser()
parser.add_argument("--output",required=True,help="Private runtime directory outside version control")
args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parent
cert_path=pathlib.Path(os.environ["APISIX_TLS_CERT_FILE"])
key_path=pathlib.Path(os.environ["APISIX_TLS_KEY_FILE"])
if not cert_path.is_absolute() or not key_path.is_absolute():raise SystemExit("TLS secret paths must be absolute")
names=[value.strip().lower() for value in os.environ["APISIX_TLS_SNIS"].split(",") if value.strip()]
if not names or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?",name) for name in names):raise SystemExit("Explicit normalized SNI names are required")
for variable in ("MANAGEMENT_HOST","SHORTLINK_HOST"):
    if os.environ[variable].lower() not in names:raise SystemExit(variable+" must be covered by TLS SNIs")
if cert_path.stat().st_size>131072 or key_path.stat().st_size>32768:raise SystemExit("TLS input exceeds budget")
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(str(cert_path),str(key_path))  # Parse PEM and verify the private key matches.
cert=cert_path.read_text(encoding="utf-8");key=key_path.read_text(encoding="utf-8")
config=(root/"config.yaml").read_text(encoding="utf-8")
config=config.replace("  enable_admin: false", "  enable_admin: false\n  ssl:\n    enable: true\n    listen:\n      - port: 9443\n    ssl_protocols: TLSv1.2 TLSv1.3\n    ssl_session_tickets: false")
routes=(root/"apisix.yaml").read_text(encoding="utf-8").replace("#END","").rstrip()
routes+="\nssls:\n  - id: shortlink-public-tls\n    snis: "+json.dumps(names)+"\n    cert: "+json.dumps(cert)+"\n    key: "+json.dumps(key)+"\n#END\n"
output=pathlib.Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True,mode=0o700)
if output==root or root in output.parents:raise SystemExit("Write secrets to an external runtime directory, not deploy/apisix")
for name,content in (("config.yaml",config),("apisix.yaml",routes)):
    path=output/name
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,"w",encoding="utf-8",newline="\n") as writer:writer.write(content)
    os.chmod(path,0o600)
print("Rendered APISIX TLS runtime files. Publish only 443:9443; do not publish 9080 or admin/metrics ports.")
