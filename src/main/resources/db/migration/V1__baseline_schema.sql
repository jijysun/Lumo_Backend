-- ─────────────────────────────────────────────────────────────
-- V1 — 기준 스키마 (baseline)
--
-- Hibernate `hbm2ddl.auto: update` 가 만들어 온 스키마를 그대로 떠서 고정한 것이다.
-- 이 시점부터 스키마의 소유자는 Hibernate 가 아니라 Flyway 다 (M-2).
--
-- 재현 방법:
--   docker compose -f docker-compose-local.yml up -d --build spring
--   docker exec Lumo_MySQL_Local mysqldump -uroot -ppassword --       --no-data --compact --skip-comments --no-tablespaces lumo_test
--
-- ⚠️ member.email 의 유니크 제약은 여기 없다 — 기존 운영 스키마에 없었기 때문이다(M-13).
--    V2 가 중복 정리 후 추가한다.
-- ─────────────────────────────────────────────────────────────

SET FOREIGN_KEY_CHECKS = 0;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm` (
  `alarm_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `alarm_time` time(6) NOT NULL,
  `is_enabled` bit(1) DEFAULT NULL,
  `label` varchar(100) DEFAULT NULL,
  `sound_type` varchar(50) DEFAULT NULL,
  `vibration` bit(1) DEFAULT NULL,
  `volume` int DEFAULT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`alarm_id`),
  KEY `FK53dra8a3h29id86y823i3blxk` (`member_id`),
  CONSTRAINT `FK53dra8a3h29id86y823i3blxk` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm_log` (
  `log_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `dismiss_type` enum('MANUAL','MISSION','SNOOZE') DEFAULT NULL,
  `dismissed_at` datetime(6) DEFAULT NULL,
  `snooze_count` int DEFAULT NULL,
  `triggered_at` datetime(6) NOT NULL,
  `alarm_id` bigint NOT NULL,
  PRIMARY KEY (`log_id`),
  KEY `FKmtntob6vol2v77mjdqtj48efs` (`alarm_id`),
  CONSTRAINT `FKmtntob6vol2v77mjdqtj48efs` FOREIGN KEY (`alarm_id`) REFERENCES `alarm` (`alarm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm_mission` (
  `mission_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `difficulty` enum('EASY','HARD','MEDIUM') DEFAULT NULL,
  `mission_type` enum('MATH','NONE','OX_QUIZ','TYPING','WALK') NOT NULL,
  `question_count` int DEFAULT NULL,
  `walk_goal_meter` int DEFAULT NULL,
  `alarm_id` bigint NOT NULL,
  PRIMARY KEY (`mission_id`),
  UNIQUE KEY `UKhbtaew73t5e9gp2s2hiqvosu2` (`alarm_id`),
  CONSTRAINT `FKawlsi6serqqxbxn2xoa2ht2iu` FOREIGN KEY (`alarm_id`) REFERENCES `alarm` (`alarm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm_repeat_day` (
  `repeat_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `day_of_week` enum('FRI','MON','SAT','SUN','THU','TUE','WED') NOT NULL,
  `alarm_id` bigint NOT NULL,
  PRIMARY KEY (`repeat_id`),
  KEY `FK7h9qj6f4clqka9iek9ecb7g60` (`alarm_id`),
  CONSTRAINT `FK7h9qj6f4clqka9iek9ecb7g60` FOREIGN KEY (`alarm_id`) REFERENCES `alarm` (`alarm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm_snooze` (
  `snooze_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `interval_sec` int DEFAULT NULL,
  `is_enabled` bit(1) DEFAULT NULL,
  `max_count` int DEFAULT NULL,
  `alarm_id` bigint NOT NULL,
  PRIMARY KEY (`snooze_id`),
  UNIQUE KEY `UKh3efnewrsfpe9bch4lcvic9d0` (`alarm_id`),
  CONSTRAINT `FKm35m4nfe0ux2odqrfpk9mtkr8` FOREIGN KEY (`alarm_id`) REFERENCES `alarm` (`alarm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `encouragement` (
  `encouragement_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `content` varchar(255) NOT NULL,
  PRIMARY KEY (`encouragement_id`),
  UNIQUE KEY `UKghtngi3ma3nj76nys0bs02aw7` (`content`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `feedback` (
  `feedback_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `content` varchar(255) NOT NULL,
  `email` varchar(100) NOT NULL,
  `title` varchar(100) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`feedback_id`),
  KEY `FKmonjtjt92g6gruqyfumtmg8m8` (`member_id`),
  CONSTRAINT `FKmonjtjt92g6gruqyfumtmg8m8` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member` (
  `member_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `consecutive_success_cnt` int NOT NULL DEFAULT '0',
  `email` varchar(50) NOT NULL,
  `is_pro_upgraded` bit(1) NOT NULL DEFAULT b'0',
  `login` enum('GOOGLE','KAKAO','NAVER','NORMAL','NULL') NOT NULL,
  `mission_success_rate` int NOT NULL DEFAULT '0',
  `password` varchar(255) DEFAULT NULL,
  `role` enum('ADMIN','USER') DEFAULT NULL,
  `username` varchar(50) NOT NULL,
  `setting_member_setting_id` bigint DEFAULT NULL,
  `stat_member_stat_id` bigint DEFAULT NULL,
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `UK1idp26aidju371hekjikw10u9` (`setting_member_setting_id`),
  UNIQUE KEY `UKsfwambxkhpf031u00fdcmi2wl` (`stat_member_stat_id`),
  CONSTRAINT `FKsplvuq0hwuy6hf0j4pu5uupmb` FOREIGN KEY (`setting_member_setting_id`) REFERENCES `member_setting` (`member_setting_id`),
  CONSTRAINT `FKsql3jvmtx5hciw6714rc3fir4` FOREIGN KEY (`stat_member_stat_id`) REFERENCES `member_stat` (`member_stat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_device` (
  `member_device_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `device_name` varchar(255) NOT NULL,
  `model_name` varchar(255) NOT NULL,
  `os_version` varchar(255) NOT NULL,
  `uuid` varchar(255) NOT NULL,
  `member_id` bigint DEFAULT NULL,
  PRIMARY KEY (`member_device_id`),
  KEY `FKhpeu76bqinfvmrf6dvujdfk9j` (`member_id`),
  CONSTRAINT `FKhpeu76bqinfvmrf6dvujdfk9j` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_setting` (
  `member_setting_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `alarm_off_mission_default_duration` int DEFAULT NULL,
  `alarm_off_mission_default_level` enum('HIGH','LOW','MEDIUM') DEFAULT NULL,
  `alarm_off_mission_default_type` enum('DICTATION','MATH','OX') DEFAULT NULL,
  `battery_saving` bit(1) NOT NULL,
  `briefing_sentence` varchar(255) DEFAULT NULL,
  `briefing_voice_default_type` enum('AI','MAN','PRO','WOMAN') DEFAULT NULL,
  `language` enum('EN','JA','KO') NOT NULL,
  `smart_briefing` bit(1) NOT NULL DEFAULT b'0',
  `theme` enum('DARK','LIGHT','SYSTEM') NOT NULL,
  PRIMARY KEY (`member_setting_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_stat` (
  `member_stat_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `alarm_activate_count` int NOT NULL,
  `app_open_count` int NOT NULL,
  `mission_complete_count` int NOT NULL,
  PRIMARY KEY (`member_stat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mission_content` (
  `content_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `answer` varchar(200) NOT NULL,
  `difficulty` enum('EASY','HARD','MEDIUM') NOT NULL,
  `mission_type` enum('MATH','NONE','OX_QUIZ','TYPING','WALK') NOT NULL,
  `question` varchar(500) NOT NULL,
  PRIMARY KEY (`content_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mission_history` (
  `history_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `attempt_count` int DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `is_success` bit(1) NOT NULL,
  `mission_type` enum('MATH','NONE','OX_QUIZ','TYPING','WALK') NOT NULL,
  `alarm_id` bigint NOT NULL,
  PRIMARY KEY (`history_id`),
  KEY `FKje52w1t9htifr0a0y35u99gj2` (`alarm_id`),
  CONSTRAINT `FKje52w1t9htifr0a0y35u99gj2` FOREIGN KEY (`alarm_id`) REFERENCES `alarm` (`alarm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice` (
  `notice_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `active` bit(1) NOT NULL,
  `content` varchar(255) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `routine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `member_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK546lpheu7wdmjm1fj26wpyno2` (`member_id`),
  CONSTRAINT `FK546lpheu7wdmjm1fj26wpyno2` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subroutine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `is_success` bit(1) DEFAULT NULL,
  `success_count` int DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `routine_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKq3fuhsh004yv6e4acr85ptkxr` (`routine_id`),
  CONSTRAINT `FKq3fuhsh004yv6e4acr85ptkxr` FOREIGN KEY (`routine_id`) REFERENCES `routine` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `to_do` (
  `to_do_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `content` varchar(255) NOT NULL,
  `event_date` date NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`to_do_id`),
  KEY `FKhkphilai2887ode3kf3qnwbwv` (`member_id`),
  CONSTRAINT `FKhkphilai2887ode3kf3qnwbwv` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

SET FOREIGN_KEY_CHECKS = 1;
