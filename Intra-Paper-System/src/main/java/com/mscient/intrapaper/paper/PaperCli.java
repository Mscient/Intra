package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.SmaCrossoverStrategy;
import java.nio.file.Path;

/** CLI operating mode of IntraPaperCli; every input is a pre-existing local file. */
public final class PaperCli {
    private PaperCli() { }
    public static void run(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("paper-stop")) {
            Path directory = Path.of(args[1]);
            if (!java.nio.file.Files.isRegularFile(directory.resolve("checkpoint.txt")))
                throw new IllegalArgumentException("No initialized paper session at this directory");
            if (!java.nio.file.Files.exists(directory.resolve("STOP")))
                java.nio.file.Files.writeString(directory.resolve("STOP"), "OPERATOR_STOP\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("Local operator stop requested; applied before the next event transaction.");
            return;
        }
        if (args.length < 4 || args.length > 5 || (args[0].equals("paper-verify") && args.length != 4))
            throw new IllegalArgumentException("Usage: paper-run <bars.csv> <run.properties> <session-dir> [max-events] | paper-verify <bars.csv> <run.properties> <session-dir>");
        var config = RunConfig.load(Path.of(args[2]));
        var data = ValidatedDataset.load(Path.of(args[1]), config);
        var date = data.bars().getFirst().sessionDate();
        if (data.bars().stream().anyMatch(b -> !b.sessionDate().equals(date)))
            throw new IllegalArgumentException("Paper CLI requires one local session per invocation");
        try (var runtime = new PaperRuntime(Path.of(args[3]), data, config, new SmaCrossoverStrategy(config),
                date, LocalMarketDataProvider.recorded(data))) {
            if (args[0].equals("paper-verify")) runtime.verifyReport();
            else runtime.run(args.length == 5 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE);
            System.out.println("PAPER_PLUMBING_ONLY: state=" + runtime.state() + "; reason=" + runtime.reason()
                    + "; events=" + runtime.consumedEvents() + "; reconciliation verified; G0=OPEN; external_connection=false");
        }
    }
}
