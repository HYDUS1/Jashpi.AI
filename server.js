// JASHPI AI — Telegram relay + static host (Render / Railway / Replit / any Node host). No npm packages needed (Node 18+).
// Replit: Secrets -> BOT_TOKEN = your BotFather token. Then Run / Deploy.
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto');
const T=process.env.BOT_TOKEN,PORT=process.env.PORT||3000;
if(!T)console.error('!! Add BOT_TOKEN in Replit Secrets');
const SEC=T?crypto.createHash('sha256').update(T).digest('hex').slice(0,32):'';
const TRK=new Map(),TRKPAGE=`<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>JASHPI live location</title><body style="margin:0;font-family:sans-serif;background:#070b1a;color:#fff"><div style="padding:12px"><b id=n>JASHPI live location</b><div id=s style="opacity:.7;font-size:13px">Loading...</div></div><iframe id=m style="border:0;width:100%;height:80vh"></iframe><script>const id=location.pathname.split('/').pop();let last='';async function f(){try{const j=await(await fetch('/track/'+id)).json();if(!j.ok)throw 0;n.textContent=j.name+' - live location';s.textContent='Updated '+Math.round((Date.now()-j.t)/1000)+'s ago';const u='https://maps.google.com/maps?q='+j.lat+','+j.lng+'&z=16&output=embed';if(u!=last){m.src=u;last=u}}catch(e){s.textContent='Not available yet - retrying'}}f();setInterval(f,8000)</script>`;
const tg=(m,b)=>fetch(`https://api.telegram.org/bot${T}/${m}`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)});
const hits=new Map(),rl=(k,max,ms)=>{const n=Date.now(),a=(hits.get(k)||[]).filter(t=>n-t<ms);a.push(n);hits.set(k,a);return a.length>max};
const TW={s:process.env.TWILIO_SID,t:process.env.TWILIO_TOKEN,f:process.env.TWILIO_FROM},day={d:'',n:0},MAXD=+process.env.MAX_DAILY||100; // optional Twilio: real SMS + phone call without any tap
const twPost=(p,q)=>fetch(`https://api.twilio.com/2010-04-01/Accounts/${TW.s}/${p}.json`,{method:'POST',headers:{Authorization:'Basic '+Buffer.from(TW.s+':'+TW.t).toString('base64'),'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams(q)});
const body=r=>new Promise(ok=>{let d='';r.on('data',c=>{d+=c;if(d.length>20000)r.destroy()});r.on('end',()=>ok(d));r.on('error',()=>ok('{}'))});
const CORS={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
const out=(res,c,o)=>{res.writeHead(c,{'Content-Type':'application/json',...CORS});res.end(JSON.stringify(o))};
http.createServer(async(req,res)=>{const u=req.url.split('?')[0];
 if(req.method==='OPTIONS')return out(res,204,{});
 if(u==='/health')return out(res,200,{jashpi:true,bot:!!T,twilio:!!(TW.s&&TW.t&&TW.f)});
 if(u==='/send'&&req.method==='POST'){
  const ip=(req.headers['x-forwarded-for']||req.socket.remoteAddress||'').split(',')[0].trim();
  if(rl('s'+ip,30,60000))return out(res,429,{ok:false,error:'rate'});
  try{const{chat_id,text}=JSON.parse(await body(req));
   if(!/^-?\d{4,15}$/.test(String(chat_id))||typeof text!=='string'||text.length>1000||!/^(🚨 )?JASHPI/.test(text))return out(res,400,{ok:false});
   const r=await tg('sendMessage',{chat_id,text});return out(res,r.ok?200:502,await r.json())}catch(e){return out(res,400,{ok:false})}}
 if(u==='/manifest.json'){res.writeHead(200,{'Content-Type':'application/manifest+json',...CORS});return res.end(JSON.stringify({id:'/',name:'JASHPI AI',short_name:'JASHPI',start_url:'/',scope:'/',display:'standalone',background_color:'#070b1a',theme_color:'#070b1a',icons:[{src:'/icon-192.webp',sizes:'192x192',type:'image/webp',purpose:'any'},{src:'/icon-512.webp',sizes:'512x512',type:'image/webp',purpose:'any'}],shortcuts:[['SOS Now','sos'],['Silent SOS','silent'],['Start Listening','listen'],['Fake Call','fake']].map(([n,a])=>({name:n,short_name:n,url:'/?a='+a}))}))}
 if(u==='/icon-192.webp'||u==='/icon-512.webp'){try{const h=fs.readFileSync(path.join(__dirname,'index.html'),'utf8'),m=h.match(/window\.IC=\{b:"data:image\/webp;base64,([^"]+)",s:"data:image\/webp;base64,([^"]+)"\}/);res.writeHead(200,{'Content-Type':'image/webp','Cache-Control':'max-age=86400'});return res.end(Buffer.from(m[u.includes('512')?1:2],'base64'))}catch(e){return out(res,404,{})}}
 if(u==='/track'&&req.method==='POST'){const ip=(req.headers['x-forwarded-for']||req.socket.remoteAddress||'').split(',')[0].trim();if(rl('t'+ip,80,60000))return out(res,429,{ok:false});
  try{const b=JSON.parse(await body(req));if(!/^[a-z0-9]{8,16}$/.test(b.id)||!isFinite(b.lat)||!isFinite(b.lng))return out(res,400,{ok:false});const n=Date.now();for(const[k,x]of TRK)if(n-x.t>216e5)TRK.delete(k);if(TRK.size>2000)return out(res,503,{ok:false});TRK.set(b.id,{lat:+b.lat,lng:+b.lng,name:String(b.name||'').slice(0,40),t:n});return out(res,200,{ok:true})}catch(e){return out(res,400,{ok:false})}}
 if(u.startsWith('/track/')){const x=TRK.get(u.slice(7));return x?out(res,200,{ok:true,...x}):out(res,404,{ok:false})}
 if(u.startsWith('/t/')){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});return res.end(TRKPAGE)}
 if(u==='/alert'&&req.method==='POST'){
  if(!TW.s||!TW.t||!TW.f)return out(res,501,{ok:false,error:'Twilio not set on server'});
  const ip=(req.headers['x-forwarded-for']||req.socket.remoteAddress||'').split(',')[0].trim();
  if(rl('a'+ip,6,3600000))return out(res,429,{ok:false,error:'too many alerts'});
  try{const b=JSON.parse(await body(req)),to=[...new Set((b.to||[]).map(String))].filter(x=>/^\d{8,15}$/.test(x)).slice(0,5),d=new Date().toISOString().slice(0,10);if(day.d!==d){day.d=d;day.n=0}
   if(!to.length||typeof b.text!=='string'||!/^(🚨 )?JASHPI/.test(b.text)||b.text.length>600||day.n+to.length>MAXD)return out(res,400,{ok:false,error:'rejected or daily limit'});
   day.n+=to.length;let sms=0,call=false;
   await Promise.all(to.map(async t=>{try{const r=await twPost('Messages',{To:'+'+t,From:TW.f,Body:b.text});if(r.ok)sms++}catch(e){}}));
   if(/^\d{8,15}$/.test(String(b.call||''))){const say=String(b.say||b.text).slice(0,300).replace(/[<>&]/g,' ');try{const r=await twPost('Calls',{To:'+'+b.call,From:TW.f,Twiml:`<Response><Pause length="1"/><Say voice="alice" language="en-IN">${say}</Say><Pause length="1"/><Say voice="alice" language="en-IN">${say}</Say></Response>`});call=r.ok}catch(e){}}
   return out(res,200,{ok:true,sms,call})}catch(e){return out(res,400,{ok:false,error:'bad request'})}}
 if(u==='/tg'&&req.method==='POST'){ // Telegram webhook: any message to the bot -> replies with the sender's chat ID
  if(req.headers['x-telegram-bot-api-secret-token']!==SEC)return out(res,403,{});
  try{const m=JSON.parse(await body(req)).message;if(m&&m.chat)await tg('sendMessage',{chat_id:m.chat.id,text:`JASHPI AI\nYour Telegram chat ID: ${m.chat.id}\n\nPaste it in the app > Settings > "Your Telegram chat ID", or give it to the person who should receive alerts.`})}catch(e){}
  return out(res,200,{})}
 const f=u==='/'||u==='/index.html'?'index.html':u==='/sw.js'?'sw.js':null,p=f&&path.join(__dirname,f);
 if(p&&fs.existsSync(p)){res.writeHead(200,{'Content-Type':f.endsWith('.js')?'text/javascript':'text/html; charset=utf-8','Cache-Control':'no-cache'});return fs.createReadStream(p).pipe(res)}
 out(res,404,{ok:false})
}).listen(PORT,'0.0.0.0',async()=>{console.log('JASHPI running on',PORT);
 const h=process.env.PUBLIC_URL||process.env.RENDER_EXTERNAL_URL||(process.env.RAILWAY_PUBLIC_DOMAIN&&'https://'+process.env.RAILWAY_PUBLIC_DOMAIN)||(process.env.REPLIT_DOMAINS&&'https://'+process.env.REPLIT_DOMAINS.split(',')[0]);
 if(T&&h){try{const r=await tg('setWebhook',{url:h.replace(/\/$/,'')+'/tg',secret_token:SEC});console.log('Telegram webhook set:',(await r.json()).ok)}catch(e){console.log('webhook error',e.message)}}});
