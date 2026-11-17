# Soqzilla (SQZ) rules, v1

Soqzilla is a mineable token recorded on the Soqucoin (SOQ) blockchain. SOQ itself knows nothing about it: every Soqzilla action is an ordinary SOQ transaction carrying a short tagged message in its data output (OP_RETURN), and anyone running the open indexer gets the same balances from the same chain. Just for fun: no premine, no team share, nothing sold.

The rules freeze when a deploy transaction writes the SHA-256 of this file to the chain. After that they cannot change.

## Supply

| | |
|---|---|
| Hard cap | 21,000,000,000 SQZ, whole coins only |
| Reward | 42,000 SQZ per mint, halving every 250,000 mints (about 174 days) |
| Floor | when halving reaches 1 SQZ it stays at 1 until the cap is hit exactly |
| Pace | one mint per SOQ block on average (about 1,440 a day) |
| Schedule | 50% mined in about 6 months, 75% in about a year, the last coin after about 9.5 years (5,000,000 mints) |
| Premine | none; the deployer mines on the same terms as everyone else |

## Messages

A message lives in the first output of a transaction whose script starts with OP_RETURN (`0x6a`). That script must be `0x6a`, one length byte (at most 75) and exactly that many bytes: `"SQZ"` (3 bytes), version `0x01`, an op byte, then the fields. If the first such output is anything else, the transaction carries no message; later outputs are never read. Numbers are big-endian. A program is the 32-byte SHA-256 of an ML-DSA-44 public key, the same thing an `sq1p…` address encodes.

| Op | Fields | Size |
|---|---|---|
| `D` deploy | rules hash (32) · launch height (4) | 41 bytes |
| `M` mint | reference height (4) · nonce (8) · miner program (32) | 49 bytes |
| `T` transfer | amount (8) · recipient program (32) | 45 bytes |

## Senders

The sender of a transaction is the program of the output spent by its first input, when that output's script is exactly `51 20 ‖ program` (a witness-v1 output, which Soqucoin lets only the holder of the matching ML-DSA-44 key spend). Otherwise the transaction has no sender, and a deploy or transfer in it is ignored. The witness is never used to decide the sender.

## Deploy

Genesis is the first confirmed deploy that meets all of these:
1. Its rules hash equals the SHA-256 of this file.
2. Its sender is the deployer, program `efae21147e955ef42e2c0af9fe88e8796537902631932738f1da176e1543efe4` (address `sq1pa7hzz9r7j400gt3vptulaz8g09jn0ypxxxfjww83mgtku92raljqeyzefq`).
3. Its launch height is at least 60 and at most 10,080 blocks after the block that includes it.

Nothing mints before the launch height. Any other deploy is ignored.

## Mining

Work = Argon2id(password = `"SQZ"`‖`01`‖`4D`‖reference block hash (32, the hex from `getblockhash` decoded as written)‖miner program (32)‖nonce (8), salt = `soqzilla/v1/pow!`, memory 64 MiB, 1 pass, 1 lane, 32-byte output).

A mint counts when all of these hold:
1. The reference height is at or after the launch height, below the height of the block that includes the mint, and no more than 20 blocks below it.
2. The work, read as a 256-bit big-endian number, is below the target in force at the reference height.
3. The same work value has not been credited before.
4. Supply remains.
5. Fewer than 3 mints have already been credited in the same block. Further mints in that block are ignored and not marked as credited.

The reward for a mint is `max(1, floor(42,000 ÷ 2^floor(n ÷ 250,000)))` SQZ, where n is the number of mints credited before it, and never more than the supply left.

The reward goes to the miner program inside the message, whoever pays the SOQ fee. Because the program is part of the work, a copied solution only ever pays the original miner. That lets a relay broadcast mints for phones that hold no SOQ.

Mints are credited in chain order: block by block, then in the order of transactions within each block.

## Difficulty

The target starts at `floor((2²⁵⁶ − 1) ÷ 60,000)`, about 60,000 hashes per mint. It applies from the launch height. Every 60 SOQ blocks after that, with n = mints credited in those 60 blocks:
- if n = 0, the new target is old × 4;
- otherwise it is `floor(old × 60 ÷ n)`;
- then it is limited to between `floor(old ÷ 4)` and `old × 4`, and to between 1 and 2²⁵⁶ − 1.

The new target applies from the first block of the next 60. A mint is checked against the target in force at its reference height.

## Transfers

The transfer moves the amount from the sender (see Senders) to the recipient program. It counts only if the sender holds at least the amount and the amount is above zero; otherwise it is ignored entirely. Amounts are whole SQZ.

## Reorgs

The indexer follows the SOQ chain's best tip. If blocks are replaced, their Soqzilla effects are undone and the new blocks applied. Wallets should treat anything under 6 confirmations as pending.

## Reference code

`lib/rules.mjs` and `lib/ledger.mjs`. If this text and that code ever disagree, the frozen text wins.
