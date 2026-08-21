package Lumo.lumo_backend.domain.home.service;

import Lumo.lumo_backend.domain.encouragement.commandLineRunner.EncouragementTextLoader;
import Lumo.lumo_backend.domain.encouragement.entity.Encouragement;
import Lumo.lumo_backend.domain.home.dto.HomeResponseDTO;
import Lumo.lumo_backend.domain.member.dto.MemberRespDTO.GetMissionRecordRespDTO;
import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.service.MemberService;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.domain.todo.service.ToDoService;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HomeService {

    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final ToDoService toDoService;
    private final EncouragementTextLoader encouragementTextLoader;

    public HomeResponseDTO get(Long memberId, LocalDate today) {
        Member persistedMember = getPersistedMember(memberId);

        Encouragement encouragement = encouragementTextLoader.getTodayEncouragement();
        List<String> todo = toDoService.findTodayThreeToDo(persistedMember, today);
        GetMissionRecordRespDTO missionRecord = memberService.getMissionRecord(persistedMember);

        return HomeResponseDTO.builder()
                .encouragement(encouragement.getContent())
                .todo(todo)
                .missionRecord(missionRecord)
                .build();
    }

    private Member getPersistedMember(Long memberId) {
        // (G-6) 이 서비스는 원래부터 회원을 다시 조회하고 있었다. 인증 필터의 조회가 사라진 것과 별개로
        // 여기서는 엔티티 필드가 필요하므로 실제 로드가 맞다.
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
    }
}
