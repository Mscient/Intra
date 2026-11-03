# L1 quote execution in strategy replay

The `run` command can use a verified local quote journal to execute the built-in bar strategy through a deterministic full-size top-of-book model. This is an offline simulator path; it does not connect to a feed or send orders.

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

The quote source name and instrument must match the selected top-of-book stream. The quote journal is fully verified before replay and its file hash participates in the dataset manifest, run identity, and run manifest. The file is loaded into memory for the replay.

An order's quote arrival time is the later of its decision time plus configured latency and the next eligible bar start. The engine selects the latest matching snapshot received by that time, subject to the exchange-timestamp skew allowance. BUY orders use displayed ask size and SELL orders use displayed bid size. Adverse slippage ticks come from the same dated cost inputs used by bar execution.

## Accounting and audit behavior

This mode fills an order only when the selected quote passes quality checks and the entire requested unit quantity is displayed. It cancels the simulated IOC without a fill when the quote is unavailable, stale or structurally invalid, or too small. Filled quantity is consumed by quote sequence and side so a later order cannot reuse the same displayed units. Quote source, sequence, receive time, reference price, fill price, and execution model are written to `fills.csv`.

Independent ledger reconciliation reselects each quote at the recorded arrival time, checks the source, sequence, receive time, full displayed size and modeled price, and rebuilds quote-side consumption. Checkpoint resume reconstructs consumed size from verified prior fills; the quote journal hash is part of the checkpoint content identity.

## Model boundary

The simulator does not model partial fills, passive queue priority, deeper book levels, hidden liquidity, market impact, exchange matching, or broker acknowledgements. The full-size all-or-miss assumption is a diagnostic fill model and can differ from actual IOC behavior. Fee and margin calculations continue to use the configured caller-supplied inputs. Results remain diagnostic and are not evidence of a profitable or executable strategy. Broker order routing remains absent.
