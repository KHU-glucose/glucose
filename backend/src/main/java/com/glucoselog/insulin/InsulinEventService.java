package com.glucoselog.insulin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.glucoselog.common.ApiException;
import com.glucoselog.common.Cursor;
import com.glucoselog.common.CursorCodec;
import com.glucoselog.common.PageResponse;

@Service
public class InsulinEventService {

    private final InsulinEventRepository repository;

    public InsulinEventService(InsulinEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public InsulinEventResponse create(UUID userId, InsulinEventRequest request) {
        InsulinEvent event = new InsulinEvent(userId, request.occurredAt(), request.units(), request.kind());
        return InsulinEventResponse.from(repository.save(event));
    }

    @Transactional
    public InsulinEventResponse update(UUID userId, UUID eventId, InsulinEventRequest request) {
        InsulinEvent event = findOwned(userId, eventId);
        event.update(request.occurredAt(), request.units(), request.kind(), Instant.now());
        return InsulinEventResponse.from(repository.save(event));
    }

    @Transactional(readOnly = true)
    public InsulinEventResponse get(UUID userId, UUID eventId) {
        return InsulinEventResponse.from(findOwned(userId, eventId));
    }

    @Transactional
    public void delete(UUID userId, UUID eventId) {
        repository.delete(findOwned(userId, eventId));
    }

    @Transactional(readOnly = true)
    public PageResponse<InsulinEventResponse> list(UUID userId, String cursor, int limit) {
        Cursor decoded = cursor != null ? CursorCodec.decode(cursor) : null;
        List<InsulinEvent> page = decoded != null
                ? repository.findPageAfterCursor(userId, decoded.time(), decoded.id(), PageRequest.of(0, limit))
                : repository.findByUserIdOrderByOccurredAtDescIdDesc(userId, PageRequest.of(0, limit));

        List<InsulinEventResponse> items = page.stream().map(InsulinEventResponse::from).toList();
        String nextCursor = page.size() == limit
                ? CursorCodec.encode(page.get(page.size() - 1).getOccurredAt(), page.get(page.size() - 1).getId())
                : null;
        return new PageResponse<>(items, nextCursor);
    }

    private InsulinEvent findOwned(UUID userId, UUID eventId) {
        return repository.findByIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSULIN_EVENT_NOT_FOUND", "인슐린 기록을 찾을 수 없습니다"));
    }
}
