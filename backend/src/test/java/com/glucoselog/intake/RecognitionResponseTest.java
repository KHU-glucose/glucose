package com.glucoselog.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** 인식 결과 대분류 전달과 기존 job.result 호환성을 DB/외부 API 없이 검증한다. */
class RecognitionResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void 대분류를_전달하면서_음식명과_기존분류는_유지한다() throws Exception {
        RecognitionResponse response = mapper.readValue("""
                {"is_food_photo":true,"items":[
                  {"name":"초콜릿","food_group":"간식","count":3,"unit":"조각",
                   "category_hint":"FAST_SUGAR","tags":["HIGH_FAT"],"confidence":"high",
                   "packaged_product":null}],"likely_consumed_all":null,"meta":{}}
                """, RecognitionResponse.class);

        var food = response.items().getFirst();
        assertThat(food.name()).isEqualTo("초콜릿");
        assertThat(food.foodGroup()).isEqualTo("간식");
        assertThat(food.categoryHint()).isEqualTo(FoodCategoryHint.FAST_SUGAR);
        assertThat(food.count()).isEqualTo(3);
        var json = mapper.readTree(mapper.writeValueAsString(response)).get("items").get(0);
        assertThat(json.get("food_group").asText()).isEqualTo("간식");
        assertThat(json.get("name").asText()).isEqualTo("초콜릿");
    }

    @Test
    void 대분류_없는_기존결과는_null로_전달한다() throws Exception {
        RecognitionResponse response = mapper.readValue("""
                {"is_food_photo":true,"items":[
                  {"name":"김밥","count":null,"unit":"개","category_hint":"MEAL",
                   "tags":[],"confidence":"high"}],"likely_consumed_all":null}
                """, RecognitionResponse.class);

        assertThat(response.items().getFirst().foodGroup()).isNull();
        var json = mapper.readTree(mapper.writeValueAsString(response)).get("items").get(0);
        assertThat(json.has("food_group")).isTrue();
        assertThat(json.get("food_group").isNull()).isTrue();
    }
}
