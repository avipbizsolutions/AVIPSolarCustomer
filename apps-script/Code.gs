/**
 * AVIP Solar (customer app): backend for the Android app used by AVIP Solar's
 * retail customers. Shows installation progress, documents, Refer & Earn and
 * support tickets.
 *
 * Bound to the Google Sheet "AVIP Solar Customer App – Database".
 * Setup (once, from the office Google account that owns the sheet):
 *   1. Open the sheet → Extensions → Apps Script → paste this file → Save.
 *   2. Run "setup" once and allow access. It creates the tabs, the Drive folder,
 *      the hourly sync and the AVIP menu.
 *   3. Fill the Settings tab (sheet IDs of Install Check and Site Visits Log, alert e-mail).
 *   4. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *   5. Put the /exec URL in config.json in the app's GitHub repo ("endpoint").
 * After any change to this file: Deploy → Manage deployments → Edit → New version.
 *
 * The phone never sees any sheet. It sends JSON to this web app, signed with
 * SHARED_KEY; every call except login carries the customer's session token.
 * The Install Check and Site Visits Log sheets are only READ, never changed.
 */

const SHARED_KEY = 'avip-cust-5Tr8';
const TZ = 'Asia/Kolkata';
const FILE_ROOT = 'AVIP Customer App Files';
const DEFAULT_APP_LINK = 'https://github.com/avipbizsolutions/AVIPSolarCustomer/releases/latest/download/AVIP-Solar.apk';

const WEAK_PINS = ['0000', '1111', '2222', '3333', '4444', '5555', '6666', '7777', '8888', '9999',
  '1234', '2345', '3456', '4567', '5678', '6789', '4321', '9876', '1212', '1122', '0123', '1004'];

/** Progress stages, in order. "col" is the Customers column the office can type a date in. */
const STAGES = [
  { key: 'survey', title: 'Site survey', col: 'Survey Date' },
  { key: 'order', title: 'Order confirm', col: 'Order Date' },
  { key: 'portal', title: 'PM Surya Ghar registration & DISCOM approval', col: 'Portal & DISCOM Approval Date' },
  { key: 'dispatch', title: 'Material dispatch', col: 'Dispatch Date' },
  { key: 'install', title: 'Installation complete', col: 'Installation Date' },
  { key: 'netmeter', title: 'Net meter application', col: 'Net Meter Applied Date' },
  { key: 'commission', title: 'Inspection & commissioning', col: 'Commissioning Date' },
  { key: 'subsidy', title: 'Subsidy credited', col: 'Subsidy Credited Date' },
];

const REF_STATUS = ['Lead received', 'Contacted', 'Site visit done', 'Order confirmed', 'Installed'];
const NOT_INTERESTED = 'Not interested';
const TICKET_STATUS = ['Open', 'Assigned', 'Visit Scheduled', 'Resolved', 'Closed', 'Reopened'];
const TICKET_CATEGORIES = ['Generation kam', 'Inverter error', 'Panel / structure', 'Wiring / earthing',
  'Net meter', 'Subsidy', 'Billing', 'Other'];

// Install Check site statuses that mean the order is confirmed.
const IC_ORDERED = ['Approved for Dispatch', 'Dispatched', 'Balance Pending', 'Installation In Progress',
  'Installation Submitted', 'Rework', 'QC Approved (Closed)'];
const IC_INSTALLED = ['Installation Submitted', 'QC Approved (Closed)'];

const TABS = {
  'Customers': ['Customer ID', 'Name', 'Mobile', 'PIN', 'Active', 'Address', 'District', 'System (kW)',
    'System Type', 'Install Check Site ID', 'Sales Visit No.', 'Sales Executive', 'Sales Exec Mobile']
    .concat(STAGES.map(function (s) { return s.col; }))
    .concat(['Customer Action', 'Note to Customer', 'Referral Code', 'UPI ID', 'Invite Link',
      'PIN Reset Requested', 'Last Seen', 'App Version', 'Added On', 'Deletion Requested']),
  'Referrals': ['Referral ID', 'Received', 'Referrer Code', 'Referrer ID', 'Referrer Name', 'Referrer Mobile',
    'Friend Name', 'Friend Mobile', 'District', 'Address', 'Note', 'Status', 'Sales Executive', 'System (kW)',
    'Reward (₹)', 'Slab Working', 'Reward Locked On', 'Payout Status', 'UPI ID', 'UTR / Ref No.', 'Paid On',
    'Office Note'],
  'Referral Slabs': ['From kW', 'To kW', 'Rate per kW (₹)', 'Note'],
  'Tickets': ['Ticket ID', 'Raised On', 'Customer ID', 'Customer Name', 'Mobile', 'Category', 'Description',
    'Error Code', 'Photos', 'Status', 'Assigned To', 'Visit Date', 'Resolution', 'Resolved On',
    'Customer Rating', 'Customer Feedback', 'Closed On', 'Last Updated'],
  'Documents': ['Customer ID', 'Customer Name', 'Title', 'Drive Link', 'Added On', 'Added By'],
  'Settings': ['Key', 'Value', 'Note'],
};

const DEFAULT_SETTINGS = [
  ['install_check_sheet_id', '', 'ID of "AVIP Solar Install Check – Database" (the long code in its link). Progress, serial numbers and the Installation Report come from it.'],
  ['site_visits_sheet_id', '', 'ID of "AVIP Site Visits Log". Quotation PDF and referral site-visit status come from it.'],
  ['office_alert_email', '', 'Gets an e-mail for every new referral, ticket, upload and PIN reset request. Several: separate with commas.'],
  ['support_phone', '9288176289', 'Call button in the app'],
  ['support_whatsapp', '9288176289', 'WhatsApp button in the app and in the referral message'],
  ['app_link', DEFAULT_APP_LINK, 'Download link put in invite messages'],
  ['announcement', '', 'Shown on top of the home screen (offers, cleaning tips, weather alerts). Leave blank for none.'],
  ['review_mobile', '', 'Google Play review login: mobile number of the demo customer row. Leave blank when not needed.'],
  ['review_pin', '', 'Google Play review login: fixed 4-digit PIN for review_mobile (never asked to change).'],
  ['referral_terms', 'Reward installation ke baad UPI par milega. Ek mobile number ka referral sirf ek baar count hoga. AVIP ka faisla final hoga.', 'Shown under Refer & Earn'],
];

const DEFAULT_SLABS = [
  [0, 5, 1000, 'First 5 kW'],
  [5, 10, 800, 'Above 5 up to 10 kW'],
  [10, 40, 500, 'Above 10 up to 40 kW'],
  [40, '', 400, 'Above 40 kW (blank To = no limit)'],
];

// ------------------------------------------------------------------ web app

function doGet() {
  return out({ ok: true, app: 'AVIP Solar customer app' });
}

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return out({ ok: false, error: 'Not allowed' });
    const handler = HANDLERS[d.kind];
    if (!handler) return out({ ok: false, error: 'unknown kind' });
    const readOnly = READ_ONLY.indexOf(d.kind) >= 0;
    const lock = LockService.getScriptLock();
    if (!readOnly) lock.waitLock(30000);
    try {
      const result = handler(d) || {};
      result.ok = true;
      return out(result);
    } finally {
      if (!readOnly) lock.releaseLock();
    }
  } catch (err) {
    return out({ ok: false, error: String((err && err.message) || err) });
  }
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

const HANDLERS = {
  login: login,
  setPin: setPin,
  forgotPin: forgotPin,
  home: home,
  refer: refer,
  setUpi: setUpi,
  ticket: newTicket,
  ticketPhoto: ticketPhoto,
  ticketClose: ticketClose,
  ticketReopen: ticketReopen,
  doc: doc,
  upload: upload,
  deleteAccount: deleteAccount,
};
// no script lock: they only read, or write single cells of the caller's own row
const READ_ONLY = ['doc', 'home'];

// ------------------------------------------------------------------ setup & menu

/** Run once from the editor. Safe to run again: it only adds what is missing. */
function setup() {
  const ss = book();
  Object.keys(TABS).forEach(function (name) {
    let sh = ss.getSheetByName(name);
    const headers = TABS[name];
    if (!sh) {
      sh = ss.insertSheet(name);
      sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold').setBackground('#DDEFE4');
      sh.setFrozenRows(1);
    } else {
      // add any new columns at the end, never move existing ones
      const have = sh.getRange(1, 1, 1, Math.max(1, sh.getLastColumn())).getValues()[0].map(function (h) { return String(h).trim(); });
      headers.forEach(function (h) {
        if (have.indexOf(h) < 0) {
          const c = sh.getLastColumn() + 1;
          sh.getRange(1, c).setValue(h).setFontWeight('bold').setBackground('#DDEFE4');
          have.push(h);
        }
      });
    }
  });
  const first = ss.getSheetByName('Sheet1');
  if (first && first.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(first);

  const st = ss.getSheetByName('Settings');
  const keys = rows('Settings').map(function (r) { return clean(r['Key']); });
  DEFAULT_SETTINGS.forEach(function (s) { if (keys.indexOf(s[0]) < 0) st.appendRow(s); });

  if (rows('Referral Slabs').length === 0) {
    ss.getSheetByName('Referral Slabs').getRange(2, 1, DEFAULT_SLABS.length, 4).setValues(DEFAULT_SLABS);
  }

  dropdown('Customers', 'Active', ['Yes', 'No']);
  dropdown('Referrals', 'Status', REF_STATUS.concat([NOT_INTERESTED]));
  dropdown('Referrals', 'Payout Status', ['Due', 'Paid', 'On hold']);
  dropdown('Tickets', 'Status', TICKET_STATUS);
  dropdown('Tickets', 'Category', TICKET_CATEGORIES);

  secret();
  fileRoot();

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'sync') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('sync').timeBased().everyHours(1).create();

  Logger.log('Ready. Fill the Settings tab, then deploy as a web app (Execute as: Me, Who has access: Anyone).');
}

function dropdown(tab, header, values) {
  const t = table(tab);
  const rule = SpreadsheetApp.newDataValidation().requireValueInList(values, true).setAllowInvalid(true).build();
  t.sh.getRange(2, t.col(header), Math.max(1, t.sh.getMaxRows() - 1), 1).setDataValidation(rule);
}

function onOpen() {
  SpreadsheetApp.getUi().createMenu('AVIP')
    .addItem('Sync now (new customers, invites, referrals)', 'syncFromMenu')
    .addToUi();
}

function syncFromMenu() {
  const r = sync();
  SpreadsheetApp.getUi().alert('Sync done.\nNew customers added: ' + r.added +
    '\nInvite links made: ' + r.invites + '\nReferrals updated: ' + r.referrals);
}

// ------------------------------------------------------------------ sheet helpers

function book() { return SpreadsheetApp.getActiveSpreadsheet(); }

function table(name) {
  const sh = book().getSheetByName(name);
  if (!sh) throw new Error('Tab missing: ' + name + ' (run setup)');
  const headers = sh.getRange(1, 1, 1, Math.max(1, sh.getLastColumn())).getValues()[0].map(function (h) { return String(h).trim(); });
  return {
    sh: sh,
    headers: headers,
    col: function (h) {
      const i = headers.indexOf(h);
      if (i < 0) throw new Error('Column missing: ' + name + ' → ' + h + ' (run setup)');
      return i + 1;
    },
  };
}

const KEY_COLS = 8;

function blankRow(r) {
  for (let j = 0; j < Math.min(KEY_COLS, r.length); j++) if (String(r[j]).trim() !== '') return false;
  return true;
}

/** Last row that has something in one of the first columns (the office may leave the ID columns blank). */
function lastRow(sh) {
  const n = sh.getLastRow();
  if (n < 2) return 1;
  const v = sh.getRange(2, 1, n - 1, Math.max(1, Math.min(KEY_COLS, sh.getLastColumn()))).getValues();
  for (let i = v.length - 1; i >= 0; i--) if (!blankRow(v[i])) return i + 2;
  return 1;
}

/** All data rows as objects keyed by header, plus _row. Rows empty in the first columns are skipped. */
function rows(name) {
  const t = table(name);
  const n = lastRow(t.sh);
  if (n < 2) return [];
  const vals = t.sh.getRange(2, 1, n - 1, t.headers.length).getValues();
  const list = [];
  vals.forEach(function (r, i) {
    if (blankRow(r)) return;
    const o = { _row: i + 2 };
    t.headers.forEach(function (h, j) { if (h) o[h] = r[j]; });
    list.push(o);
  });
  return list;
}

/** Writes the given fields into one row, cell by cell. */
function setFields(name, row, obj) {
  const t = table(name);
  Object.keys(obj).forEach(function (k) {
    if (k.charAt(0) === '_') return;
    t.sh.getRange(row, t.col(k)).setValue(obj[k] === undefined || obj[k] === null ? '' : obj[k]);
  });
}

function append(name, obj) {
  const t = table(name);
  const row = lastRow(t.sh) + 1;
  if (row > t.sh.getMaxRows()) t.sh.insertRowsAfter(t.sh.getMaxRows(), 200);
  setFields(name, row, obj);
  return row;
}

function clean(v, max) {
  const s = String(v === undefined || v === null ? '' : v).trim();
  return max ? s.slice(0, max) : s;
}

function last10(v) { return String(v || '').replace(/\D/g, '').slice(-10); }

function num(v) {
  const n = Number(String(v === undefined || v === null ? '' : v).replace(/[^\d.\-]/g, ''));
  return isFinite(n) ? n : 0;
}

function now() { return new Date(); }

/** A date cell (or text) as "21 Oct 2026"; '' when empty. */
function day(v) {
  if (isDate(v)) return Utilities.formatDate(v, TZ, 'd MMM yyyy');
  const s = clean(v);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (m) return Utilities.formatDate(new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])), TZ, 'd MMM yyyy');
  // a date typed as text the Indian way: 31/10/2026, 31-10-2026 or 31.10.26
  const n = s.match(/^(\d{1,2})[\/.-](\d{1,2})[\/.-](\d{2}|\d{4})$/);
  if (n && Number(n[2]) >= 1 && Number(n[2]) <= 12 && Number(n[1]) >= 1 && Number(n[1]) <= 31) {
    const y = n[3].length === 2 ? 2000 + Number(n[3]) : Number(n[3]);
    return Utilities.formatDate(new Date(y, Number(n[2]) - 1, Number(n[1])), TZ, 'd MMM yyyy');
  }
  return s;
}

function isDate(v) { return Object.prototype.toString.call(v) === '[object Date]' && !isNaN(v.getTime()); }

function stamp(v) {
  if (isDate(v)) return Utilities.formatDate(v, TZ, 'yyyy-MM-dd HH:mm');
  return clean(v);
}

/** ₹1,23,456 (Indian grouping). */
function money(n) {
  const s = String(Math.round(Math.abs(n)));
  const head = s.slice(0, -3);
  const tail = s.slice(-3);
  const grouped = head ? head.replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + tail : tail;
  return (n < 0 ? '-₹' : '₹') + grouped;
}

function settings() {
  const m = {};
  rows('Settings').forEach(function (r) { const k = clean(r['Key']); if (k) m[k] = clean(r['Value']); });
  return m;
}

function alertOffice(s, subject, body) {
  const to = clean(s['office_alert_email']);
  if (!to) return;
  try {
    MailApp.sendEmail({ to: to, subject: 'AVIP Solar app: ' + subject, body: body + '\n\n— AVIP Solar customer app' });
  } catch (e) {
    Logger.log('Mail failed: ' + e);
  }
}

/** Google Drive file ID from a Drive link (or the ID itself). */
function driveId(link) {
  const s = clean(link);
  let m = s.match(/\/d\/([\w-]{20,})/);
  if (m) return m[1];
  m = s.match(/[?&]id=([\w-]{20,})/);
  if (m) return m[1];
  if (/^[\w-]{25,}$/.test(s)) return s;
  return '';
}

// ------------------------------------------------------------------ the other two sheets (read only)

const EXT = {};

/** Rows of a tab in another spreadsheet, as objects. [] when not set up or not shared with this account. */
function extRows(sheetId, tabName) {
  const id = clean(sheetId);
  if (!id) return [];
  const k = id + '|' + tabName;
  if (EXT[k]) return EXT[k];
  let list = [];
  try {
    const sh = SpreadsheetApp.openById(id).getSheetByName(tabName);
    if (sh && sh.getLastRow() > 1) {
      const vals = sh.getDataRange().getValues();
      const headers = vals[0].map(function (h) { return String(h).trim(); });
      list = vals.slice(1).filter(function (r) { return String(r[0]).trim() !== ''; }).map(function (r) {
        const o = {};
        headers.forEach(function (h, j) { if (h) o[h] = r[j]; });
        return o;
      });
    }
  } catch (e) {
    Logger.log('Cannot read ' + tabName + ' from ' + id + ': ' + e);
  }
  EXT[k] = list;
  return list;
}

function groupBy(list, field) {
  const m = {};
  list.forEach(function (r) { const k = clean(r[field]); if (k) (m[k] = m[k] || []).push(r); });
  return m;
}

/** Everything needed from AVIP Solar Install Check, keyed for quick lookups. */
function installCheck(s) {
  const id = s['install_check_sheet_id'];
  const sites = extRows(id, 'Sites');
  const byMobile = {};
  sites.forEach(function (x) {
    [last10(x['Mobile']), last10(x['WhatsApp'])].forEach(function (m) {
      if (m.length !== 10) return;
      const cur = byMobile[m];
      if (!cur || String(stamp(x['Created On'])) > String(stamp(cur['Created On']))) byMobile[m] = x;
    });
  });
  const byId = {};
  sites.forEach(function (x) { byId[clean(x['Site ID'])] = x; });
  const users = {};
  extRows(id, 'Users').forEach(function (u) { users[clean(u['Name']).toLowerCase()] = last10(u['Mobile']); });
  return {
    sites: sites, byId: byId, byMobile: byMobile, users: users,
    dispatch: groupBy(extRows(id, 'Dispatch Log'), 'Site ID'),
    serials: groupBy(extRows(id, 'Serial Numbers'), 'Site ID'),
    signoff: groupBy(extRows(id, 'Sign-off & QC'), 'Site ID'),
    photos: groupBy(extRows(id, 'Photos'), 'Site ID'),
  };
}

/** Visits from the AVIP Site Visits Log (AVIP Solar Sales). */
function siteVisits(s) {
  const list = extRows(s['site_visits_sheet_id'], 'Site Visits');
  const byMobile = {};
  const byNo = {};
  list.forEach(function (v) {
    const m = last10(v['WhatsApp']);
    if (m.length === 10) {
      const cur = byMobile[m];
      // keep a Converted visit over an open one, else the latest
      if (!cur || clean(v['Lead Status']) === 'Converted' || clean(cur['Lead Status']) !== 'Converted') byMobile[m] = v;
    }
    byNo[clean(v['Visit No'])] = v;
  });
  return { list: list, byMobile: byMobile, byNo: byNo };
}

function icSiteFor(c, ic) {
  const sid = clean(c['Install Check Site ID']);
  if (sid && ic.byId[sid]) return ic.byId[sid];
  return ic.byMobile[last10(c['Mobile'])] || null;
}

function visitFor(c, site, sv) {
  const no = clean(c['Sales Visit No.']) || (site ? clean(site['Sales Visit No.']) : '');
  if (no && sv.byNo[no]) return sv.byNo[no];
  return sv.byMobile[last10(c['Mobile'])] || null;
}

// ------------------------------------------------------------------ security

function secret() {
  const p = PropertiesService.getScriptProperties();
  let s = p.getProperty('SECRET');
  if (!s) {
    s = Utilities.getUuid() + Utilities.getUuid();
    p.setProperty('SECRET', s);
  }
  return s;
}

function b64(bytes) { return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/, ''); }

function pinHash(mobile, pin) {
  return '#' + b64(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, secret() + '|' + last10(mobile) + '|' + pin)).slice(0, 32);
}

function tokenFor(c) {
  const sig = b64(Utilities.computeHmacSha256Signature(clean(c['Customer ID']) + '|' + String(c['PIN']), secret())).slice(0, 24);
  return clean(c['Customer ID']) + '.' + sig;
}

function isActive(c) { return clean(c['Active']).toLowerCase() !== 'no'; }

/** All active rows of this mobile number. The first one holds the PIN. */
function customersByMobile(mobile, list) {
  const m = last10(mobile);
  return (list || rows('Customers')).filter(function (c) { return isActive(c) && last10(c['Mobile']) === m; });
}

/** Checks the session token; returns the customer's first (PIN) row. */
function auth(d, allowTempPin) {
  const tok = clean(d.token);
  const id = tok.split('.')[0];
  const c = rows('Customers').filter(function (x) { return clean(x['Customer ID']) === id; })[0];
  if (!c || !isActive(c) || tokenFor(c) !== tok) throw new Error('SESSION: Please login again.');
  if (!allowTempPin && String(c['PIN']).charAt(0) !== '#' && !isReview(c)) throw new Error('SESSION: Please set your own PIN first.');
  return c;
}

// ------------------------------------------------------------------ login & PIN

function login(d) {
  const mobile = last10(d.mobile);
  if (mobile.length !== 10) throw new Error('10 अंकों का mobile number डालें।');
  const pin = clean(d.pin);
  const cache = CacheService.getScriptCache();
  const failKey = 'fail_' + mobile;
  const fails = Number(cache.get(failKey) || 0);
  if (fails >= 5) throw new Error('5 बार गलत PIN। 10 मिनट बाद फिर कोशिश करें।');
  const c = customersByMobile(mobile)[0];
  if (!c) throw new Error('यह number AVIP Solar में registered नहीं है। Office को call करें: ' + clean(settings()['support_phone']));
  if (isReview(c)) {
    // Google Play reviewers: fixed PIN from Settings, never asked to change it
    if (pin !== clean(settings()['review_pin'])) { cache.put(failKey, String(fails + 1), 600); throw new Error('गलत PIN।'); }
    cache.remove(failKey);
    return { token: tokenFor(c), name: clean(c['Name']), mustSetPin: false };
  }
  const stored = clean(c['PIN']);
  if (!stored) throw new Error('आपका PIN अभी बना नहीं है। Office से temporary PIN लें।');
  const ok = stored.charAt(0) === '#' ? stored === pinHash(mobile, pin) : stored === pin;
  if (!ok) {
    cache.put(failKey, String(fails + 1), 600);
    throw new Error('गलत PIN।');
  }
  cache.remove(failKey);
  return { token: tokenFor(c), name: clean(c['Name']), mustSetPin: stored.charAt(0) !== '#' };
}

function setPin(d) {
  const c = auth(d, true);
  if (isReview(c)) throw new Error('Demo account का PIN नहीं बदल सकते।');
  const pin = clean(d.newPin);
  if (!/^\d{4}$/.test(pin)) throw new Error('PIN 4 अंकों का होना चाहिए।');
  if (WEAK_PINS.indexOf(pin) >= 0) throw new Error('यह PIN बहुत आसान है। कोई दूसरा चुनें।');
  if (clean(c['PIN']) === pin) throw new Error('Office वाले PIN से अलग PIN चुनें।');
  const hash = pinHash(c['Mobile'], pin);
  setFields('Customers', c._row, { 'PIN': hash, 'Invite Link': '', 'PIN Reset Requested': '' });
  c['PIN'] = hash;
  return { token: tokenFor(c), name: clean(c['Name']) };
}

function forgotPin(d) {
  const mobile = last10(d.mobile);
  if (mobile.length !== 10) throw new Error('10 अंकों का mobile number डालें।');
  const cache = CacheService.getScriptCache();
  const k = 'reset_' + mobile;
  if (cache.get(k)) return { message: 'Request पहले ही भेज दी गई है। Office जल्द ही WhatsApp पर नया PIN भेजेगा।' };
  const c = customersByMobile(mobile)[0];
  if (!c) throw new Error('यह number AVIP Solar में registered नहीं है।');
  if (isReview(c)) return { message: 'Demo account: PIN Settings tab के review_pin में है।' };
  setFields('Customers', c._row, { 'PIN Reset Requested': now() });
  cache.put(k, '1', 3600);
  const s = settings();
  alertOffice(s, 'PIN reset request – ' + clean(c['Name']),
    clean(c['Name']) + ' (' + mobile + ', ' + clean(c['Customer ID']) + ') forgot the app PIN.\n' +
    'Type a new 4-digit PIN in the PIN cell of the Customers tab, then use AVIP → Sync now and send the Invite Link on WhatsApp.');
  return { message: 'Request भेज दी गई। Office WhatsApp पर नया temporary PIN भेजेगा।' };
}

/** The demo customer used by Google Play reviewers (Settings → review_mobile / review_pin). */
function isReview(c) {
  const s = settings();
  const m = last10(s['review_mobile']);
  return m.length === 10 && /^\d{4}$/.test(clean(s['review_pin'])) && last10(c['Mobile']) === m;
}

/**
 * "Delete my account" in the app (required by Google Play). Switches off every
 * row of this mobile number at once (Active = No, PIN cleared) and e-mails the
 * office, who then deletes the customer's app data within 30 days.
 */
function deleteAccount(d) {
  const c = auth(d, true);
  if (isReview(c)) return { demo: true, message: 'Demo account delete नहीं होता। असली customer का account तुरंत बंद हो जाता है।' };
  const t = table('Customers');
  if (t.headers.indexOf('Deletion Requested') < 0) {
    t.sh.getRange(1, t.sh.getLastColumn() + 1).setValue('Deletion Requested');
  }
  const mine = customersByMobile(c['Mobile']);
  mine.forEach(function (x) {
    setFields('Customers', x._row, { 'Active': 'No', 'PIN': '', 'Invite Link': '', 'Deletion Requested': now() });
  });
  const s = settings();
  alertOffice(s, 'Account deletion request – ' + clean(c['Name']),
    clean(c['Name']) + ' (' + last10(c['Mobile']) + ', ' + mine.map(function (x) { return clean(x['Customer ID']); }).join(', ') +
    ') deleted their AVIP Solar app account from the app.\n' +
    'The account is already switched off (Active = No). Within 30 days delete their app data: uploaded photos and documents ' +
    '(Drive folder "' + FILE_ROOT + '"), their rows in Tickets and Documents, and their UPI ID. ' +
    'Keep the Customers row (Active = No) so the sync does not add them again; warranty, subsidy and billing records stay as the law needs.');
  return { message: 'आपका account बंद कर दिया गया है। आपकी app details 30 दिन में delete कर दी जाएँगी। सवाल हो तो call करें: ' + clean(s['support_phone']) };
}

// ------------------------------------------------------------------ home (everything the app shows)

function home(d) {
  const c = auth(d);
  const s = settings();
  const all = rows('Customers');
  const mine = customersByMobile(c['Mobile'], all);
  const ids = mine.map(function (x) { return clean(x['Customer ID']); });
  const ic = installCheck(s);
  const sv = siteVisits(s);

  const systems = mine.map(function (x) { return systemView(x, ic, sv); });
  const docs = documentsFor(mine, ic, sv).map(function (x) {
    return { id: x.id, url: x.url, title: x.title, date: x.date, by: x.by };
  });

  const code = clean(c['Referral Code']);
  const refs = rows('Referrals').filter(function (r) {
    return ids.indexOf(clean(r['Referrer ID'])) >= 0 || (code && clean(r['Referrer Code']).toUpperCase() === code.toUpperCase());
  }).map(function (r) {
    const reward = num(r['Reward (₹)']);
    return {
      id: clean(r['Referral ID']), name: clean(r['Friend Name']), district: clean(r['District']),
      date: day(r['Received']), status: clean(r['Status']) || REF_STATUS[0], kw: num(r['System (kW)']),
      reward: reward, payout: clean(r['Payout Status']), paidOn: day(r['Paid On']),
    };
  }).reverse();
  const paid = refs.filter(function (r) { return r.payout === 'Paid'; }).reduce(function (a, r) { return a + r.reward; }, 0);
  const pending = refs.filter(function (r) { return r.payout !== 'Paid' && r.status !== NOT_INTERESTED; })
    .reduce(function (a, r) { return a + r.reward; }, 0);

  const tickets = rows('Tickets').filter(function (t) { return ids.indexOf(clean(t['Customer ID'])) >= 0; })
    .map(function (t) {
      return {
        id: clean(t['Ticket ID']), date: day(t['Raised On']), category: clean(t['Category']),
        description: clean(t['Description']), errorCode: clean(t['Error Code']),
        photos: clean(t['Photos']) ? clean(t['Photos']).split(/\s+/).length : 0,
        status: clean(t['Status']) || 'Open', assigned: clean(t['Assigned To']), visit: day(t['Visit Date']),
        resolution: clean(t['Resolution']), rating: num(t['Customer Rating']),
      };
    }).reverse();

  // who is looking, and with which version
  const seen = { 'Last Seen': now() };
  if (clean(d.appVersion)) seen['App Version'] = clean(d.appVersion, 20);
  setFields('Customers', c._row, seen);

  return {
    customer: {
      id: clean(c['Customer ID']), name: clean(c['Name']), mobile: last10(c['Mobile']),
      code: code, upi: clean(c['UPI ID']),
    },
    systems: systems,
    documents: docs,
    referrals: refs,
    referralTotals: { count: refs.length, paid: paid, pending: pending },
    slabs: slabs().map(function (x) { return { from: x.from, to: x.to === Infinity ? 0 : x.to, rate: x.rate }; }),
    referralTerms: clean(s['referral_terms']),
    tickets: tickets,
    categories: TICKET_CATEGORIES,
    announcement: clean(s['announcement']),
    support: { phone: last10(s['support_phone']) || '9288176289', whatsapp: last10(s['support_whatsapp']) || last10(s['support_phone']) || '9288176289' },
  };
}

/** One installation with its progress stages, merged from this sheet and Install Check. */
function systemView(c, ic, sv) {
  const site = icSiteFor(c, ic);
  const sid = site ? clean(site['Site ID']) : '';
  const visit = visitFor(c, site, sv);
  const auto = {};
  if (site) {
    auto.survey = site['Survey Date'];
    auto.order = site['Verified On'] || (IC_ORDERED.indexOf(clean(site['Status'])) >= 0 ? site['Created On'] : '');
    const trips = ic.dispatch[sid] || [];
    if (trips.length) auto.dispatch = trips[0]['Dispatch Date'];
    // installation counts as complete only while it is submitted or QC-approved (not during rework)
    const signs = ic.signoff[sid] || [];
    if (signs.length && IC_INSTALLED.indexOf(clean(site['Status'])) >= 0) auto.install = signs[signs.length - 1]['Submitted On'];
  }
  if (!auto.survey && visit) auto.survey = visit['Visit Date'];

  const dates = STAGES.map(function (st) { return day(c[st.col]) || day(auto[st.key]); });
  let lastDone = -1;
  dates.forEach(function (v, i) { if (v) lastDone = i; });
  if (site && IC_INSTALLED.indexOf(clean(site['Status'])) >= 0) lastDone = Math.max(lastDone, 4);
  else if (site && IC_ORDERED.indexOf(clean(site['Status'])) >= 0) lastDone = Math.max(lastDone, 1);
  else if (visit && clean(visit['Lead Status']) === 'Converted') lastDone = Math.max(lastDone, 1);
  const current = lastDone + 1 < STAGES.length ? lastDone + 1 : -1;

  const serials = (ic.serials[sid] || []).map(function (r) {
    return { type: clean(r['Item Type']), make: clean(r['Make / Model']), serial: clean(r['Serial No.']) };
  });
  const photos = (ic.photos[sid] || []).length;

  const exec = clean(c['Sales Executive']) || (site ? clean(site['Sales Person']) : '') || (visit ? clean(visit['Sales Executive']) : '');
  const execMobile = last10(c['Sales Exec Mobile']) || ic.users[exec.toLowerCase()] || (visit ? last10(visit['Exec Mobile']) : '');

  return {
    id: clean(c['Customer ID']),
    kw: num(c['System (kW)']) || (site ? num(site['Site Size (kW)']) : 0) || (visit ? num(visit['kWp']) : 0),
    type: clean(c['System Type']) || (site ? clean(site['System Type']) : '') || (visit ? clean(visit['System Type']) : ''),
    address: clean(c['Address']) || (site ? clean(site['Address']) : ''),
    district: clean(c['District']) || (site ? clean(site['District']) : ''),
    stages: STAGES.map(function (st, i) {
      return { key: st.key, title: st.title, date: dates[i], done: i <= lastDone, current: i === current };
    }),
    doneCount: lastDone + 1,
    action: clean(c['Customer Action']),
    note: clean(c['Note to Customer']),
    serials: serials,
    photos: photos,
    salesExecutive: exec,
    salesExecMobile: execMobile,
  };
}

/** Documents the customer may open: Documents tab + Installation Report + Quotation. */
function documentsFor(mine, ic, sv) {
  const ids = mine.map(function (x) { return clean(x['Customer ID']); });
  const list = [];
  const seen = {};
  function add(link, title, date, by) {
    const id = driveId(link);
    const url = id ? '' : clean(link);
    const k = id || url;
    if (!k || seen[k]) return;
    seen[k] = true;
    list.push({ id: id, url: url, title: title, date: day(date), by: by || '' });
  }
  mine.forEach(function (c) {
    const site = icSiteFor(c, ic);
    const visit = visitFor(c, site, sv);
    if (visit && clean(visit['Quotation PDF'])) add(visit['Quotation PDF'], 'Quotation', visit['Visit Date']);
    if (site) {
      // only the latest report (a rework round makes a new one)
      const withReport = (ic.signoff[clean(site['Site ID'])] || []).filter(function (r) { return clean(r['Report PDF Link']); });
      const r = withReport[withReport.length - 1];
      if (r) add(r['Report PDF Link'], 'Installation Report', r['Submitted On']);
    }
  });
  rows('Documents').forEach(function (r) {
    if (ids.indexOf(clean(r['Customer ID'])) >= 0 && clean(r['Drive Link'])) {
      add(r['Drive Link'], clean(r['Title']) || 'Document', r['Added On'], clean(r['Added By']) === 'Customer' ? 'customer' : '');
    }
  });
  return list;
}

/** Sends one document's file to the phone (only documents of this customer). */
function doc(d) {
  const c = auth(d);
  const s = settings();
  const mine = customersByMobile(c['Mobile']);
  const id = clean(d.fileId);
  const ok = documentsFor(mine, installCheck(s), siteVisits(s)).some(function (x) { return x.id && x.id === id; });
  if (!ok) throw new Error('Document नहीं मिला।');
  const f = DriveApp.getFileById(id);
  if (f.getSize() > 20 * 1024 * 1024) throw new Error('File बहुत बड़ी है। Office से WhatsApp पर मँगवाएँ।');
  const blob = f.getBlob();
  return { name: f.getName(), mime: blob.getContentType(), data: Utilities.base64Encode(blob.getBytes()) };
}

// ------------------------------------------------------------------ files from the customer

function fileRoot() {
  const p = PropertiesService.getScriptProperties();
  const id = p.getProperty('FILE_ROOT');
  if (id) {
    try { return DriveApp.getFolderById(id); } catch (e) { /* recreated below */ }
  }
  const f = DriveApp.createFolder(FILE_ROOT);
  p.setProperty('FILE_ROOT', f.getId());
  return f;
}

function customerFolder(c) {
  const root = fileRoot();
  const name = clean(c['Customer ID']) + ' - ' + clean(c['Name'], 60);
  const it = root.getFoldersByName(name);
  return it.hasNext() ? it.next() : root.createFolder(name);
}

function saveFile(c, name, mime, data) {
  if (!data) throw new Error('File खाली है।');
  const bytes = Utilities.base64Decode(data);
  if (bytes.length > 12 * 1024 * 1024) throw new Error('File बहुत बड़ी है।');
  return customerFolder(c).createFile(Utilities.newBlob(bytes, mime, name));
}

function upload(d) {
  const c = auth(d);
  const title = clean(d.title, 80) || 'Photo';
  const mime = clean(d.mime) || 'image/jpeg';
  const ext = mime === 'application/pdf' ? '.pdf' : '.jpg';
  const f = saveFile(c, title.replace(/[^\w\- ]/g, '_') + ' ' + Utilities.formatDate(now(), TZ, 'yyyyMMdd-HHmm') + ext, mime, d.data);
  append('Documents', {
    'Customer ID': clean(c['Customer ID']), 'Customer Name': clean(c['Name']), 'Title': title,
    'Drive Link': f.getUrl(), 'Added On': now(), 'Added By': 'Customer',
  });
  const s = settings();
  alertOffice(s, 'Upload from ' + clean(c['Name']), clean(c['Name']) + ' (' + last10(c['Mobile']) + ') uploaded "' + title + '":\n' + f.getUrl() +
    (clean(c['Customer Action']) ? '\n\nTheir pending action was: ' + clean(c['Customer Action']) + '\nClear "Customer Action" in the Customers tab when it is done.' : ''));
  return {};
}

// ------------------------------------------------------------------ refer & earn

/** Slabs from the Referral Slabs tab, lowest first. A blank "To kW" means no limit. */
function slabs() {
  return rows('Referral Slabs').map(function (r) {
    const to = clean(r['To kW']);
    return { from: num(r['From kW']), to: to === '' ? Infinity : num(to), rate: num(r['Rate per kW (₹)']) };
  }).filter(function (x) { return x.rate > 0; }).sort(function (a, b) { return a.from - b.from; });
}

/** Reward for a system of [kw]: each kW is paid at the rate of its own slab. */
function reward(kw, list) {
  let total = 0;
  const parts = [];
  (list || slabs()).forEach(function (x) {
    if (kw <= x.from) return;
    const units = Math.min(kw, x.to) - x.from;
    if (units <= 0) return;
    total += units * x.rate;
    parts.push((Math.round(units * 100) / 100) + ' kW × ' + money(x.rate));
  });
  return { amount: Math.round(total), working: parts.join(' + ') };
}

function refer(d) {
  const c = auth(d);
  const s = settings();
  const name = clean(d.name, 80);
  const mobile = last10(d.mobile);
  if (name.length < 2) throw new Error('दोस्त का नाम डालें।');
  if (!/^[6-9]\d{9}$/.test(mobile)) throw new Error('सही 10 अंकों का mobile number डालें।');
  if (mobile === last10(c['Mobile'])) throw new Error('अपना खुद का number refer नहीं कर सकते।');
  if (rows('Customers').some(function (x) { return last10(x['Mobile']) === mobile; })) {
    throw new Error('यह number पहले से AVIP Solar का customer है।');
  }
  if (rows('Referrals').some(function (r) { return last10(r['Friend Mobile']) === mobile; })) {
    throw new Error('यह number पहले ही refer किया जा चुका है।');
  }
  if (siteVisits(s).byMobile[mobile]) throw new Error('हमारी Sales team पहले से इस number से बात कर रही है।');
  if (installCheck(s).byMobile[mobile]) throw new Error('यह number पहले से AVIP Solar का customer है।');

  const prefix = 'R' + Utilities.formatDate(now(), TZ, 'yyMMdd') + '-';
  let max = 0;
  rows('Referrals').forEach(function (r) {
    const id = clean(r['Referral ID']);
    if (id.indexOf(prefix) === 0) max = Math.max(max, Number(id.slice(prefix.length)) || 0);
  });
  const id = prefix + ('0' + (max + 1)).slice(-2);
  append('Referrals', {
    'Referral ID': id, 'Received': now(), 'Referrer Code': clean(c['Referral Code']),
    'Referrer ID': clean(c['Customer ID']), 'Referrer Name': clean(c['Name']), 'Referrer Mobile': "'" + last10(c['Mobile']),
    'Friend Name': name, 'Friend Mobile': "'" + mobile, 'District': clean(d.district, 60), 'Address': clean(d.address, 300),
    'Note': clean(d.note, 500), 'Status': REF_STATUS[0], 'UPI ID': clean(c['UPI ID']),
  });
  alertOffice(s, 'New referral – ' + name,
    'Referred by: ' + clean(c['Name']) + ' (' + last10(c['Mobile']) + ', code ' + clean(c['Referral Code']) + ')\n' +
    'Friend: ' + name + ', ' + mobile + '\nDistrict: ' + clean(d.district) + '\nAddress: ' + clean(d.address) +
    '\nNote: ' + clean(d.note) + '\n\nReferral ID ' + id + '. Please assign a sales executive to call within 24 hours.');
  return { referralId: id };
}

function setUpi(d) {
  const c = auth(d);
  const upi = clean(d.upi, 60);
  if (upi && !/^[\w.\-]{2,}@[a-zA-Z][\w.]{1,}$/.test(upi)) throw new Error('UPI ID सही नहीं लग रहा (जैसे 9876543210@ybl)।');
  customersByMobile(c['Mobile']).forEach(function (x) { setFields('Customers', x._row, { 'UPI ID': upi }); });
  // unpaid referrals pick up the new UPI ID
  const ids = customersByMobile(c['Mobile']).map(function (x) { return clean(x['Customer ID']); });
  rows('Referrals').forEach(function (r) {
    if (ids.indexOf(clean(r['Referrer ID'])) >= 0 && clean(r['Payout Status']) !== 'Paid') {
      setFields('Referrals', r._row, { 'UPI ID': upi });
    }
  });
  return {};
}

// ------------------------------------------------------------------ tickets

function ticketOf(c, ticketId) {
  const ids = customersByMobile(c['Mobile']).map(function (x) { return clean(x['Customer ID']); });
  const t = rows('Tickets').filter(function (x) { return clean(x['Ticket ID']) === clean(ticketId); })[0];
  if (!t || ids.indexOf(clean(t['Customer ID'])) < 0) throw new Error('Ticket नहीं मिला।');
  return t;
}

function newTicket(d) {
  const c = auth(d);
  const category = clean(d.category, 40);
  const text = clean(d.description, 1500);
  if (!category) throw new Error('समस्या का प्रकार चुनें।');
  if (text.length < 5) throw new Error('समस्या थोड़ा detail में लिखें।');
  const open = rows('Tickets').filter(function (t) {
    return clean(t['Customer ID']) === clean(c['Customer ID']) && ['Resolved', 'Closed'].indexOf(clean(t['Status'])) < 0;
  });
  if (open.length >= 5) throw new Error('आपके 5 tickets पहले से खुले हैं। Office को call करें।');
  const system = customersByMobile(c['Mobile']).filter(function (x) { return clean(x['Customer ID']) === clean(d.systemId); })[0] || c;

  const prefix = 'T' + Utilities.formatDate(now(), TZ, 'yyMM') + '-';
  let max = 0;
  rows('Tickets').forEach(function (t) {
    const id = clean(t['Ticket ID']);
    if (id.indexOf(prefix) === 0) max = Math.max(max, Number(id.slice(prefix.length)) || 0);
  });
  const id = prefix + ('00' + (max + 1)).slice(-3);
  append('Tickets', {
    'Ticket ID': id, 'Raised On': now(), 'Customer ID': clean(system['Customer ID']), 'Customer Name': clean(system['Name']),
    'Mobile': "'" + last10(system['Mobile']), 'Category': category, 'Description': text,
    'Error Code': clean(d.errorCode, 40), 'Photos': '', 'Status': 'Open', 'Last Updated': now(),
  });
  const s = settings();
  alertOffice(s, 'New ticket ' + id + ' – ' + category,
    clean(system['Name']) + ' (' + last10(system['Mobile']) + ', ' + clean(system['Customer ID']) + ')\n' +
    clean(system['Address']) + ' ' + clean(system['District']) + '\n\nProblem: ' + category +
    (clean(d.errorCode) ? '\nError code: ' + clean(d.errorCode) : '') + '\n' + text +
    '\n\nAssign it in the Tickets tab (Status, Assigned To, Visit Date). Photos follow in the Photos column.');
  return { ticketId: id };
}

function ticketPhoto(d) {
  const c = auth(d);
  const t = ticketOf(c, d.ticketId);
  const have = clean(t['Photos']);
  if (have.split(/\s+/).filter(String).length >= 6) throw new Error('एक ticket में 6 photos तक।');
  const f = saveFile(c, clean(t['Ticket ID']) + ' photo ' + Utilities.formatDate(now(), TZ, 'HHmmss') + '.jpg', 'image/jpeg', d.data);
  setFields('Tickets', t._row, { 'Photos': (have ? have + '\n' : '') + f.getUrl(), 'Last Updated': now() });
  return {};
}

function ticketClose(d) {
  const c = auth(d);
  const t = ticketOf(c, d.ticketId);
  const rating = Math.max(1, Math.min(5, Math.round(num(d.rating)) || 5));
  setFields('Tickets', t._row, {
    'Status': 'Closed', 'Customer Rating': rating, 'Customer Feedback': clean(d.feedback, 500),
    'Closed On': now(), 'Last Updated': now(),
  });
  if (rating <= 2) {
    alertOffice(settings(), 'Low rating on ticket ' + clean(t['Ticket ID']),
      clean(t['Customer Name']) + ' (' + last10(t['Mobile']) + ') rated ' + rating + '/5.\nFeedback: ' + clean(d.feedback));
  }
  return {};
}

function ticketReopen(d) {
  const c = auth(d);
  const t = ticketOf(c, d.ticketId);
  const note = clean(d.note, 500);
  if (note.length < 3) throw new Error('क्या समस्या बची है, लिखें।');
  setFields('Tickets', t._row, {
    'Status': 'Reopened', 'Description': clean(t['Description']) + '\n[Reopened ' + Utilities.formatDate(now(), TZ, 'd MMM') + '] ' + note,
    'Last Updated': now(),
  });
  alertOffice(settings(), 'Ticket ' + clean(t['Ticket ID']) + ' reopened', clean(t['Customer Name']) + ' (' + last10(t['Mobile']) + '): ' + note);
  return {};
}

// ------------------------------------------------------------------ hourly sync

/**
 * Runs every hour (and from the AVIP menu):
 *  1. adds customers from Install Check once their order is confirmed,
 *  2. fills missing Customer IDs, referral codes and temporary PINs, and makes WhatsApp invite links,
 *  3. moves referrals forward (site visit → order → installed), locks the reward, marks payouts Due.
 */
function sync() {
  const s = settings();
  const ic = installCheck(s);
  const sv = siteVisits(s);
  const result = { added: 0, invites: 0, referrals: 0 };

  // 1. new customers from Install Check
  const known = {};
  rows('Customers').forEach(function (c) { known[last10(c['Mobile'])] = true; });
  ic.sites.forEach(function (x) {
    const m = last10(x['Mobile']) || last10(x['WhatsApp']);
    if (m.length !== 10 || known[m] || IC_ORDERED.indexOf(clean(x['Status'])) < 0) return;
    known[m] = true;
    const exec = clean(x['Sales Person']);
    append('Customers', {
      'Name': clean(x['Customer Name']), 'Mobile': "'" + m, 'Active': 'Yes', 'Address': clean(x['Address']),
      'District': clean(x['District']), 'System (kW)': num(x['Site Size (kW)']) || '', 'System Type': clean(x['System Type']),
      'Install Check Site ID': clean(x['Site ID']), 'Sales Visit No.': clean(x['Sales Visit No.']) ? "'" + clean(x['Sales Visit No.']) : '',
      'Sales Executive': exec, 'Sales Exec Mobile': ic.users[exec.toLowerCase()] ? "'" + ic.users[exec.toLowerCase()] : '',
      'Added On': now(),
    });
    result.added++;
  });

  // 2. IDs, codes, PINs, invite links
  const list = rows('Customers');
  let maxId = 0;
  const codes = {};
  list.forEach(function (c) {
    const id = clean(c['Customer ID']);
    if (/^C\d+$/.test(id)) maxId = Math.max(maxId, Number(id.slice(1)));
    if (clean(c['Referral Code'])) codes[clean(c['Referral Code']).toUpperCase()] = true;
  });
  list.forEach(function (c) {
    if (!clean(c['Name']) && !last10(c['Mobile'])) return;
    const upd = {};
    if (!clean(c['Customer ID'])) { maxId++; upd['Customer ID'] = 'C' + ('000' + maxId).slice(-4); c['Customer ID'] = upd['Customer ID']; }
    if (!clean(c['Active'])) upd['Active'] = 'Yes';
    if (!clean(c['Referral Code'])) { const code = newCode(codes); codes[code] = true; upd['Referral Code'] = code; }
    if (!clean(c['Install Check Site ID'])) {
      const site = ic.byMobile[last10(c['Mobile'])];
      if (site) upd['Install Check Site ID'] = clean(site['Site ID']);
    }
    let pin = clean(c['PIN']);
    const first = customersByMobile(c['Mobile'], list)[0];
    const isFirst = first && first._row === c._row;
    if (isFirst && !pin && last10(c['Mobile']).length === 10) { pin = randomPin(); upd['PIN'] = "'" + pin; }
    if (isFirst && pin && pin.charAt(0) !== '#') {
      const link = inviteLink(c, pin, s);
      if (link !== clean(c['Invite Link'])) { upd['Invite Link'] = link; result.invites++; }
    }
    if (Object.keys(upd).length) setFields('Customers', c._row, upd);
  });

  // 3. referrals
  const sl = slabs();
  const byCode = {};
  rows('Customers').forEach(function (c) { if (clean(c['Referral Code'])) byCode[clean(c['Referral Code']).toUpperCase()] = c; });
  const custByMobile = groupBy(rows('Customers').map(function (c) { c._m = last10(c['Mobile']); return c; }), '_m');
  rows('Referrals').forEach(function (r) {
    const upd = {};
    const m = last10(r['Friend Mobile']);
    const code = clean(r['Referrer Code']).toUpperCase();
    if (!clean(r['Referrer ID']) && code && byCode[code]) {
      const ref = byCode[code];
      upd['Referrer ID'] = clean(ref['Customer ID']);
      upd['Referrer Name'] = clean(ref['Name']);
      upd['Referrer Mobile'] = "'" + last10(ref['Mobile']);
      if (!clean(r['UPI ID'])) upd['UPI ID'] = clean(ref['UPI ID']);
    }
    if (!clean(r['Referral ID'])) upd['Referral ID'] = 'R' + Utilities.formatDate(now(), TZ, 'yyMMdd') + '-M' + r._row;
    if (!clean(r['Received'])) upd['Received'] = now();

    const curStatus = clean(r['Status']) || REF_STATUS[0];
    let rank = REF_STATUS.indexOf(curStatus);
    let kw = 0;
    let exec = '';
    const visit = m ? sv.byMobile[m] : null;
    if (visit) {
      rank = Math.max(rank, 2);
      kw = num(visit['kWp']) || kw;
      exec = clean(visit['Sales Executive']);
      if (clean(visit['Lead Status']) === 'Converted') rank = Math.max(rank, 3);
    }
    const site = m ? ic.byMobile[m] : null;
    if (site) {
      const st = clean(site['Status']);
      if (IC_ORDERED.indexOf(st) >= 0) { rank = Math.max(rank, 3); kw = num(site['Site Size (kW)']) || kw; }
      if (IC_INSTALLED.indexOf(st) >= 0) rank = Math.max(rank, 4);
      exec = exec || clean(site['Sales Person']);
    }
    (custByMobile[m] || []).forEach(function (c) {
      rank = Math.max(rank, 3);
      kw = num(c['System (kW)']) || kw;
      if (day(c['Installation Date'])) rank = Math.max(rank, 4);
    });
    const newStatus = curStatus === NOT_INTERESTED && rank < 3 ? NOT_INTERESTED : REF_STATUS[Math.max(0, rank)];
    if (newStatus !== curStatus) upd['Status'] = newStatus;
    if (exec && !clean(r['Sales Executive'])) upd['Sales Executive'] = exec;
    const locked = clean(r['Reward (₹)']) !== '';
    if (!locked && kw > 0 && num(r['System (kW)']) !== kw) upd['System (kW)'] = kw;
    if (!locked && rank >= 3 && kw > 0) {
      const w = reward(kw, sl);
      upd['Reward (₹)'] = w.amount;
      upd['Slab Working'] = w.working;
      upd['Reward Locked On'] = now();
    }
    const amount = locked ? num(r['Reward (₹)']) : num(upd['Reward (₹)']);
    if (rank >= 4 && amount > 0 && !clean(r['Payout Status'])) upd['Payout Status'] = 'Due';
    if (Object.keys(upd).length) { setFields('Referrals', r._row, upd); result.referrals++; }
  });
  return result;
}

function randomPin() {
  for (;;) {
    const p = String(1000 + Math.floor(Math.random() * 9000));
    if (WEAK_PINS.indexOf(p) < 0) return p;
  }
}

/** Short code like AVIP-K7Q2 (no 0/O/1/I to avoid mix-ups). */
function newCode(used) {
  const abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  for (;;) {
    let s = 'AVIP-';
    for (let i = 0; i < 4; i++) s += abc.charAt(Math.floor(Math.random() * abc.length));
    if (!used[s]) return s;
  }
}

function inviteLink(c, pin, s) {
  const m = last10(c['Mobile']);
  const name = clean(c['Name']).split(' ')[0] || '';
  const msg = 'नमस्ते ' + name + ' जी 🙏\n\n' +
    'AVIP Solar App पर अपने solar का पूरा हाल देखें — installation progress, documents, ' +
    'Refer & Earn (हर kW पर ' + money((slabs()[0] || { rate: 1000 }).rate) + ' तक) और complaint/ticket।\n\n' +
    'App download करें: ' + (clean(s['app_link']) || DEFAULT_APP_LINK) + '\n\n' +
    'Login\nMobile: ' + m + '\nPIN: ' + pin + '\n(पहली बार login पर अपना नया PIN बनाएँ)\n\n' +
    '— AVIP Solar, ' + (clean(s['support_phone']) || '92881 76289');
  return 'https://wa.me/91' + m + '?text=' + encodeURIComponent(msg);
}
