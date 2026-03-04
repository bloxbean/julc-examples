# JuLC-examples

Example repository for [JuLC](https://github.com/bloxbean/julc) - Validators, with off-chain demos and unit tests.

## Prerequisites

- Java 24+
- Gradle
- [Yaci Devkit](https://github.com/bloxbean/yaci-devkit) (required for off-chain demos)

## Project Structure

```
src/main/java/com/example/
├── validators/          # On-chain validators and minting policies
├── offchain/            # Off-chain demo programs (transaction building)
├── swap/                # DEX swap order (on-chain + off-chain + README)
├── lending/             # Collateral loan (on-chain + off-chain + README)
├── nft/                 # CIP-68 NFT (on-chain + off-chain + README)
├── benchmark/           # WingRiders DEX benchmark validators
├── mpf/                 # Merkle-Patricia Forestry types
└── util/                # Utility classes

src/test/java/com/example/
├── validators/          # Unit tests for validators
├── swap/                # SwapOrder tests (Direct Java + UPLC + JulcEval proxy)
├── lending/             # CollateralLoan tests (Direct Java + UPLC + JulcEval proxy)
├── nft/                 # Cip68Nft tests (UPLC + JulcEval proxy)
├── benchmark/           # Benchmark tests
└── mpf/                 # MPF tests
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

```bash
# Default: VestingDemo
./gradlew run

# Run a specific demo
./gradlew run -PmainClass=com.example.offchain.VestingDemo
```

### Available Demos

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

