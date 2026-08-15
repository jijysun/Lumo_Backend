package Lumo.lumo_backend.domain.member.repository;

import Lumo.lumo_backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);

    Optional<Member> findByEmailAndPassword(String email, String password);

    boolean existsByEmail(String mail);

    /** 부하 테스트 시딩용 — 이미 생성된 loadtest_ 계정 수를 센다 (LoadTestUserSeeder) */
    long countByEmailStartingWith(String emailPrefix);
}
