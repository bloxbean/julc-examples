#!/bin/sh
#
# Run CF template offchain demos.
#
# Usage:
#   ./run-cfdemo.sh                        # Run all demos sequentially
#   ./run-cfdemo.sh escrow                 # Run only the escrow demo
#   ./run-cfdemo.sh --list                 # List available demos
#   ./run-cfdemo.sh --evaluator backend    # Evaluate scripts via Yaci Store's
#                                          # /utils/txs/evaluate endpoint instead
#                                          # of the in-process JuLC VM
#
# Requires Yaci DevKit running.
#
# Every demo's console output is also written to logs/cfdemo/<name>.log, so a
# failure can be inspected after the run.
#
# When running all demos, failures are recorded and a summary is printed
# at the end.  When running a single demo, the script exits immediately
# with the demo's exit code.

set -eu

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_ROOT/logs/cfdemo"
EVAL_ARG=""

# name:fully.qualified.ClassName (sorted)
DEMOS="
anonymousdata:com.example.cftemplates.anonymousdata.offchain.AnonymousDataDemo
atomictx:com.example.cftemplates.atomictx.offchain.AtomicTxDemo
auction:com.example.cftemplates.auction.offchain.AuctionDemo
bet:com.example.cftemplates.bet.offchain.BetDemo
crowdfund:com.example.cftemplates.crowdfund.offchain.CrowdfundDemo
escrow:com.example.cftemplates.escrow.offchain.EscrowDemo
factory:com.example.cftemplates.factory.offchain.FactoryDemo
htlc:com.example.cftemplates.htlc.offchain.HtlcDemo
identity:com.example.cftemplates.identity.offchain.IdentityDemo
lottery:com.example.cftemplates.lottery.offchain.LotteryDemo
paymentsplitter:com.example.cftemplates.paymentsplitter.offchain.PaymentSplitterDemo
pricebet:com.example.cftemplates.pricebet.offchain.PriceBetDemo
simpletransfer:com.example.cftemplates.simpletransfer.offchain.SimpleTransferDemo
simplewallet:com.example.cftemplates.simplewallet.offchain.SimpleWalletDemo
storage:com.example.cftemplates.storage.offchain.StorageDemo
tokentransfer:com.example.cftemplates.tokentransfer.offchain.TokenTransferDemo
upgradeableproxy:com.example.cftemplates.upgradeableproxy.offchain.ProxyDemo
vault:com.example.cftemplates.vault.offchain.VaultDemo
vesting:com.example.cftemplates.vesting.offchain.VestingDemo
"

lookup() {
  echo "$DEMOS" | while IFS=: read -r name class; do
    [ -z "$name" ] && continue
    if [ "$name" = "$1" ]; then
      echo "$class"
      return 0
    fi
  done
}

list_demos() {
  echo "Available demos:"
  echo "$DEMOS" | while IFS=: read -r name class; do
    [ -z "$name" ] && continue
    echo "  $name"
  done
}

run_demo() {
  name="$1"
  main_class="$2"
  log="$LOG_DIR/$name.log"
  echo ""
  echo "========================================="
  echo "  Running: $name"
  echo "  Class:   $main_class"
  echo "  Log:     $log"
  echo "========================================="
  echo ""
  cd "$PROJECT_ROOT"
  # Keep gradle's exit code (not tee's) while still capturing the output.
  status_file=$(mktemp)
  {
    ./gradlew run -PmainClass="$main_class" $EVAL_ARG
    echo $? > "$status_file"
  } 2>&1 | tee "$log"
  status=$(cat "$status_file")
  rm -f "$status_file"
  return "$status"
}

# -- Main --

# --evaluator <local|backend> may precede the demo name.
if [ $# -ge 1 ] && { [ "$1" = "--evaluator" ] || [ "$1" = "-e" ]; }; then
  if [ $# -lt 2 ]; then
    echo "Error: $1 requires a value (local or backend)"
    exit 1
  fi
  EVAL_ARG="-Pevaluator=$2"
  shift 2
fi

mkdir -p "$LOG_DIR"
[ -n "$EVAL_ARG" ] && echo "Script evaluation: $EVAL_ARG"

if [ $# -eq 0 ]; then
  # --- Run all demos ---
  # Use a temp file to track results across the pipe-to-while subshell.
  RESULTS_FILE=$(mktemp)
  trap 'rm -f "$RESULTS_FILE"' EXIT

  total=0
  # Count demos first
  total=$(echo "$DEMOS" | grep -c ':')

  echo "Running all ${total} offchain demos..."

  # Disable errexit so the script continues past individual demo failures.
  set +e

  echo "$DEMOS" | while IFS=: read -r name class; do
    [ -z "$name" ] && continue
    if run_demo "$name" "$class"; then
      echo "PASS:${name}" >> "$RESULTS_FILE"
    else
      echo "FAIL:${name}" >> "$RESULTS_FILE"
    fi
  done

  # Re-enable errexit for the summary section.
  set -e

  # -- Summary --
  echo ""
  echo "========================================="
  echo "  Summary"
  echo "========================================="

  passed=$(grep -c '^PASS:' "$RESULTS_FILE" 2>/dev/null || true)
  failed=$(grep -c '^FAIL:' "$RESULTS_FILE" 2>/dev/null || true)

  echo "  Total:  ${total}"
  echo "  Passed: ${passed}"
  echo "  Failed: ${failed}"

  if [ "$failed" -gt 0 ]; then
    echo ""
    echo "  Failed demos:"
    grep '^FAIL:' "$RESULTS_FILE" | while IFS=: read -r _ dname; do
      echo "    - ${dname}  (log: logs/cfdemo/${dname}.log)"
    done
    echo ""
    exit 1
  else
    echo ""
    echo "All demos passed."
  fi

elif [ "$1" = "--list" ] || [ "$1" = "-l" ]; then
  list_demos
else
  main_class=$(lookup "$1")
  if [ -z "$main_class" ]; then
    echo "Error: Unknown demo '$1'"
    echo ""
    list_demos
    exit 1
  fi
  run_demo "$1" "$main_class"
fi
