package com.glucoselog.intake;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
public class IntakeService {

    private final IntakeRepository intakeRepository;
    private final FoodCatalogRepository foodCatalogRepository;

    public IntakeService(IntakeRepository intakeRepository, FoodCatalogRepository foodCatalogRepository) {
        this.intakeRepository = intakeRepository;
        this.foodCatalogRepository = foodCatalogRepository;
    }

    @Transactional
    public IntakeResponse create(UUID userId, IntakeRequest request) {
        Intake intake = new Intake(userId, request.photoId(), request.context(), request.occurredAt());
        intake.replaceItems(buildItems(request.items()));
        return IntakeResponse.from(intakeRepository.save(intake));
    }

    @Transactional
    public IntakeResponse update(UUID userId, UUID intakeId, IntakeRequest request) {
        Intake intake = findOwned(userId, intakeId);
        intake.update(request.context(), request.occurredAt(), Instant.now());
        intake.replaceItems(buildItems(request.items()));
        return IntakeResponse.from(intakeRepository.save(intake));
    }

    @Transactional(readOnly = true)
    public IntakeResponse get(UUID userId, UUID intakeId) {
        return IntakeResponse.from(findOwned(userId, intakeId));
    }

    @Transactional
    public void delete(UUID userId, UUID intakeId) {
        intakeRepository.delete(findOwned(userId, intakeId));
    }

    @Transactional(readOnly = true)
    public PageResponse<IntakeResponse> list(UUID userId, String cursor, int limit) {
        Cursor decoded = cursor != null ? CursorCodec.decode(cursor) : null;
        List<Intake> page = decoded != null
                ? intakeRepository.findPageAfterCursor(userId, decoded.time(), decoded.id(), PageRequest.of(0, limit))
                : intakeRepository.findByUserIdOrderByOccurredAtDescIdDesc(userId, PageRequest.of(0, limit));

        List<IntakeResponse> items = page.stream().map(IntakeResponse::from).toList();
        String nextCursor = page.size() == limit
                ? CursorCodec.encode(page.get(page.size() - 1).getOccurredAt(), page.get(page.size() - 1).getId())
                : null;
        return new PageResponse<>(items, nextCursor);
    }

    private Intake findOwned(UUID userId, UUID intakeId) {
        return intakeRepository.findByIdAndUserId(intakeId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INTAKE_NOT_FOUND", "기록을 찾을 수 없습니다"));
    }

    private List<IntakeItem> buildItems(List<IntakeItemRequest> requests) {
        List<IntakeItem> items = new ArrayList<>();
        int order = 0;
        for (IntakeItemRequest request : requests) {
            items.add(buildItem(request, order++));
        }
        return items;
    }

    /**
     * category_hint를 요청에서 보냈으면 사용자가 직접 분류를 정한 것으로 보고 tags도 요청값(없으면 빈 목록)을
     * 그대로 쓴다. category_hint가 없으면 둘 다 food_catalog 값을 쓴다. tags만 따로 비어있는지로 override
     * 여부를 판단하면 "사용자가 일부러 태그를 다 지움"과 "태그를 안 보냄"을 구분할 수 없어 category_hint
     * 하나로 override 여부를 판단한다. 당류는 항상 코드(카탈로그)가 계산한다.
     */
    private IntakeItem buildItem(IntakeItemRequest request, int sortOrder) {
        FoodCatalog catalog = foodCatalogRepository.findByNameIgnoreCase(request.name()).orElse(null);
        boolean userOverride = request.categoryHint() != null;

        FoodCategoryHint categoryHint = userOverride
                ? request.categoryHint()
                : (catalog != null ? catalog.getCategoryHint() : null);
        List<Tag> tags = userOverride
                ? (request.tags() != null ? request.tags() : List.of())
                : (catalog != null ? catalog.getTags() : List.of());
        BigDecimal sugarGrams = catalog != null ? catalog.getSugarGrams() : null;

        return new IntakeItem(
                request.name(), request.count(), request.unit(), categoryHint, tags, sugarGrams, catalog, sortOrder);
    }
}
