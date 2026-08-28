package Lumo.lumo_backend.domain.routine.service;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.domain.routine.dto.RoutineRespDTO;
import Lumo.lumo_backend.domain.routine.entity.Routine;
import Lumo.lumo_backend.domain.routine.exception.RoutineException;
import Lumo.lumo_backend.domain.routine.repository.RoutineRepository;
import Lumo.lumo_backend.domain.routine.status.RoutineErrorCode;
import Lumo.lumo_backend.domain.subroutine.converter.SubroutineConverter;
import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RoutineService {

    private final MemberRepository memberRepository;
    private final RoutineRepository routineRepository;

    @Transactional
    public RoutineRespDTO.CreateRoutineDTO createRoutine(Long memberId, String title) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        Routine routine = new Routine(title, member);
        return RoutineRespDTO.CreateRoutineDTO.builder().id(routineRepository.save(routine).getId()).build();
    }

    public List<RoutineRespDTO.GetRoutineDTO> getRoutine(Long memberId) {

        List<Routine> routineList = routineRepository.findAllByMember_Id(memberId);

        return routineList.stream()
                .map(routine ->
                        RoutineRespDTO.GetRoutineDTO.builder()
                                .routineId(routine.getId())
                                .routineTitle(routine.getTitle())
                                .subroutineList(SubroutineConverter.toSubroutineDTO(routine))
                                .build()
                )
                .toList();
    }


    @Transactional
    public void deleteRoutine(Long memberId, Long routineId) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        Member reqMember = memberRepository.findById(member.getId()).orElseThrow(() -> new GeneralException(MemberErrorCode.CANT_FOUND_MEMBER));
        Routine routine = routineRepository.findByIdAndMember_Id(routineId, reqMember.getId()).orElseThrow(() -> new RoutineException(RoutineErrorCode.ROUTINE_NOT_FOUND));

        ///  deleteById(routineId) vs delete(Routine) ?
        routineRepository.deleteById(routine.getId());
    }

    @Transactional
    public void renameRoutine(Long memberId, Long routineId, String title) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        Member reqMember = memberRepository.findById(member.getId()).orElseThrow(() -> new GeneralException(MemberErrorCode.CANT_FOUND_MEMBER));
        Routine routine = routineRepository.findByIdAndMember_Id(routineId, reqMember.getId()).orElseThrow(() -> new RoutineException(RoutineErrorCode.ROUTINE_NOT_FOUND));

        routine.renameRoutine(title);
    }

}