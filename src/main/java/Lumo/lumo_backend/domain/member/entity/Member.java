package Lumo.lumo_backend.domain.member.entity;

import Lumo.lumo_backend.domain.alarm.entity.Alarm;
import Lumo.lumo_backend.domain.member.entity.memberEnum.Login;
import Lumo.lumo_backend.domain.member.entity.memberEnum.MemberRole;
import Lumo.lumo_backend.domain.routine.entity.Routine;
import Lumo.lumo_backend.domain.setting.entity.Feedback;
import Lumo.lumo_backend.domain.setting.entity.MemberDevice;
import Lumo.lumo_backend.domain.setting.entity.MemberStat;
import Lumo.lumo_backend.domain.setting.entity.memberSetting.MemberSetting;
import Lumo.lumo_backend.domain.todo.entity.ToDo;
import Lumo.lumo_backend.global.BaseEntity.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;

import java.util.ArrayList;
import java.util.List;

@Entity
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@DynamicInsert
// (A-2 / M-13) email 은 인증된 모든 요청이 타는 최고 빈도 조회 키인데 인덱스가 없어 풀스캔이었고,
// DB 유니크 제약도 없어 signIn 의 "조회 후 저장" 사이에서 중복 가입 레이스가 열려 있었다.
// unique 제약은 유니크 인덱스를 만들므로 성능과 정합성이 한 번에 해결된다.
//
// @Column(unique = true) 대신 제약 이름을 명시한다 — 생성된 이름(UK_xxxxxx)은
// 마이그레이션 스크립트에서 참조할 수 없고 오류 메시지 추적도 어렵다.
@Table(
        name = "member",
        uniqueConstraints = @UniqueConstraint(name = "uk_member_email", columnNames = "email")
)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Login login;

    @Enumerated(value = EnumType.STRING)
    private MemberRole role = MemberRole.USER;

    @Email
    @Column(nullable = false, length = 50)
    private String email;

    @Column(nullable = false, length = 50)
    private String username;

    private String password;

    @Column (nullable = false)
    @ColumnDefault("0")
    private Integer missionSuccessRate; // 이번 달 달성률

    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer consecutiveSuccessCnt; // 미션 연속 성공 수

    @Column(nullable = false)
    @ColumnDefault("false")
    private Boolean isProUpgraded;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Alarm> alarmList = new ArrayList<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ToDo> toDoList = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL,  orphanRemoval = true, fetch = FetchType.LAZY)
    private MemberSetting setting;

    @OneToOne(cascade = CascadeType.ALL,  orphanRemoval = true, fetch = FetchType.LAZY)
    private MemberStat stat;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Routine> routineList = new ArrayList<>();

    @OneToMany(mappedBy = "member")
    private List<Feedback> feedbackList = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private List<MemberDevice> deviceList = new ArrayList<>();

    public static Member create(String email, String username, String password, Login login, MemberRole role) {
        Member member = Member.builder()
                .email(email)
                .username(username)
                .password(password)
                .consecutiveSuccessCnt(0)
                .isProUpgraded(false)
                .missionSuccessRate (0)
                .login(login)
                .role(role)
                .build();

        member.setting = MemberSetting.createDefault();
        member.stat = MemberStat.createDefault();

        return member;
    }

    /**
     * 미션 연속 성공 횟수 초기화
     */
    public void initConsecutiveSuccessCnt() {
        this.consecutiveSuccessCnt = 0;
    }

    /**
     * 미션 연속 성공 횟수 증가
     */
    public void incrementConsecutiveSuccessCnt() {
        this.consecutiveSuccessCnt++;
    }

    /**
     * 이번 달 미션 달성률 업데이트
     * @param successCount 이번 달 성공 횟수
     * @param totalCount 이번 달 시도 횟수
     */
    public void updateMissionSuccessRate(int successCount, int totalCount) {
        if (totalCount == 0) {
            this.missionSuccessRate = 0;
        } else {
            this.missionSuccessRate = (int) Math.round((double) successCount / totalCount * 100);
        }
    }

    public void updatePassword (String password){
        this.password = password;
    }
}