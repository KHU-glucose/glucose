package com.glucoselog.intake;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** List&lt;Tag&gt;를 "HIGH_FAT,HIGH_CARB" 같은 콤마 구분 문자열 컬럼 하나로 저장한다. */
@Converter
public class TagListConverter implements AttributeConverter<List<Tag>, String> {

    @Override
    public String convertToDatabaseColumn(List<Tag> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return attribute.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public List<Tag> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .map(Tag::valueOf)
                .collect(Collectors.toList());
    }
}
