const V='jashpi-v3',SHELL=['./','index.html'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
 const r=e.request,u=new URL(r.url);
 if(u.hostname==='image.pollinations.ai'){e.respondWith(caches.open(V).then(c=>c.match(r).then(m=>m||fetch(r).then(n=>{c.put(r,n.clone());return n}))));return}
 if(r.method!=='GET'||u.origin!==location.origin||/^\/(sos|track|t|info|subs|tg)(\/|$)/.test(u.pathname))return;
 e.respondWith(fetch(r).then(n=>{const c=n.clone();caches.open(V).then(x=>x.put(r.mode==='navigate'?'index.html':r,c));return n})
  .catch(()=>caches.match(r.mode==='navigate'?'index.html':r).then(m=>m||caches.match('index.html'))));
});