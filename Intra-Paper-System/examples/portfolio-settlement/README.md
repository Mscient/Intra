# Synthetic portfolio settlement example

This four-bar futures/call example uses explicitly synthetic identities, calendars, settlement values, fees, and risk budgets. It is a diagnostic fixture, not market evidence or a recommended tariff/risk policy. The risk source reference hash is a placeholder declaration.

With a JDK 21 JAR, run from `Intra-Paper-System`:

```powershell
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar = '.\build\settlement-followup\intra-paper-system.jar'
& $java -jar $jar portfolio-run .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\output\settlement-demo
# Substitute the run ID printed above:
& $java -jar $jar portfolio-replay-verify .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\output\settlement-demo\<run-id>
.\portfolio-risk-audit.ps1 -JarPath $jar audit .\output\settlement-demo\<run-id> .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\examples\portfolio-settlement\risk .\output\settlement-risk
# Substitute the audit ID printed above (expected exit code 2 again):
.\portfolio-risk-audit.ps1 -JarPath $jar verify .\output\settlement-risk\<audit-id> .\output\settlement-demo\<run-id> .\examples\portfolio-settlement\inputs.csv .\examples\portfolio-settlement\portfolio.properties .\examples\portfolio-settlement\risk
```

Expected replay: two settlements, three settlement-cost lines, both positions flat, gross P&L -1, execution fees 2, settlement fees 1.83, and net P&L -4.83. Eight replay output files must reproduce byte-for-byte.

The risk fixture deliberately sets a session-loss cap of 4. Before settlement, net P&L is -2; after settlement, the loss is 4.83. The audit must report one `SESSION_LOSS_INR` breach at `SETTLEMENT`, with zero remaining exposure and margin proxy, and exit code 2. `verify` of that audit also returns code 2 because it preserves the verified BREACH status.

For settlement-aware registered trial evaluation, use the frozen split and `DEVELOPMENT_VALIDATION_ONLY` scope described in [the evaluator guide](../../docs/PORTFOLIO-TRIAL-EVALUATION.md). This one-session plumbing example is too short for the evaluator's statistical requirements; the focused evaluator checks generate a separate registered multi-session fixture.
