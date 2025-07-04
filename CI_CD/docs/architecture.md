# CATXI 프로젝트 아키텍처 문서

## 1. 기존 아키텍처 (As-Is)

### 1.1. 시스템 구성도

![image](https://github.com/user-attachments/assets/83d0900e-4661-40e0-b7cb-3be207f61b67)

```
EC2 #1: Nginx + Redis(Docker) + Chat Server
EC2 #2: 채팅 외 API Server
EC2 #3: Chat Server
```

### 1.2. 상세 구성

#### EC2 #1 (Load Balancer + Redis + Chat Server)
- **구성 요소**:
    - Nginx (Load Balancer): Port 80/443, `/api` -> EC2#2, `/ws`, `/chat` -> EC2#1, #3
    - Redis (Docker): Port 6379, 모든 서버의 중앙 Redis 역할
    - Spring Boot App: Port 8080, 채팅 서버 기능

#### EC2 #2 (General API Server)
- **구성 요소**:
    - Spring Boot App: Port 8080, General API, MySQL 및 Redis(EC2#1) 연결

#### EC2 #3 (Chat Server)
- **구성 요소**:
    - Spring Boot App: Port 8080, 채팅 전용 서버, MySQL 및 Redis(EC2#1) 연결

#### Nginx 설정 (`ip_hash`)
```nginx
upstream chatbackend {
    ip_hash;
    server localhost:8080;      # EC2 #1
    server 172.31.47.243:8080;  # EC2 #3
}
```
- **ip_hash**: 동일 클라이언트는 항상 같은 서버로 라우팅되어 부하 분산 효과가 제한적입니다.

---

## 2. 아키텍처 개선 (To-Be)

### 2.1. 개선된 시스템 구성도

![image](https://github.com/user-attachments/assets/88762d62-96fa-4113-a90a-ae3c53a214d2)

```
EC2 #1: Gateway & Load Balancer (Nginx)
EC2 #2: Business Logic Server (API)
EC2 #3: Chat Server #1
EC2 #4: Chat Server #2
ElastiCache: Redis (Pub/Sub, Caching)
```

### 2.2. 역할 재분배 및 개선 사항

#### EC2 #1: Gateway & Orchestration Server
- **역할**: 지능형 라우팅, SSL 터미네이션, Health Check, 정적 파일 서빙
- **개선**: Nginx 로드밸런싱 방식을 `least_conn`으로 변경하여 동적 부하 분산 및 리소스 효율성 증대

#### EC2 #2: Business Logic API Server
- **역할**: 핵심 비즈니스 로직, 채팅 관련 REST API, DB 트랜잭션, Redis 캐싱(세션)
- **개선**: 채팅 외 모든 비즈니스 로직을 전담하여 책임 명확화

#### EC2 #3 & #4: Real-time Communication Engine
- **역할**: WebSocket 연결 관리, Redis Pub/Sub 핸들링, SSE, 메시지 큐 처리
- **개선**: 실시간 통신 전용 서버로 분리하여 독립적인 확장성 확보

#### ElastiCache for Redis
- **역할**: Pub/Sub 메시지 브로커, 분산 캐싱
- **개선**: EC2에서 Redis를 분리하여 SPOF를 제거하고, 안정성과 확장성 확보

---

## 3. 아키텍처 회고

### 분산 모놀리스(Distributed Monolith) 구조
현재 CATXI 구조는 완전한 MSA가 아닌 **"분산 모놀리스"** 형태입니다. 모든 서비스가 동일한 데이터베이스(RDS)를 공유하기 때문입니다.

### 현 구조의 합리성
- **명확한 기능 분리**: 채팅과 일반 API의 역할이 명확히 분리되어 있습니다.
- **성능 최적화**: 트래픽 특성이 다른 서비스(채팅 vs API)를 분리하여 성능을 최적화했습니다.
- **독립적 확장성**: 채팅 트래픽 증가는 채팅 서버만, API 트래픽 증가는 API 서버만 스케일 아웃하면 됩니다.
- **장애 격리**: API 서버 장애가 채팅 기능에 영향을 주지 않으며, 그 반대도 마찬가지입니다.

### 결론: 현재 구조는 최적의 선택
프로젝트의 규모와 요구사항을 고려할 때, 무리한 MSA 전환보다 **목적에 맞는 적절한 분산 구조**를 채택한 것은 합리적이고 실용적인 선택입니다. 향후 서비스가 더 복잡해지면 데이터베이스 분리를 포함한 완전한 MSA로 점진적으로 발전시킬 수 있습니다.
