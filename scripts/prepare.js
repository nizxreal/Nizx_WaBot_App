#!/usr/bin/env node
'use strict';
// Menyiapkan semua yang dibutuhkan Gradle sebelum membangun APK (Windows, macOS, Linux).
//   node scripts/prepare.js [--force] [--nodejs-mobile=v18.17.2] [--baileys=6.7] [--gradle=8.9]

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const BUILD = path.join(ROOT, 'build');
const opt = {};
for (const a of process.argv.slice(2)) {
  const m = /^--([^=]+)(?:=(.*))?$/.exec(a);
  if (m) opt[m[1]] = m[2] === undefined ? true : m[2];
}
const NM_TAG = opt['nodejs-mobile'] || 'v18.17.2';
const BAILEYS = opt.baileys || '6.7';
const GRADLE = opt.gradle || '8.9';
const FORCE = !!opt.force;
const IS_WIN = process.platform === 'win32';

const say = (m) => console.log(m);
const step = (n, m) => say('\n[' + n + '] ' + m);
const fail = (m) => {
  console.error('\nGAGAL: ' + m);
  process.exit(1);
};

// ---------- utilitas ----------
function has(cmd, shell) {
  const r = spawnSync(cmd, ['--version'], { stdio: 'ignore', shell: !!shell });
  return r.status === 0;
}

function checkTools() {
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 18) fail('Node.js 18 atau lebih baru diperlukan (sekarang ' + process.version + ').');
  if (!has('npm', IS_WIN)) fail('npm tidak ditemukan.');
  if (!has('git', false)) fail('git tidak ditemukan (dibutuhkan npm untuk dependensi Baileys).');
}

async function download(url, file, headers) {
  const res = await fetch(url, { headers: Object.assign({ 'user-agent': 'nizx-prepare' }, headers || {}) });
  if (!res.ok) throw new Error('HTTP ' + res.status + ' untuk ' + url);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
}

function findFiles(dir, pred, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) findFiles(p, pred, out);
    else if (pred(p)) out.push(p);
  }
  return out;
}

function unzipTo(zip, dest) {
  fs.rmSync(dest, { recursive: true, force: true });
  fs.mkdirSync(dest, { recursive: true });
  const tries = [
    ['tar', ['-xf', zip, '-C', dest]],
    ['unzip', ['-q', '-o', zip, '-d', dest]],
  ];
  if (IS_WIN) {
    tries.push(['powershell', ['-NoProfile', '-Command', "Expand-Archive -Force -LiteralPath '" + zip + "' -DestinationPath '" + dest + "'"]]);
  }
  for (const t of tries) {
    const r = spawnSync(t[0], t[1], { stdio: 'ignore' });
    if (r.status === 0) return;
  }
  fail('Tidak bisa mengekstrak zip. Linux: pasang unzip (sudo apt install unzip), lalu ulangi.');
}

// ---------- penulis zip sederhana (jalur dengan "/" agar terbaca di Android) ----------
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function listFiles(root) {
  const out = [];
  (function walk(dir, rel) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, e.name);
      const name = rel ? rel + '/' + e.name : e.name;
      let isDir = e.isDirectory();
      let isFile = e.isFile();
      const link = e.isSymbolicLink();
      if (link) {
        try {
          const st = fs.statSync(abs);
          isDir = st.isDirectory();
          isFile = st.isFile();
        } catch (err) {
          continue;
        }
      }
      if (isDir) {
        if (!link) walk(abs, name);
      } else if (isFile) {
        out.push({ abs, name });
      }
    }
  })(root, '');
  return out;
}

function makeZip(srcDir, outFile) {
  const list = listFiles(srcDir);
  if (list.length > 65000) throw new Error('terlalu banyak file untuk zip sederhana (' + list.length + ')');
  const fd = fs.openSync(outFile, 'w');
  let offset = 0;
  const central = [];
  const d = new Date();
  const dosTime = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const dosDate = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  const write = (b) => {
    let n = 0;
    while (n < b.length) n += fs.writeSync(fd, b, n);
    offset += b.length;
  };
  for (const f of list) {
    const data = fs.readFileSync(f.abs);
    const comp = zlib.deflateRawSync(data, { level: 6 });
    const name = Buffer.from(f.name, 'utf8');
    const crc = crc32(data);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0);
    h.writeUInt16LE(20, 4);
    h.writeUInt16LE(0x0800, 6);
    h.writeUInt16LE(8, 8);
    h.writeUInt16LE(dosTime, 10);
    h.writeUInt16LE(dosDate, 12);
    h.writeUInt32LE(crc, 14);
    h.writeUInt32LE(comp.length, 18);
    h.writeUInt32LE(data.length, 22);
    h.writeUInt16LE(name.length, 26);
    h.writeUInt16LE(0, 28);
    central.push({ name, crc, csize: comp.length, size: data.length, offset });
    write(h);
    write(name);
    write(comp);
  }
  const cdStart = offset;
  for (const c of central) {
    const h = Buffer.alloc(46);
    h.writeUInt32LE(0x02014b50, 0);
    h.writeUInt16LE(20, 4);
    h.writeUInt16LE(20, 6);
    h.writeUInt16LE(0x0800, 8);
    h.writeUInt16LE(8, 10);
    h.writeUInt16LE(dosTime, 12);
    h.writeUInt16LE(dosDate, 14);
    h.writeUInt32LE(c.crc, 16);
    h.writeUInt32LE(c.csize, 20);
    h.writeUInt32LE(c.size, 24);
    h.writeUInt16LE(c.name.length, 28);
    h.writeUInt32LE(c.offset, 42);
    write(h);
    write(c.name);
  }
  const cdSize = offset - cdStart;
  const e = Buffer.alloc(22);
  e.writeUInt32LE(0x06054b50, 0);
  e.writeUInt16LE(central.length, 8);
  e.writeUInt16LE(central.length, 10);
  e.writeUInt32LE(cdSize, 12);
  e.writeUInt32LE(cdStart, 16);
  write(e);
  fs.closeSync(fd);
  return list.length;
}

// ---------- langkah 1: libnode ----------
async function prepLibnode() {
  step('1/4', 'libnode (nodejs-mobile ' + NM_TAG + ')');
  const out = path.join(ROOT, 'app', 'libnode', 'bin', 'arm64-v8a', 'libnode.so');
  if (fs.existsSync(out) && !FORCE) return say('sudah ada, dilewati (pakai --force untuk mengunduh ulang)');

  const headers = { accept: 'application/vnd.github+json', 'user-agent': 'nizx-prepare' };
  if (process.env.GITHUB_TOKEN) headers.authorization = 'Bearer ' + process.env.GITHUB_TOKEN;
  const res = await fetch('https://api.github.com/repos/nodejs-mobile/nodejs-mobile/releases/tags/' + NM_TAG, { headers });
  if (!res.ok) fail('Rilis ' + NM_TAG + ' tidak ditemukan di GitHub (HTTP ' + res.status + ').');
  const rel = await res.json();
  const assets = rel.assets || [];
  const asset = assets.find((a) => /android/i.test(a.name) && /\.zip$/i.test(a.name));
  if (!asset) fail('Aset Android tidak ditemukan. Aset yang ada: ' + assets.map((a) => a.name).join(', '));

  const zip = path.join(BUILD, 'cache', asset.name);
  if (!fs.existsSync(zip) || FORCE) {
    say('mengunduh ' + asset.name + ' (' + Math.round(asset.size / 1048576) + ' MB)...');
    await download(asset.browser_download_url, zip);
  }
  say('mengekstrak...');
  const dir = path.join(BUILD, 'nm');
  unzipTo(zip, dir);
  const lib = findFiles(dir, (p) => p.endsWith('libnode.so') && p.includes('arm64-v8a'))[0];
  if (!lib) fail('libnode.so arm64-v8a tidak ada di dalam zip.');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.copyFileSync(lib, out);
  say('ok: ' + path.relative(ROOT, out) + ' (' + Math.round(fs.statSync(out).size / 1048576) + ' MB)');
}

// ---------- langkah 2: proyek Node ----------
function repoSlug() {
  try {
    const r = spawnSync('git', ['remote', 'get-url', 'origin'], { cwd: ROOT, encoding: 'utf8' });
    const m = /github\.com[\/:]([^\/\s]+\/[^\/\s]+?)(?:\.git)?\s*$/.exec(r.stdout || '');
    if (m) return m[1];
  } catch (e) {}
  return 'nizxreal/Nizx_WaBot_App';
}

function prepNodeProject() {
  step('2/4', 'proyek Node (Baileys ' + BAILEYS + ')');
  const dir = path.join(BUILD, 'njs');
  const marker = path.join(dir, '.baileys-spec');
  const fresh =
    !FORCE &&
    fs.existsSync(path.join(dir, 'node_modules')) &&
    fs.existsSync(marker) &&
    fs.readFileSync(marker, 'utf8') === BAILEYS;
  if (!fresh) {
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name: 'nizx-node', version: '1.0.0', private: true }, null, 2));
    const r = spawnSync('npm', ['install', '--omit=dev', '--no-audit', '--no-fund', '@whiskeysockets/baileys@' + BAILEYS], {
      cwd: dir,
      stdio: 'inherit',
      shell: IS_WIN,
    });
    if (r.status !== 0) fail('npm install gagal. Pastikan internet aktif dan git terpasang.');
    fs.writeFileSync(marker, BAILEYS);
  } else {
    say('node_modules sudah ada, dilewati (pakai --force untuk memasang ulang)');
  }
  fs.copyFileSync(path.join(ROOT, 'poc', 'nodejs-project', 'main.js'), path.join(dir, 'main.js'));
  fs.writeFileSync(path.join(dir, 'repo.txt'), repoSlug() + '\n');
  spawnSync('npm', ['ls', '@whiskeysockets/baileys', '--depth=0'], { cwd: dir, stdio: 'inherit', shell: IS_WIN });
  return dir;
}

// ---------- langkah 3: zip ----------
function packZip(srcDir) {
  step('3/4', 'membuat nodejs-project.zip');
  const out = path.join(ROOT, 'app', 'src', 'main', 'assets', 'nodejs-project.zip');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const n = makeZip(srcDir, out);
  say('ok: ' + n + ' file, ' + Math.round(fs.statSync(out).size / 1048576) + ' MB');
}

// ---------- langkah 4: Gradle wrapper dan SDK ----------
async function prepWrapper() {
  step('4/4', 'Gradle wrapper dan lokasi SDK');
  const base = 'https://raw.githubusercontent.com/gradle/gradle/v' + GRADLE + '.0';
  const files = [
    ['gradlew', 'gradlew'],
    ['gradlew.bat', 'gradlew.bat'],
    ['gradle/wrapper/gradle-wrapper.jar', path.join('gradle', 'wrapper', 'gradle-wrapper.jar')],
  ];
  const complete = files.every((f) => fs.existsSync(path.join(ROOT, f[1])));
  if (!complete || FORCE) {
    try {
      for (const f of files) await download(base + '/' + f[0], path.join(ROOT, f[1]));
      if (!IS_WIN) fs.chmodSync(path.join(ROOT, 'gradlew'), 0o755);
      say('wrapper diunduh');
    } catch (e) {
      say('PERINGATAN: wrapper tidak bisa diunduh (' + e.message + ').');
      say('Alternatif: pasang Gradle ' + GRADLE + ', lalu jalankan: gradle wrapper --gradle-version ' + GRADLE);
    }
  } else {
    say('wrapper sudah ada');
  }
  fs.mkdirSync(path.join(ROOT, 'gradle', 'wrapper'), { recursive: true });
  fs.writeFileSync(
    path.join(ROOT, 'gradle', 'wrapper', 'gradle-wrapper.properties'),
    [
      'distributionBase=GRADLE_USER_HOME',
      'distributionPath=wrapper/dists',
      'distributionUrl=https://services.gradle.org/distributions/gradle-' + GRADLE + '-bin.zip',
      'networkTimeout=10000',
      'validateDistributionUrl=true',
      'zipStoreBase=GRADLE_USER_HOME',
      'zipStorePath=wrapper/dists',
      '',
    ].join('\n')
  );

  const lp = path.join(ROOT, 'local.properties');
  if (!fs.existsSync(lp)) {
    const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    if (sdk && fs.existsSync(sdk)) {
      fs.writeFileSync(lp, 'sdk.dir=' + sdk.replace(/\\/g, '\\\\') + '\n');
      say('local.properties dibuat dari ANDROID_HOME');
    } else {
      say('local.properties belum ada. Android Studio membuatnya otomatis saat folder dibuka.');
      say('Untuk build lewat terminal, set ANDROID_HOME ke folder Android SDK, lalu jalankan skrip ini lagi.');
    }
  }
}

(async () => {
  checkTools();
  await prepLibnode();
  const dir = prepNodeProject();
  packZip(dir);
  await prepWrapper();
  say('\nSelesai. Langkah berikutnya:');
  say('  ' + (IS_WIN ? 'gradlew.bat assembleDebug' : './gradlew assembleDebug'));
  say('  APK: app/build/outputs/apk/debug/app-debug.apk');
  say('  Pasang: adb install -r app/build/outputs/apk/debug/app-debug.apk');
})().catch((e) => fail(e && e.message ? e.message : String(e)));
