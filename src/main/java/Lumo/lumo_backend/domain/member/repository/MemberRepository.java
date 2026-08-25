package Lumo.lumo_backend.domain.member.repository;

import Lumo.lumo_backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);

    /*
     * (M-14) 호출부가 없는 죽은 메서드. BCrypt 도입 이전, 평문 비밀번호로 조회하던 설계의 잔재다.
     *
     * Spring Data JPA 는 선언만으로 쿼리를 생성하므로 실행되지는 않지만,
     * <b>"비밀번호로 회원을 조회한다"는 잘못된 사용법을 코드가 광고하는 상태</b>였다.
     * 해시된 비밀번호로는 매칭 자체가 불가능하므로 되살릴 여지도 없다.
     *
     * 삭제 대신 주석으로 남긴다 — 왜 없어졌는지가 이력으로 남아야 같은 메서드가 다시 추가되지 않는다.
     */
//    Optional<Member> findByEmailAndPassword(String email, String password);

    boolean existsByEmail(String mail);

    /** 부하 테스트 시딩용 — 이미 생성된 loadtest_ 계정 수를 센다 (LoadTestUserSeeder) */
    long countByEmailStartingWith(String emailPrefix);
}
