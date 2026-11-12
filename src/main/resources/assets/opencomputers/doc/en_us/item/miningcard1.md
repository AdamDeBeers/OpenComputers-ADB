# Mining Card

![Hash it out.](oredict:opencomputers:miningcard1)

The mining card is dedicated SHA-256 hardware for ForgeCoin (FRG), the in-game blockchain. FRG has no real-world value: it cannot be bought or sold for real money.

The card hashes `prefix .. nonce` in hardware. It picks a random starting nonce on every call, so a script only supplies the block prefix, how many hashes to try and the difficulty (number of leading zero bits).

Each card has a hash budget per second and cannot save up more than one second's worth. All cards on the server also share a global budget. When the budget is used up, `mine` returns `0` hashes done; the script should sleep for a tick and try again.

Every hash costs energy. Higher tiers draw more power in total but are more efficient per hash.

| Tier | Hashes per second | Energy per hash |
|---|---|---|
| 1 | 30 | 83.3 FE |
| 2 | 75 | 66.7 FE |
| 3 | 150 | 50 FE |

(Default values; server operators can change them in the config.)

Methods:
- `mine(prefix:string, count:number, difficulty:number):number|nil, string|nil, number` - hashes up to `count` nonces. Returns the nonce, its hash as lowercase hex and the number of hashes done, or `nil, nil, hashesDone` if nothing was found.
- `hash(data:string):string|nil` - single SHA-256 as lowercase hex, costs one hash.
- `getBudget():number, number` - hashes available right now and the card's hashes per second.
- `getInfo():table` - tier and limits of the card.
