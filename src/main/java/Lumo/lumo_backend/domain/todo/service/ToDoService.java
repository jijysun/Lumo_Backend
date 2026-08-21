package Lumo.lumo_backend.domain.todo.service;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.domain.todo.dto.request.ToDoCreateRequestDTO;
import Lumo.lumo_backend.domain.todo.dto.request.ToDoUpdateRequestDTO;
import Lumo.lumo_backend.domain.todo.dto.response.ToDoResponseDTO;
import Lumo.lumo_backend.domain.todo.entity.ToDo;
import Lumo.lumo_backend.domain.todo.exception.ToDoException;
import Lumo.lumo_backend.domain.todo.repository.ToDoRepository;
import Lumo.lumo_backend.domain.todo.status.ToDoErrorCode;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputText;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToDoService {

    private final ToDoRepository toDoRepository;
    private final MemberRepository memberRepository;
    private final OpenAIClient openAIClient;

    @Transactional
    public ToDoResponseDTO create(Long memberId, ToDoCreateRequestDTO toDoCreateRequestDTO) {
        Member persistedMember = getPersistedMember(memberId);

        ToDo toDo = ToDo.builder()
                .member(persistedMember)
                .eventDate(toDoCreateRequestDTO.eventDate())
                .content(toDoCreateRequestDTO.content())
                .build();

        ToDo savedToDo = toDoRepository.save(toDo);
        return ToDoResponseDTO.from(savedToDo);
    }

    @Transactional
    public ToDoResponseDTO update(Long memberId, Long toDoId, ToDoUpdateRequestDTO toDoUpdateRequestDTO) {
        Member persistedMember = getPersistedMember(memberId);

        ToDo toDo = toDoRepository.findById(toDoId)
                .orElseThrow(() -> new ToDoException(ToDoErrorCode.NOT_FOUND));

        if (toDo.getMember() != persistedMember) {
            throw new ToDoException(ToDoErrorCode.ACCESS_DENIED);
        }

        String content = toDoUpdateRequestDTO.content();
        toDo.update(content);

        return ToDoResponseDTO.from(toDo);
    }

    @Transactional
    public void delete(Long memberId, Long toDoId) {
        Member persistedMember = getPersistedMember(memberId);

        ToDo toDo = toDoRepository.findById(toDoId)
                .orElseThrow(() -> new ToDoException(ToDoErrorCode.NOT_FOUND));

        if (toDo.getMember() != persistedMember) {
            throw new ToDoException(ToDoErrorCode.ACCESS_DENIED);
        }

        toDoRepository.delete(toDo); //hard delete
    }

    @Transactional(readOnly = true)
    public List<ToDoResponseDTO> findToDoListByEventDate(Long memberId, LocalDate eventDate) {
        Member persistedMember = getPersistedMember(memberId);

        return toDoRepository.findAllByMemberAndEventDate(persistedMember, eventDate);
    }

    @Transactional(readOnly = true)
    public List<String> findTodayThreeToDo(Member persistedMember, LocalDate today) {

        return toDoRepository.findTodayThreeToDo(persistedMember.getId(), today);
    }

    @Transactional(readOnly = true)
    public String getBriefing(Long memberId, LocalDate today) {
        Member persistedMember = getPersistedMember(memberId);

        List<String> toDoList = toDoRepository.findContentByMemberAndEventDate(persistedMember, today);
        String todo = String.join(", ", toDoList);

        if (persistedMember.getIsProUpgraded()) {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .input("오늘의 할 일: " + (todo.isEmpty() ? "없음" : todo))
                    .model(ChatModel.GPT_5_NANO)
                    .reasoning(Reasoning.builder().effort(ReasoningEffort.MINIMAL).build())
                    .instructions("너는 비서야. 할 일 목록을 바탕으로 오늘의 할 일을 브리핑하는게 너의 일이야. 알람 종료 후에 브리핑을 할거니까 인삿말이나 동기부여, 할 일에 관해 짧게 말을 덧붙이면 좋아. 강조 기호, 리스트 기호는 쓰면 안돼. 줄바꿈을 사용하면 안돼. 존댓말을 사용해.")
                    .build();
            long start = System.currentTimeMillis();
            Response response = openAIClient.responses().create(params);
            long end = System.currentTimeMillis();
            log.info("챗지피티 소요시간: {} ms", end - start);

            return response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(ResponseOutputText::text)
                    .collect(Collectors.joining());
        } else {
            if (todo.isEmpty()) {
                return "오늘은 할 일이 없습니다.";
            }
            return "오늘의 할 일은 " + todo + "입니다.";
        }
    }

    private Member getPersistedMember(Long memberId) {
        // (G-6) 이 서비스는 원래부터 회원을 다시 조회하고 있었다. 인증 필터의 조회가 사라진 것과 별개로
        // 여기서는 엔티티 필드가 필요하므로 실제 로드가 맞다.
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
    }
}
