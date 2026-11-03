# Toy limit order book

`ToyLimitOrderBook` is an offline research primitive for inspecting price/time priority and partial fills. It is not an NSE emulator, execution guarantee, or calibrated queue model. Its assumptions are explicit:

- Prices are positive integer ticks and quantities are positive integer units. A caller converts from currency prices using the applicable dated tick size.
- Calls to `submit` define event order. Within a price level, resting orders match FIFO; better prices match first.
- A crossing limit order trades against resting orders at each resting order's price. Any remaining limit quantity joins the back of its price-level queue.
- `MARKET_IOC` sweeps the available opposite-side book and cancels any unfilled remainder. The model has no price protection, so callers must not interpret a sweep as a realistic executable price without adding a separately justified bound.
- Cancellation removes only the remaining quantity. There is no replace operation; cancel plus a new order ID receives a new priority sequence.
- The book does not model self-trade prevention, hidden/iceberg liquidity, auctions, stop orders, latency, exchange validation, fees, market impact beyond traversed levels, or broker behavior.

The API is single-writer and not thread-safe. Results from this model must be labeled toy/synthetic until an exchange-specific simulator has been sourced, independently reviewed, and calibrated against authorized event data.
