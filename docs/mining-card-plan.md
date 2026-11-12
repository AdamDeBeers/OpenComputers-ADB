# Mining Card - Implementation Plan

Status: plan final for step 1, nothing implemented yet. Branch: `feature/mining-card`.

Delivery in two steps:

- **Step 1 (this branch):** Mining Card T1-T3 without signatures. The card chooses the
  start nonce itself (see section 6), so the API does not change in step 2. Works with the
  existing chain, no reset.
- **Step 2 (later):** card-signed blocks (option 9b) plus signature check in the pool,
  then a chain reset. The only API change is an extra return value from `mine()`.

Goal: a new OpenComputers component "Mining Card" (tiers 1-3) for the in-game
SHA-256 blockchain ForgeCoin, running on our NeoForge 1.21.1 server. Protecting
server TPS is a hard requirement.

Measurements from in-game:

- Pure Lua SHA-256 (`sha256_lib.lua`): ~3,600 H/s, but every hash runs in the Lua VM.
- Data Card `data.sha256()`: ~157 H/s, limited by the direct call budget, not by hashing.

---

## 1. ForgeCoin hash format (must stay compatible)

Source: `ADB-Create-Homelab-Pack/oc-scripts/mining/lib/`.

### What gets hashed

`blockchain_lib.lua`, `calculateHash`:

```lua
sha256.hex(table.concat({
  b.index, b.previousHash, b.timestamp, b.miner,
  b.reward, b.data, b.difficulty, b.nonce
}, "|"))
```

- The input is a **text string**: eight fields joined with `|`, with no trailing separator.
- Example: `5|<64 hex chars>|1762900000|Steve|5000000000|test block 1|8|12345`
- The nonce is the **last field**, written as a **decimal integer**. `table.concat` on a
  Lua 5.3 integer gives `%d` formatting (`12345`, never `12345.0`), which is the same as Java
  `Long.toString`.
- `isValidBlockStructure` requires every numeric field, including the nonce, to be a Lua
  **integer** (`math.type(v) == "integer"`). `miner` and `data` must not contain `|`.
- The hash is a **single** SHA-256, returned as a **64-character lowercase hex string**,
  and stored as `block.hash`.
- `mineBlock` starts at nonce 0 and counts up by 1. The nonce is a Lua 5.3 integer (64 bit),
  so any non-negative value up to 2^63-1 is valid.

### How difficulty is checked

`mining_lib.lua`, `hashMatchesDifficulty(hash, bits)`:

- Difficulty is the number of **leading zero bits** of the hash, checked on the hex string:
  `bits // 4` leading `'0'` characters, then the next nibble must be `< 2^(4 - bits % 4)`.
- This is exactly "the first `bits` bits of the 32-byte digest are zero", so the card can
  check the raw digest bytes without converting to hex.
- `GENESIS_DIFFICULTY` 8, `MIN` 1, `MAX` 60. It is re-adjusted every 10 blocks, aiming for 60 s per block.

### Consequences for the card

- No binary header and no target value. The card hashes `prefix .. tostring(nonce)` and
  compares leading zero bits.
- `prefix` is everything before the nonce:
  `table.concat({index, previousHash, timestamp, miner, reward, data, difficulty}, "|") .. "|"`
- The card must read the prefix as **raw bytes** (`args.checkByteArray`), not as a decoded
  string. Otherwise a non-ASCII miner name or data field would hash differently than in Lua.
- The card chooses the start nonce itself, as a random value in `[0, 2^62)`. That
  leaves plenty of headroom below 2^63-1 and keeps every nonce non-negative.
- The card returns the nonce as `java.lang.Long`. `ExtendedLuaState` pushes `Long` with
  `pushInteger`, so `math.type(nonce) == "integer"` and `isValidBlockStructure` accepts it.
- The card returns the hash as lowercase hex, so it can be stored directly in `block.hash`.

A block mined by the card therefore passes `isNewBlockValid` and `isChainValid` with no
changes to the validation code.

### Lua-side integration (additive only)

`mining.mineBlock(block, hashFn, shouldAbort)` calls `hashFn` once per nonce, so the card
cannot work through it efficiently. The plan is a **new, additive** helper, for example
`mining.mineBlockWithCard(block, card, shouldAbort)` or a separate `card_miner.lua`. It:

1. builds `prefix` from the block fields, exactly as `calculateHash` does, minus the nonce,
2. loops `card.mine(prefix, maxPerCall, block.difficulty)` and sums `hashesDone` into `attempts`
   (no nonce tracking on the Lua side, the card picks a fresh random start every call),
3. on success sets `block.nonce` and `block.hash` and returns `block, elapsed, attempts`, the
   same return values as `mineBlock`,
4. calls `shouldAbort()` between calls, and `os.sleep(0.05)` when `hashesDone == 0` (the credit is used up).
5. rebuilds `prefix` when the block changes (new timestamp, new chain tip); nothing else to reset.

`blockchain.generateNextBlock` then validates the result with the existing
`isNewBlockValid` call. Nothing existing has to change. To use the card, a caller only
needs a way to choose the helper. That's a one-line option in `generateNextBlock`, or a
separate function next to it.

---

## 2. How the Data Card works in this fork (reference)

| Part | Location |
|---|---|
| Item class | `common/item/DataCard.scala`. `ComponentItem` + `ItemTier`. The lang key adds the 0-based tier (`item.oc.datacard0-2`). |
| Item registration | `common/init/OCItems.scala:450-454`, through `registerItem`. The creative tab is filled from the registry. |
| Item IDs | `Constants.scala:106-108`: `datacard1-3` |
| Driver | `integration/opencomputers/DriverDataCard.scala`. Tier selects `component.DataCard.TierN`, returns null on the client, slot `Card`, plus a `Provider` object. |
| Driver registration | `ModOpenComputers.scala:124` and `:180` |
| Environment | `server/component/DataCard.scala`. `AbstractManagedEnvironment`, node `Neighbors`, component `"data"`, `withConnector()`. Tiers are subclasses. |
| Callbacks | All `direct = true`, each with its own `limit` (`sha256` 4). |
| Energy | `node.tryChangeBuffer(-cost)` before doing any work. Throws "not enough energy" if it fails. |
| Recipes and models | `src/data/java/.../OCRecipeProvider.java:763`, `OCItemModelProvider.java:113`, generated into `src/main/generatedResources` |
| Assets | `textures/item/datacard{1,2,3}.png` (+ `.mcmeta`), `tags/item/datacard{1,2,3}.json`, `doc/*/item/datacard{1,2,3}.md` |

---

## 3. Call budget and where the work runs

- `Machine.scala:145-148`: `maxCallBudget` is the average of the CPU and memory call budgets
  (`computer.callBudgets: [0.5, 1.0, 1.5, 2.0]`). It resets every tick (`Machine.scala:556`).
- Each direct call costs `1.0 / limit` (`Machine.scala:413`). For `sha256` that's
  4 calls per 1.0 of budget, so about 8 calls per tick, which gives about 157 H/s as measured.
- When the budget is used up, `LimitReachedException` makes `machine.lua:1087-1099` retry the
  call as a **synchronized call on the server main thread**. Synchronized calls ignore the
  budget (`inSynchronizedCall`, `Machine.scala:310`, `:618-625`).
- Direct calls run on the computer executor threads (`computer.threads: 4`).

Rules for the card:

1. `mine()` is `direct = true`, so hashing normally runs on an executor thread.
2. The card enforces its **own** hash budget in Java. It does not rely on `limit` alone.
3. If `mine()` sees that it is running on the server thread (the fallback case), it returns
   `nil, nil, 0` immediately and hashes nothing. The script then waits until the next tick.
   No hashing ever happens on the main thread.

---

## 4. Tiers: deliberately slow

### Decision

Tier 3 runs at **150 H/s** at full speed, which is 7.5 hashes per tick, and the speed is configurable.
T1 and T2 keep the earlier 1 : 2.5 : 5 ratio between tiers. Starting values, tuned after in-game tests.

| Tier | H/s | Hashes/tick (average) | vs. Lua (3,600 H/s) | vs. Data Card (~157 H/s) |
|---|---|---|---|---|
| 1 | 30 | 1.5 | ~1/120 | ~1/5 |
| 2 | 75 | 3.75 | ~1/48 | ~1/2 |
| 3 | 150 | 7.5 | ~1/24 | about equal |

### Conflict with the "card must beat Lua" goal

At these speeds the card is **slower than a pure-Lua miner** (3,600 H/s) and about as fast
as the existing Data Card loop (157 H/s). Nobody has a speed reason to switch to the card. The
server-wide cap would only throttle the miners that are already harmless, while a Lua miner
keeps one executor core busy.

So the speed limit only protects TPS if Lua mining and Data Card mining **are not accepted**
by the chain. In practice, that turns option 9b (card-signed blocks) from optional into a
requirement:

- **Without 9b:** players mine in Lua at 24x the speed. The card is cosmetic, and TPS is not protected.
- **With 9b:** the card is the only valid way to mine. Total network speed is
  `cards × 150 H/s`, which is fully predictable, and server CPU cost becomes negligible.
  Lua mining is still possible but useless, because unsigned blocks are rejected.

Difficulty follows on its own. At 150 H/s a Tier 3 card needs about 1.7 s for the genesis
difficulty (8 bits = 256 hashes). `getDifficulty` aims for 60 s per block and adjusts by
±1 bit every 10 blocks. For example, 10 T3 cards (1,500 H/s) settle at about 16 bits.

### Budget per card: fractional hashes per tick

7.5 hashes per tick isn't a whole number, so the budget is a **token bucket** instead of a
per-tick counter:

- Every tick the card earns `hashesPerSecond / 20` credit (`AtomicLong` in milli-hashes,
  topped up lazily by comparing with `getGameTime`).
- The credit is capped at 1 second's worth (`hashesPerSecond`), so an idle card can't save up a burst.
- `mine()` takes `floor(credit)` hashes, limited by `count` and `maxPerCall`.
- One call per tick, or one every few ticks, reaches full speed. The script calls,
  gets `hashesDone == 0` once the credit is used up, does `os.sleep(0.05)`, and repeats.

### Server CPU cost

At about 1 µs per hash in Java (estimate), a T3 card uses about **0.15 ms of CPU per second**.
Even 100 T3 cards are about 15 ms per second, spread over the executor threads. CPU stops being
the bottleneck. Per-call overhead (the direct call itself, building the result) is now larger
than the hashing, which is why `mine()` should still do a whole batch per call.

### Server-wide cap

- It stays as a safety net, first-come, reset in `EventHandler.onServerTickPre`.
- Initial value: `30,000` hashes per second (200 T3 cards). Tune it with `/forge tps`
  (overall and per dimension) if it ever matters.
- At these speeds the cap protects against unexpected numbers of cards, not against normal play.

---

## 5. Energy: mining must hurt

### Conversion in this fork

`application.conf`, `power.value`: `ForgeEnergy: 100.0` against an internal value of 1000,
so `Settings.ratioForgeEnergy = 0.1`. Blocks take in FE through
`integration/util/Power.fromRF` (`EventHandlerNeoForge.scala:45,58`): `OC = FE * 0.1`.

**1 OC energy = 10 FE.**

For scale: a computer costs `power.cost.computer: 0.5` OC/tick, which is 5 FE/tick or **100 FE/s**.

> Side finding, out of scope: `common/item/traits/Chargeable.scala:18-19` converts in
> the opposite direction (`fe / ratio`), so items charge at 1 FE = 10 OC while blocks
> charge at 10 FE = 1 OC. Worth a separate fix.

### Targets and derived cost per hash

Decision: Tier 3 at full speed draws about **7,500 FE/s**. That gives `7,500 ÷ 150 =` **50 FE per hash**.
T1 and T2 keep the earlier FE/s targets, so higher tiers draw more power in total but
are more efficient per hash. T1 and T2 are starting values, tuned after in-game tests.

| Tier | H/s | Target FE/s | FE/hash | OC/hash | OC/tick at full speed | Equivalent computers |
|---|---|---|---|---|---|---|
| 1 | 30 | 2,500 | 83.3 | 8.33 | 12.5 | 25 |
| 2 | 75 | 5,000 | 66.7 | 6.67 | 25.0 | 50 |
| 3 | 150 | 7,500 | 50.0 | 5.00 | 37.5 | 75 |

How the table is derived: `FE/hash = targetFE/s ÷ H/s`, and `OC/hash = FE/hash × ratioForgeEnergy` (0.1).

For comparison, the Data Card's `sha256` on an about 150-byte block string costs
`6 + 150 × 0.1 = 21 OC` (210 FE) per hash. That's more per hash than the card, but
Data Card mining is only stopped by 9b, not by its price.

### Config

Speed is configured **in hashes per second**, because 7.5 per tick isn't a whole number. Energy is
configured **in FE per hash** and converted to OC with `ratioForgeEnergy` when the settings load, so
a server that changes `power.value.ForgeEnergy` stays consistent.

```
misc.miningCardHashesPerSecond:        [30, 75, 150]
misc.miningCardMaxPerCall:             [8, 8, 8]       # at most ~1 tick of T3 work per call
power.cost.miningCardEnergyPerHashFE:  [83.3, 66.7, 50.0]
misc.miningCardGlobalHashesPerSecond:  30000
```

An alternative is to configure the **FE/s target** instead of FE per hash and derive
`energyPerHash = targetFE/s ÷ hashesPerSecond`. Then changing the speed doesn't change the power
draw. The trade-off is that the config shows a less direct number. Either way works. The FE-per-hash
variant is simpler to explain in-game.

### Effects on power infrastructure

- `power.rate.case` is `[5, 10, 20, 30]` OC/tick, which is 1,000 / 2,000 / 4,000 FE/s of
  direct input for a T1, T2 or T3 case.
  - A T1 card (12.5 OC/tick) can run in a T3 case on direct power.
  - T2 and T3 cards need a **Power Converter** (`powerConverter: 500` OC/tick) or capacitors.
  - This is intended: mining needs real power infrastructure.
- A computer has a 500 OC buffer. A full T3 call (8 hashes × 5 OC = 40 OC) fits easily.

### Charging energy per call

Energy is charged per call:

1. Reserve `n` hashes, where `n = min(count, cardRemaining, globalRemaining, floor(buffer / costPerHash))`.
2. Charge `n * costPerHash` with `tryChangeBuffer`.
3. Hash.
4. If a nonce is found early, refund the unused energy and return the unused hashes to the
   card and global budgets.

Low power gives slower mining instead of an error. "Not enough energy" is thrown only when
not even one hash can be paid for.

---

## 6. API (component `mining`)

```
mine(prefix:string, count:integer, difficulty:integer)
  -> nonce:integer|nil, hash:string|nil, hashesDone:integer
```
- The card picks a random `start` in `[0, 2^62)` on **every call** and hashes
  `prefix .. tostring(n)` for `n` in `[start, start + hashesDone)`.
  It stops at the first `n` whose SHA-256 has at least `difficulty` leading zero bits.
- Why the card picks the start: in step 2 the card signs what it finds. If the caller chose
  the nonce, a nonce found in Lua could be passed in with `count = 1` and get signed. Choosing
  it in the card from day one means step 2 does not change the signature of `mine()`.
- The card keeps no state between calls. The chance to find a block per hash is the same as
  with sequential nonces; overlap between random ranges is negligible at 2^62.
- `hash` is lowercase hex, identical to `sha256_lib.hex`.
- `hashesDone` includes the successful hash.
- `hashesDone == 0` means the tick budget is used up, energy ran out, or the call fell back
  to the main thread. The script should `os.sleep(0.05)` and call again.
- Step 2 adds a fourth return value `signature` on success. Existing callers ignore it.
- Errors (`IllegalArgumentException`):
  - `count < 1`
  - `difficulty` outside `0..256`
  - `prefix` longer than 1,024 bytes (the largest valid ForgeCoin prefix is about 420 bytes)
  - a float passed instead of an integer for `count` or `difficulty`
- `direct = true, limit = 8`.

```
hash(data:string) -> string
```
- One SHA-256 as lowercase hex, charged as one hash and counted against the hash budget.
  It lets a validator check blocks faster. It is optional and not needed for compatibility.

```
getBudget() -> availableHashes:integer, hashesPerSecond:integer
getInfo()   -> { tier, hashesPerSecond, maxPerCall, energyPerHashFE, energyPerHash }
```

---

## 7. Files

**New:**
- `src/main/scala/li/cil/oc/common/item/MiningCard.scala`
- `src/main/scala/li/cil/oc/server/component/MiningCard.scala`: base class with the hashing core, plus `Tier1`, `Tier2`, `Tier3`
- `src/main/scala/li/cil/oc/server/component/MiningBudget.scala`: server-wide per-tick budget
- `src/main/scala/li/cil/oc/integration/opencomputers/DriverMiningCard.scala`: driver + `Provider`
- `src/main/resources/assets/opencomputers/textures/item/miningcard{1,2,3}.png`: recolored Data Card textures (+ `.mcmeta` if animated)
- `src/main/resources/data/opencomputers/tags/item/miningcard{1,2,3}.json`
- `src/main/resources/assets/opencomputers/doc/en_us/item/miningcard{1,2,3}.md`

**Changed:**
- `Constants.scala`: `MiningCardTier1-3`
- `OCItems.scala`: three `registerItem` calls
- `ModOpenComputers.scala`: `Driver.add(DriverMiningCard)` and `Driver.add(DriverMiningCard.Provider)`
- `common/EventHandler.scala`: reset `MiningBudget` in `onServerTickPre`
- `Settings.scala` and `application.conf`: the four values from section 5
- `src/data/java/.../OCRecipeProvider.java` and `OCItemModelProvider.java`, then run datagen to update `generatedResources`
- `lang/en_us.json`: `item.oc.miningcard0-2`, `oc:tooltip.miningcard0-2`
- `doc/en_us/item/index.md`

**ForgeCoin pack (separate repo, additive):** the `mineBlockWithCard` helper from section 1.

---

## 8. Risks

- **Main-thread fallback.** Handled by returning 0 hashes when `mine()` runs on the server thread.
- **CPU quota on Pterodactyl.** At 150 H/s per card the card's CPU cost is negligible. The real
  CPU risk is pure-Lua miners.
- **Lua and Data Card miners (main risk).** At the chosen speeds the card is slower than Lua
  (24x) and about as fast as the Data Card. Without option 9b, players keep mining in Lua and the
  speed limit protects nothing. See section 4.
- **Long calls.** Each call is at most 8 hashes (about 10 µs), so it can't approach
  `computer.timeout` (5 s).
- **Chunk unload or card removal.** The card keeps no state between calls. A call in progress
  finishes its bounded batch. Nothing to resume: the next call picks a new random start.
- **Thread safety.**
  - Use an `AtomicLong` per-card budget, reset lazily by comparing with `getGameTime`.
  - Use an atomic global budget.
  - Keep one `ThreadLocal` `MessageDigest` per thread. The midstate digest is cloned per call
    and never shared.
  - Energy goes through `tryChangeBuffer`, which is already synchronized.
- **String encoding.** The prefix must be taken as raw bytes. Hashing a decoded `String`
  would break blocks whose miner or data field contains non-ASCII characters.
- **Integer type.** The nonce must go back to Lua as an integer (`Long`), otherwise
  `isValidBlockStructure` rejects the block. `count` and `difficulty` are rejected if passed as floats.
- **Difficulty growth.** With cards, the total hash rate can rise by orders of magnitude.
  `getDifficulty` only moves ±1 bit every 10 blocks, so after cards arrive the chain will
  produce blocks quickly for a while. This is acceptable and settles by itself. Raising
  `GENESIS_DIFFICULTY` is optional for a fresh chain.

---

## 9. Open options (not decided)

### 9a. Miner registration at the pool

- The pool keeps a registry of miners per player and limits how many can be active at once.
- Identity could be the card's component address. It is stored with the item, so it stays
  the same when the card moves to another computer.
- The limit is enforced only in pool Lua code. The card needs no changes; `getInfo()` already
  exposes the tier.
- Without 9b this is cooperative: a player can submit blocks without registering.

### 9b. The card signs found blocks (decided: step 2)

- The card signs `hash` together with its own address using an **EC private key that exists
  only on the server**. The key is generated once and stored in world `SavedData`, never in
  the config or in item data.
- `mine()` returns the signature as an extra value. `getPublicKey()` exposes the public key.
- The pool checks the signature with a Tier 3 Data Card (`ecdsa`, `deserializeKey`), which
  already exists, and rejects unsigned blocks. This makes the card the only accepted way to mine.
- **Loophole (closed in step 1 already):** if the player chose the start nonce, they could
  find a nonce in Lua and have the card sign it with `count = 1`. The step 1 API already lets
  the card choose the start, so nothing changes here in step 2.
- **Protocol change:** blocks gain a `signature` field and an `isNewBlockValid` rule. That
  breaks compatibility with existing blocks, so it needs a fork height or a chain reset.
  This is the reason it is kept as a separate decision.
- No `sign(arbitrary data)` method should ever be exposed.

---

## 10. Decisions taken

- Tier 3 runs at 150 H/s (7.5 hashes per tick), configurable. T1 and T2 are proposed at 30 and 75 H/s.
- Tier 3 draws about 7,500 FE/s, so 50 FE per hash, configurable. T1 and T2 are proposed at 83.3 and 66.7 FE per hash.
- Hash format follows the existing Lua code (section 1).
- The server-wide cap is first-come. The per-card budget prevents monopolization.
- The texture is a recolor of the Data Card for now.
- Two-step delivery: step 1 = card without signatures, step 2 = option 9b + chain reset.
- `mine(prefix, count, difficulty)`: the card chooses a random start nonce every call (section 6).
- Energy is configured in FE per hash (section 5).
- Option 9a (miner registration) stays open; it needs no card changes.
- Recipes (proposal, tunable): each tier is the matching Data Card upgraded, so mining
  hardware costs more than a Data Card.

  | Tier | Pattern | Keys |
  |---|---|---|
  | 1 | `gCg` / ` D ` | `g` gold nugget, `C` Microchip T2, `D` Data Card T1 |
  | 2 | `gPg` / `CDC` | `g` gold ingot, `P` CPU T1, `C` Microchip T3, `D` Data Card T2 |
  | 3 | `dPd` / `CDC` | `d` Diamond Chip, `P` CPU T3, `C` Microchip T3, `D` Data Card T3 |
