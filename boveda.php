<?php
declare(strict_types=1);
/* Bóveda Gran Logia · Un solo archivo para Hostinger.
 * El endpoint de dispositivos únicamente acepta POST por HTTPS y token o código de un solo uso.
 * El panel y su API necesitan sesión activa de rango 4 / tarea general.
 * Avisar explícitamente al dueño del dispositivo sobre consulta remota y descarga.
 */
require_once dirname(__DIR__).'/includes/sesion.php';
require_once __DIR__.'/includes/permisos-admins.php';
function gl_bd(): PDO { $p=function_exists('sesion_db')?sesion_db():($GLOBALS['conexion']??null); if (!$p instanceof PDO) throw new RuntimeException('Sin base de datos'); $p->setAttribute(PDO::ATTR_ERRMODE,PDO::ERRMODE_EXCEPTION); return $p; }
function gl_admin(): array {
 $s=requerir_sesion(); $id=(int)($s['id']??0); if(!$id){http_response_code(403);exit;}
 $db=gl_bd();$q=$db->prepare('SELECT rango,tareas,estado_miembro FROM miembros_clan WHERE id=? LIMIT 1');$q->execute([$id]);$u=$q->fetch(PDO::FETCH_ASSOC);
 if(!$u || (int)$u['rango']!==4 || !in_array($u['estado_miembro'],['activo','justificada','riesgo'],true)){http_response_code(403);exit('Sin autorización');}
 $raw=$u['tareas']??'';$items=function_exists('adm_tareas')?adm_tareas($raw):(is_array(json_decode((string)$raw,true))?json_decode((string)$raw,true):preg_split('/[,;|]+/',(string)$raw));
 $ok=false;foreach((array)$items as $t){if(is_array($t))$t=$t['tarea']??$t['nombre']??$t['slug']??'';if(mb_strtolower(trim((string)$t))==='general')$ok=true;}
 if(!$ok){http_response_code(403);exit('Solo tarea general');}return $u;
}
function gl_json($data,int $status=200):never {http_response_code($status);header('Content-Type: application/json; charset=utf-8');header('Cache-Control: no-store');header('X-Content-Type-Options: nosniff');echo json_encode($data,JSON_UNESCAPED_UNICODE|JSON_INVALID_UTF8_SUBSTITUTE);exit;}
function gl_clean(PDO $db):void {$db->exec('DELETE FROM gl_boveda_trabajos WHERE vence < UTC_TIMESTAMP()');$db->exec('DELETE FROM gl_boveda_vinculos WHERE vence < UTC_TIMESTAMP()');}


if (isset($_GET['device_api'])) {
if(($_SERVER['REQUEST_METHOD']??'')!=='POST')gl_json(['error'=>'Método'],405);
if(!isset($_SERVER['HTTPS'])||$_SERVER['HTTPS']==='off')gl_json(['error'=>'Se requiere HTTPS'],403);
$db=gl_bd();$op=(string)($_POST['op']??'');
if($op==='pair'){
 $code=(string)($_POST['code']??'');$name=mb_substr(trim((string)($_POST['name']??'Android')),0,80);if(!preg_match('/^[a-f0-9]{32}$/D',$code))gl_json(['error'=>'Código inválido'],400);
 $db->beginTransaction();$q=$db->prepare('SELECT hash FROM gl_boveda_vinculos WHERE hash=? AND usado=0 AND vence>UTC_TIMESTAMP() FOR UPDATE');$q->execute([hash('sha256',$code)]);if(!$q->fetch()){$db->rollBack();gl_json(['error'=>'Código expirado o utilizado'],403);}
 $token=bin2hex(random_bytes(32));$q=$db->prepare('INSERT INTO gl_boveda_dispositivos(nombre,token_hash) VALUES (?,?)');$q->execute([$name,hash('sha256',$token)]);$id=(int)$db->lastInsertId();$q=$db->prepare('UPDATE gl_boveda_vinculos SET usado=1 WHERE hash=?');$q->execute([hash('sha256',$code)]);$db->commit();gl_json(['device'=>$id,'token'=>$token]);
}
$token=(string)($_SERVER['HTTP_X_DEVICE_TOKEN']??'');if(!preg_match('/^[a-f0-9]{64}$/D',$token))gl_json(['error'=>'Token requerido'],401);
$q=$db->prepare("SELECT id FROM gl_boveda_dispositivos WHERE token_hash=? AND estado='activo' LIMIT 1");$q->execute([hash('sha256',$token)]);$device=(int)$q->fetchColumn();if(!$device)gl_json(['error'=>'Revocado'],403);
$q=$db->prepare('UPDATE gl_boveda_dispositivos SET ultimo_contacto=UTC_TIMESTAMP() WHERE id=?');$q->execute([$device]);
if($op==='next'){$db->beginTransaction();$q=$db->prepare("SELECT id,tipo,media_id,parametros FROM gl_boveda_trabajos WHERE dispositivo_id=? AND estado='pendiente' AND vence>UTC_TIMESTAMP() ORDER BY id ASC LIMIT 1 FOR UPDATE");$q->execute([$device]);$r=$q->fetch(PDO::FETCH_ASSOC);if($r){$q=$db->prepare("UPDATE gl_boveda_trabajos SET estado='procesando' WHERE id=?");$q->execute([$r['id']]);}$db->commit();gl_json(['job'=>$r?:null]);}
if($op==='chunk'){
 $id=(int)($_POST['job']??0);$seq=filter_var($_POST['seq']??null,FILTER_VALIDATE_INT);$data=base64_decode((string)($_POST['payload']??''),true);
 if($id<1||$seq===false||$seq<0||$data===false||strlen($data)>256*1024)gl_json(['error'=>'Bloque inválido'],400);
 $q=$db->prepare("SELECT id FROM gl_boveda_trabajos WHERE id=? AND dispositivo_id=? AND estado='procesando' AND tipo='imagen' AND vence>UTC_TIMESTAMP()");$q->execute([$id,$device]);if(!$q->fetchColumn())gl_json(['error'=>'Transferencia vencida'],409);
 $q=$db->prepare('INSERT INTO gl_boveda_partes(trabajo_id,numero,datos) VALUES (?,?,?)');$q->bindValue(1,$id,PDO::PARAM_INT);$q->bindValue(2,$seq,PDO::PARAM_INT);$q->bindValue(3,$data,PDO::PARAM_LOB);$q->execute();gl_json(['ok'=>true]);
}
if($op==='finish'){
 $id=(int)($_POST['job']??0);$mime=mb_substr((string)($_POST['mime']??'application/octet-stream'),0,100);
 $q=$db->prepare("UPDATE gl_boveda_trabajos SET mime=?,estado='listo' WHERE id=? AND dispositivo_id=? AND tipo='imagen' AND estado='procesando'");$q->execute([$mime,$id,$device]);gl_json(['ok'=>$q->rowCount()===1]);
}
if($op==='fail'){
 $q=$db->prepare("UPDATE gl_boveda_trabajos SET estado='error' WHERE id=? AND dispositivo_id=? AND estado='procesando'");$q->execute([(int)($_POST['job']??0),$device]);gl_json(['ok'=>true]);
}
if($op==='result'){
 $id=(int)($_POST['job']??0);$mime=(string)($_POST['mime']??'');$payload=(string)($_POST['payload']??'');
 if(!in_array($mime,['application/json','image/jpeg','image/png','image/webp'],true)||strlen($payload)>8*1024*1024)gl_json(['error'=>'Respuesta demasiado grande'],413);
 $bytes=base64_decode($payload,true);if($bytes===false||strlen($bytes)>5*1024*1024)gl_json(['error'=>'Contenido inválido'],400);
 if($mime==='application/json' && !is_array(json_decode($bytes,true)))gl_json(['error'=>'JSON inválido'],400);
 if(str_starts_with($mime,'image/') && @getimagesizefromstring($bytes)===false)gl_json(['error'=>'Imagen inválida'],415);
 $q=$db->prepare("UPDATE gl_boveda_trabajos SET respuesta=?,mime=?,estado='listo' WHERE id=? AND dispositivo_id=? AND estado='procesando' AND vence>UTC_TIMESTAMP()");$q->execute([$bytes,$mime,$id,$device]);gl_json(['ok'=>$q->rowCount()===1]);
}
gl_json(['error'=>'Operación inválida'],404);

}
if(isset($_GET['download'])) {
 gl_admin();
$db=gl_bd();$id=(int)($_GET['job']??0);$q=$db->prepare("SELECT respuesta,mime FROM gl_boveda_trabajos WHERE id=? AND estado='listo' AND tipo='imagen' AND vence>UTC_TIMESTAMP() LIMIT 1");$q->execute([$id]);$r=$q->fetch(PDO::FETCH_ASSOC);if(!$r){http_response_code(404);exit('Archivo no disponible');}
$mime=(string)$r['mime'];if(!preg_match('~^(image/[a-z0-9.+-]+|video/[a-z0-9.+-]+|application/octet-stream)$~i',$mime)){http_response_code(415);exit;}
$inline=isset($_GET['inline']);header('Content-Type: '.$mime);header('Content-Disposition: '.($inline?'inline':'attachment').'; filename="media-'.$id.'"');header('Cache-Control: private, no-store');header('X-Content-Type-Options: nosniff');session_write_close();
if($r['respuesta']!==null){echo $r['respuesta'];}else{$q=$db->prepare('SELECT datos FROM gl_boveda_partes WHERE trabajo_id=? ORDER BY numero');$q->execute([$id]);while($part=$q->fetch(PDO::FETCH_ASSOC)){echo $part['datos'];flush();}}
if(!$inline){$q=$db->prepare('DELETE FROM gl_boveda_trabajos WHERE id=?');$q->execute([$id]);}exit;

}
if(isset($_GET['api'])) {
 gl_admin();
if(!in_array($_SERVER['REQUEST_METHOD']??'', ['POST','GET'],true))gl_json(['error'=>'Método'],405);
header('Referrer-Policy: no-referrer');
if(($_SERVER['REQUEST_METHOD']??'')==='POST'){
 $origin=$_SERVER['HTTP_ORIGIN']??'';$host=$_SERVER['HTTP_HOST']??'';
 if($origin && parse_url($origin,PHP_URL_HOST)!==$host)gl_json(['error'=>'Origen inválido'],403);
 $csrf=(string)($_SERVER['HTTP_X_CSRF_TOKEN']??''); if(!hash_equals((string)($_SESSION['gl_boveda_csrf']??''),$csrf))gl_json(['error'=>'CSRF'],403);
}
$db=gl_bd(); if(random_int(1,12)===1)gl_clean($db);
$op=(string)($_POST['op']??$_GET['op']??'');
switch($op){
 case 'devices':$rows=$db->query("SELECT id,nombre,estado,ultimo_contacto FROM gl_boveda_dispositivos ORDER BY id DESC")->fetchAll(PDO::FETCH_ASSOC);gl_json(['devices'=>$rows]);
 case 'pair':
  if($_SERVER['REQUEST_METHOD']!=='POST')gl_json(['error'=>'Método'],405);
  $code=bin2hex(random_bytes(16));$q=$db->prepare('INSERT INTO gl_boveda_vinculos(hash,vence) VALUES (?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))');$q->execute([hash('sha256',$code)]);gl_json(['code'=>$code,'expires'=>'10 minutos']);
 case 'revoke':
  if($_SERVER['REQUEST_METHOD']!=='POST')gl_json(['error'=>'Método'],405);
  $q=$db->prepare("UPDATE gl_boveda_dispositivos SET estado='revocado' WHERE id=?");$q->execute([(int)($_POST['device']??0)]);gl_json(['ok'=>true]);
 case 'request':
  if($_SERVER['REQUEST_METHOD']!=='POST')gl_json(['error'=>'Método'],405);
  $device=(int)($_POST['device']??0);$kind=(string)($_POST['kind']??'');$media=(int)($_POST['media']??0);$args=(string)($_POST['params']??'{}');if(strlen($args)>1000||!is_array(json_decode($args,true)))gl_json(['error'=>'Parámetros inválidos'],400);
  if(!in_array($kind,['lista','imagen'],true) || ($kind==='imagen'&&$media<1))gl_json(['error'=>'Parámetros'],400);
  $q=$db->prepare("SELECT id FROM gl_boveda_dispositivos WHERE id=? AND estado='activo'");$q->execute([$device]);if(!$q->fetch())gl_json(['error'=>'Dispositivo no disponible'],404);
  $q=$db->prepare("INSERT INTO gl_boveda_trabajos(dispositivo_id,tipo,media_id,parametros,vence) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))");$q->execute([$device,$kind,$kind==='imagen'?$media:null,$args]);gl_json(['job'=>(int)$db->lastInsertId()]);
 case 'poll':
  $id=(int)($_GET['job']??0);$q=$db->prepare('SELECT id,estado,tipo,respuesta,mime FROM gl_boveda_trabajos WHERE id=? AND vence>UTC_TIMESTAMP()');$q->execute([$id]);$j=$q->fetch(PDO::FETCH_ASSOC);if(!$j)gl_json(['error'=>'Expiró'],404);
  if($j['estado']!=='listo')gl_json(['status'=>$j['estado']]);
  if($j['tipo']==='lista')gl_json(['status'=>'listo','data'=>json_decode((string)$j['respuesta'],true)]);
  gl_json(['status'=>'listo','download'=>'/panel/boveda?download=1&job='.$id,'inline'=>'/panel/boveda?download=1&inline=1&job='.$id]);
 default:gl_json(['error'=>'Operación no encontrada'],404);
}

}
$admActor=gl_admin();$nonce=base64_encode(random_bytes(16));$_SESSION['gl_boveda_csrf']??=bin2hex(random_bytes(24));
header('Cache-Control: no-store');header('X-Frame-Options: DENY');header('X-Content-Type-Options: nosniff');header('Referrer-Policy: no-referrer');header("Content-Security-Policy: default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'nonce-$nonce'; script-src 'nonce-$nonce'; base-uri 'self'; frame-ancestors 'none'");
?><!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Bóveda · Gran Logia</title><link rel="icon" href="/images/logo-oct.png"><style nonce="<?=htmlspecialchars($nonce)?>">
:root{font-family:system-ui,sans-serif;color:#433152;background:#faf7fd}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 15% 0%,#eadcff,transparent 46%),#faf7fd}main{max-width:1230px;margin:auto;padding:25px 18px 75px}h1{font-size:1.8rem}h2{font-size:1.1rem}.desc,.note{color:#9079a0;font-size:.85rem}.bar,.albums,.gallery,.devices{display:grid;gap:12px}.bar{display:flex;flex-wrap:wrap;margin:19px 0}.devices{grid-template-columns:repeat(auto-fill,minmax(240px,1fr))}.albums{grid-template-columns:repeat(auto-fill,minmax(165px,1fr));margin:18px 0}.gallery{grid-template-columns:repeat(auto-fill,minmax(120px,1fr));margin:16px 0}.card,.album,.photo{background:white;border:1px solid #e8dbef;border-radius:18px;padding:14px;box-shadow:0 12px 23px -20px #624a85}.album{min-height:110px;text-align:left}.photo{padding:6px;text-align:left}.photo img{width:100%;height:135px;object-fit:cover;background:#eee5f7;border-radius:12px;display:block}.photo span{font-size:.7rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;display:block;margin:7px}button{cursor:pointer;font:inherit;padding:11px 14px;border-radius:12px;border:1px solid #d9c5e7;background:#fff;color:#68418e}button.primary{background:linear-gradient(115deg,#9362c7,#c99bcf);color:#fff;border:0;font-weight:700}.bar button{min-height:44px}.album b{display:block}.album small{color:#9882a5}dialog{border:0;border-radius:18px;width:min(920px,96vw);max-height:94vh;padding:15px;background:#fff}dialog::backdrop{background:#21152cc9}dialog video{display:block;max-width:100%;max-height:72vh;margin:auto}dialog img{display:block;max-width:100%;max-height:72vh;object-fit:contain;margin:auto}dialog header{display:flex;justify-content:space-between;align-items:center;gap:8px}input{min-width:0;padding:11px;border:1px solid #e2d4eb;border-radius:12px}.pager{display:flex;align-items:center;justify-content:center;gap:15px;margin-top:15px}.hidden{display:none!important}
</style></head><body><?php include __DIR__.'/includes/navbar-admins.php'; ?><main><h1>🔐 Bóveda · Gran Logia</h1><p class="desc">Tus álbumes y fotografías se consultan desde el teléfono. Abre un álbum para cargar sus imágenes.</p>
<section class="bar"><button id="pair" class="primary">+ Vincular dispositivo</button><button id="refresh">Actualizar dispositivos</button><span id="info" role="status"></span></section><section class="devices" id="devices"></section>
<h2 id="heading">Álbumes del dispositivo</h2><section id="albums" class="albums"></section><section id="photos" class="gallery"></section><div id="pager" class="pager hidden"><button id="previous">Anterior</button><span id="counter"></span><button id="following">Siguiente</button></div>
<dialog id="viewer"><header><strong id="filename">Fotografía</strong><button id="close">Cerrar ✕</button></header><div id="preview" class="note">Cargando…</div><p><button id="save" class="primary">Descargar original</button></p></dialog></main>
<script nonce="<?=htmlspecialchars($nonce)?>">(()=>{'use strict';const api='/panel/boveda?api=1',csrf=<?=json_encode($_SESSION['gl_boveda_csrf'])?>,by=id=>document.getElementById(id);let device=0,album='',offset=0,current=null,jobBusy=false,photos=[];const say=t=>by('info').textContent=t;
async function call(op,data={}){const body=new URLSearchParams(data);body.set('op',op);const r=await fetch(api,{method:'POST',credentials:'same-origin',headers:{'X-CSRF-Token':csrf},body});const j=await r.json();if(!r.ok)throw Error(j.error||'Error '+r.status);return j}
async function wait(job){for(let i=0;i<150;i++){await new Promise(r=>setTimeout(r,1100));const r=await fetch(api+'&op=poll&job='+job,{credentials:'same-origin',cache:'no-store'});const j=await r.json();if(j.error)throw Error(j.error);if(j.status==='error')throw Error('No se pudo procesar en el teléfono');if(j.status==='listo')return j}throw Error('El teléfono no respondió')}
async function request(kind,media=0,params={}){const j=await call('request',{device,kind,media,params:JSON.stringify(params)});return wait(j.job)}
async function devices(){const d=await call('devices');by('devices').replaceChildren();for(const x of d.devices){const el=document.createElement('article');el.className='card';const h=document.createElement('h3');h.textContent='📱 '+x.nombre;const p=document.createElement('p');p.className='note';p.textContent='Última conexión: '+(x.ultimo_contacto||'Nunca');const b=document.createElement('button');b.className='primary';b.textContent='Explorar álbumes';b.onclick=()=>openDevice(x.id);const revoke=document.createElement('button');revoke.textContent='Revocar';revoke.onclick=async()=>{if(!confirm('¿Revocar este dispositivo?'))return;await call('revoke',{device:x.id});devices()};el.append(h,p,b,' ',revoke);by('devices').append(el)}}
async function openDevice(id){if(jobBusy)return;jobBusy=true;device=id;by('photos').replaceChildren();say('Consultando álbumes…');try{const r=await request('lista',0,{mode:'albums'});by('albums').replaceChildren();const arr=r.data?.albums||[];for(const a of arr){const b=document.createElement('button');b.className='album';const name=document.createElement('b');name.textContent='📁 '+a.name;const small=document.createElement('small');small.textContent=a.count+' archivos';b.append(name,small);b.onclick=()=>openAlbum(a.id,a.name);by('albums').append(b)}say(arr.length+' álbumes encontrados');by('heading').textContent='Álbumes del dispositivo'}catch(e){say(e.message)}finally{jobBusy=false}}
async function openAlbum(id,name){if(jobBusy)return;album=String(id);offset=0;by('heading').textContent=name;by('albums').replaceChildren();await loadPage()}
async function loadPage(){if(jobBusy)return;jobBusy=true;by('photos').replaceChildren();say('Cargando archivos del álbum…');try{const r=await request('lista',0,{mode:'photos',album,offset});photos=r.data?.items||[];for(const it of photos){const card=document.createElement('button');card.className='photo';const img=document.createElement('img');img.alt=it.name;img.loading='lazy';const label=document.createElement('span');label.textContent=it.name;card.append(img,label);card.onclick=()=>show(it);by('photos').append(card);getThumb(it,img)}by('pager').classList.remove('hidden');by('counter').textContent='Fotos '+(offset+1)+'–'+(offset+photos.length);by('previous').disabled=offset===0;by('following').disabled=!r.data?.hasMore;say(photos.length+' archivos cargados')}catch(e){say(e.message)}finally{jobBusy=false}}
async function getThumb(it,img){try{const r=await request('imagen',it.id,{mode:'thumb',media_type:it.type});const a=await fetch(r.inline,{credentials:'same-origin'});if(!a.ok)return;const blob=await a.blob();const url=URL.createObjectURL(blob);img.src=url;img.onload=()=>URL.revokeObjectURL(url)}catch(e){img.alt='Vista previa no disponible'}}
async function show(it){current=it;by('filename').textContent=it.name;by('preview').textContent='Cargando vista previa…';by('viewer').showModal();try{
 const video=it.type==='video';const r=await request('imagen',it.id,{mode:video?'original':'thumb',media_type:it.type});
 if(current!==it)return;
 if(video){const player=document.createElement('video');player.controls=true;player.playsInline=true;player.preload='metadata';player.src=r.inline;player.style.cssText='max-width:100%;max-height:72vh;display:block;margin:auto';by('preview').replaceChildren(player)}
 else{const b=await(await fetch(r.inline,{credentials:'same-origin'})).blob();if(current!==it)return;const url=URL.createObjectURL(b);const img=document.createElement('img');img.src=url;img.onload=()=>URL.revokeObjectURL(url);by('preview').replaceChildren(img)}
 }catch(e){by('preview').textContent=e.message}}
by('save').onclick=async()=>{if(!current)return;const selected=current;try{say('Preparando descarga original…');const r=await request('imagen',selected.id,{mode:'original',media_type:selected.type});const a=document.createElement('a');a.href=r.download;a.download=selected.name;document.body.append(a);a.click();a.remove();say('Descarga solicitada')}catch(e){say(e.message)}};
by('close').onclick=()=>by('viewer').close();by('viewer').addEventListener('close',()=>{by('preview').querySelectorAll('video').forEach(v=>{v.pause();v.removeAttribute('src');v.load()});current=null});by('previous').onclick=()=>{offset=Math.max(0,offset-40);loadPage()};by('following').onclick=()=>{offset+=40;loadPage()};by('refresh').onclick=()=>devices().catch(e=>say(e.message));by('pair').onclick=async()=>{try{const r=await call('pair');prompt('Key Administración (válida 10 minutos)',r.code);say('Key generada')}catch(e){say(e.message)}};devices().catch(e=>say(e.message))})();</script></body></html>