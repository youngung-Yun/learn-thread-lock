package stock.experiment.dto.response;

import stock.experiment.dto.ExperimentStrategy;

public record ExperimentRunResponse(
        ExperimentStrategy strategy,
        Long initialQuantity,
        Long requestCount,
        Long successCount,
        Long remainingQuantity,
        Long elapsedMillis,
        Boolean invariantSatisfied
) {
}
