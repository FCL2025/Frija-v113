// 管理面板的煙霧測試：用 CDP 驅動無頭 Chrome，對著「正在執行的伺服器」跑一遍。
//
//   node panel/tools/smoke.mjs <token> [http://127.0.0.1:8080/]
//
// 登入 → 走過每個分頁 → 實際按下倍率 / 廣播 / reload / 設定編輯（改完會改回去）→ 登出，
// 期間收集 console 錯誤與 4xx/5xx 回應，並把每個畫面截圖到 panel/tools/shots/。
// 只用 Node 24 內建的 fetch 與 WebSocket，沒有任何依賴。這個 repo 沒有測試套件，
// 這支腳本是最接近的東西。

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const TOKEN = process.argv[2]
const BASE = (process.argv[3] ?? 'http://127.0.0.1:8080/').replace(/\/?$/, '/')
if (!TOKEN) {
  console.error('用法：node panel/tools/smoke.mjs <token> [baseUrl]')
  process.exit(2)
}
const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome / Edge')
  process.exit(2)
}
const PORT = 9333
const SHOTS = path.join(HERE, 'shots')
const PROFILE = path.join(HERE, '.chrome-profile')
rmSync(PROFILE, { recursive: true, force: true })
mkdirSync(SHOTS, { recursive: true })
const INI = path.join(HERE, '..', '..', 'Settings.ini')
const iniBefore = readFileSync(INI)

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--window-size=1400,900', '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank',
], { stdio: 'ignore' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let page
for (let i = 0; i < 50 && !page; i++) {
  try {
    page = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find((t) => t.type === 'page')
  } catch { /* not up yet */ }
  if (!page) await sleep(200)
}
if (!page) {
  chrome.kill()
  throw new Error('devtools not reachable')
}
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })

let seq = 0
const pending = new Map()
const errors = []
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id)
    pending.delete(m.id)
    m.error ? p.rej(new Error(m.error.message)) : p.res(m.result)
    return
  }
  if (m.method === 'Runtime.exceptionThrown') errors.push('[exception] ' + (m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text))
  if (m.method === 'Runtime.consoleAPICalled' && (m.params.type === 'error' || m.params.type === 'warning')) {
    errors.push(`[console.${m.params.type}] ` + m.params.args.map((a) => a.value ?? a.description ?? JSON.stringify(a)).join(' '))
  }
  if (m.method === 'Network.loadingFailed') errors.push('[network] ' + m.params.errorText)
  if (m.method === 'Network.responseReceived' && m.params.response.status >= 400 && !m.params.response.url.endsWith('/api/ping')) {
    errors.push(`[http ${m.params.response.status}] ${m.params.response.url}`)
  }
}
const send = (method, params = {}) => new Promise((res, rej) => {
  const id = ++seq
  pending.set(id, { res, rej })
  ws.send(JSON.stringify({ id, method, params }))
})
const ev = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('eval failed: ' + (r.exceptionDetails.exception?.description ?? r.exceptionDetails.text))
  return r.result.value
}
const shot = async (name) => writeFileSync(path.join(SHOTS, name + '.png'), Buffer.from((await send('Page.captureScreenshot', { format: 'png' })).data, 'base64'))
const text = () => ev('document.body.innerText')
const go = async (hash, wait = 1800) => { await ev(`location.hash = ${JSON.stringify(hash)}`); await sleep(wait); return text() }
// React 受控輸入要用原生 setter 再派發 input 事件才會更新 state
const setInput = (sel, val) => ev(`(() => { const i = ${sel}; const proto = i.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto, 'value').set.call(i, ${JSON.stringify(val)}); i.dispatchEvent(new Event('input', { bubbles: true })) })()`)
const clickButton = (txt) => ev(`(() => { const b = [...document.querySelectorAll('button')].find((b) => b.textContent.trim().includes(${JSON.stringify(txt)})); if (!b) return false; b.click(); return true })()`)
// Radix 的 Tabs 聽的是 pointer 事件，不是 click
const clickTab = (txt) => ev(`(() => { const el = [...document.querySelectorAll('[role=tab]')].find((x) => x.textContent.includes(${JSON.stringify(txt)})); if (!el) return false; for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) el.dispatchEvent(new MouseEvent(t, { bubbles: true, button: 0, buttons: t.endsWith('down') ? 1 : 0 })); return true })()`)
const api = async (p) => (await fetch(BASE.replace(/\/$/, '') + p, { headers: { Authorization: 'Bearer ' + TOKEN } })).json()

const results = []
const check = (name, ok, extra = '') => { results.push(`${ok ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`) }

try {
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1400, height: 900, deviceScaleFactor: 1, mobile: false })

  // --- 登入 ---------------------------------------------------------------
  await send('Page.navigate', { url: BASE }); await sleep(1200)
  await ev('localStorage.clear()'); await send('Page.reload'); await sleep(1500)
  let t = await text()
  check('登入頁', /Token/.test(t))
  await shot('01-login')
  await setInput(`document.querySelector('input')`, 'wrong-token'); await ev(`document.querySelector('form').requestSubmit()`); await sleep(800)
  check('錯誤 token 被拒', /Token 錯誤/.test(await text()))
  await setInput(`document.querySelector('input')`, TOKEN); await ev(`document.querySelector('form').requestSubmit()`); await sleep(2500)
  t = await text()
  check('登入後進儀表板', /線上人數/.test(t) && /頻道數/.test(t))
  const ping = await api('/api/ping')
  check('中文伺服器名稱顯示正確', t.includes(ping.serverName), ping.serverName)
  await shot('02-dashboard')

  // --- 唯讀分頁 -----------------------------------------------------------
  const channels = await api('/api/channels')
  t = await go('channels')
  check('頻道表列數正確', channels.every((c) => t.includes(c.ip)), `${channels.length} 個頻道`)
  await shot('03-channels')
  t = await go('players')
  const players = await api('/api/players')
  check('線上玩家', players.length === 0 ? /目前沒有玩家在線上/.test(t) : players.every((p) => t.includes(p.name)), `${players.length} 人`)
  await shot('04-players')
  t = await go('logs')
  check('日誌分頁', /日誌/.test(t))
  await shot('05-logs')

  // --- 即時操作 -----------------------------------------------------------
  t = await go('actions')
  const exp0 = channels[0]?.expRate ?? 1
  const expVal = await ev(`document.getElementById('rate-exp')?.value`)
  check('倍率表單預填線上值', expVal === String(exp0), `value=${expVal}`)
  check('七組 reload', (t.match(/重載/g) || []).length >= 7)
  check('關機警語', /面板無法重新啟動伺服器/.test(t))
  await shot('06-actions')

  await setInput(`document.getElementById('rate-exp')`, String(exp0 + 1)); await sleep(100)
  check('倍率：按下套用', await clickButton('套用並保存')); await sleep(1500)
  check('倍率：三態顯示「已套用且已保存」', /已套用且已保存/.test(await text()))
  check('倍率：每個頻道都變了', (await api('/api/channels')).every((c) => c.expRate === exp0 + 1))
  check('倍率：寫回 ini', new RegExp(`tms\\.Exp\\s+= ${exp0 + 1}\\r?\\n`).test(readFileSync(INI, 'utf8')))
  await shot('07-rates-applied')
  await setInput(`document.getElementById('rate-exp')`, String(exp0)); await clickButton('套用並保存'); await sleep(1200)
  check('倍率：改回原值', (await api('/api/channels')).every((c) => c.expRate === exp0))

  await setInput(`document.getElementById('broadcast-message')`, '面板煙霧測試廣播'); await sleep(100)
  check('廣播：送出', await clickButton('送出')); await sleep(1200)
  check('廣播：結果', /已送給 \d+ 位玩家/.test(await text()))

  await ev(`(() => { const li = [...document.querySelectorAll('li')].find((l) => l.textContent.includes('掉寶') && l.querySelector('button')); li.querySelector('button').click() })()`); await sleep(1500)
  check('reload 掉寶：完成', /完成 · \d+ ms/.test(await text()))
  await shot('08-actions-after')

  // --- 設定檔 -------------------------------------------------------------
  t = await go('settings', 2200)
  check('設定：列出項目', /tms\.Url/.test(t) && /tms\.Exp/.test(t))
  check('設定：密碼不外流', /不會離開伺服器/.test(t) && !/tms\.Pass[\s\S]{0,60}(root|password)/.test(t))
  await shot('09-settings')
  const limit0 = (await api('/api/status')).userLimit
  await ev(`(() => { const row = [...document.querySelectorAll('tr')].find((r) => r.textContent.includes('tms.UserLimit')); row.querySelector('button').click() })()`); await sleep(400)
  await setInput(`document.querySelector('tr input')`, String(limit0 + 50)); await sleep(100)
  check('設定：保存並套用', await clickButton('保存並套用')); await sleep(1500)
  check('設定：三態顯示「已套用且已保存」', /已套用且已保存/.test(await text()))
  check('設定：UserLimit 線上生效', (await api('/api/status')).userLimit === limit0 + 50)
  await shot('10-settings-edited')
  await ev(`(() => { const row = [...document.querySelectorAll('tr')].find((r) => r.textContent.includes('tms.UserLimit')); row.querySelector('button').click() })()`); await sleep(400)
  await setInput(`document.querySelector('tr input')`, String(limit0)); await clickButton('保存並套用'); await sleep(1200)
  check('設定：改回原值', (await api('/api/status')).userLimit === limit0)

  // --- 腳本 ---------------------------------------------------------------
  t = await go('scripts', 3000)
  check('腳本：分類與數量', /NPC/.test(t) && /傳送點/.test(t))
  await ev(`document.querySelector('ul button')?.click()`); await sleep(2500)
  const cm = await ev(`document.querySelector('.cm-content')?.textContent?.slice(0, 200) ?? ''`)
  check('腳本：編輯器載入內容', cm.length > 20, cm.slice(0, 50).replace(/\n/g, ' | '))
  await shot('11-scripts-editor')
  await setInput(`[...document.querySelectorAll('input')].find((x) => x.placeholder.includes('全文搜尋'))`, 'importPackage'); await sleep(2500)
  check('腳本：全文搜尋', /處命中/.test(await text()))
  await shot('12-scripts-search')
  await setInput(`[...document.querySelectorAll('input')].find((x) => x.placeholder.includes('全文搜尋'))`, ''); await sleep(500)
  check('腳本：切到傳送點分頁', await clickTab('傳送點')); await sleep(800)
  await setInput(`[...document.querySelectorAll('input')].find((x) => x.placeholder.includes('依檔名過濾'))`, 'gh17'); await sleep(600)
  const openedBroken = await ev(`(() => { const b = [...document.querySelectorAll('ul button')].find((b) => b.textContent.includes('gh17.js')); if (!b) return false; b.click(); return true })()`); await sleep(2000)
  if (openedBroken) check('腳本：壞掉的 UTF-8 傳送點被標示', /不是合法的 BIG5/.test(await text()))
  await shot('13-scripts-broken-portal')

  // --- 深色模式與登出 -----------------------------------------------------
  await ev(`document.querySelector('button[aria-label*="深色"]')?.click()`); await sleep(500)
  await go('dashboard', 1500); await shot('14-dashboard-dark')
  await clickButton('登出'); await sleep(800)
  t = await text()
  check('登出回到登入頁', /Token/.test(t) && !/線上人數/.test(t))
} finally {
  ws.close()
  chrome.kill()
  // Chrome 要一會兒才放開 profile 目錄；清不掉就留著，下次開始時會再清一次
  await Promise.race([new Promise((r) => chrome.once('exit', r)), sleep(3000)])
  try { rmSync(PROFILE, { recursive: true, force: true }) } catch { /* ignore */ }
}

const iniAfter = readFileSync(INI)
check('Settings.ini 位元組完全還原', Buffer.compare(iniBefore, iniAfter) === 0)

console.log(results.join('\n'))
console.log('\n瀏覽器錯誤：' + (errors.length ? '\n' + errors.join('\n') : '無'))
console.log(`截圖：${SHOTS}`)
process.exit(results.some((r) => r.startsWith('FAIL')) || errors.length ? 1 : 0)
