#!/usr/bin/env python3
"""Sign assembled release APK with local credentials, checking the production certificate."""
import os, pathlib, re, subprocess, sys
root=pathlib.Path(__file__).resolve().parents[1]
props=root/'signing.local.properties'
if not props.is_file():
    sys.exit('Create signing.local.properties with storePassword, keyAlias and keyPassword.')
values={}
for line in props.read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        k,v=line.split('=',1);values[k.strip()]=v.strip()
for key in ('storePassword','keyAlias','keyPassword'):
    if not values.get(key): sys.exit(f'Missing {key} in signing.local.properties')
keystore=pathlib.Path(values.get('storeFile','/var/home/Snoopy63/Documents/bonobo.jks'))
if not keystore.is_file(): sys.exit(f'Keystore not found: {keystore}')
source=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
if not source.is_file(): sys.exit('Assemble release first.')
expected='a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1'
sdk=pathlib.Path(os.environ.get('ANDROID_HOME','/var/home/Snoopy63/Android/Sdk'))
apksigner=sdk/'build-tools/35.0.0/apksigner'
output=root/'delivery/DNSphere-1.5g-production.apk'
env=os.environ.copy();env['DNSPHERE_STORE_PASSWORD']=values['storePassword'];env['DNSPHERE_KEY_PASSWORD']=values['keyPassword']
cmd=[str(apksigner),'sign','--ks',str(keystore),'--ks-key-alias',values['keyAlias'],
     '--ks-pass','env:DNSPHERE_STORE_PASSWORD','--key-pass','env:DNSPHERE_KEY_PASSWORD',
     '--out',str(output),str(source)]
subprocess.run(cmd,env=env,check=True)
result=subprocess.run([str(apksigner),'verify','--print-certs',str(output)],env=env,capture_output=True,text=True,check=True).stdout
actual=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',result)
if not actual or actual.group(1)!=expected:
    output.unlink(missing_ok=True)
    sys.exit('Signing certificate differs from the known production APK. Output removed.')
print(f'Signed production APK: {output}')
print('Installed DNSphere 1.5f uses a different Debug certificate; this APK cannot update that installation in place.')
