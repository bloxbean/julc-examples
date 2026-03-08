# JuLC-examples

Example repository for [JuLC](https://github.com/bloxbean/julc) - Validators, with off-chain demos and unit tests.

## Prerequisites

- Java 24+
- Gradle
- [Yaci Devkit](https://github.com/bloxbean/yaci-devkit) (required for off-chain demos)

## Project Structure

```
src/main/java/com/example/
├── cftemplates/             # Cardano Foundation template validators (19 templates)
│   ├── anonymousdata/       # Commit-reveal anonymous data verification
│   ├── atomictx/            # Atomic transaction (spend + mint)
│   ├── auction/             # English auction
│   ├── bet/                 # Two-player oracle-resolved bet
│   ├── crowdfund/           # Crowdfunding with deadline and goal
│   ├── escrow/              # Two-phase asset escrow
│   ├── factory/             # Factory pattern with product minting
│   ├── htlc/                # Hash Time-Locked Contract
│   ├── identity/            # Decentralized identity with delegates
│   ├── lottery/             # Commit-reveal lottery game
│   ├── paymentsplitter/     # Equal payment distribution
│   ├── pricebet/            # Oracle-based price betting
│   ├── simpletransfer/      # Simple receiver-locked transfer
│   ├── simplewallet/        # Payment intent wallet
│   ├── storage/             # Immutable audit snapshot storage
│   ├── tokentransfer/       # Native asset guarded transfer
│   ├── upgradeableproxy/    # Upgradeable proxy with state token
│   ├── vault/               # Two-phase withdrawal with time lock
│   └── vesting/             # Time-locked vesting
├── validators/              # On-chain validators and minting policies
├── offchain/                # Off-chain demo programs (transaction building)
├── swap/                    # DEX swap order (on-chain + off-chain)
├── lending/                 # Collateral loan (on-chain + off-chain)
├── nft/                     # CIP-68 NFT (on-chain + off-chain)
├── uverify/                 # UVerify document verification (on-chain + off-chain)
├── benchmark/               # WingRiders DEX benchmark validators
├── mpf/                     # Merkle-Patricia Forestry types
└── util/                    # Utility classes

src/test/java/com/example/
├── cftemplates/             # Unit + integration tests for all CF templates
├── validators/              # Unit tests for validators
├── swap/                    # SwapOrder tests
├── lending/                 # CollateralLoan tests
├── nft/                     # Cip68Nft tests
├── uverify/                 # UVerify tests
├── benchmark/               # Benchmark tests
└── mpf/                     # MPF tests
```

Each CF template follows a consistent structure:
```
cftemplates/<template>/
├── onchain/                 # Validator class(es)
└── offchain/                # Off-chain demo
```

## Build

```bash
./gradlew clean build
```

## Run Unit Tests

```bash
./gradlew test
```

## Run Off-Chain Demos

Off-chain demos require a running [Yaci Devkit](https://github.com/bloxbean/yaci-devkit) instance.

### CF Template Demos

Use the `run-cfdemo.sh` script to run the 19 Cardano Foundation template demos:

```bash
# Run all 19 CF template demos sequentially
./run-cfdemo.sh

# Run a single demo
./run-cfdemo.sh escrow

# List available demos
./run-cfdemo.sh --list
```

| Demo | Description |
|------|-------------|
| anonymousdata | Commit-reveal scheme with blake2b_256(pkh \|\| nonce) |
| atomictx | Atomic spend + mint with password protection |
| auction | English auction with bidding and settlement |
| bet | Two-player bet resolved by oracle |
| crowdfund | Donation collection toward goal by deadline |
| escrow | Two-phase asset swap between parties |
| factory | One-shot factory marker with product creation |
| htlc | Hash Time-Locked Contract (secret claim or timeout reclaim) |
| identity | Decentralized identity with owner and delegates |
| lottery | Commit-reveal game with parity-based winner |
| paymentsplitter | Equal fund distribution among payees |
| pricebet | Oracle-based price betting with reference inputs |
| simpletransfer | Receiver-locked fund release |
| simplewallet | Payment intent minting and execution |
| storage | Immutable audit snapshot commitments |
| tokentransfer | Native asset guarded transfer |
| upgradeableproxy | State token proxy with withdrawal delegation |
| vault | Two-phase withdrawal with time lock |
| vesting | Time-locked funds with beneficiary claim |

### Other Demos

```bash
# Run a specific demo by class name
./gradlew run -PmainClass=com.example.offchain.VestingDemo
```

| Demo | Main Class |
|------|------------|
| Vesting | `com.example.offchain.VestingDemo` |
| Auction | `com.example.offchain.AuctionDemo` |
| Escrow | `com.example.offchain.EscrowDemo` |
| Minting | `com.example.offchain.MintingDemo` |
| One-Shot Mint | `com.example.offchain.OneShotMintDemo` |
| Multi-Sig | `com.example.offchain.MultiSigDemo` |
| Multi-Sig Minting | `com.example.offchain.MultiSigMintingDemo` |
| Output Check | `com.example.offchain.OutputCheckDemo` |
| MPF Registry | `com.example.offchain.MpfRegistryDemo` |
| Token Distribution | `com.example.offchain.TokenDistributionDemo` |
| Whitelist Treasury | `com.example.offchain.WhitelistTreasuryDemo` |
| Swap Order | `com.example.swap.offchain.SwapOrderDemo` |
| Collateral Loan | `com.example.lending.offchain.CollateralLoanDemo` |
| CIP-68 NFT | `com.example.nft.offchain.Cip68NftDemo` |
| UVerify (Proxy + V1) | `com.example.uverify.offchain.UVerifyDemo` |
