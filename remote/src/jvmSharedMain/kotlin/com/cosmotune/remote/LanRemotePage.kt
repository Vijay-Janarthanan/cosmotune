package com.cosmotune.remote

/** Self-contained local controls; no CDNs, telemetry, account forms or media addresses. */
internal object LanRemotePage {
    val html = """<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cosmotune remote</title><style>
:root{color-scheme:dark;font:16px system-ui,sans-serif;background:#171311;color:#f8eee8}*{box-sizing:border-box}
body{margin:0;padding:clamp(20px,6vw,56px);max-width:740px;margin-inline:auto}h1{font-size:clamp(30px,6vw,46px);letter-spacing:-.04em;margin:12px 0}
p{line-height:1.5;color:#cabeb6}button,input{font:inherit;border:1px solid #62544c;border-radius:12px;padding:14px;background:#302720;color:inherit}
button{cursor:pointer;min-height:48px}button:hover{background:#49372b}button:focus-visible,input:focus-visible{outline:3px solid #eeb798;outline-offset:3px}
button:disabled{opacity:.5;cursor:wait}.controls{display:flex;flex-wrap:wrap;gap:10px;margin:24px 0}.primary{background:#eeb798;color:#24140d;border-color:transparent}button.primary:hover{background:#ffd2b8;color:#24140d}
input[type=range]{padding:8px 0;accent-color:#eeb798;width:100%}label{display:block;margin-top:24px}form{display:flex;gap:10px;margin-top:10px}input[type=search]{min-width:0;flex:1}
#songs,#queue{list-style:none;padding:0}#songs li,#queue li{border-top:1px solid #493e37;padding:12px 0;display:flex;align-items:center;gap:16px}#songs span,#queue span{flex:1;white-space:pre-line;overflow-wrap:anywhere}
button[aria-pressed=true]{background:#eeb798;color:#24140d;border-color:transparent}h2{font-size:20px;margin:32px 0 0}
#message{min-height:48px}small{color:#cabeb6}#artist{min-height:24px}</style>
<body><small>COSMOTUNE · LOCAL REMOTE</small><h1 id="title">Pair your browser</h1><p id="artist">Open the pairing link shown in the app on the same network.</p>
<p id="message" role="status" aria-live="polite"></p><div class="controls"><button data-action="PREVIOUS">Previous</button><button class="primary" data-action="TOGGLE" id="toggle">Play / pause</button><button data-action="NEXT">Next</button></div>
<div class="controls"><button data-action="SHUFFLE" id="shuffle" aria-pressed="false" hidden>Shuffle</button><button data-action="REPEAT" id="repeat" hidden>Repeat off</button><button data-action="LIKE" id="like" aria-pressed="false" hidden>Love</button></div>
<label for="seek">Position <output id="time">0:00</output></label><input id="seek" type="range" min="0" max="1" value="0" disabled>
<label for="volume">Volume</label><input id="volume" type="range" min="0" max="100" value="100" disabled>
<label for="query">Find music</label><form id="search"><input id="query" type="search" maxlength="300" placeholder="Title or artist"><button>Search</button></form><ul id="songs"></ul>
<section id="upnext" hidden><h2>Up next</h2><ul id="queue"></ul></section>
<p><small>Keep this page open to control the app. Stop the remote in Cosmotune to disconnect every browser.</small></p>
<script>
const el=id=>document.getElementById(id);let key=new URLSearchParams(location.hash.slice(1)).get('key');
if(key){sessionStorage.setItem('cosmotuneRemoteKey',key);history.replaceState(null,'',location.pathname)}else key=sessionStorage.getItem('cosmotuneRemoteKey');
let lastTrack=null,pending=false,paired=false,editing=false,lastState=null,statusError=false,refreshGeneration=0,pendingFocus=null;const message=text=>el('message').textContent=text;
function controls(){const active=document.activeElement;if(pending&&paired&&!pendingFocus&&active?.matches('button,input'))pendingFocus=active;document.querySelectorAll('button,#query').forEach(item=>item.disabled=!paired||pending);el('seek').disabled=!paired||pending||!lastState?.durationMs;el('volume').disabled=!paired||pending||lastState?.volume==null;if(!paired)pendingFocus=null;if(!pending&&paired&&pendingFocus){const restore=pendingFocus;pendingFocus=null;const target=restore.isConnected?restore:restore.dataset.queue?el('queue').querySelector('button'):null;if(document.activeElement===document.body&&target&&!target.disabled)target.focus({preventScroll:true})}}
async function request(path,body){let response;try{response=await fetch(path,{cache:'no-store',headers:{Authorization:'Bearer '+(key||''),...(body?{'Content-Type':'application/json'}:{})},method:body?'POST':'GET',body:body?JSON.stringify(body):undefined})}catch(_){throw new Error('Cannot reach Cosmotune. Keep the app and remote running on the same network.')}if(!response.ok){if(response.status===401){paired=false;controls()}throw new Error(response.status===401?'Pair this browser again using the current app link.':'The player could not finish this request.')}return response.json()}
async function action(value){if(pending||!paired)return;pending=true;controls();try{await request('/api/action',value);message('');await refresh();await loadQueue()}catch(error){message(error.message)}finally{pending=false;controls()}}
const stamp=ms=>{const seconds=Math.floor(ms/1000);return Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0')};
async function refresh(){if(document.hidden||editing)return;const generation=++refreshGeneration;try{const s=await request('/api/status');if(generation!==refreshGeneration)return;lastState=s;paired=true;if(statusError)message('');statusError=false;el('title').textContent=s.title;el('artist').textContent=s.artist;el('toggle').textContent=s.playing?'Pause':'Play';el('time').textContent=stamp(s.positionMs)+(s.durationMs?' / '+stamp(s.durationMs):'');el('seek').max=s.durationMs||1;el('seek').value=s.positionMs;el('volume').value=(s.volume??1)*100;
const mode=(id,value,show)=>{const b=el(id);b.hidden=value==null;if(value!=null)show(b,value)};
mode('shuffle',s.shuffle,(b,v)=>b.setAttribute('aria-pressed',String(v)));
mode('repeat',s.repeat,(b,v)=>b.textContent='Repeat '+({OFF:'off',ALL:'all',ONE:'one'})[v]);
mode('like',s.liked,(b,v)=>{b.setAttribute('aria-pressed',String(v));b.textContent=v?'Loved':'Love'});
controls();const track=s.title+'\n'+s.artist;if(track!==lastTrack){lastTrack=track;loadQueue()}}catch(error){if(generation!==refreshGeneration)return;paired=false;statusError=true;controls();message(error.message)}}
async function loadQueue(){if(!paired)return;try{const q=await request('/api/queue'),list=el('queue');list.replaceChildren();for(const item of q.items){const row=document.createElement('li'),label=document.createElement('span');label.textContent=item.title+'\n'+item.artist;row.append(label);if(item.index===q.current){const now=document.createElement('small');now.textContent='Now playing';row.append(now)}else{const button=document.createElement('button');button.textContent='Play';button.dataset.queue='1';button.setAttribute('aria-label','Play '+item.title);button.onclick=()=>action({action:'PLAY_QUEUE_INDEX',index:item.index});row.append(button)}list.append(row)}el('upnext').hidden=!q.items.length;controls()}catch(_){}}
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',()=>action({action:button.dataset.action})));
for(const id of ['seek','volume']){el(id).addEventListener('input',()=>editing=true);el(id).addEventListener('change',()=>{editing=false;action(id==='seek'?{action:'SEEK',positionMs:Number(el(id).value)}:{action:'VOLUME',volume:Number(el(id).value)/100})});el(id).addEventListener('blur',()=>editing=false)}
el('search').addEventListener('submit',async event=>{event.preventDefault();if(pending||!paired)return;pending=true;controls();try{const tracks=await request('/api/search?q='+encodeURIComponent(el('query').value));el('songs').replaceChildren();for(const track of tracks){const row=document.createElement('li'),label=document.createElement('span'),button=document.createElement('button');label.textContent=track.title+'\n'+track.artist;button.textContent='Play';button.setAttribute('aria-label','Play '+track.title);button.onclick=()=>action({action:'PLAY_TRACK',trackId:track.id});row.append(label,button);el('songs').append(row)}message(tracks.length?'':'No matching tracks in the host library.')}catch(error){message(error.message)}finally{pending=false;controls()}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh()});controls();refresh();setInterval(refresh,1500);
</script></body></html>"""
}
