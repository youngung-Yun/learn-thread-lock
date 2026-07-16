package stock;

/**
 * 재고 관리 전략 인터페이스
 */
public interface StockStrategy {

    /**
     * 재고 예약
     * @param quantity 예약할 재고 수
     * @return 예약 불가능시 false 리턴, 예약 가능시 예약 후 true 리턴
     */
    boolean reserve(int quantity);

    /**
     * 남아있는 재고 조회
     * @return 남아있는 재고
     */
    int getRemainingQuantity();

    /**
     * 재고 초기화
     * @param quantity 초기화할 재고 수
     */
    void reset(int quantity);
}
