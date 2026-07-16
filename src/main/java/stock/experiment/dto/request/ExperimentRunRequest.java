package stock.experiment.dto.request;

import stock.experiment.dto.ExperimentStrategy;

public record ExperimentRunRequest(
        ExperimentStrategy strategy,
        Long threadCount,
        Long requestCount
) {
}
