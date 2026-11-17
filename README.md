# Soqzilla (SQZ)

A just-for-fun token you mine on your phone, recorded on the [Soqucoin](https://soqu.org) blockchain. Live at **[soqzilla.com](https://soqzilla.com)**.

- **21,000,000,000 SQZ**, hard cap. 42,000 SQZ per find, about one find per SOQ block (a minute), halving every 250,000 finds.
- **No premine, no team share, nothing sold.** Everyone, the deployer included, mines on the same terms.
- **Phone-first proof of work:** Argon2id with 64 MiB of memory per hash. That makes Bitcoin and Scrypt mining rigs useless. A PC or graphics card will still out-mine a phone, so this is "built for phones", not "phones only".
- **No SOQ needed to mine:** a relay pays the network fee and broadcasts your find. Your address is part of the work itself, so nobody can redirect the reward.

Soqucoin has no native tokens. Every Soqzilla action is an ordinary SOQ transaction with a short tagged message in its data output, and the open indexer here turns the chain into balances. Anyone can run it and get the same numbers. The rules are in [SPEC.md](SPEC.md); the deploy transaction commits its SHA-256 on chain, and after that they cannot change.

**Heat and batteries:** mining keeps a phone's processor busy, so phones get warm and batteries wear faster. The app pauses at 42°C battery temperature and stops at 45°C, which lowers the risk without removing it. You mine at your own risk; the software comes with no warranty (see LICENSE).

Not affiliated with Soqucoin or Soqucoin Labs. Not an investment: SQZ has no backing, no promised value and no team behind it beyond the code.

## Security model

- **Senders are proven by the chain, not the message.** A transfer or deploy counts only when its first input spends a witness-v1 output (`51 20 ‖ program`), which Soqucoin lets only the key holder spend. The indexer reads that output's script; it never trusts the witness.
- **Only the named deployer can launch**, and only with a launch height 1 hour to 1 week ahead, so nobody can launch early, launch alone, or push the launch out of reach.
- **The relay cannot redirect rewards:** the miner's address is inside the proof of work. It can only refuse to relay (then broadcast yourself with your own SOQ).
- **At most 3 finds count per SOQ block**, which caps how fast any one rig can take coins while difficulty catches up.
- **Trust assumptions:** this indexer reads blocks from one public Soqucoin RPC and does not check their proof of work itself, so a compromised RPC could show false balances (run your own node for certainty). The relay's hot wallet is an environment variable on the host, so it only ever holds small change for fees.

## Layout

| Path | What |
|---|---|
| `SPEC.md` | the rules |
| `lib/rules.mjs`, `lib/ledger.mjs` | reference implementation: messages, proof of work, emission, difficulty, balances |
| `lib/chain.mjs`, `lib/wallet.mjs` | Soqucoin RPC access; ML-DSA-44 transaction signing (soq-lightning-sdk) |
| `server.mjs` | indexer + JSON API + mint relay |
| `android/` | the Android miner and wallet (Kotlin, BouncyCastle for ML-DSA-44 and Argon2id) |
| `scripts/` | operator transactions, a test miner, the Android test vectors |

## Run your own indexer

Soqzilla's record lives on the Soqucoin chain: the deploy, every find and every transfer is an ordinary SOQ transaction. soqzilla.com only reads the chain and applies [SPEC.md](SPEC.md). Anyone can run the same code and must get the same balances:

```sh
git clone https://github.com/Hung0v3r/soqzilla && cd soqzilla
npm ci
npm test
START_HEIGHT=52050 node server.mjs   # any SOQ height at or before the deploy block, 52052
# then compare http://localhost:8080/api/address/<sq1p…> with https://soqzilla.com/api/address/<sq1p…>
```

- Genesis: deploy transaction `d59c87378a922e45845b20b60e6a7126b0072b059e43459b8bc28dbdeade32f4` in SOQ block 52052; mining from block 52130.
- Rules hash: `afc96afcaae8837cf82090ee0dae004b7d8d9dee77f070a1c28220d8e92a6f30` (the SHA-256 of SPEC.md at tag `spec-v1`).
- Set `SOQ_RPC_URL` to your own `soqucoind` to stop trusting the public RPC.
- Add `RELAY_SEED` (a wallet holding SOQ) to run a relay that pays fees for other people's finds.

API: `/api/status`, `/api/work`, `/api/top`, `/api/events`, `/api/address/<sq1p…>`, `POST /api/mint`, `POST /api/gas`, `POST /api/broadcast`.

`SQZ_NET=test` swaps the tag to `SQX` and makes mining trivial, for dry runs on mainnet that the real indexer ignores.

## Android

CI builds a signed release APK on every push (Actions → latest run → artifacts). It is sideload only: Google Play does not allow on-device mining apps.

The released app talks only to soqzilla.com (with the Railway address as a fallback) and has no server setting. To point the app at your own indexer, build it with `soqzilla.serverSetting=true` in `android/gradle.properties`; that adds Wallet → Server.

## Licence

MIT
