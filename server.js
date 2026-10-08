// JASHPI relay for Render. Node 18+, no dependencies. Env: BOT_TOKEN (secret), DATA_DIR (optional, persistent disk path)
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto');
const TOKEN=process.env.BOT_TOKEN;if(!TOKEN){console.error('Set BOT_TOKEN in Render Environment');process.exit(1)}
const API=`https://api.telegram.org/bot${TOKEN}/`,HOOK=crypto.createHash('sha256').update(TOKEN).digest('hex').slice(0,24);
const DB=path.join(process.env.DATA_DIR||__dirname,'subs.json');let subs={};try{subs=JSON.parse(fs.readFileSync(DB))}catch(e){}
const saveDb=()=>fs.writeFile(DB,JSON.stringify(subs),()=>{});
const tracks={},hits={};let botName='';
const tg=(m,b)=>fetch(API+m,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)}).then(r=>r.json());
const ok=(r,c,b,t='application/json')=>{r.writeHead(c,{'content-type':t+'; charset=utf-8','access-control-allow-origin':'*','access-control-allow-headers':'content-type'});r.end(typeof b==='string'||Buffer.isBuffer(b)?b:JSON.stringify(b))};
const body=q=>new Promise(r=>{let d='';q.on('data',c=>{d+=c;if(d.length>20000)q.destroy()});q.on('end',()=>{try{r(JSON.parse(d||'{}'))}catch(e){r({})}})});
const limited=ip=>{const n=Date.now(),h=(hits[ip]=(hits[ip]||[]).filter(t=>n-t<60000));h.push(n);return h.length>40};
const num=v=>typeof v==='number'&&isFinite(v);
setInterval(()=>{const n=Date.now();for(const k in tracks)if(n-tracks[k].t>6*3600e3)delete tracks[k]},600e3);

http.createServer(async(q,s)=>{
 const u=new URL(q.url,'http://x'),p=u.pathname,ip=q.headers['x-forwarded-for']||q.socket.remoteAddress;
 if(q.method==='OPTIONS')return ok(s,204,'');
 if(limited(ip)&&p!==`/tg/${HOOK}`)return ok(s,429,{error:'slow down'});
 try{
  if(p==='/info')return ok(s,200,{bot:botName});
  if(p===`/tg/${HOOK}`&&q.method==='POST'){const m=(await body(q)).message;
   const t=m&&m.text,c=t&&/^\/start\s+([a-z0-9]{16})$/.exec(t);
   if(c){const k=c[1];subs[k]=[...new Set([...(subs[k]||[]),m.chat.id])].slice(0,20);saveDb();tg('sendMessage',{chat_id:m.chat.id,text:'✅ You will now receive JASHPI safety alerts.'})}
   return ok(s,200,{})}
  if(p.startsWith('/subs/')){const i=subs[p.slice(6)]||[];return ok(s,200,{count:i.length,ids:i})}
  if(p==='/sos'&&q.method==='POST'){const b=await body(q);if(!/^[a-z0-9]{16}$/.test(b.code||''))return ok(s,400,{error:'code'});
   const extra=Array.isArray(b.ids)?b.ids.filter(Number.isFinite).slice(0,20):[],ids=[...new Set([...(subs[b.code]||[]),...extra])],text=String(b.text||'').slice(0,800);let sent=0;
   await Promise.all(ids.map(async id=>{const r=await tg('sendMessage',{chat_id:id,text:'🚨 '+text});if(r.ok){sent++;if(num(b.lat)&&num(b.lng))tg('sendLocation',{chat_id:id,latitude:b.lat,longitude:b.lng})}}));
   return ok(s,200,{sent,total:ids.length})}
  if(p==='/track'&&q.method==='POST'){const b=await body(q);if(!/^[a-z0-9]{10}$/.test(b.id||'')||!num(b.lat)||!num(b.lng))return ok(s,400,{});
   tracks[b.id]={lat:b.lat,lng:b.lng,name:String(b.name||'').slice(0,40),t:Date.now()};return ok(s,200,{})}
  if(p.startsWith('/t/')){const k=tracks[p.slice(3)],e=x=>String(x).replace(/[&<>"]/g,'');
   if(!k)return ok(s,404,'<meta name=viewport content="width=device-width"><body style="font:18px sans-serif;padding:24px">Tracking link expired.</body>','text/html');
   const d=.004,src=`https://www.openstreetmap.org/export/embed.html?bbox=${k.lng-d},${k.lat-d},${k.lng+d},${k.lat+d}&layer=mapnik&marker=${k.lat},${k.lng}`;
   return ok(s,200,`<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv=refresh content=10><title>Live location</title><body style="margin:0;font:16px sans-serif"><div style="padding:12px;background:#0B1030;color:#fff"><b>${e(k.name)}</b> · updated ${Math.round((Date.now()-k.t)/1000)}s ago · <a style="color:#19D3F2" href="https://maps.google.com/?q=${k.lat},${k.lng}">Open in Maps</a></div><iframe src="${src}" style="border:0;width:100%;height:calc(100vh - 48px)"></iframe></body>`,'text/html')}
  const f={'/':'index.html','/index.html':'index.html','/sw.js':'sw.js'}[p];
  if(f){const pub=path.join(__dirname,'public',f),alt=path.join(__dirname,f),file=fs.existsSync(pub)?pub:alt;
   return ok(s,200,fs.readFileSync(file),f.endsWith('.js')?'text/javascript':'text/html')}
  ok(s,404,{error:'not found'})
 }catch(e){console.error(e);ok(s,500,{error:'server'})}
}).listen(process.env.PORT||3000,'0.0.0.0',async()=>{
 const me=await tg('getMe',{});botName=me.result&&me.result.username||'';
 const base=process.env.PUBLIC_URL||process.env.RENDER_EXTERNAL_URL||'';
 if(base){const r=await tg('setWebhook',{url:`${base}/tg/${HOOK}`,allowed_updates:['message']});console.log('webhook',base,r.ok)}
 else console.log('Set PUBLIC_URL to your https URL so Telegram can reach /tg');
 console.log('JASHPI relay ready, bot @'+botName)});
