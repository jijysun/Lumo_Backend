<p align="center">
  <img src="./.github/image/lumo-image.png" width="55%">
</p>

# **💡 Lumo**

> **“깨우고, 움직이고, 변화한다. Wakes you up. Moves you forward.**

Lumo는 단순히 잠을 깨우는 알람을 넘어, 사용자가 하루의 첫 행동을 시작할 수 있도록 설계된 **AI 행동 코칭 미션 알람 서비스**입니다. 

침대 위에서의 관성을 끊어내고, AI 브리핑을 통해 명확한 하루의 시작을 돕습니다.

## 🚀 Key Features
1. **행동 기반 GPS 거리 미션**
- 기존 알람 앱들이 제공하는 단순한 문제 풀이나 흔들기 미션은 다시 잠들기 쉽습니다. 
- Lumo는 **다양한 기상 미션**을 통해 사용자가 실제로 침대 밖을 벗어나게 되며, 알람이 종료되는 '강제형 행동 구조'를 가집니다.

<br>

2. **AI 데일리 브리핑 (TTS)**
- 미션 성공 직후, 전날 설정한 **'오늘의 할 일(To-do)'** 리스트를 팝업으로 리마인드합니다. 동시에 AI가 해당 리스트를 기반으로 오늘의 일정을 음성(TTS)으로 브리핑하여, 사용자가 뇌를 깨우고 즉시 행동 모멘텀을 가질 수 있도록 코칭합니다.


## **🛠 Tech Stack**

### Language
<img src="https://img.shields.io/badge/spring-6DB33F?style=for-the-badge&logo=spring&logoColor=white"> 
<img src="https://img.shields.io/badge/springboot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white">
<img src="https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white">
<img src="https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=for-the-badge&logo=hibernate&logoColor=white">

### Database
<img src="https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white">
<img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white">

### DevOps & Infrastructure
<img src="https://img.shields.io/badge/Amazon%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white">
<img src="https://img.shields.io/badge/Amazon%20RDS-FF9900?style=for-the-badge&logo=amazonrds&logoColor=white">
<img src="https://img.shields.io/badge/Amazon%20ElastiCache-FF9900?style=for-the-badge&logo=amazonelasticache&logoColor=white">
<img src = "https://img.shields.io/badge/Docker%20Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white">
<img src="https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white">


### Monitoring & Observability
<img src="https://img.shields.io/badge/Grafana-E07A3F?style=for-the-badge&logo=grafana&logoColor=white">
<img src="https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white">
<img src="https://img.shields.io/badge/Grafana%20Loki-E07A3F?style=for-the-badge&logo=grafana&logoColor=white">
<img src="https://img.shields.io/badge/Grafana%20Promtail-E07A3F?style=for-the-badge&logo=grafana&logoColor=white">

### Collaboration & Documentation
<img src="https://img.shields.io/badge/github-181717?style=for-the-badge&logo=github&logoColor=white">
<img src="https://img.shields.io/badge/Notion-000000?style=for-the-badge&logo=notion&logoColor=white">
<img src="https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black">
<img src="https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white">





## **💎 Backend Core Values**

### **무중단 배포 (Blue-Green Deployment)**
- 사용자에게 24시간 끊김 없는 알람 서비스를 제공하기 위해 **Blue-Green 방식**을 채택했습니다. 
- 새로운 버전 배포 시 가동 중인 서버와 동일한 환경을 하나 더 구축하여 신/구 버전을 교체함으로써, 배포 시 발생하는 다운타임을 최소화 하였습니다

### Redis 기반 성능 최적화

- **캐싱:** 반복적인 쿼리 요청을 줄여 응답 속도를 향상했습니다.

- **변수 만료 로직:** Redis의 TTL(Time-To-Live) 기능을 활용하여 미션 상태값 및 임시 데이터를 효율적으로 관리하고 메모리 자원을 최적화했습니다.


### 비동기 처리 최적화

- Redis를 활용하여 비동기 처리 서비스에 트래픽 집중시 발생하는 동시성 문제를 해결하였습니다.

## 👥 Team Members

| 이름                                                                       | 역할                             | GitHub / Contact                                                    |
|--------------------------------------------------------------------------|--------------------------------|---------------------------------------------------------------------|
| <img src="https://github.com/jijysun.png" width="80" /> <br> 김석현 (빈)     | **BE T/L**, 배포, 인증, 사용자 API 담당 | [@jijysun](https://github.com/jijysun)   jijysun@gmail.com          |
| <img src="https://github.com/rudals02.png" width="80" /> <br> 이경민 (팔머)   | 알람 및 미션 API 담당                 | [@rudals02](https://github.com/rudals02) lkmm7460@gmail.com         |
| <img src="https://github.com/gusfhr777.png" width="80" /> <br> 이현록 (로기)  | 서비스 설정 및 환경 API 담당             | [@gusfhr777](https://github.com/gusfhr777) gusfhr777@gmail.com      |
| <img src="https://github.com/minseo6753.png" width="80" /> <br> 박민서 (도리) | 메인 기능 및 비즈니스 API 담당            | [@minseo6753](https://github.com/minseo6753)   minseo6753@naver.com |
