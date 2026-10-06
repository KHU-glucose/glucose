package com.glucoselog.intake;

import java.util.List;

public record RecognitionResponse(boolean isFoodPhoto, List<RecognizedFoodItemResponse> items, Boolean likelyConsumedAll) {
}
