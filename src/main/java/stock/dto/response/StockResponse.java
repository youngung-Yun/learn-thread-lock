package stock.dto.response;

public record StockResponse(
        Long quantity,
        Long successfulReservations
) {
}
