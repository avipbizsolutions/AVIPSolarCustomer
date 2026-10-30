# AVIP Solar — customer app

Android app for **AVIP Solar**'s retail customers (AVIP Business Solutions LLP). The customer
sees the progress of their installation, opens their documents, earns by referring friends and
relatives, and raises a ticket for any problem. The UI is Hindi + English.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPSolarCustomer/releases/latest/download/AVIP-Solar.apk

## What the customer sees
| Tab | What it does |
|---|---|
| **Home** | Progress of their system in 8 stages: Site survey → Order confirm → PM Surya Ghar registration & DISCOM approval → Material dispatch → Installation complete → Net meter application → Inspection & commissioning → Subsidy credited. Dates, what is happening now, and **"आपका काम"** (something the customer must do, e.g. upload the electricity bill) with an upload button. Serial numbers of panels / inverter after dispatch. Their sales executive and the AVIP office with Call / WhatsApp. |
| **Documents** | Quotation (from AVIP Solar Sales), Installation Report (from AVIP Solar Installation) and anything the office adds (warranty cards, invoice, commissioning report). The customer can send photos of documents too. |
| **Refer & Earn** | The reward slabs, their own referral code, "share on WhatsApp", a form to fill a friend's details, every referral with its status and reward, total paid and coming, and their UPI ID. |
| **Support** | Raise a ticket (type of problem, details, inverter error code, up to 6 photos), see its status and visit date, confirm it is fixed with a 1–5 star rating, or reopen it. |

A background check every few hours shows a phone notification when a stage is done, a ticket
visit is fixed, a referral moves forward or a reward is paid.

## Where the data comes from
Everything lives in one private Google Sheet, **AVIP Solar Customer App – Database**. Its script
also **reads** (never changes) two existing sheets, so the office does not type anything twice:

| From | Used for |
|---|---|
| **AVIP Solar Install Check – Database** (AVIP Solar Installation app) | New customers (once a site is *Approved for Dispatch*), survey / order / dispatch / installation dates, serial numbers, GPS photo count, Installation Report PDF, the sales executive's number, referral status (order confirmed, installed) |
| **AVIP Site Visits Log** (AVIP Solar Sales) | Quotation PDF, survey date, referral status (site visit done, converted) and the friend's kW |

### Tabs of the customer sheet
| Tab | Who fills it | What it holds |
|---|---|---|
| **Customers** | Script + office | One row per installation. Office types the dates the other sheets do not know: *Portal & DISCOM Approval Date*, *Net Meter Applied Date*, *Commissioning Date*, *Subsidy Credited Date* (any date column can also be typed to override). **Customer Action** = what the customer must do now (shown in the app; clear it when done). **Note to Customer** = any message. |
| **Referrals** | App + script + office | Every referral. The script moves *Status* forward and, once the order is confirmed, **locks the reward** (*Reward (₹)* + *Slab Working*). After installation *Payout Status* becomes **Due**; after paying, set it to **Paid** and fill *UTR / Ref No.* and *Paid On*. *Office Note* is never shown to the customer. |
| **Referral Slabs** | Office | Reward per kW, slab-wise. Today: 0–5 kW ₹1,000 · 5–10 kW ₹800 · 10–40 kW ₹500 · 40+ kW ₹400 (blank *To kW* = no limit). An 8 kW referral = 5 × 1,000 + 3 × 800 = ₹7,400. Changing the slabs affects only referrals not locked yet. |
| **Tickets** | App + office | Set *Status* (Open → Assigned → Visit Scheduled → Resolved), *Assigned To*, *Visit Date*, *Resolution*. The customer confirms with a rating (→ Closed) or reopens it. Photos are Drive links. |
| **Documents** | Office + app | *Customer ID*, *Title*, *Drive Link* — appears in the customer's Documents tab. The file must be in Drive of the account that runs the script (or shared with it). Customer uploads land here too (*Added By* = Customer). |
| **Settings** | Office | Sheet IDs, alert e-mail, support numbers, home-screen announcement, referral terms. |

## Login and PINs
Mobile number + 4-digit PIN. The **sync** gives every new customer a random temporary PIN and
makes an **Invite Link** (WhatsApp message with the download link, mobile and PIN): click it
and press send. At first login the customer must choose their own PIN; the sheet then keeps only
a scrambled code (starting with `#`) and the invite link is cleared.

*Forgot PIN* in the app e-mails the office and stamps *PIN Reset Requested*. Type a new 4-digit
PIN in that customer's PIN cell, run **AVIP → Sync now**, and send the new Invite Link.
Five wrong PINs lock that number for 10 minutes. Set *Active* = **No** to block a customer.

If one customer has two installations, give each its own row with the same mobile number:
the app shows both, and the PIN is kept on the first row.

## Past customers (one-time)
Paste them into **Customers**: *Name*, *Mobile*, *Address*, *District*, *System (kW)*,
*System Type*, and whatever dates you know (at least *Installation Date*). Leave *Customer ID*,
*PIN* and *Referral Code* blank, then **AVIP → Sync now**. A stage counts as done when it has a
date or any later stage has one, so a customer with only a *Subsidy Credited Date* shows
everything done. Then send each customer their Invite Link (or a broadcast with the app link,
and the PINs one by one).

## One-time setup (office)
1. Create a Google Sheet **AVIP Solar Customer App – Database** in the office Google account
   (the same account that can open the Install Check sheet and the Site Visits Log).
2. Extensions → Apps Script → paste `apps-script/Code.gs` → Save.
3. Run **setup** once and allow access (Sheets, Drive, e-mail, triggers). It makes the tabs,
   the dropdowns, the *AVIP Customer App Files* Drive folder, the hourly sync and the **AVIP** menu.
4. **Settings** tab: paste the ID of the Install Check sheet and of the Site Visits Log (the long
   code between `/d/` and `/edit` in each sheet's link) and the office alert e-mail.
   If those sheets belong to another Google account, share them (Viewer) with this account,
   and share their Drive folders too (*AVIP Install Check Photos*, *AVIP Site Visits*) so
   Quotation and Installation Report PDFs open in the app.
5. Deploy → New deployment → type **Web app** → Execute as **Me**, Who has access **Anyone** → Deploy.
6. Copy the `/exec` URL into `config.json` in this repo (`"endpoint": "…"`). Phones read it on
   every launch.
7. **AVIP → Sync now** — Install Check customers appear with their invite links.

After changing `Code.gs`: Deploy → Manage deployments → Edit → Version: **New version** → Deploy
(the URL stays the same). Run **setup** again whenever a new version adds columns; it only adds
what is missing.

## Updates are compulsory
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release. On launch the app checks the latest release; a newer version is downloaded and
installed from the start screen, and the old version does not open. Keep `app/release.keystore`
in the repo: every update must be signed with it.
(For the Google Play Store later, the same code can be uploaded; Play then handles updates.)

## Code map
| File | What it does |
|---|---|
| `apps-script/Code.gs` | Backend on the Google Sheet: login/PIN, progress merged from Install Check + Site Visits Log, documents, uploads, referrals with slab rewards, tickets, hourly sync, AVIP menu |
| `Api.kt` | Calls the web app; session |
| `Data.kt` | What the server sends (systems, stages, referrals, tickets), saved copy for offline, what changed |
| `SplashActivity.kt`, `Updater.kt`, `UpdatedReceiver.kt` | Compulsory update (same as the other AVIP apps) |
| `LoginActivity.kt` | Login, first-login PIN, forgot PIN |
| `MainActivity.kt` | Home (progress), Documents, Refer & Earn, Support tabs |
| `ReferActivity.kt` | Friend's details form |
| `TicketActivity.kt` | New ticket with photos |
| `Files.kt` | Camera / gallery photos, opening PDFs |
| `Watcher.kt` | Background check and notifications |
