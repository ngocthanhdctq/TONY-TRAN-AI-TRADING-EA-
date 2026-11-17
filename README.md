# V2.0.0 – AI Outcome Learning
- Tracks scanner-qualified funding opportunities regardless of the rule engine's accept/reject decision.
- Resolves each observation at the first successful market scan at/after settlement using the current mark price.
- Estimates hypothetical net outcome with directional mark-price PnL, simulated funding, estimated taker fees, spread and slippage reserve.
- Displays accepted/rejected cohort performance, positive outcome rate, average absolute forecast error and total simulated net.
- Threshold recommendation requires at least 50 resolved opportunities and minimum 15 samples in a score band; it is advisory only and never changes AUTO settings.
- This is counterfactual paper research, not actual execution/fill or Binance funding-income data. It can miss an outcome if the app is not scanning after settlement; the first post-settlement mark can differ materially from the settlement mark.



## V2.1.0 – AI Pattern & Risk Research
- Adds local historical opportunity cohorts by funding magnitude, spread and 24h volatility.
- Stores directional price PnL and price-move percentage separately from estimated funding/net outcome.
- Displays an empirical adverse-tail indicator (10th percentile of directional price PnL percentage) only after at least 20 resolved samples.
- Adds chronological 70/30 train/holdout threshold research; requires at least 10 training observations and 15 holdout observations for a displayed holdout result.
- All insights are sample-size gated and advisory only; no automatic threshold changes and no live orders.
- Results remain counterfactual PAPER estimates using the first available mark scan after settlement, estimated fees, spread and slippage. They are not actual fills or confirmed Binance funding income.


## V2.2.0 – Safety Core & Controlled Self-Learning
- Adds a central Safety Core used directly by the PAPER ledger before every Spot/Futures entry.
- Hard guards: Spot order cap 5 USDT, Futures margin cap 2 USDT/order, leverage cap 10x, maximum 3 open positions.
- No martingale, no automatic capital increase, no live order execution.
- Adds local audit logging and a learning-proposal lifecycle model (draft, backtest, walk-forward, pending approval, approved/rejected/rollback).
- Strategy promotion remains advisory: validation must pass and user approval is required before any active strategy change.
- Existing exchange connectivity remains public market data only. No API key/secret is required or stored.
- Build status: source updated; Android compilation and APK packaging still require an Android SDK/Gradle build environment and have not been verified here.


## V2.3.0 – Dual AI Workspace & Approval Gate
- Reorganizes the main navigation into two primary workspaces: AI TRADING and AI SELF-LEARNING.
- Funding Hunter remains a strategy/tool inside AI TRADING, not a third AI workspace.
- Keeps scanner, paper trade, history, news, fusion, backtest and settings as sections within AI TRADING.
- Removes automatic strategy promotion from the optimizer path: a passing candidate is recorded as PENDING_APPROVAL and is not written to the active strategy configuration.
- Adds Safety Core validation and local audit entry when a strategy promotion proposal is created or blocked.
- AI SELF-LEARNING remains research/validation focused; its model output is advisory.
- PAPER-only; no authenticated Binance account, API secret, or live order execution.
- Build status: source updated and archive integrity checked; Android build/APK not compiled in this environment.


## V2.4.0 – Trading Event Center
- Adds an on-device event journal with a 500-event rolling history and category filters.
- Records PAPER order opens/closes, risk alerts, AI non-WAIT signals, large 24h market moves, Funding Hunter status and user-cleared journal actions.
- Adds timestamps, symbol, trade ID, source, status and optional internal processing latency field.
- Adds deduplication cooldowns to reduce repeated market/risk notifications.
- Explicitly labels all orders as PAPER; does not claim Binance order submission, exchange acceptance, real fills or exchange execution latency.
- Event history is stored locally in SharedPreferences; no cloud synchronization.
- Build status: source archive integrity checked; Android compilation and APK generation have not been performed in this environment.


## V2.5.0 — Mobile UI refinement
- Reworked the trading workspace section navigation into a horizontally scrollable chip row to avoid narrow-screen overflow.
- Added clear selected-state styling for the active workspace section.
- Added a persistent PAPER / no-real-money status indicator in the main workspace.
- Styled the app status message as a readable status panel.
- Clarified the AI SELF-LEARNING boundary: it proposes changes and does not silently replace trading rules.
- Kept the two primary workspaces: AI TRADING and AI SELF-LEARNING.
- No live Binance order execution or API-secret storage was introduced.

Build status: source update only. This environment does not have a verified Android SDK/Gradle build setup, so no APK compilation or device test is claimed.


## V2.6.0 — System Diagnostics
- Added a local diagnostics screen under AI TRADING.
- Added repeatable checks for Spot minimum/maximum, Futures margin/leverage caps, and the three-position limit.
- Added invalid numeric input and invalid price checks.
- Added PAPER Spot open/close ledger lifecycle checks and Futures 10x configuration check.
- Results are displayed per test with pass/fail status.
- Diagnostics never submit exchange orders and do not require Binance credentials.
- Explicitly lists what is not covered: network reliability, exchange latency, device performance, and Android APK build.

Build status: source update only. Android compilation and device execution have not been verified in this environment.


## V2.7.0 — Persistent PAPER Ledger
- Added app-private local persistence for the PAPER wallet and trade ledger using SharedPreferences.
- Saves the wallet, open/closed trades, IDs, fills, fees, realized PnL, and notes after successful opens/closes and resets.
- Restores the PAPER state on app launch and displays a recovery notice with open/closed trade counts.
- Keeps a bounded history of up to 500 closed trades in the engine and up to 1,000 serialized records in storage; open positions are retained.
- Validates restored numeric fields and enum values; malformed snapshots are ignored rather than partially trusted.
- No Binance API credentials, account balances, or live orders are stored or accessed.
- Important: local PAPER positions are not exchange positions. Restoring them does not mean they were monitored while the app was closed; refresh market prices and review every open simulated position after restart.

Build status: source update only. Android compilation, persistence instrumentation tests, and Samsung device testing have not been verified in this environment.


## V2.8.0 — PAPER Performance Analytics
- Added realized PnL, estimated unrealized PnL, estimated equity, win/loss counts, win rate, gross profit/loss, profit factor, and maximum drawdown estimates.
- Added Spot/Futures market-level closed-trade breakdown.
- Added close timestamps to new closed trades and persisted them for chronological drawdown calculations.
- Dashboard reports when open positions lack a current ticker; those positions are not silently assigned a stale or invented price.
- Equity and unrealized PnL use the latest available public ticker and fee assumptions (Spot 0.10%, Futures 0.05% per side).
- Drawdown is an estimate from the initial 10 USDT paper capital, realized close sequence, and current marked equity; it is not tick-level or exchange-account drawdown.
- No strategy-level attribution is claimed because existing historical trade records do not carry reliable strategy IDs.
- No live orders or Binance private credentials are used.

Build status: source update only. Android compilation and device tests have not been verified in this environment.


## V2.9.0 — Security & Integration Hardening
- Adds Android Keystore-backed AES-256-GCM authenticated encryption for the local PAPER wallet/trade snapshot.
- Stores only ciphertext and the GCM IV in app-private preferences; the AES key is generated and held by Android Keystore.
- Performs a one-time migration of a valid V2.7/V2.8 plaintext PAPER snapshot into encrypted storage, then removes the old plaintext preference.
- If encrypted data fails authentication/decryption, the app does not silently fall back to an old plaintext snapshot.
- Disables Android app backup to reduce the chance of local trading state being copied through device backup.
- Keeps the app PAPER-only: no API key, API secret, account access, or live exchange orders are introduced.
- Security scope: this protects the local PAPER ledger at rest; it is not a full app security audit and does not protect against a compromised/rooted device, runtime instrumentation, screenshots, or a user exporting data.
- Migration/build/device tests still need to be run on a real Android build environment. Do not treat the source archive as a compiled or signed APK.


## V3.0.0 — Full System Integration Testing
- Hardens the PAPER execution engine against NaN and infinite entry/exit prices.
- Rejects malformed trading symbols before modifying the PAPER wallet.
- Prevents invalid close prices and verifies that a closed position cannot be closed a second time.
- Expands the in-app System Diagnostics suite with invalid-price, malformed-symbol, duplicate-close and in-memory ledger snapshot restoration checks.
- Keeps all checks local and simulated; diagnostics do not send orders to Binance.
- Existing Android Keystore AES-GCM protection for the PAPER ledger remains enabled.
- No authenticated exchange access, API credentials, or live order execution is introduced.
- Test scope remains limited: these are deterministic in-app checks, not exchange integration tests, Android instrumentation tests, or proof of APK compilation.
- Build status: source archive only; Android SDK/Gradle compilation and Samsung Galaxy A56 runtime testing have not been performed in this environment.


## V3.1.0 – Binance Read-only Account Integration
- Adds an optional Binance account connection screen, separate from public market data and the PAPER ledger.
- Uses signed read-only requests for Spot account balances, USDⓈ-M Futures account balances, and open Futures position risk data.
- API Key and Secret are encrypted locally with AES-GCM using a non-exportable Android Keystore key; credentials are never printed to logs or sent to ChatGPT.
- The module contains account-read methods only. It does not include order placement, cancellation, transfer, or withdrawal methods.
- Displays non-zero Spot balances, Futures wallet/unrealized PnL balances, open Futures positions, leverage and isolated/cross mode.
- Displays PAPER wallet separately; it never imports live funds into PAPER and does not infer a combined USDT total across different assets.
- Requires a Binance API key with read permissions for Spot and USDⓈ-M Futures. Trading and withdrawal permissions must remain disabled. IP restrictions are recommended when practical.
- A successful connection requires credentials entered on the device and testing against the user's Binance account. No credentials are bundled in the source.
- Build status: source updated and archive integrity checked. Android compilation, API-device integration, and APK packaging have not been performed in this environment.


## V3.2.0 – Local Binance Account Advisor
- Adds a transparent on-device rule-based assessment for the authenticated read-only Binance snapshot.
- Reviews open Futures positions for high leverage, Cross margin, negative unrealized PnL, and conflict with the app's current technical signal.
- Shows a bounded internal risk indicator and plain-language WAIT / review / reduce-risk guidance.
- Does not send orders, close positions, modify leverage, set stops, or change account settings.
- Risk score is an internal heuristic only; it is not Binance margin ratio, liquidation price, probability of loss, or a guarantee of safety.
- If the account snapshot or market signal is missing, the advisor does not fabricate data; lack of a signal remains WAIT.
- Spot balances are displayed as asset quantities and are not summed into USDT without reliable conversion prices.
- Build status: source-level integration and archive integrity checked. Android compilation, device tests, and authenticated Binance API tests remain outstanding.


## V3.3.0 – AI Market Intelligence
- Adds a market-wide analysis workspace using public Binance ticker data, the existing 1h technical trend, ATR%/volume metrics, and the latest available Futures funding scan.
- Adds BTC 24h context, market breadth, high-volatility count, funding coverage, per-symbol score, BUY/SELL/WAIT recommendation, and plain-language reasons.
- Uses conservative vetoes: WAIT on missing base technical signal, high ATR%, weak liquidity, or material BTC/technical disagreement.
- Scores are transparent heuristic points, not calibrated probabilities, a trained AI model, or a promise of profitability.
- Funding data can be stale or missing for a symbol; UI discloses this and does not fabricate values.
- Public market intelligence is advisory and does not submit or modify exchange orders. PAPER remains separate.
- Build status: source-level checks and ZIP integrity checked. Android compilation, on-device rendering, and live API/device validation remain outstanding.


## V3.4.0 — AI Self-Learning Signal Outcomes
- Captures the final AI Market Intelligence LONG/SHORT/WAIT decision with symbol, score, reference price, market regime, ATR% and BTC 24h context. Missing Funding or veto conditions are recorded as WAIT.
- Tracks four independent horizons: 5m, 15m, 1h and 4h. Each horizon is evaluated separately.
- Directional labels: CORRECT at >= +0.15% directional move; INCORRECT at <= -0.15%; otherwise FLAT. Accuracy excludes FLAT and WAIT.
- WAIT is not scored as a directional win/loss. It is labeled AVOIDED_MOVE when absolute move reaches >=0.50%; otherwise FLAT.
- AI SELF-LEARNING screen displays horizon summaries and outcome counts by market regime.
- Keeps the existing on-device learning preference namespace for backward-compatible loading; old 1h records remain readable.
- Stores up to 1,200 signal-horizon observations. The store is app-private preferences, not encrypted with the PAPER wallet Keystore layer.
- Outcomes are sampled at the first fresh price update at or after each target horizon. If the app is stopped or offline, no historical tick reconstruction occurs; evaluation may be late or remain pending.
- This is forward observation, not exchange execution, PnL, or calibrated probability. No strategy parameters are automatically changed. Existing backtest/walk-forward approval flow remains separate.
- Android SDK/Gradle compilation and device tests were not available in the build environment; only source-level checks and archive integrity are performed.


## V3.5.0 — AI Strategy Optimizer with Learning Feedback
- Strategy optimizer now consumes completed directional outcomes from the AI Self-Learning signal tracker.
- When at least 20 scored directional observations exist, low observed accuracy biases candidate generation toward stricter volume/RSI filters; stronger observed accuracy adds a modest less-restrictive candidate. These are heuristic search hints, not a trained model.
- Evaluates the baseline and candidate parameter sets over three chronological holdout folds; displays the top three candidates and their return/drawdown/trade/fold summaries.
- Candidate qualification requires at least 15 trades, at least 3 trades in each fold, at least 2 profitable folds, drawdown within policy, at least +0.25 percentage-point average-return improvement, and improvement over baseline in at least 2 folds.
- A passing candidate creates a pending approval proposal only. It does not replace active settings or submit live orders.
- Optimizer's aggregate BacktestMetrics adapter now exposes fold summaries to the existing SafetyCore approval gate.
- Scope remains the current LONG-only backtest engine; SHORT optimization and realistic spread/slippage/funding/partial-fill simulation are not implemented.

### Build a debug APK
This working environment did not contain Gradle, Gradle Wrapper, Android SDK or `android.jar`, so an APK could not be compiled or device-tested here. A GitHub Actions workflow is included at `.github/workflows/android-debug-apk.yml`. Push the source to a GitHub repository (main/master) or run the workflow manually from Actions; download the `tony-tran-ai-trading-ea-v3.5.0-debug` artifact if the build succeeds. The APK is a debug/test build, not a Play Store release.
