const $ = (s) => document.querySelector(s);
const DB_NAME = "novra-video-db", STORE = "videos";
let db, currentVideo = null, currentObjectUrl = null, toastTimer, pendingPosterId = null;
let currentNativePlaybackPath = null;
let searchRenderTimer = null;
let activePlaylistId = null;
// NOVRA_LIBRARY_V2
let currentCategory = "video";
let vaultOpen = false;
let currentPage = "home";
let currentAudio = null;
let currentAudioUrl = null;

// NOVRA MUSIC ENGINE
let musicBaseQueue = [];
let musicQueue = [];
let musicQueueIndex = -1;
let musicQueueSignature = "";
let musicShuffleEnabled = false;
let musicRepeatMode = "off"; // off | all | one
let currentAudioId = null;
let listeningTick = null;
let listeningLastTick = 0;
let listeningPersistAt = 0;
let musicBrowseMode = "tracks";
let musicGroupFilter = null;
let currentMusicVideo = false;
let currentMusicTitle = "";
let musicLoadToken = 0;
let logoTapCount = 0;
let logoTapTimer = null;
let vaultSetupMode = false;
let pendingVaultResolve = null;
let vaultPurpose = "browse";
let nativeMediaPlugin = null;
let nativeBackgroundPlayback = false;
let nativeBackgroundPlaying = false;
const nativeThumbnailCache = new Map();
const nativeThumbnailPending = new Set();
const DEVICE_MEDIA_EXCLUSIONS_KEY = "novra-device-media-hidden-v1";
const MEDIA_RENDER_BATCH = 72;
let mediaRenderLimit = MEDIA_RENDER_BATCH;
let nativeShareImportQueue = Promise.resolve();
let thumbnailGenerationQueue = Promise.resolve();

const rootDirPromise = navigator.storage?.getDirectory ? navigator.storage.getDirectory() : Promise.reject(new Error("المتصفح لا يدعم التخزين الخاص OPFS. استخدم Chrome حديثًا." ));

function toast(message) {
  const el = $("#toast"); el.textContent = message; el.classList.add("show");
  clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove("show"), 3200);
}
function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 3);

    req.onupgradeneeded = () => {
      const database = req.result;

      if (!database.objectStoreNames.contains(STORE)) {
        database.createObjectStore(STORE, {keyPath:"id"});
      }

      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath:"id"});
      }

      if (!database.objectStoreNames.contains("playlists")) {
        database.createObjectStore("playlists", {keyPath:"id"});
      }
    };

    req.onsuccess = () => {
      const database = req.result;
      database.onversionchange = () => database.close();
      resolve(database);
    };

    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new Error(
      "ترقية قاعدة البيانات متوقفة. أغلق أي نسخة قديمة من التطبيق ثم أعد فتحه."
    ));
  });
}
function dbRequest(mode, action, value) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode), store = tx.objectStore(STORE);
    let req;
    if (action === "all") req = store.getAll();
    else if (action === "put") req = store.put(value);
    else if (action === "delete") req = store.delete(value);
    else req = store.get(value);
    req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
  });
}
function dbBulkPut(records) {
  if (!records.length) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    for (const record of records) store.put(record);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("Media library update was aborted."));
  });
}

function playlistRequest(action, value) {
  return new Promise((resolve, reject) => {
    const mode = action === "all" || action === "get" ? "readonly" : "readwrite";
    const tx = db.transaction("playlists", mode);
    const store = tx.objectStore("playlists");
    let req;

    if (action === "all") req = store.getAll();
    else if (action === "put") req = store.put(value);
    else if (action === "delete") req = store.delete(value);
    else req = store.get(value);

    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    tx.onabort = () => reject(tx.error || new Error("Playlist transaction aborted."));
  });
}

function id() { return crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36)+Math.random().toString(36).slice(2); }
function safeName(name) { return (name || "video").replace(/[\\/:*?"<>|]/g,"_").slice(0,120); }
function formatBytes(bytes) { if (!bytes) return "0 MB"; const units=["B","KB","MB","GB"]; let n=bytes,i=0; while(n>=1024&&i<3){n/=1024;i++;} return `${n.toFixed(i>1?1:0)} ${units[i]}`; }
function formatTime(s) { if (!Number.isFinite(s)) return "—"; const h=Math.floor(s/3600),m=Math.floor(s%3600/60),sec=Math.floor(s%60); return h ? `${h}:${String(m).padStart(2,"0")}:${String(sec).padStart(2,"0")}` : `${m}:${String(sec).padStart(2,"0")}`; }
async function writeFile(file, filename, onProgress) {
  const root = await rootDirPromise;
  const handle = await root.getFileHandle(filename, {create:true});
  const writable = await handle.createWritable();

  let reader = null;
  let written = 0;
  let lastProgressAt = 0;
  let lastProgressValue = -1;

  try {
    if (file.stream) {
      reader = file.stream().getReader();

      while (true) {
        const {done, value} = await reader.read();
        if (done) break;

        await writable.write(value);
        written += value.byteLength;

        if (onProgress && file.size) {
          const percent = Math.min(99, Math.round(written / file.size * 100));
          const now = performance.now();

          if (
            percent === 99 ||
            percent - lastProgressValue >= 5 ||
            now - lastProgressAt >= 300
          ) {
            onProgress(percent);
            lastProgressAt = now;
            lastProgressValue = percent;
          }
        }
      }
    } else {
      await writable.write(file);
    }

    await writable.close();
    if (onProgress) onProgress(100);
  } catch (error) {
    if (reader) {
      try { await reader.cancel(); } catch {}
    }
    try { await writable.abort(); } catch {}
    try { await root.removeEntry(filename); } catch {}
    throw error;
  } finally {
    if (reader) {
      try { reader.releaseLock(); } catch {}
    }
  }
}

async function getFile(filename) {
  const root=await rootDirPromise, handle=await root.getFileHandle(filename); return handle.getFile();
}
async function makeThumb(file) {
  return new Promise(resolve => {
    const url=URL.createObjectURL(file);
    const v=document.createElement("video");
    let settled=false;
    let duration=0;
    let candidates=[];
    let index=0;
    let lastThumb="";

    const timer=setTimeout(()=>done(lastThumb),15000);

    const done=thumb=>{
      if(settled)return;
      settled=true;
      clearTimeout(timer);
      v.onloadedmetadata=v.onloadeddata=v.onseeked=v.onerror=null;
      try{v.pause();v.removeAttribute("src");v.load();}catch{}
      URL.revokeObjectURL(url);
      resolve({
        thumb:thumb||"",
        duration:Number.isFinite(duration)&&duration>0?duration:0
      });
    };

    const nextFrame=()=>{
      if(settled)return;

      if(index>=candidates.length){
        done(lastThumb);
        return;
      }

      const target=candidates[index++];

      try{
        v.currentTime=target;
      }catch{
        nextFrame();
      }
    };

    const capture=()=>{
      if(settled||v.readyState<2||!v.videoWidth||!v.videoHeight)return;

      try{
        const c=document.createElement("canvas");
        c.width=640;
        c.height=Math.max(1,Math.round(640*v.videoHeight/v.videoWidth));

        const ctx=c.getContext("2d");
        if(!ctx)return nextFrame();

        ctx.drawImage(v,0,0,c.width,c.height);

        const sample=document.createElement("canvas");
        sample.width=32;
        sample.height=32;

        const sc=sample.getContext("2d");
        sc.drawImage(c,0,0,32,32);

        const pixels=sc.getImageData(0,0,32,32).data;
        let brightness=0;

        for(let i=0;i<pixels.length;i+=16){
          brightness+=(pixels[i]+pixels[i+1]+pixels[i+2])/3;
        }

        brightness/=pixels.length/16;

        const thumb=c.toDataURL("image/jpeg",.78);
        lastThumb=thumb;

        if(brightness<5){
          nextFrame();
        }else{
          done(thumb);
        }
      }catch{
        nextFrame();
      }
    };

    v.preload="auto";
    v.muted=true;
    v.playsInline=true;

    v.onloadedmetadata=()=>{
      duration=Number.isFinite(v.duration)?v.duration:0;

      if(duration>0.2){
        candidates=[1,duration*.1,duration*.25,duration*.5,.2]
          .map(t=>Math.min(Math.max(.05,t),Math.max(.05,duration-.05)))
          .filter((t,i,a)=>a.findIndex(x=>Math.abs(x-t)<.05)===i);
      }else{
        candidates=[0];
      }

      nextFrame();
    };

    v.onloadeddata=()=>{
      if(!v.seeking&&v.readyState>=2&&candidates.length===1&&candidates[0]===0){
        capture();
      }
    };

    v.onseeked=capture;
    v.onerror=()=>done(lastThumb);

    v.src=url;
    v.load();
  });
}

async function generateAndSaveThumb(videoId,file) {
  try {
    const info=await makeThumb(file);
    const video=await dbRequest("readonly","get",videoId);
    if(!video)return;

    let changed=false;
    if(info.duration>0&&(!video.duration||video.duration<=0)){
      video.duration=info.duration;
      changed=true;
    }
    if(info.thumb&&!video.customThumb){
      video.thumb=info.thumb;
      changed=true;
    }
    if(changed){
      await dbRequest("readwrite","put",video);
      await render();
    }
  }catch(error){
    console.warn("Video metadata/thumbnail generation failed:",error);
  }
}

function scheduleVideoThumbnail(videoId, file) {
  const generate = () => generateAndSaveThumb(videoId, file);
  thumbnailGenerationQueue = thumbnailGenerationQueue.then(generate, generate);
  return thumbnailGenerationQueue;
}


function mediaKind(type, name) {
  const t = (type || "").toLowerCase();
  const n = (name || "").toLowerCase();

  // Prefer known extensions, while keeping OGG audio distinct from OGV video.
  if (/\.(mp4|webm|ogv|mov|m4v|mkv|avi|3gp)$/i.test(n)) return "video";
  if (/\.(mp3|m4a|aac|wav|flac|ogg|opus|oga|weba)$/i.test(n)) return "audio";

  if (t.startsWith("video/")) return "video";
  if (t.startsWith("audio/")) return "audio";

  return "";
}

function mediaTypeFor(file, kind) {
  const type = (file.type || "").toLowerCase();
  const ext = (file.name.match(/\.([^.]+)$/) || [])[1]?.toLowerCase();

  const videoTypes = {
    mp4: "video/mp4",
    m4v: "video/mp4",
    webm: "video/webm",
    ogv: "video/ogg",
    ogg: "video/ogg",
    mov: "video/quicktime",
    mkv: "video/x-matroska",
    avi: "video/x-msvideo",
    "3gp": "video/3gpp"
  };
  const audioTypes = {
    mp3: "audio/mpeg",
    m4a: "audio/mp4",
    aac: "audio/aac",
    wav: "audio/wav",
    flac: "audio/flac",
    ogg: "audio/ogg",
    oga: "audio/ogg",
    opus: "audio/ogg",
    weba: "audio/webm"
  };

  if (kind === "video" && !type.startsWith("video/")) {
    return videoTypes[ext] || "video/mp4";
  }
  if (kind === "audio" && !type.startsWith("audio/")) {
    return audioTypes[ext] || "audio/mpeg";
  }
  return file.type || (kind === "audio" ? "audio/mpeg" : "video/mp4");
}

function isVaultItem(item) {
  return item.vault === true;
}

async function passwordDigest(password, salt) {
  const material = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    {name:"PBKDF2", hash:"SHA-256", salt, iterations:310000},
    material, 256
  );
  return new Uint8Array(bits);
}

function bytesToHex(bytes) {
  return [...bytes].map(x => x.toString(16).padStart(2,"0")).join("");
}

function hexToBytes(hex) {
  if (!/^(?:[0-9a-f]{2})+$/i.test(hex || "")) throw new Error("بيانات كلمة المرور غير صالحة.");
  return new Uint8Array(hex.match(/.{2}/g).map(x => parseInt(x,16)));
}

async function createVaultPassword(password) {
  if (typeof password !== "string" || password.length < 8) {
    throw new Error("كلمة المرور يجب أن تكون 8 أحرف على الأقل.");
  }
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const digest = await passwordDigest(password, salt);
  localStorage.setItem("novra-vault-salt-v1", bytesToHex(salt));
  localStorage.setItem("novra-vault-hash-v1", bytesToHex(digest));
}

async function verifyVaultPassword(password) {
  const saltHex = localStorage.getItem("novra-vault-salt-v1");
  const hashHex = localStorage.getItem("novra-vault-hash-v1");
  if (!saltHex || !hashHex) return false;
  const actual = await passwordDigest(password, hexToBytes(saltHex));
  const expected = hexToBytes(hashHex);
  if (actual.length !== expected.length) return false;
  let diff = 0;
  for (let i=0; i<actual.length; i++) diff |= actual[i] ^ expected[i];
  return diff === 0;
}

function hasVaultPassword() {
  return !!(localStorage.getItem("novra-vault-salt-v1") &&
            localStorage.getItem("novra-vault-hash-v1"));
}

async function getAudioDuration(file) {
  return new Promise(resolve => {
    const url = URL.createObjectURL(file);
    const audio = document.createElement("audio");
    let finished = false;
    const done = value => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      audio.removeAttribute("src");
      audio.load();
      URL.revokeObjectURL(url);
      resolve(Number.isFinite(value) && value > 0 ? value : 0);
    };
    const timer = setTimeout(() => done(0), 10000);
    audio.preload = "metadata";
    audio.onloadedmetadata = () => done(audio.duration);
    audio.onerror = () => done(0);
    audio.src = url;
  });
}

async function repairExistingVideos() {
  const records = await dbRequest("readonly","all");
  for (const video of records) {
    if ((video.kind || "video") !== "video") continue;
    try {
      const file = await getFile(video.filename);
      const info = await makeThumb(file);
      let changed = false;
      if (info.duration > 0 && (!video.duration || video.duration <= 0)) {
        video.duration = info.duration;
        changed = true;
      }
      if (info.thumb && !video.customThumb) {
        video.thumb = info.thumb;
        changed = true;
      }
      if (changed) await dbRequest("readwrite","put",video);
    } catch(error) {
      console.warn("Could not repair video:",video.name,error);
    }
  }
}

async function makeCustomThumb(file) {
  if(!file || !file.type.startsWith("image/")) throw new Error("اختار ملف صورة صالح.");
  const url=URL.createObjectURL(file);
  try {
    const img=new Image();
    await new Promise((resolve,reject)=>{img.onload=resolve;img.onerror=reject;img.src=url;});
    const c=document.createElement("canvas"), scale=Math.min(1,640/img.width);
    c.width=Math.max(1,Math.round(img.width*scale));c.height=Math.max(1,Math.round(img.height*scale));
    c.getContext("2d").drawImage(img,0,0,c.width,c.height);
    return c.toDataURL("image/jpeg",.78);
  } finally { URL.revokeObjectURL(url); }
}



// NOVRA — exact duplicate detection using bounded memory.
async function filesHaveSameContent(left, right) {
  if (!left || !right || left.size !== right.size || left.size <= 0) {
    return false;
  }

  const CHUNK_SIZE = 1024 * 1024;

  for (let offset = 0; offset < left.size; offset += CHUNK_SIZE) {
    const end = Math.min(offset + CHUNK_SIZE, left.size);

    const [aBuffer, bBuffer] = await Promise.all([
      left.slice(offset, end).arrayBuffer(),
      right.slice(offset, end).arrayBuffer()
    ]);

    const a = new Uint8Array(aBuffer);
    const b = new Uint8Array(bBuffer);

    if (a.length !== b.length) return false;

    for (let i = 0; i < a.length; i++) {
      if (a[i] !== b[i]) return false;
    }
  }

  return true;
}

async function findDuplicateMedia(file, useVault) {
  if (!file || !Number.isFinite(file.size) || file.size <= 0) {
    return null;
  }

  const records = await dbRequest("readonly", "all");

  for (const record of records) {
    if (
      !record ||
      !record.filename ||
      record.size !== file.size ||
      Boolean(record.vault) !== Boolean(useVault)
    ) {
      continue;
    }

    try {
      const storedFile = await getFile(record.filename);

      if (await filesHaveSameContent(file, storedFile)) {
        return record;
      }
    } catch (error) {
      // A missing or unreadable old file must not block a new import.
      console.warn("Duplicate comparison skipped:", record.filename, error);
    }
  }

  return null;
}

async function inspectIncomingMedia(file) {
  let kind = mediaKind(file.type, file.name);
  let info = null;

  // Known audio extensions should not be sent through the video decoder.
  // This avoids long metadata timeouts for M4A/FLAC/OGG tracks.
  const knownAudio = /\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|weba)$/i.test(file.name);

  // Keep a guarded fallback for unusual files mislabeled as audio.
  if (kind === "audio" && !knownAudio && (file.type || "").toLowerCase().startsWith("audio/")) {
    try {
      const head = new Uint8Array(await file.slice(0, 32).arrayBuffer());
      const signature = String.fromCharCode(...head);
      const isEbml = head[0] === 0x1A && head[1] === 0x45 && head[2] === 0xDF && head[3] === 0xA3;
      const looksLikeVideoContainer = signature.includes("ftyp") || isEbml ||
        (signature.startsWith("RIFF") && signature.includes("AVI "));
      if (looksLikeVideoContainer) {
        const result = await makeThumb(new File([file], file.name, {
          type: isEbml ? "video/webm" : "video/mp4",
          lastModified: file.lastModified
        }));
        if (result.thumb) { kind = "video"; info = result; }
      }
    } catch (error) {
      console.warn("Unusual media inspection skipped:", error);
    }
  }

  if (kind === "video" && !info) info = await makeThumb(file);
  return { kind, info };
}

async function extractMp3Metadata(file) {
  const metadata = { cover: "", title: "", artist: "", album: "", year: "" };
  try {
    const bytes = new Uint8Array(
      await file.slice(0, Math.min(file.size, 8 * 1024 * 1024)).arrayBuffer()
    );
    if (bytes.length < 10 || bytes[0] !== 0x49 || bytes[1] !== 0x44 || bytes[2] !== 0x33) {
      return metadata;
    }

    const version = bytes[3];
    const tagSize =
      ((bytes[6] & 0x7f) << 21) |
      ((bytes[7] & 0x7f) << 14) |
      ((bytes[8] & 0x7f) << 7) |
      (bytes[9] & 0x7f);
    const end = Math.min(bytes.length, 10 + tagSize);
    const textFields = {
      TIT2: "title",
      TPE1: "artist",
      TALB: "album",
      TDRC: "year",
      TYER: "year"
    };

    const decodeText = (start, size) => {
      if (size < 2) return "";
      const encoding = bytes[start];
      const payload = bytes.slice(start + 1, start + size);
      let decoder;
      try {
        decoder = encoding === 1 ? new TextDecoder("utf-16")
          : encoding === 2 ? new TextDecoder("utf-16be")
          : encoding === 3 ? new TextDecoder("utf-8")
          : new TextDecoder("windows-1252");
      } catch {
        decoder = new TextDecoder();
      }
      return decoder.decode(payload).replace(/\u0000/g, "").trim();
    };

    let offset = 10;
    while (offset + 10 <= end) {
      const frameId = String.fromCharCode(
        bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]
      );
      if (!frameId.trim() || frameId.charCodeAt(0) === 0) break;

      let size = version === 4
        ? ((bytes[offset + 4] & 0x7f) << 21) |
          ((bytes[offset + 5] & 0x7f) << 14) |
          ((bytes[offset + 6] & 0x7f) << 7) |
          (bytes[offset + 7] & 0x7f)
        : (bytes[offset + 4] << 24) |
          (bytes[offset + 5] << 16) |
          (bytes[offset + 6] << 8) |
          bytes[offset + 7];

      if (!Number.isFinite(size) || size <= 0 || offset + 10 + size > end) break;
      const frameStart = offset + 10;

      if (textFields[frameId] && !metadata[textFields[frameId]]) {
        metadata[textFields[frameId]] = decodeText(frameStart, size);
      }

      if (frameId === "APIC" && !metadata.cover) {
        try {
          const encoding = bytes[frameStart];
          let pos = frameStart + 1;
          while (pos < frameStart + size && bytes[pos] !== 0) pos++;
          const mime = new TextDecoder().decode(bytes.slice(frameStart + 1, pos));
          pos++;
          if (pos < frameStart + size) pos++;
          if (encoding === 1 || encoding === 2) {
            while (pos + 1 < frameStart + size &&
              !(bytes[pos] === 0 && bytes[pos + 1] === 0)) pos += 2;
            pos += 2;
          } else {
            while (pos < frameStart + size && bytes[pos] !== 0) pos++;
            pos++;
          }

          if (pos < frameStart + size) {
            const imageBlob = new Blob(
              [bytes.slice(pos, frameStart + size)],
              { type: mime.startsWith("image/") ? mime : "image/jpeg" }
            );
            const objectUrl = URL.createObjectURL(imageBlob);
            try {
              const image = new Image();
              await new Promise((resolve, reject) => {
                image.onload = resolve;
                image.onerror = reject;
                image.src = objectUrl;
              });
              const scale = Math.min(1, 480 / Math.max(image.width, image.height));
              const canvas = document.createElement("canvas");
              canvas.width = Math.max(1, Math.round(image.width * scale));
              canvas.height = Math.max(1, Math.round(image.height * scale));
              const context = canvas.getContext("2d");
              if (context) {
                context.drawImage(image, 0, 0, canvas.width, canvas.height);
                metadata.cover = canvas.toDataURL("image/jpeg", 0.82);
              }
            } finally {
              URL.revokeObjectURL(objectUrl);
            }
          }
        } catch (error) {
          console.debug("MP3 artwork frame skipped:", error);
        }
      }

      offset += 10 + size;
    }
  } catch (error) {
    console.warn("MP3 metadata extraction skipped:", error);
  }
  return metadata;
}

async function importLocal(file, options = {}) {
  const useVault = $("#chooseVault")?.checked === true;
  showProgress(`تجهيز ${file.name}`, 2);

  if (useVault && !vaultOpen) {
    throw new Error("افتح الخزنة وأدخل كلمة المرور أولًا.");
  }

  // Check exact content before inspecting, generating thumbnails or writing.
  const duplicate = options.streaming
    ? null
    : await findDuplicateMedia(file, useVault);

  if (duplicate) {
    hideProgress();
    const label = duplicate.name || duplicate.originalName || file.name;
    toast(`الملف موجود بالفعل، لم تتم إعادة إضافته: ${label}`);
    return;
  }

  // Identify common media types without decoding the whole video before saving.
  // Thumbnail and duration extraction runs after the import, off the critical UI path.
  let actualKind = mediaKind(file.type, file.name);
  let mediaInfo = null;
  const knownAudio = /\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|weba)$/i.test(file.name);
  const needsFallbackInspection = !actualKind ||
    (!options.streaming && actualKind === "audio" && !knownAudio &&
      (file.type || "").toLowerCase().startsWith("audio/"));

  if (needsFallbackInspection) {
    try {
      const inspected = await inspectIncomingMedia(file);
      actualKind = inspected.kind;
      mediaInfo = inspected.info;
    } catch (error) {
      hideProgress();
      throw error;
    }
  }

  if (actualKind === "audio" &&
      !/\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|weba)$/i.test(file.name) &&
      !(file.type || "").toLowerCase().startsWith("audio/")) {
    hideProgress();
    throw new Error("صيغة الصوت غير مدعومة. جرّب MP3 أو M4A أو AAC أو WAV أو FLAC أو OGG أو OPUS.");
  }

  showProgress(`تجهيز بيانات ${file.name}`, 25);

  const audioMetadata = !options.streaming && actualKind === "audio" && /\.mp3$/i.test(file.name)
    ? await extractMp3Metadata(file)
    : null;
  const audioCover = audioMetadata?.cover || "";

  if (!actualKind) {
    throw new Error("نوع الملف غير مدعوم. اختر فيديو أو ملفًا صوتيًا معروفًا.");
  }

  const destination = useVault
    ? "vault"
    : ($("#importDestination")?.value || "auto");

  const kind = useVault || destination === "auto"
    ? actualKind
    : destination;

  const mediaId = id();
  const filename = `${mediaId}.media`;

  showProgress(`حفظ ${file.name}`, 40);

  try {
    await writeFile(
      file,
      filename,
      p => showProgress(`استيراد ${file.name}`, p)
    );

    const duration = options.streaming
      ? 0
      : actualKind === "video"
        ? (mediaInfo?.duration || 0)
        : await getAudioDuration(file);

    const record = {
      id: mediaId,
      name: safeName(audioMetadata?.title || file.name.replace(/\.[^.]+$/, "")),
      originalName: file.name,
      filename,
      size: file.size,
      type: mediaTypeFor(file, actualKind),
      kind,
      vault: useVault,
      duration,
      thumb: actualKind === "video" ? (mediaInfo?.thumb || "") : audioCover,
      artist: audioMetadata?.artist || "",
      album: audioMetadata?.album || "",
      year: audioMetadata?.year || "",
      created: Date.now(),
      lastPlayed: 0,
      position: 0,
      favorite: false
    };

    await dbRequest("readwrite", "put", record);

    hideProgress();

    toast(
      useVault
        ? "تم حفظ الملف داخل الخزنة."
        : kind === "audio"
          ? "تمت إضافة الملف إلى الموسيقى."
          : "تمت إضافة الفيديو إلى مكتبتك."
    );

    if (actualKind === "video" && !mediaInfo?.thumb && !options.streaming) {
      const schedule = () => scheduleVideoThumbnail(mediaId, file);
      if ("requestIdleCallback" in window) {
        window.requestIdleCallback(schedule, { timeout: 4000 });
      } else {
        setTimeout(schedule, 1200);
      }
    }

    if (useVault) currentCategory = "vault";
    return mediaId;
  } catch (error) {
    try {
      const root = await rootDirPromise;
      await root.removeEntry(filename);
    } catch {}

    hideProgress();
    throw error;
  }
}

async function syncDeviceMedia(kind, requestPermission = true, quiet = false) {
  if (!nativeMediaPlugin) {
    if (!quiet) toast("مزامنة ملفات الهاتف متاحة في تطبيق Android فقط.");
    return;
  }

  const button = $("#scanDeviceBtn");
  if (button && !quiet) {
    button.disabled = true;
    button.textContent = "جارٍ الفحص…";
  }

  try {
    if (!quiet && requestPermission) {
      toast(kind === "audio"
        ? "اسمح بالوصول إلى ملفات الصوت لإضافتها إلى مكتبتك."
        : "اسمح بالوصول إلى الفيديوهات لاكتشاف ملفات الهاتف.");
    }

    const access = requestPermission
      ? await nativeMediaPlugin.requestAccess({ kind })
      : await nativeMediaPlugin.checkAccess({ kind });

    if (!access?.granted) {
      if (!quiet) toast("لم يتم منح إذن الوصول. يمكنك تفعيله لاحقًا من إعدادات Android.");
      return;
    }

    const result = await nativeMediaPlugin.listMedia({ kind });
    const items = Array.isArray(result?.items) ? result.items : [];
    const ignored = new Set(JSON.parse(localStorage.getItem(DEVICE_MEDIA_EXCLUSIONS_KEY) || "[]"));
    const existing = await dbRequest("readonly", "all");
    const byId = new Map(existing.map(record => [String(record.id), record]));
    const recordsToWrite = [];
    let added = 0;
    let refreshed = 0;

    for (const item of items) {
      if (!item?.id || !item?.uri || ignored.has(String(item.id))) continue;

      const mediaId = String(item.id);
      const old = byId.get(mediaId);
      const dateAdded = Number(item.dateAdded) > 0 ? Number(item.dateAdded) * 1000 : Date.now();
      const duration = Number(item.durationMs) > 0
        ? Number(item.durationMs) / 1000
        : (old?.duration || 0);

      const record = {
        ...(old || {}),
        id: mediaId,
        name: old?.nameOverride ? old.name : String(item.title || item.name || "بدون اسم").replace(/\.[^.]+$/, ""),
        nameOverride: old?.nameOverride === true,
        originalName: String(item.name || item.title || "بدون اسم"),
        filename: old?.filename || null,
        nativeUri: String(item.uri),
        source: "android-media-store",
        kind,
        type: String(item.mimeType || (kind === "audio" ? "audio/*" : "video/*")),
        size: Number(item.size) || 0,
        duration,
        created: old?.created || dateAdded,
        artist: String(item.artist || old?.artist || ""),
        album: String(item.album || old?.album || ""),
        thumb: old?.thumb || "",
        position: Number(old?.position) || 0,
        favorite: old?.favorite === true,
        vault: false,
        lastPlayed: Number(old?.lastPlayed) || 0
      };

      const changed = !old ||
        old.nativeUri !== record.nativeUri ||
        old.name !== record.name ||
        old.type !== record.type ||
        Number(old.size) !== record.size ||
        Number(old.duration) !== record.duration ||
        old.artist !== record.artist ||
        old.album !== record.album;

      if (changed) {
        recordsToWrite.push(record);
        if (old) refreshed++;
        else added++;
      }
    }

    await dbBulkPut(recordsToWrite);
    await render();

    if (!quiet) {
      toast(`تم تحديث المكتبة: ${added} جديد، ${refreshed} تم تحديثه من ملفات الهاتف.`);
    }
  } catch (error) {
    console.error("NOVRA device media sync failed:", error);
    if (!quiet) toast(error?.message || "تعذر فحص ملفات الهاتف.");
  } finally {
    if (button && !quiet) {
      button.disabled = false;
      button.textContent = "مزامنة الجهاز";
    }
  }
}

let lastNativeMediaRefreshAt = 0;
async function refreshNativeMediaIfAllowed() {
  if (!nativeMediaPlugin || Date.now() - lastNativeMediaRefreshAt < 45000) return;
  lastNativeMediaRefreshAt = Date.now();
  for (const kind of ["video", "audio"]) {
    try {
      const access = await nativeMediaPlugin.checkAccess({ kind });
      if (access?.granted) await syncDeviceMedia(kind, false, true);
    } catch (error) {
      console.debug("NOVRA background media refresh skipped:", kind, error);
    }
  }
}

async function getPendingSharedFiles() {
  return new Promise((resolve, reject) => {
    const tx = db.transaction("shared-files", "readonly");
    const req = tx.objectStore("shared-files").getAll();

    req.onsuccess = () => resolve(req.result || []);
    req.onerror = () => reject(req.error);
  });
}

async function deletePendingSharedFile(fileId) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction("shared-files", "readwrite");
    tx.objectStore("shared-files").delete(fileId);

    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("تعذر حذف الملف المؤقت."));
  });
}

async function importSharedFiles() {
  let pending = [];

  try {
    pending = await getPendingSharedFiles();
  } catch (error) {
    console.error("Shared files read error:", error);
    toast("تعذر قراءة الملفات المستلمة.");
    return;
  }

  const params = new URLSearchParams(location.search);
  const wasShared = params.has("shared");

  if (!pending.length) {
    if (wasShared) toast("لم يتم استلام أي ملفات.");
  } else {
    let imported = 0;
    let failed = 0;

    for (const item of pending) {
      try {
        if (!item.blob || !item.name) {
          throw new Error("بيانات الملف المستلم غير مكتملة.");
        }

        const file = new File(
          [item.blob],
          item.name,
          {
            type: item.type || item.blob.type || "",
            lastModified: item.lastModified || Date.now()
          }
        );

        await importLocal(file);
        await deletePendingSharedFile(item.id);
        imported++;

      } catch (error) {
        failed++;
        console.error("Shared import failed:", item.name, error);
      }
    }

    if (imported && failed) {
      toast(`تم استيراد ${imported} ملف، وتعذر استيراد ${failed}. الملفات المتعذرة ما زالت محفوظة مؤقتًا.`);
    } else if (failed) {
      toast(`تعذر استيراد ${failed} ملف. لم نحذف الملفات المستلمة.`);
    }
  }

  if (wasShared) {
    params.delete("shared");

    const query = params.toString();
    const cleanURL =
      location.pathname +
      (query ? "?" + query : "") +
      location.hash;

    history.replaceState({}, "", cleanURL);
  }
}


async function importNativeSharedFiles() {
  if (!nativeMediaPlugin?.consumeSharedFiles) return;

  const processPendingShares = async () => {
  try {
    const shared = await nativeMediaPlugin.consumeSharedFiles();
    const items = Array.isArray(shared?.items) ? shared.items : [];
    let imported = 0;
    let failed = 0;

    for (const item of items) {
      try {
        const localUrl = window.Capacitor?.convertFileSrc?.(item.path);
        if (!localUrl) throw new Error("تعذر فتح مسار الملف المستلم.");
        const response = await fetch(localUrl);
        if (!response.ok || !response.body) throw new Error("تعذر قراءة الملف المستلم.");
        const file = {
          name: item.name || "shared-media",
          type: item.type || response.headers.get("content-type") || "",
          size: Number(item.size) || Number(response.headers.get("content-length")) || 0,
          lastModified: Date.now(),
          stream: () => response.body
        };
        if (!file.size) throw new Error("تعذر تحديد حجم الملف المستلم.");
        const importedId = await importLocal(file, { streaming: true });

        if (importedId && nativeMediaPlugin.getSharedMetadata) {
          try {
            const metadata = await nativeMediaPlugin.getSharedMetadata({
              path: item.path,
              kind: mediaKind(file.type, file.name) || "video"
            });
            const record = await dbRequest("readonly", "get", importedId);
            if (record && (metadata?.thumb || metadata?.duration > 0)) {
              if (metadata.thumb) record.thumb = "data:image/jpeg;base64," + metadata.thumb;
              if (metadata.duration > 0) record.duration = metadata.duration;
              await dbRequest("readwrite", "put", record);
            }
          } catch (metadataError) {
            console.debug("NOVRA shared media metadata skipped:", metadataError);
          }
        }

        await nativeMediaPlugin.deleteSharedFile({ path: item.path });
        imported++;
      } catch (error) {
        failed++;
        console.error("NOVRA native share import failed:", item?.name, error);
      }
    }

    if (imported) await render();

    if (failed) {
      toast(`تعذر استيراد ${failed} ملف. احتفظ التطبيق بالنسخ المستلمة لمحاولة أخرى.`);
    } else if (imported > 1) {
      toast(`تم استيراد ${imported} ملفات مشتركة إلى المكتبة.`);
    }

    const sharedText = String(shared?.text || "").trim();
    const urlMatch = sharedText.match(/https?:\/\/[^\s]+/i);
    if (urlMatch) {
      $("#urlInput").value = urlMatch[0].replace(/[),.؛،]+$/, "");
      openModal("addModal");
      toast("استلمت رابطًا من تطبيق آخر. راجعه ثم اضغط استيراد الرابط.");
    } else if (sharedText && !items.length) {
      toast("استلمت نصًا من تطبيق آخر، لكن التطبيق يحتاج رابط ملف فيديو أو صوت مباشر.");
    }
  } catch (error) {
    console.error("NOVRA share receiver failed:", error);
    toast("تعذر استقبال المشاركة. حاول إرسال الملف مرة أخرى.");
  }
  };

  nativeShareImportQueue = nativeShareImportQueue.then(
    processPendingShares,
    processPendingShares
  );
  return nativeShareImportQueue;
}

async function importUrl() {
  const url = $("#urlInput").value.trim();
  if (!/^https?:\/\//i.test(url)) {
    toast("اكتب رابطًا يبدأ بـ http أو https.");
    return;
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(url);
  } catch {
    toast("الرابط غير صالح.");
    return;
  }

  const host = parsedUrl.hostname.toLowerCase().replace(/\.$/, "");
  if (host === "xnxx.com" || host.endsWith(".xnxx.com")) {
    toast("هذا الموقع غير مدعوم.");
    return;
  }

  const useVault = $("#chooseVault")?.checked === true;
  if (useVault && !vaultOpen) {
    toast("افتح الخزنة وأدخل كلمة المرور أولًا.");
    return;
  }

  $("#importUrl").disabled = true;
  showProgress("فحص الرابط...", 0);

  let filename = null;
  let saved = false;

  try {
    // First try the existing direct-media importer.
    let response = await fetch(`/api/proxy?url=${encodeURIComponent(url)}`);
    let pageDownload = false;

    // If it is a webpage rather than a direct media file,
    // use the separate yt-dlp endpoint.
    if (!response.ok) {
      response = await fetch("/api/page-download", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({url})
      });
      pageDownload = true;
    }

    if (!response.ok) {
      const body = await response.text();
      let message = body;
      try {
        const parsed = JSON.parse(body);
        message = parsed.error || body;
      } catch {}
      throw new Error(message || `تعذر الاستيراد (${response.status})`);
    }

    const type = (response.headers.get("content-type") || "")
      .split(";")[0].toLowerCase();

    let rawName = "";
    if (pageDownload) {
      const disposition = response.headers.get("content-disposition") || "";
      const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i);
      const plainName = disposition.match(/filename="?([^";]+)"?/i);

      if (encodedName) {
        try { rawName = decodeURIComponent(encodedName[1]); } catch {}
      } else if (plainName) {
        rawName = plainName[1];
      }
    } else {
      rawName = parsedUrl.pathname.split("/").pop() || "";
      try { rawName = decodeURIComponent(rawName); } catch {}
    }

    const kind = mediaKind(type, rawName || parsedUrl.pathname);
    let finalKind = kind;

    if (!finalKind && !pageDownload) {
      const pathKind = mediaKind("", parsedUrl.pathname);
      if (type === "application/octet-stream" ||
          type === "binary/octet-stream") {
        finalKind = pathKind;
      }
    }

    if (!finalKind && !pageDownload) {
      throw new Error("الرابط لا يشير إلى ملف فيديو أو صوت مدعوم.");
    }

    if (pageDownload && finalKind !== "video") {
      throw new Error("الصفحة لم تُنتج ملف فيديو مدعومًا.");
    }

    if (!finalKind) {
      throw new Error("تعذر تحديد نوع الملف.");
    }

    const actualKind = finalKind;
    const destination = useVault
      ? "vault"
      : ($("#importDestination")?.value || "auto");

    if (!useVault && destination !== "auto") {
      finalKind = destination;
    }

    const mediaId = id();
    filename = `${mediaId}.media`;

    const root = await rootDirPromise;
    const handle = await root.getFileHandle(filename, {create: true});
    const writable = await handle.createWritable();

    const total = Number(response.headers.get("content-length") || 0);
    let received = 0;

    try {
      if (response.body) {
        const reader = response.body.getReader();

        while (true) {
          const {done, value} = await reader.read();
          if (done) break;

          await writable.write(value);
          received += value.byteLength;

          showProgress(
            pageDownload ? "تنزيل الفيديو من الصفحة" : "تنزيل الوسائط",
            total ? Math.min(99, Math.round(received / total * 100)) : 0
          );
        }
      } else {
        const blob = await response.blob();
        received = blob.size;
        await writable.write(blob);
      }

      await writable.close();
    } catch (error) {
      try { await writable.abort(); } catch {}
      try { await root.removeEntry(filename); } catch {}
      throw error;
    }

    const file = await getFile(filename);

    // URL downloads are checked too, before creating a library record.
    const duplicate = await findDuplicateMedia(file, useVault);

    if (duplicate) {
      try {
        await root.removeEntry(filename);
      } catch {}

      filename = null;
      hideProgress();
      $("#urlInput").value = "";
      closeModal("addModal");

      const label = duplicate.name || duplicate.originalName || "الملف";
      toast(`الملف موجود بالفعل، لم تتم إعادة إضافته: ${label}`);

      await render();
      return;
    }

    if (!rawName) {
      rawName = finalKind === "audio" ? "صوت" : "فيديو";
    }

    const name = safeName(rawName.replace(/\.[^.]+$/, ""));
    const duration = actualKind === "audio"
      ? await getAudioDuration(file)
      : 0;

    await dbRequest("readwrite", "put", {
      id: mediaId,
      name,
      filename,
      size: file.size,
      type:mediaTypeFor(file, finalKind),
      kind: finalKind,
      vault: useVault,
      duration,
      thumb: "",
      created: Date.now(),
      lastPlayed: 0,
      position: 0,
      favorite: false
    });

    saved = true;
    hideProgress();
    $("#urlInput").value = "";
    closeModal("addModal");

    toast(useVault
      ? "تم حفظ الملف داخل الخزنة."
      : pageDownload
        ? "تم تنزيل الفيديو وحفظه في مكتبتك."
        : finalKind === "audio"
          ? "تم استيراد الصوت وحفظه محليًا."
          : "تم استيراد الفيديو وحفظه محليًا.");

    if (actualKind === "video") generateAndSaveThumb(mediaId, file);
    if (useVault) currentCategory = "vault";

    await render();
  } catch (error) {
    hideProgress();

    if (filename && !saved) {
      try {
        const root = await rootDirPromise;
        await root.removeEntry(filename);
      } catch {}
    }

    toast(error.message || "فشل استيراد الرابط.");
  } finally {
    $("#importUrl").disabled = false;
  }
}

function showProgress(label,pct){$("#progressBox").hidden=false;$("#progressLabel").textContent=label;$("#progressPercent").textContent=pct+"%";$("#progressFill").style.width=pct+"%";}
function hideProgress(){$("#progressBox").hidden=true;}
function openModal(id){$("#"+id).hidden=false;}
function closeModal(id){$("#"+id).hidden=true;}
function currentFilter(){return document.querySelector(".filter.active")?.dataset.filter||"all";}

async function renderPlaylistPanel(playlists) {
  const panel = $("#playlistPanel");
  if (!panel) return;

  panel.hidden = currentCategory !== "playlists";
  if (panel.hidden) return;

  const grid = $("#playlistGrid");
  const backRow = $("#playlistBackRow");
  const createRow = $("#playlistCreateRow");
  const activeName = $("#activePlaylistName");

  grid.innerHTML = "";
  grid.hidden = !!activePlaylistId;
  backRow.hidden = !activePlaylistId;
  createRow.hidden = !!activePlaylistId;

  let selected = playlists.find(p => p.id === activePlaylistId);

  if (activePlaylistId && !selected) {
    activePlaylistId = null;
    grid.hidden = false;
    backRow.hidden = true;
    createRow.hidden = false;
  }

  if (selected) activeName.textContent = selected.name;

  if (!activePlaylistId && !playlists.length) {
    const empty = document.createElement("p");
    empty.className = "playlist-empty";
    empty.textContent = "لسه مفيش قوائم تشغيل. أنشئ أول قائمة من هنا.";
    grid.append(empty);
  }

  for (const playlist of playlists) {
    const card = document.createElement("article");
    card.className = "playlist-card";

    const open = document.createElement("button");
    open.type = "button";
    open.className = "playlist-open";

    const symbol = document.createElement("span");
    symbol.className = "playlist-symbol";
    symbol.textContent = "♫";

    const info = document.createElement("span");
    info.className = "playlist-info";
    info.textContent = playlist.name + " · " + (playlist.mediaIds || []).length + " ملف";

    const arrow = document.createElement("span");
    arrow.className = "playlist-arrow";
    arrow.textContent = "←";

    open.append(symbol, info, arrow);
    open.onclick = () => {
      activePlaylistId = playlist.id;
      render();
    };

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "playlist-delete";
    remove.textContent = "حذف القائمة";
    remove.onclick = async () => {
      if (!confirm("حذف قائمة التشغيل؟ لن يتم حذف ملفات الفيديو أو الصوت.")) return;
      await playlistRequest("delete", playlist.id);
      if (activePlaylistId === playlist.id) activePlaylistId = null;
      toast("تم حذف قائمة التشغيل.");
      await render();
    };

    card.append(open, remove);
    grid.append(card);
  }
}

async function openPlaylistPicker(media) {
  const playlists = await playlistRequest("all");
  const wrap = $("#playlistPickerOptions");
  wrap.innerHTML = "";

  $("#playlistPickerMediaName").textContent = media.name || "ملف وسائط";

  if (!playlists.length) {
    const note = document.createElement("p");
    note.className = "muted";
    note.textContent = "أنشئ قائمة تشغيل أولًا من تبويب قوائم التشغيل.";
    wrap.append(note);
  }

  for (const playlist of playlists) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "playlist-pick-option";

    const included = (playlist.mediaIds || []).includes(media.id);
    button.textContent = (included ? "✓ إزالة من: " : "＋ إضافة إلى: ") + playlist.name;

    button.onclick = async () => {
      const current = await playlistRequest("get", playlist.id);
      if (!current) return;

      current.mediaIds = current.mediaIds || [];
      const index = current.mediaIds.indexOf(media.id);

      if (index >= 0) current.mediaIds.splice(index, 1);
      else current.mediaIds.push(media.id);

      await playlistRequest("put", current);
      toast(index >= 0 ? "تمت إزالة الملف من القائمة." : "تمت إضافة الملف إلى القائمة.");
      await render();
      await openPlaylistPicker(media);
    };

    wrap.append(button);
  }

  openModal("playlistPickerModal");
}


function shuffleMusicIds(ids) {
  const result = [...new Set(ids)];

  // Fisher-Yates: unbiased in-place shuffle on a copy.
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }

  return result;
}


let currentMusicTrack = null;
let musicSleepTimerId = null;
let musicSleepTimerEndsAt = 0;
let musicAudioContext = null;
let musicAudioSource = null;
let musicEqFilters = null;
let musicEqPresetIndex = 0;

const musicEqBands = [
  { label: "31 Hz", frequency: 31 },
  { label: "62 Hz", frequency: 62 },
  { label: "125 Hz", frequency: 125 },
  { label: "250 Hz", frequency: 250 },
  { label: "500 Hz", frequency: 500 },
  { label: "1 kHz", frequency: 1000 },
  { label: "2 kHz", frequency: 2000 },
  { label: "4 kHz", frequency: 4000 },
  { label: "8 kHz", frequency: 8000 },
  { label: "16 kHz", frequency: 16000 }
];
let musicEqCustomGains = Array(10).fill(0);
let musicEqCustomEnabled = false;
const musicEqPresets = [
  { name: "إيقاف", gains: [0,0,0,0,0,0,0,0,0,0] },
  { name: "Bass+", gains: [6,5,4,2,0,0,-1,-2,-2,-2] },
  { name: "Vocal", gains: [-2,-1,0,2,4,5,4,2,0,-1] },
  { name: "Treble+", gains: [-3,-2,-1,0,1,2,3,4,5,6] },
  { name: "Rock", gains: [4,3,1,-1,-2,1,3,4,4,4] },
  { name: "Jazz", gains: [3,2,1,2,-1,-1,1,2,3,3] },
  { name: "Classical", gains: [4,3,2,1,-1,-1,0,2,3,4] },
  { name: "Pop", gains: [-1,1,3,4,2,0,1,2,3,2] }
];

function loadMusicEqSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem("novra-music-eq-v1") || "null");
    if (!saved || typeof saved !== "object") return;
    if (Number.isInteger(saved.presetIndex)) {
      musicEqPresetIndex = Math.max(0, Math.min(musicEqPresets.length - 1, saved.presetIndex));
    }
    if (Array.isArray(saved.customGains) && saved.customGains.length === 10) {
      musicEqCustomGains = saved.customGains.map(value =>
        Math.max(-12, Math.min(12, Number(value) || 0))
      );
    }
    musicEqCustomEnabled = saved.customEnabled === true;
  } catch (error) {
    console.warn("Could not restore NOVRA EQ settings:", error);
  }
}

function saveMusicEqSettings() {
  try {
    localStorage.setItem("novra-music-eq-v1", JSON.stringify({
      presetIndex: musicEqPresetIndex,
      customEnabled: musicEqCustomEnabled,
      customGains: musicEqCustomGains
    }));
  } catch (error) {
    console.warn("Could not save NOVRA EQ settings:", error);
  }
}

function syncMusicEqControls() {
  const button = $("#musicEqBtn");
  if (button) {
    button.textContent = musicEqCustomEnabled
      ? "♫ EQ: مخصص"
      : "♫ EQ: " + musicEqPresets[musicEqPresetIndex].name;
  }
  document.querySelectorAll("[data-eq-band]").forEach(input => {
    const index = Number(input.dataset.eqBand);
    if (!Number.isInteger(index) || index < 0 || index >= 10) return;
    input.value = String(musicEqCustomGains[index]);
    const output = input.parentElement?.querySelector("output");
    if (output) output.textContent = musicEqCustomGains[index] + " dB";
  });
}


// NOVRA VIDEO — LRC LYRICS
let novraLyrics = [];
let novraLyricsTrackId = null;
let novraLyricsTimed = true;

function parseNovraLrc(source) {
  const result = [];
  const lines = String(source || "").replace(/^\uFEFF/, "").split(/\r?\n/);

  for (const line of lines) {
    const timestamps = [...line.matchAll(/\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
    const lyric = line.replace(/\[[^\]]+\]/g, "").trim();
    if (!lyric || !timestamps.length) continue;

    for (const match of timestamps) {
      const fraction = Number(String(match[3] || "").padEnd(3, "0")) / 1000;
      const time = Number(match[1]) * 60 + Number(match[2]) + fraction;
      result.push({ time, text: lyric });
    }
  }

  return result.sort((a, b) => a.time - b.time);
}

function updateNovraLyrics() {
  const panel = document.querySelector("#novraLyricsPanel");
  if (!panel || panel.hidden || !novraLyrics.length) return;

  if (!currentAudioId || currentAudioId !== novraLyricsTrackId) {
    panel.hidden = true;
    const button = document.querySelector("#novraLyricsBtn");
    if (button) button.textContent = "♫ كلمات الأغنية";
    return;
  }

  if (!novraLyricsTimed) {
    if (current) current.textContent = novraLyrics.map(line => line.text).join("\n");
    if (next) next.textContent = "كلمات غير متزامنة";
    return;
  }
  const now = currentAudio?.currentTime || 0;
  let index = -1;

  for (let i = 0; i < novraLyrics.length; i++) {
    if (novraLyrics[i].time <= now) index = i;
    else break;
  }

  const current = panel.querySelector(".novra-lyrics-current");
  const next = panel.querySelector(".novra-lyrics-next");

  if (current) current.textContent = index >= 0
    ? novraLyrics[index].text
    : "استعد للاستماع...";

  if (next) next.textContent = index >= 0 && novraLyrics[index + 1]
    ? novraLyrics[index + 1].text
    : "";
}

async function loadNovraLrc(file) {
  if (!file) return;

  try {
    const parsed = parseNovraLrc(await file.text());
    if (!parsed.length) {
      toast("ملف LRC لا يحتوي على كلمات بتوقيت صالح.");
      return;
    }

    novraLyrics = parsed;
    novraLyricsTrackId = currentAudioId;
    novraLyricsTimed = true;

    const panel = document.querySelector("#novraLyricsPanel");
    const button = document.querySelector("#novraLyricsBtn");

    if (panel) panel.hidden = false;
    if (button) button.textContent = "إخفاء الكلمات";

    updateNovraLyrics();
    toast(`تم تحميل ${parsed.length} سطرًا من كلمات الأغنية.`);
  } catch (error) {
    console.error("LRC loading failed:", error);
    toast("تعذر تحميل ملف الكلمات.");
  }
}
// END NOVRA VIDEO — LRC LYRICS

async function fetchTrackLyrics(track) {
  if (!track?.name || !track?.artist) return;
  const trackId = track.id;
  ensureMusicEnhancementControls();
  novraLyrics = [];
  novraLyricsTrackId = trackId;
  novraLyricsTimed = true;
  const panel = $("#novraLyricsPanel");
  const current = panel?.querySelector(".novra-lyrics-current");
  const next = panel?.querySelector(".novra-lyrics-next");
  if (panel) panel.hidden = false;
  if (current) current.textContent = "جارٍ البحث عن كلمات الأغنية...";
  if (next) next.textContent = "بحث تلقائي عن الكلمات المتاحة";
  const params = new URLSearchParams({track_name:track.name,artist_name:track.artist,...(track.album?{album_name:track.album}:{}),...(Number(track.duration)>0?{duration:String(Math.round(track.duration))}:{})});
  try {
    const response = await fetch("https://lrclib.net/api/get?" + params.toString(), {headers:{"X-User-Agent":"Media Library/1.1 (local media player)"}});
    if (!response.ok) throw new Error(response.status === 404 ? "not-found" : "lyrics-service");
    const data = await response.json();
    if (currentAudioId !== trackId) return;
    if (data.syncedLyrics) {
      const parsed = parseNovraLrc(data.syncedLyrics);
      if (parsed.length) { novraLyrics = parsed; novraLyricsTimed = true; }
    }
    if (!novraLyrics.length && data.plainLyrics) {
      const lines = String(data.plainLyrics).split(/\r?\n/).map(text => text.trim()).filter(Boolean);
      novraLyrics = lines.map(text => ({time:0,text}));
      novraLyricsTimed = false;
    }
    novraLyricsTrackId = trackId;
    const button = $("#novraLyricsBtn");
    if (novraLyrics.length) {
      if (panel) panel.hidden = false;
      if (button) button.textContent = "إخفاء الكلمات";
      updateNovraLyrics();
    } else {
      if (current) current.textContent = "الأغنية بدون كلمات متاحة.";
      if (next) next.textContent = "";
    }
  } catch (error) {
    if (currentAudioId !== trackId) return;
    if (current) current.textContent = error.message === "not-found" ? "لم نعثر على كلمات مطابقة لهذه الأغنية." : "تعذر جلب الكلمات الآن. يمكنك تحميل ملف LRC يدويًا.";
    if (next) next.textContent = "";
    console.debug("Automatic lyrics lookup unavailable:", error);
  }
}
function ensureMusicEnhancementControls() {
  const controls = $("#musicControls");
  if (!controls || $("#musicSleepBtn")) return;

  const area = document.createElement("div");
  area.id = "musicEnhancements";
  area.className = "music-enhancements";

  const sleep = document.createElement("button");
  sleep.id = "musicSleepBtn";
  sleep.type = "button";
  sleep.className = "control-btn";
  sleep.textContent = "◷ مؤقت النوم";
  sleep.addEventListener("click", toggleMusicSleepTimer);

  const eq = document.createElement("button");
  eq.id = "musicEqBtn";
  eq.type = "button";
  eq.className = "control-btn";
  eq.textContent = "♫ EQ: إيقاف";
  eq.addEventListener("click", cycleMusicEqualizer);

  area.append(sleep, eq);

  const eqDetails = document.createElement("details");
  eqDetails.className = "music-eq-details";
  const eqSummary = document.createElement("summary");
  eqSummary.textContent = "معادل صوتي احترافي — 10 نطاقات";
  const eqGrid = document.createElement("div");
  eqGrid.className = "music-eq-grid";

  musicEqBands.forEach((band, index) => {
    const label = document.createElement("label");
    label.className = "music-eq-band";
    const name = document.createElement("span");
    name.textContent = band.label;
    const range = document.createElement("input");
    range.type = "range";
    range.min = "-12";
    range.max = "12";
    range.step = "1";
    range.value = String(musicEqCustomGains[index]);
    range.dataset.eqBand = String(index);
    range.setAttribute("aria-label", "معادل " + band.label);
    const value = document.createElement("output");
    value.textContent = musicEqCustomGains[index] + " dB";
    range.addEventListener("input", () => {
      musicEqCustomGains[index] = Number(range.value);
      musicEqCustomEnabled = true;
      applyMusicEqualizer();
      syncMusicEqControls();
      saveMusicEqSettings();
    });
    label.append(name, range, value);
    eqGrid.append(label);
  });

  eqDetails.append(eqSummary, eqGrid);
  area.append(eqDetails);
  syncMusicEqControls();

  // NOVRA VIDEO — LRC LYRICS UI
  const lyricsButton = document.createElement("button");
  lyricsButton.id = "novraLyricsBtn";
  lyricsButton.type = "button";
  lyricsButton.className = "control-btn";
  lyricsButton.textContent = "♫ كلمات الأغنية";

  const lyricsInput = document.createElement("input");
  lyricsInput.id = "novraLyricsInput";
  lyricsInput.type = "file";
  lyricsInput.accept = ".lrc,text/plain";
  lyricsInput.hidden = true;

  const lyricsPanel = document.createElement("div");
  lyricsPanel.id = "novraLyricsPanel";
  lyricsPanel.className = "novra-lyrics-panel";
  lyricsPanel.hidden = true;

  const lyricsCurrent = document.createElement("p");
  lyricsCurrent.className = "novra-lyrics-current";
  lyricsCurrent.textContent = "استعد للاستماع...";

  const lyricsNext = document.createElement("small");
  lyricsNext.className = "novra-lyrics-next";

  lyricsPanel.append(lyricsCurrent, lyricsNext);
  area.append(lyricsButton, lyricsInput, lyricsPanel);

  lyricsButton.addEventListener("click", () => {
    if (!novraLyrics.length || novraLyricsTrackId !== currentAudioId) {
      lyricsInput.click();
      return;
    }

    lyricsPanel.hidden = !lyricsPanel.hidden;
    lyricsButton.textContent = lyricsPanel.hidden
      ? "♫ كلمات الأغنية"
      : "إخفاء الكلمات";

    if (!lyricsPanel.hidden) updateNovraLyrics();
  });

  lyricsInput.addEventListener("change", async event => {
    await loadNovraLrc(event.target.files?.[0]);
    event.target.value = "";
  });

  if (typeof currentAudio !== "undefined" &&
      currentAudio &&
      !currentAudio.dataset.novraLyricsBound) {
    currentAudio.dataset.novraLyricsBound = "1";
    currentAudio.addEventListener("timeupdate", updateNovraLyrics);
  }
  // END NOVRA VIDEO — LRC LYRICS UI

  controls.append(area);
}

function toggleMusicSleepTimer() {
  if (musicSleepTimerId) {
    clearTimeout(musicSleepTimerId);
    musicSleepTimerId = null;
    musicSleepTimerEndsAt = 0;
    $("#musicSleepBtn").textContent = "◷ مؤقت النوم";
    toast("تم إلغاء مؤقت النوم.");
    return;
  }

  const raw = prompt("إيقاف التشغيل بعد كام دقيقة؟ (1 إلى 720)", "30");
  if (raw === null) return;

  const minutes = Number(raw);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 720) {
    toast("اكتب عدد دقائق صحيحًا من 1 إلى 720.");
    return;
  }

  musicSleepTimerEndsAt = Date.now() + minutes * 60000;
  musicSleepTimerId = setTimeout(() => {
    const player = currentMusicVideo ? $("#videoPlayer") : currentAudio;
    if (player) player.pause();

    musicSleepTimerId = null;
    musicSleepTimerEndsAt = 0;
    const button = $("#musicSleepBtn");
    if (button) button.textContent = "◷ مؤقت النوم";

    updateMusicControls();
    toast("انتهى مؤقت النوم وتم إيقاف التشغيل.");
  }, minutes * 60000);

  $("#musicSleepBtn").textContent = `◷ إلغاء المؤقت (${minutes} د)`;
  toast(`مؤقت النوم مضبوط على ${minutes} دقيقة.`);
}

function ensureMusicAudioGraph() {
  if (!currentAudio) return false;
  if (musicAudioSource && musicEqFilters) return true;

  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) return false;

  try {
    musicAudioContext = musicAudioContext || new AudioContextClass();
    musicAudioSource = musicAudioContext.createMediaElementSource(currentAudio);

    musicEqFilters = musicEqBands.map(band => {
      const filter = musicAudioContext.createBiquadFilter();
      filter.type = "peaking";
      filter.frequency.value = band.frequency;
      filter.Q.value = 1.0;
      return filter;
    });

    let previous = musicAudioSource;
    for (const filter of musicEqFilters) {
      previous.connect(filter);
      previous = filter;
    }
    previous.connect(musicAudioContext.destination);
    applyMusicEqualizer();
    return true;
  } catch (error) {
    console.warn("NOVRA equalizer unavailable:", error);
    return false;
  }
}

function applyMusicEqualizer() {
  if (!musicEqFilters) return;

  const gains = musicEqCustomEnabled
    ? musicEqCustomGains
    : musicEqPresets[musicEqPresetIndex].gains;
  musicEqFilters.forEach((filter, index) => {
    filter.gain.setTargetAtTime(gains[index] || 0, musicAudioContext.currentTime, 0.04);
  });
}

async function cycleMusicEqualizer() {
  musicEqCustomEnabled = false;
  musicEqPresetIndex = (musicEqPresetIndex + 1) % musicEqPresets.length;
  try {
    if (currentAudio && ensureMusicAudioGraph() && musicAudioContext.state === "suspended") {
      await musicAudioContext.resume();
    }
    applyMusicEqualizer();
    syncMusicEqControls();
    saveMusicEqSettings();
    toast("تم اختيار المعادل: " + musicEqPresets[musicEqPresetIndex].name);
  } catch (error) {
    console.warn(error);
    toast("تم حفظ إعداد المعادل وسيُطبّق عند تشغيل الصوت.");
  }
}

function updateMusicMediaPosition() {
  if (!("mediaSession" in navigator)) return;

  const player = currentMusicVideo
    ? $("#videoPlayer")
    : currentAudio;

  if (!player || !Number.isFinite(player.duration) || player.duration <= 0) {
    return;
  }

  try {
    navigator.mediaSession.setPositionState({
      duration: player.duration,
      playbackRate: player.playbackRate || 1,
      position: Math.max(0, Math.min(player.currentTime || 0, player.duration))
    });
  } catch (error) {
    console.debug("Media position update skipped:", error);
  }
}

function syncMusicMediaSession() {
  if (!("mediaSession" in navigator)) return;

  const session = navigator.mediaSession;

  try {
    if (currentMusicTrack && "MediaMetadata" in window) {
      session.metadata = new MediaMetadata({
        title: currentMusicTrack.name || currentMusicTitle || "مشغل الوسائط",
        artist: currentMusicTrack.artist || "المكتبة المحلية",
        album: currentMusicTrack.album || "المكتبة المحلية",
        artwork: [{
          src: currentMusicTrack.thumb || new URL("/icon.svg", location.origin).href,
          sizes: "512x512",
          type: (currentMusicTrack.thumb || "").startsWith("data:image/png") ? "image/png" :
            (currentMusicTrack.thumb || "").startsWith("data:image/") ? "image/jpeg" : "image/svg+xml"
        }]
      });
    }

    const activePlayer = () =>
      currentMusicVideo ? $("#videoPlayer") : currentAudio;

    const actions = {
      play: () => {
        const player = activePlayer();
        if (!player) return;
        if (player.ended) player.currentTime = 0;

        const result = player.play();
        if (result && typeof result.catch === "function") {
          result.then(updateMusicControls).catch(error => {
            console.warn("Media Session play failed:", error);
            updateMusicControls();
          });
        }
      },
      pause: () => {
        const player = activePlayer();
        if (player) player.pause();
        updateMusicControls();
      },
      previoustrack: () => advanceMusic(-1),
      nexttrack: () => advanceMusic(1),
      seekto: details => {
        const player = activePlayer();
        if (!player || !Number.isFinite(details.seekTime)) return;
        if (!Number.isFinite(player.duration) || player.duration <= 0) return;
        player.currentTime = Math.max(0, Math.min(details.seekTime, player.duration));
        updateMusicMediaPosition();
      },
      seekbackward: details => {
        const player = activePlayer();
        if (!player) return;
        player.currentTime = Math.max(0, player.currentTime - (details.seekOffset || 10));
        updateMusicMediaPosition();
      },
      seekforward: details => {
        const player = activePlayer();
        if (!player || !Number.isFinite(player.duration)) return;
        player.currentTime = Math.min(player.duration, player.currentTime + (details.seekOffset || 10));
        updateMusicMediaPosition();
      }
    };

    for (const [action, handler] of Object.entries(actions)) {
      try {
        session.setActionHandler(action, handler);
      } catch {}
    }

    const player = activePlayer();
    if (player) {
      session.playbackState = player.paused ? "paused" : "playing";
      updateMusicMediaPosition();
    }
  } catch (error) {
    console.debug("Media Session update skipped:", error);
  }
}

function updateMusicArtwork() {
  const orb = $("#musicControls .music-orb");
  if (!orb) return;
  const cover = currentMusicTrack?.thumb || "/icon.svg";
  if (cover) {
    orb.replaceChildren();
    const img = document.createElement("img");
    img.src = cover;
    img.alt = "غلاف الأغنية";
    img.className = "music-cover-image";
    orb.appendChild(img);
    orb.classList.add("has-cover");
  } else {
    orb.classList.remove("has-cover");
    orb.replaceChildren(document.createTextNode("♫"));
  }
}

function updateMusicControls() {
  const controls = $("#musicControls");
  if (!controls) return;

  const hasQueue = musicQueue.length > 0;
  const hasPlayback = !!currentAudioId &&
    (!!currentAudio || currentMusicVideo || nativeBackgroundPlayback);
  const isPlaying = nativeBackgroundPlayback
    ? nativeBackgroundPlaying
    : currentMusicVideo ? !$("#videoPlayer").paused
    : !!currentAudio && !currentAudio.paused;

  $("#musicPrevBtn").disabled = !hasQueue;
  $("#musicNextBtn").disabled = !hasQueue;
  $("#musicPlayPauseBtn").disabled = !hasQueue && !hasPlayback;
  $("#musicShuffleBtn").classList.toggle("active", musicShuffleEnabled);
  $("#musicShuffleBtn").setAttribute("aria-pressed", String(musicShuffleEnabled));

  const repeatButton = $("#musicRepeatBtn");
  repeatButton.classList.toggle("active", musicRepeatMode !== "off");
  repeatButton.setAttribute("aria-pressed", String(musicRepeatMode !== "off"));
  repeatButton.textContent = musicRepeatMode === "one"
    ? "🔂 تكرار الأغنية"
    : musicRepeatMode === "all"
      ? "🔁 تكرار القائمة"
      : "↻ التكرار مغلق";

  $("#musicPlayPauseBtn").textContent =
    isPlaying ? "Ⅱ إيقاف مؤقت" : "▶ تشغيل";

  const title = $("#musicNowPlaying");
  if (title) {
    title.textContent = hasPlayback
      ? `يعمل الآن: ${currentMusicTitle || "بدون اسم"}`
      : "اختر أغنية لبدء التشغيل";
  }
  updateMusicArtwork();
  ensureMusicEnhancementControls();
  syncMusicMediaSession();
}

function syncMusicQueue(items) {
  // Remove duplicate library entries representing the same song.
  // Keep the currently playing ID when duplicate records are found.
  const uniqueIds = [];
  const songPositions = new Map();

  const normalized = value =>
    String(value || "")
      .normalize("NFKC")
      .trim()
      .toLocaleLowerCase()
      .replace(/\s+/g, " ");

  const audioItems = items.filter(
    item => item.kind === "audio" && !isVaultItem(item)
  );

  for (const item of audioItems) {
    const name = normalized(item.name);
    const artist = normalized(item.artist);
    const filename = normalized(item.filename);

    const identity = filename
      ? "file:" + filename
      : "song:" + name + "|artist:" + artist;

    const key = name ? identity : "id:" + item.id;

    if (!songPositions.has(key)) {
      songPositions.set(key, uniqueIds.length);
      uniqueIds.push(item.id);
    } else if (item.id === currentAudioId) {
      const position = songPositions.get(key);
      uniqueIds[position] = item.id;
    }
  }

  const ids = uniqueIds;

  const signature = JSON.stringify(ids);

  if (signature !== musicQueueSignature) {
    musicBaseQueue = ids;

    if (musicShuffleEnabled) {
      const rest = ids.filter(id => id !== currentAudioId);
      const shuffled = shuffleMusicIds(rest);
      musicQueue = ids.includes(currentAudioId)
        ? [currentAudioId, ...shuffled]
        : shuffled;
    } else {
      musicQueue = [...ids];
    }

    musicQueueSignature = signature;
  }

  musicQueueIndex = currentAudioId
    ? musicQueue.indexOf(currentAudioId)
    : -1;

  const controls = $("#musicControls");
  const playlistHasAudio = currentCategory === "playlists" && ids.length > 0;
  if (controls) {
    controls.hidden = !(currentCategory === "audio" || playlistHasAudio);
  }

  updateMusicControls();
}

async function playMusicTrack(id) {
  stopListeningTracker();
  const token = ++musicLoadToken;

  try {
    const all = await dbRequest("readonly", "all");
    const track = all.find(item => item.id === id);

    if (!track || track.kind !== "audio" || isVaultItem(track)) {
      toast("الملف غير متاح في قائمة الموسيقى.");
      return;
    }
    if (nativeBackgroundPlayback) {
      await nativeMediaPlugin?.controlBackgroundPlayback?.({command:"stop"}).catch(() => {});
      nativeBackgroundPlayback = false;
      nativeBackgroundPlaying = false;
    }

    if (!musicBaseQueue.includes(id)) {
      musicBaseQueue.push(id);
      musicQueue = musicShuffleEnabled
        ? [id, ...shuffleMusicIds(musicBaseQueue.filter(x => x !== id))]
        : [...musicBaseQueue];
      musicQueueSignature = JSON.stringify(musicBaseQueue);
    }

    musicQueueIndex = musicQueue.indexOf(id);
    currentAudioId = id;
    track.playCount = (Number(track.playCount) || 0) + 1;
    track.lastPlayed = Date.now();
    await dbRequest("readwrite", "put", track);
    currentMusicTitle = track.name || "بدون اسم";
    currentMusicTrack = track;
    fetchTrackLyrics(track);

    const actualKind = mediaKind(track.type, track.filename);

    if (actualKind === "video") {
      if (nativeBackgroundPlayback) {
        await nativeMediaPlugin?.controlBackgroundPlayback?.({command:"stop"}).catch(() => {});
        nativeBackgroundPlayback = false;
        nativeBackgroundPlaying = false;
      }
      currentMusicVideo = true;

      if (currentAudio) {
        currentAudio.pause();
        currentAudio.removeAttribute("src");
        currentAudio.load();
      }

      if (currentAudioUrl) {
        URL.revokeObjectURL(currentAudioUrl);
        currentAudioUrl = null;
      }

      updateMusicControls();
      await playVideo(id, true);
      return;
    }

    currentMusicVideo = false;

    const player = $("#videoPlayer");
    if (player && (player.currentSrc || player.src)) {
      player.pause();
      player.removeAttribute("src");
      player.load();

      if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
      currentObjectUrl = null;
      currentVideo = null;
      await releaseCurrentNativePlayback();
      closeModal("playerModal");
    }

    const file = track.nativeUri ? null : await getFile(track.filename);
    if (token !== musicLoadToken) return;

    if (currentAudio) currentAudio.pause();

    if (currentAudioUrl) {
      URL.revokeObjectURL(currentAudioUrl);
      currentAudioUrl = null;
    }
    await releaseCurrentNativePlayback();

    let playbackSource;
    if (track.nativeUri) {
      if (!nativeMediaPlugin?.preparePlayback) throw new Error("تشغيل ملفات الجهاز يحتاج إصدار Android المحدث.");
      const prepared = await nativeMediaPlugin.preparePlayback({uri:track.nativeUri});
      currentNativePlaybackPath = prepared.path;
      if (nativeMediaPlugin.playInBackground) {
        await nativeMediaPlugin.playInBackground({path:prepared.path,title:currentMusicTitle,artist:track.artist || ""});
        currentAudio = null;
        nativeBackgroundPlayback = true;
        nativeBackgroundPlaying = true;
        currentMusicVideo = false;
        startListeningTracker();
        updateMusicControls();
        return;
      }
      playbackSource = window.Capacitor?.convertFileSrc?.(prepared.path);
      if (!playbackSource) throw new Error("تعذر تجهيز مسار تشغيل الملف.");
    } else {
      playbackSource = URL.createObjectURL(file);
    }
    currentAudioUrl = playbackSource.startsWith("blob:") ? playbackSource : null;

    if (!currentAudio) currentAudio = new Audio();

    currentAudio.pause();
    currentAudio.src = playbackSource;
    currentAudio.onended = () => { stopListeningTracker(); handleMusicEnded(); };

    currentAudio.onplay = () => updateMusicControls();
    currentAudio.onpause = () => { stopListeningTracker(); updateMusicControls(); };
    currentAudio.onloadedmetadata = () => {
      syncMusicMediaSession();
      updateMusicMediaPosition();
    };
    currentAudio.ontimeupdate = () => updateMusicMediaPosition();

    ensureMusicAudioGraph();
    if (musicAudioContext && musicAudioContext.state === "suspended") {
      musicAudioContext.resume().catch(() => {});
    }
    currentAudio.onerror = () => toast("تعذر تشغيل الملف الصوتي.");

    updateMusicControls();

    await currentAudio.play();
    startListeningTracker();
    updateMusicControls();
  } catch (error) {
    if (token === musicLoadToken) {
      console.error(error);
      toast("تعذر فتح الملف من التخزين المحلي.");
    }
  }
}

async function advanceMusic(direction) {
  if (!musicQueue.length) {
    toast("مفيش أغاني في قائمة التشغيل الحالية.");
    return;
  }

  const activePlayer = currentMusicVideo
    ? $("#videoPlayer")
    : nativeBackgroundPlayback ? null : currentAudio;
  if (nativeBackgroundPlayback && direction < 0) {
    nativeMediaPlugin?.controlBackgroundPlayback?.({command:"seek",position:0}).catch(() => {});
    return;
  }

  // Previous button restarts the current track if it has played for 3 seconds.
  if (direction < 0 && activePlayer && activePlayer.currentTime > 3) {
    activePlayer.currentTime = 0;
    activePlayer.play().catch(() => {});
    return;
  }

  let nextIndex = musicQueueIndex + direction;

  if (nextIndex < 0 || nextIndex >= musicQueue.length) {
    if (musicRepeatMode !== "all") {
      if (direction > 0 && activePlayer) {
        activePlayer.pause();
        updateMusicControls();
      }
      return;
    }

    if (musicShuffleEnabled) {
      // Start a fresh shuffled cycle without immediately repeating
      // the track that ended the previous cycle.
      const remaining = musicBaseQueue.filter(
        id => id !== currentAudioId
      );

      let nextCycle = shuffleMusicIds(remaining);

      // A one-track library is allowed to repeat that single track.
      if (!nextCycle.length && currentAudioId) {
        nextCycle = [currentAudioId];
      }

      // For backward wrap, reverse the newly generated cycle.
      if (direction < 0) {
        nextCycle.reverse();
      }

      musicQueue = nextCycle;
      musicQueueIndex = -1;
      nextIndex = 0;
    } else {
      nextIndex = direction > 0 ? 0 : musicQueue.length - 1;
    }
  }

  const nextId = musicQueue[nextIndex];

  if (nextId) {
    await playMusicTrack(nextId);
  }
}

function handleMusicEnded() {
  const player = currentMusicVideo ? $("#videoPlayer") : currentAudio;

  if (musicRepeatMode === "one" && player) {
    player.currentTime = 0;
    player.play().catch(() => toast("تعذر إعادة تشغيل الملف."));
    return;
  }

  advanceMusic(1);
}

function toggleMusicShuffle() {
  musicShuffleEnabled = !musicShuffleEnabled;

  const currentId = currentAudioId;
  if (musicShuffleEnabled) {
    const rest = musicBaseQueue.filter(id => id !== currentId);
    musicQueue = currentId && musicBaseQueue.includes(currentId)
      ? [currentId, ...shuffleMusicIds(rest)]
      : shuffleMusicIds(musicBaseQueue);
  } else {
    musicQueue = [...musicBaseQueue];
  }

  musicQueueIndex = currentId ? musicQueue.indexOf(currentId) : -1;
  updateMusicControls();
}

function cycleMusicRepeat() {
  musicRepeatMode = musicRepeatMode === "off"
    ? "all"
    : musicRepeatMode === "all"
      ? "one"
      : "off";

  updateMusicControls();
}

function toggleMusicPlayPause() {
  if (nativeBackgroundPlayback && nativeMediaPlugin?.controlBackgroundPlayback) {
    nativeBackgroundPlaying = !nativeBackgroundPlaying;
    nativeMediaPlugin.controlBackgroundPlayback({command:nativeBackgroundPlaying ? "resume" : "pause"}).catch(error => { console.warn(error); nativeBackgroundPlaying = !nativeBackgroundPlaying; });
    if (nativeBackgroundPlaying) startListeningTracker(); else stopListeningTracker();
    updateMusicControls();
    return;
  }
  if (!currentAudioId || (!currentAudio && !currentMusicVideo)) {
    if (musicQueue.length) playMusicTrack(musicQueue[0]);
    return;
  }

  const player = currentMusicVideo ? $("#videoPlayer") : currentAudio;
  if (!player) return;

  if (player.paused) {
    if (player.ended) player.currentTime = 0;

    const result = player.play();
    if (result && typeof result.catch === "function") {
      result.catch(error => {
        console.warn("Music playback failed:", error);
        toast("تعذر استكمال التشغيل.");
        updateMusicControls();
      });
    }
  } else {
    player.pause();
  }

  updateMusicControls();
}



function observeNativeVideoThumbnail(media, thumb) {
  if (!nativeMediaPlugin || !media?.nativeUri || media.kind !== "video" || media.thumb || !thumb) return;
  if (thumb.dataset.nativeThumbnailBound === "1") return;
  thumb.dataset.nativeThumbnailBound = "1";
  const placeholder = thumb.querySelector(".thumb-placeholder");
  const image = document.createElement("img");
  image.alt = "";
  image.loading = "lazy";
  image.decoding = "async";
  image.hidden = true;
  image.style.cssText = "position:absolute;inset:0;width:100%;height:100%;object-fit:cover";
  thumb.append(image);
  const load = async () => {
    if (!thumb.isConnected) return;
    const cached = nativeThumbnailCache.get(media.id);
    if (cached) {
      image.src = cached; image.hidden = false;
      if (placeholder) placeholder.hidden = true;
      return;
    }
    if (nativeThumbnailPending.has(media.id)) return;
    nativeThumbnailPending.add(media.id);
    try {
      const result = await nativeMediaPlugin.getThumbnail({uri:media.nativeUri});
      if (!result?.data || !thumb.isConnected) return;
      const source = "data:image/jpeg;base64," + result.data;
      if (nativeThumbnailCache.size >= 80) nativeThumbnailCache.delete(nativeThumbnailCache.keys().next().value);
      nativeThumbnailCache.set(media.id, source);
      image.src = source; image.hidden = false;
      if (placeholder) placeholder.hidden = true;
    } catch (error) { console.debug("NOVRA native thumbnail unavailable:", error); }
    finally { nativeThumbnailPending.delete(media.id); }
  };
  if ("IntersectionObserver" in window) {
    if (!window.novraThumbnailObserver) {
      window.novraThumbnailObserver = new IntersectionObserver(entries => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          window.novraThumbnailObserver.unobserve(entry.target);
          entry.target.__novraLoadThumbnail?.();
        }
      }, {rootMargin:"240px 0px"});
    }
    thumb.__novraLoadThumbnail = load;
    window.novraThumbnailObserver.observe(thumb);
  } else load();
}

async function render() {
  const all = await dbRequest("readonly","all");
  const allPlaylists = await playlistRequest("all");
  const ordinary = all.filter(v => !isVaultItem(v));
  const selectedPlaylist = allPlaylists.find(p => p.id === activePlaylistId);
  const videosOnly = ordinary.filter(v => (v.kind || "video") === "video");
  const audioOnly = ordinary.filter(v => v.kind === "audio");

  $("#videoCount").textContent = videosOnly.length;
  $("#audioCount").textContent = audioOnly.length;
  $("#allCount").textContent = currentCategory === "audio" ? audioOnly.length :
    currentCategory === "vault" ? all.filter(isVaultItem).length :
    currentCategory === "playlists" ? (selectedPlaylist ? (selectedPlaylist.mediaIds || []).length : allPlaylists.length) :
    videosOnly.length;
  $("#watchedCount").textContent = videosOnly.filter(v => v.position > 5).length;
  const totalListeningSeconds = audioOnly.reduce((sum, track) => sum + Math.max(0, Number(track.listenedSeconds) || 0), 0);
  const listeningStat = $("#listeningTimeValue");
  if (listeningStat) listeningStat.textContent = formatListeningTime(totalListeningSeconds);
  $("#storageUsed").textContent = formatBytes(all.reduce((n,v)=>n+(v.nativeUri?0:(Number(v.size)||0)),0));

  let items = currentCategory === "vault"
    ? (vaultOpen ? all.filter(isVaultItem) : [])
    : currentCategory === "audio" ? audioOnly
    : currentCategory === "playlists"
      ? (selectedPlaylist
          ? (selectedPlaylist.mediaIds || [])
              .map(id => ordinary.find(v => v.id === id))
              .filter(Boolean)
          : [])
      : videosOnly;

  const q = $("#searchInput").value.trim().toLowerCase();
  const filter = currentFilter();
  const sort = $("#sortSelect").value;

  if (q) items = items.filter(v => {
    const searchable = currentCategory === "audio" ? [v.name, v.artist, v.album].join(" ").toLowerCase() : (v.name || "").toLowerCase();
    return searchable.includes(q);
  });
  if (filter === "recent") items = items.filter(v => Date.now()-(v.created||0)<7*86400000);
  if (filter === "continue") items = items.filter(v => (v.position||0)>5);
  if (filter === "favorites") items = items.filter(v => v.favorite);

  if (!(currentCategory === "playlists" && selectedPlaylist)) {
    items.sort((a,b) => sort === "mostPlayed" && currentCategory === "audio"
      ? (Number(b.playCount)||0) - (Number(a.playCount)||0) || (Number(b.listenedSeconds)||0) - (Number(a.listenedSeconds)||0)
      : sort === "oldest" ? a.created-b.created
      : sort === "name" ? a.name.localeCompare(b.name)
      : sort === "largest" ? b.size-a.size : b.created-a.created);
  }

  syncMusicQueue(items);
  await renderPlaylistPanel(allPlaylists);

  const playlistIndexView = currentCategory === "playlists" && !activePlaylistId;
  const videoGrid = $("#videoGrid");
  const cardFragment = document.createDocumentFragment();
  document.querySelector("#loadMoreMedia")?.remove();
  videoGrid.replaceChildren();
  if (currentCategory === "audio" && musicBrowseMode !== "tracks" && !musicGroupFilter) {
    videoGrid.hidden = false;
    $("#emptyState").hidden = items.length > 0;
    $("#musicBrowseBack").hidden = true;
    const field = musicBrowseMode === "artists" ? "artist" : "album";
    const groups = new Map();
    for (const item of items) {
      const label = String(item[field] || (field === "artist" ? "فنان غير معروف" : "ألبوم غير معروف")).trim();
      if (!groups.has(label)) groups.set(label, []);
      groups.get(label).push(item);
    }
    if (!groups.size) {
      $("#emptyState").querySelector("h3").textContent = "لا توجد مجموعات بعد";
      $("#emptyState").querySelector("p").textContent = "أضف ملفات موسيقى تحتوي على بيانات الفنان أو الألبوم لعرضها هنا.";
      $("#emptyState button").hidden = true;
      return;
    }
    const orderedGroups = [...groups.entries()].sort((a,b) => b[1].length - a[1].length || a[0].localeCompare(b[0]));
    for (const [label, records] of orderedGroups) {
      const groupCard = document.createElement("button");
      groupCard.type = "button";
      groupCard.className = "music-group-card";
      const cover = document.createElement("span");
      cover.className = "music-group-cover";
      if (records[0].thumb) { const image = document.createElement("img"); image.src = records[0].thumb; image.alt = ""; cover.append(image); }
      else cover.textContent = field === "artist" ? "♙" : "▤";
      const details = document.createElement("span"); details.className = "music-group-details";
      const name = document.createElement("strong"); name.textContent = label;
      const count = document.createElement("small"); count.textContent = records.length + (records.length === 1 ? " أغنية" : " أغاني");
      details.append(name,count); groupCard.append(cover,details);
      groupCard.addEventListener("click", () => { musicGroupFilter = {field,value:label,label}; $("#musicBrowseBack").hidden = false; render(); });
      cardFragment.append(groupCard);
    }
    videoGrid.append(cardFragment);
    $("#emptyState").hidden = true;
    const groupHeading = $("#libraryHeading");
    if (groupHeading) groupHeading.textContent = musicBrowseMode === "artists" ? "الفنانون" : "الألبومات";
    return;
  }
  if (currentCategory === "audio" && musicGroupFilter) {
    items = items.filter(item => String(item[musicGroupFilter.field] || (musicGroupFilter.field === "artist" ? "فنان غير معروف" : "ألبوم غير معروف")).trim() === musicGroupFilter.value);
    $("#musicBrowseBack").hidden = false;
  } else if ($("#musicBrowseBack")) {
    $("#musicBrowseBack").hidden = true;
  }
  $("#videoGrid").hidden = playlistIndexView;
  $("#emptyState").hidden = playlistIndexView || items.length > 0;

  const heading = $("#libraryHeading");
  if (heading) heading.textContent = currentCategory === "vault" ? "الخزنة الخاصة" :
    currentCategory === "audio" ? (musicGroupFilter?.label || "الموسيقى والصوتيات") :
    currentCategory === "playlists" ? (selectedPlaylist ? selectedPlaylist.name : "قوائم التشغيل") :
    "مكتبة الفيديوهات";

  if (!items.length) {
    const h = $("#emptyState").querySelector("h3");
    const p = $("#emptyState").querySelector("p");
    const btn = $("#emptyState button");
    if (currentCategory === "playlists" && activePlaylistId) {
      h.textContent = "القائمة فارغة";
      p.textContent = "أضف فيديوهات أو صوتيات إليها من المكتبة.";
      btn.hidden = true;
    } else if (currentCategory === "vault" && !vaultOpen) {
      h.textContent = "الخزنة مقفولة";
      p.textContent = "افتح الخزنة من أيقونة التطبيق لإدخال كلمة المرور.";
      btn.hidden = true;
    } else if (q || filter !== "all") {
      h.textContent = "مفيش نتائج";
      p.textContent = "جرّب كلمة بحث أو تصنيف مختلف.";
      btn.hidden = true;
    } else {
      h.textContent = currentCategory === "audio" ? "مكتبة الصوتيات لسه فاضية" :
        currentCategory === "vault" ? "الخزنة فارغة" : "مكتبتك لسه فاضية";
      p.textContent = "ابدأ بإضافة ملفات من جهازك أو من رابط مباشر.";
      btn.hidden = false;
    }
  } else {
    $("#emptyState").querySelector("h3").textContent = "مكتبتك لسه فاضية";
    $("#emptyState").querySelector("p").textContent = "ابدأ بإضافة ملفات من جهازك أو من رابط مباشر.";
    $("#emptyState button").hidden = false;
  }

  const visibleItems = items.slice(0, mediaRenderLimit);
  for (const v of visibleItems) {
    const kind = v.kind || "video";
    const card = document.createElement("article");
    card.className = "video-card";

    const thumb = document.createElement("div");
    thumb.className = "thumb";
    if (kind === "video") {
      thumb.dataset.play = v.id;
      if (v.thumb) {
        const img = document.createElement("img");
        img.src = v.thumb;
        img.alt = "";
        img.loading = "lazy";
        img.decoding = "async";
        thumb.append(img);
      } else {
        const ph = document.createElement("span");
        ph.className = "thumb-placeholder";
        ph.textContent = "▶";
        thumb.append(ph);
      }
      if (v.nativeUri && !v.thumb) observeNativeVideoThumbnail(v, thumb);
    } else {
      thumb.classList.add("audio-thumb");
      if (v.thumb) {
        const cover = document.createElement("img");
        cover.src = v.thumb;
        cover.alt = "";
        cover.loading = "lazy";
        cover.decoding = "async";
        cover.style.cssText = "position:absolute;inset:0;width:100%;height:100%;object-fit:cover";
        thumb.append(cover);
      } else {
        const symbol = document.createElement("span");
        symbol.className = "audio-symbol";
        symbol.textContent = "♫";
        thumb.append(symbol);
      }
    }

    const duration = document.createElement("span");
    duration.className = "duration";
    duration.textContent = v.duration ? formatTime(v.duration) : formatBytes(v.size);
    thumb.append(duration);

    const prog = document.createElement("div");
    prog.className = "progress-line";
    const bar = document.createElement("span");
    bar.style.width = kind === "video" && v.duration
      ? Math.min(100,(v.position||0)/v.duration*100)+"%" : "0%";
    prog.append(bar);

    const body = document.createElement("div");
    body.className = "card-body";
    const mediaDisplayName = document.createElement("h3");
    mediaDisplayName.className = "media-display-name";
    mediaDisplayName.textContent = String(v.name || "بدون اسم");
    mediaDisplayName.style.cssText = "display:block;direction:auto;text-align:start;overflow-wrap:anywhere;margin:0 0 10px;font-size:16px;font-weight:700;";
    body.prepend(mediaDisplayName);

    const title = document.createElement("p");
    title.className = "card-title";
    title.textContent = v.name;

    const meta = document.createElement("div");
    meta.className = "card-meta";
    const date = document.createElement("span");
    date.textContent = new Date(v.created).toLocaleDateString("ar-EG");
    const size = document.createElement("span");
    size.textContent = formatBytes(v.size);
    meta.append(date,size);
    if (kind === "audio" && (v.artist || v.album)) {
      const trackInfo = document.createElement("span");
      trackInfo.textContent = [v.artist, v.album].filter(Boolean).join(" • ");
      trackInfo.style.cssText = "flex-basis:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap";
      meta.append(trackInfo);
    }
    if (kind === "audio") {
      const plays = document.createElement("span");
      plays.className = "track-play-count";
      plays.textContent = `▶ ${Number(v.playCount) || 0} مرة تشغيل`;
      meta.append(plays);
    }

    const actions = document.createElement("div");
    actions.className = "card-actions";

    if (kind === "video") {
      const play = document.createElement("button");
      play.textContent = v.position > 5 ? "▶ متابعة" : "▶ تشغيل";
      play.onclick = () => playVideo(v.id);
      actions.append(play);

      const poster = document.createElement("button");
      poster.textContent = "▧ الصورة";
      poster.title = "اختيار صورة مصغرة من الجهاز";
      poster.onclick = () => { pendingPosterId=v.id; $("#posterInput").click(); };
      actions.append(poster);
    } else {
      const play = document.createElement("button");
      play.textContent = currentAudioId === v.id && currentAudio && !currentAudio.paused
        ? "♫ يعمل الآن"
        : "♫ تشغيل الصوت";
      play.onclick = () => playMusicTrack(v.id);
      actions.append(play);
    }

    if (currentCategory !== "vault" && !isVaultItem(v)) {
      const move = document.createElement("button");
      move.textContent = kind === "audio"
        ? "⇄ نقل إلى الفيديوهات"
        : "⇄ نقل إلى الموسيقى";
      move.title = "تغيير مكان الملف في المكتبة دون نسخه أو حذفه";
      move.onclick = async () => {
        const oldKind = v.kind || "video";
        v.kind = oldKind === "audio" ? "video" : "audio";

        try {
          await dbRequest("readwrite", "put", v);
          toast(v.kind === "audio"
            ? "تم نقل الملف إلى الموسيقى. إذا كان فيديو فسيعمل بالصورة والصوت."
            : "تم نقل الملف إلى الفيديوهات.");
          await render();

          if (v.kind === "video" && mediaKind(v.type, v.filename) === "video") {
            getFile(v.filename).then(file => generateAndSaveThumb(v.id, file)).catch(() => {});
          }
        } catch (error) {
          console.error(error);
          v.kind = oldKind;
          toast("تعذر تغيير تصنيف الملف.");
        }
      };
      actions.append(move);
    }

    const fav = document.createElement("button");
    fav.textContent = v.favorite ? "♥ محفوظ" : "♡ المفضلة";
    fav.onclick = async () => {
      v.favorite = !v.favorite;
      await dbRequest("readwrite","put",v);
      await render();
    };

    const del = document.createElement("button");
    del.className = "delete";
    del.textContent = "×";
    del.title = "حذف الملف";
    del.onclick = async () => {
      if (currentCategory === "playlists" && activePlaylistId) {
        const playlist = await playlistRequest("get", activePlaylistId);
        if (!playlist) return;

        playlist.mediaIds = (playlist.mediaIds || []).filter(mediaId => mediaId !== v.id);
        await playlistRequest("put", playlist);
        toast("تمت إزالة الملف من قائمة التشغيل فقط.");
        await render();
      } else {
        deleteVideo(v.id);
      }
    };

    if (currentCategory !== "vault" && !isVaultItem(v)) {
      const addToPlaylist = document.createElement("button");
      addToPlaylist.textContent = "＋ قائمة";
      addToPlaylist.title = "إضافة أو إزالة من قائمة تشغيل";
      addToPlaylist.onclick = () => openPlaylistPicker(v);
      actions.append(addToPlaylist);
    }

    const rename = document.createElement("button");
    rename.className = "rename-media-btn";
    rename.textContent = "✎ تعديل الاسم";
    rename.title = "تعديل اسم الملف في المكتبة فقط";
    rename.onclick = async () => {
      const enteredName = prompt("اكتب الاسم الجديد:", String(v.name || ""));
      if (enteredName === null) return;
      const trimmedName = enteredName.trim();
      if (!trimmedName) {
        toast("الاسم لا يمكن أن يكون فارغًا.");
        return;
      }
      const newName = safeName(trimmedName);
      if (!newName.trim()) {
        toast("الاسم غير صالح.");
        return;
      }
      const oldName = v.name;
      try {
        v.name = newName;
        if (v.nativeUri) v.nameOverride = true;
        await dbRequest("readwrite", "put", v);
        toast("تم تعديل اسم الملف.");
        await render();
      } catch (error) {
        v.name = oldName;
        toast("تعذر حفظ الاسم الجديد.");
      }
    };

    actions.append(rename,fav,del);
    body.append(title,meta,actions);
    card.append(thumb,prog,body);
    cardFragment.append(card);
  }

  videoGrid.append(cardFragment);

  if (items.length > visibleItems.length) {
    const more = document.createElement("button");
    more.id = "loadMoreMedia";
    more.className = "load-more-media";
    more.type = "button";
    const remaining = items.length - visibleItems.length;
    more.textContent = `عرض المزيد · ${Math.min(MEDIA_RENDER_BATCH, remaining)} من ${remaining}`;
    more.onclick = () => {
      mediaRenderLimit += MEDIA_RENDER_BATCH;
      render().catch(error => {
        console.error("NOVRA incremental render failed:", error);
        toast("تعذر تحميل المزيد من عناصر المكتبة.");
      });
    };
    videoGrid.after(more);
  }
}

async function releaseCurrentNativePlayback() {
  const path = currentNativePlaybackPath;
  currentNativePlaybackPath = null;
  if (path && nativeMediaPlugin?.releasePlaybackFile) {
    try { await nativeMediaPlugin.releasePlaybackFile({path}); }
    catch (error) { console.debug("Temporary playback cache cleanup skipped:", error); }
  }
}

function formatPlayerTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return "00:00";
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  return hours
    ? `${hours}:${String(minutes).padStart(2,"0")}:${String(secs).padStart(2,"0")}`
    : `${String(minutes).padStart(2,"0")}:${String(secs).padStart(2,"0")}`;
}
function refreshPlayerControls() {
  const p = $("#videoPlayer");
  if (!p) return;
  const duration = Number.isFinite(p.duration) ? p.duration : 0;
  const current = Number.isFinite(p.currentTime) ? p.currentTime : 0;
  $("#currentTime").textContent = formatPlayerTime(current);
  $("#totalTime").textContent = formatPlayerTime(duration);
  $("#seekBar").value = duration ? Math.round(current / duration * 1000) : 0;
  $("#playToggle").textContent = p.paused ? "▶" : "Ⅱ";
  $("#muteToggle").textContent = p.muted || p.volume === 0 ? "🔇" : "🔊";
}
function togglePlayerPlayback() {
  const p = $("#videoPlayer");
  if (p.paused) p.play().catch(() => {});
  else p.pause();
}

let currentSubtitleUrl = null;

function subtitleToVtt(text) {
  let value = String(text || "").replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
  value = value.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
  if (!/^\s*WEBVTT(?:\s|$)/i.test(value)) value = "WEBVTT\n\n" + value;
  return value;
}

async function attachExternalSubtitle(file) {
  if (!file) return;
  const name = (file.name || "").toLowerCase();
  if (!name.endsWith(".srt") && !name.endsWith(".vtt")) {
    toast("الترجمة المدعومة حاليًا هي SRT وVTT.");
    return;
  }

  try {
    const text = await file.text();
    const vtt = subtitleToVtt(text);
    const blob = new Blob([vtt], { type: "text/vtt;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const player = $("#videoPlayer");

    player.querySelectorAll('track[data-novra-subtitle]').forEach(track => track.remove());
    if (currentSubtitleUrl) URL.revokeObjectURL(currentSubtitleUrl);
    currentSubtitleUrl = url;

    const track = document.createElement("track");
    track.kind = "subtitles";
    track.label = file.name;
    track.srclang = "ar";
    track.default = true;
    track.dataset.novraSubtitle = "1";
    track.src = url;
    player.append(track);

    track.addEventListener("load", () => {
      try { track.track.mode = "showing"; } catch {}
    }, { once: true });

    toast("تم تحميل ملف الترجمة. فعّلها من خيارات الترجمة في المشغل إذا لم تظهر تلقائيًا.");
  } catch (error) {
    console.error("Subtitle loading failed:", error);
    toast("تعذر قراءة ملف الترجمة.");
  }
}

async function togglePictureInPicture() {
  const player = $("#videoPlayer");
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
    } else if (player.requestPictureInPicture) {
      await player.requestPictureInPicture();
    } else if (player.webkitSetPresentationMode) {
      player.webkitSetPresentationMode("picture-in-picture");
    } else {
      toast("النافذة العائمة غير مدعومة في المتصفح الحالي.");
    }
  } catch (error) {
    console.warn("Picture-in-picture unavailable:", error);
    toast("تعذر تشغيل النافذة العائمة. تأكد أن الفيديو بدأ تشغيله أولًا.");
  }
}

function setupCustomPlayer() {
  const p = $("#videoPlayer");
  $("#playToggle").onclick = togglePlayerPlayback;
  $("#seekBar").addEventListener("input", e => {
    if (Number.isFinite(p.duration) && p.duration > 0) {
      p.currentTime = p.duration * Number(e.target.value) / 1000;
      refreshPlayerControls();
    }
  });
  $("#muteToggle").onclick = () => {
    p.muted = !p.muted;
    refreshPlayerControls();
  };
  $("#volumeBar").addEventListener("input", e => {
    p.volume = Number(e.target.value);
    p.muted = p.volume === 0;
    refreshPlayerControls();
  });
  $("#fullscreenToggle").onclick = async () => {
    const screen = $("#playerScreen");
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (screen.requestFullscreen) await screen.requestFullscreen();
      else if (p.webkitEnterFullscreen) p.webkitEnterFullscreen();
    } catch {}
  };
  $("#pipToggle").onclick = togglePictureInPicture;

  // NOVRA VIDEO — DOUBLE TAP SEEK
  const seekScreen = $("#playerScreen");
  if (!seekScreen.dataset.novraSeekBound) {
    seekScreen.dataset.novraSeekBound = "1";
    let feedbackTimer = null;

    seekScreen.addEventListener("dblclick", e => {
      if (e.target.closest(".custom-controls, button, input, select, .player-tools")) return;

      const duration = p.duration;
      if (!Number.isFinite(duration) || duration <= 0) return;

      const rect = seekScreen.getBoundingClientRect();
      const goBack = e.clientX < rect.left + rect.width / 2;
      const change = goBack ? -10 : 10;

      p.currentTime = Math.max(0, Math.min(duration, p.currentTime + change));
      refreshPlayerControls();

      let feedback = seekScreen.querySelector(".player-seek-feedback");
      if (!feedback) {
        feedback = document.createElement("div");
        feedback.className = "player-seek-feedback";
        feedback.setAttribute("aria-live", "polite");
        seekScreen.append(feedback);
      }

      feedback.textContent = goBack ? "↶ 10 ثوانٍ" : "10 ثوانٍ ↷";
      feedback.style.left = goBack ? "22%" : "78%";
      feedback.classList.remove("show");
      void feedback.offsetWidth;
      feedback.classList.add("show");

      clearTimeout(feedbackTimer);
      feedbackTimer = setTimeout(() => {
        feedback.classList.remove("show");
      }, 850);
    });
  }
  // END NOVRA VIDEO — DOUBLE TAP SEEK

  $("#subtitleOpen").onclick = () => $("#subtitleInput").click();
  $("#subtitleInput").addEventListener("change", async e => {
    await attachExternalSubtitle(e.target.files?.[0]);
    e.target.value = "";
  });

  const speedSelect = $("#speedSelect");
  const savedRate = Number(localStorage.getItem("novraVideoPlaybackRate") || "1");
  if (speedSelect) {
    const allowed = [...speedSelect.options].some(option => Number(option.value || option.text) === savedRate);
    if (allowed) speedSelect.value = String(savedRate);
    p.playbackRate = Number(speedSelect.value) || 1;
    speedSelect.addEventListener("change", () => {
      const rate = Number(speedSelect.value) || 1;
      p.playbackRate = rate;
      localStorage.setItem("novraVideoPlaybackRate", String(rate));
    });
  }
  p.addEventListener("loadedmetadata", () => {
    const rate = Number(localStorage.getItem("novraVideoPlaybackRate") || "1");
    if (Number.isFinite(rate) && rate >= 0.25 && rate <= 4) p.playbackRate = rate;
  });
  ["play","pause","timeupdate","loadedmetadata","volumechange","ended"].forEach(event => {
    p.addEventListener(event, () => {
      refreshPlayerControls();

      if (currentMusicVideo) {
        if (event === "timeupdate") {
          updateMusicMediaPosition();
        } else {
          updateMusicControls();
        }
      }
    });
  });
  document.addEventListener("fullscreenchange", () => {
    $("#fullscreenToggle").textContent = document.fullscreenElement ? "⛶" : "⛶";
  });
}


let secretVaultTaps = 0, secretVaultTimer = null, vaultOptionsTimer = null;
let playerControlsTimer = null, playerTapTimer = null;

function showPlayerControls() {
  const screen = $("#playerScreen");
  screen.classList.remove("controls-hidden");
  clearTimeout(playerControlsTimer);
  if (!$("#videoPlayer").paused) {
    playerControlsTimer = setTimeout(() => {
      if (!$("#videoPlayer").paused) screen.classList.add("controls-hidden");
    }, 10000);
  }
}

function hidePlayerVaultOptions() {
  $("#vaultActions").hidden = true;
  secretVaultTaps = 0;
  clearTimeout(vaultOptionsTimer);
}

async function playerVaultAccess() {
  if (vaultOpen) return true;
  return await requestVaultAccess("player");
}

async function copyCurrentVideoToVault() {
  if (!currentVideo) return;
  if (isVaultItem(currentVideo)) return toast("الفيديو موجود بالفعل داخل الخزنة.");
  if (!await playerVaultAccess() || !currentVideo) return;

  const source = currentVideo;
  const newId = id(), filename = `${newId}.media`;
  let written = false;
  try {
    await savePosition(true);
    const file = await getFile(source.filename);
    await writeFile(file, filename, () => {});
    written = true;
    await dbRequest("readwrite", "put", {
      ...source, id:newId, filename, vault:true,
      created:Date.now(), position:0, lastPlayed:0
    });
    toast("تم إنشاء نسخة مستقلة في الخزنة، والأصل لم يتغير.");
    hidePlayerVaultOptions();
    $("#vaultGearMenu").hidden = true;
    await render();
  } catch (e) {
    if (written) {
      try { await (await rootDirPromise).removeEntry(filename); } catch {}
    }
    console.error(e);
    toast("تعذر إنشاء النسخة داخل الخزنة.");
  }
}

async function moveCurrentVideoToVault() {
  if (!currentVideo) return;
  if (isVaultItem(currentVideo)) return toast("الفيديو موجود بالفعل داخل الخزنة.");
  if (!await playerVaultAccess() || !currentVideo) return;

  try {
    await savePosition(true);
    currentVideo.vault = true;
    await dbRequest("readwrite", "put", currentVideo);
    toast("تم نقل الفيديو إلى الخزنة دون حذف ملفه.");
    hidePlayerVaultOptions();
    $("#vaultGearMenu").hidden = true;
    await render();
  } catch (e) {
    console.error(e);
    toast("تعذر نقل الفيديو.");
  }
}


async function playVideo(videoId, musicMode = false) {
  const v = await dbRequest("readonly", "get", videoId);
  if (!v) return;

  currentMusicVideo = musicMode;
  videoLoopA = null;
  videoLoopB = null;
  const abLoopButton = $("#abLoopBtn");
  if (abLoopButton) abLoopButton.textContent = "A↔B تكرار جزء";
  if (!musicMode) {
    currentAudioId = null;
    currentMusicTitle = "";
  }

  try {
    if (currentAudio) currentAudio.pause();
    if (currentAudioUrl) {
      URL.revokeObjectURL(currentAudioUrl);
      currentAudioUrl = null;
    }
    const file = v.nativeUri ? null : await getFile(v.filename);
    const player = $("#videoPlayer");
    const screen = $("#playerScreen");

    if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);

    player.pause();
    player.removeAttribute("src");
    player.load();
    await releaseCurrentNativePlayback();

    currentObjectUrl = file ? URL.createObjectURL(file) : null;
    let playbackSource = currentObjectUrl;
    if (v.nativeUri) {
      if (!nativeMediaPlugin?.preparePlayback) throw new Error("تشغيل ملفات الجهاز يحتاج إصدار Android المحدث.");
      const prepared = await nativeMediaPlugin.preparePlayback({uri:v.nativeUri});
      currentNativePlaybackPath = prepared.path;
      playbackSource = window.Capacitor?.convertFileSrc?.(prepared.path);
      if (!playbackSource) throw new Error("تعذر تجهيز مسار تشغيل الملف.");
    }
    currentVideo = v;
    if (musicMode) currentMusicTitle = v.name || v.filename || "بدون اسم";

    $("#playerTitle").textContent = v.name;
    $("#favoritePlayer").textContent = v.favorite
      ? "♥ في المفضلة"
      : "♡ المفضلة";

    screen.classList.remove("is-portrait", "is-landscape");
    openModal("playerModal");

    // Read the video's real dimensions before displaying it.
    player.onloadedmetadata = () => {
      const isPortrait = player.videoHeight > player.videoWidth;

      screen.classList.toggle("is-portrait", isPortrait);
      screen.classList.toggle("is-landscape", !isPortrait);

      if (
        v.position > 5 &&
        Number.isFinite(player.duration) &&
        v.position < player.duration - 5
      ) {
        player.currentTime = v.position;
      }

      refreshPlayerControls();
    };

    player.onerror = () => {
      toast("تعذر تشغيل هذا الفيديو. قد يكون تنسيقه غير مدعوم على الهاتف.");
    };

    player.querySelectorAll('track[data-novra-subtitle]').forEach(track => track.remove());
    if (currentSubtitleUrl) {
      URL.revokeObjectURL(currentSubtitleUrl);
      currentSubtitleUrl = null;
    }

    player.src = playbackSource;
    player.load();

    player.play().then(() => {
      if (musicMode) updateMusicControls();
      refreshPlayerControls();
    }).catch(() => {
      refreshPlayerControls();
      if (musicMode) updateMusicControls();
    });

  } catch (e) {
    console.error("NOVRA VIDEO player error:", e);
    toast("تعذر فتح ملف الفيديو. ربما تم حذف بيانات الموقع من المتصفح.");
  }
}
let lastVideoPositionWriteAt = 0;
let videoLoopA = null;
let videoLoopB = null;

async function savePosition(force = false) {
  if (!currentVideo || !Number.isFinite($("#videoPlayer").currentTime)) return;

  const now = Date.now();
  if (!force && now - lastVideoPositionWriteAt < 4500) return;
  lastVideoPositionWriteAt = now;

  currentVideo.position = $("#videoPlayer").currentTime;
  currentVideo.duration = $("#videoPlayer").duration || currentVideo.duration;
  currentVideo.lastPlayed = now;

  try {
    await dbRequest("readwrite", "put", currentVideo);
  } catch (error) {
    console.warn("Could not save playback position:", error);
  }
}
function toggleVideoABLoop() {
  const player = $("#videoPlayer");
  const button = $("#abLoopBtn");
  if (!currentVideo || !Number.isFinite(player.currentTime)) {
    toast("شغّل فيديو أولًا لتحديد المقطع.");
    return;
  }

  if (videoLoopA === null || videoLoopB !== null) {
    videoLoopA = player.currentTime;
    videoLoopB = null;
    if (button) button.textContent = "حدد نهاية B";
    toast("تم تحديد بداية المقطع A. انتقل للنهاية واضغط مرة أخرى.");
    return;
  }

  if (player.currentTime <= videoLoopA + 0.25) {
    toast("نقطة النهاية B لازم تكون بعد نقطة البداية A.");
    return;
  }

  videoLoopB = player.currentTime;
  if (button) button.textContent = "إيقاف تكرار A-B";
  toast("تم تفعيل تكرار المقطع المحدد.");
}

async function captureVideoFrame() {
  const player = $("#videoPlayer");
  if (!player.videoWidth || !player.videoHeight) {
    toast("انتظر حتى يظهر إطار الفيديو أولًا.");
    return;
  }

  try {
    const canvas = document.createElement("canvas");
    canvas.width = player.videoWidth;
    canvas.height = player.videoHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("تعذر إنشاء صورة الإطار.");
    context.drawImage(player, 0, 0, canvas.width, canvas.height);

    const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/png"));
    if (!blob) throw new Error("تعذر تجهيز الصورة.");

    const filename = "Media-frame-" + new Date().toISOString().replace(/[:.]/g, "-") + ".png";
    const file = new File([blob], filename, { type: "image/png" });

    if (navigator.share && navigator.canShare?.({ files: [file] })) {
      await navigator.share({ files: [file], title: "لقطة من مشغل الوسائط" });
    } else {
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
    }
    toast("تم تجهيز لقطة الإطار.");
  } catch (error) {
    console.error("NOVRA frame capture failed:", error);
    toast(error?.message || "تعذر التقاط الإطار. قد يمنع مصدر الفيديو التقاط الصور.");
  }
}

async function deleteVideo(videoId) {
  const v = await dbRequest("readonly", "get", videoId);
  if (!v) return;
  const isDeviceMedia = !!v.nativeUri;
  const message = isDeviceMedia
    ? "إزالة هذا الملف من المكتبة فقط؟ سيظل الملف الأصلي محفوظًا على الهاتف."
    : "هل تريد حذف هذا الملف من المكتبة ومساحة التخزين الخاصة بها؟ لا يمكن التراجع عن الحذف.";
  if (!confirm(message)) return;
  if (isDeviceMedia) {
    const ignored = new Set(JSON.parse(localStorage.getItem(DEVICE_MEDIA_EXCLUSIONS_KEY) || "[]"));
    ignored.add(String(v.id));
    localStorage.setItem(DEVICE_MEDIA_EXCLUSIONS_KEY, JSON.stringify([...ignored]));
  } else {
    try { const root = await rootDirPromise; if (v.filename) await root.removeEntry(v.filename); } catch {}
  }
  await dbRequest("readwrite", "delete", videoId);
  toast(isDeviceMedia ? "تمت الإزالة من المكتبة، والملف الأصلي لم يُحذف." : "تم حذف الملف من المكتبة.");
  await render();
}
async function storageInfo() {
  const estimate=await navigator.storage?.estimate?.()||{}, used=estimate.usage||0, quota=estimate.quota||0;
  $("#storageDetails").textContent=`المستخدم في مساحة الموقع: ${formatBytes(used)} من ${formatBytes(quota)} تقريبًا.`;
  $("#storageBar").style.width=quota?Math.min(100,used/quota*100)+"%":"0%";
}
async function exportData() {
  const videos=await dbRequest("readonly","all");
  const blob=new Blob([JSON.stringify({app:"Media Library",version:1,exported:new Date().toISOString(),videos},null,2)],{type:"application/json"});
  const a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download="media-library-backup.json";a.click();URL.revokeObjectURL(a.href);
}
function requestVaultAccess(purpose = "browse") {
  if (vaultOpen) {
    if (purpose === "browse") navigatePage("vault", {skipGuard:true});
    return Promise.resolve(true);
  }

  return new Promise(resolve => {
    if (pendingVaultResolve) {
      resolve(false);
      return;
    }

    pendingVaultResolve = resolve;
    vaultPurpose = purpose;
    vaultSetupMode = !hasVaultPassword();

    $("#vaultTitle").textContent = vaultSetupMode
      ? "إنشاء كلمة مرور الخزنة"
      : "فتح الخزنة";

    $("#vaultMessage").textContent = vaultSetupMode
      ? "أنشئ كلمة مرور لا تقل عن 8 أحرف."
      : "أدخل كلمة مرور الخزنة للمتابعة.";

    $("#vaultConfirmWrap").hidden = !vaultSetupMode;
    $("#vaultPassword").value = "";
    $("#vaultPasswordConfirm").value = "";
    $("#vaultPassword").autocomplete = vaultSetupMode ? "new-password" : "current-password";
    $("#vaultSubmit").textContent = vaultSetupMode ? "إنشاء وفتح الخزنة" : "فتح الخزنة";

    openModal("vaultModal");
    $("#vaultPassword").focus();
  });
}

function finishVaultPrompt(success) {
  const resolve = pendingVaultResolve;
  pendingVaultResolve = null;
  closeModal("vaultModal");
  $("#vaultPassword").value = "";
  $("#vaultPasswordConfirm").value = "";

  if (!success && vaultPurpose === "import") {
    $("#chooseVault").checked = false;
  }

  if (resolve) resolve(success);
}

async function submitVaultPassword() {
  const password = $("#vaultPassword").value;

  try {
    if (vaultSetupMode) {
      const confirmation = $("#vaultPasswordConfirm").value;

      if (password.length < 8) {
        throw new Error("كلمة المرور يجب أن تكون 8 أحرف على الأقل.");
      }

      if (password !== confirmation) {
        throw new Error("كلمتا المرور غير متطابقتين.");
      }

      await createVaultPassword(password);
    } else {
      const valid = await verifyVaultPassword(password);
      if (!valid) throw new Error("كلمة المرور غير صحيحة.");
    }

    vaultOpen = true;

    if (vaultPurpose === "browse") {
      navigatePage("vault", {replace:true, skipGuard:true, render:false});
      document.querySelectorAll(".library-tab").forEach(tab => tab.classList.remove("active"));
    }

    finishVaultPrompt(true);
    toast(vaultSetupMode ? "تم إنشاء الخزنة وفتحها." : "تم فتح الخزنة.");
    await render();
  } catch (error) {
    toast(error.message || "تعذر فتح الخزنة.");
  }
}

function lockVault() {
  vaultOpen = false;
  if (currentPage === "vault") navigatePage("videos", {replace:true, skipGuard:true, render:false});

  if (currentCategory === "vault") {
    currentCategory = "video";
  }

  $("#chooseVault").checked = false;

  if (currentVideo && isVaultItem(currentVideo)) {
    $("#videoPlayer").pause();
    $("#videoPlayer").removeAttribute("src");
    $("#videoPlayer").load();

    if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
    currentObjectUrl = null;
    currentVideo = null;
    closeModal("playerModal");
  }

  if (currentAudio) {
    try {
      currentAudio.pause();
      currentAudio.src = "";
    } catch {}
    currentAudio = null;
  }

  if (currentAudioUrl) {
    URL.revokeObjectURL(currentAudioUrl);
    currentAudioUrl = null;
  }

  render();
}


function applyPageVisibility(page) {
  $("#homePage").hidden = page !== "home";
  $("#libraryPage").hidden = page === "home";
  const discovery = $("#onlineMusicSearch");
  if (discovery) discovery.hidden = page !== "music";
  const videoDiscovery = $("#onlineVideoDiscovery");
  if (videoDiscovery) videoDiscovery.hidden = page !== "videos";
  const musicBrowse = $("#musicBrowsePanel");
  if (musicBrowse) musicBrowse.hidden = page !== "music";
  const videoSearchButton = $("#onlineVideoSearchBtn");
  if (videoSearchButton) videoSearchButton.hidden = page !== "videos";
  document.querySelectorAll(".sidebar-link[data-page]").forEach(link => {
    link.classList.toggle("active", link.dataset.page === page);
    if (link.dataset.page === page) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
}

function navigatePage(page, options = {}) {
  const valid = ["home", "videos", "music", "playlists", "vault"];
  if (!valid.includes(page)) page = "home";

  if (page === "vault" && !vaultOpen && !options.skipGuard) {
    requestVaultAccess("browse");
    return;
  }
  if (currentPage === "vault" && page !== "vault" && vaultOpen) lockVault();

  currentPage = page;
  if (page === "videos") currentCategory = "video";
  if (page === "music") currentCategory = "audio";
  if (page === "playlists") currentCategory = "playlists";
  if (page === "vault") currentCategory = "vault";
  if (page !== "playlists") activePlaylistId = null;

  applyPageVisibility(page);
  const target = "#/" + page;
  if (options.replace) history.replaceState({page}, "", target);
  else if (location.hash !== target) history.pushState({page}, "", target);
  if (options.render !== false && db) render();
}

function routeFromLocation() {
  const route = (location.hash || "").replace(/^#\/?/, "");
  if (route === "vault" && !vaultOpen) {
    navigatePage("videos", {replace:true, render:false, skipGuard:true});
    return;
  }
  navigatePage(["home","videos","music","playlists","vault"].includes(route) ? route : "home",
    {replace:true, render:false, skipGuard:true});
}


let onlineSearchTimer = null;
let onlinePreviewAudio = null;
let onlinePreviewTrack = null;

function setupDiscoveryExperience() {
  const tabs = document.querySelector(".library-tabs");
  if (!tabs || document.querySelector("#onlineMusicSearch")) return;
  const videoPanel = document.createElement("section");
  videoPanel.id = "onlineVideoDiscovery";
  videoPanel.className = "online-video-discovery";
  videoPanel.innerHTML = '<div class="discovery-heading"><div><p class="eyebrow">استكشاف الفيديو</p><h3>اكتشف فيديوهات جديدة</h3><p>محتوى مفتوح ومرخّص، مع بحث YouTube الرسمي.</p></div><span class="discovery-mark">▶</span></div><form id="onlineVideoForm" class="online-search-form"><input id="onlineVideoQuery" type="search" placeholder="ابحث عن فيديو أو موضوع..." aria-label="بحث فيديو"><button class="primary-btn" type="submit">بحث</button><button class="secondary-btn" id="youtubeSearchAction" type="button">YouTube</button></form><div id="onlineVideoStatus" class="online-status">اكتب موضوعًا للبحث.</div><div id="onlineVideoResults" class="archive-results"></div><p class="online-disclaimer">راجع الترخيص وصفحة المصدر قبل تنزيل أو إعادة استخدام أي محتوى.</p>';
  videoPanel.querySelector("#onlineVideoForm").addEventListener("submit", event => { event.preventDefault(); searchLicensedArchive($("#onlineVideoQuery").value, "movies"); });
  videoPanel.querySelector("#youtubeSearchAction").addEventListener("click", () => { const query = $("#onlineVideoQuery").value.trim(); if (!query) { toast("اكتب موضوع البحث أولًا."); return; } window.open("https://www.youtube.com/results?search_query=" + encodeURIComponent(query), "_blank", "noopener,noreferrer"); });
  tabs.before(videoPanel);
  const panel = document.createElement("section");
  panel.id = "onlineMusicSearch";
  panel.className = "online-music-search";
  panel.innerHTML = `
    <div class="discovery-heading"><div><p class="eyebrow">اكتشاف عبر الإنترنت</p><h3>ابحث عن أغنية جديدة</h3><p>معاينات موسيقية رسمية وروابط المصدر.</p></div><span class="discovery-mark">♫</span></div>
    <form id="onlineMusicForm" class="online-search-form"><input id="onlineMusicQuery" type="search" placeholder="اسم الأغنية أو الفنان..." autocomplete="off" aria-label="بحث موسيقى عبر الإنترنت"><button class="primary-btn" type="submit">بحث</button></form>
    <div id="onlineMusicStatus" class="online-status">اكتب اسم أغنية أو فنان لبدء البحث.</div>
    <div id="onlineMusicResults" class="online-music-results"></div>
    <p class="online-disclaimer">معاينة ترويجية من Apple قد تصل إلى 30 ثانية، وبجوارها رابط Apple Music للمحتوى الكامل.</p>`;
  panel.insertAdjacentHTML("beforeend", '<div class="licensed-audio-block"><div class="licensed-audio-heading"><strong>موسيقى مجانية بترخيص معلن</strong><button id="licensedAudioSearch" class="secondary-btn" type="button">بحث في Internet Archive</button></div><div id="licensedAudioStatus" class="online-status">ابحث عن تسجيلات وأعمال منشورة بتراخيص مفتوحة.</div><div id="licensedAudioResults" class="archive-results"></div></div>');
  tabs.after(panel);
  $("#licensedAudioSearch")?.addEventListener("click", () => searchLicensedArchive($("#onlineMusicQuery")?.value || "", "audio"));
  const browsePanel = document.createElement("section");
  browsePanel.id = "musicBrowsePanel";
  browsePanel.className = "music-browse-panel";
  browsePanel.innerHTML = '<div class="music-browse-head"><strong>مكتبتي الموسيقية</strong><button id="musicBrowseBack" class="secondary-btn" type="button" hidden>← رجوع للقائمة</button></div><nav class="music-browse-tabs"><button type="button" data-music-view="tracks" class="active">الأغاني</button><button type="button" data-music-view="artists">الفنانون</button><button type="button" data-music-view="albums">الألبومات</button></nav>';
  const musicControlsPanel = document.querySelector("#musicControls");
  if (musicControlsPanel) musicControlsPanel.after(browsePanel); else panel.after(browsePanel);
  browsePanel.querySelectorAll("[data-music-view]").forEach(button => button.addEventListener("click", () => {
    musicBrowseMode = button.dataset.musicView;
    musicGroupFilter = null;
    browsePanel.querySelectorAll("[data-music-view]").forEach(tab => tab.classList.toggle("active", tab === button));
    render();
  }));
  $("#musicBrowseBack").addEventListener("click", () => { musicGroupFilter = null; render(); });
  $("#onlineMusicForm").addEventListener("submit", event => {
    event.preventDefault();
    searchOnlineMusic($("#onlineMusicQuery").value);
  });
  $("#onlineMusicQuery").addEventListener("input", () => {
    clearTimeout(onlineSearchTimer);
    const query = $("#onlineMusicQuery").value.trim();
    if (query.length < 2) return;
    onlineSearchTimer = setTimeout(() => searchOnlineMusic(query), 550);
  });
  $("#onlineVideoSearchBtn")?.addEventListener("click", () => {
    const query = $("#searchInput")?.value.trim() || "";
    $("#onlineVideoQuery").value = query;
    if (query) searchLicensedArchive(query, "movies"); else $("#onlineVideoQuery").focus();
    $("#onlineVideoDiscovery").scrollIntoView({behavior:"smooth",block:"start"});
  });
}

async function downloadLicensedArchive(item, mediaType, button) {
  const license = String(item?.licenseurl || "");
  let licenseHost = "";
  try { licenseHost = new URL(license).hostname.toLowerCase(); } catch {}
  if (!["creativecommons.org","www.creativecommons.org","publicdomain.org","www.publicdomain.org"].includes(licenseHost)) {
    toast("لم يتم تنزيل الملف لأن الترخيص لم يمكن التحقق منه.");
    return;
  }
  if (!item?.identifier) return;
  const originalLabel = button?.textContent || "تنزيل";
  if (button) { button.disabled = true; button.textContent = "جارٍ التجهيز…"; }
  try {
    const metadataResponse = await fetch("https://archive.org/metadata/" + encodeURIComponent(item.identifier));
    if (!metadataResponse.ok) throw new Error("metadata");
    const metadata = await metadataResponse.json();
    const files = Array.isArray(metadata.files) ? metadata.files : [];
    const allowedFormats = mediaType === "audio"
      ? ["VBR MP3","MP3","Ogg Vorbis","FLAC","WAVE","MPEG4 Audio"]
      : ["MPEG4","MPEG4 Movie","h.264","MPEG2"];
    const candidate = files
      .filter(file => file && file.name && allowedFormats.includes(String(file.format || "")))
      .filter(file => !file.source || file.source === "original")
      .filter(file => Number(file.size) > 0 && Number(file.size) <= 250 * 1024 * 1024)
      .sort((a,b) => Number(a.size)-Number(b.size))[0];
    if (!candidate) throw new Error("no-compatible-file");
    const downloadUrl = "https://archive.org/download/" + encodeURIComponent(item.identifier) + "/" + candidate.name.split("/").map(encodeURIComponent).join("/");
    const response = await fetch(downloadUrl);
    if (!response.ok) throw new Error("download");
    const blob = await response.blob();
    if (!blob.size || blob.size > 250 * 1024 * 1024) throw new Error("size");
    const filename = candidate.name.split("/").pop() || (mediaType === "audio" ? "licensed-audio.mp3" : "licensed-video.mp4");
    const file = new File([blob], filename, {type:blob.type || (mediaType === "audio" ? "audio/mpeg" : "video/mp4"),lastModified:Date.now()});
    const destination = $("#importDestination");
    const previousDestination = destination?.value;
    if (destination) destination.value = mediaType === "audio" ? "audio" : "video";
    try { await importLocal(file); }
    finally { if (destination && previousDestination) destination.value = previousDestination; }
    toast("تم تنزيل المحتوى المرخّص وإضافته إلى مكتبتك.");
  } catch (error) {
    console.warn("Licensed Archive download failed:", error);
    toast(error.message === "no-compatible-file"
      ? "لم نعثر على صيغة متوافقة بحجم أقل من 250 ميجابايت."
      : "تعذر التنزيل من المصدر. يمكنك فتح صفحة العمل ومراجعة خيارات التنزيل.");
  } finally {
    if (button) { button.disabled = false; button.textContent = originalLabel; }
  }
}

async function searchLicensedArchive(query, mediaType) {
  const term = String(query || "").trim();
  const isAudio = mediaType === "audio";
  const status = $(isAudio ? "#licensedAudioStatus" : "#onlineVideoStatus");
  const results = $(isAudio ? "#licensedAudioResults" : "#onlineVideoResults");
  if (!term) { status.textContent = "اكتب اسمًا أو موضوعًا للبحث أولًا."; return; }
  status.textContent = "جارٍ البحث في المحتوى المفتوح..."; results.replaceChildren();
  try {
    const safeTerm = term.replace(/["\\]/g, " ").slice(0,100);
    const q = "mediatype:" + mediaType + ' AND (licenseurl:http*creativecommons.org* OR licenseurl:http*publicdomain*) AND "' + safeTerm + '"';
    const params = new URLSearchParams({q:q,rows:"18",page:"1",output:"json"});
    ["identifier","title","creator","licenseurl","downloads"].forEach(field => params.append("fl[]",field));
    const response = await fetch("https://archive.org/advancedsearch.php?" + params.toString());
    if (!response.ok) throw new Error("archive-search");
    const data = await response.json(); const docs = data.response?.docs || [];
    if (!docs.length) { status.textContent = "لا توجد نتائج مرخّصة مطابقة. جرّب كلمات بحث مختلفة."; return; }
    status.textContent = "نتائج مفتوحة: " + docs.length + ". راجع الترخيص داخل صفحة كل نتيجة.";
    const fragment = document.createDocumentFragment();
    for (const item of docs) {
      if (!item.identifier || !item.licenseurl) continue;
      const card = document.createElement("article"); card.className = "archive-result";
      const cover = document.createElement("img"); cover.src = "https://archive.org/services/img/" + encodeURIComponent(item.identifier); cover.alt = ""; cover.loading = "lazy";
      const info = document.createElement("div"); info.className = "archive-result-info";
      const title = document.createElement("strong"); title.textContent = item.title || item.identifier;
      const creator = document.createElement("small"); creator.textContent = item.creator || "صاحب العمل غير محدد";
      const license = document.createElement("a"); license.href = String(item.licenseurl); license.target = "_blank"; license.rel = "noopener noreferrer"; license.textContent = "عرض الترخيص";
      info.append(title,creator,license);
      const actions = document.createElement("div");
      actions.className = "archive-result-actions";
      const source = document.createElement("a"); source.className = "archive-open"; source.href = "https://archive.org/details/" + encodeURIComponent(item.identifier); source.target = "_blank"; source.rel = "noopener noreferrer"; source.textContent = "فتح المصدر";
      const download = document.createElement("button");
      download.type = "button"; download.className = "archive-download";
      download.textContent = isAudio ? "تنزيل الأغنية" : "تنزيل الفيديو";
      download.addEventListener("click", () => downloadLicensedArchive(item, mediaType, download));
      actions.append(source,download);
      card.append(cover,info,actions); fragment.append(card);
    }
    results.append(fragment);
  } catch (error) { console.warn("Open archive search failed:",error); status.textContent = "تعذر الاتصال بمكتبة المحتوى المفتوح. تحقق من الإنترنت وحاول لاحقًا."; }
}
async function searchOnlineMusic(query) {
  const term = String(query || "").trim();
  const status = $("#onlineMusicStatus");
  const results = $("#onlineMusicResults");
  if (!term) { status.textContent = "اكتب اسم أغنية أو فنان أولًا."; return; }
  status.textContent = "جارٍ البحث عن الأغاني...";
  results.replaceChildren();
  try {
    const endpoint = "https://itunes.apple.com/search?media=music&entity=song&limit=24&term=" + encodeURIComponent(term);
    const response = await fetch(endpoint);
    if (!response.ok) throw new Error("خدمة البحث غير متاحة حاليًا.");
    const data = await response.json();
    const tracks = (data.results || []).filter(track => track.trackName && track.artistName);
    if (!tracks.length) { status.textContent = "لم نعثر على نتائج مطابقة. جرّب اسمًا آخر."; return; }
    status.textContent = `تم العثور على ${tracks.length} نتيجة. اضغط تشغيل لسماع المعاينة.`;
    const fragment = document.createDocumentFragment();
    for (const track of tracks) {
      const card = document.createElement("article");
      card.className = "online-track";
      const cover = document.createElement("img");
      cover.src = String(track.artworkUrl100 || "").replace("100x100bb", "240x240bb");
      cover.alt = "";
      cover.loading = "lazy";
      const info = document.createElement("div");
      info.className = "online-track-info";
      const title = document.createElement("strong");
      title.textContent = track.trackName;
      const artist = document.createElement("small");
      artist.textContent = track.artistName + (track.collectionName ? " · " + track.collectionName : "");
      info.append(title, artist);
      const actions = document.createElement("div");
      actions.className = "online-track-actions";
      if (track.previewUrl) {
        const play = document.createElement("button");
        play.type = "button";
        play.className = "preview-button";
        play.textContent = "▶ معاينة";
        play.addEventListener("click", () => playOnlinePreview(track, play));
        actions.append(play);
      }
      if (track.trackViewUrl) {
        const source = document.createElement("a");
        source.href = track.trackViewUrl;
        source.target = "_blank";
        source.rel = "noopener noreferrer";
        source.textContent = "Apple Music";
        actions.append(source);
      }
      card.append(cover, info, actions);
      fragment.append(card);
    }
    results.append(fragment);
  } catch (error) {
    console.error("Online music search failed:", error);
    status.textContent = "تعذر الاتصال بخدمة البحث. تحقق من الإنترنت وحاول مجددًا.";
  }
}

async function playOnlinePreview(track, button) {
  if (!track.previewUrl) return;
  if (currentAudio && !currentAudio.paused) currentAudio.pause();
  if (currentMusicVideo) $("#videoPlayer")?.pause();
  if (!onlinePreviewAudio) {
    onlinePreviewAudio = new Audio();
    onlinePreviewAudio.preload = "none";
    onlinePreviewAudio.addEventListener("ended", () => {
      onlinePreviewTrack = null;
      document.querySelectorAll(".preview-button").forEach(item => item.textContent = "▶ معاينة");
    });
  }
  if (onlinePreviewTrack === track.trackId && !onlinePreviewAudio.paused) {
    onlinePreviewAudio.pause();
    button.textContent = "▶ معاينة";
    return;
  }
  onlinePreviewAudio.pause();
  onlinePreviewAudio.src = track.previewUrl;
  onlinePreviewTrack = track.trackId;
  document.querySelectorAll(".preview-button").forEach(item => item.textContent = "▶ معاينة");
  button.textContent = "Ⅱ إيقاف";
  try {
    await onlinePreviewAudio.play();
  } catch (error) {
    button.textContent = "▶ معاينة";
    toast("تعذر تشغيل المعاينة. جرّب فتح المصدر الرسمي.");
  }
  onlinePreviewAudio.onpause = () => {
    if (onlinePreviewTrack === track.trackId) button.textContent = "▶ معاينة";
  };
}

function setupSettingsExperience() {
  const theme = localStorage.getItem("novraTheme") || "dark";
  document.documentElement.dataset.theme = theme;
  const themeSelect = $("#themeSetting");
  const speedSelect = $("#defaultSpeedSetting");
  applySavedWallpaper();
  const wallpaperInput = $("#wallpaperInput");
  $("#chooseWallpaperBtn")?.addEventListener("click", () => wallpaperInput?.click());
  wallpaperInput?.addEventListener("change", async () => {
    const file = wallpaperInput.files?.[0];
    if (!file || !file.type.startsWith("image/")) return;
    try {
      const image = await compressWallpaper(file);
      localStorage.setItem("vioraCustomWallpaper", image);
      applySavedWallpaper();
      toast("تم تطبيق الخلفية الجديدة.");
    } catch (error) { console.error(error); toast("تعذر تطبيق الصورة كخلفية."); }
    wallpaperInput.value = "";
  });
  $("#clearWallpaperBtn")?.addEventListener("click", () => {
    localStorage.removeItem("vioraCustomWallpaper");
    applySavedWallpaper();
    toast("تمت استعادة الخلفية الافتراضية.");
  });
  if (themeSelect) {
    themeSelect.value = theme;
    themeSelect.addEventListener("change", () => {
      document.documentElement.dataset.theme = themeSelect.value;
      localStorage.setItem("novraTheme", themeSelect.value);
    });
  }
  if (speedSelect) {
    speedSelect.value = localStorage.getItem("novraVideoPlaybackRate") || "1";
    speedSelect.addEventListener("change", () => {
      localStorage.setItem("novraVideoPlaybackRate", speedSelect.value);
      if ($("#speedSelect")) $("#speedSelect").value = speedSelect.value;
      if ($("#videoPlayer")) $("#videoPlayer").playbackRate = Number(speedSelect.value);
    });
  }
  $("#settingsScanVideo")?.addEventListener("click", () => syncDeviceMedia("video"));
  $("#settingsScanAudio")?.addEventListener("click", () => syncDeviceMedia("audio"));
  $("#settingsExportBtn")?.addEventListener("click", exportData);
}

function formatListeningTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const hours = Math.floor(total / 3600), minutes = Math.floor((total % 3600) / 60);
  if (hours) return `${hours} س ${minutes} د`;
  if (minutes) return `${minutes} دقيقة`;
  return `${total} ث`;
}

let listeningPendingSeconds = 0;
async function persistListeningTime() {
  if (!currentAudioId || !listeningPendingSeconds) return;
  const amount = listeningPendingSeconds;
  listeningPendingSeconds = 0;
  const track = await dbRequest("readonly", "get", currentAudioId).catch(() => null);
  if (!track) return;
  track.listenedSeconds = (Number(track.listenedSeconds) || 0) + amount;
  await dbRequest("readwrite", "put", track);
  const stat = $("#listeningTimeValue");
  if (stat) {
    const all = await dbRequest("readonly", "all");
    stat.textContent = formatListeningTime(all.filter(x => x.kind === "audio" && !isVaultItem(x)).reduce((sum,x) => sum + (Number(x.listenedSeconds)||0), 0));
  }
}

function startListeningTracker() {
  stopListeningTracker();
  listeningLastTick = Date.now();
  listeningPersistAt = listeningLastTick;
  listeningTick = setInterval(() => {
    if ((!nativeBackgroundPlayback && (!currentAudio || currentAudio.paused)) || (nativeBackgroundPlayback && !nativeBackgroundPlaying) || !currentAudioId || currentMusicVideo) return;
    const now = Date.now();
    const elapsed = Math.min(3, Math.max(0, Math.floor((now - listeningLastTick) / 1000)));
    listeningLastTick = now;
    if (!elapsed) return;
    listeningPendingSeconds += elapsed;
    if (now - listeningPersistAt >= 10000) {
      listeningPersistAt = now;
      persistListeningTime().catch(error => console.warn("Listening stats save failed:", error));
    }
  }, 1000);
}

function stopListeningTracker() {
  if (listeningTick) clearInterval(listeningTick);
  listeningTick = null;
  listeningLastTick = 0;
  persistListeningTime().catch(error => console.warn("Listening stats save failed:", error));
}

function compressWallpaper(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file), image = new Image();
    image.onload = () => {
      const scale = Math.min(1, 1920 / Math.max(image.width, image.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(image.width * scale));
      canvas.height = Math.max(1, Math.round(image.height * scale));
      canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
      URL.revokeObjectURL(url);
      try { resolve(canvas.toDataURL("image/jpeg", 0.78)); } catch (error) { reject(error); }
    };
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error("Invalid image")); };
    image.src = url;
  });
}

function applySavedWallpaper() {
  const image = localStorage.getItem("vioraCustomWallpaper");
  document.documentElement.style.setProperty("--viora-wallpaper", image ? `url("${image}")` : "none");
}

function setupPageRouter() {
  setupDiscoveryExperience();
  const launch = document.createElement("section");
  launch.id = "homeLaunchGrid";
  launch.className = "home-launch-grid";
  launch.innerHTML = `
    <button class="home-launch-card" data-go-page="videos"><span>▦</span><strong>مكتبة الفيديوهات</strong><small>استكشف مجموعتك</small><b>←</b></button>
    <button class="home-launch-card" data-go-page="music"><span>♫</span><strong>مساحة الموسيقى</strong><small>استمع إلى ملفاتك الصوتية</small><b>←</b></button>
    <button class="home-launch-card" data-go-page="playlists"><span>☷</span><strong>قوائم التشغيل</strong><small>نظّم محتواك</small><b>←</b></button>`;
  $("#statsPanel").after(launch);

  document.querySelectorAll(".sidebar-link[data-page]").forEach(link => {
    link.addEventListener("click", e => {
      e.preventDefault();
      navigatePage(link.dataset.page);
    });
  });
  document.addEventListener("click", e => {
    const card = e.target.closest("[data-go-page]");
    if (card) navigatePage(card.dataset.goPage);
  });
  window.addEventListener("popstate", routeFromLocation);
  window.addEventListener("hashchange", () => {
    if (location.hash !== "#/" + currentPage) routeFromLocation();
  });

  if (location.hash.startsWith("#/")) routeFromLocation();
  else navigatePage("home", {replace:true, render:false, skipGuard:true});
}

async function init() {
  // Bind the interface before waiting for storage or Android APIs.
  // A slow/blocked database must never leave the whole app visually dead.
  setupPageRouter();
  if (!("storage" in navigator) || !navigator.storage.getDirectory) {
    toast("التخزين المحلي المتقدم غير متاح في هذا الإصدار من WebView.");
  }

  const scanDeviceButton = $("#scanDeviceBtn");
  if (scanDeviceButton) {
    scanDeviceButton.hidden = true;
    scanDeviceButton.onclick = () => syncDeviceMedia(currentCategory === "audio" ? "audio" : "video");
  }

  window.addEventListener("novraShareIntent", () => {
    if (!db) return;
    importNativeSharedFiles().catch(error => {
      console.error("NOVRA foreground share import failed:", error);
      toast("تعذر استيراد المشاركة الجديدة.");
    });
  });

  const nativeReady = Promise.resolve(window.__novraNativeReady || null)
    .then(plugin => {
      nativeMediaPlugin = plugin || null;
      if (scanDeviceButton) scanDeviceButton.hidden = !nativeMediaPlugin;
      if (!nativeMediaPlugin) return null;
      document.addEventListener("visibilitychange", () => {
        if (!document.hidden && db) refreshNativeMediaIfAllowed();
      });
      if (typeof nativeMediaPlugin.addListener === "function") {
        nativeMediaPlugin.addListener("nativePlaybackState", state => {
          if (!nativeBackgroundPlayback) return;
          const wasPlaying = nativeBackgroundPlaying;
          nativeBackgroundPlaying = !!state?.playing;
          if (nativeBackgroundPlaying && !wasPlaying) startListeningTracker();
          if (!nativeBackgroundPlaying && wasPlaying) stopListeningTracker();
          if (state?.ended) {
            nativeBackgroundPlaying = false;
            stopListeningTracker();
            if (musicRepeatMode === "one") {
              nativeMediaPlugin.controlBackgroundPlayback({command:"seek",position:0})
                .then(() => nativeMediaPlugin.controlBackgroundPlayback({command:"resume"}))
                .catch(() => {});
            } else {
              advanceMusic(1);
            }
          }
          updateMusicControls();
        }).catch(error => console.debug("Native playback state listener unavailable:", error));
      }
      if (nativeMediaPlugin.getAudioOutputs) {
        const refreshAudioOutputs = async () => {
          try {
            const result = await nativeMediaPlugin.getAudioOutputs();
            const outputs = result?.outputs || [];
            const bluetooth = outputs.find(item => String(item.type || "").startsWith("bluetooth"));
            const wired = outputs.find(item => String(item.type || "").startsWith("wired"));
            const label = bluetooth ? "Bluetooth متصل" : wired ? "سماعة سلكية متصلة" : outputs.some(item => item.type === "speaker") ? "مكبر صوت الهاتف" : "مخرج الصوت حسب إعدادات Android";
            const status = $("#musicOutputStatus");
            if (status) status.textContent = label;
          } catch (error) { console.debug("Audio output query unavailable:", error); }
        };
        refreshAudioOutputs();
        if (typeof nativeMediaPlugin.addListener === "function") {
          nativeMediaPlugin.addListener("audioOutputsChanged", refreshAudioOutputs).catch(() => {});
        }
      }
      if (typeof nativeMediaPlugin.addListener === "function") {
        let lastMediaEventAt = 0;
        Promise.resolve(nativeMediaPlugin.addListener("mediaChanged", () => {
          const now = Date.now();
          if (now - lastMediaEventAt < 15000) return;
          lastMediaEventAt = now;
          lastNativeMediaRefreshAt = 0;
          if (db) refreshNativeMediaIfAllowed();
        })).catch(error => console.debug("NOVRA media observer listener unavailable:", error));
      }
      return nativeMediaPlugin;
    })
    .catch(error => {
      console.warn("NOVRA native bridge unavailable:", error);
      return null;
    });

  $("#heroAdd").onclick = $("#emptyAdd").onclick = () => openModal("addModal");

  $("#chooseFile").onclick = () => $("#fileInput").click();
  $("#chooseGallery").onclick = () => $("#galleryInput").click();

  $("#fileInput").onchange = async e => {
    const files = [...e.target.files];

    if ($("#chooseVault").checked && !vaultOpen) {
      const allowed = await requestVaultAccess("import");
      if (!allowed) {
        e.target.value = "";
        return;
      }
    }

    let successfulSinceRender = 0;

    for (const file of files) {
      await new Promise(resolve => requestAnimationFrame(resolve));

      try {
        await importLocal(file);
        successfulSinceRender++;

        if (successfulSinceRender >= 2) {
          await render();
          successfulSinceRender = 0;
          await new Promise(resolve => requestAnimationFrame(resolve));
        }
      } catch (err) {
        toast(err.message || "تعذرت إضافة الملف.");
      }
    }

    e.target.value = "";
    closeModal("addModal");
    await render();
  };

  $("#galleryInput").onchange = async e => {
    const files = [...e.target.files];

    if ($("#chooseVault").checked && !vaultOpen) {
      const allowed = await requestVaultAccess("import");
      if (!allowed) {
        e.target.value = "";
        return;
      }
    }

    for (const file of files) {
      try {
        await importLocal(file);
      } catch (err) {
        toast(err.message || "تعذرت إضافة الملف.");
      }
    }

    e.target.value = "";
    closeModal("addModal");
    await render();
  };

  $("#chooseVault").onchange = async e => {
    if (e.target.checked && !vaultOpen) {
      const allowed = await requestVaultAccess("import");
      if (!allowed) e.target.checked = false;
    }
  };

  $("#vaultSubmit").onclick = submitVaultPassword;

  $("#vaultPassword").addEventListener("keydown", e => {
    if (e.key === "Enter") {
      if (vaultSetupMode) $("#vaultPasswordConfirm").focus();
      else submitVaultPassword();
    }
  });

  $("#vaultPasswordConfirm").addEventListener("keydown", e => {
    if (e.key === "Enter") submitVaultPassword();
  });

  $("#importDestination").onchange = () => {
    const labels = {
      auto: "سيتم تحديد الوجهة حسب نوع الملف.",
      video: "سيتم حفظ الملفات في مكتبة الفيديوهات.",
      audio: "سيتم حفظ الملفات في مكتبة الموسيقى والصوتيات."
    };
    $("#importDestinationLabel").textContent = labels[$("#importDestination").value] || "";
  };

  $("#posterInput").onchange = async e => {
    const file = e.target.files?.[0], target = pendingPosterId;
    e.target.value = "";
    pendingPosterId = null;

    if (!file || !target) return;

    try {
      const v = await dbRequest("readonly", "get", target);
      if (!v) return;

      v.thumb = await makeCustomThumb(file);
      v.customThumb = true;

      await dbRequest("readwrite", "put", v);
      toast("تم تحديث الصورة المصغرة.");
      await render();
    } catch (err) {
      toast(err.message || "تعذر استخدام الصورة.");
    }
  };

  $("#importUrl").onclick = async () => {
    if ($("#chooseVault").checked && !vaultOpen) {
      const allowed = await requestVaultAccess("import");
      if (!allowed) return;
    }
    await importUrl();
  };

  $("#urlInput").addEventListener("keydown", e => {
    if (e.key === "Enter") $("#importUrl").click();
  });

  document.querySelectorAll("[data-close]").forEach(button => {
    button.onclick = () => {
      const id = button.dataset.close;
      if (id === "vaultModal") {
        finishVaultPrompt(false);
      } else {
        closeModal(id);
      }
    };
  });

  document.querySelectorAll(".modal-backdrop").forEach(modal => {
    modal.addEventListener("click", e => {
      if (e.target !== modal) return;

      if (modal.id === "vaultModal") {
        finishVaultPrompt(false);
      } else {
        closeModal(modal.id);
      }
    });
  });

  $("#createPlaylistBtn").onclick = async () => {
    const input = $("#playlistNameInput");
    const name = input.value.trim();

    if (!name) {
      toast("اكتب اسم قائمة التشغيل أولًا.");
      input.focus();
      return;
    }

    const playlists = await playlistRequest("all");
    if (playlists.some(p => p.name.toLowerCase() === name.toLowerCase())) {
      toast("يوجد بالفعل قائمة بنفس الاسم.");
      return;
    }

    await playlistRequest("put", {
      id: id(),
      name,
      mediaIds: [],
      created: Date.now()
    });

    input.value = "";
    toast("تم إنشاء قائمة التشغيل.");
    await render();
  };

  $("#playlistNameInput").addEventListener("keydown", e => {
    if (e.key === "Enter") $("#createPlaylistBtn").click();
  });

  $("#playlistBack").onclick = () => {
    activePlaylistId = null;
    render();
  };

  document.querySelectorAll(".library-tab").forEach(tab => {
    tab.onclick = () => {
      currentCategory = tab.dataset.category;
      activePlaylistId = null;
      const destination = currentCategory === "audio" ? "music" :
        currentCategory === "playlists" ? "playlists" : "videos";
      navigatePage(destination, {render:false});

      if (currentCategory !== "vault") {
        document.querySelectorAll(".library-tab").forEach(x => {
          x.classList.toggle("active", x === tab);
        });
      }

      render();
    };
  });

  document.querySelectorAll(".filter").forEach(button => {
    button.onclick = () => {
      document.querySelectorAll(".filter").forEach(x => x.classList.remove("active"));
      button.classList.add("active");
      render();
    };
  });

  $("#searchInput").oninput = () => {
    clearTimeout(searchRenderTimer);
    searchRenderTimer = setTimeout(() => {
      render().catch(error => {
        console.error("Search render failed:", error);
        toast("تعذر تحديث نتائج البحث.");
      });
    }, 180);
  };
  $("#sortSelect").onchange = render;

  $("#musicPrevBtn").onclick = () => advanceMusic(-1);
  $("#musicNextBtn").onclick = () => advanceMusic(1);
  $("#musicPlayPauseBtn").onclick = toggleMusicPlayPause;
  $("#musicShuffleBtn").onclick = toggleMusicShuffle;
  $("#musicRepeatBtn").onclick = cycleMusicRepeat;

  $("#videoGrid").addEventListener("click", e => {
    const target = e.target.closest("[data-play]");
    if (target) playVideo(target.dataset.play);
  });

  setupSettingsExperience();
  $("#settingsBtn").onclick = () => openModal("settingsModal");

  $("#settingsStorageBtn").onclick = async () => {
    await storageInfo();
    closeModal("settingsModal");
    openModal("storageModal");
  };

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) return;
    if (currentAudio && !currentAudio.paused) { /* keep listening while audio is playing */ }

    const vaultVideo = currentVideo && isVaultItem(currentVideo);
    const vaultAudio = currentCategory === "vault" && currentAudio;

    if (vaultVideo || vaultAudio || currentCategory === "vault") {
      if (vaultVideo) {
        $("#videoPlayer").pause();
        $("#videoPlayer").removeAttribute("src");
        $("#videoPlayer").load();

        if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
        currentObjectUrl = null;
        currentVideo = null;
        closeModal("playerModal");
      }

      lockVault();
    }
  });

  setupCustomPlayer();

  $("#videoPlayer").addEventListener("timeupdate", () => {
    const player = $("#videoPlayer");
    if (videoLoopA !== null && videoLoopB !== null && player.currentTime >= videoLoopB) {
      player.currentTime = videoLoopA;
    }
    if (Math.floor(player.currentTime) % 5 === 0) savePosition();
  });

  $("#videoPlayer").addEventListener("pause", () => savePosition(true));

  $("#videoPlayer").addEventListener("ended", async () => {
    if (currentVideo) {
      currentVideo.position = 0;
      await dbRequest("readwrite", "put", currentVideo);
    }

    if (currentMusicVideo && currentAudioId) {
      handleMusicEnded();
    }
  });

  $("#speedSelect").onchange = () => {
    $("#videoPlayer").playbackRate = Number($("#speedSelect").value);
  };
  $("#abLoopBtn").onclick = toggleVideoABLoop;
  $("#captureFrameBtn").onclick = captureVideoFrame;


  let logoVaultTaps = 0, logoVaultTimer = null;
  const logoMark = document.querySelector(".brand-mark");
  if (logoMark) {
    logoMark.addEventListener("click", () => {
      logoVaultTaps++;
      clearTimeout(logoVaultTimer);
      logoVaultTimer = setTimeout(() => logoVaultTaps = 0, 2500);
      if (logoVaultTaps >= 5) {
        logoVaultTaps = 0;
        requestVaultAccess("browse");
      }
    });
  }

  $("#vaultGearBtn").onclick = () => {
    $("#vaultGearMenu").hidden = !$("#vaultGearMenu").hidden;
    hidePlayerVaultOptions();
  };

  $("#vaultSecretTrigger").onclick = () => {
    secretVaultTaps++;
    clearTimeout(secretVaultTimer);
    secretVaultTimer = setTimeout(() => secretVaultTaps = 0, 3000);
    if (secretVaultTaps >= 5) {
      secretVaultTaps = 0;
      $("#vaultActions").hidden = false;
      clearTimeout(vaultOptionsTimer);
      vaultOptionsTimer = setTimeout(hidePlayerVaultOptions, 30000);
      toast("ظهرت خيارات الخزنة لمدة 30 ثانية.");
    }
  };

  $("#copyVideoToVault").onclick = copyCurrentVideoToVault;
  $("#moveVideoToVault").onclick = moveCurrentVideoToVault;

  $("#playerScreen").addEventListener("click", e => {
    if (e.target !== $("#videoPlayer")) return;
    const v = $("#videoPlayer"), r = v.getBoundingClientRect();
    const x = e.clientX - r.left, w = r.width;

    if (playerTapTimer) {
      clearTimeout(playerTapTimer);
      playerTapTimer = null;
      if (x > w * .62) {
        v.currentTime = Math.min(v.duration || Infinity, v.currentTime + 10);
        toast("تقديم 10 ثوانٍ");
      } else if (x < w * .38) {
        v.currentTime = Math.max(0, v.currentTime - 10);
        toast("رجوع 10 ثوانٍ");
      }
      showPlayerControls();
      return;
    }

    playerTapTimer = setTimeout(() => {
      playerTapTimer = null;
      const screen = $("#playerScreen");
      if (screen.classList.contains("controls-hidden")) showPlayerControls();
      else {
        clearTimeout(playerControlsTimer);
        screen.classList.add("controls-hidden");
      }
    }, 260);
  });

  $("#playerScreen .custom-controls").addEventListener("pointerdown", () => {
    showPlayerControls();
  });
  $("#playerScreen .custom-controls").addEventListener("input", () => {
    showPlayerControls();
  });

  $("#videoPlayer").addEventListener("play", showPlayerControls);
  $("#videoPlayer").addEventListener("pause", () => {
    clearTimeout(playerControlsTimer);
    $("#playerScreen").classList.remove("controls-hidden");
  });

  $("#favoritePlayer").onclick = async () => {
    if (!currentVideo) return;

    currentVideo.favorite = !currentVideo.favorite;
    await dbRequest("readwrite", "put", currentVideo);

    $("#favoritePlayer").textContent = currentVideo.favorite ? "♥ في المفضلة" : "♡ المفضلة";
    await render();
  };

  $("#playerModal").querySelector(".close-btn").addEventListener("click", async () => {
    await savePosition(true);
    $("#videoPlayer").pause();
    $("#videoPlayer").removeAttribute("src");
    $("#videoPlayer").load();

    if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
    currentObjectUrl = null;
    currentVideo = null;
    await releaseCurrentNativePlayback();

    if (currentMusicVideo) {
      currentMusicVideo = false;
      currentAudioId = null;
      currentMusicTitle = "";
      musicLoadToken++;
      updateMusicControls();
    }

    await render();
  });

  $("#storageBtn")?.addEventListener("click", async () => {
    await storageInfo();
    openModal("storageModal");
  });

  $("#exportLibrary").onclick = exportData;

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js", {updateViaCache:"none"}).then(reg => reg.update().catch(() => {})).catch(error => {
      console.warn("Service Worker registration failed:", error);
    });
  }

  // Storage initialization happens only after all navigation and controls are live.
  try {
    db = await openDB();
    loadMusicEqSettings();

    if (navigator.storage && typeof navigator.storage.persist === "function") {
      try { await navigator.storage.persist(); } catch {}
    }

    await nativeReady;
    await importNativeSharedFiles();
    await importSharedFiles();
    await render();

    if (nativeMediaPlugin) {
      const scheduleRefresh = () => refreshNativeMediaIfAllowed();
      if ("requestIdleCallback" in window) {
        window.requestIdleCallback(scheduleRefresh, { timeout: 5000 });
      } else {
        setTimeout(scheduleRefresh, 1800);
      }
    }
    document.documentElement.dataset.novraReady = "true";
  } catch (error) {
    console.error("NOVRA startup failed:", error);
    document.documentElement.dataset.novraReady = "error";
    toast("تعذر تهيئة المكتبة: " + (error?.message || "خطأ غير معروف"));
  }
}
init().catch(e=>{
  console.error("NOVRA interface initialization failed:",e);
  document.documentElement.dataset.novraReady = "error";
  try { toast("تعذر تشغيل الواجهة: "+(e?.message||"خطأ غير معروف")); } catch {}
});
