package Lumo.lumo_backend.domain.subroutine.service;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.domain.routine.entity.Routine;
import Lumo.lumo_backend.domain.routine.exception.RoutineException;
import Lumo.lumo_backend.domain.routine.repository.RoutineRepository;
import Lumo.lumo_backend.domain.routine.status.RoutineErrorCode;
import Lumo.lumo_backend.domain.subroutine.entity.Subroutine;
import Lumo.lumo_backend.domain.subroutine.exception.SubroutineException;
import Lumo.lumo_backend.domain.subroutine.repository.SubroutineRepository;
import Lumo.lumo_backend.domain.subroutine.status.SubroutineErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubroutineService {

    /*
    *
    * 관리하고자 하는 서브루틴을 보유하고 있는 사용자 인지에 대한 검증이 필요!
    *
    * */

    private final RoutineRepository routineRepository;
    private final MemberRepository memberRepository;
    private final SubroutineRepository subroutineRepository;

    @Transactional
    public Long createSubroutine (Long memberId, Long routineId, String title) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        Member member1 = memberRepository.findById(member.getId()).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        Routine routine = routineRepository.findById(routineId).orElseThrow(() -> new RoutineException(RoutineErrorCode.ROUTINE_NOT_FOUND));
        Subroutine savedSubroutine = subroutineRepository.save(new Subroutine(title, routine));
        routine.addSubroutine(savedSubroutine);

        return savedSubroutine.getId();
    }

    @Transactional
    public void deleteSubroutine (Long memberId, Long subroutineId){
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        Member member1 = memberRepository.findById(member.getId()).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        subroutineRepository.deleteById(subroutineId); ///  deleteById vs delete?
    }

    @Transactional
    public void renameSubroutine(Long memberId, Long subroutineId, String title) {
        Subroutine subroutine = subroutineRepository.findById(subroutineId).orElseThrow(() -> new SubroutineException(SubroutineErrorCode.SUBROUTINE_NOT_FOUND));
        subroutine.renameSubroutine(title);
    }

    @Transactional
    public void checkSubroutine ( Long memberId,  Long subroutineId) {
        Subroutine subroutine = subroutineRepository.findById(subroutineId).orElseThrow(() -> new SubroutineException(SubroutineErrorCode.SUBROUTINE_NOT_FOUND));
        subroutine.checkSubroutine();
        subroutine.increateCnt();
    }

}
