// Adapted from Lokal. Copyright (c) 2026 sipbuu. MIT; see providers/third-party/LICENSE.lokal.
(function(bridge, config) {
  'use strict';
  const stringify = JSON.stringify, parse = JSON.parse;
  const callbacks = new Map(); let nextCallback = 0, extension;
  function pack(value, seen = []) {
    if (value == null) return null;
    if (typeof value === 'function') { const id = ++nextCallback; callbacks.set(id, value); return {__callback:id}; }
    if (value instanceof ArrayBuffer) return {__bytes:Array.from(new Uint8Array(value)), __buffer:true};
    if (ArrayBuffer.isView(value)) return {__bytes:Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))};
    if (typeof value !== 'object') return value;
    if (seen.length > 100 || seen.includes(value)) throw Error('Cyclic or deeply nested value');
    if (Array.isArray(value)) return value.map(v => pack(v, [...seen,value]));
    const out = Object.create(null);
    for (const k of Object.keys(value)) out[k] = pack(value[k], [...seen,value]);
    return out;
  }
  function unpack(value) {
    if (!value || typeof value !== 'object') return value;
    if (value.__bytes) { const bytes = new Uint8Array(value.__bytes); return value.__buffer ? bytes.buffer : bytes; }
    if (value.__callback) return (...args) => call('callback', [value.__callback,args]);
    if (Array.isArray(value)) return value.map(unpack);
    const out = {}; for (const k of Object.keys(value)) Object.defineProperty(out,k,{value:unpack(value[k]),writable:true,enumerable:true,configurable:true}); return out;
  }
  function call(method, args = []) {
    const firstCallback=nextCallback; let pending;
    try {
      let result=parse(bridge(method,stringify(pack(args))));
      for (;;) {
        if(result.failure)throw Error(result.failure);
        pending=result.pending;
        for(const event of result.events || []) {
          const callback=callbacks.get(event.callback);
          if(callback)callback(...unpack(event.args));
        }
        if(!pending)return unpack(result.value);
        result=parse(bridge('work.poll',stringify([pending])));
      }
    } catch(error) {
      if(pending) { try { bridge('work.cancel',stringify([pending])); } catch {} }
      throw error;
    } finally {
      for(let id=firstCallback+1;id<=nextCallback;id++)callbacks.delete(id);
    }
  }
  function goJSON(value) {
    function convert(v, seen = []) {
      if (v == null) return null;
      if (v instanceof Uint8Array) return call('base64Bytes',[Array.from(v)]);
      if (typeof v === 'number' && !Number.isFinite(v)) throw Error('Unsupported JSON number');
      if (typeof v !== 'object') { if (typeof v === 'function' || typeof v === 'bigint' || typeof v === 'symbol') throw Error('Unsupported JSON value'); return v; }
      if (seen.includes(v) || seen.length > 100) throw Error('Cyclic JSON');
      if (v instanceof Date) return v.toISOString();
      if (Array.isArray(v)) return v.map(x => convert(x,[...seen,v]));
      const out = Object.create(null); for (const k of Object.keys(v).sort()) out[k] = convert(v[k],[...seen,v]); return out;
    }
    return stringify(convert(value)).replace(/[<>&\u2028\u2029]/g, c => '\\u'+c.charCodeAt(0).toString(16).padStart(4,'0'));
  }
  globalThis.registerExtension = value => { extension = value; globalThis.extension = value; };
  globalThis.settings = unpack(config.settings || {});
  if (config.permissions.storage) {
    globalThis.storage = {
      get: (key,fallback) => { const r=call('storage.get',[String(key)]); return r.found ? r.value : fallback; },
      set: (key,value) => call('storage.set',[String(key),parse(goJSON(value))]).success,
      remove: key => call('storage.remove',[String(key)]).success,
      clear: () => call('storage.clear').success,
    };
    globalThis.credentials = {
      get: (key,fallback) => { const r=call('credentials.get',[String(key)]); return r.found ? r.value : fallback; },
      store: (key,value) => call('credentials.set',[String(key),parse(goJSON(value))]),
      remove: key => call('credentials.remove',[String(key)]).success,
      has: key => call('credentials.get',[String(key)]).found,
    };
  }
  function request(url,method,body,headers,asFetch) {
    body = body == null ? '' : typeof body === 'object' ? goJSON(body) : String(body);
    const r = call('http.request',[String(url),{method,body,headers:headers || {},asFetch:!!asFetch}]);
    if (!asFetch) return r;
    r.text = () => r.body || '';
    r.json = () => { try { return parse(r.body); } catch {} };
    r.arrayBuffer = () => call('decodeBase64Bytes',[r.binary || '']);
    return r;
  }
  if (config.permissions.network.length) {
    globalThis.http = {
      get:(url,headers)=>request(url,'GET',null,headers), delete:(url,headers)=>request(url,'DELETE',null,headers),
      post:(url,body,headers)=>request(url,'POST',body,headers), put:(url,body,headers)=>request(url,'PUT',body,headers),
      patch:(url,body,headers)=>request(url,'PATCH',body,headers),
      request:(url,opts={})=>request(url,opts.method || 'GET',opts.body,opts.headers),
      clearCookies:()=>call('http.clearCookies'),
    };
    globalThis.fetch = (url,opts={})=>request(url,opts.method || 'GET',opts.body,opts.headers,true);
    globalThis.auth = {};
    for (const name of ['openAuthUrl','getAuthCode','setAuthCode','getTokens','isAuthenticated','clearAuth','getPKCE','startOAuthWithPKCE','exchangeCodeWithPKCE']) auth[name]=(...args)=>call('auth.'+name,args);
    auth.generatePKCE = length => call('auth.generatePKCE',[Number.isFinite(Number(length)) ? Math.max(43,Math.min(128,Math.trunc(Number(length)))) : 64]);
    if (config.signedSession) {
      globalThis.session = {};
      for (const name of ['status','clear','completeGrant']) session[name]=(...args)=>call('session.'+name,args);
      session.signedFetch = (method,path,body,headers)=>call('session.signedFetch',[method,path,body == null ? '' : typeof body === 'object' ? goJSON(body) : String(body),headers || {}]);
    }
  }
  if (config.permissions.file) {
    globalThis.file = {};
    for (const name of ['exists','read','write','delete','copy','move','getSize','readBytes','writeBytes','download','downloadSegments','transformPatternedBlocks']) file[name]=(...args)=>call('file.'+name,args);
    globalThis.ffmpeg = {getInfo:path=>call('ffmpeg.getInfo',[path]), convert:(...args)=>call('ffmpeg.convert',args)};
    if (config.rawFfmpeg) ffmpeg.execute=()=>({success:false,error:'Raw FFmpeg execution is disabled; use ffmpeg.convert'});
  }
  globalThis.log = {}; for (const level of ['debug','info','warn','error']) log[level]=(...args)=>call('log',[level,args.map(v=>typeof v === 'object' ? '<object>' : String(v)).slice(0,8)]);
  globalThis.console={log:log.info,warn:log.warn,error:log.error};
  globalThis.matching={compareStrings:(a,b)=>call('matching.compareStrings',[String(a),String(b)]),compareDuration:(a,b,t=3000)=>Math.abs(Number(a)-Number(b))<=t,normalizeString:v=>call('matching.normalizeString',[String(v)])};
  globalThis.gobackend = {}; for (const name of ['sanitizeFilename','buildFilename','getLocalTime','getAudioQuality','getLyricsLRC','checkISRCExists','addToISRCIndex']) gobackend[name]=(...args)=>call('gobackend.'+name,args);
  globalThis.utils = {};
  for (const name of ['isDownloadCancelled','isRequestCancelled','randomUserAgent','appVersion','appUserAgent','setDownloadStatus','getResolutionRemainingMs','encrypt','decrypt','generateKey','encryptBlockCipher','decryptBlockCipher','decryptCTRSegments','base64Encode','base64Decode','md5','sha256','hmacSHA256','hmacSHA256Base64','hmacSHA1','sleep']) utils[name]=(...args)=>call('utils.'+name,args);
  utils.parseJSON=v=>{try{return parse(goJSON(parse(String(v))));}catch{}};
  utils.stringifyJSON=v=>{try{return goJSON(v);}catch{return '';}};
  globalThis.btoa=v=>utils.base64Encode(String(v)); globalThis.atob=v=>utils.base64Decode(String(v));
  globalThis.TextEncoder=function(){this.encoding='utf-8';this.encode=v=>call('encode',[String(v ?? '')]);this.encodeInto=(value,destination)=>{let read=0,written=0;for(const character of String(value ?? '')){const bytes=this.encode(character);if(written+bytes.length>destination.length)break;destination.set(bytes,written);written+=bytes.length;read+=character.length;}return {read,written};};};
  globalThis.TextDecoder=function(encoding='utf-8'){if(!/^utf-?8$/i.test(encoding))throw Error('Unsupported text encoding');this.encoding='utf-8';this.decode=v=>typeof v==='string'?v:call('decode',[Array.from(v instanceof ArrayBuffer?new Uint8Array(v):ArrayBuffer.isView(v)?new Uint8Array(v.buffer,v.byteOffset,v.byteLength):v || [])]);};
  globalThis.URL=function(input,base){const parsed=call('url.parse',[String(input),base == null ? null : String(base)]);Object.assign(this,parsed);this.searchParams=new URLSearchParams(parsed.search,encoded=>{const before=parsed.href.split('?')[0],hash=parsed.hash || '';parsed.search=encoded?'?'+encoded:'';parsed.href=before+parsed.search+hash;this.search=parsed.search;this.href=parsed.href;});this.toString=this.toJSON=()=>parsed.href;};
  globalThis.URLSearchParams=function(init='',onChange){
    const entries=call('url.query',[init]);
    const changed=()=>onChange?.(call('url.encode',[entries]));
    this.get=k=>entries.find(e=>e[0]===String(k))?.[1] ?? null;
    this.getAll=k=>entries.filter(e=>e[0]===String(k)).map(e=>e[1]);this.has=k=>entries.some(e=>e[0]===String(k));
    this.append=(k,v)=>{entries.push([String(k),String(v)]);changed();};
    this.delete=k=>{for(let i=entries.length-1;i>=0;i--)if(entries[i][0]===String(k))entries.splice(i,1);changed();};
    this.set=(k,v)=>{for(let i=entries.length-1;i>=0;i--)if(entries[i][0]===String(k))entries.splice(i,1);entries.push([String(k),String(v)]);changed();};this.toString=()=>call('url.encode',[entries]);
  };
  return {
    registered:()=>!!extension && typeof extension==='object',
    methods:()=>stringify([...new Set([...Object.keys(extension || {}).filter(k=>typeof extension[k]==='function'),...(config.signedSession?['completeGrant']:[])])]),
    callback:(id,args)=>{const fn=callbacks.get(id);if(fn)return fn(...unpack(parse(args)));},
    invoke:(name,args)=>{
      callbacks.clear(); nextCallback=0;
      args=unpack(parse(args));
      if(name==='getPlaylist' && !extension.getPlaylist)name='getAlbum';
      if(name==='handleUrl' && !extension.handleUrl && extension.handleURL)name='handleURL';
      if(name==='download')args.splice(3,0,value=>call('progress',[Math.max(0,Math.min(100,Number(value)||0))]));
      if(name==='postProcessV2' && !extension.postProcessV2){name='postProcess';args[0]=args[0].path;}
      const fn=extension[name] || globalThis[name];
      const result=typeof fn==='function'?fn.apply(extension,args):name==='completeGrant' && config.signedSession?session.completeGrant(...args):null;
      if(result && typeof result.then==='function')return result.then(v=>stringify(pack(v)));
      return stringify(pack(result));
    },
  };
})
