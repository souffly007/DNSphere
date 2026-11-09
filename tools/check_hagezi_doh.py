#!/usr/bin/env python3
"""Real wire-format A/AAAA DoH checks; does not exercise Android's VPN tunnel."""
import base64, json, struct, subprocess
endpoints = [('ctif.hagezi.org','162.55.58.40'), ('root.hagezi.org','188.34.161.210'), ('wurzn.hagezi.org','159.69.155.94')]
results=[]
for host,ip in endpoints:
    for domain in ['example.com','use-application-dns.net']:
        for qtype in [1,28]:
            query=struct.pack('!6H',0x1234,0x100,1,0,0,0)+b''.join(bytes([len(x)])+x.encode() for x in domain.split('.'))+b'\0'+struct.pack('!HH',qtype,1)
            url=f'https://{host}/dns-query?dns='+base64.urlsafe_b64encode(query).decode().rstrip('=')
            r=subprocess.run(['curl','--silent','--show-error','--fail','--noproxy','*','--connect-timeout','5','--max-time','8','--resolve',f'{host}:443:{ip}','-H','Accept: application/dns-message',url],capture_output=True)
            item=dict(endpoint=host,domain=domain,qtype=qtype)
            if r.returncode: item.update(ok=False,error=r.stderr.decode().strip())
            else:
                a=r.stdout
                valid=len(a)>=12 and a[:2]==query[:2] and a[2]&128 and a[12:len(query)]==query[12:]
                rcode=a[3]&15 if len(a)>=12 else None
                answers=struct.unpack('!H',a[6:8])[0] if len(a)>=12 else 0
                item.update(ok=bool(valid and ((rcode==0 and answers>0) if domain=='example.com' else rcode==3)),rcode=rcode,answers=answers,bytes=len(a))
            results.append(item)
print(json.dumps(results,indent=2,ensure_ascii=False))
raise SystemExit(0 if all(r['ok'] for r in results) else 1)
