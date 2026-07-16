# 프로젝트 개요

- 현재 프로젝트는 Java의 Thread 및 Lock 학습을 위해 LLM으로 요청하여 받은 프로젝트 커리큘럼이다.

## LLM 커리큘럼
### 추천 프로젝트: **재고 예약 동시성 실험실**

기능은 하나만 둡니다.

> 재고가 100개인 상품에 여러 요청이 동시에 들어왔을 때, 재고를 정확하게 차감한다.

여기에 구현 전략만 계속 바꿔가며 결과를 비교합니다.

- 동기화 없음
- `volatile`
- `synchronized`
- `ReentrantLock`
- `AtomicInteger`
- 낙관적 락
- 비관적 락
- 플랫폼 스레드
- 스레드 풀
- 가상 스레드

회원, 인증, 결제, 프론트엔드, 메시지 브로커 등은 만들지 않습니다.

---

## 1. 프로젝트 구성

### 기술 스택

다음 구성이 적절합니다.

- Java 21
- Spring Boot 3.5.x
- Spring MVC
- Spring Boot Test
- Gradle 또는 Maven
- 후반부에만 Spring Data JPA와 H2 추가

Spring Boot 3.5.16은 Java 17 이상을 요구하고 Java 25까지 지원합니다. Java 21을 선택하면 가상 스레드까지 학습하면서도 프리뷰 기능을 피할 수 있습니다. ([Home][1])

초기 의존성은 두 개면 충분합니다.

```text
Spring Web
Spring Boot Test
```

처음에는 데이터베이스도 사용하지 않고 메모리에 재고를 저장합니다.

---

## 2. 최소 기능

### API

API도 다음 정도만 만듭니다.

```http
POST /stocks/reset
GET  /stocks
POST /stocks/reserve
POST /experiments/run
```

### 공통 요청 및 응답 원칙

- 요청이나 응답에 본문이 있는 경우 JSON을 사용하고 `Content-Type: application/json`으로 처리합니다.
- 성공 상태가 항상 `200 OK`인 컨트롤러는 응답 DTO를 직접 반환합니다. 단순히 `ResponseEntity.ok(...)`로 감싸기 위해 모든 컨트롤러의 반환 타입을 `ResponseEntity`로 통일하지 않습니다.
- 상태 코드나 응답 헤더를 실행 결과에 따라 직접 변경해야 할 때만 `ResponseEntity`를 사용합니다.
- 잘못된 요청과 비즈니스 예외는 컨트롤러에서 오류 응답을 직접 만들지 않고 예외를 던진 뒤 `@RestControllerAdvice`의 `@ExceptionHandler`에서 HTTP 상태 코드와 오류 본문으로 변환합니다.
- 예상하지 못한 코드 오류는 성공 응답이나 `4xx`로 변환하지 않습니다. 서버가 요청을 처리하지 못한 경우이므로 `500 Internal Server Error`로 응답합니다.
- 오류 응답은 학습 범위를 벗어나게 복잡하게 만들지 않고 다음 두 필드만 사용합니다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "quantity는 1 이상이어야 합니다."
}
```

대표 상태 코드의 의미는 다음과 같습니다.

| 상태 코드 | 사용 기준 |
| --- | --- |
| `200 OK` | 조회, 초기화, 예약 또는 실험 실행이 정상적으로 완료됨 |
| `400 Bad Request` | 필수 값 누락, 잘못된 JSON, 0 이하의 수량이나 스레드 수, 지원하지 않는 전략 |
| `409 Conflict` | 요청 형식은 올바르지만 현재 재고 상태 때문에 단일 예약을 완료할 수 없음 |
| `500 Internal Server Error` | 예상하지 못한 서버 코드 오류로 요청 처리가 중단됨 |

### 각 API의 요청 및 응답

#### `POST /stocks/reset`

현재 재고와 성공한 예약 수를 새로운 실험을 시작할 수 있는 상태로 초기화합니다.

초기화할 수량은 JSON 요청 본문으로 전달합니다. 재고를 변경하는 명령에 필요한 데이터이므로 조회 조건에 주로 사용하는 쿼리 파라미터보다 요청 DTO로 표현합니다. 이렇게 하면 다른 `POST` API와 요청 형식이 일관되고 Bean Validation을 적용하거나 필드를 확장하기도 쉽습니다.

```http
POST /stocks/reset
Content-Type: application/json
```

```json
{
  "quantity": 100
}
```

`quantity`는 1 이상의 정수여야 합니다.

성공 응답:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "initialQuantity": 100
}
```

초기화가 완료되면 현재 재고는 `100`, 성공한 예약 수는 `0`이 됩니다. 학습 중 초기화 결과를 바로 확인할 수 있도록 `204 No Content` 대신 `200 OK`와 응답 본문을 사용합니다.

잘못된 요청 예:

```http
POST /stocks/reset
Content-Type: application/json
```

```json
{
  "quantity": 0
}
```

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "code": "INVALID_QUANTITY",
  "message": "quantity는 1 이상이어야 합니다."
}
```

#### `GET /stocks`

마지막 초기화 이후의 현재 재고와 성공한 예약 수를 조회합니다. 단일 인메모리 재고는 항상 존재한다고 가정하므로 식별자나 `404 Not Found` 응답을 사용하지 않습니다.

요청:

```http
GET /stocks
```

요청 파라미터와 요청 본문은 없습니다.

성공 응답:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "quantity": 37,
  "successfulReservations": 63
}
```

동시 실행 중 조회한 값은 조회 시점의 스냅샷입니다. 응답 이후 다른 스레드가 예약하면 실제 현재 값은 즉시 달라질 수 있습니다.

#### `POST /stocks/reserve`

현재 선택된 재고 전략으로 지정한 수량을 한 번 예약합니다.

요청:

```http
POST /stocks/reserve
Content-Type: application/json
```

```json
{
  "quantity": 1
}
```

`quantity`는 1 이상의 정수여야 합니다.

예약 성공 응답:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "reserved": true
}
```

예약 응답에는 `remainingQuantity`를 포함하지 않습니다. `reserve()`가 성공한 뒤 별도로 재고를 조회하면 그 사이 다른 스레드가 재고를 변경할 수 있어, 해당 예약 직후의 값처럼 오해할 수 있기 때문입니다. 현재 재고가 필요하면 `GET /stocks`로 별도 조회합니다.

재고 부족 응답:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "요청한 수량만큼 예약할 재고가 없습니다."
}
```

단일 예약 API에서는 요청한 작업을 현재 재고 상태 때문에 완료하지 못했으므로 `409 Conflict`로 처리합니다. 반면 동시성 실험 내부에서 발생한 예약 실패는 예상된 측정 결과이므로 예외로 변환하지 않고 실패 횟수에 포함합니다.

#### `POST /experiments/run`

선택한 재고 전략을 여러 스레드가 공유하도록 하고 지정한 횟수만큼 예약 작업을 실행합니다. 각 예약 작업은 재고 1개를 예약합니다.

요청:

```http
POST /experiments/run
Content-Type: application/json
```

```json
{
  "strategy": "SYNCHRONIZED",
  "threadCount": 20,
  "requestCount": 100
}
```

각 필드의 의미와 검증 조건은 다음과 같습니다.

| 필드 | 의미 | 조건 |
| --- | --- | --- |
| `strategy` | 실험에 사용할 재고 구현 전략 | 지원하는 전략 이름과 정확히 일치해야 함 |
| `threadCount` | 예약 작업을 실행할 스레드 수 | 1 이상의 정수 |
| `requestCount` | 전체 예약 요청 횟수 | 1 이상의 정수 |

초기 구현에서 지원하는 전략 이름은 다음과 같습니다.

```text
UNSAFE
VOLATILE
SYNCHRONIZED
REENTRANT_LOCK
```

성공 응답:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "strategy": "SYNCHRONIZED",
  "initialQuantity": 100,
  "requestCount": 100,
  "successCount": 100,
  "remainingQuantity": 0,
  "elapsedMillis": 24,
  "invariantSatisfied": true
}
```

각 응답 필드의 의미는 다음과 같습니다.

| 필드 | 의미 |
| --- | --- |
| `strategy` | 실제 실행한 전략 |
| `initialQuantity` | 실험 시작 시점의 최초 재고 |
| `requestCount` | 제출한 전체 예약 작업 수 |
| `successCount` | `reserve(1)`이 `true`를 반환한 횟수 |
| `remainingQuantity` | 모든 작업 종료 후 남은 재고 |
| `elapsedMillis` | 작업 실행 시작부터 전체 작업 종료까지 걸린 시간 |
| `invariantSatisfied` | 핵심 재고 불변식이 모두 성립하는지 여부 |

`UNSAFE` 또는 `VOLATILE` 전략에서 불변식이 깨져도 실험 실행 자체가 정상적으로 끝났다면 `200 OK`로 응답합니다. 불변식 위반은 서버 처리 실패가 아니라 이 프로젝트가 관찰하려는 실험 결과입니다.

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "strategy": "UNSAFE",
  "initialQuantity": 100,
  "requestCount": 100,
  "successCount": 100,
  "remainingQuantity": 7,
  "elapsedMillis": 18,
  "invariantSatisfied": false
}
```

지원하지 않는 전략이나 잘못된 실행 조건은 실험을 시작하지 않고 `400 Bad Request`로 응답합니다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
```

```json
{
  "code": "INVALID_STRATEGY",
  "message": "지원하지 않는 재고 전략입니다: UNKNOWN"
}
```

### 컨트롤러 반환 타입 기준

이 프로젝트의 컨트롤러 반환 타입은 다음 기준을 따릅니다.

```java
@PostMapping("/stocks/reset")
public ResetStockResponse resetStocks(
        @Valid @RequestBody ResetStockRequest request
) {
    // 항상 200 OK
}

@GetMapping("/stocks")
public StockResponse getStocks() {
    // 항상 200 OK
}

@PostMapping("/stocks/reserve")
public ReserveStockResponse reserveStocks(...) {
    // 성공하면 DTO 반환, 실패하면 예외 발생
}

@PostMapping("/experiments/run")
public ExperimentResult runExperiments(...) {
    // 실험 결과와 함께 항상 200 OK
}
```

컨트롤러는 성공 결과만 DTO로 반환합니다. `400`, `409`, `500` 오류 응답은 공통 예외 처리기가 담당하며, 컨트롤러마다 `ResponseEntity` 분기문을 반복하지 않습니다.

실제 학습은 HTTP 요청보다 `ExperimentRunner`와 테스트 코드를 중심으로 진행하는 것이 좋습니다. HTTP는 실험을 실행하고 결과를 확인하는 얇은 인터페이스로만 둡니다.

---

## 3. 핵심 불변식

모든 실험에서 다음 조건을 검사합니다.

```text
남은 재고 >= 0

성공한 예약 수 + 남은 재고 = 최초 재고

성공한 예약 수 <= 최초 재고
```

예를 들어 재고가 100개인데 1,000개의 예약 요청을 동시에 실행하면 반드시 다음 결과가 나와야 합니다.

```text
성공: 100
실패: 900
남은 재고: 0
```

이 조건이 깨진다면 동시성 버그가 있는 것입니다.

---

## 4. 프로젝트 구조

전략을 교체할 수 있게 인터페이스를 하나 둡니다.

```text
src/main/java/com/example/concurrency
├── stock
│   ├── Stock.java
│   ├── StockService.java
│   ├── StockStrategy.java
│   ├── StockStrategyRegistry.java
│   ├── StrategyType.java
│   ├── UnsafeStockStrategy.java
│   ├── VolatileStockStrategy.java
│   ├── SynchronizedStockStrategy.java
│   ├── ReentrantLockStockStrategy.java
│   └── AtomicStockStrategy.java
├── experiment
│   ├── ConcurrencyExperimentRunner.java
│   ├── ExperimentRequest.java
│   └── ExperimentResult.java
├── async
│   ├── AsyncReservationService.java
│   └── ExecutorConfiguration.java
└── api
    └── StockController.java
```

핵심 인터페이스는 이 정도면 충분합니다.

```java
public interface StockStrategy {

    boolean reserve(int quantity);

    int getRemainingQuantity();

    void reset(int quantity);
}
```

전략별 구현체가 동일한 계약을 구현하게 합니다. 그러면 테스트와 실행기는 유지하면서 동기화 방법만 교체할 수 있습니다.

### `StockStrategy`의 조회와 초기화 구현 원칙

동기화 전략은 `reserve()`에만 적용하지 않습니다. `quantity`는 공유 가변 상태이므로 `getRemainingQuantity()`와 `reset()`도 각 전략이 학습하려는 방식과 동일한 규칙으로 접근해야 합니다.

공통 규칙은 다음과 같습니다.

- `reset(quantity)`는 1 이상의 수량만 허용합니다. HTTP 요청은 Bean Validation으로 검사하고, 전략을 테스트 코드에서 직접 호출할 때도 잘못된 상태가 만들어지지 않도록 전략 내부에서 다시 검사합니다.
- `getRemainingQuantity()`는 호출 시점의 재고 스냅샷을 반환합니다. 반환 직후 다른 스레드가 예약하면 실제 현재 값은 달라질 수 있습니다.
- 안전한 전략에서는 `reserve()`, `getRemainingQuantity()`, `reset()`이 같은 락 또는 같은 원자 변수를 사용해야 합니다.
- 실험 실행 중에는 `reset()`을 호출하지 않습니다. `reset()`과 `reserve()`를 각각 스레드 안전하게 구현해도 두 작업을 동시에 수행했을 때 어느 결과가 최종 상태여야 하는지는 별도의 비즈니스 규칙이 필요하기 때문입니다.

수량 검증은 다음처럼 공통으로 적용합니다.

```java
private void validateQuantity(int quantity) {
    if (quantity < 1) {
        throw new IllegalArgumentException(
                "quantity는 1 이상이어야 합니다."
        );
    }
}
```

#### `UnsafeStockStrategy`

의도적으로 어떠한 동기화도 사용하지 않습니다.

```java
private int quantity;

@Override
public int getRemainingQuantity() {
    return quantity;
}

@Override
public void reset(int quantity) {
    validateQuantity(quantity);
    this.quantity = quantity;
}
```

다른 스레드가 변경한 값이 즉시 보인다는 보장이 없습니다. 이 가시성 문제도 동기화되지 않은 전략의 특성에 포함됩니다.

#### `VolatileStockStrategy`

재고 필드를 `volatile`로 선언하고 단일 읽기와 쓰기를 그대로 사용합니다.

```java
private volatile int quantity;

@Override
public int getRemainingQuantity() {
    return quantity;
}

@Override
public void reset(int quantity) {
    validateQuantity(quantity);
    this.quantity = quantity;
}
```

단일 읽기와 쓰기의 가시성은 보장되지만 `reserve()`의 조건 확인과 차감을 하나의 원자적 연산으로 만들지는 못합니다.

#### `SynchronizedStockStrategy`

세 메서드가 모두 같은 인스턴스 모니터를 사용해야 합니다.

```java
private int quantity;

@Override
public synchronized int getRemainingQuantity() {
    return quantity;
}

@Override
public synchronized void reset(int quantity) {
    validateQuantity(quantity);
    this.quantity = quantity;
}
```

`reserve()`는 `synchronized(this)`를 사용하면서 조회나 초기화는 다른 락 객체를 사용하면 같은 공유 상태를 보호하지 못합니다. 별도의 락 객체를 선택했다면 세 메서드 모두 반드시 그 객체를 사용합니다.

#### `ReentrantLockStockStrategy`

`reserve()`와 동일한 `ReentrantLock`으로 조회와 초기화를 보호하고, 락 해제는 항상 `finally`에서 수행합니다.

```java
private final Lock lock = new ReentrantLock();
private int quantity;

@Override
public int getRemainingQuantity() {
    lock.lock();

    try {
        return quantity;
    } finally {
        lock.unlock();
    }
}

@Override
public void reset(int quantity) {
    validateQuantity(quantity);
    lock.lock();

    try {
        this.quantity = quantity;
    } finally {
        lock.unlock();
    }
}
```

#### `AtomicStockStrategy`

원자 변수 자체가 공유 상태이므로 조회에는 `get()`, 초기화에는 `set()`을 사용합니다.

```java
private final AtomicInteger quantity = new AtomicInteger();

@Override
public int getRemainingQuantity() {
    return quantity.get();
}

@Override
public void reset(int quantity) {
    validateQuantity(quantity);
    this.quantity.set(quantity);
}
```

`set()`과 CAS 기반 `reserve()`는 각각 원자적이지만, 실험 중 `reset()`을 허용한다는 뜻은 아닙니다. 실험 시작 전에 초기화하고 모든 예약 작업이 끝난 다음 결과를 조회합니다.

### 성공한 예약 수의 관리 위치

`StockStrategy`는 재고 수량을 어떤 방식으로 보호하는지 비교하는 역할만 담당합니다. `successfulReservations` 필드를 각 전략에 추가하면 재고와 카운터라는 복수 공유 변수를 동시에 보호해야 하므로 초기 학습 목표가 흐려질 수 있습니다.

따라서 성공 횟수는 다음처럼 관리합니다.

- `ConcurrencyExperimentRunner`는 각 작업의 `reserve()` 반환값을 수집하여 `true`의 개수를 `successCount`로 계산합니다.
- 실행 중 여러 스레드가 일반 `int` 카운터에 `++`하지 않습니다. `Future` 결과를 작업 종료 후 단일 스레드에서 집계하거나, 필요한 경우 `AtomicInteger`를 사용합니다.
- `ExperimentResult.successCount`는 해당 실험에서 성공한 횟수입니다.
- `GET /stocks`의 `successfulReservations`를 유지하려면 전략 외부의 실행 서비스가 성공한 단일 예약을 집계합니다. 이 값은 재고 전략의 정확성을 판정하는 근거로 사용하지 않고, 실험의 불변식은 실행기가 수집한 결과로 검사합니다.

### 웹 계층과 전략의 연결 구조

`StockController`에서 `StockStrategy`를 직접 주입받아 호출하지 않습니다. 컨트롤러는 HTTP 요청을 Java 객체로 변환하고 성공 응답을 반환하는 얇은 웹 어댑터로 유지합니다.

의존 방향은 다음과 같습니다.

```text
StockController
├── StockService
│   └── StockStrategyRegistry
│       └── StockStrategy 구현체
└── ConcurrencyExperimentRunner
    └── StockStrategyRegistry
        └── StockStrategy 구현체
```

각 구성 요소의 책임은 다음과 같습니다.

| 구성 요소 | 책임 |
| --- | --- |
| `StockController` | 요청 DTO 검증, 서비스 또는 실행기 호출, 성공 응답 DTO 반환 |
| `StockService` | 단일 초기화·조회·예약 유스케이스 실행, 예약 실패를 비즈니스 예외로 변환 |
| `ConcurrencyExperimentRunner` | 전략 선택, 실험 전 초기화, 스레드 생성과 종료 대기, 성공 횟수 및 불변식 계산 |
| `StockStrategyRegistry` | `StrategyType`에 해당하는 전략 구현체 반환 |
| `StockStrategy` | 하나의 재고 수량에 대한 예약·조회·초기화와 전략별 동기화 방식 구현 |
| `@RestControllerAdvice` | 서비스에서 발생한 예외를 `400`, `409`, `500` 오류 응답으로 변환 |

#### 컨트롤러의 구현 범위

컨트롤러에는 다음 로직을 넣지 않습니다.

- `if (remainingQuantity < quantity)`와 같은 재고 조건 검사
- `reserve()` 결과에 따른 재고 계산
- 전략 이름을 비교하는 `switch` 문
- 스레드 생성, `join()`, `CountDownLatch` 대기
- 성공 횟수와 불변식 계산
- 오류 응답을 직접 생성하는 `try-catch`

컨트롤러는 다음처럼 서비스와 실행기에 위임합니다.

```java
@RestController
public class StockController {

    private final StockService stockService;
    private final ConcurrencyExperimentRunner experimentRunner;

    public StockController(
            StockService stockService,
            ConcurrencyExperimentRunner experimentRunner
    ) {
        this.stockService = stockService;
        this.experimentRunner = experimentRunner;
    }

    @PostMapping("/stocks/reset")
    public ResetStockResponse resetStocks(
            @Valid @RequestBody ResetStockRequest request
    ) {
        return stockService.reset(request.quantity());
    }

    @GetMapping("/stocks")
    public StockResponse getStocks() {
        return stockService.getStock();
    }

    @PostMapping("/stocks/reserve")
    public ReserveStockResponse reserveStocks(
            @Valid @RequestBody ReserveStockRequest request
    ) {
        return stockService.reserve(request.quantity());
    }

    @PostMapping("/experiments/run")
    public ExperimentResult runExperiments(
            @Valid @RequestBody ExperimentRequest request
    ) {
        return experimentRunner.run(request);
    }
}
```

#### `StockService`의 구현 범위

`StockService`는 `/stocks/reset`, `/stocks`, `/stocks/reserve`에서 사용하는 단일 요청 유스케이스를 담당합니다. 예약 실패는 개별 API 요청을 완료하지 못한 것이므로 여기서 `InsufficientStockException`으로 변환합니다.

```java
@Service
public class StockService {

    private final StockStrategy defaultStrategy;
    private final AtomicLong successfulReservations = new AtomicLong();

    public StockService(StockStrategyRegistry registry) {
        this.defaultStrategy = registry.get(StrategyType.UNSAFE);
    }

    public ResetStockResponse reset(int quantity) {
        defaultStrategy.reset(quantity);
        successfulReservations.set(0);
        return new ResetStockResponse(quantity);
    }

    public StockResponse getStock() {
        return new StockResponse(
                (long) defaultStrategy.getRemainingQuantity(),
                successfulReservations.get()
        );
    }

    public ReserveStockResponse reserve(int quantity) {
        if (!defaultStrategy.reserve(quantity)) {
            throw new InsufficientStockException();
        }

        successfulReservations.incrementAndGet();
        return new ReserveStockResponse(true);
    }
}
```

`successfulReservations`는 전략 외부에서 단일 예약 API의 성공 횟수를 관리하는 보조 카운터입니다. 일반 `long`에 동시 `++`를 하지 않고 `AtomicLong`으로 집계합니다. 이 카운터는 전략의 정확성을 판정하는 데 사용하지 않습니다. 재고와 성공 카운터를 서로 다른 원자 연산으로 읽기 때문에 `GET /stocks`의 두 필드는 완전히 원자적인 하나의 스냅샷은 아닙니다. 정확성 비교는 반드시 실행기가 모든 작업 종료 후 수집한 `reserve()` 반환값과 남은 재고로 수행합니다.

#### 일반 재고 API가 사용하는 전략

현재 `/stocks/reset`, `/stocks`, `/stocks/reserve` 요청에는 `strategy` 필드가 없습니다. 따라서 이 세 API는 `StockService`에 설정된 하나의 기본 전략만 사용합니다.

- 첫 학습 단계의 기본값은 `UNSAFE`로 둡니다.
- 다른 전략을 수동으로 확인할 때는 서비스의 기본 전략 설정을 변경합니다.
- 마지막으로 실행한 실험의 전략을 전역 `activeStrategy`로 저장하여 암묵적으로 변경하지 않습니다. 동시에 들어온 요청이 어떤 전략을 사용했는지 불명확해지기 때문입니다.
- 기본 전략이 아닌 전략의 실험 결과는 `POST /experiments/run`의 응답으로 확인합니다.
- 향후 일반 재고 API에서도 전략을 선택해야 한다면 요청 계약에 `strategy`를 명시적으로 추가합니다. 요청에 없는 값을 컨트롤러가 추측하지 않습니다.

기본 전략을 코드에 하드코딩하지 않고 설정으로 바꾸는 것은 선택 사항입니다. 초기 학습에서는 구조를 복잡하게 만들지 않기 위해 `StrategyType.UNSAFE`와 같은 명시적인 기본값으로 시작해도 충분합니다.

#### `ConcurrencyExperimentRunner`의 구현 범위

실험 실행기는 요청의 `strategy`를 이용해 전략을 직접 선택하고 호출합니다. 이것은 전략 실행과 비교가 실행기의 핵심 책임이므로 컨트롤러에서 전략을 직접 사용하는 것과는 다릅니다.

```java
public ExperimentResult run(ExperimentRequest request) {
    StockStrategy strategy = registry.get(request.strategy());

    strategy.reset(100);

    // 작업 시작 시점을 맞춘다.
    // 여러 스레드에서 strategy.reserve(1)을 실행한다.
    // 모든 작업의 종료를 기다린다.
    // 반환값을 집계하고 불변식을 계산한다.
}
```

초기 요청 DTO에 `initialQuantity`가 없으므로 각 실험은 전략을 `100`으로 초기화한 뒤 시작합니다. 이렇게 해야 이전 실행 결과가 다음 실험에 영향을 주지 않고 전략별 결과를 같은 조건에서 비교할 수 있습니다. 실험 도중에는 `/stocks/reset`을 호출하지 않는 것을 전제로 합니다.

개별 예약 API와 실험 실행기의 실패 처리는 구분합니다.

```text
StockService.reserve()
reserve() == false
→ InsufficientStockException
→ 409 Conflict

ConcurrencyExperimentRunner
reserve() == false
→ 정상적인 실패 결과로 집계
→ ExperimentResult.successCount에 반영
→ 실험 자체는 200 OK
```

#### 여러 전략 구현체의 선택

여러 구현체를 모두 Spring Bean으로 등록하면 다음 주입은 후보가 여러 개라서 모호해집니다.

```java
public StockService(StockStrategy stockStrategy) {
}
```

컨트롤러나 서비스에서 `@Qualifier`와 전략별 분기문을 반복하지 않고 `StockStrategyRegistry` 한 곳에서 매핑합니다.

```java
@Component
public class StockStrategyRegistry {

    private final Map<StrategyType, StockStrategy> strategies;

    public StockStrategyRegistry(
            UnsafeStockStrategy unsafe,
            VolatileStockStrategy volatileStrategy,
            SynchronizedStockStrategy synchronizedStrategy,
            ReentrantLockStockStrategy reentrantLock
    ) {
        this.strategies = Map.of(
                StrategyType.UNSAFE, unsafe,
                StrategyType.VOLATILE, volatileStrategy,
                StrategyType.SYNCHRONIZED, synchronizedStrategy,
                StrategyType.REENTRANT_LOCK, reentrantLock
        );
    }

    public StockStrategy get(StrategyType type) {
        StockStrategy strategy = strategies.get(type);

        if (strategy == null) {
            throw new InvalidStrategyException(type);
        }

        return strategy;
    }
}
```

전략 구현체는 상태를 보유하고 여러 요청 스레드가 같은 인스턴스에 접근해야 하므로 Spring 싱글턴 빈으로 사용합니다. 요청마다 새 전략 객체를 만들면 재고가 공유되지 않아 동시성 실험의 의미가 사라집니다.

이 프로젝트에서는 별도의 Repository, Facade, UseCase 계층을 추가하지 않습니다. `Controller → Service 또는 ExperimentRunner → StrategyRegistry → Strategy` 정도면 역할 분리와 학습 목적을 모두 충족합니다.

---

## 5. 단계별 학습 순서

### 0단계: 단일 스레드 기준 구현

먼저 동시성 없이 정상 동작하는 재고 차감 코드를 만듭니다.

```java
public boolean reserve(int requestedQuantity) {
    if (quantity < requestedQuantity) {
        return false;
    }

    quantity -= requestedQuantity;
    return true;
}
```

단일 스레드 테스트를 작성합니다.

```text
재고 100
1개씩 100회 예약
성공 100회
남은 재고 0
```

이 단계에서는 스레드를 사용하지 않습니다.

#### 학습 목표

- 비즈니스 불변식 정의
- 동시성 버그와 일반 로직 버그 분리
- 테스트 가능한 구조 만들기

---

### 1단계: `Thread` 직접 사용

`Thread`와 `Runnable`을 직접 사용하여 동시에 예약을 실행합니다.

학습할 내용:

- `Thread`
- `Runnable`
- `start()`와 `run()`의 차이
- `join()`
- 현재 스레드 이름
- 스레드 상태
- 인터럽트
- 데몬 스레드

실험:

```text
스레드 10개
각 스레드가 10번씩 예약
총 100번 예약
```

로그에 반드시 스레드 이름을 기록합니다.

```java
log.info(
    "thread={}, remaining={}",
    Thread.currentThread().getName(),
    quantity
);
```

#### 확인할 문제

- 실행 순서는 매번 달라지는가?
- 메인 스레드가 `join()`을 하지 않으면 어떻게 되는가?
- 인터럽트된 스레드는 어떻게 종료해야 하는가?
- 스레드를 매번 생성하는 것은 어떤 비용이 있는가?

---

### 2단계: Race Condition 재현

동기화하지 않은 `UnsafeStockStrategy`를 여러 스레드가 공유하게 합니다.

재고 감소 사이에 인위적인 지연을 넣으면 문제를 쉽게 재현할 수 있습니다.

```java
int current = quantity;

Thread.yield();

quantity = current - requestedQuantity;
```

또는 학습용으로 아주 짧은 `sleep()`을 넣을 수 있습니다.

예상되는 잘못된 결과:

```text
최초 재고: 100
성공한 예약: 100
남은 재고: 7
```

여러 스레드가 같은 값을 읽고 같은 결과를 저장하면서 갱신 일부가 사라진 것입니다. 이를 lost update라고 합니다.

#### 학습 목표

- Race condition
- 임계 구역
- 공유 가변 상태
- 원자성
- 가시성
- 순서 보장
- happens-before 개념

---

### 3단계: `volatile`의 한계

재고 필드를 다음과 같이 변경합니다.

```java
private volatile int quantity;
```

그리고 같은 실험을 반복합니다.

여전히 문제가 발생해야 합니다.

```java
quantity--;
```

이 연산은 개념적으로 다음 여러 단계입니다.

```text
quantity 읽기
1 빼기
quantity에 쓰기
```

`volatile`은 값의 가시성을 제공하지만, 이 복합 연산 전체를 하나의 원자적 연산으로 만들지는 않습니다.

#### 별도 실험

`volatile boolean running`을 사용한 작업 종료 플래그도 구현합니다.

```java
while (running) {
    doWork();
}
```

이렇게 하면 `volatile`이 적합한 경우와 부적합한 경우를 함께 확인할 수 있습니다.

---

### 4단계: `synchronized`

재고 차감 메서드에 `synchronized`를 적용합니다.

```java
public synchronized boolean reserve(int requestedQuantity) {
    if (quantity < requestedQuantity) {
        return false;
    }

    quantity -= requestedQuantity;
    return true;
}
```

다음 세 방법을 각각 구현해 봅니다.

```java
public synchronized boolean reserve(...)
```

```java
synchronized (this) {
    // critical section
}
```

```java
synchronized (lockObject) {
    // critical section
}
```

#### 비교할 사항

- 메서드 전체를 잠그는 경우
- 실제 임계 구역만 잠그는 경우
- 인스턴스 락과 클래스 락
- 같은 객체를 잠가야 하는 이유
- 락 범위가 성능에 미치는 영향
- 재진입성

Spring의 기본 빈은 싱글턴이므로, 상태를 가진 서비스 빈을 여러 요청 스레드가 동시에 사용할 수 있다는 점도 이 단계에서 확인합니다.

---

### 5단계: `ReentrantLock`

같은 기능을 `ReentrantLock`으로 다시 구현합니다.

```java
private final Lock lock = new ReentrantLock();

public boolean reserve(int requestedQuantity) {
    lock.lock();

    try {
        if (quantity < requestedQuantity) {
            return false;
        }

        quantity -= requestedQuantity;
        return true;
    } finally {
        lock.unlock();
    }
}
```

`ReentrantLock`은 `synchronized`와 기본적인 상호 배제 의미는 비슷하지만, `tryLock()`, 인터럽트 가능한 락 획득, 공정성 설정, `Condition` 등의 추가 기능을 제공합니다. 락 해제는 반드시 `finally`에서 실행해야 합니다. ([Oracle Docs][2])

다음 실험을 진행합니다.

#### `tryLock()`

```java
if (!lock.tryLock()) {
    return false;
}
```

락을 기다리지 않고 즉시 실패하게 합니다.

#### 타임아웃

```java
if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
    return false;
}
```

#### 인터럽트 가능한 획득

```java
lock.lockInterruptibly();
```

#### 공정 락

```java
new ReentrantLock(true);
```

공정 락은 오래 기다린 스레드를 우선하지만, 처리량이 감소할 수 있습니다. ([Oracle Docs][2])

---

### 6단계: 원자 클래스와 CAS

재고를 `AtomicInteger`로 변경합니다.

단순히 다음처럼 작성하면 부족할 수 있습니다.

```java
quantity.decrementAndGet();
```

재고가 0보다 작아지면 안 되기 때문입니다. 따라서 CAS 루프를 구현합니다.

```java
public boolean reserve(int requestedQuantity) {
    while (true) {
        int current = quantity.get();

        if (current < requestedQuantity) {
            return false;
        }

        int next = current - requestedQuantity;

        if (quantity.compareAndSet(current, next)) {
            return true;
        }
    }
}
```

`AtomicInteger`는 원자적으로 갱신 가능한 정수이며, 원자적 카운터 같은 용도에 적합합니다. ([Oracle Docs][3])

#### 학습 목표

- CAS
- 낙관적 동시성 제어
- lock-free의 의미
- CAS 재시도 비용
- ABA 문제 개념
- 단일 변수 원자성과 복수 변수 불변식의 차이

#### 추가 비교

- `AtomicInteger`
- `LongAdder`
- `synchronized`
- `ReentrantLock`

`LongAdder`는 통계 카운터에는 적합하지만, 정확한 현재 재고를 조건 검사와 함께 갱신하는 용도로는 적합하지 않다는 차이를 확인합니다.

---

### 7단계: 스레드 풀과 `ExecutorService`

이제 직접 `Thread`를 생성하는 코드를 `ExecutorService`로 변경합니다.

```java
ExecutorService executor =
    Executors.newFixedThreadPool(10);
```

학습할 내용:

- `Executor`
- `ExecutorService`
- `submit()`
- `Future`
- `shutdown()`
- `shutdownNow()`
- `awaitTermination()`
- 고정 스레드 풀
- 캐시 스레드 풀
- 단일 스레드 실행기
- 작업 큐
- 거절 정책
- CPU 작업과 I/O 작업의 풀 크기 차이

직접 `ThreadPoolExecutor`도 한 번 구성합니다.

```java
new ThreadPoolExecutor(
    4,
    8,
    30,
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(100),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

#### 반드시 실험할 것

```text
corePoolSize = 2
maximumPoolSize = 4
queueCapacity = 10
작업 수 = 1,000
```

관찰 대상:

- 큐가 언제 차는가?
- 스레드 수는 언제 증가하는가?
- 거절 정책이 언제 실행되는가?
- 무제한 큐가 위험한 이유는 무엇인가?
- 작업 제출 속도와 처리 속도가 다르면 어떻게 되는가?

---

### 8단계: 동시성 테스트 도구

동시성 테스트에서 단순히 여러 작업을 제출하는 것만으로는 충분하지 않습니다. 작업 시작 시점이 달라져 경쟁이 약해질 수 있습니다.

`CountDownLatch`로 시작 시점을 맞춥니다.

```java
CountDownLatch ready = new CountDownLatch(threadCount);
CountDownLatch start = new CountDownLatch(1);
CountDownLatch done = new CountDownLatch(threadCount);
```

각 작업:

```java
ready.countDown();
start.await();

try {
    strategy.reserve(1);
} finally {
    done.countDown();
}
```

실행 측:

```java
ready.await();
start.countDown();
done.await();
```

추가로 학습합니다.

- `CountDownLatch`
- `CyclicBarrier`
- `Semaphore`
- `Phaser`

#### 활용 예

| 도구               | 프로젝트에서의 용도          |
| ---------------- | ------------------- |
| `CountDownLatch` | 여러 예약 작업의 시작 시점 동기화 |
| `CyclicBarrier`  | 반복 실험 단계 맞추기        |
| `Semaphore`      | 동시에 실행 가능한 예약 수 제한  |
| `Phaser`         | 여러 단계로 구성된 복잡한 실험   |
| `BlockingQueue`  | 생산자-소비자 구현          |

---

### 9단계: 교착 상태

재고 하나만으로는 교착 상태를 만들기 어렵습니다. 이 단계에서만 창고 두 개를 추가합니다.

```text
창고 A
창고 B
```

기능:

```text
A에서 B로 재고 이동
B에서 A로 재고 이동
```

잘못된 구현:

```text
스레드 1: A 잠금 → B 잠금
스레드 2: B 잠금 → A 잠금
```

두 스레드가 서로의 락을 기다리면서 교착 상태가 발생합니다.

#### 해결 방법

1. 락 획득 순서를 고정합니다.
2. `tryLock()`과 타임아웃을 사용합니다.
3. 중첩 락 자체를 제거합니다.
4. 더 큰 단위의 단일 락을 사용합니다.

#### 함께 학습할 개념

- Deadlock
- Livelock
- Starvation
- Lock ordering
- Thread dump
- `jstack`
- IntelliJ thread dump

이 단계에서는 실제로 교착 상태를 만든 후 스레드 덤프에서 다음 상태를 확인하는 것이 중요합니다.

```text
BLOCKED
waiting to lock
locked
```

---

### 10단계: 읽기/쓰기 락

재고 조회가 매우 많고 수정은 적다는 상황을 가정합니다.

다음을 비교합니다.

- `synchronized`
- `ReentrantReadWriteLock`
- `StampedLock`

실험 비율:

```text
조회 10,000회
수정 100회
```

`StampedLock`은 낙관적 읽기를 실험하는 정도로만 다루고, 기본 학습에서는 `ReentrantReadWriteLock`을 먼저 이해하는 것이 좋습니다.

주의할 점은 읽기/쓰기 락이 항상 더 빠른 것은 아니라는 것입니다. 임계 구역이 작거나 경합이 낮으면 관리 비용이 더 클 수 있습니다.

---

## 6. Spring 비동기 처리

### 11단계: `@Async`

예약이 성공한 후 감사 로그를 비동기로 저장한다고 가정합니다.

```java
@Async("reservationExecutor")
public CompletableFuture<Void> writeAuditLog(...) {
    // artificial I/O delay
}
```

실제 파일이나 외부 시스템은 사용하지 않습니다. `Thread.sleep()`으로 I/O 대기만 흉내 냅니다.

학습할 내용:

- `@EnableAsync`
- `@Async`
- `TaskExecutor`
- 스레드 풀 설정
- 예외 처리
- 반환 타입
- 프록시 기반 호출
- 동일 클래스 내부 호출 문제
- 트랜잭션 컨텍스트 전달 여부
- `ThreadLocal` 전달 문제

Spring은 `TaskExecutor`를 비동기 작업 실행의 추상화로 제공하며, Spring Boot는 별도 실행기가 없다면 비동기 작업용 실행기를 자동 구성합니다. ([Home][4])

여기서 중요한 비교는 다음입니다.

```text
직접 ExecutorService 사용
Spring TaskExecutor 사용
@Async 사용
```

---

### 12단계: `CompletableFuture`

하나의 예약 요청에서 세 작업을 동시에 실행한다고 가정합니다.

```text
상품 정보 조회
회원 등급 조회
배송 가능 여부 조회
```

실제 외부 API는 만들지 않고 각 작업이 임의의 값을 반환하도록 합니다.

학습할 내용:

- `supplyAsync()`
- `runAsync()`
- `thenApply()`
- `thenCompose()`
- `thenCombine()`
- `allOf()`
- `exceptionally()`
- `handle()`
- 타임아웃
- 취소
- 기본 ForkJoinPool 사용의 주의점
- 명시적 Executor 전달

예:

```java
CompletableFuture<Product> productFuture =
    CompletableFuture.supplyAsync(this::loadProduct, executor);

CompletableFuture<Member> memberFuture =
    CompletableFuture.supplyAsync(this::loadMember, executor);

return productFuture.thenCombine(
    memberFuture,
    ReservationContext::new
);
```

여기서는 공유 상태의 안전성보다는 비동기 작업의 조합, 실패 전파, 타임아웃을 학습합니다.

---

## 7. 가상 스레드

### 13단계: 플랫폼 스레드와 가상 스레드 비교

동일한 I/O 모방 작업을 다음 두 실행기로 비교합니다.

```java
Executors.newFixedThreadPool(100);
```

```java
Executors.newVirtualThreadPerTaskExecutor();
```

Spring Boot에서는 Java 21 이상에서 다음 설정으로 가상 스레드 기반 실행기를 사용할 수 있습니다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

([Home][5])

실험:

```text
작업 10,000개
각 작업은 100ms 대기
```

측정:

- 전체 처리 시간
- 생성된 플랫폼 스레드 수
- 메모리 사용량
- 동시 처리량
- CPU 사용량

가상 스레드는 주로 I/O 대기가 많은 작업의 처리량을 높이기 위한 것이며, CPU 집약적 작업을 더 빠르게 실행하는 기술은 아닙니다. 또한 락이나 공유 상태 문제를 해결해주지도 않습니다. ([Oracle Docs][6])

즉 다음 두 문제는 별개입니다.

```text
어떻게 많은 작업을 동시에 실행할 것인가?
→ 플랫폼 스레드, 스레드 풀, 가상 스레드

공유 상태를 어떻게 안전하게 보호할 것인가?
→ synchronized, Lock, Atomic, 불변 객체
```

---

## 8. 데이터베이스 락

### 14단계: H2와 JPA 추가

인메모리 락 학습이 끝난 뒤에만 추가합니다.

추가 의존성:

```text
Spring Data JPA
H2 Database
```

이 단계에서 학습합니다.

- JPA 낙관적 락
- JPA 비관적 락
- `@Version`
- 트랜잭션 격리 수준
- lost update
- 재시도
- 데드락
- 데이터베이스 타임아웃

#### 낙관적 락

```java
@Version
private Long version;
```

#### 비관적 락

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<StockEntity> findById(Long id);
```

#### 반드시 구분할 것

```text
synchronized / ReentrantLock
→ 현재 JVM 프로세스 안에서만 유효

DB 비관적 락 / 낙관적 락
→ 여러 애플리케이션 인스턴스가 같은 DB를 사용할 때도 유효
```

따라서 인메모리 락으로 성공한 코드도 애플리케이션을 두 개 실행하면 안전하지 않을 수 있습니다.

또한 `@Transactional` 자체는 Java 객체에 대한 상호 배제 락이 아닙니다.

---

## 9. 권장 학습 우선순위

### 반드시 구현

1. `Thread`, `Runnable`, `join`, interrupt
2. Race condition
3. 가시성, 원자성, happens-before
4. `volatile`
5. `synchronized`
6. `ReentrantLock`
7. `AtomicInteger`, CAS
8. `ExecutorService`, `ThreadPoolExecutor`
9. `CountDownLatch`
10. Deadlock과 lock ordering
11. `CompletableFuture`
12. Spring `@Async`
13. 가상 스레드

### 구현하거나 간단히 실험

- `Semaphore`
- `BlockingQueue`
- `ConcurrentHashMap`
- `ReentrantReadWriteLock`
- `LongAdder`
- `ThreadLocal`
- `Condition`

### 마지막에 선택적으로 학습

- `StampedLock`
- JPA 낙관적/비관적 락
- JVM 두 개를 실행한 분산 동시성 실험
- Scoped Values
- Structured Concurrency

Java 25의 `StructuredTaskScope`는 현재도 프리뷰 API이므로, 기본 프로젝트 범위에는 포함하지 않고 가상 스레드와 `CompletableFuture`를 학습한 뒤 선택적으로 보는 것이 적절합니다. ([Oracle Docs][7])

---

## 10. 테스트 작성 원칙

동시성 테스트는 일반 테스트보다 비결정적입니다. 다음 원칙을 적용합니다.

### 시작 시점을 강제로 맞춘다

`CountDownLatch` 또는 `CyclicBarrier`를 사용합니다.

### 한 번만 실행하지 않는다

```java
@RepeatedTest(100)
void concurrentReservationTest() {
}
```

다만 반복 횟수를 늘리는 것만으로 정확성을 증명할 수는 없습니다. 실패 가능성을 높이는 보조 수단으로 사용합니다.

### `sleep()`으로 동기화하지 않는다

다음 코드는 피합니다.

```java
executor.submit(task);
Thread.sleep(1000);
assertEquals(...);
```

작업 완료는 `Future.get()`, `CountDownLatch`, `awaitTermination()` 등으로 기다립니다.

### 결과가 아니라 불변식을 검사한다

```java
assertThat(remainingQuantity).isGreaterThanOrEqualTo(0);

assertThat(successCount + remainingQuantity)
    .isEqualTo(initialQuantity);
```

### 실행 시간을 정확성보다 먼저 보지 않는다

우선순위는 다음과 같습니다.

```text
1. 정확성
2. 종료와 실패 처리
3. 코드 이해 가능성
4. 성능
```

---

## 11. 구현 전략 비교표

각 실험 후 다음 표를 README에 기록합니다.

| 전략              | 결과 정확성 |      블로킹 |  타임아웃 |  인터럽트 | 다중 JVM | 특징             |
| --------------- | -----: | -------: | ----: | ----: | -----: | -------------- |
| 동기화 없음          |     실패 |       없음 |    없음 |    없음 |     실패 | Race condition |
| `volatile`      |     실패 |       없음 |    없음 |    없음 |     실패 | 가시성만 제공        |
| `synchronized`  |     성공 |       있음 |   제한적 |   제한적 |     실패 | 가장 단순한 락       |
| `ReentrantLock` |     성공 |       있음 |    가능 |    가능 |     실패 | 세밀한 락 제어       |
| `AtomicInteger` |     성공 |  CAS 재시도 | 해당 없음 | 해당 없음 |     실패 | 단일 값에 적합       |
| JPA 낙관적 락       |     성공 | 충돌 시 재시도 |    가능 | 해당 없음 |     성공 | 충돌이 적을 때 유리    |
| JPA 비관적 락       |     성공 |       있음 |    가능 | 해당 없음 |     성공 | 충돌이 많을 때 명확    |

---

## 12. 불필요한 기능

프로젝트 목적을 흐리므로 다음은 제외하는 것이 좋습니다.

- Spring Security
- JWT
- 회원가입
- 프론트엔드
- Docker
- Redis
- Kafka
- 실제 결제
- 외부 API
- 복잡한 예외 응답 규격
- 복잡한 계층형 아키텍처
- QueryDSL
- 테스트 컨테이너
- 마이크로서비스
- WebFlux

DB 락 단계 전까지는 JPA도 제외합니다. Lombok 역시 공유 상태와 생성자 구조를 명확하게 보기 위해 사용하지 않는 편이 낫습니다.

---

## 13. 권장 진행 단위

각 단계는 다음 순서로 진행합니다.

```text
1. 실패할 것이라는 가설 작성
2. 동시성 테스트 작성
3. 실제 실패 재현
4. 원인 설명
5. 동기화 기술 적용
6. 테스트 성공 확인
7. 장단점 기록
8. 다음 전략과 비교
```

커밋도 전략별로 나눕니다.

```text
feat: add unsafe stock reservation
test: reproduce lost update
feat: protect stock with synchronized
feat: replace monitor with reentrant lock
feat: implement CAS stock reservation
feat: add bounded thread pool experiment
feat: reproduce and resolve deadlock
feat: compare platform and virtual threads
```

### 가장 먼저 구현할 범위

첫 번째 목표는 아래 네 구현체와 하나의 테스트 실행기입니다.

```text
UnsafeStockStrategy
VolatileStockStrategy
SynchronizedStockStrategy
ReentrantLockStockStrategy
ConcurrencyExperimentRunner
```

이 범위만 완성해도 Race condition, 가시성, 원자성, 임계 구역, 모니터 락, 명시적 락을 실제 코드로 비교할 수 있습니다. 이후 `AtomicInteger`, 스레드 풀, 교착 상태, 비동기, 가상 스레드 순서로 확장하는 것이 가장 효율적입니다.

[1]: https://docs.spring.io/spring-boot/3.5/system-requirements.html "System Requirements :: Spring Boot"
[2]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html "ReentrantLock (Java SE 25 & JDK 25)"
[3]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html "AtomicInteger (Java SE 25 & JDK 25)"
[4]: https://docs.spring.io/spring-framework/reference/integration/scheduling.html?utm_source=chatgpt.com "Task Execution and Scheduling"
[5]: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html?utm_source=chatgpt.com "Task Execution and Scheduling"
[6]: https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html "Virtual Threads"
[7]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html "StructuredTaskScope (Java SE 25 & JDK 25)"
