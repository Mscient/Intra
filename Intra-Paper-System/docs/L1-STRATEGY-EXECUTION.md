# L1 quote execution in strategy replay

The `run` command can use a verified local quote journal to execute the built-in bar strategy through deterministic full-size or whole-lot partial top-of-book IOC models. This is an offline simulator path; it does not connect to a feed or send orders.

## Configuration

Set these keys in the run properties file:

```properties
execution.model=L1_FULL_SIZE_IOC
execution.quote_journal_file=authorized-quotes.qj
execution.quote_source_name=AUTHORIZED_SOURCE
execution.decision_to_arrival_latency_ms=5
execution.quote_maximum_age_ms=1000
execution.quote_maximum_future_skew_ms=0
```

Set `execution.model=L1_PARTIAL_IOC` to model fills up to the visible top-level quantity, rounded down to whole market lots. The original `L1_FULL_SIZE_IOC` mode remains available and cancels without a fill unless the entire requested quantity is displayed.

The quote source name and instrument must match the selected top-of-book stream. The quote journal is fully verified before replay and its file hash participates in the dataset manifest, run identity, and run manifest. The file is loaded into memory for the replay.

An order's quote arrival time is the later of its decision time plus configured latency and the next eligible bar start. The engine selects the latest matching snapshot received by that time, subject to the exchange-timestamp skew allowance. BUY orders use displayed ask size and SELL orders use displayed bid size. Adverse slippage ticks come from the same dated cost inputs used by bar execution.

## Accounting and audit behavior

Both modes require the selected quote to pass quality checks and consume filled quantity by quote sequence and side, so a later simulated order cannot reuse the same displayed units. `L1_FULL_SIZE_IOC` fills only when the entire requested quantity is displayed. `L1_PARTIAL_IOC` fills the smaller of the requested quantity and displayed quantity rounded down to whole market lots; if less than one lot is displayed, it cancels without a fill. Any remaining IOC quantity is canceled. A later strategy decision may create a new order for the still-unfilled position target.

Entry fills can complete the original target over multiple strategy decisions. Entry increases beyond the original target, and increases after any exit has begun, are rejected. Partial exits allocate the remaining cost basis and entry fees across closed slices; `trades.csv` contains one row per closed slice, including each partial exit, so slice gross P&L sums to realized P&L. Mark-to-market uses the exact remaining basis. Checkpoint state retains that basis and restores consumed displayed quantity from verified fills.

The regular-lot behavior is informed by the [NSE NNF protocol](https://nsearchives.nseindia.com/web/sites/default/files/inline-files/TP_FO_Trimmed_NNF_PROTOCOL_9.45_2.pdf), which describes IOC residual cancellation and regular order quantities in market-lot multiples (with an odd-lot exception). Flooring aggregated displayed L1 quantity to whole lots is a simulator assumption; it is not evidence that a specific exchange fill will occur.

Quote source, sequence, receive time, reference price, fill price, and execution model are written to `fills.csv`.

Independent ledger reconciliation reselects each quote at the recorded arrival time, checks the source, sequence, receive time, requested and filled quantity, lot multiple, and modeled price, and rebuilds quote-side consumption. Checkpoint resume reconstructs consumed size from verified prior fills; the quote journal hash is part of the checkpoint content identity.

## Model boundary

Neither model represents passive queue priority, deeper book levels, hidden liquidity, market impact, exchange matching, or broker acknowledgements. L1 displayed size can change before an order reaches the exchange, and this simulator's consumed-size rule is only a deterministic replay convention. Fee and margin calculations continue to use configured caller-supplied inputs. Results remain diagnostic and are not evidence of a profitable or executable strategy. Broker order routing remains absent.
