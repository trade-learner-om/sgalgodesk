# Indicator parity fixtures

`tradingview_parity.csv` columns: `time,open,high,low,close,EMA,Supertrend`.

The checked-in file is a **synthetic** series generated with the same Pine-parity EMA(9) and Supertrend(3.5, ATR 10) formulas as `TradingViewIndicatorUtil`. Replace it with a TradingView **Export chart data** CSV (same columns) for live chart parity; the JUnit test asserts every post-warmup bar within `1e-4`.
