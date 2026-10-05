"""A Python stand-in for the Android service's PhoneFs: same protocol, files under a local folder."""
import asyncio, json, sys, fnmatch, os, time
import aiohttp
T="testsecret-0123456789abcdef"; U=sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8799"; ROOT=sys.argv[1]
def resolve(p):
    p=(p or "/sdcard").replace("\\","/")
    if p.startswith("/sdcard"): p=p[7:]
    f=os.path.realpath(os.path.join(ROOT, p.lstrip("/")))
    root=os.path.realpath(ROOT)   # compare whole components: on Windows realpath gives "\\", "+'/'" never matched
    if os.path.normcase(os.path.commonpath([f, root])) != os.path.normcase(root): raise ValueError("outside storage")
    return f
def show(f): return "/sdcard"+os.path.realpath(f)[len(os.path.realpath(ROOT)):].replace(os.sep, "/")
def item(f): st=os.stat(f); return {"name":os.path.basename(f),"path":show(f),"dir":os.path.isdir(f),"size":0 if os.path.isdir(f) else st.st_size,"mtime":int(st.st_mtime)}
async def main():
    writes={}
    async with aiohttp.ClientSession() as s:
        ws=await s.ws_connect(U+"/ws/phone", autoclose=False)
        await ws.send_str(json.dumps({"t":"auth","token":T})); await ws.receive()
        await ws.send_str(json.dumps({"t":"hello_phone","model":"Redmi Note 13","fs":True}))
        print("fake phone ready", flush=True)
        async for m in ws:
            if m.type==aiohttp.WSMsgType.BINARY:
                if m.data[0]==7:
                    rid=m.data[1:9].decode().strip(); w=writes.get(rid)
                    if w: w.write(m.data[9:])
                continue
            if m.type!=aiohttp.WSMsgType.TEXT: continue
            ev=json.loads(m.data)
            if ev.get("t")!="pfs": continue
            rid=ev["id"]; op=ev["op"]; r={"t":"pfs_r","id":rid,"ok":True}
            try:
                if op=="roots": r["items"]=[{"name":"Память телефона","path":"/sdcard"},{"name":"Камера","path":"/sdcard/DCIM/Camera"}]; r["free"]=50e9; r["total"]=128e9
                elif op=="list":
                    d=resolve(ev.get("path")); names=sorted(os.listdir(d), key=lambda n:(not os.path.isdir(os.path.join(d,n)), n.lower()))
                    r["path"]=show(d); r["items"]=[item(os.path.join(d,n)) for n in names]
                elif op=="stat": r["item"]=item(resolve(ev["path"]))
                elif op=="find":
                    out=[]
                    for dp,dn,fn in os.walk(resolve(ev.get("path"))):
                        for n in dn+fn:
                            if fnmatch.fnmatch(n.lower(), ev.get("q","*").lower()): out.append(item(os.path.join(dp,n)))
                    r["items"]=out[:500]
                elif op=="read":
                    f=resolve(ev["path"]); await ws.send_str(json.dumps({**r,"item":item(f),"begin":True}, separators=(",",":")))
                    hdr=b"\x06"+rid.encode().ljust(8)
                    with open(f,"rb") as fh:
                        while chunk:=fh.read(256*1024): await ws.send_bytes(hdr+chunk)
                    r["done"]=True
                elif op=="write_begin":
                    f=resolve(ev["path"]); os.makedirs(os.path.dirname(f), exist_ok=True); writes[rid]=open(f+".part","wb"); r["ready"]=True
                elif op=="write_end":
                    w=writes.pop(rid); w.close(); f=w.name[:-5]; os.replace(w.name,f); r["item"]=item(f)
                elif op=="delete":
                    f=resolve(ev["path"]); import shutil; shutil.rmtree(f) if os.path.isdir(f) else os.remove(f)
                elif op=="mkdir": f=resolve(ev["path"]); os.makedirs(f, exist_ok=True); r["item"]=item(f)
                elif op=="move": a=resolve(ev["path"]); b=resolve(ev["to"]); os.rename(a,b); r["item"]=item(b)
                else: r={"t":"pfs_r","id":rid,"ok":False,"error":"unknown op"}
            except Exception as e: r={"t":"pfs_r","id":rid,"ok":False,"error":str(e)}
            await ws.send_str(json.dumps(r, separators=(",",":")))
asyncio.run(main())
