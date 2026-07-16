package stock.controller;

import org.springframework.web.bind.annotation.*;
import stock.dto.request.StockReserveRequest;
import stock.dto.request.StockResetRequest;
import stock.experiment.dto.request.ExperimentRunRequest;

@RestController
public class StockController {

    @PostMapping(value = "/stocks/reset",
    produces = "application/json")
    public void resetStocks(
            @RequestBody StockResetRequest request
            ) {
    }

    @GetMapping(value = "/stocks",
    produces = "application/json")
    public void getStocks() {

    }

    @PostMapping(value = "/stocks/reserve",
    produces = "application/json")
    public void reserveStocks(
            @RequestBody StockReserveRequest request
            ) {

    }

    @PostMapping(value = "/experiments/run",
    produces = "application/json")
    public void runExperiments(
            @RequestBody ExperimentRunRequest request
            ) {
    }
}
